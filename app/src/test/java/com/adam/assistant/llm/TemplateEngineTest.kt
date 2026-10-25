package com.adam.assistant.llm

import com.adam.assistant.core.AssistantCommand.Action
import org.junit.Assert.*
import org.junit.Test

class TemplateEngineTest {

    private val engine = TemplateEngine(
        listOf(
            Template("open_app", Action.OPEN_APP, "(open|launch|start) {target}"),
            Template("play", Action.PLAY_MUSIC, "play {target} [by {artist}]", mapOf("app" to "Spotify")),
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
        assertNull(engine.match("call")) // required slot left empty
    }

    @Test
    fun normalization() {
        val m = engine.match("  Play   Yesterday!! ")
        assertNotNull(m)
        assertEquals("yesterday", m!!.slots["target"])
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
