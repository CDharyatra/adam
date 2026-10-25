package com.adam.assistant.core

import org.junit.Assert.*
import org.junit.Test

class MemoryScorerTest {

    private fun pattern(vararg kws: String) =
        MemoryStore.LearnedPattern(
            keywords = kws.toList(),
            action = "OPEN_APP",
            target = "Spotify",
            params = emptyMap(),
            example = ""
        )

    @Test
    fun fullCoverageScoresOne() {
        val s = MemoryStore.score("open spotify now", listOf(pattern("open", "spotify")))!!
        assertEquals(1.0, s.score, 0.001)
    }

    @Test
    fun partialCoverage() {
        val s = MemoryStore.score("text shubhanshu", listOf(pattern("text", "shubhanshu", "whatsapp")))!!
        assertEquals(2.0 / 3.0, s.score, 0.001)
    }

    @Test
    fun singleKeywordNeedsItsHit() {
        assertNotNull(MemoryStore.score("mute it", listOf(pattern("mute"))))
        assertNull(MemoryStore.score("volume up", listOf(pattern("mute"))))
    }

    @Test
    fun twoKeywordNeedsBoth() {
        // Only one of two keywords → no match (avoids trigger-happy routing).
        assertNull(MemoryStore.score("open chrome", listOf(pattern("open", "spotify"))))
    }

    @Test
    fun noOverlapOrEmpty() {
        assertNull(MemoryStore.score("hello", listOf(pattern("open", "spotify"))))
        assertNull(MemoryStore.score("hello", emptyList()))
        assertNull(MemoryStore.score("", listOf(pattern("open"))))
    }

    @Test
    fun bestPatternWins() {
        val s = MemoryStore.score(
            "open spotify app",
            listOf(pattern("open", "chrome"), pattern("open", "spotify"))
        )!!
        assertEquals(listOf("open", "spotify"), s.pattern.keywords)
    }
}
