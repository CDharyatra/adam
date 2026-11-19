package com.adam.assistant.llm

import com.adam.assistant.core.AssistantCommand
import com.adam.assistant.core.AssistantCommand.Action
import com.adam.assistant.core.AssistantCommand.PlanStatus
import com.adam.assistant.core.CommandRouter
import com.adam.assistant.core.IntentMatch
import com.adam.assistant.core.MatchSource
import java.util.Locale

class DeterministicRouter(
    templates: List<Template> = BUILT_IN_TEMPLATES,
    private val maxInputWords: Int = MAX_INPUT_WORDS
) : CommandRouter {

    private data class Parsed(
        val action: Action,
        val target: String = "",
        val params: Map<String, String> = emptyMap(),
        val status: PlanStatus = PlanStatus.READY,
        val missing: List<String> = emptyList(),
        val question: String? = null,
        val spans: Map<String, IntRange> = emptyMap()
    )

    private val templateEngine = TemplateEngine(templates)

    override suspend fun route(text: String): IntentMatch =
        explicit(text) ?: template(text) ?: fuzzy(text) ?: abstain(text)

    fun explicit(text: String): IntentMatch? {
        if (!isAllowed(text)) return null
        val raw = text.trim()
        if (raw.isEmpty()) return null
        val bare = raw.trimEnd('.', '!', '?', ';', ',')
        return exact(raw, bare)
            ?: screen(raw, bare)
            ?: settings(raw, bare)
            ?: volume(raw, bare)
            ?: email(raw)
            ?: message(raw)
            ?: note(raw)
            ?: type(raw)
            ?: playMusic(raw)
            ?: call(raw)
            ?: search(raw)
            ?: navigate(raw)
            ?: alarm(raw)
            ?: timer(raw)
            ?: calendar(raw)
            ?: calculate(raw)
            ?: open(raw)
    }

    fun template(text: String): IntentMatch? {
        if (!isAllowed(text)) return null
        val match = templateEngine.match(text) ?: return null
        val params = match.template.fixedParams.toMutableMap()
        match.slots.forEach { (key, value) ->
            if (key != "target" && value.isNotBlank()) params[key] = value
        }
        val target = sanitizeTarget(match.template.action, match.slots["target"].orEmpty())
        val command = applyClarifications(
            AssistantCommand(match.template.action, target, params)
        )
        return IntentMatch(
            command = command,
            confidence = match.confidence,
            source = MatchSource.TEMPLATE,
            reasonCode = "template",
            rawText = text,
            spans = match.spans
        )
    }

    fun fuzzy(text: String): IntentMatch? {
        if (!isAllowed(text)) return null
        val raw = text.trim()
        val lower = raw.lowercase(Locale.ROOT)
        val words = lower.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        musicFuzzy(raw, lower, words)?.let { return it }
        arithmeticFuzzy(raw, lower, words)?.let { return it }
        messageFuzzy(raw)?.let { return it }
        openFuzzy(raw)?.let { return it }
        return null
    }

    fun isAllowed(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.split(Regex("\\s+")).size > maxInputWords) return false
        if (negation.containsMatchIn(trimmed)) return false
        if (hasUnsupportedCompound(trimmed)) return false
        return true
    }

    fun applyClarifications(command: AssistantCommand): AssistantCommand {
        if (command.status != PlanStatus.READY) return command
        return when {
            command.action == Action.PLAY_MUSIC && isVaguePlay(command.target) ->
                command.copy(
                    status = PlanStatus.NEEDS_CLARIFICATION,
                    missingFields = listOf("target"),
                    question = "What should I play?"
                )
            command.action == Action.SEND_MESSAGE &&
                command.params["body"].isNullOrBlank() -> {
                val who = command.target.ifBlank { "them" }
                command.copy(
                    status = PlanStatus.NEEDS_CLARIFICATION,
                    missingFields = listOf("body"),
                    question = "What should I say to $who?"
                )
            }
            command.action == Action.NOTE && command.target.isBlank() ->
                command.copy(
                    status = PlanStatus.NEEDS_CLARIFICATION,
                    missingFields = listOf("target"),
                    question = "What should the note say?"
                )
            command.action == Action.TYPE && command.target.isBlank() ->
                command.copy(
                    status = PlanStatus.NEEDS_CLARIFICATION,
                    missingFields = listOf("target"),
                    question = "What should I type?"
                )
            else -> command
        }
    }

    private fun exact(raw: String, bare: String): IntentMatch? {
        if (bare.matches(Regex("(?:go\\s+)?back", IGNORE_CASE))) {
            return match(raw, Parsed(Action.BACK))
        }
        if (bare.matches(Regex("(?:go\\s+)?home|home\\s+screen", IGNORE_CASE))) {
            return match(raw, Parsed(Action.HOME))
        }
        if (bare.matches(Regex("(?:show\\s+)?(?:recent\\s+apps|recents)", IGNORE_CASE))) {
            return match(raw, Parsed(Action.RECENTS))
        }
        if (bare.matches(
                Regex(
                    "(?:open|show|view|pull\\s+down)\\s+(?:my\\s+|the\\s+)?notifications",
                    IGNORE_CASE
                )
            )
        ) {
            return match(raw, Parsed(Action.OPEN_NOTIFICATIONS))
        }
        if (bare.matches(
                Regex(
                    "(?:close|dismiss|clear|hide)\\s+(?:my\\s+|the\\s+)?notifications",
                    IGNORE_CASE
                )
            )
        ) {
            return match(raw, Parsed(Action.CLOSE_NOTIFICATIONS))
        }
        if (bare.matches(
                Regex("(?:open|launch|start|use)\\s+(?:the\\s+)?camera(?:\\s+app)?", IGNORE_CASE)
            ) ||
            bare.matches(Regex("(?:take|snap)\\s+(?:a\\s+)?(?:photo|picture)", IGNORE_CASE))
        ) {
            return match(raw, Parsed(Action.TAKE_PHOTO))
        }
        return null
    }

    private fun screen(raw: String, bare: String): IntentMatch? {
        Regex(
            "^(?:read|read\\s+out)\\s+(?:my\\s+|the\\s+)?(?:screen|this|page)$",
            IGNORE_CASE
        ).find(bare)?.let {
            return match(raw, Parsed(Action.READ_SCREEN))
        }
        Regex(
            "^(?:long\\s*[- ]?press|long\\s*[- ]?click|press\\s+and\\s+hold)\\s+" +
                "(?:on\\s+)?(?<item>[\\s\\S]+)$",
            IGNORE_CASE
        ).find(bare)?.let { m ->
            val target = sanitizeTarget(Action.LONG_CLICK, m.groupValues[1])
            if (target.isBlank()) return null
            return match(
                raw,
                Parsed(
                    Action.LONG_CLICK,
                    target,
                    spans = span(m, "target", "item")
                )
            )
        }
        Regex("^swipe\\s+(up|down|left|right)$", IGNORE_CASE).find(bare)?.let { m ->
            val direction = m.groupValues[1].lowercase(Locale.ROOT)
            return match(
                raw,
                Parsed(
                    Action.SWIPE,
                    direction,
                    mapOf("direction" to direction)
                )
            )
        }
        Regex("^scroll\\s+(up|down)$", IGNORE_CASE).find(bare)?.let { m ->
            val direction = m.groupValues[1].lowercase(Locale.ROOT)
            return match(
                raw,
                Parsed(
                    Action.SCROLL,
                    direction,
                    mapOf("direction" to direction)
                )
            )
        }
        return null
    }

    private fun settings(raw: String, bare: String): IntentMatch? {
        Regex(
            "^(?:open|go\\s+to|show|launch|display)\\s+(?:the\\s+|my\\s+)?settings" +
                "(?:\\s+(?:page|for|to))?\\s*([\\s\\S]*)$",
            IGNORE_CASE
        ).find(bare)?.let { m ->
            return settingsResult(raw, m.groupValues[1], m, 1)
        }
        Regex(
            "^(?:open|go\\s+to|show)\\s+(?:the\\s+)?([\\p{L}\\p{N} _-]+?)\\s+settings(?:\\s+page)?$",
            IGNORE_CASE
        ).find(bare)?.let { m ->
            return settingsResult(raw, m.groupValues[1], m, 1)
        }
        return null
    }

    private fun settingsResult(
        raw: String,
        rawPage: String,
        m: MatchResult,
        group: Int
    ): IntentMatch {
        val cleaned = rawPage.trim().removePrefix("for ").trim()
        val page = cleaned.lowercase(Locale.ROOT).let { value ->
            when (value) {
                "wi-fi", "wi fi", "wireless" -> "wifi"
                "applications", "application", "app" -> "apps"
                "location services" -> "location"
                else -> value
            }
        }
        return match(
            raw,
            Parsed(
                Action.OPEN_SETTINGS,
                page,
                spans = m.groups[group]?.range?.let { mapOf("target" to it) }.orEmpty()
            )
        )
    }

    private fun volume(raw: String, bare: String): IntentMatch? {
        Regex(
            "^(?:please\\s+)?(?:turn\\s+)?(?:the\\s+)?volume\\s+" +
                "(up|down|max|maximum|mute|unmute)$",
            IGNORE_CASE
        ).find(bare)?.let { m ->
            val direction = normalizeVolume(m.groupValues[1])
            return match(
                raw,
                Parsed(Action.ADJUST_VOLUME, params = mapOf("direction" to direction))
            )
        }
        val fixed = listOf(
            Regex(
                "^(?:please\\s+)?(?:unmute|turn\\s+(?:the\\s+)?mute\\s+off|" +
                    "turn\\s+off\\s+(?:the\\s+)?mute|sound\\s+on)$",
                IGNORE_CASE
            ) to "unmute",
            Regex(
                "^(?:please\\s+)?(?:mute|turn\\s+(?:the\\s+)?mute\\s+on|silence)$",
                IGNORE_CASE
            ) to "mute",
            Regex(
                "^(?:please\\s+)?(?:louder|turn\\s+it\\s+up|volume\\s+up|" +
                    "turn\\s+up\\s+(?:the\\s+)?volume)$",
                IGNORE_CASE
            ) to "up",
            Regex(
                "^(?:please\\s+)?(?:quieter|turn\\s+it\\s+down|volume\\s+down|" +
                    "turn\\s+down\\s+(?:the\\s+)?volume)$",
                IGNORE_CASE
            ) to "down"
        )
        for ((regex, direction) in fixed) {
            if (regex.matches(bare)) {
                return match(
                    raw,
                    Parsed(Action.ADJUST_VOLUME, params = mapOf("direction" to direction))
                )
            }
        }
        return null
    }

    private fun normalizeVolume(value: String): String = when (value.lowercase(Locale.ROOT)) {
        "maximum" -> "max"
        else -> value.lowercase(Locale.ROOT)
    }

    private fun message(raw: String): IntentMatch? {
        val prefix = Regex("^(?:text|message|whatsapp|send)\\b\\s*", IGNORE_CASE)
            .find(raw) ?: return null
        val rest = raw.substring(prefix.range.last + 1)
        val base = prefix.range.last + 1

        val sendTo = Regex(
            "^(?<body>[\\s\\S]+?)\\s+to\\s+(?<who>[\\s\\S]+?)" +
                "(?:\\s+on\\s+whatsapp)?$",
            IGNORE_CASE
        ).find(rest)
        if (sendTo != null) {
            val body = (sendTo.groups["body"]?.value ?: "").trimStart(' ', '\t')
            if (body.isNotBlank() &&
                !body.matches(Regex("^(?:a|an)\\s+message$", IGNORE_CASE))
            ) {
                val who = trimRecipient(sendTo.groups["who"]?.value.orEmpty())
                if (who.isNotBlank()) {
                    return match(
                        raw,
                        Parsed(
                            Action.SEND_MESSAGE,
                            who,
                            mapOf("app" to "WhatsApp", "body" to body),
                            spans = spans(
                                base,
                                "target" to sendTo.groups["who"],
                                "body" to sendTo.groups["body"]
                            )
                        )
                    )
                }
            }
        }

        val normalized = rest.replaceFirst(
            Regex("^(?:a\\s+|an\\s+)?(?:message\\s+)?(?:to\\s+)?", IGNORE_CASE),
            ""
        )
        val normalizedBase = base + (rest.length - normalized.length)
        val split = Regex(
            "^(?<who>[\\s\\S]+?)(?:\\s+on\\s+whatsapp)?\\s*" +
                "(?::|,|\\bsaying\\b|\\bthat\\b)\\s*(?<body>[\\s\\S]*)$",
            IGNORE_CASE
        ).find(normalized)
        if (split != null) {
            val who = trimRecipient(split.groups["who"]?.value.orEmpty())
            val body = (split.groups["body"]?.value ?: "").trimStart(' ', '\t')
            if (who.isBlank()) {
                return clarification(
                    raw,
                    Action.SEND_MESSAGE,
                    "target",
                    "Who should I message?"
                )
            }
            if (body.isBlank()) {
                return clarification(
                    raw,
                    Action.SEND_MESSAGE,
                    "body",
                    "What should I say to $who?"
                )
            }
            return match(
                raw,
                Parsed(
                    Action.SEND_MESSAGE,
                    who,
                    mapOf("app" to "WhatsApp", "body" to body),
                    spans = spans(
                        normalizedBase,
                        "target" to split.groups["who"],
                        "body" to split.groups["body"]
                    )
                )
            )
        }

        val who = trimRecipient(normalized)
        return if (who.isBlank()) {
            clarification(raw, Action.SEND_MESSAGE, "target", "Who should I message?")
        } else {
            clarification(
                raw,
                Action.SEND_MESSAGE,
                "body",
                "What should I say to $who?"
            )
        }
    }

    private fun email(raw: String): IntentMatch? {
        val prefix = Regex(
            "^(?:send\\s+(?:an?\\s+)?email|email)\\b\\s*",
            IGNORE_CASE
        ).find(raw) ?: return null
        val rest = raw.substring(prefix.range.last + 1)
        val subjectMarker = Regex("\\s+subject\\s+", IGNORE_CASE).find(rest)
        val bodyMarker = Regex("\\s+body\\s+", IGNORE_CASE).find(rest)
        val targetEnd = listOfNotNull(
            subjectMarker?.range?.first,
            bodyMarker?.range?.first
        ).minOrNull() ?: rest.length
        val who = trimRecipient(
            rest.substring(0, targetEnd).trim().removePrefix("to ").trim()
        )
        if (who.isBlank()) {
            return clarification(raw, Action.SEND_EMAIL, "target", "Who should I email?")
        }
        val subject = if (subjectMarker != null) {
            val end = bodyMarker?.range?.first
                ?.takeIf { it > subjectMarker.range.last }
                ?: rest.length
            rest.substring(subjectMarker.range.last + 1, end).trim()
        } else {
            ""
        }
        val body = if (bodyMarker != null &&
            (subjectMarker == null || bodyMarker.range.first > subjectMarker.range.first)
        ) {
            rest.substring(bodyMarker.range.last + 1).trimStart(' ', '\t')
        } else {
            ""
        }
        return match(
            raw,
            Parsed(
                Action.SEND_EMAIL,
                who,
                buildMap {
                    if (subject.isNotBlank()) put("subject", subject)
                    if (body.isNotBlank()) put("body", body)
                }
            )
        )
    }

    private fun note(raw: String): IntentMatch? {
        val prefix = Regex(
            "^(?:note\\s+down|note|remember|remind\\s+me\\s+to)\\b\\s*:?\\s*",
            IGNORE_CASE
        ).find(raw) ?: return null
        val rest = raw.substring(prefix.range.last + 1)
        val body = rest.trimStart(' ', '\t')
        if (body.isBlank()) {
            return clarification(raw, Action.NOTE, "target", "What should the note say?")
        }
        val start = prefix.range.last + 1 + (rest.length - body.length)
        return match(
            raw,
            Parsed(
                Action.NOTE,
                body,
                spans = mapOf("target" to (start..start + body.length - 1))
            )
        )
    }

    private fun type(raw: String): IntentMatch? {
        val prefix = Regex(
            "^(?:type|write|enter)\\b\\s*:?\\s*",
            IGNORE_CASE
        ).find(raw) ?: return null
        val rest = raw.substring(prefix.range.last + 1)
        val body = rest.trimStart(' ', '\t')
        if (body.isBlank()) {
            return clarification(raw, Action.TYPE, "target", "What should I type?")
        }
        val start = prefix.range.last + 1 + (rest.length - body.length)
        return match(
            raw,
            Parsed(
                Action.TYPE,
                body,
                spans = mapOf("target" to (start..start + body.length - 1))
            )
        )
    }

    private fun playMusic(raw: String): IntentMatch? {
        val prefix = Regex("^(?:play|listen\\s+to|hear)\\b\\s*", IGNORE_CASE)
            .find(raw) ?: return null
        val restStart = prefix.range.last + 1
        var rest = raw.substring(restStart)
        Regex("\\s+on\\s+spotify\\s*$", IGNORE_CASE).find(rest)?.let {
            rest = rest.substring(0, it.range.first)
        }
        rest = rest.trim()
        if (rest.isBlank() || isVaguePlay(rest)) {
            return clarification(raw, Action.PLAY_MUSIC, "target", "What should I play?")
        }
        val by = Regex("\\s+by\\s+", IGNORE_CASE).findAll(rest).lastOrNull()
        val song = if (by != null) rest.substring(0, by.range.first).trim() else rest
        val artist = if (by != null) rest.substring(by.range.last + 1).trim() else ""
        if (song.isBlank() || isVaguePlay(song)) {
            return clarification(raw, Action.PLAY_MUSIC, "target", "What should I play?")
        }
        val params = buildMap {
            if (artist.isNotBlank()) put("artist", artist)
            put("app", "Spotify")
        }
        val songStart = raw.indexOf(song, restStart).takeIf { it >= 0 }
        val artistStart = if (artist.isNotBlank()) {
            raw.indexOf(artist, restStart).takeIf { it >= 0 }
        } else {
            null
        }
        val spans = buildMap {
            if (songStart != null) {
                put("target", songStart..songStart + song.length - 1)
            }
            if (artistStart != null) {
                put("artist", artistStart..artistStart + artist.length - 1)
            }
        }
        return match(raw, Parsed(Action.PLAY_MUSIC, song, params, spans = spans))
    }

    private fun call(raw: String): IntentMatch? {
        val m = Regex("^(?:call|dial|ring|phone)\\b\\s*(?<who>[\\s\\S]*)$", IGNORE_CASE)
            .find(raw) ?: return null
        val who = trimRecipient(m.groupValues[1])
        if (who.isBlank()) {
            return clarification(raw, Action.MAKE_CALL, "target", "Who should I call?")
        }
        return match(
            raw,
            Parsed(
                Action.MAKE_CALL,
                who,
                spans = span(m, "target", "who")
            )
        )
    }

    private fun search(raw: String): IntentMatch? {
        val m = Regex(
            "^(?:search|google|look\\s+up)\\b\\s*(?:for\\s+)?(?<query>[\\s\\S]*)$",
            IGNORE_CASE
        ).find(raw) ?: return null
        val query = m.groupValues[1].trim()
        if (query.isBlank()) {
            return clarification(raw, Action.SEARCH_WEB, "target", "What should I search for?")
        }
        return match(
            raw,
            Parsed(
                Action.SEARCH_WEB,
                query,
                spans = span(m, "target", "query")
            )
        )
    }

    private fun navigate(raw: String): IntentMatch? {
        val m = Regex(
            "^(?:navigate\\s+to|directions\\s+to|take\\s+me\\s+to|drive\\s+to|go\\s+to)" +
                "\\b\\s*(?<destination>[\\s\\S]*)$",
            IGNORE_CASE
        ).find(raw) ?: return null
        val place = sanitizeTarget(Action.NAVIGATE, m.groupValues[1])
        if (place.isBlank()) {
            return clarification(raw, Action.NAVIGATE, "target", "Where should I navigate?")
        }
        return match(
            raw,
            Parsed(Action.NAVIGATE, place, spans = span(m, "target", "destination"))
        )
    }

    private fun alarm(raw: String): IntentMatch? {
        val m = Regex(
            "^(?:set\\s+(?:an?\\s+)?alarm|alarm)\\b\\s*(?:for\\s+)?([\\s\\S]*)$",
            IGNORE_CASE
        ).find(raw) ?: return null
        val rest = m.groupValues[1].trim()
        val time = Regex(
            "(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?",
            IGNORE_CASE
        ).find(rest)
        if (time == null) {
            return clarification(
                raw,
                Action.SET_ALARM,
                "hour",
                "What time should I set the alarm for?"
            )
        }
        var hour = time.groupValues[1].toIntOrNull() ?: 0
        val minute = time.groupValues[2].toIntOrNull() ?: 0
        val ampm = time.groupValues[3].replace(".", "").lowercase(Locale.ROOT)
        if (ampm == "pm" && hour < 12) hour += 12
        if (ampm == "am" && hour == 12) hour = 0
        return match(
            raw,
            Parsed(
                Action.SET_ALARM,
                rest,
                mapOf("hour" to hour.toString(), "minute" to minute.toString())
            )
        )
    }

    private fun timer(raw: String): IntentMatch? {
        val m = Regex(
            "^(?:set\\s+(?:a\\s+)?timer|timer\\s+for)\\b\\s*([\\s\\S]*)$",
            IGNORE_CASE
        ).find(raw) ?: return null
        val rest = m.groupValues[1].trim()
        val seconds = parseDurationSeconds(rest)
        if (seconds == null) {
            return clarification(
                raw,
                Action.SET_TIMER,
                "seconds",
                "How long should the timer be?"
            )
        }
        return match(
            raw,
            Parsed(
                Action.SET_TIMER,
                rest,
                mapOf("seconds" to seconds.toString())
            )
        )
    }

    private fun calendar(raw: String): IntentMatch? {
        if (!Regex("\\b(?:calendar|event|reminder)\\b", IGNORE_CASE).containsMatchIn(raw)) {
            return null
        }
        val prefix = Regex("^(?:add|schedule|create)\\s+", IGNORE_CASE).find(raw)
            ?: return null
        val body = raw.substring(prefix.range.last + 1).trim()
        if (body.isBlank()) {
            return clarification(
                raw,
                Action.ADD_CALENDAR_EVENT,
                "target",
                "What should the event be?"
            )
        }
        return match(
            raw,
            Parsed(
                Action.ADD_CALENDAR_EVENT,
                body,
                mapOf("description" to body)
            )
        )
    }

    private fun calculate(raw: String): IntentMatch? {
        val prefix = Regex(
            "^(?:calculate|what\\s+is|what's|whats|how\\s+much\\s+is)\\b\\s*",
            IGNORE_CASE
        ).find(raw) ?: return null
        val expression = raw.substring(prefix.range.last + 1)
            .trim()
            .trim('?', '!', '.', ',', ';', ':')
            .trim()
        if (expression.isBlank()) {
            return clarification(
                raw,
                Action.CALCULATE,
                "target",
                "What should I calculate?"
            )
        }
        return match(raw, Parsed(Action.CALCULATE, expression))
    }

    private fun open(raw: String): IntentMatch? {
        val prefix = Regex(
            "^(?:open\\s+up|open|launch|start)\\b\\s*",
            IGNORE_CASE
        ).find(raw) ?: return null
        val target = raw.substring(prefix.range.last + 1).trim()
        val cleaned = target.trimEnd('.', '!', '?', ';', ',')
        if (cleaned.isBlank()) {
            return clarification(raw, Action.OPEN_APP, "target", "Which app should I open?")
        }
        val action = if (looksLikeUrl(cleaned)) Action.OPEN_URL else Action.OPEN_APP
        val value = if (action == Action.OPEN_URL) cleaned else sanitizeTarget(action, cleaned)
        val start = raw.indexOf(target, prefix.range.last + 1)
            .takeIf { it >= 0 }
        val spans = if (start != null) {
            mapOf("target" to (start..start + value.length - 1))
        } else {
            emptyMap()
        }
        return match(raw, Parsed(action, value, spans = spans))
    }

    private fun musicFuzzy(
        raw: String,
        lower: String,
        words: List<String>
    ): IntentMatch? {
        val verb = Regex(
            "\\b(?:play|played|playing|player|players|listen\\s+to|hear)\\b",
            IGNORE_CASE
        ).find(raw) ?: return null
        if ("spotify" !in lower && "song" !in words &&
            "music" !in words && " by " !in lower
        ) {
            return null
        }
        var after = raw.substring(verb.range.last + 1)
        after = Regex("\\s+on\\s+spotify\\b|\\bspotify\\b", IGNORE_CASE)
            .replace(after, " ")
        after = Regex(
            "\\b(?:please|player|players|playing|played)\\b",
            IGNORE_CASE
        ).replace(after, " ")
        after = after.trim()
            .removePrefix("a ")
            .removePrefix("and ")
            .removeSuffix(" and")
            .trim()
        if (after.isBlank() || isVaguePlay(after)) {
            return clarification(
                raw,
                Action.PLAY_MUSIC,
                "target",
                "What should I play?",
                source = MatchSource.FUZZY,
                confidence = 0.55f
            )
        }
        val by = Regex("\\s+by\\s+", IGNORE_CASE).findAll(after).lastOrNull()
        val song = if (by != null) after.substring(0, by.range.first).trim() else after
        val artist = if (by != null) after.substring(by.range.last + 1).trim() else ""
        if (song.isBlank() || isVaguePlay(song)) {
            return clarification(
                raw,
                Action.PLAY_MUSIC,
                "target",
                "What should I play?",
                source = MatchSource.FUZZY,
                confidence = 0.55f
            )
        }
        val params = buildMap {
            if (artist.isNotBlank()) put("artist", artist)
            put("app", "Spotify")
        }
        return match(
            raw,
            Parsed(Action.PLAY_MUSIC, song, params),
            source = MatchSource.FUZZY,
            confidence = 0.55f,
            reason = "fuzzy"
        )
    }

    private fun arithmeticFuzzy(
        raw: String,
        lower: String,
        words: List<String>
    ): IntentMatch? {
        val hasNumber = words.any { it in numberLex } || raw.any { it.isDigit() }
        val hasOperator = words.any { it in opLex } ||
            "what's" in lower || "what is" in lower
        if (!hasNumber || !hasOperator) return null
        return match(
            raw,
            Parsed(Action.CALCULATE, raw),
            source = MatchSource.FUZZY,
            confidence = 0.55f,
            reason = "fuzzy"
        )
    }

    private fun messageFuzzy(raw: String): IntentMatch? {
        val prefix = Regex(
            "^(?:(?:hey|ok|okay|please|adam)[\\s,]+)*" +
                "(?:text|message|messages|send|sending|whatsapp)\\b\\s*",
            IGNORE_CASE
        ).find(raw) ?: return null
        var rest = raw.substring(prefix.range.last + 1)
        rest = Regex("\\b(?:on\\s+whatsapp|whatsapp)\\b", IGNORE_CASE)
            .replace(rest, " ")
        rest = Regex("^(?:please|to|an?)\\s+", IGNORE_CASE).replace(rest, "")
        rest = rest.trim()
        if (rest.isBlank()) return null
        val splitAt = listOf(" that ", " saying ", ":").mapNotNull { separator ->
            val index = rest.indexOf(separator, ignoreCase = true)
            if (index >= 0) index to separator.length else null
        }.minByOrNull { it.first } ?: return null
        val who = trimRecipient(rest.substring(0, splitAt.first).removePrefix("to "))
        val body = rest.substring(splitAt.first + splitAt.second).trimStart(' ', '\t')
        if (who.isBlank() || body.isBlank()) return null
        return match(
            raw,
            Parsed(
                Action.SEND_MESSAGE,
                who,
                mapOf("app" to "WhatsApp", "body" to body)
            ),
            source = MatchSource.FUZZY,
            confidence = 0.55f,
            reason = "fuzzy"
        )
    }

    private fun openFuzzy(raw: String): IntentMatch? {
        val m = Regex(
            "^(?:(?:hey|ok|okay|please|adam)[\\s,]+)*" +
                "(?:open|opened|opens|launch|launched|start|starts)\\b\\s+([\\s\\S]+)$",
            IGNORE_CASE
        ).find(raw) ?: return null
        val cleaned = m.groupValues[1].trim().trimEnd('.', '!', '?', ';', ',')
        if (cleaned.isBlank()) return null
        val action = if (looksLikeUrl(cleaned)) Action.OPEN_URL else Action.OPEN_APP
        return match(
            raw,
            Parsed(action, sanitizeTarget(action, cleaned)),
            source = MatchSource.FUZZY,
            confidence = 0.55f,
            reason = "fuzzy"
        )
    }

    private fun isVaguePlay(value: String): Boolean =
        value.trim()
            .trimEnd('.', '!', '?', ',')
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ") in vaguePlay

    private fun sanitizeTarget(action: Action, value: String): String = when (action) {
        Action.OPEN_APP,
        Action.MAKE_CALL,
        Action.SEND_EMAIL,
        Action.OPEN_SETTINGS,
        Action.CLICK,
        Action.LONG_CLICK -> value.trim().trimEnd('.', ',', '!', '?', ';', ':')
        else -> value.trim()
    }

    private fun trimRecipient(value: String): String =
        value.trim()
            .replace(Regex("\\s+on\\s+whatsapp\\s*$", IGNORE_CASE), "")
            .trim()
            .trimEnd('.', ',', '!', '?', ';', ':')

    private fun parseDurationSeconds(text: String): Int? {
        val value = text.lowercase(Locale.ROOT)
        var total = 0
        var found = false
        Regex("(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)")
            .findAll(value)
            .forEach { m ->
                val count = m.groupValues[1].toIntOrNull() ?: return@forEach
                val unit = m.groupValues[2]
                total += when {
                    unit.startsWith("hour") || unit.startsWith("hr") -> count * 3600
                    unit.startsWith("min") -> count * 60
                    else -> count
                }
                found = true
            }
        if (!found) {
            Regex("(\\d+)").find(value)?.groupValues?.get(1)?.toIntOrNull()?.let {
                total = it * 60
                found = true
            }
        }
        return if (found) total else null
    }

    private fun looksLikeUrl(value: String): Boolean {
        val text = value.trim().trimEnd('.', ',', '!', '?', ';', ':')
        if (text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true)
        ) {
            return true
        }
        return Regex(
            "^[\\p{L}\\p{N}.-]+\\.[a-zA-Z]{2,}(?:[/?#].*)?$"
        ).matches(text)
    }

    private fun hasUnsupportedCompound(text: String): Boolean {
        if (bodyFirst.containsMatchIn(text)) return false
        return compoundTail.containsMatchIn(text)
    }

    private fun match(
        raw: String,
        parsed: Parsed,
        source: MatchSource = MatchSource.EXPLICIT,
        confidence: Float = 0.95f,
        reason: String = "explicit"
    ): IntentMatch = IntentMatch(
        command = AssistantCommand(
            action = parsed.action,
            target = parsed.target,
            params = parsed.params,
            status = parsed.status,
            missingFields = parsed.missing,
            question = parsed.question
        ),
        confidence = confidence,
        source = source,
        reasonCode = reason,
        rawText = raw,
        spans = parsed.spans
    )

    private fun clarification(
        raw: String,
        action: Action,
        field: String,
        question: String,
        source: MatchSource = MatchSource.EXPLICIT,
        confidence: Float = 0.95f
    ): IntentMatch = match(
        raw,
        Parsed(
            action = action,
            status = PlanStatus.NEEDS_CLARIFICATION,
            missing = listOf(field),
            question = question
        ),
        source,
        confidence
    )

    private fun abstain(raw: String): IntentMatch = IntentMatch(
        command = AssistantCommand(Action.UNKNOWN),
        confidence = 0f,
        source = MatchSource.NOVEL,
        reasonCode = "abstained",
        rawText = raw
    )

    private fun span(
        match: MatchResult,
        key: String,
        groupName: String
    ): Map<String, IntRange> =
        match.groups[groupName]?.range?.let { mapOf(key to it) }.orEmpty()

    private fun spans(
        base: Int,
        vararg groups: Pair<String, MatchGroup?>
    ): Map<String, IntRange> = buildMap {
        for ((key, group) in groups) {
            if (group != null) {
                put(key, base + group.range.first..base + group.range.last)
            }
        }
    }

    private companion object {
        val IGNORE_CASE = RegexOption.IGNORE_CASE
        const val MAX_INPUT_WORDS = 64

        val negation = Regex(
            "^(?:(?:hey|ok|okay|please|adam)[\\s,]+)*(?:i\\s+)?" +
                "(?:don't|dont|do\\s+not|never|no|not|stop|cancel|without|" +
                "shouldn't|should\\s+not|must\\s+not|can't|cannot|won't|wouldn't)\\b",
            IGNORE_CASE
        )

        val bodyFirst = Regex(
            "^(?:text|message|send|whatsapp|email|note|remember|type|write|enter)\\b",
            IGNORE_CASE
        )

        val compoundTail = Regex(
            "\\b(?:and|then)\\s+(?:open|launch|start|search|google|play|call|dial|" +
                "text|message|send|set|turn|go|navigate|take|read|show|close|mute|" +
                "unmute|swipe|scroll|calculate|note|remember|email|type|click)\\b",
            IGNORE_CASE
        )

        val vaguePlay = setOf(
            "", "a", "an", "some", "something", "anything", "it", "this", "that",
            "song", "songs", "a song", "some song", "some songs", "music",
            "some music", "a music", "me a song", "me some music", "me music"
        )

        val numberLex = setOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
            "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty",
            "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred",
            "thousand", "million"
        )

        val opLex = setOf(
            "plus", "minus", "times", "multiplied", "divided", "over", "mod",
            "calculate", "dividedby"
        )
    }
}
