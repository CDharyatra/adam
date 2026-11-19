package com.adam.assistant.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryStoreTest {

    private val sensitive = MemoryStore.LearnedPattern(
        keywords = listOf("text", "mom"),
        action = "SEND_MESSAGE",
        target = "Mom",
        params = mapOf(
            "body" to "Dinner?",
            "phone" to "+15550001",
            "app" to "WhatsApp"
        ),
        example = "text mom saying Dinner?"
    )

    @Test
    fun learnedSensitiveArgumentsAreDropped() {
        val safe = MemoryStore.withoutSensitiveArgs(sensitive)
        assertEquals("", safe.target)
        assertNull(safe.params["body"])
        assertNull(safe.params["phone"])
        assertEquals("WhatsApp", safe.params["app"])
    }

    @Test
    fun learnedNonSensitivePatternIsUnchanged() {
        val pattern = MemoryStore.LearnedPattern(
            keywords = listOf("open", "spotify"),
            action = "OPEN_APP",
            target = "Spotify",
            params = emptyMap(),
            example = "open spotify"
        )
        assertEquals(pattern, MemoryStore.withoutSensitiveArgs(pattern))
    }

    @Test
    fun sensitiveActionsAreRecognised() {
        assertTrue(MemoryStore.isSensitiveAction("SEND_MESSAGE"))
        assertTrue(MemoryStore.isSensitiveAction("send_email"))
        assertFalse(MemoryStore.isSensitiveAction("OPEN_APP"))
    }

    @Test
    fun jsonSerializationPreservesPayloadExactly() {
        val body = "\"quoted\" \\slash\\ \nline — 日本語"
        val pattern = sensitive.copy(
            params = mapOf("body" to body, "app" to "WhatsApp")
        )
        val json = MemoryStore.toCommandJson(pattern)
        val parsed = JSONObject(json)
        assertEquals("SEND_MESSAGE", parsed.getString("action"))
        assertEquals("Mom", parsed.getString("target"))
        assertEquals(body, parsed.getJSONObject("params").getString("body"))
    }
}
