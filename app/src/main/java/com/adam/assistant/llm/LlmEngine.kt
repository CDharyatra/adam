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
 * model is downloaded) the engine falls back to a lightweight regex parser so
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

    // ── System prompt ────────────────────────────────────────────────────────
    private val systemPrompt = """
        You are Adam, a fully offline Android assistant running on the user's phone.
        All actions are executed locally. Nothing leaves the device.
        Always respond with ONLY a valid JSON object — no prose, no markdown, no code fences.
        Schema:
        {
          "action": "<one of: OPEN_APP|PLAY_MUSIC|SEND_MESSAGE|MAKE_CALL|OPEN_URL|SEARCH_WEB|SET_ALARM|SET_TIMER|ADD_CALENDAR_EVENT|SEND_EMAIL|TAKE_PHOTO|NAVIGATE|OPEN_SETTINGS|ADJUST_VOLUME|READ_SCREEN|LONG_CLICK|OPEN_NOTIFICATIONS|CLOSE_NOTIFICATIONS|SWIPE|CALCULATE|NOTE|CLICK|SCROLL|TYPE|BACK|HOME|RECENTS|REPLY|UNKNOWN>",
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
        User: "Long press on settings"
        {"action":"LONG_CLICK","target":"settings","params":{}}
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
     *  2. precise patterns,
     *  3. previously learned patterns ([MemoryStore]),
     *  4. fuzzy keyword scoring for garbled transcripts,
     *  5. novelty: note it down (+ optional one-shot reasoning), static reply.
     */
    private val templateEngine: TemplateEngine by lazy { TemplateEngine(BUILT_IN_TEMPLATES) }

    suspend fun process(userText: String): AssistantCommand =
        processDetailed(userText).command

    /**
     * Hybrid routing, fastest first:
     *  1. native LLM (when the engine AAR is ever bundled),
     *  2. ordered pipeline: explicit → learned → template → on-device
     *     LLM brain → fuzzy,
     *  3. novelty: note it down (+ optional one-shot reasoning), static reply.
     *
     * The brain sits AFTER the deterministic layers (Siri-router pattern):
     * cheap confident matches never pay inference cost, and the brain only
     * sees utterances nothing else understands. Its output must still parse
     * to a non-UNKNOWN command or the stage abstains to fuzzy.
     */
    suspend fun processDetailed(userText: String): IntentMatch = withContext(Dispatchers.Default) {
        if (isNativeReady) {
            android.util.Log.i("Adam", "Engine: native-llm conf=1.00")
            val cmd = clarify(parseJson(nativeChat(nativeHandle, userText)))
            return@withContext IntentMatch(cmd, 1f, MatchSource.NATIVE, "native")
        }
        val pipeline = IntentPipeline(
            listOf(
                IntentPipeline.Stage { t ->
                    explicitParse(t)?.let {
                        IntentPipeline.StageResult(it, 0.95f, MatchSource.EXPLICIT)
                    }
                },
                IntentPipeline.Stage { t ->
                    MemoryStore.findBest(context, t)?.let { (pattern, score) ->
                        IntentPipeline.StageResult(
                            MemoryStore.toCommandJson(pattern),
                            (0.6f + 0.3f * score).toFloat().coerceIn(0f, 1f),
                            MatchSource.LEARNED
                        )
                    }
                },
                IntentPipeline.Stage { t ->
                    templateEngine.match(t)?.let { m ->
                        IntentPipeline.StageResult(
                            templateToJson(m),
                            m.confidence,
                            MatchSource.TEMPLATE
                        )
                    }
                },
                IntentPipeline.Stage { t ->
                    if (!brain.isReady) return@Stage null
                    val raw = try {
                        brain.complete(t)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        null
                    }?.takeIf { it.isNotBlank() } ?: return@Stage null
                    val cmd = parseJson(raw)
                    if (cmd.action == Action.UNKNOWN) return@Stage null
                    android.util.Log.i("Adam", "Engine: mediapipe-llm raw=${raw.take(160)}")
                    // Re-serialize through esc() so downstream sees clean JSON.
                    val paramsJson = cmd.params.entries.joinToString(",") { (k, v) ->
                        "\"${esc(k)}\":\"${esc(v)}\""
                    }
                    val json = "{\"action\":\"${cmd.action.name}\"," +
                        "\"target\":\"${esc(cmd.target)}\",\"params\":{$paramsJson}}"
                    IntentPipeline.StageResult(json, 0.8f, MatchSource.MEDIAPIPE)
                },
                IntentPipeline.Stage { t ->
                    fuzzyParse(t)?.let {
                        IntentPipeline.StageResult(it, 0.55f, MatchSource.FUZZY)
                    }
                }
            )
        )
        pipeline.route(userText)?.let { r ->
            android.util.Log.i("Adam", "Engine: ${r.source.name.lowercase()} conf=${"%.2f".format(r.confidence)}")
            val cmd = clarify(parseJson(r.json))
            return@withContext IntentMatch(cmd, r.confidence, r.source, "matched")
        }
        if (reasoning.isAvailable) {
            val learned = try { reasoning.reason(userText) } catch (_: Exception) { null }
            if (learned != null) {
                MemoryStore.addLearned(context, learned)
                android.util.Log.i("Adam", "Engine: learned-new ${learned.keywords}")
            }
        }
        android.util.Log.i("Adam", "Engine: novel (noted for learning)")
        val cmd = clarify(parseJson(novelReplyJson(userText)))
        IntentMatch(cmd, 0f, MatchSource.NOVEL, "unknown")
    }

    /** Template slots → command JSON (slot "target" becomes the target). */
    private fun templateToJson(m: TemplateMatch): String {
        val target = m.slots["target"] ?: ""
        val params = m.template.fixedParams.toMutableMap()
        m.slots.forEach { (k, v) ->
            if (k != "target" && v.isNotBlank()) params[k] = v
        }
        val sb = StringBuilder()
        sb.append("{\"action\":\"").append(m.template.action.name)
        sb.append("\",\"target\":\"").append(esc(target)).append("\",\"params\":{")
        params.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(',')
            sb.append('"').append(esc(k)).append("\":\"").append(esc(v)).append('"')
        }
        return sb.append("}}").toString()
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
        return try {
            // Extract first JSON object from the response
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start == -1 || end == -1) return AssistantCommand(Action.UNKNOWN)
            val json = JSONObject(raw.substring(start, end + 1))
            val action = runCatching { Action.valueOf(json.getString("action")) }
                .getOrDefault(Action.UNKNOWN)
            val target = json.optString("target", "")
            val paramsJson = json.optJSONObject("params")
            val params = buildMap {
                paramsJson?.keys()?.forEach { k -> put(k, paramsJson.getString(k)) }
            }
            val status = when (json.optString("status", "ready").lowercase()) {
                "needs_clarification" -> AssistantCommand.PlanStatus.NEEDS_CLARIFICATION
                "unsupported" -> AssistantCommand.PlanStatus.UNSUPPORTED
                else -> AssistantCommand.PlanStatus.READY
            }
            val missing = json.optJSONArray("missing_fields")?.let { arr ->
                List(arr.length()) { i -> arr.optString(i) }.filter { it.isNotBlank() }
            } ?: emptyList()
            val question = json.optString("question", "").takeIf { it.isNotBlank() }
                ?: json.optString("clarifying_question", "").takeIf { it.isNotBlank() }
            AssistantCommand(action, target, params, status, missing, question)
        } catch (e: Exception) {
            AssistantCommand(Action.UNKNOWN)
        }
    }

    // ── Regex fallback (no native lib) ───────────────────────────────────────

    /**
     * Precise patterns only. Returns null when nothing matches so the caller
     * can try learned patterns, fuzzy scoring, then the novelty flow.
     */
    private fun explicitParse(text: String): String? {
        val lower = text.lowercase().trim()
        return when {
            lower.startsWith("open ") || lower.startsWith("launch ") -> {
                val rest = text.removePrefix("open").removePrefix("launch").trim()
                // Multi-step orders ("open swiggy and search pizza") are novel:
                // don't execute half of one — let them flow to learning.
                if (" and " in rest.lowercase() || " then " in rest.lowercase()) null
                else if (looksLikeUrl(rest)) """{"action":"OPEN_URL","target":"$rest","params":{}}"""
                else """{"action":"OPEN_APP","target":"$rest","params":{}}"""
            }
            lower.startsWith("search ") || lower.startsWith("google ") -> {
                val q = text.removePrefix("search").removePrefix("google").trim()
                """{"action":"SEARCH_WEB","target":"$q","params":{}}"""
            }
            lower.startsWith("navigate to ") || lower.startsWith("directions to ") -> {
                val q = text.removePrefix("navigate to").removePrefix("directions to").trim()
                """{"action":"NAVIGATE","target":"$q","params":{}}"""
            }
            lower.startsWith("set an alarm") || lower.startsWith("set alarm") || lower.startsWith("alarm for ") -> {
                val rest = lower.removePrefix("set an alarm").removePrefix("set alarm").removePrefix("alarm for").trim()
                val tm = Regex("""(\d{1,2})(?::(\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?""").find(rest)
                if (tm != null) {
                    var h = tm.groupValues[1].toInt()
                    val m = tm.groupValues[2].toIntOrNull() ?: 0
                    val ampm = tm.groupValues[3].replace(".", "")
                    if (ampm == "pm" && h < 12) h += 12
                    if (ampm == "am" && h == 12) h = 0
                    """{"action":"SET_ALARM","target":"$rest","params":{"hour":"$h","minute":"$m"}}"""
                } else """{"action":"REPLY","target":"","params":{"message":"I couldn't understand the time."}}"""
            }
            lower.startsWith("set a timer") || lower.startsWith("set timer") || lower.startsWith("timer for ") -> {
                val rest = text.removePrefix("set a timer").removePrefix("set timer").removePrefix("timer for").trim()
                val secs = parseDurationSeconds(rest)
                if (secs != null) """{"action":"SET_TIMER","target":"$rest","params":{"seconds":"$secs"}}"""
                else """{"action":"REPLY","target":"","params":{"message":"I couldn't understand the duration."}}"""
            }
            lower.startsWith("add ") && (lower.contains("calendar") || lower.contains("event") || lower.contains("reminder")) -> {
                val rest = text.removePrefix("add").trim()
                """{"action":"ADD_CALENDAR_EVENT","target":"$rest","params":{"description":"$rest"}}"""
            }
            lower.startsWith("email ") || lower.startsWith("send email") || lower.startsWith("send an email") -> {
                val rest = text.removePrefix("send an email").removePrefix("send email").removePrefix("email").trim()
                val to = rest.substringBefore(" ").trim()
                val subject = Regex("""subject\s+([^,]+)""").find(rest)?.groupValues?.get(1) ?: ""
                val body = Regex("""body\s+(.+)$""").find(rest)?.groupValues?.get(1) ?: ""
                """{"action":"SEND_EMAIL","target":"$to","params":{"subject":"$subject","body":"$body"}}"""
            }
            lower.startsWith("take a photo") || lower.startsWith("take photo") || lower == "open camera" ->
                """{"action":"TAKE_PHOTO","target":"","params":{}}"""
            lower.startsWith("call ") || lower.startsWith("dial ") -> {
                val who = text.removePrefix("call").removePrefix("dial").trim()
                """{"action":"MAKE_CALL","target":"$who","params":{}}"""
            }
            lower.startsWith("note ") || lower.startsWith("remember ") -> {
                val rest = text.removePrefix("note").removePrefix("remember").trim()
                """{"action":"NOTE","target":"$rest","params":{}}"""
            }
            lower.startsWith("calculate ") || lower.startsWith("what is ") || lower.startsWith("what's ") -> {
                // Case-insensitive strip: the transcript keeps its original case
                // ("What's 12 plus 5?"), so case-sensitive removePrefix left the
                // whole question as the expression. Also drop sentence-final
                // punctuation — the executor rejects it as non-arithmetic.
                val expr = text.replaceFirst(
                    Regex("^(calculate|what is|what's)\\s+", RegexOption.IGNORE_CASE), ""
                ).trim().trim('?', '!', '.', ',', ';', ':').trim()
                """{"action":"CALCULATE","target":"$expr","params":{}}"""
            }
            lower.startsWith("open settings") || lower.startsWith("go to settings") -> {
                val page = text.removePrefix("open settings").removePrefix("go to settings").trim()
                """{"action":"OPEN_SETTINGS","target":"$page","params":{}}"""
            }
            lower.contains("volume up") || lower.contains("louder") ->
                """{"action":"ADJUST_VOLUME","target":"","params":{"direction":"up"}}"""
            lower.contains("volume down") || lower.contains("quieter") ->
                """{"action":"ADJUST_VOLUME","target":"","params":{"direction":"down"}}"""
            lower.contains("mute") ->
                """{"action":"ADJUST_VOLUME","target":"","params":{"direction":"mute"}}"""
            lower.startsWith("read ") && (lower.contains("screen") || lower.contains("this")) ->
                """{"action":"READ_SCREEN","target":"","params":{}}"""
            lower.startsWith("long press") || lower.startsWith("long-press") -> {
                val lbl = text.removePrefix("long press").removePrefix("long-press").removePrefix("on").trim()
                """{"action":"LONG_CLICK","target":"$lbl","params":{}}"""
            }
            lower.contains("open notifications") || lower.contains("show notifications") ->
                """{"action":"OPEN_NOTIFICATIONS","target":"","params":{}}"""
            lower.contains("close notifications") || lower.contains("dismiss notifications") ->
                """{"action":"CLOSE_NOTIFICATIONS","target":"","params":{}}"""
            lower.startsWith("swipe ") -> {
                val dir = lower.removePrefix("swipe").trim()
                """{"action":"SWIPE","target":"$dir","params":{"direction":"$dir"}}"""
            }
            lower.contains("play ") && (lower.contains(" on spotify") || lower.contains(" by ")) -> {
                // Parse on the lowercased copy: transcripts vary in case and a
                // case-sensitive cut left "on spotify" inside the song title.
                var remainder = lower.substringAfter("play ").trim()
                if (" on spotify" in remainder) remainder = remainder.substringBefore(" on spotify").trim()
                val byIndex = remainder.indexOf(" by ")
                val song = if (byIndex >= 0) remainder.substring(0, byIndex).trim() else remainder
                val artist = if (byIndex >= 0) remainder.substring(byIndex + 4).trim() else ""
                """{"action":"PLAY_MUSIC","target":"$song","params":{"artist":"$artist","app":"Spotify"}}"""
            }
            (lower.startsWith("text ") || lower.startsWith("message ") || lower.startsWith("send ")) &&
                    lower.contains(" on whatsapp") -> {
                val contact = lower.substringAfter(" ").substringBefore(" on whatsapp").trim()
                    .removePrefix("text ").removePrefix("message ").removePrefix("send ")
                    .removePrefix("an ").trim()
                val body = if (lower.contains(":")) lower.substringAfter(":").trim() else ""
                """{"action":"SEND_MESSAGE","target":"$contact","params":{"app":"WhatsApp","body":"$body"}}"""
            }
            lower == "go back" || lower == "back" ->
                """{"action":"BACK","target":"","params":{}}"""
            lower.contains("scroll down") ->
                """{"action":"SCROLL","target":"","params":{"direction":"down"}}"""
            lower.contains("scroll up") ->
                """{"action":"SCROLL","target":"","params":{"direction":"up"}}"""
            lower.startsWith("go home") || lower == "home" ->
                """{"action":"HOME","target":"","params":{}}"""
            lower.contains("recent apps") || lower == "recents" ->
                """{"action":"RECENTS","target":"","params":{}}"""
            else -> null
        }
    }

    // ── Fuzzy scoring (garbled transcripts) ──────────────────────────────────
    //
    // The speech model mangles words ("play yeh duniya" → "played suffer and",
    // "what's twelve" → "watch twelve"). Rigid prefixes can't survive that, so
    // this layer scores keyword sets and extracts best-effort slots. Anything
    // still unclear returns null → novelty flow (noted + static reply).

    private val musicVerbs = setOf("play", "played", "playing", "players", "player")
    private val vagueTitles = setOf("", "a", "some", "something", "me", "it", "this", "that", "song", "songs", "music")

    private val numberLex = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
        "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
        "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty",
        "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred",
        "thousand", "million"
    )
    private val opLex = setOf(
        "plus", "minus", "times", "multiplied", "divided", "over", "mod",
        "calculate", "dividedby"
    )

    private fun fuzzyParse(text: String): String? {
        val lower = text.lowercase()
        val words = lower.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()

        // ── music: "played suffer and spotify" → PLAY_MUSIC("suffer") ──
        if (words.any { it in musicVerbs || it == "song" || it == "songs" || it == "music" } &&
            ("spotify" in lower || "song" in words || "music" in words || " by " in lower)
        ) {
            var title = lower
                .replace("on spotify", " ").replace("spotify", " ")
                .replace("players", " ").replace("playing", " ").replace("played", " ")
                .replace("player", " ").replace("play", " ")
                .replace("songs", " ").replace("song", " ").replace("music", " ")
                .replace("please", " ").trim().replace(Regex(" +"), " ")
                .removePrefix("a ").removePrefix("and ").removeSuffix(" and").trim()
            if (title in vagueTitles) {
                // Genuinely vague ("play a song") → just open Spotify.
                return """{"action":"OPEN_APP","target":"Spotify","params":{}}"""
            }
            if (title.isNotBlank()) {
                val byIndex = title.indexOf(" by ")
                val song = if (byIndex >= 0) title.substring(0, byIndex).trim() else title
                val artist = if (byIndex >= 0) title.substring(byIndex + 4).trim() else ""
                if (song.isNotBlank()) {
                    return """{"action":"PLAY_MUSIC","target":"${esc(song)}","params":{"artist":"${esc(artist)}","app":"Spotify"}}"""
                }
            }
        }

        // ── arithmetic: "watch twelve plus five" → CALCULATE ──
        val hasNum = words.any { it in numberLex } || lower.any { it.isDigit() }
        val hasOp = words.any { it in opLex } || "what's" in lower || "what is" in lower
        if (hasNum && hasOp) {
            return """{"action":"CALCULATE","target":"${esc(text)}","params":{}}"""
        }

        // ── messaging: "text shubhanshu that its working" → SEND_MESSAGE ──
        if (words.any { it == "text" || it == "message" || it == "messages" || it == "send" || it == "sending" || it == "whatsapp" }) {
            var rest = lower
                .replace("on whatsapp", " ").replace("whatsapp", " ")
                .replace("messages", " ").replace("message", " ")
                .replace("sending", " ").replace("send", " ").replace("text", " ")
                .replace("please", " ")
                .trim().replace(Regex(" +"), " ")
            val splitAt = listOf(" that ", " saying ", ":").mapNotNull { sep ->
                rest.indexOf(sep).takeIf { it >= 0 }?.let { it to sep.length }
            }.minByOrNull { it.first }
            if (splitAt != null) {
                val contact = rest.substring(0, splitAt.first).trim()
                    .removePrefix("to ").removePrefix("an ").trim()
                val body = rest.substring(splitAt.first + splitAt.second).trim()
                    .removePrefix(":").trim()
                if (contact.isNotBlank() && body.isNotBlank()) {
                    return """{"action":"SEND_MESSAGE","target":"${esc(contact)}","params":{"app":"WhatsApp","body":"${esc(body)}"}}"""
                }
            }
            if (rest.isNotBlank()) {
                // Contact but no message yet — clarify() will ask for the body.
                val contact = rest.removePrefix("to ").trim()
                if (contact.isNotBlank()) {
                    return """{"action":"SEND_MESSAGE","target":"${esc(contact)}","params":{"app":"WhatsApp","body":""}}"""
                }
            }
        }

        // ── open/launch variants the precise branch missed ──
        val openMatch = Regex("""^(open|opened|opens|launch|launched|start|starts)\s+(.+)$""").find(lower)
        if (openMatch != null) {
            val rest = openMatch.groupValues[2].trim()
            if (rest.isNotBlank() && " and " !in rest && " then " !in rest && !looksLikeUrl(rest)) {
                return """{"action":"OPEN_APP","target":"${esc(rest)}","params":{}}"""
            }
        }

        return null
    }

    /** Static reply for genuinely novel commands (also flags them for learning). */
    private fun novelReplyJson(text: String): String {
        val clean = esc(text.trim())
        return """{"action":"REPLY","target":"","params":{"message":"That's new to me — I've noted it down and I'll learn it for next time.","pendingLearn":"$clean"}}"""
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * Deterministic slot validation (the policy side of the action plan).
     * Turns "right shape, missing piece" parses into clarification requests
     * instead of letting them fail deep in the executor — e.g. a message with
     * no body becomes "What should I say to X?" rather than a shrug.
     */
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

    private fun looksLikeUrl(s: String): Boolean =
        s.startsWith("http://") || s.startsWith("https://") ||
        Regex(""".+\.(com|org|net|io|co|ai|app|dev|me|info|biz)\b""", RegexOption.IGNORE_CASE).containsMatchIn(s)

    private fun parseDurationSeconds(text: String): Int? {
        val t = text.lowercase()
        var total = 0; var found = false
        Regex("""(\d+)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)""").findAll(t).forEach { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@forEach
            val u = m.groupValues[2]
            total += when {
                u.startsWith("hour") || u.startsWith("hr") -> n * 3600
                u.startsWith("min")                       -> n * 60
                else                                      -> n
            }
            found = true
        }
        if (!found) Regex("""(\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { total = it * 60; found = true }
        return if (found) total else null
    }

    // ── JNI declarations ─────────────────────────────────────────────────────

    private external fun nativeCreateEngine(modelDir: String, systemPrompt: String): Long
    private external fun nativeChat(handle: Long, userMessage: String): String
    private external fun nativeResetChat(handle: Long)
    private external fun nativeDestroyEngine(handle: Long)
}
