package com.adam.assistant.stt

import android.content.Context
import android.util.Log
import com.adam.assistant.llm.ModelDownloader
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Neural voice-activity detection (Silero VAD via sherpa-onnx).
 *
 * Replaces/augments the raw energy gate (`peak > 800`) in utterance capture:
 * a learned speech-vs-noise classifier is what lets real assistants endpoint
 * reliably in noise, and it is the input to the planned speculative/confirm
 * dual-gate endpointing (fast gate starts NLU early, accurate gate commits).
 *
 * Not wired into the capture path yet — lands here first so thresholds can
 * be tuned against logged probabilities before it gates anything.
 */
class VadGate(private val context: Context) {

    companion object {
        /** Silero operates on 32 ms windows at 16 kHz. */
        const val WINDOW_SAMPLES = 512

        /** Default speech threshold; tune per device (0.3–0.7 typical). */
        const val DEFAULT_THRESHOLD = 0.5f
    }

    @Volatile private var vad: Vad? = null
    private val mutex = Mutex()
    private val nativeThread = Executors
        .newSingleThreadExecutor { r -> Thread(r, "sherpa-vad") }
        .asCoroutineDispatcher()
    private val downloader = ModelDownloader.get(context)

    val isReady: Boolean get() = vad != null

    suspend fun init(threshold: Float = DEFAULT_THRESHOLD): Boolean =
        withContext(Dispatchers.IO) {
            if (vad != null) return@withContext true
            val model = try {
                downloader.ensureVadModel()
            } catch (t: Throwable) {
                Log.e("Adam", "VAD model fetch failed", t)
                null
            } ?: return@withContext false
            try {
                val built = withContext(nativeThread) { buildVad(model, threshold) }
                vad = built
                Log.i("Adam", "VAD ready: ${model.absolutePath}")
                true
            } catch (t: Throwable) {
                Log.e("Adam", "VAD init failed (energy gate stays)", t)
                vad = null
                false
            }
        }

    private fun buildVad(model: File, threshold: Float): Vad {
        val silero = SileroVadModelConfig().apply {
            this.model = model.absolutePath
            this.threshold = threshold
            minSilenceDuration = 0.5f
            minSpeechDuration = 0.25f
            windowSize = WINDOW_SAMPLES
            maxSpeechDuration = 20f
        }
        val config = VadModelConfig().apply {
            sileroVadModelConfig = silero
            sampleRate = 16000
            numThreads = 1
            provider = "cpu"
            debug = false
        }
        // Disk path, not APK assets → null AssetManager (Whisper lesson).
        return Vad(null, config)
    }

    /**
     * Speech probability for exactly [WINDOW_SAMPLES] of 16 kHz mono audio.
     * 0f when unavailable — callers treat that as "no opinion", never as
     * silence, so a dead VAD can never mute the assistant.
     */
    suspend fun speechProbability(window: FloatArray): Float =
        withContext(nativeThread) {
            val v = vad ?: return@withContext 0f
            if (window.size != WINDOW_SAMPLES) return@withContext 0f
            mutex.withLock {
                try {
                    v.compute(window)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e("Adam", "VAD compute failed", t)
                    0f
                }
            }
        }

    fun release() {
        try { vad?.release() } catch (_: Throwable) { }
        vad = null
        try { nativeThread.close() } catch (_: Throwable) { }
    }
}
