package com.adam.assistant.llm

import com.adam.assistant.core.AssistantCommand

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
    val confidence: Float
)

/**
 * Pure-Kotlin template matcher (hassil subset, no dependencies).
 *
 * The pattern compiles to a word-token structure (literals, slots, optional
 * groups, alternation branches) matched against input words with bounded
 * backtracking. Word-level matching can't suffer the separator/anchoring bugs
 * of the regex approach it replaces; best (highest-confidence) template wins.
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

    private data class Hit(
        val slots: Map<String, String>,
        val nextIdx: Int,
        val litMatched: Int,
        val slotsUsed: Int
    )

    private val compiled = templates.mapNotNull { compile(it) }

    fun match(text: String): TemplateMatch? {
        val words = normalize(text).split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        var best: TemplateMatch? = null
        for (c in compiled) {
            val hits = matchList(c.elems, words, 0, emptyMap()).take(25)
                .filter { it.nextIdx == words.size }
            if (hits.isEmpty()) continue
            val h = hits.maxWithOrNull(
                compareBy<Hit> { it.litMatched }.thenBy { -it.slotsUsed }
            ) ?: continue
            val fullSlots = c.slotNames.associateWith { h.slots[it] ?: "" }
            val conf = (0.60f + 0.08f * h.litMatched - 0.04f * h.slotsUsed)
                .coerceIn(0.5f, 0.95f)
            if (best == null || conf > best.confidence) {
                best = TemplateMatch(c.template, fullSlots, conf)
            }
        }
        return best
    }

    private fun matchList(
        elems: List<Elem>,
        words: List<String>,
        wIdx: Int,
        slots: Map<String, String>
    ): List<Hit> {
        if (elems.isEmpty()) return listOf(Hit(slots, wIdx, 0, 0))
        return when (val first = elems[0]) {
            is Elem.Lit -> {
                if (wIdx < words.size && words[wIdx] == first.word) {
                    matchList(elems.drop(1), words, wIdx + 1, slots)
                        .map { it.copy(litMatched = it.litMatched + 1) }
                } else emptyList()
            }
            is Elem.Slot -> {
                (1..(words.size - wIdx)).asSequence().flatMap { len ->
                    val v = words.subList(wIdx, wIdx + len).joinToString(" ")
                    matchList(elems.drop(1), words, wIdx + len, slots + (first.name to v))
                        .asSequence()
                        .map { it.copy(slotsUsed = it.slotsUsed + 1) }
                }.take(25).toList()
            }
            is Elem.Opt ->
                matchList(elems.drop(1), words, wIdx, slots) +
                    matchList(first.inner + elems.drop(1), words, wIdx, slots)
            is Elem.Alt ->
                first.branches.asSequence().flatMap { b ->
                    matchList(b + elems.drop(1), words, wIdx, slots).asSequence()
                }.take(25).toList()
        }
    }

    private fun normalize(s: String): String =
        s.lowercase().trim().replace(Regex("\\s+"), " ").trimEnd(',', '.', '!', '?')

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
}
