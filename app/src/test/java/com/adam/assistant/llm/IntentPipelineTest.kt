package com.adam.assistant.llm

import com.adam.assistant.core.AssistantCommand
import com.adam.assistant.core.AssistantCommand.Action
import com.adam.assistant.core.AssistantCommand.PlanStatus
import com.adam.assistant.core.MatchSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IntentPipelineTest {

    private fun command(
        action: Action,
        target: String = "",
        status: PlanStatus = PlanStatus.READY,
        missing: List<String> = emptyList()
    ) = AssistantCommand(action, target, status = status, missingFields = missing)

    private fun stage(
        command: AssistantCommand,
        conf: Float,
        source: MatchSource
    ) = IntentPipeline.Stage { IntentPipeline.Candidate(command, conf, source) }

    @Test
    fun firstConfidentMatchWinsRegardlessOfConfidence() = runBlocking {
        val p = IntentPipeline(
            listOf(
                stage(command(Action.BACK), 0.5f, MatchSource.TEMPLATE),
                stage(command(Action.HOME), 0.99f, MatchSource.FUZZY)
            )
        )
        val r = p.route("anything")!!
        assertEquals(MatchSource.TEMPLATE, r.source)
        assertEquals(Action.BACK, r.command.action)
    }

    @Test
    fun belowFloorFallsThrough() = runBlocking {
        val p = IntentPipeline(
            listOf(
                stage(command(Action.OPEN_APP, "Spotify"), 0.1f, MatchSource.TEMPLATE),
                stage(command(Action.BACK), 0.9f, MatchSource.EXPLICIT)
            ),
            mapOf(MatchSource.TEMPLATE to 0.5f, MatchSource.EXPLICIT to 0.9f)
        )
        val r = p.route("anything")!!
        assertEquals(MatchSource.EXPLICIT, r.source)
    }

    @Test
    fun throwingStageIsSkipped() = runBlocking {
        val p = IntentPipeline(
            listOf(
                IntentPipeline.Stage { throw RuntimeException("boom") },
                stage(command(Action.BACK), 0.9f, MatchSource.EXPLICIT)
            )
        )
        assertEquals(MatchSource.EXPLICIT, p.route("x")!!.source)
    }

    @Test
    fun allMissReturnsNull() = runBlocking {
        val p = IntentPipeline(listOf(IntentPipeline.Stage { null }))
        assertNull(p.route("x"))
    }

    @Test
    fun emptyPipelineReturnsNull() = runBlocking {
        assertNull(IntentPipeline(emptyList()).route("x"))
    }

    @Test
    fun suspendStageIsAwaited() = runBlocking {
        val p = IntentPipeline(
            listOf(
                IntentPipeline.Stage { t ->
                    kotlinx.coroutines.delay(10)
                    IntentPipeline.Candidate(
                        command(Action.NOTE, t),
                        0.9f,
                        MatchSource.MEDIAPIPE
                    )
                }
            )
        )
        val r = p.route("hello")!!
        assertEquals(MatchSource.MEDIAPIPE, r.source)
        assertTrue(r.command.target.contains("hello"))
    }

    @Test
    fun unknownCandidateAbstainsInsteadOfWinning() = runBlocking {
        val p = IntentPipeline(
            listOf(
                stage(command(Action.UNKNOWN), 0.99f, MatchSource.EXPLICIT)
            )
        )
        assertNull(p.route("x"))
    }

    @Test
    fun unsupportedCandidateAbstains() = runBlocking {
        val p = IntentPipeline(
            listOf(
                stage(
                    command(Action.CLICK, "Send", PlanStatus.UNSUPPORTED),
                    0.99f,
                    MatchSource.EXPLICIT
                )
            )
        )
        assertNull(p.route("x"))
    }

    @Test
    fun clarificationWithoutMissingFieldAbstains() = runBlocking {
        val p = IntentPipeline(
            listOf(
                stage(
                    command(Action.SEND_MESSAGE, "John", PlanStatus.NEEDS_CLARIFICATION),
                    0.9f,
                    MatchSource.EXPLICIT
                )
            )
        )
        assertNull(p.route("x"))
    }

    @Test
    fun knownIncompleteCommandStopsAtClarification() = runBlocking {
        val clarification = command(
            Action.SEND_MESSAGE,
            "John",
            PlanStatus.NEEDS_CLARIFICATION,
            listOf("body")
        )
        val p = IntentPipeline(
            listOf(
                stage(clarification, 0.9f, MatchSource.EXPLICIT),
                stage(command(Action.OPEN_APP, "Spotify"), 0.99f, MatchSource.EXPLICIT)
            )
        )
        val r = p.route("message John")!!
        assertEquals(PlanStatus.NEEDS_CLARIFICATION, r.command.status)
        assertEquals(listOf("body"), r.command.missingFields)
    }
}
