package com.adam.assistant.stt

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.adam.assistant.llm.ModelDownloader
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

/**
 * True keyword spotting via sherpa-onnx (streaming Zipformer transducer).
 *
 * Unlike the Vosk fallback — which fully decodes every second of audio and
 * substring-matches the transcript — this runs a ~10 MB spotter that only
 * fires on the enrolled phrases. Closer to Siri/Porcupine architecture:
 * cheap always-on first pass, expensive engines start only on a hit.
 *
 * Threading: the spotter is decoded on ONE dedicated OS thread (same lesson
 * as the Whisper engine — onnxruntime sessions must not hop pool threads).
 * Single consumer by design: the service runs one wake loop at a time.
 *
 * Failure mode is always fail-soft: any problem (missing model, OOV keyword,
 * native error) leaves [isReady] false and the caller keeps the Vosk path.
 */
class SherpaKeywordSpotter(private val context: Context) : WakeWordDetector {

    companion object {
        /** Boost added to keyword arcs; higher = more hits, more false alarms. */
        const val KEYWORDS_SCORE = 1.5f

        /** Activation threshold on the keyword posterior. */
        const val KEYWORDS_THRESHOLD = 0.25f

        private const val SAMPLE_RATE = 16000

        /** Phrases baked into keywords.txt at init. */
        val DEFAULT_PHRASES = listOf("hey adam", "ok adam", "adam")
    }

    @Volatile private var spotter: KeywordSpotter? = null
    private val mutex = Mutex()
    private val nativeThread = Executors
        .newSingleThreadExecutor { r -> Thread(r, "sherpa-kws") }
        .asCoroutineDispatcher()
    private val downloader = ModelDownloader.get(context)

    override val isReady: Boolean get() = spotter != null

    override suspend fun init(): Boolean = withContext(Dispatchers.IO) {
        if (spotter != null) return@withContext true
        val dir = try {
            downloader.ensureKwsModel()
        } catch (t: Throwable) {
            Log.e("Adam", "KWS download failed", t)
            null
        } ?: return@withContext false
        try {
            writeKeywordsFile(dir)
            val built = withContext(nativeThread) { buildSpotter(dir) }
            spotter = built
            Log.i("Adam", "KWS ready: ${dir.absolutePath}")
            true
        } catch (t: Throwable) {
            Log.e("Adam", "KWS init failed (Vosk fallback stays)", t)
            spotter = null
            false
        }
    }

    private fun writeKeywordsFile(dir: File) {
        File(dir, ModelDownloader.KWS_KEYWORDS_FILE)
            .writeText(DEFAULT_PHRASES.joinToString("\n", postfix = "\n"))
    }

    private fun buildSpotter(dir: File): KeywordSpotter {
        val transducer = OnlineTransducerModelConfig().apply {
            encoder = File(dir, ModelDownloader.KWS_ENCODER_FILE).absolutePath
            decoder = File(dir, ModelDownloader.KWS_DECODER_FILE).absolutePath
            joiner = File(dir, ModelDownloader.KWS_JOINER_FILE).absolutePath
        }
        val model = OnlineModelConfig().apply {
            this.transducer = transducer
            tokens = File(dir, ModelDownloader.KWS_TOKENS_FILE).absolutePath
            numThreads = 1
            debug = false
            provider = "cpu"
            modelType = "zipformer"
        }
        val config = KeywordSpotterConfig().apply {
            featConfig = FeatureConfig().apply {
                sampleRate = SAMPLE_RATE
                featureDim = 80
            }
            modelConfig = model
            maxActivePaths = 4
            keywordsFile = File(dir, ModelDownloader.KWS_KEYWORDS_FILE).absolutePath
            keywordsScore = KEYWORDS_SCORE
            keywordsThreshold = KEYWORDS_THRESHOLD
            numTrailingBlanks = 1
        }
        // Disk paths, not APK assets → AssetManager MUST be null. Passing
        // context.assets makes sherpa resolve absolute paths as APK assets,
        // fail the read, and LOG(FATAL)-abort the process (the Whisper crash).
        return KeywordSpotter(null, config)
    }

    override fun listen(keywords: List<String>): Flow<String> = callbackFlow {
        val kws = spotter ?: run { close(); return@callbackFlow }
        val stream = kws.createStream()
        try {
            mutex.withLock {
                micLoop { samples ->
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    kws.decode(stream)
                    if (kws.isReady(stream)) {
                        val hit = kws.getResult(stream).keyword.trim()
                        // Reset so the same stream keeps spotting afterwards.
                        kws.reset(stream)
                        if (hit.isNotBlank()) {
                            Log.i("Adam", "KWS hit: \"$hit\"")
                            trySend(hit)
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e("Adam", "KWS loop failed", t)
            close(t)
        } finally {
            try { stream.release() } catch (_: Throwable) { }
        }
        awaitClose { }
    }.flowOn(nativeThread)

    /**
     * Streams 16 kHz mono float windows ([chunkMs] each) until the flow
     * collector cancels. Fail-fast on dead mics (same hardening as STT).
     */
    private suspend fun micLoop(
        chunkMs: Int = 100,
        onChunk: (FloatArray) -> Unit
    ) {
        val framesPerChunk = SAMPLE_RATE * chunkMs / 1000
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 4
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            try { recorder.release() } catch (_: Exception) { }
            throw IOException("Microphone unavailable (init failed)")
        }
        recorder.startRecording()
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            try { recorder.stop() } catch (_: Exception) { }
            try { recorder.release() } catch (_: Exception) { }
            throw IOException("Microphone unavailable (start failed)")
        }
        val shorts = ShortArray(bufferSize)
        val pending = FloatArray(framesPerChunk)
        var filled = 0
        try {
            while (true) {
                coroutineContext.ensureActive()
                val read = recorder.read(shorts, 0, shorts.size)
                if (read <= 0) continue
                var i = 0
                while (i < read) {
                    val room = framesPerChunk - filled
                    val take = minOf(room, read - i)
                    for (j in 0 until take) {
                        pending[filled + j] = (shorts[i + j] / 32768f).coerceIn(-1f, 1f)
                    }
                    filled += take
                    i += take
                    if (filled == framesPerChunk) {
                        onChunk(pending.copyOf())
                        filled = 0
                    }
                }
            }
        } finally {
            try { recorder.stop() } catch (_: Exception) { }
            try { recorder.release() } catch (_: Exception) { }
        }
    }

    override fun release() {
        try { spotter?.release() } catch (_: Throwable) { }
        spotter = null
        try { nativeThread.close() } catch (_: Throwable) { }
    }
}
