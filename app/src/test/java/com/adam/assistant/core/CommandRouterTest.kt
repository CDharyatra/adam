package com.adam.assistant.core

import com.adam.assistant.llm.DeterministicRouter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRouterTest {

    private val router = DeterministicRouter()

    private fun route(text: String): IntentMatch = runBlocking { router.route(text) }

    @Test
    fun openSpotifyWithSentencePunctuation() {
        val match = route("Open Spotify.")
        assertEquals(AssistantCommand.Action.OPEN_APP, match.command.action)
        assertEquals("Spotify", match.command.target)
        assertEquals("Open Spotify.", match.rawText)
    }

    @Test
    fun openUpSpotify() {
        val match = route("open up Spotify")
        assertEquals(AssistantCommand.Action.OPEN_APP, match.command.action)
        assertEquals("Spotify", match.command.target)
    }

    @Test
    fun launchWhatsAppWithExclamation() {
        val match = route("Launch WhatsApp!")
        assertEquals(AssistantCommand.Action.OPEN_APP, match.command.action)
        assertEquals("WhatsApp", match.command.target)
    }

    @Test
    fun backWithPunctuation() {
        val match = route("Back.")
        assertEquals(AssistantCommand.Action.BACK, match.command.action)
        assertEquals("Back.", match.rawText)
    }

    @Test
    fun specificSettingsFormBeatsGenericOpen() {
        val match = route("open settings wifi")
        assertEquals(AssistantCommand.Action.OPEN_SETTINGS, match.command.action)
        assertEquals("wifi", match.command.target)
        assertEquals("wifi", route("open wifi settings").command.target)
    }

    @Test
    fun specificNotificationsFormBeatsGenericOpen() {
        assertEquals(
            AssistantCommand.Action.OPEN_NOTIFICATIONS,
            route("open notifications").command.action
        )
    }

    @Test
    fun unmuteIsNotMute() {
        val match = route("unmute")
        assertEquals(AssistantCommand.Action.ADJUST_VOLUME, match.command.action)
        assertEquals("unmute", match.command.params["direction"])
        assertEquals("mute", route("mute").command.params["direction"])
    }

    @Test
    fun negatedInstructionAbstains() {
        assertEquals(
            AssistantCommand.Action.UNKNOWN,
            route("don't open Spotify").command.action
        )
    }

    @Test
    fun unsupportedCompoundInstructionAbstains() {
        assertEquals(
            AssistantCommand.Action.UNKNOWN,
            route("open Swiggy and search pizza").command.action
        )
    }

    @Test
    fun vagueMusicAsksForTitle() {
        val match = route("play a song")
        assertEquals(AssistantCommand.Action.PLAY_MUSIC, match.command.action)
        assertEquals(AssistantCommand.PlanStatus.NEEDS_CLARIFICATION, match.command.status)
        assertEquals(listOf("target"), match.command.missingFields)
        assertNotNull(match.command.question)
    }

    @Test
    fun thirtyWordTargetSurvives() {
        val target = (1..30).joinToString(" ") { "word$it" }
        val match = route("open $target")
        assertEquals(AssistantCommand.Action.OPEN_APP, match.command.action)
        assertEquals(target, match.command.target)
    }

    @Test
    fun messageBodyPreservesUnicodeQuotesBackslashesAndNewlines() {
        val body = "\"hi\\there\"\nline two — 日本語"
        val raw = "message John saying $body"
        val match = route(raw)
        assertEquals(AssistantCommand.Action.SEND_MESSAGE, match.command.action)
        assertEquals("John", match.command.target)
        assertEquals(body, match.command.params["body"])
        val targetSpan = match.spans.getValue("target")
        val bodySpan = match.spans.getValue("body")
        assertEquals("John", raw.substring(targetSpan.first, targetSpan.last + 1))
        assertEquals(body, raw.substring(bodySpan.first, bodySpan.last + 1))
    }

    @Test
    fun similarRequestsNeverReuseArguments() {
        val first = route("message Ann saying first message")
        val second = route("message Bob saying second message")
        assertEquals("Ann", first.command.target)
        assertEquals("first message", first.command.params["body"])
        assertEquals("Bob", second.command.target)
        assertEquals("second message", second.command.params["body"])
    }

    @Test
    fun urlsKeepTheirCaseAndQuery() {
        val match = route("open https://Example.com/A?B=C")
        assertEquals(AssistantCommand.Action.OPEN_URL, match.command.action)
        assertEquals("https://Example.com/A?B=C", match.command.target)
    }

    @Test
    fun calculationKeepsExpression() {
        val match = route("what is 12 * (3 + 4)")
        assertEquals(AssistantCommand.Action.CALCULATE, match.command.action)
        assertEquals("12 * (3 + 4)", match.command.target)
    }

    @Test
    fun lexicalCeilingAbstains() {
        val target = (1..65).joinToString(" ") { "word$it" }
        assertEquals(AssistantCommand.Action.UNKNOWN, route("open $target").command.action)
    }

    @Test
    fun messageBodyMayContainCompoundWords() {
        val match = route("message John saying meet me at five and bring chips")
        assertEquals(AssistantCommand.Action.SEND_MESSAGE, match.command.action)
        assertEquals("meet me at five and bring chips", match.command.params["body"])
    }

    @Test
    fun typedRouterIsACommandRouter() {
        val commandRouter: CommandRouter = router
        val match = runBlocking { commandRouter.route("Back.") }
        assertTrue(match.command.action == AssistantCommand.Action.BACK)
    }
}
