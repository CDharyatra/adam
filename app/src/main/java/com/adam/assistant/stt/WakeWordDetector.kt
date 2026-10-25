package com.adam.assistant.stt

import kotlinx.coroutines.flow.Flow

/**
 * Always-on wake-word detection (the "ears" before the brain wakes up).
 *
 * Mirrors how every major assistant splits this off from full speech
 * recognition: a tiny, cheap spotter runs continuously, and only a hit
 * starts the expensive transcription pipeline.
 *
 * Implementations: [SherpaKeywordSpotter] (primary — Zipformer transducer,
 * ~10 MB, true keyword spotting) and the Vosk substring path inside
 * [SttEngine] (fallback — full decode, costs more CPU per hour of idle).
 */
interface WakeWordDetector {

    /** True once the underlying model is loaded and spotting works. */
    val isReady: Boolean

    /**
     * Loads (downloading first if needed) the spotter model.
     * @return true when spotting is usable afterwards.
     */
    suspend fun init(): Boolean

    /**
     * Streams wake phrases as they are spotted. The [keywords] argument is
     * advisory — file-backed spotters bake the set in at [init] time and
     * report whichever phrase fires.
     */
    fun listen(keywords: List<String>): Flow<String>

    fun release()
}
