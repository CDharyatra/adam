package com.adam.assistant.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

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

    private val sensitiveActions = setOf(
        "SEND_MESSAGE", "MAKE_CALL", "SEND_EMAIL", "NOTE", "TYPE"
    )
    private val sensitiveParams = setOf(
        "body", "phone", "subject", "message", "pendinglearn"
    )

    fun isSensitiveAction(action: String): Boolean =
        action.trim().uppercase(Locale.ROOT) in sensitiveActions

    /**
     * Learned patterns teach actions, never fixed sensitive arguments. A
     * message pattern must not replay yesterday's recipient or body.
     */
    fun withoutSensitiveArgs(pattern: LearnedPattern): LearnedPattern {
        if (!isSensitiveAction(pattern.action)) return pattern
        val safeParams = pattern.params.filterKeys {
            it.lowercase(Locale.ROOT) !in sensitiveParams
        }
        return pattern.copy(target = "", params = safeParams)
    }

    /** Serializes a stored pattern through the JSON library (not string joins). */
    fun toCommandJson(p: LearnedPattern): String {
        val params = JSONObject()
        p.params.forEach { (k, v) -> params.put(k, v) }
        return JSONObject()
            .put("action", p.action)
            .put("target", p.target)
            .put("params", params)
            .toString()
    }

    /** Stores a newly reasoned pattern (dedupes by keyword set). */
    @Synchronized
    fun addLearned(context: Context, pattern: LearnedPattern) {
        ensureLoaded(context)
        val safe = withoutSensitiveArgs(pattern)
        learned.removeAll { it.keywords.toSet() == safe.keywords.toSet() }
        learned += safe.copy(example = redact(safe.example))
        persist(context)
    }

    /** Deletes one learned pattern; returns true when something was removed. */
    @Synchronized
    fun removeLearned(context: Context, pattern: LearnedPattern): Boolean {
        ensureLoaded(context)
        val removed = learned.removeAll { it.keywords.toSet() == pattern.keywords.toSet() }
        if (removed) persist(context)
        return removed
    }

    @Synchronized
    fun clearLearned(context: Context) {
        ensureLoaded(context)
        learned.clear()
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
