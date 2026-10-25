package com.adam.assistant.stt

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.adam.assistant.llm.ModelDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Offline STT using Vosk.
 *
 * Model setup:
 *   1. Download a Vosk model (e.g. vosk-model-en-us-0.22-lgraph) from
 *      https://alphacephei.com/vosk/models
 *   2. Place the unzipped folder at:
 *      /sdcard/Android/data/com.adam.assistant/files/vosk-model-en-us-lgraph/
 *
 * [listen] emits partial results continuously and a final result when silence
 * is detected (Vosk returns isFinal=true).
 *
 * [listenForWakeWord] runs a recognizer continuously and emits the recognised
 * text every time it contains one of the configured wake phrases. Vosk 0.3.47
 * does not expose a native keyword-spotting API on Android, so we use plain
 * recognition with cheap substring matching — this costs slightly more CPU
 * than a kws model but needs zero extra model files.
 */
class SttEngine(private val context: Context) {

    private var model: Model? = null
    private val sampleRate = 16000
    private val downloader = ModelDownloader.get(context)
    private val sherpa = SherpaEngine(context)

    suspend fun init() = withContext(Dispatchers.IO) {
        val modelDir = downloader.ensureSpeechModel() ?: return@withContext
        // Vosk's native Model() throws on corrupt/incomplete models and can
        // throw UnsatisfiedLinkError / OutOfMemoryError (both are Errors, not
        // Exceptions). An uncaught throw here used to escape AssistantService
        // .start() and crash the whole app right after "dependencies being
        // installed". Fail soft instead: leave the engine not-ready.
        try {
            model = Model(modelDir.absolutePath)
        } catch (t: Throwable) {
            android.util.Log.e("Adam", "Vosk model load failed: ${modelDir.absolutePath}", t)
            try { model?.close() } catch (_: Throwable) { }
            model = null
            return@withContext
        }
        // Accurate model loads (or downloads ~600 MB) in the background of
        // setup; wake-word + fallback work on Vosk until it's ready.
        try { sherpa.init() } catch (t: Throwable) {
            android.util.Log.e("Adam", "Accurate STT init failed (non-fatal)", t)
        }
    }

    val isReady: Boolean get() = model != null

    /** True once Parakeet is loaded — commands use it instead of Vosk. */
    val isAccurateReady: Boolean get() = sherpa.isReady

    fun listen(): Flow<SttResult> =
        if (sherpa.isReady) accurateListen() else voskListen()

    /**
     * Captures one utterance (energy endpointing, ~15 s max).
     * Returns 16 kHz mono floats in [-1, 1]; empty when nothing was heard.
     * Split from [transcribeSamples] so callers can budget recording and
     * decoding separately — a single timeout over both starved transcription
     * whenever recording ran long (continuous speech always did).
     */
    suspend fun captureUtterance(): FloatArray = recordUtterance()

    /**
     * Transcribes previously captured samples with the accurate engine.
     * Returns trimmed text, or "" when unavailable/empty/failed.
     */
    suspend fun transcribeSamples(samples: FloatArray): String {
        if (!sherpa.isReady || samples.isEmpty()) return ""
        val t0 = android.os.SystemClock.elapsedRealtime()
        try {
            val text = sherpa.transcribe(samples)
            android.util.Log.i(
                "Adam",
                "Heard (whisper): \"$text\" (${android.os.SystemClock.elapsedRealtime() - t0} ms)"
            )
            return text
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            android.util.Log.e("Adam", "Accurate STT failed", t)
            return ""
        }
    }

    /**
     * Parakeet path: buffer one utterance (energy endpointing), decode offline.
     * Emits a single final — callers only consume finals, so the Flow contract
     * is unchanged and Vosk partials were never used downstream.
     */
    private fun accurateListen(): Flow<SttResult> = callbackFlow {
        val samples = recordUtterance()
        val text = try {
            sherpa.transcribe(samples)
        } catch (e: Exception) {
            android.util.Log.e("Adam", "Accurate STT failed, falling back", e)
            ""
        }
        android.util.Log.i("Adam", "Heard (whisper): \"$text\"")
        trySend(SttResult(text, isFinal = true))
        close()
    }.flowOn(Dispatchers.IO)

    /**
     * Records until 1.2 s of silence after speech, or 15 s max.
     * Returns 16 kHz mono floats in [-1, 1]. Empty when nothing heard.
     */
    private suspend fun recordUtterance(): FloatArray = withContext(Dispatchers.IO) {
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
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

        val out = ArrayList<Short>(sampleRate * 8)
        val chunk = ShortArray(bufferSize)
        val startMs = android.os.SystemClock.elapsedRealtime()
        var lastSpeechMs = startMs
        var heardSpeech = false
        try {
            while (true) {
                coroutineContext.ensureActive()
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - startMs > 15_000L) break
                val read = recorder.read(chunk, 0, chunk.size)
                if (read <= 0) continue
                var peak = 0
                for (i in 0 until read) {
                    val v = chunk[i].toInt()
                    out.add(chunk[i])
                    val a = if (v < 0) -v else v
                    if (a > peak) peak = a
                }
                if (peak > 800) {
                    heardSpeech = true
                    lastSpeechMs = now
                } else if (heardSpeech && now - lastSpeechMs > 1_200L) {
                    break
                }
            }
        } finally {
            try { recorder.stop() } catch (_: Exception) { }
            try { recorder.release() } catch (_: Exception) { }
        }
        FloatArray(out.size) { (out[it] / 32768f).coerceIn(-1f, 1f) }
    }

    fun voskListen(): Flow<SttResult> = callbackFlow {
        val voskModel = model ?: run { close(); return@callbackFlow }
        val recognizer = Recognizer(voskModel, sampleRate.toFloat())

        audioLoop(shouldContinue = { !isClosedForSend }) { bytes, size ->
            if (recognizer.acceptWaveForm(bytes, size)) {
                // Emit even blank finals: on pure silence Vosk only produces
                // empty results, and the caller needs them to time out / count
                // idle rounds instead of hanging forever.
                val text = JSONObject(recognizer.result).optString("text")
                trySend(SttResult(text, isFinal = true))
            } else {
                val partial = JSONObject(recognizer.partialResult).optString("partial")
                if (partial.isNotBlank()) trySend(SttResult(partial, isFinal = false))
            }
        }

        awaitClose { recognizer.close() }
    }.flowOn(Dispatchers.IO)

    /**
     * Constrained listen: Vosk may only emit words from [grammarJson] (plus
     * "[unk]"). Used when asking "who?" — with the user's contact names as
     * the vocabulary, ambiguous audio snaps onto real names instead of
     * becoming "sure" or "show one shoe".
     */
    fun listenWithGrammar(grammarJson: String): Flow<SttResult> = callbackFlow {
        val voskModel = model ?: run { close(); return@callbackFlow }
        val recognizer = try {
            Recognizer(voskModel, sampleRate.toFloat(), grammarJson)
        } catch (e: Exception) {
            close(e)
            return@callbackFlow
        }

        audioLoop(shouldContinue = { !isClosedForSend }) { bytes, size ->
            if (recognizer.acceptWaveForm(bytes, size)) {
                val text = JSONObject(recognizer.result).optString("text")
                trySend(SttResult(text, isFinal = true))
            } else {
                val partial = JSONObject(recognizer.partialResult).optString("partial")
                if (partial.isNotBlank()) trySend(SttResult(partial, isFinal = false))
            }
        }

        awaitClose { recognizer.close() }
    }.flowOn(Dispatchers.IO)

    fun listenForWakeWord(keywords: List<String>): Flow<String> = callbackFlow {
        val voskModel = model ?: run { close(); return@callbackFlow }
        val recognizer = Recognizer(voskModel, sampleRate.toFloat())
        val lowerKeywords = keywords.map { it.lowercase() }

        audioLoop(shouldContinue = { !isClosedForSend }) { bytes, size ->
            if (recognizer.acceptWaveForm(bytes, size)) {
                val text = JSONObject(recognizer.result).optString("text").trim()
                if (text.isNotBlank()) {
                    val lower = text.lowercase()
                    if (lowerKeywords.any { lower.contains(it) }) {
                        trySend(text)
                    }
                }
            }
        }

        awaitClose { recognizer.close() }
    }.flowOn(Dispatchers.IO)

    private suspend fun audioLoop(
        shouldContinue: () -> Boolean,
        onChunk: (ByteArray, Int) -> Unit
    ) {
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 4
        )
        // Fail fast: an unvalidated recorder yields read errors forever with
        // zero logging (silent permanent deafness). Surface it instead.
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
        val buffer = ShortArray(bufferSize)

        try {
            var errorStreak = 0
            while (shouldContinue()) {
                // Cancellation checkpoint: without this, timeouts can never
                // preempt a blocking read and the listen hangs indefinitely.
                coroutineContext.ensureActive()
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    errorStreak++
                    if (errorStreak >= 100) throw IOException("Microphone read failed ($read)")
                    continue
                }
                errorStreak = 0
                val bytes = ByteArray(read * 2)
                for (i in 0 until read) {
                    bytes[i * 2]     = (buffer[i].toInt() and 0xFF).toByte()
                    bytes[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
                }
                onChunk(bytes, bytes.size)
            }
        } finally {
            try { recorder.stop() } catch (_: Exception) { }
            try { recorder.release() } catch (_: Exception) { }
        }
    }

    fun release() {
        try { sherpa.release() } catch (_: Throwable) { }
        try { model?.close() } catch (_: Throwable) { }
        model = null
    }
}

data class SttResult(val text: String, val isFinal: Boolean)
