package com.adam.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPolicyTest {

    private val policy = CommandPolicy()

    private fun candidate(
        action: AssistantCommand.Action,
        target: String = "",
        params: Map<String, String> = emptyMap(),
        status: AssistantCommand.PlanStatus = AssistantCommand.PlanStatus.READY,
        missingFields: List<String> = emptyList(),
        question: String? = null,
        source: MatchSource = MatchSource.NATIVE
    ) = CandidatePlan(
        rawText = "raw",
        command = AssistantCommand(
            action = action,
            target = target,
            params = params,
            status = status,
            missingFields = missingFields,
            question = question
        ),
        confidence = 1f,
        source = source
    )

    @Test
    fun unknownActionRejected() {
        val decision = policy.validate(
            candidate(AssistantCommand.Action.UNKNOWN),
            CommandMode.EXECUTE,
            "p1"
        )
        assertTrue(decision is ValidationDecision.Rejected)
    }

    @Test
    fun unsupportedStatusRejected() {
        val decision = policy.validate(
            candidate(
                AssistantCommand.Action.OPEN_APP,
                target = "Spotify",
                status = AssistantCommand.PlanStatus.UNSUPPORTED
            ),
            CommandMode.EXECUTE,
            "p1"
        )
        assertTrue(decision is ValidationDecision.Rejected)
    }

    @Test
    fun clarificationWithoutMissingFieldRejected() {
        val decision = policy.validate(
            candidate(
                AssistantCommand.Action.SEND_MESSAGE,
                target = "John",
                status = AssistantCommand.PlanStatus.NEEDS_CLARIFICATION
            ),
            CommandMode.EXECUTE,
            "p1"
        )
        assertTrue(decision is ValidationDecision.Rejected)
    }

    @Test
    fun clarificationCarriesQuestionAndField() {
        val decision = policy.validate(
            candidate(
                AssistantCommand.Action.SEND_MESSAGE,
                target = "John",
                status = AssistantCommand.PlanStatus.NEEDS_CLARIFICATION,
                missingFields = listOf("body"),
                question = "What should the message say?"
            ),
            CommandMode.EXECUTE,
            "p1"
        )
        assertTrue(decision is ValidationDecision.NeedsClarification)
        decision as ValidationDecision.NeedsClarification
        assertEquals("body", decision.field)
        assertEquals("What should the message say?", decision.question)
    }

    @Test
    fun clarificationFallsBackToPolicyQuestion() {
        val decision = policy.validate(
            candidate(
                AssistantCommand.Action.SEND_MESSAGE,
                target = "John",
                status = AssistantCommand.PlanStatus.NEEDS_CLARIFICATION,
                missingFields = listOf("body")
            ),
            CommandMode.EXECUTE,
            "p1"
        )
        decision as ValidationDecision.NeedsClarification
        assertEquals(policy.questionFor("body"), decision.question)
    }

    @Test
    fun messageWithoutTargetNeedsTarget() {
        val decision = policy.validate(
            candidate(AssistantCommand.Action.SEND_MESSAGE),
            CommandMode.EXECUTE,
            "p1"
        )
        decision as ValidationDecision.NeedsClarification
        assertEquals("target", decision.field)
    }

    @Test
    fun messageWithoutBodyNeedsBody() {
        val decision = policy.validate(
            candidate(AssistantCommand.Action.SEND_MESSAGE, target = "John"),
            CommandMode.EXECUTE,
            "p1"
        )
        decision as ValidationDecision.NeedsClarification
        assertEquals("body", decision.field)
    }

    @Test
    fun validPlanKeepsIdentityAndMode() {
        val decision = policy.validate(
            candidate(
                AssistantCommand.Action.OPEN_APP,
                target = "Spotify"
            ),
            CommandMode.DRY_RUN,
            "plan-7",
            revision = 3
        )
        decision as ValidationDecision.Valid
        assertEquals("plan-7", decision.plan.planId)
        assertEquals(3, decision.plan.revision)
        assertEquals(CommandMode.DRY_RUN, decision.plan.mode)
        assertEquals(MatchSource.NATIVE, decision.plan.source)
        assertEquals("plan-7:3", decision.plan.revisionKey)
    }

    @Test
    fun sensitiveActionsRequireConfirmation() {
        assertTrue(policy.requiresConfirmation(AssistantCommand.Action.SEND_MESSAGE))
        assertTrue(policy.requiresConfirmation(AssistantCommand.Action.MAKE_CALL))
        assertTrue(policy.requiresConfirmation(AssistantCommand.Action.SEND_EMAIL))
    }

    @Test
    fun safeActionsDoNotRequireConfirmation() {
        assertFalse(policy.requiresConfirmation(AssistantCommand.Action.OPEN_APP))
        assertFalse(policy.requiresConfirmation(AssistantCommand.Action.PLAY_MUSIC))
        assertFalse(policy.requiresConfirmation(AssistantCommand.Action.CALCULATE))
        assertFalse(policy.requiresConfirmation(AssistantCommand.Action.SEARCH_WEB))
    }

    // ── Task 5: model may never smuggle a bypass or invent identity ──────────

    @Test
    fun modelCannotProposeGenericUiActions() {
        for (action in listOf(
            AssistantCommand.Action.CLICK,
            AssistantCommand.Action.TYPE,
            AssistantCommand.Action.LONG_CLICK
        )) {
            val decision = policy.validate(
                candidate(action, target = "Send"),
                CommandMode.EXECUTE,
                "p1"
            )
            assertTrue(decision is ValidationDecision.Rejected)
        }
    }

    @Test
    fun modelCannotInventPackage() {
        val decision = policy.validate(
            candidate(
                AssistantCommand.Action.OPEN_APP,
                target = "Spotify",
                params = mapOf("package" to "com.evil.example")
            ),
            CommandMode.EXECUTE,
            "p1"
        )
        assertTrue(decision is ValidationDecision.Rejected)
    }

    @Test
    fun modelCannotInventPhone() {
        val decision = policy.validate(
            candidate(
                AssistantCommand.Action.MAKE_CALL,
                target = "John",
                params = mapOf("phone" to "+19998887777")
            ),
            CommandMode.EXECUTE,
            "p1"
        )
        assertTrue(decision is ValidationDecision.Rejected)
    }

    @Test
    fun unknownParamIsRejected() {
        val decision = policy.validate(
            candidate(
                AssistantCommand.Action.OPEN_APP,
                target = "Spotify",
                params = mapOf("bogus" to "x")
            ),
            CommandMode.EXECUTE,
            "p1"
        )
        assertTrue(decision is ValidationDecision.Rejected)
    }

    @Test
    fun invalidTypedValueIsRejected() {
        val decision = policy.validate(
            candidate(
                AssistantCommand.Action.SEND_MESSAGE,
                target = "John",
                params = mapOf("phone" to "abc", "body" to "hi"),
                source = MatchSource.TEMPLATE
            ),
            CommandMode.EXECUTE,
            "p1"
        )
        assertTrue(decision is ValidationDecision.Rejected)
    }

    @Test
    fun unsupportedClarificationFieldIsRejected() {
        val decision = policy.validate(
            candidate(
                AssistantCommand.Action.SEND_MESSAGE,
                target = "John",
                status = AssistantCommand.PlanStatus.NEEDS_CLARIFICATION,
                missingFields = listOf("bogus")
            ),
            CommandMode.EXECUTE,
            "p1"
        )
        assertTrue(decision is ValidationDecision.Rejected)
    }
}
