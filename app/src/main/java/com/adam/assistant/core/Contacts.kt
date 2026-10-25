package com.adam.assistant.core

import android.content.Context
import android.provider.ContactsContract

/**
 * Phone contacts: the fix for proper names speech models mangle.
 *
 * Small STT models turn "Shubhanshu" into "sure" / "show one shoe" because
 * such names barely exist in their language model. Two-part answer:
 *  1. [grammarJson] constrains Vosk to a custom vocabulary (the user's own
 *     contact names) when we ask "who?", snapping audio onto real names.
 *  2. [bestMatch] fuzzy-matches any heard string against real contacts as a
 *     safety net (exact → contains → edit-similarity).
 */
object Contacts {

    data class Entry(val name: String, val phone: String?)

    private val fillerWords = setOf("uh", "um", "uhm", "er", "hey", "please", "the")

    /** Up to [max] contacts with phone numbers, de-duplicated by name. */
    fun load(context: Context, max: Int = 200): List<Entry> {
        val out = mutableListOf<Entry>()
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val cols = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            context.contentResolver.query(
                uri, cols, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { c ->
                val ni = c.getColumnIndex(cols[0])
                val pi = c.getColumnIndex(cols[1])
                while (c.moveToNext() && out.size < max) {
                    val name = c.getString(ni)?.trim() ?: continue
                    if (name.length < 2) continue
                    val phone = c.getString(pi)?.trim()?.takeIf { it.isNotBlank() }
                    out += Entry(name, phone)
                }
            }
        } catch (_: Exception) {
            // Contacts unavailable: callers degrade to un-resolved targets.
        }
        return out.distinctBy { it.name.lowercase() }
    }

    /** Vosk grammar JSON: contact names as the allowed vocabulary. */
    fun grammarJson(entries: List<Entry>): String {
        val names = entries.map { it.name.lowercase() }.distinct().take(150)
        val all = listOf("[unk]") + names
        return all.joinToString(prefix = "[", postfix = "]") { "\"${esc(it)}\"" }
    }

    /** Best contact for [heard], or null when nothing is close enough. */
    fun bestMatch(entries: List<Entry>, heard: String): Entry? {
        val h = norm(heard)
        if (h.isBlank()) return null
        entries.firstOrNull { norm(it.name) == h }?.let { return it }
        var best: Entry? = null
        var bestScore = 0.0
        for (e in entries) {
            val score = similarity(h, norm(e.name))
            if (score > bestScore) {
                bestScore = score
                best = e
            }
        }
        return if (bestScore >= 0.62) best else null
    }

    private fun norm(s: String): String =
        s.lowercase()
            .split(Regex("[^a-z]+"))
            .filter { it.isNotEmpty() && it !in fillerWords }
            .joinToString(" ")

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val spaceless = 1.0 - lev(a.replace(" ", ""), b.replace(" ", "")) /
                maxOf(a.replace(" ", "").length, b.replace(" ", "").length).toDouble()
        val spaced = 1.0 - lev(a, b) / maxOf(a.length, b.length).toDouble()
        return maxOf(spaceless, spaced)
    }

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                curr[j] = minOf(
                    prev[j] + 1,
                    curr[j - 1] + 1,
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                )
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
