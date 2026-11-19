package com.adam.assistant.llm

/**
 * Strict, allocation-light JSON reader used to validate model output before any
 * part of it can reach the policy/executor.
 *
 * Vanilla [org.json.JSONObject] silently keeps the last of duplicate keys and
 * coerces any type with [org.json.JSONObject.optString], so a malformed or
 * hostile model reply would flow through as if it were well formed. This parser
 * instead:
 *  - accepts a single JSON value as the whole input (surrounding whitespace is
 *    fine), rejecting prose, code fences, or trailing junk;
 *  - rejects duplicate object keys;
 *  - preserves values as typed [JsonValue]s, so callers can enforce that the
 *    plan object uses the right shapes instead of trusting string coercion.
 *
 * Any structural violation throws [JsonError]; [parse] never returns a partial
 * result.
 */
object StrictJson {

    class JsonError(message: String) : Exception(message)

    sealed interface JsonValue {
        data class Obj(val members: Map<String, JsonValue>) : JsonValue
        data class Arr(val items: List<JsonValue>) : JsonValue
        data class Str(val value: String) : JsonValue
        data class Num(val raw: String) : JsonValue
        data class Bool(val value: Boolean) : JsonValue
        data object Null : JsonValue
    }

    /**
     * Parses exactly one JSON value. Throws [JsonError] on any malformed input,
     * duplicate key, or trailing non-whitespace content.
     */
    fun parse(raw: String): JsonValue {
        val p = Parser(raw)
        p.skipWs()
        val value = p.parseValue()
        p.skipWs()
        if (!p.atEnd()) p.fail("trailing content after JSON value")
        return value
    }

    /** Lenient entry used by the LLM pipeline; null means "reject". */
    fun parseOrNull(raw: String): JsonValue? =
        try {
            parse(raw)
        } catch (_: JsonError) {
            null
        } catch (_: Throwable) {
            null
        }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd(): Boolean = i >= s.length

        fun fail(message: String): Nothing =
            throw JsonError("$message at offset $i")

        fun skipWs() {
            while (i < s.length) {
                when (s[i]) {
                    ' ', '\t', '\r', '\n' -> i++
                    else -> return
                }
            }
        }

        fun parseValue(): JsonValue {
            if (atEnd()) fail("unexpected end of input")
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't', 'f' -> parseBool()
                'n' -> parseNull()
                else -> parseNumber()
            }
        }

        private fun parseObject(): JsonValue {
            i++ // consume '{'
            val members = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (!atEnd() && s[i] == '}') {
                i++
                return JsonValue.Obj(members)
            }
            while (true) {
                skipWs()
                if (atEnd() || s[i] != '"') fail("expected object key")
                val key = parseString()
                if (members.containsKey(key)) fail("duplicate key: $key")
                skipWs()
                if (atEnd() || s[i] != ':') fail("expected ':' after key")
                i++ // consume ':'
                skipWs()
                val value = parseValue()
                members[key] = value
                skipWs()
                if (atEnd()) fail("unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return JsonValue.Obj(members)
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): JsonValue {
            i++ // consume '['
            val items = ArrayList<JsonValue>()
            skipWs()
            if (!atEnd() && s[i] == ']') {
                i++
                return JsonValue.Arr(items)
            }
            while (true) {
                skipWs()
                items.add(parseValue())
                skipWs()
                if (atEnd()) fail("unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return JsonValue.Arr(items)
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            i++ // consume opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) fail("unterminated escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("short \\u escape")
                                val hex = s.substring(i, i + 4)
                                i += 4
                                val cp = hex.toIntOrNull(16) ?: fail("invalid \\u escape: $hex")
                                sb.append(cp.toChar())
                            }
                            else -> fail("invalid escape: \\$e")
                        }
                    }
                    else -> if (c < ' ') fail("control character in string") else sb.append(c)
                }
            }
        }

        private fun parseBool(): JsonValue =
            if (s.startsWith("true", i)) {
                i += 4
                JsonValue.Bool(true)
            } else if (s.startsWith("false", i)) {
                i += 5
                JsonValue.Bool(false)
            } else {
                fail("invalid literal")
            }

        private fun parseNull(): JsonValue =
            if (s.startsWith("null", i)) {
                i += 4
                JsonValue.Null
            } else {
                fail("invalid literal")
            }

        private fun parseNumber(): JsonValue {
            val start = i
            if (!atEnd() && (s[i] == '-' || s[i] == '+')) i++
            while (!atEnd() && (s[i].isDigit() || s[i] in ".eE+-")) i++
            if (i == start) fail("invalid value")
            val raw = s.substring(start, i)
            val numeric = raw.toDoubleOrNull() ?: fail("invalid number: $raw")
            if (numeric.isNaN()) fail("invalid number: $raw")
            return JsonValue.Num(raw)
        }
    }
}
