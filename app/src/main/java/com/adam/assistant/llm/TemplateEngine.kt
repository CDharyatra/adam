package com.adam.assistant.llm

import com.adam.assistant.core.AssistantCommand
import java.util.Locale

/**
 * One hassil-style template: a pattern with `{slots}`, `[optional]` parts
 * and `(a|b)` alternations, e.g. `"play {target} [by {artist}] [on spotify]"`.
 */
data class Template(
    val id: String,
    val action: AssistantCommand.Action,
    val pattern: String,
    val fixedParams: Map<String, String> = emptyMap()
)

data class TemplateMatch(
    val template: Template,
    val slots: Map<String, String>,
    /** 0.5..0.95 — more matched literals and fewer slots score higher. */
    val confidence: Float,
    val spans: Map<String, IntRange> = emptyMap()
)

/**
 * Pure-Kotlin template matcher (hassil subset, no dependencies).
 *
 * Matching runs on raw word tokens with case-insensitive literal keys, so slot
 * values keep the speaker's original case and punctuation. Long slots are
 * enumerated longest-first under an explicit word ceiling and work budget:
 * a 30-word target is reachable, a pathological input abstains.
 */
class TemplateEngine(templates: List<Template>) {

    private sealed interface Elem {
        data class Lit(val word: String) : Elem
        data class Slot(val name: String) : Elem
        data class Opt(val inner: List<Elem>) : Elem
        data class Alt(val branches: List<List<Elem>>) : Elem
    }

    private data class Compiled(
        val template: Template,
        val elems: List<Elem>,
        val slotNames: List<String>
    )

    private data class Token(
        val raw: String,
        val key: String,
        val start: Int,
        val end: Int
    )

    private data class Hit(
        val slots: Map<String, String>,
        val spans: Map<String, IntRange>,
        val litMatched: Int,
        val slotsUsed: Int
    )

    private class Budget(var left: Int)

    private val compiled = templates.mapNotNull { compile(it) }

    fun match(text: String): TemplateMatch? {
        val words = tokenize(text)
        if (words.isEmpty() || words.size > MAX_INPUT_WORDS) return null
        var best: TemplateMatch? = null
        for (c in compiled) {
            val hits = mutableListOf<Hit>()
            walk(
                c.elems, 0, text, words, 0,
                emptyMap(), emptyMap(), 0, 0,
                Budget(MAX_WORK), hits
            )
            if (hits.isEmpty()) continue
            val h = hits.maxWithOrNull(
                compareBy<Hit> { it.litMatched }.thenBy { -it.slotsUsed }
            ) ?: continue
            val fullSlots = c.slotNames.associateWith { h.slots[it] ?: "" }
            val conf = (0.60f + 0.08f * h.litMatched - 0.04f * h.slotsUsed)
                .coerceIn(0.5f, 0.95f)
            if (best == null || conf > best.confidence) {
                best = TemplateMatch(c.template, fullSlots, conf, h.spans)
            }
        }
        return best
    }

    private fun walk(
        elems: List<Elem>,
        eIdx: Int,
        source: String,
        words: List<Token>,
        wIdx: Int,
        slots: Map<String, String>,
        spans: Map<String, IntRange>,
        litMatched: Int,
        slotsUsed: Int,
        budget: Budget,
        out: MutableList<Hit>
    ) {
        if (out.size >= MAX_HITS) return
        if (budget.left-- <= 0) return
        if (eIdx >= elems.size) {
            if (wIdx == words.size) {
                out += Hit(slots, spans, litMatched, slotsUsed)
            }
            return
        }
        when (val e = elems[eIdx]) {
            is Elem.Lit -> {
                if (wIdx < words.size && words[wIdx].key == e.word) {
                    walk(
                        elems, eIdx + 1, source, words, wIdx + 1,
                        slots, spans, litMatched + 1, slotsUsed, budget, out
                    )
                }
            }
            is Elem.Slot -> {
                val maxLen = words.size - wIdx - minWords(elems, eIdx + 1)
                if (maxLen < 1) return
                for (len in maxLen downTo 1) {
                    if (budget.left <= 0 || out.size >= MAX_HITS) return
                    val first = words[wIdx]
                    val last = words[wIdx + len - 1]
                    val value = source.substring(first.start, last.end)
                    walk(
                        elems, eIdx + 1, source, words, wIdx + len,
                        slots + (e.name to value),
                        spans + (e.name to (first.start..last.end - 1)),
                        litMatched, slotsUsed + 1, budget, out
                    )
                }
            }
            is Elem.Opt -> {
                walk(
                    elems, eIdx + 1, source, words, wIdx,
                    slots, spans, litMatched, slotsUsed, budget, out
                )
                walk(
                    e.inner + suffix(elems, eIdx), 0, source, words, wIdx,
                    slots, spans, litMatched, slotsUsed, budget, out
                )
            }
            is Elem.Alt -> {
                for (branch in e.branches) {
                    walk(
                        branch + suffix(elems, eIdx), 0, source, words, wIdx,
                        slots, spans, litMatched, slotsUsed, budget, out
                    )
                }
            }
        }
    }

    private fun suffix(elems: List<Elem>, eIdx: Int): List<Elem> =
        if (eIdx + 1 >= elems.size) emptyList() else elems.subList(eIdx + 1, elems.size)

    private fun minWords(elems: List<Elem>, from: Int): Int {
        var total = 0
        for (i in from until elems.size) {
            total += when (val e = elems[i]) {
                is Elem.Lit -> 1
                is Elem.Slot -> 1
                is Elem.Opt -> 0
                is Elem.Alt -> e.branches.minOfOrNull { minWords(it, 0) } ?: 0
            }
        }
        return total
    }

    private fun tokenize(source: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        while (i < source.length) {
            while (i < source.length && source[i].isWhitespace()) i++
            if (i >= source.length) break
            val start = i
            while (i < source.length && !source[i].isWhitespace()) i++
            val raw = source.substring(start, i)
            out += Token(raw, key(raw), start, i)
        }
        return out
    }

    private fun key(raw: String): String =
        raw.trim { it in ".,!?;:\"'()[]{}" }.lowercase(Locale.ROOT)

    /**
     * Single left-to-right pass with an explicit stack (no recursion): the
     * index only moves forward, so a hanging compiler is impossible.
     * Returns null for malformed patterns (template skipped, never a crash).
     */
    private fun compile(t: Template): Compiled? {
        try {
            val root = mutableListOf<Elem>()
            data class Frame(val list: MutableList<Elem>, val kind: Int) // 0 root, 1 opt, 2 alt
            val stack = ArrayDeque<Frame>()
            stack.addLast(Frame(root, 0))
            val altBranches = ArrayDeque<MutableList<List<Elem>>>()
            var i = 0
            val src = t.pattern
            fun cur(): MutableList<Elem> = stack.last().list
            while (i < src.length) {
                when (src[i]) {
                    ' ', '\t' -> i++
                    '[' -> {
                        val l = mutableListOf<Elem>()
                        stack.addLast(Frame(l, 1)); i++
                    }
                    ']' -> {
                        if (stack.size > 1 && stack.last().kind == 1) {
                            val f = stack.removeLast()
                            cur().add(Elem.Opt(f.list))
                        }
                        i++
                    }
                    '(' -> {
                        stack.addLast(Frame(mutableListOf(), 2))
                        altBranches.addLast(mutableListOf())
                        i++
                    }
                    '|' -> {
                        if (stack.size > 1 && stack.last().kind == 2) {
                            val f = stack.last()
                            altBranches.last().add(f.list.toList())
                            f.list.clear()
                        }
                        i++
                    }
                    ')' -> {
                        if (stack.size > 1 && stack.last().kind == 2) {
                            val f = stack.removeLast()
                            val done = altBranches.removeLast()
                            done.add(f.list.toList())
                            val nonEmpty = done.filter { it.isNotEmpty() }
                            if (nonEmpty.isNotEmpty()) cur().add(Elem.Alt(nonEmpty))
                        }
                        i++
                    }
                    '{' -> {
                        val end = src.indexOf('}', i)
                        if (end < 0) {
                            i++
                        } else {
                            val name = src.substring(i + 1, end).trim()
                            i = end + 1
                            if (name.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) {
                                cur().add(Elem.Slot(name))
                            }
                        }
                    }
                    else -> {
                        var j = i
                        while (j < src.length && src[j] !in " \t[](){}|") j++
                        if (j > i) cur().add(Elem.Lit(src.substring(i, j).lowercase()))
                        i = if (j > i) j else i + 1
                    }
                }
            }
            // Tolerate unclosed groups: keep optionals, drop dangling alts.
            while (stack.size > 1) {
                val f = stack.removeLast()
                if (f.kind == 1) {
                    cur().add(Elem.Opt(f.list))
                } else {
                    val done = if (altBranches.isNotEmpty()) altBranches.removeLast() else mutableListOf()
                    done.add(f.list.toList())
                    val nonEmpty = done.filter { it.isNotEmpty() }
                    if (nonEmpty.isNotEmpty()) cur().add(Elem.Alt(nonEmpty))
                }
            }
            val names = mutableListOf<String>()
            fun collect(es: List<Elem>) {
                for (e in es) when (e) {
                    is Elem.Slot -> names += e.name
                    is Elem.Opt -> collect(e.inner)
                    is Elem.Alt -> e.branches.forEach { collect(it) }
                    else -> Unit
                }
            }
            collect(root)
            if (root.isEmpty()) return null
            return Compiled(t, root, names.distinct())
        } catch (_: Exception) {
            return null
        }
    }

    companion object {
        const val MAX_INPUT_WORDS = 64
        private const val MAX_HITS = 512
        private const val MAX_WORK = 100_000
    }
}
