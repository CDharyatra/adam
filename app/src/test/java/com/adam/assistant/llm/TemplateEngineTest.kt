package com.adam.assistant.llm

import com.adam.assistant.core.AssistantCommand.Action
import org.junit.Assert.*
import org.junit.Test

class TemplateEngineTest {

    private val engine = TemplateEngine(
        listOf(
            Template("open_app", Action.OPEN_APP, "(open|launch|start|open up) {target}"),
            Template(
                "play",
                Action.PLAY_MUSIC,
                "play {target} [by {artist}]",
                mapOf("app" to "Spotify")
            ),
            Template(
                "message",
                Action.SEND_MESSAGE,
                "(text|message|send) {target} [(that|saying) {body}]",
                mapOf("app" to "WhatsApp")
            ),
            Template("mute", Action.ADJUST_VOLUME, "mute", mapOf("direction" to "mute")),
            Template("calc", Action.CALCULATE, "(calculate|what is) {target}")
        )
    )

    @Test
    fun literalAndSlot() {
        val m = engine.match("open spotify")
        assertNotNull(m)
        assertEquals("open_app", m!!.template.id)
        assertEquals("spotify", m.slots["target"])
    }

    @Test
    fun optionalAbsent() {
        val m = engine.match("play yesterday")
        assertNotNull(m)
        assertEquals("yesterday", m!!.slots["target"])
        assertTrue(m.slots["artist"].isNullOrBlank())
    }

    @Test
    fun optionalPresent() {
        val m = engine.match("play yesterday by beatles")
        assertNotNull(m)
        assertEquals("yesterday", m!!.slots["target"])
        assertEquals("beatles", m.slots["artist"])
    }

    @Test
    fun alternation() {
        assertNotNull(engine.match("launch chrome"))
        assertNotNull(engine.match("start vlc"))
    }

    @Test
    fun openUpIsASinglePhrase() {
        val m = engine.match("open up Spotify")
        assertNotNull(m)
        assertEquals("Spotify", m!!.slots["target"])
    }

    @Test
    fun literalOnlyTemplate() {
        val m = engine.match("mute")
        assertNotNull(m)
        assertEquals("mute", m!!.template.id)
        assertEquals("mute", m.template.fixedParams["direction"])
    }

    @Test
    fun noMatch() {
        assertNull(engine.match("blargh foo bar"))
        assertNull(engine.match(""))
        assertNull(engine.match("call"))
    }

    @Test
    fun normalizationKeepsOriginalCaseAndPunctuation() {
        val m = engine.match("  Play   Yesterday!! ")
        assertNotNull(m)
        assertEquals("Yesterday!!", m!!.slots["target"])
    }

    @Test
    fun sentencePunctuationDoesNotBlockLiteralMatch() {
        val e = TemplateEngine(
            listOf(Template("back", Action.BACK, "back"))
        )
        assertNotNull(e.match("Back."))
        assertEquals("back", e.match("Back!")!!.template.id)
    }

    @Test
    fun messageBodyKeepsCaseQuotesBackslashesAndNewlines() {
        val body = "\"line one\"\nline two\\end"
        val m = engine.match("message John saying $body")
        assertNotNull(m)
        assertEquals("John", m!!.slots["target"])
        assertEquals(body, m.slots["body"])
    }

    @Test
    fun thirtyWordTargetIsNotCutOff() {
        val e = TemplateEngine(
            listOf(Template("open", Action.OPEN_APP, "open {target}"))
        )
        val target = (1..30).joinToString(" ") { "word$it" }
        val m = e.match("open $target")
        assertNotNull(m)
        assertEquals(target, m!!.slots["target"])
    }

    @Test
    fun inputCeilingAbstains() {
        val e = TemplateEngine(
            listOf(Template("open", Action.OPEN_APP, "open {target}"))
        )
        val target = (1..65).joinToString(" ") { "word$it" }
        assertNull(e.match("open $target"))
    }

    @Test
    fun moreLiteralsWin() {
        val e = TemplateEngine(
            listOf(
                Template("generic", Action.SEARCH_WEB, "{target}"),
                Template("specific", Action.OPEN_APP, "open {target}")
            )
        )
        val m = e.match("open spotify")
        assertEquals("specific", m!!.template.id)
    }

    @Test
    fun confidenceRange() {
        val m = engine.match("calculate twelve plus five")!!
        assertTrue(m.confidence in 0.5f..0.95f)
    }
}
