package com.adam.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NumberWordsTest {

    @Test
    fun unitsAndTeens() {
        assertEquals("12 plus 5", NumberWords.toDigits("twelve plus five"))
        assertEquals("0", NumberWords.toDigits("zero"))
        assertEquals("19", NumberWords.toDigits("nineteen"))
    }

    @Test
    fun tens() {
        assertEquals("99", NumberWords.toDigits("ninety nine"))
        assertEquals("20", NumberWords.toDigits("twenty"))
    }

    @Test
    fun hundreds() {
        assertEquals("123", NumberWords.toDigits("one hundred twenty three"))
        assertEquals("100", NumberWords.toDigits("a hundred"))
        assertEquals("105", NumberWords.toDigits("one hundred and five"))
    }

    @Test
    fun thousandsAndMillions() {
        assertEquals("2005", NumberWords.toDigits("two thousand and five"))
        assertEquals("1200000", NumberWords.toDigits("one million two hundred thousand"))
    }

    @Test
    fun nonNumbersPassThrough() {
        assertEquals("hello world", NumberWords.toDigits("hello world"))
        assertEquals("call mom", NumberWords.toDigits("call mom"))
        assertEquals("12 + 5", NumberWords.toDigits("12 + 5"))
    }

    @Test
    fun hyphenated() {
        assertEquals("42", NumberWords.toDigits("forty-two"))
    }
}
