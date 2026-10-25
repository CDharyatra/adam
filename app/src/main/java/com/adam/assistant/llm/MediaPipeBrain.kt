package com.adam.assistant.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The on-device LLM brain (Gemma3-1B int4 .task via MediaPipe LLM Inference).
 *
 * This is the stage the architecture research pointed at: every major
 * assistant routes ambiguous utterances through a small local model, with
 * deterministic layers (templates/regex) kept as the fast path in front of
 * it — Siri's System Orchestrator pattern. The router ([LlmEngine]) only
 * calls here when explicit/learned/template matching missed, so the ~1–2 s
 * inference cost is paid solely for utterances nothing else understands.
 *
 * Reliability comes from three layers, not from trusting the model:
 *  1. low temperature + tiny output budget (JSON object, nothing else),
 *  2. [LlmEngine] rejects anything that doesn't parse to a non-UNKNOWN
 *     command (falls through to fuzzy),
 *  3. [com.adam.assistant.core.Session] confirm/slot policy still gates
 *     every risky action afterwards.
 *
 * Inactive ([isReady] false) until the .task file is downloaded — the app
 * behaves exactly as before until then.
 */
class MediaPipeBrain(private val context: Context) {

    companion object {
        /** Output budget: a command JSON object is < 150 tokens. */
        const val MAX_OUTPUT_TOKENS = 256

        /** Context window: compact system prompt + one utterance fits easily. */
        const val MAX_TOKENS = 2048

        /** Deterministic JSON needs near-zero sampling noise. */
        const val TEMPERATURE = 0.1f
        const val TOP_K = 8

        const val GENERATE_TIMEOUT_MS = 60_000L

        /**
         * Compact system prompt (~200 tokens). The full schema lives in
         * [LlmEngine]; the brain only needs the contract + two anchors.
         */
        const val BRAIN_SYSTEM = """
You are Adam's command parser. Reply with ONLY one JSON object, no prose.
Schema: {"action":"<OPEN_APP|PLAY_MUSIC|SEND_MESSAGE|MAKE_CALL|OPEN_URL|SEARCH_WEB|SET_ALARM|SET_TIMER|ADD_CALENDAR_EVENT|SEND_EMAIL|TAKE_PHOTO|NAVIGATE|OPEN_SETTINGS|ADJUST_VOLUME|READ_SCREEN|LONG_CLICK|OPEN_NOTIFICATIONS|CLOSE_NOTIFICATIONS|SWIPE|CALCULATE|NOTE|CLICK|SCROLL|TYPE|BACK|HOME|RECENTS|REPLY|UNKNOWN>","target":"<main target>","params":{}}
Examples:
User: "could you please open spotify for me"
{"action":"OPEN_APP","target":"Spotify","params":{}}
User: "remind me to buy milk" (no reminder action exists)
{"action":"NOTE","target":"buy milk","params":{}}
Unsure -> {"action":"UNKNOWN","target":"","params":{}}. REPLY only for chit-chat, message in params.message.
"""
    }

    @Volatile private var llm: LlmInference? = null
    private val mutex = Mutex()
    private val downloader = ModelDownloader.get(context)

    val isReady: Boolean get() = llm != null

    suspend fun init(): Boolean = withContext(Dispatchers.IO) {
        if (llm != null) return@withContext true
        val modelFile = try {
            downloader.ensureLlmTask()
        } catch (t: Throwable) {
            Log.e("Adam", "LLM download failed", t)
            null
        } ?: return@withContext false
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(MAX_TOKENS)
                .build()
            llm = LlmInference.createFromOptions(context, options)
            Log.i("Adam", "MediaPipe LLM ready: ${modelFile.absolutePath}")
            true
        } catch (t: Throwable) {
            Log.e("Adam", "LLM init failed (template pipeline stays)", t)
            llm = null
            false
        }
    }

    /**
     * One JSON command object for [userText], or null on any failure/timeout.
     * Single-flight: sessions are created per request but generations never
     * overlap (MediaPipe sessions are not thread-safe).
     */
    suspend fun complete(userText: String): String? {
        val engine = llm ?: return null
        return mutex.withLock {
            withTimeoutOrNull(GENERATE_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    var session: LlmInferenceSession? = null
                    cont.invokeOnCancellation {
                        try { session?.close() } catch (_: Throwable) { }
                    }
                    try {
                        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                            .setTemperature(TEMPERATURE)
                            .setTopK(TOP_K)
                            .build()
                        val s = LlmInferenceSession.createFromOptions(engine, sessionOptions)
                            ?: run {
                                if (cont.isActive) cont.resume(null)
                                return@suspendCancellableCoroutine
                            }
                        session = s
                        s.addQueryChunk("$BRAIN_SYSTEM\nUser: \"$userText\"")
                        val sb = StringBuilder()
                        s.generateResponseAsync { partial, done ->
                            sb.append(partial)
                            if (done) {
                                try { s.close() } catch (_: Throwable) { }
                                if (cont.isActive) cont.resume(sb.toString())
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        try { session?.close() } catch (_: Throwable) { }
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
    }

    fun release() {
        try { llm?.close() } catch (_: Throwable) { }
        llm = null
    }
}
