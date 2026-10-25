package com.adam.assistant.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Adam's learning memory — the store behind the hybrid brain.
 *
 * Fast path (Option 1): [findBest] scores stored keyword patterns against
 * what the user said. Runs in microseconds, no model, no RAM cost.
 *
 * Learning (Option 2, on demand): when nothing matches, the raw command is
 * kept in [logNovel] and a reasoning backend ([com.adam.assistant.llm.ReasoningEngine])
 * can later turn it into a [LearnedPattern] via [addLearned]. The next
 * similar utterance then hits the fast path — Adam only ever "thinks hard"
 * about genuinely new commands, and never holds a model in RAM meanwhile.
 *
 * Persisted as one small JSON file in internal storage.
 */
object MemoryStore {

    data class LearnedPattern(
        val keywords: List<String>,
        val action: String,
        val target: String,
        val params: Map<String, String>,
        val example: String
    )

    private const val FILE_NAME = "adam_memory.json"
    private const val MAX_NOVEL = 50

    @Volatile private var loaded = false
    private val learned = mutableListOf<LearnedPattern>()
    private val novel = mutableListOf<String>()

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = file(context)
            if (!f.exists()) return
            val root = JSONObject(f.readText())
            root.optJSONArray("learned")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val kws = mutableListOf<String>()
                    o.optJSONArray("keywords")?.let { ka ->
                        for (j in 0 until ka.length()) kws += ka.optString(j)
                    }
                    val params = mutableMapOf<String, String>()
                    o.optJSONObject("params")?.let { po ->
                        po.keys().forEach { k -> params[k] = po.optString(k) }
                    }
                    learned += LearnedPattern(
                        keywords = kws,
                        action = o.optString("action"),
                        target = o.optString("target"),
                        params = params,
                        example = o.optString("example")
                    )
                }
            }
            root.optJSONArray("novel")?.let { arr ->
                for (i in 0 until arr.length()) novel += arr.optString(i)
            }
        } catch (_: Exception) {
            // Corrupt memory file: start fresh rather than crash.
            learned.clear(); novel.clear()
        }
    }

    @Synchronized
    private fun persist(context: Context) {
        try {
            val root = JSONObject()
            val la = JSONArray()
            for (p in learned) {
                val o = JSONObject()
                o.put("keywords", JSONArray(p.keywords))
                o.put("action", p.action)
                o.put("target", p.target)
                val po = JSONObject()
                p.params.forEach { (k, v) -> po.put(k, v) }
                o.put("params", po)
                o.put("example", p.example)
                la.put(o)
            }
            root.put("learned", la)
            val na = JSONArray()
            for (n in novel) na.put(n)
            root.put("novel", na)
            file(context).writeText(root.toString())
        } catch (_: Exception) {
            // Memory is best-effort; never break a command over it.
        }
    }

    data class Scored(val pattern: LearnedPattern, val score: Double)

    /**
     * Pure scorer (unit-testable): best of [patterns] for [text] with its
     * score. Single-keyword patterns need their hit; multi-keyword ones need
     * at least 2 hits and half coverage.
     */
    fun score(text: String, patterns: List<LearnedPattern>): Scored? {
        val words = text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }
            .toSet()
        if (words.isEmpty() || patterns.isEmpty()) return null
        var best: LearnedPattern? = null
        var bestScore = 0.0
        for (p in patterns) {
            if (p.keywords.isEmpty() || p.action.isBlank()) continue
            val hits = p.keywords.count { it in words }
            if (hits == 0) continue
            val s = hits.toDouble() / p.keywords.size
            val ok = p.keywords.size == 1 || (hits >= 2 && s >= 0.5)
            if (ok && s > bestScore) {
                best = p; bestScore = s
            }
        }
        return best?.let { Scored(it, bestScore) }
    }

    /** Best stored pattern for [text], or null when nothing matches. */
    @Synchronized
    fun findBest(context: Context, text: String): Scored? {
        ensureLoaded(context)
        return score(text, learned)
    }

    /** Serializes a stored pattern to the same JSON the LLM would emit. */
    fun toCommandJson(p: LearnedPattern): String {
        val sb = StringBuilder()
        sb.append("{\"action\":\"").append(p.action)
        sb.append("\",\"target\":\"").append(esc(p.target)).append("\",\"params\":{")
        p.params.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(',')
            sb.append('"').append(esc(k)).append("\":\"").append(esc(v)).append('"')
        }
        sb.append("}}")
        return sb.toString()
    }

    /** Stores a newly reasoned pattern (dedupes by keyword set). */
    @Synchronized
    fun addLearned(context: Context, pattern: LearnedPattern) {
        ensureLoaded(context)
        learned.removeAll { it.keywords.toSet() == pattern.keywords.toSet() }
        learned += pattern.copy(example = redact(pattern.example))
        persist(context)
    }

    /** Logs a command nothing understood (capped, de-duplicated consecutively). */
    @Synchronized
    fun logNovel(context: Context, text: String) {
        ensureLoaded(context)
        val clean = redact(text.trim())
        if (clean.isBlank() || novel.lastOrNull() == clean) return
        novel += clean
        while (novel.size > MAX_NOVEL) novel.removeAt(0)
        persist(context)
    }

    /** Novel commands awaiting reasoning (oldest first). */
    @Synchronized
    fun novelList(context: Context): List<String> {
        ensureLoaded(context)
        return novel.toList()
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * Strips secrets before anything is persisted: email addresses and long
     * digit runs (phone numbers, OTPs, card fragments). Names and ordinary
     * words survive so patterns stay learnable.
     */
    fun redact(text: String): String {
        var s = text
        s = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").replace(s, "[email]")
        s = Regex("\\d{4,}").replace(s, "[#]")
        return s
    }
}
