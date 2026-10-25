package com.adam.assistant.executor

import com.adam.assistant.core.NumberWords
import java.util.ArrayDeque
import java.util.Locale

/**
 * Pure, side-effect-free arithmetic for voice transcripts.
 *
 * Extracted from [CommandExecutor] so the normalization (the part that broke
 * on "What's 12 plus 5?") is JVM-unit-testable without Android. Handles:
 *  - spoken operators (plus/minus/times/divided by/over/mod/multiply/divide)
 *  - number words ("twelve" → 12) via [NumberWords]
 *  - transcript noise ("what's"/"watch"/"calculate" lead-ins, trailing "?")
 *
 * Returns either `"<original> = <result>"` or a human refusal string.
 */
object ArithmeticEvaluator {

    fun evaluate(expression: String): String {
        if (expression.isBlank()) return "Tell me what to calculate."
        // Transcripts mishear the lead-in ("what's twelve" → "watch twelve"):
        // drop leading words until the first number/operator so the real
        // expression survives regardless of what the first word became.
        val noiseStripped = expression.lowercase()
            .split(Regex("[\\s-]+"))
            .dropWhile { tok ->
                tok.none { it.isDigit() } && !NumberWords.isNumberWord(tok) &&
                        tok !in setOf("plus", "minus", "times", "multiplied", "divided", "over", "mod", "calculate", "(", ")")
            }
            .joinToString(" ")
        val cleaned = NumberWords.toDigits(
            noiseStripped
                .removePrefix("what is ").removePrefix("calculate ").removePrefix("what's")
        )
            .replace("plus", "+")
            .replace("minus", "-")
            .replace("multiplied by", "*").replace("multiply", "*").replace("times", "*").replace(" x ", " * ")
            .replace("divided by", "/").replace("divide", "/").replace("over", "/")
            .replace("mod", "%")
            .trim()
            // Voice transcripts end sentences with punctuation ("12 + 5?").
            // Strip boundary punctuation only — interior '.' (decimals) stays.
            .trim('?', '!', '.', ',', ';', ':')
            .trim()
        if (!cleaned.matches(Regex("""[\d+\-*/().% \t]+"""))) {
            return "Only basic arithmetic is supported."
        }
        return try {
            val result = evalExpression(cleaned)
            val pretty = if (result == result.toLong().toDouble()) result.toLong().toString()
                         else "%.6f".format(Locale.US, result).trimEnd('0').trimEnd('.')
            "$expression = $pretty"
        } catch (e: Exception) {
            "Could not calculate: $expression."
        }
    }

    /** Shunting-yard evaluator for + - * / % and parentheses. */
    fun evalExpression(expr: String): Double {
        val output = ArrayDeque<Any>()
        val ops    = ArrayDeque<Char>()
        val prec   = mapOf('+' to 1, '-' to 1, '*' to 2, '/' to 2, '%' to 2)
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            when {
                c.isDigit() || c == '.' -> {
                    val sb = StringBuilder()
                    while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) {
                        sb.append(expr[i]); i++
                    }
                    output.addLast(sb.toString().toDouble())
                    continue
                }
                c == '(' -> ops.addLast(c)
                c == ')' -> {
                    while (ops.isNotEmpty() && ops.last() != '(') output.addLast(ops.removeLast())
                    if (ops.isNotEmpty()) ops.removeLast()
                }
                c in "+-*/%" -> {
                    while (ops.isNotEmpty() && ops.last() != '(' &&
                           (prec[ops.last()] ?: 0) >= (prec[c] ?: 0)) {
                        output.addLast(ops.removeLast())
                    }
                    ops.addLast(c)
                }
                c.isWhitespace() -> {}
                else -> throw IllegalArgumentException("Invalid char: $c")
            }
            i++
        }
        while (ops.isNotEmpty()) output.addLast(ops.removeLast())
        val stack = ArrayDeque<Double>()
        for (token in output) {
            when (token) {
                is Double -> stack.addLast(token)
                is Char -> {
                    val b = stack.removeLast(); val a = stack.removeLast()
                    stack.addLast(when (token) {
                        '+' -> a + b; '-' -> a - b; '*' -> a * b; '/' -> a / b; '%' -> a % b
                        else -> throw IllegalArgumentException()
                    })
                }
            }
        }
        return stack.last()
    }
}
