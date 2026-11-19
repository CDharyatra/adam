package com.adam.assistant.core

import android.content.Context
import android.provider.ContactsContract
import java.text.Normalizer
import java.util.Locale

object Contacts {

    private val fillerWords = setOf("uh", "um", "uhm", "er", "hey", "please", "the")
    private val marks = Regex("\\p{M}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    fun load(context: Context, max: Int = 200): List<ContactCandidate> {
        val grouped = LinkedHashMap<String, MutableContact>()
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val cols = arrayOf(
                ContactsContract.CommonDataKinds.Phone._ID,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL
            )
            context.contentResolver.query(
                uri, cols, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { c ->
                val idIndex = c.getColumnIndex(cols[0])
                val contactIndex = c.getColumnIndex(cols[1])
                val nameIndex = c.getColumnIndex(cols[2])
                val numberIndex = c.getColumnIndex(cols[3])
                val typeIndex = c.getColumnIndex(cols[4])
                val labelIndex = c.getColumnIndex(cols[5])
                while (c.moveToNext()) {
                    val name = c.getString(nameIndex)?.trim().orEmpty()
                    if (name.length < 2 && name.codePointCount(0, name.length) < 2) continue
                    val number = c.getString(numberIndex)?.trim().orEmpty()
                    if (number.isBlank()) continue
                    val contactId = c.getLong(contactIndex).takeIf { it > 0L }
                    val key = contactId?.let { "id:$it" }
                        ?: "name:${norm(name)}"
                    val existing = grouped[key]
                    if (existing == null && grouped.size >= max) break
                    val entry = existing ?: MutableContact(name, contactId).also {
                        grouped[key] = it
                    }
                    val phoneId = c.getLong(idIndex).takeIf { it > 0L }
                    if (entry.numbers.any { it.id != null && it.id == phoneId }) continue
                    if (entry.numbers.any { it.number == number }) continue
                    val label = if (typeIndex >= 0) {
                        val type = c.getInt(typeIndex)
                        val custom = if (labelIndex >= 0) c.getString(labelIndex) else null
                        ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                            context.resources, type, custom
                        ).toString()
                    } else {
                        null
                    }
                    entry.numbers += ContactNumber(phoneId, number, label)
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return grouped.values.map { entry ->
            val numbers = entry.numbers.toList()
            ContactCandidate(
                name = entry.name,
                phone = numbers.firstOrNull()?.number,
                contactId = entry.contactId,
                phoneId = numbers.firstOrNull()?.id,
                numbers = numbers
            )
        }
    }

    fun grammarJson(entries: List<ContactCandidate>): String {
        val names = entries.flatMap { listOf(it.name) + it.aliases }
            .map { it.lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(150)
        val all = listOf("[unk]") + names
        return all.joinToString(prefix = "[", postfix = "]") { "\"${esc(it)}\"" }
    }

    fun match(entries: List<ContactCandidate>, heard: String): ContactMatch {
        val query = norm(heard)
        if (query.isBlank()) return ContactMatch.NotFound
        val scored = entries.map { it to score(heard, it) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
        if (scored.isEmpty()) return ContactMatch.NotFound

        val exact = scored.filter { it.second >= 0.9999 }.map { it.first }
        if (exact.size == 1) return ContactMatch.Resolved(exact.first())
        if (exact.size > 1) return ContactMatch.Ambiguous(exact)

        val top = scored.first()
        if (top.second < 0.62) return ContactMatch.NotFound
        val close = scored
            .takeWhile { top.second - it.second <= 0.08 }
            .take(5)
            .map { it.first }
        return if (close.size == 1) {
            ContactMatch.Resolved(close.first())
        } else {
            ContactMatch.Ambiguous(close)
        }
    }

    fun byNumber(
        entries: List<ContactCandidate>,
        heard: String,
        region: String?
    ): List<ContactCandidate> {
        if (!PhoneNumber.isPlausible(heard)) return emptyList()
        return entries.filter { candidate ->
            candidate.allNumbers().any { PhoneNumber.same(it.number, heard, region) }
        }
    }

    fun norm(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(marks, "")
            .lowercase(Locale.ROOT)
            .split(separators)
            .filter { it.isNotEmpty() && it !in fillerWords }
            .joinToString(" ")

    private fun score(heard: String, candidate: ContactCandidate): Double {
        val query = norm(heard)
        if (query.isBlank()) return 0.0
        var best = 0.0
        for (variant in listOf(candidate.name) + candidate.aliases) {
            val name = norm(variant)
            if (name.isBlank()) continue
            val value = when {
                name == query -> 1.0
                name.startsWith("$query ") || query.startsWith("$name ") -> 0.9
                name.contains(query) || query.contains(name) -> 0.86
                else -> similarity(query, name)
            }
            if (value > best) best = value
        }
        return best
    }

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
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    private fun esc(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private class MutableContact(
        val name: String,
        val contactId: Long?
    ) {
        val numbers = mutableListOf<ContactNumber>()
    }
}
