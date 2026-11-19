package com.adam.assistant.llm

import android.content.Context
import com.adam.assistant.core.AssistantCommand
import com.adam.assistant.core.AssistantCommand.Action
import com.adam.assistant.core.IntentMatch
import com.adam.assistant.core.MatchSource
import com.adam.assistant.core.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Wraps the MLC LLM native library.
 *
 * MLC LLM exposes a C++ engine via JNI. The AAR/SO is bundled in assets and
 * extracted at first run. We call the engine with a structured prompt that
 * instructs the model to always reply with a single JSON object.
 *
 * If the native library is unavailable (e.g. during unit tests or before the
 * model is downloaded) the engine falls back to the deterministic router so
 * the rest of the app remains functional.
 */
class LlmEngine(private val context: Context) {

    private var nativeHandle: Long = 0L
    private var isNativeReady = false

    val downloader = ModelDownloader.get(context)
    val downloadProgress = downloader.progress
    val sourceUrl: String get() = downloader.sourceUrl

    /** On-device LLM brain (MediaPipe .task). Inactive until its model file exists. */
    val brain = MediaPipeBrain(context)

    private val deterministic = DeterministicRouter()

    // ── System prompt ────────────────────────────────────────────────────────
    private val systemPrompt = """
        You are Adam, a fully offline Android assistant running on the user's phone.
        All actions are executed locally. Nothing leaves the device.
        Always respond with ONLY a valid JSON object — no prose, no markdown, no code fences.
        Schema:
        {
          "action": "<one of: OPEN_APP|PLAY_MUSIC|SEND_MESSAGE|MAKE_CALL|OPEN_URL|SEARCH_WEB|SET_ALARM|SET_TIMER|ADD_CALENDAR_EVENT|SEND_EMAIL|TAKE_PHOTO|NAVIGATE|OPEN_SETTINGS|ADJUST_VOLUME|READ_SCREEN|OPEN_NOTIFICATIONS|CLOSE_NOTIFICATIONS|SWIPE|CALCULATE|NOTE|SCROLL|BACK|HOME|RECENTS|REPLY|UNKNOWN>",
          "target": "<primary target string, e.g. app/contact/song/URL/place>",
          "params": { "<key>": "<value>" }
        }
        Recognised params keys per action:
          PLAY_MUSIC         -> artist, app (default Spotify)
          SEND_MESSAGE       -> app, body, phone
          MAKE_CALL          -> phone
          SET_ALARM          -> hour (0-23), minute (0-59)
          SET_TIMER          -> seconds (integer)
          ADD_CALENDAR_EVENT -> description, start (epoch ms), title
          SEND_EMAIL         -> subject, body
          OPEN_SETTINGS      -> (target IS the page: wifi/bluetooth/display/sound/apps/battery/storage/date/accessibility/developer/location/network/nfc/security/language/accounts)
          ADJUST_VOLUME      -> direction (up/down/mute/unmute/max)
          SCROLL             -> direction (up/down)
          SWIPE              -> direction (up/down/left/right)
        Examples:
        User: "Open Spotify"
        {"action":"OPEN_APP","target":"Spotify","params":{}}
        User: "Play Bohemian Rhapsody by Queen on Spotify"
        {"action":"PLAY_MUSIC","target":"Bohemian Rhapsody","params":{"artist":"Queen","app":"Spotify"}}
        User: "Text John on WhatsApp: I'll be there in 5 minutes"
        {"action":"SEND_MESSAGE","target":"John","params":{"app":"WhatsApp","body":"I'll be there in 5 minutes"}}
        User: "Call John"
        {"action":"MAKE_CALL","target":"John","params":{}}
        User: "Open youtube.com"
        {"action":"OPEN_URL","target":"youtube.com","params":{}}
        User: "Search for best pizza recipes"
        {"action":"SEARCH_WEB","target":"best pizza recipes","params":{}}
        User: "Set an alarm for 7 30 AM"
        {"action":"SET_ALARM","target":"","params":{"hour":"7","minute":"30"}}
        User: "Set a timer for 5 minutes"
        {"action":"SET_TIMER","target":"5 minutes","params":{"seconds":"300"}}
        User: "Add calendar event Team meeting tomorrow at 2 PM"
        {"action":"ADD_CALENDAR_EVENT","target":"Team meeting","params":{"description":"Team meeting"}}
        User: "Email john at example.com subject Hello body Hi there"
        {"action":"SEND_EMAIL","target":"john@example.com","params":{"subject":"Hello","body":"Hi there"}}
        User: "Take a photo"
        {"action":"TAKE_PHOTO","target":"","params":{}}
        User: "Navigate to the Eiffel Tower"
        {"action":"NAVIGATE","target":"Eiffel Tower","params":{}}
        User: "Open Wi-Fi settings"
        {"action":"OPEN_SETTINGS","target":"wifi","params":{}}
        User: "Volume up"
        {"action":"ADJUST_VOLUME","target":"","params":{"direction":"up"}}
        User: "Read my screen"
        {"action":"READ_SCREEN","target":"","params":{}}
        User: "Open notifications"
        {"action":"OPEN_NOTIFICATIONS","target":"","params":{}}
        User: "Swipe down"
        {"action":"SWIPE","target":"","params":{"direction":"down"}}
        User: "What is 12 times 7"
        {"action":"CALCULATE","target":"12*7","params":{}}
        User: "Note: buy groceries"
        {"action":"NOTE","target":"buy groceries","params":{}}
        User: "Go back"
        {"action":"BACK","target":"","params":{}}
        User: "Scroll down"
        {"action":"SCROLL","target":"","params":{"direction":"down"}}
        Rules:
          - Reply with a single JSON object and NOTHING else.
          - If unsure, use REPLY with a short message in params.message, or UNKNOWN.
    """.trimIndent()

    // ── Initialisation ───────────────────────────────────────────────────────

    companion object {
        /**
         * Set true only when the MLC engine AAR is bundled
         * (app/libs/mlc4j-release.aar). While false, [init] never downloads
         * the 1.8 GB model — the template/fuzzy pipeline owns everything.
         */
        const val NATIVE_LLM_ENABLED = false

        /**
         * Params a model may never set: the app resolves "package" from the
         * app name and "phone"/"contact" from a matched contact or the user's
         * own follow-up. Stripping them keeps a fabricated target from ever
         * reaching the executor (Task 5).
         */
        private val reservedParamKeys = setOf("package", "phone", "contact_id", "phone_id")
    }

    suspend fun init() = withContext(Dispatchers.IO) {
        // On-device LLM brain (Gemma .task via MediaPipe): independent of the
        // native MLC path. Fail-soft — template pipeline owns everything until
        // the model file is present.
        try {
            brain.init()
        } catch (t: Throwable) {
            android.util.Log.e("Adam", "Brain init failed (template pipeline stays)", t)
        }
        if (!NATIVE_LLM_ENABLED) {
            isNativeReady = false
            return@withContext
        }
        try {
            System.loadLibrary("mlc_llm_jni")
        } catch (e: UnsatisfiedLinkError) {
            // Native lib not present — fallback mode active
            isNativeReady = false
            return@withContext
        }

        val modelDir = downloader.ensureModel() ?: run {
            isNativeReady = false
            return@withContext
        }
        nativeHandle = nativeCreateEngine(modelDir.absolutePath, systemPrompt)
        isNativeReady = nativeHandle != 0L
    }

    /**
     * On-demand reasoning backend (Option 2). Defaults to a no-op until a real
     * backend is plugged in; see [ReasoningEngine] for the contract.
     */
    var reasoning: ReasoningEngine = NoOpReasoningEngine()

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Hybrid routing, fastest first:
     *  1. native LLM (when the engine AAR is ever bundled),
     *  2. ordered pipeline: explicit → learned → template → on-device
     *     LLM brain → fuzzy,
     *  3. novelty: static reply (no unsupported learning promise).
     *
     * The brain sits AFTER the deterministic layers (Siri-router pattern):
     * cheap confident matches never pay inference cost, and the brain only
     * sees utterances nothing else understands. Its output must still parse
     * to a non-UNKNOWN command or the stage abstains to fuzzy.
     */
    suspend fun process(userText: String): AssistantCommand =
        processDetailed(userText).command

    suspend fun processDetailed(userText: String): IntentMatch = withContext(Dispatchers.Default) {
        if (!deterministic.isAllowed(userText)) {
            android.util.Log.i("Adam", "Engine: rejected (negated/compound/oversize)")
            return@withContext novelty(userText, "rejected")
        }
        if (isNativeReady) {
            val command = deterministic.applyClarifications(
                parseJson(nativeChat(nativeHandle, userText))
            )
            if (structurallyValid(command)) {
                android.util.Log.i("Adam", "Engine: native-llm conf=1.00")
                return@withContext IntentMatch(
                    command, 1f, MatchSource.NATIVE, "native", userText
                )
            }
        }
        val pipeline = IntentPipeline(
            listOf(
                IntentPipeline.Stage { t -> deterministic.explicit(t)?.toCandidate() },
                IntentPipeline.Stage { t ->
                    val scored = MemoryStore.findBest(context, t) ?: return@Stage null
                    val pattern = scored.pattern
                    if (MemoryStore.isSensitiveAction(pattern.action)) return@Stage null
                    val action = runCatching { Action.valueOf(pattern.action) }.getOrNull()
                        ?: return@Stage null
                    val command = deterministic.applyClarifications(
                        AssistantCommand(action, pattern.target, pattern.params)
                    )
                    IntentPipeline.Candidate(
                        command = command,
                        confidence = (0.6f + 0.3f * scored.score)
                            .toFloat()
                            .coerceIn(0f, 1f),
                        source = MatchSource.LEARNED,
                        reasonCode = "learned"
                    )
                },
                IntentPipeline.Stage { t -> deterministic.template(t)?.toCandidate() },
                IntentPipeline.Stage { t ->
                    if (!brain.isReady) return@Stage null
                    val raw = try {
                        brain.complete(t)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        null
                    }?.takeIf { it.isNotBlank() } ?: return@Stage null
                    val command = deterministic.applyClarifications(parseJson(raw))
                    if (!structurallyValid(command)) return@Stage null
                    android.util.Log.i("Adam", "Engine: mediapipe-llm proposal accepted")
                    IntentPipeline.Candidate(
                        command = command,
                        confidence = 0.8f,
                        source = MatchSource.MEDIAPIPE,
                        reasonCode = "proposal"
                    )
                },
                IntentPipeline.Stage { t -> deterministic.fuzzy(t)?.toCandidate() }
            )
        )
        pipeline.route(userText)?.let { candidate ->
            android.util.Log.i(
                "Adam",
                "Engine: ${candidate.source.name.lowercase()} " +
                    "conf=${"%.2f".format(candidate.confidence)}"
            )
            val command = clarify(candidate.command)
            return@withContext IntentMatch(
                command, candidate.confidence, candidate.source,
                candidate.reasonCode, userText, candidate.spans
            )
        }
        if (reasoning.isAvailable) {
            val learned = try {
                reasoning.reason(userText)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (learned != null) {
                MemoryStore.addLearned(context, learned)
                android.util.Log.i("Adam", "Engine: learned-new ${learned.keywords}")
            }
        }
        android.util.Log.i("Adam", "Engine: novel (no executable match)")
        novelty(userText, "unknown")
    }

    fun release() {
        try { brain.release() } catch (_: Throwable) { }
        if (isNativeReady) {
            nativeResetChat(nativeHandle)
            nativeDestroyEngine(nativeHandle)
        }
        nativeHandle = 0L
        isNativeReady = false
    }

    // ── JSON → Command ───────────────────────────────────────────────────────

    private fun parseJson(raw: String): AssistantCommand {
        val root = try {
            StrictJson.parseOrNull(raw)
        } catch (_: Throwable) {
            null
        } ?: return AssistantCommand(Action.UNKNOWN)
        if (root !is StrictJson.JsonValue.Obj) return AssistantCommand(Action.UNKNOWN)
        val members = root.members

        val allowed = setOf(
            "action", "target", "params", "status",
            "missing_fields", "question", "clarifying_question"
        )
        if (members.keys.any { it !in allowed }) {
            return AssistantCommand(Action.UNKNOWN)
        }

        val action = when (val a = members["action"]) {
            is StrictJson.JsonValue.Str ->
                runCatching { Action.valueOf(a.value) }.getOrNull()
            else -> null
        } ?: return AssistantCommand(Action.UNKNOWN)

        if (action == Action.UNKNOWN) return AssistantCommand(Action.UNKNOWN)

        val target = when (val t = members["target"]) {
            is StrictJson.JsonValue.Str -> t.value
            null -> ""
            else -> return AssistantCommand(Action.UNKNOWN)
        }

        val params: Map<String, String> = when (val p = members["params"]) {
            null -> emptyMap()
            is StrictJson.JsonValue.Obj -> {
                val map = LinkedHashMap<String, String>()
                for ((key, value) in p.members) {
                    // Reserved keys are system-owned: the app resolves the app
                    // package from the app name and the phone from a matched
                    // contact or the user's own follow-up. A model may never
                    // invent them (Task 5: no fabricated package/phone/id).
                    if (key in reservedParamKeys) continue
                    val text = value as? StrictJson.JsonValue.Str ?: return AssistantCommand(
                        Action.UNKNOWN
                    )
                    if (text.value.isNotEmpty()) map[key] = text.value
                }
                map
            }
            else -> return AssistantCommand(Action.UNKNOWN)
        }

        val status = when (val s = members["status"]) {
            is StrictJson.JsonValue.Str -> mapStatus(s.value)
            null -> AssistantCommand.PlanStatus.READY
            else -> return AssistantCommand(Action.UNKNOWN)
        }

        val missing = when (val m = members["missing_fields"]) {
            null -> emptyList()
            is StrictJson.JsonValue.Arr -> {
                val fields = ArrayList<String>(m.items.size)
                for (item in m.items) {
                    val text = item as? StrictJson.JsonValue.Str
                        ?: return AssistantCommand(Action.UNKNOWN)
                    if (text.value.isNotBlank()) fields += text.value
                }
                fields
            }
            else -> return AssistantCommand(Action.UNKNOWN)
        }

        val question = when (val q = members["question"]) {
            is StrictJson.JsonValue.Str -> q.value.takeIf { it.isNotBlank() }
            null -> null
            else -> return AssistantCommand(Action.UNKNOWN)
        } ?: when (val q = members["clarifying_question"]) {
            is StrictJson.JsonValue.Str -> q.value.takeIf { it.isNotBlank() }
            null -> null
            else -> return AssistantCommand(Action.UNKNOWN)
        }

        return AssistantCommand(action, target, params, status, missing, question)
    }

    private fun mapStatus(raw: String): AssistantCommand.PlanStatus =
        when (raw.lowercase()) {
            "needs_clarification" -> AssistantCommand.PlanStatus.NEEDS_CLARIFICATION
            "unsupported" -> AssistantCommand.PlanStatus.UNSUPPORTED
            else -> AssistantCommand.PlanStatus.READY
        }

    /**
     * Deterministic slot validation. Keeps "right shape, missing piece"
     * parses in clarification instead of failing deep in the executor.
     */
    private fun structurallyValid(command: AssistantCommand): Boolean {
        if (command.action == Action.UNKNOWN) return false
        if (command.status == AssistantCommand.PlanStatus.UNSUPPORTED) return false
        if (command.status == AssistantCommand.PlanStatus.NEEDS_CLARIFICATION &&
            command.missingFields.isEmpty()
        ) {
            return false
        }
        return true
    }

    private fun clarify(cmd: AssistantCommand): AssistantCommand {
        if (cmd.status != AssistantCommand.PlanStatus.READY) return cmd
        if (cmd.action == AssistantCommand.Action.SEND_MESSAGE &&
            cmd.params["body"].isNullOrBlank()
        ) {
            val who = cmd.target.ifBlank { "them" }
            return cmd.copy(
                status = AssistantCommand.PlanStatus.NEEDS_CLARIFICATION,
                missingFields = listOf("body"),
                question = "What should I say to $who?"
            )
        }
        return cmd
    }

    private fun novelty(userText: String, reason: String): IntentMatch {
        val command = clarify(parseJson(novelReplyJson()))
        return IntentMatch(
            command = command,
            confidence = 0f,
            source = MatchSource.NOVEL,
            reasonCode = reason,
            rawText = userText
        )
    }

    private fun novelReplyJson(): String {
        val params = JSONObject().put("message", "That's new to me. I can't do that yet.")
        return JSONObject()
            .put("action", Action.REPLY.name)
            .put("target", "")
            .put("params", params)
            .toString()
    }

    // ── JNI declarations ─────────────────────────────────────────────────────

    private external fun nativeCreateEngine(modelDir: String, systemPrompt: String): Long
    private external fun nativeChat(handle: Long, userMessage: String): String
    private external fun nativeResetChat(handle: Long)
    private external fun nativeDestroyEngine(handle: Long)
}
