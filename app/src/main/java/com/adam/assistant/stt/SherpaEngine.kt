package com.adam.assistant.stt

import android.content.Context
import android.util.Log
import com.adam.assistant.llm.ModelDownloader
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Accurate offline STT: Whisper Small.en int8 via sherpa-onnx.
 *
 * Why this model: 244M params, multilingual training → best practical
 * accuracy on Indian accents + names. ~350 MB int8, roughly half of Parakeet,
 * sub-2x base latency, still real-time on flagship SoCs for short commands.
 * Fully offline, proven Android arm64 AAR.
 *
 * Role split with [SttEngine] (Vosk):
 *  - Vosk small: always-on wake-word (cheap) + contact-grammar re-listen.
 *  - Whisper: every command transcription (accuracy where it matters).
 *
 * Threading: the native recognizer is used behind a [Mutex] on Dispatchers.IO.
 * Model files live in `.../files/sherpa-whisper-small.en/`.
 */
class SherpaEngine(private val context: Context) {

    @Volatile private var recognizer: OfflineRecognizer? = null
    private val mutex = Mutex()
    private val downloader = ModelDownloader.get(context)

    /**
     * ALL native recognizer calls (build, decode, release-stream) are confined
     * to this single OS thread. onnxruntime's session crashed with SIGSEGV on
     * the second decode when successive decodes arrived from different pool
     * threads (Dispatchers.IO/Default hop threads per call). Mutex alone only
     * serializes — it does not pin the thread. Single thread + mutex gives
     * strict single-flight on a stable thread.
     */
    private val nativeThread = Executors
        .newSingleThreadExecutor { r -> Thread(r, "sherpa-native") }
        .asCoroutineDispatcher()

    val isReady: Boolean get() = recognizer != null

    suspend fun init() = withContext(Dispatchers.IO) {
        if (recognizer != null) return@withContext
        val dir = try {
            downloader.ensureSherpaModel()
        } catch (t: Throwable) {
            Log.e("Adam", "Sherpa download failed", t)
            null
        } ?: return@withContext
        // OfflineRecognizer loads native .so files: missing ABI slices,
        // non-16KB-aligned libs on Android 15+, or OOM on 400 MB models
        // throw UnsatisfiedLinkError / OutOfMemoryError (Errors, not
        // Exceptions). Catch Throwable so setup degrades to Vosk-only
        // instead of crashing the app.
        try {
            val built = withContext(nativeThread) { buildRecognizer(dir) }
            recognizer = built
            Log.i("Adam", "Sherpa Whisper ready: ${dir.absolutePath}")
        } catch (t: Throwable) {
            Log.e("Adam", "Sherpa init failed (falling back to Vosk)", t)
            recognizer = null
        }
    }

    private fun buildRecognizer(dir: File): OfflineRecognizer {
        val whisper = OfflineWhisperModelConfig().apply {
            encoder = File(dir, ModelDownloader.SHERPA_ENCODER_FILE).absolutePath
            decoder = File(dir, ModelDownloader.SHERPA_DECODER_FILE).absolutePath
            language = "en"
            task = "transcribe"
        }
        val model = OfflineModelConfig().apply {
            this.whisper = whisper
            tokens = File(dir, ModelDownloader.SHERPA_TOKENS_FILE).absolutePath
            modelType = "whisper"
            // 2 intra-op threads: plenty for short commands on a flagship,
            // lower memory, and less exposure to onnxruntime threading bugs.
            numThreads = 2
        }
        val config = OfflineRecognizerConfig().apply {
            featConfig = FeatureConfig().apply {
                sampleRate = 16000
                featureDim = 80
            }
            modelConfig = model
        }
        // Model files live on external storage (absolute paths), NOT in APK
        // assets — so AssetManager MUST be null here. Passing context.assets
        // makes sherpa-onnx resolve absolute paths as APK assets, fail the
        // read, and LOG(FATAL)-abort the entire process (uncatchable SIGABRT;
        // this was the "dies right after dependencies installed" crash).
        // See https://github.com/k2-fsa/sherpa-onnx/issues/2562
        return OfflineRecognizer(null, config)
    }

    /**
     * Transcribes 16 kHz mono float samples ([-1, 1]).
     * Returns trimmed text, or "" on failure/empty.
     * Runs on the dedicated native thread (see [nativeThread]).
     */
    suspend fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String =
        withContext(nativeThread) {
            val rec = recognizer ?: return@withContext ""
            if (samples.isEmpty()) return@withContext ""
            mutex.withLock {
                var stream: com.k2fsa.sherpa.onnx.OfflineStream? = null
                try {
                    stream = rec.createStream()
                    stream.acceptWaveform(samples, sampleRate)
                    rec.decode(stream)
                    rec.getResult(stream).text?.trim() ?: ""
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e("Adam", "Sherpa decode failed", t)
                    ""
                } finally {
                    try { stream?.release() } catch (_: Throwable) { }
                }
            }
        }

    fun release() {
        try { recognizer?.release() } catch (_: Throwable) { }
        recognizer = null
        try { nativeThread.close() } catch (_: Throwable) { }
    }
}
