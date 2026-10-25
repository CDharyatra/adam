package com.adam.assistant.core

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Short non-verbal audio cues that tell the user what Adam is doing.
 *
 * Uses the built-in [ToneGenerator] so no sound files need to ship in the APK.
 * Each cue is a small sequence of tones, easily distinguishable by ear.
 */
class AudioCue {

    private var tone: ToneGenerator? = null

    fun init() {
        tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
    }

    fun release() {
        tone?.release()
        tone = null
    }

    /** Two short ascending beeps — "I heard the wake word." */
    fun wakeDetected() {
        val t = tone ?: return
        t.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed({ t.startTone(ToneGenerator.TONE_PROP_BEEP2, 120) }, 160)
    }

    /** One soft beep — "Got your command, working on it." */
    fun commandHeard() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
    }

    /** Short descending pair — "Sorry, something went wrong." */
    fun error() {
        val t = tone ?: return
        t.startTone(ToneGenerator.TONE_PROP_NACK, 200)
        android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed({ t.startTone(ToneGenerator.TONE_PROP_NACK, 200) }, 260)
    }

    /** Brief soft tone — "I caught you mid-sentence, switching back to listening." */
    fun bargeIn() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
    }
}
