package com.adam.assistant.executor

import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for the voice-arithmetic path. The log-proven failure was
 * `Heard (whisper): "What's 12 plus 5?"` → "Only basic arithmetic is
 * supported.": the transcript's capital W survived prefix stripping and the
 * trailing "?" failed the charset check. Both layers are covered here.
 */
class ArithmeticEvaluatorTest {

    @Test
    fun whispersQuestionWithPunctuation() {
        assertEquals("What's 12 plus 5? = 17", ArithmeticEvaluator.evaluate("What's 12 plus 5?"))
    }

    @Test
    fun misheardLeadInStillComputes() {
        assertEquals("watch twelve plus five = 17", ArithmeticEvaluator.evaluate("watch twelve plus five"))
    }

    @Test
    fun numberWordsAndOperators() {
        assertEquals("twelve minus five = 7", ArithmeticEvaluator.evaluate("twelve minus five"))
        assertEquals("12 divided by 4 = 3", ArithmeticEvaluator.evaluate("12 divided by 4"))
        assertEquals("calculate (5+3)*2 = 16", ArithmeticEvaluator.evaluate("calculate (5+3)*2"))
        assertEquals("what is 12 times 7 = 84", ArithmeticEvaluator.evaluate("what is 12 times 7"))
    }

    @Test
    fun decimalsSurviveBoundaryTrim() {
        assertEquals("12.5 plus 3? = 15.5", ArithmeticEvaluator.evaluate("12.5 plus 3?"))
    }

    @Test
    fun blankAndGarbageRefuseCleanly() {
        assertEquals("Tell me what to calculate.", ArithmeticEvaluator.evaluate("  "))
        assertEquals("Only basic arithmetic is supported.", ArithmeticEvaluator.evaluate("hello world"))
    }
}
