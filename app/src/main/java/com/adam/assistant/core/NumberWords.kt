package com.adam.assistant.core

/**
 * Spoken number words → digits ("twelve plus five" → "12 plus 5").
 *
 * Speech engines emit words, not digits, so every voice sum needs this before
 * evaluation. Handles units, tens, hundred, thousand and million, plus filler
 * words ("and", "a hundred"). Anything else passes through untouched.
 */
object NumberWords {

    private val ones = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30,
        "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
        "eighty" to 80, "ninety" to 90
    )

    /** True for number words and scales ("twelve", "hundred"). */
    fun isNumberWord(tok: String): Boolean =
        ones.containsKey(tok) || tok == "hundred" || tok == "thousand" || tok == "million"

    fun toDigits(s: String): String {
        val out = StringBuilder()
        var total = 0L
        var current = 0L
        var inNumber = false

        fun emit(word: String) {
            if (out.isNotEmpty()) out.append(' ')
            out.append(word)
        }

        fun flush() {
            if (inNumber) {
                emit((total + current).toString())
                total = 0L; current = 0L; inNumber = false
            }
        }

        val tokens = s.split(Regex("[\\s-]+")).map { it.trim() }.filter { it.isNotEmpty() }
        for (i in tokens.indices) {
            val tok = tokens[i]
            val next = tokens.getOrNull(i + 1)
            when {
                tok == "and" && inNumber -> Unit // "one hundred and five"
                // "a hundred" / "an thousand" → treat the article as 1
                (tok == "a" || tok == "an") && !inNumber &&
                        (next == "hundred" || next == "thousand" || next == "million") -> {
                    current = 1L; inNumber = true
                }
                ones.containsKey(tok) -> { current += ones.getValue(tok); inNumber = true }
                tok == "hundred" && inNumber -> {
                    current = (if (current == 0L) 1L else current) * 100L
                }
                (tok == "thousand" || tok == "million") && inNumber -> {
                    val scale = if (tok == "thousand") 1_000L else 1_000_000L
                    total += (if (current == 0L) 1L else current) * scale
                    current = 0L
                }
                else -> { flush(); emit(tok) }
            }
        }
        flush()
        return out.toString().trim().replace(Regex(" +"), " ")
    }
}
