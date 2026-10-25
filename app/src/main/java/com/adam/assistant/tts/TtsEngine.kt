package com.adam.assistant.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Wraps Android's built-in TextToSpeech engine.
 * Works fully offline when the device has a TTS engine installed
 * (all modern Android devices do). Users can install higher-quality
 * voices (e.g. Google TTS, eSpeak NG via F-Droid) in system settings.
 */
class TtsEngine(private val context: Context) {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tts: TextToSpeech? = null
    private var isReady = false

    // Bounded: if the system TTS never calls back (disabled engine, missing
    // voice data), startup used to hang forever on "dependencies being
    // installed". Time out instead and let the app continue silently.
    suspend fun init(): Boolean = try {
        withTimeoutOrNull(12_000L) {
            suspendCancellableCoroutine { cont ->
                tts = TextToSpeech(context) { status ->
                    isReady = status == TextToSpeech.SUCCESS
                    if (isReady) {
                        ioScope.launch {
                            tts?.language = Locale.US
                            tts?.setSpeechRate(1.0f)
                        }
                    }
                    if (cont.isActive) cont.resume(isReady)
                }
            }
        } ?: false
    } catch (t: Throwable) {
        android.util.Log.e("Adam", "TTS init failed", t)
        false
    }

    /** Speaks [text] and suspends until the utterance finishes (max 30 s). */
    suspend fun speak(text: String) {
        if (!isReady || tts == null) return
        try {
            withTimeoutOrNull(30_000L) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val id = "adam_${System.currentTimeMillis()}"
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?): Unit = Unit
                        override fun onDone(utteranceId: String?): Unit {
                            if (utteranceId == id && cont.isActive) cont.resume(Unit)
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?): Unit {
                            if (cont.isActive) cont.resume(Unit)
                        }
                        override fun onError(utteranceId: String?, errorCode: Int): Unit {
                            if (cont.isActive) cont.resume(Unit)
                        }
                        override fun onStop(utteranceId: String?, interrupted: Boolean): Unit {
                            if (cont.isActive) cont.resume(Unit)
                        }
                    })
                    val rc: Int? = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
                    if (rc == TextToSpeech.ERROR && cont.isActive) {
                        cont.resume(Unit)
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("Adam", "TTS speak failed", t)
        }
    }

    fun stop() = tts?.stop()

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        ioScope.cancel()
    }
}
