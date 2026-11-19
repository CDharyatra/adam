package com.adam.assistant.core

import com.adam.assistant.executor.ActionPort
import com.adam.assistant.executor.ActionResult
import com.adam.assistant.executor.MockActionPort
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantCoordinatorTest {

    private class RecordingPort : ActionPort {
        val plans = mutableListOf<ValidatedPlan>()

        override suspend fun execute(plan: ValidatedPlan): ActionResult {
            plans += plan
            return ActionResult.ObservedComplete("recorded ${plan.command.action}")
        }
    }

    private class FakeDirectory(
        private val entries: List<ContactCandidate>
    ) : ContactDirectory {
        override suspend fun load(): List<ContactCandidate> = entries

        override fun match(
            entries: List<ContactCandidate>,
            heard: String
        ): ContactMatch {
            val needle = heard.lowercase().trim()
            if (needle.isEmpty()) return ContactMatch.NotFound
            val hits = entries.filter {
                it.name.lowercase().contains(needle) ||
                    needle.contains(it.name.lowercase())
            }
            return when {
                hits.isEmpty() -> ContactMatch.NotFound
                hits.size == 1 -> ContactMatch.Resolved(hits.first())
                else -> ContactMatch.Ambiguous(hits)
            }
        }
    }

    private fun intent(
        action: AssistantCommand.Action,
        target: String = "",
        vararg params: Pair<String, String>
    ) = IntentMatch(
        command = AssistantCommand(
            action = action,
            target = target,
            params = mapOf(*params)
        ),
        confidence = 0.9f,
        source = MatchSource.NATIVE
    )

    private fun unknown() = IntentMatch(
        command = AssistantCommand(AssistantCommand.Action.UNKNOWN),
        confidence = 1f,
        source = MatchSource.NOVEL
    )

    private fun router(vararg entries: Pair<String, IntentMatch>): CommandRouter {
        val commands = entries.toMap()
        return CommandRouter { text ->
            commands[text.trim().lowercase()] ?: unknown()
        }
    }

    private fun coordinator(
        router: CommandRouter,
        executor: ActionPort,
        contacts: ContactDirectory = EmptyContactDirectory,
        preview: ActionPort = MockActionPort(),
        now: () -> Long = { 1_000L },
        ttlMs: Long = 90_000L,
        routeTimeoutMs: Long = 1_000L,
        executionTimeoutMs: Long = 5_000L,
        planIds: () -> String = { "plan-1" }
    ) = AssistantCoordinator(
        router = router,
        executor = executor,
        contacts = contacts,
        previewExecutor = preview,
        nowMs = now,
        sessionTtlMs = ttlMs,
        routeTimeoutMs = routeTimeoutMs,
        executionTimeoutMs = executionTimeoutMs,
        planIdFactory = planIds
    )

    @Test
    fun parseOnlyOpenAppReturnsPlanWithoutSideEffects() = runBlocking {
        val real = RecordingPort()
        val preview = MockActionPort()
        val c = coordinator(
            router("open spotify" to intent(AssistantCommand.Action.OPEN_APP, "Spotify")),
            executor = real,
            preview = preview
        )

        val reply = c.submitText("Open Spotify", InputSource.TYPED, CommandMode.PARSE_ONLY)

        assertNotNull(reply.plan)
        assertEquals(AssistantCommand.Action.OPEN_APP, reply.plan!!.command.action)
        assertEquals("Spotify", reply.plan!!.command.target)
        assertNull(reply.result)
        assertFalse(reply.awaitingInput)
        assertEquals(DialogState.NONE, reply.dialog)
        assertEquals(InputSource.TYPED, reply.inputSource)
        assertTrue(real.plans.isEmpty())
        assertTrue(preview.operations.isEmpty())
    }

    @Test
    fun parseOnlyArithmeticPreservesExpression() = runBlocking {
        val real = RecordingPort()
        val c = coordinator(
            router(
                "what is 12 * (3 + 4)" to
                    intent(AssistantCommand.Action.CALCULATE, "12 * (3 + 4)")
            ),
            executor = real
        )

        val reply = c.submitText(
            "what is 12 * (3 + 4)",
            InputSource.TYPED,
            CommandMode.PARSE_ONLY
        )

        assertEquals(
            "12 * (3 + 4)",
            reply.plan!!.command.target
        )
        assertTrue(real.plans.isEmpty())
    }

    @Test
    fun parseOnlyMessageReturnsPlanButNeverExecutes() = runBlocking {
        val real = RecordingPort()
        val c = coordinator(
            router(
                "message john saying hello" to intent(
                    AssistantCommand.Action.SEND_MESSAGE,
                    "John",
                    "app" to "WhatsApp",
                    "body" to "Hello"
                )
            ),
            executor = real
        )

        val reply = c.submitText(
            "message John saying Hello",
            InputSource.TYPED,
            CommandMode.PARSE_ONLY
        )

        assertEquals(AssistantCommand.Action.SEND_MESSAGE, reply.plan!!.command.action)
        assertEquals("Hello", reply.plan!!.command.params["body"])
        assertFalse(reply.awaitingInput)
        assertTrue(real.plans.isEmpty())
    }

    @Test
    fun dryRunUsesPreviewPortOnly() = runBlocking {
        val real = RecordingPort()
        val preview = MockActionPort()
        val c = coordinator(
            router("open spotify" to intent(AssistantCommand.Action.OPEN_APP, "Spotify")),
            executor = real,
            preview = preview
        )

        val reply = c.submitText("open spotify", InputSource.TYPED, CommandMode.DRY_RUN)

        assertTrue(real.plans.isEmpty())
        assertEquals(1, preview.operations.size)
        assertEquals(AssistantCommand.Action.OPEN_APP, preview.operations[0].action)
        assertTrue(reply.result is ActionResult.LaunchRequested)
        assertTrue(reply.speech.startsWith("Dry run:"))
    }

    @Test
    fun dryRunSensitiveActionStillNeedsConsentButNeverTouchesRealPort() = runBlocking {
        val real = RecordingPort()
        val preview = MockActionPort()
        val c = coordinator(
            router(
                "text mom saying dinner" to intent(
                    AssistantCommand.Action.SEND_MESSAGE,
                    "Mom",
                    "app" to "WhatsApp",
                    "body" to "Dinner?"
                )
            ),
            executor = real,
            preview = preview,
            contacts = FakeDirectory(listOf(ContactCandidate("Mom", "+15550001")))
        )

        val ask = c.submitText(
            "text mom saying dinner",
            InputSource.TYPED,
            CommandMode.DRY_RUN
        )
        assertTrue(ask.awaitingInput)
        assertTrue(preview.operations.isEmpty())
        assertTrue(real.plans.isEmpty())

        val done = c.submitText("yes", InputSource.TYPED, CommandMode.DRY_RUN)

        assertEquals(1, preview.operations.size)
        assertEquals("Dinner?", preview.operations[0].params["body"])
        assertTrue(real.plans.isEmpty())
        assertTrue(done.result is ActionResult.DraftOpened)
    }

    @Test
    fun sensitiveMessageExecutesOnlyAfterTypedYes() = runBlocking {
        val mock = MockActionPort()
        val c = coordinator(
            router(
                "message john saying hello" to intent(
                    AssistantCommand.Action.SEND_MESSAGE,
                    "John",
                    "app" to "WhatsApp",
                    "body" to "Hello!"
                )
            ),
            executor = mock,
            contacts = FakeDirectory(listOf(ContactCandidate("John", "+15550002")))
        )

        val ask = c.submitText(
            "message John saying Hello",
            InputSource.TYPED,
            CommandMode.EXECUTE
        )
        assertTrue(ask.awaitingInput)
        assertEquals(DialogState.CONFIRM, ask.dialog)
        assertTrue(mock.operations.isEmpty())
        assertTrue(ask.speech.contains("say yes", ignoreCase = true))

        val done = c.submitText("Yes.", InputSource.TYPED, CommandMode.EXECUTE)

        assertFalse(done.awaitingInput)
        assertEquals(DialogState.NONE, done.dialog)
        assertEquals(1, mock.operations.size)
        assertEquals("Hello!", mock.operations[0].params["body"])
        assertTrue(done.result is ActionResult.DraftOpened)
    }

    @Test
    fun typedNoCancelsWithoutExecuting() = runBlocking {
        val mock = MockActionPort()
        val c = coordinator(
            router(
                "message john saying hello" to intent(
                    AssistantCommand.Action.SEND_MESSAGE,
                    "John",
                    "body" to "Hello"
                )
            ),
            executor = mock,
            contacts = FakeDirectory(listOf(ContactCandidate("John", "+15550002")))
        )

        c.submitText("message John saying Hello", InputSource.TYPED, CommandMode.EXECUTE)
        val reply = c.submitText("No.", InputSource.TYPED, CommandMode.EXECUTE)

        assertEquals("Okay, cancelled.", reply.speech)
        assertFalse(reply.awaitingInput)
        assertTrue(mock.operations.isEmpty())
    }

    @Test
    fun slotFillPreservesExactPayload() = runBlocking {
        val mock = MockActionPort()
        val c = coordinator(
            router(
                "message john" to intent(
                    AssistantCommand.Action.SEND_MESSAGE,
                    "John"
                )
            ),
            executor = mock,
            contacts = FakeDirectory(listOf(ContactCandidate("John", "+15550002")))
        )

        val ask = c.submitText("message John", InputSource.TYPED, CommandMode.EXECUTE)
        assertTrue(ask.awaitingInput)
        assertEquals(DialogState.SLOT, ask.dialog)
        assertTrue(ask.speech.contains("message say", ignoreCase = true))

        val confirm = c.submitText(
            "Hello, 12 + 7!",
            InputSource.TYPED,
            CommandMode.EXECUTE
        )
        assertTrue(confirm.awaitingInput)
        assertEquals(DialogState.CONFIRM, confirm.dialog)
        assertTrue(mock.operations.isEmpty())
        assertEquals(2, confirm.plan!!.revision)

        c.submitText("yes", InputSource.TYPED, CommandMode.EXECUTE)

        assertEquals(1, mock.operations.size)
        assertEquals("Hello, 12 + 7!", mock.operations[0].params["body"])
        assertEquals(2, mock.operations[0].revision)
    }

    @Test
    fun contactQuestionResolvesNameAndCarriesPhone() = runBlocking {
        val mock = MockActionPort()
        val directory = FakeDirectory(
            listOf(ContactCandidate("John Smith", "+15550001"))
        )
        val c = coordinator(
            router(
                "message zoe saying hi" to intent(
                    AssistantCommand.Action.SEND_MESSAGE,
                    "Zoe",
                    "body" to "hi"
                )
            ),
            executor = mock,
            contacts = directory
        )

        val ask = c.submitText(
            "message Zoe saying hi",
            InputSource.TYPED,
            CommandMode.EXECUTE
        )
        assertTrue(ask.awaitingInput)
        assertEquals(DialogState.CONTACT, ask.dialog)
        assertTrue(ask.speech.contains("couldn't find", ignoreCase = true))
        assertEquals(1, c.pendingContactCandidates().size)

        val confirm = c.submitText("John Smith", InputSource.TYPED, CommandMode.EXECUTE)
        assertTrue(confirm.awaitingInput)
        assertEquals(DialogState.CONFIRM, confirm.dialog)
        assertTrue(mock.operations.isEmpty())
        assertTrue(c.pendingContactCandidates().isEmpty())

        c.submitText("yes", InputSource.TYPED, CommandMode.EXECUTE)

        assertEquals(1, mock.operations.size)
        assertEquals("John Smith", mock.operations[0].target)
        assertEquals("+15550001", mock.operations[0].params["phone"])
    }

    @Test
    fun modeChangeDiscardsArmedConsent() = runBlocking {
        val mock = MockActionPort()
        val preview = MockActionPort()
        val c = coordinator(
            router(
                "message john saying hello" to intent(
                    AssistantCommand.Action.SEND_MESSAGE,
                    "John",
                    "body" to "Hello"
                )
            ),
            executor = mock,
            preview = preview,
            contacts = FakeDirectory(listOf(ContactCandidate("John", "+15550002")))
        )

        c.submitText("message John saying Hello", InputSource.TYPED, CommandMode.EXECUTE)
        val reply = c.submitText("yes", InputSource.TYPED, CommandMode.DRY_RUN)

        assertFalse(reply.awaitingInput)
        assertTrue(mock.operations.isEmpty())
        assertTrue(preview.operations.isEmpty())
    }

    @Test
    fun expiredConfirmationDoesNotExecute() = runBlocking {
        val mock = MockActionPort()
        var now = 0L
        val c = coordinator(
            router(
                "message john saying hello" to intent(
                    AssistantCommand.Action.SEND_MESSAGE,
                    "John",
                    "body" to "Hello"
                )
            ),
            executor = mock,
            contacts = FakeDirectory(listOf(ContactCandidate("John", "+15550002"))),
            now = { now },
            ttlMs = 1_000L
        )

        c.submitText("message John saying Hello", InputSource.TYPED, CommandMode.EXECUTE)
        now = 1_001L
        val reply = c.submitText("yes", InputSource.TYPED, CommandMode.EXECUTE)

        assertFalse(reply.awaitingInput)
        assertTrue(mock.operations.isEmpty())
    }

    @Test
    fun malformedRouterCannotBypassPolicy() = runBlocking {
        val mock = MockActionPort()
        val c = coordinator(
            router("message john" to unknown()),
            executor = mock
        )

        val reply = c.submitText("message John", InputSource.TYPED, CommandMode.EXECUTE)

        assertNull(reply.plan)
        assertNull(reply.result)
        assertTrue(mock.operations.isEmpty())
    }

    @Test
    fun throwingRouterCannotBypassPolicy() = runBlocking {
        val mock = MockActionPort()
        val c = coordinator(
            CommandRouter { throw IllegalStateException("llm exploded") },
            executor = mock
        )

        val reply = c.submitText("message John", InputSource.TYPED, CommandMode.EXECUTE)

        assertNull(reply.plan)
        assertNull(reply.result)
        assertTrue(mock.operations.isEmpty())
    }

    @Test
    fun slowRouterTimesOutWithoutExecution() = runBlocking {
        val mock = MockActionPort()
        val c = coordinator(
            CommandRouter {
                delay(500)
                intent(AssistantCommand.Action.SEND_MESSAGE, "John", "body" to "Hi")
            },
            executor = mock,
            routeTimeoutMs = 10L
        )

        val reply = c.submitText("message John", InputSource.TYPED, CommandMode.EXECUTE)

        assertNull(reply.plan)
        assertNull(reply.result)
        assertTrue(mock.operations.isEmpty())
    }

    @Test
    fun unsupportedRouterPlanCannotBypassPolicy() = runBlocking {
        val mock = MockActionPort()
        val unsupported = IntentMatch(
            command = AssistantCommand(
                action = AssistantCommand.Action.SEND_MESSAGE,
                target = "John",
                params = mapOf("body" to "Hi"),
                status = AssistantCommand.PlanStatus.UNSUPPORTED
            ),
            confidence = 1f,
            source = MatchSource.NATIVE
        )
        val c = coordinator(
            router("message john" to unsupported),
            executor = mock
        )

        val reply = c.submitText("message John", InputSource.TYPED, CommandMode.EXECUTE)

        assertNull(reply.plan)
        assertTrue(mock.operations.isEmpty())
    }

    @Test
    fun cancelClearsPendingFollowUp() = runBlocking {
        val mock = MockActionPort()
        val c = coordinator(
            router(
                "message john saying hello" to intent(
                    AssistantCommand.Action.SEND_MESSAGE,
                    "John",
                    "body" to "Hello"
                )
            ),
            executor = mock,
            contacts = FakeDirectory(listOf(ContactCandidate("John", "+15550002")))
        )

        c.submitText("message John saying Hello", InputSource.TYPED, CommandMode.EXECUTE)
        c.cancel()
        val reply = c.submitText("yes", InputSource.TYPED, CommandMode.EXECUTE)

        assertFalse(reply.awaitingInput)
        assertTrue(mock.operations.isEmpty())
    }

    // ── Task 5 acceptance: recipient resolution, numbers, model guard ────────

    @Test
    fun makeCallWithoutNumberAsksForPhoneThenConfirms() = runBlocking {
        val mock = MockActionPort()
        val c = coordinator(
            router("call zoe" to intent(AssistantCommand.Action.MAKE_CALL, "Zoe")),
            executor = mock
        )

        val ask = c.submitText("call zoe", InputSource.TYPED, CommandMode.EXECUTE)
        assertTrue(ask.awaitingInput)
        assertEquals(DialogState.SLOT, ask.dialog)
        assertTrue(mock.operations.isEmpty())

        val confirm = c.submitText("+15550000001", InputSource.TYPED, CommandMode.EXECUTE)
        assertTrue(confirm.awaitingInput)
        assertEquals(DialogState.CONFIRM, confirm.dialog)
        assertEquals("+15550000001", confirm.plan!!.command.params["phone"])
        assertTrue(mock.operations.isEmpty())
    }

    @Test
    fun contactWithMultipleNumbersAsksWhichAndUsesSelection() = runBlocking {
        val mock = MockActionPort()
        val directory = FakeDirectory(
            listOf(
                ContactCandidate(
                    "John",
                    "+15550000001",
                    contactId = 1L,
                    numbers = listOf(
                        ContactNumber(10L, "+15550000001", "Mobile"),
                        ContactNumber(11L, "+15550000002", "Home")
                    )
                )
            )
        )
        val c = coordinator(
            router("text john saying hi" to intent(
                AssistantCommand.Action.SEND_MESSAGE, "John", "body" to "hi")),
            executor = mock,
            contacts = directory
        )

        val ask = c.submitText("text john saying hi", InputSource.TYPED, CommandMode.EXECUTE)
        assertTrue(ask.awaitingInput)
        assertEquals(DialogState.NUMBER, ask.dialog)

        val confirm = c.submitText("2", InputSource.TYPED, CommandMode.EXECUTE)
        assertTrue(confirm.awaitingInput)
        assertEquals(DialogState.CONFIRM, confirm.dialog)
        assertEquals("+15550000002", confirm.plan!!.command.params["phone"])
        assertTrue(mock.operations.isEmpty())
    }

    @Test
    fun ambiguousSameNameAsksWhichContact() = runBlocking {
        val mock = MockActionPort()
        val directory = FakeDirectory(
            listOf(
                ContactCandidate("John Smith", "+15550000001", contactId = 1L),
                ContactCandidate("John Smith", "+15550000009", contactId = 2L)
            )
        )
        val c = coordinator(
            router("text john smith saying hi" to intent(
                AssistantCommand.Action.SEND_MESSAGE, "John Smith", "body" to "hi")),
            executor = mock,
            contacts = directory
        )

        val ask = c.submitText("text john smith saying hi", InputSource.TYPED, CommandMode.EXECUTE)
        assertTrue(ask.awaitingInput)
        assertEquals(DialogState.CONTACT, ask.dialog)
        assertEquals(2, c.pendingContactCandidates().size)
        assertTrue(mock.operations.isEmpty())
    }

    @Test
    fun modelCannotProposeGenericClick() = runBlocking {
        val mock = MockActionPort()
        val c = coordinator(
            router("tap send" to intent(AssistantCommand.Action.CLICK, "Send")),
            executor = mock
        )

        val reply = c.submitText("tap send", InputSource.TYPED, CommandMode.EXECUTE)

        assertNull(reply.plan)
        assertNull(reply.result)
        assertFalse(reply.awaitingInput)
        assertTrue(mock.operations.isEmpty())
    }

    @Test
    fun blankBodyNeverConfirms() = runBlocking {
        val mock = MockActionPort()
        val directory = FakeDirectory(listOf(ContactCandidate("John", "+15550000001")))
        val c = coordinator(
            router("text john" to intent(AssistantCommand.Action.SEND_MESSAGE, "John")),
            executor = mock,
            contacts = directory
        )

        val ask = c.submitText("text john", InputSource.TYPED, CommandMode.EXECUTE)
        assertTrue(ask.awaitingInput)
        assertEquals(DialogState.SLOT, ask.dialog)

        // An empty answer must never advance to a confirmable plan.
        val blank = c.submitText("   ", InputSource.TYPED, CommandMode.EXECUTE)
        assertTrue(blank.awaitingInput)
        assertEquals(DialogState.SLOT, blank.dialog)
        assertTrue(mock.operations.isEmpty())

        val confirm = c.submitText("hi", InputSource.TYPED, CommandMode.EXECUTE)
        assertTrue(confirm.awaitingInput)
        assertEquals(DialogState.CONFIRM, confirm.dialog)
        assertEquals("hi", confirm.plan!!.command.params["body"])
        assertTrue(mock.operations.isEmpty())
    }

    @Test
    fun yesButDoNotSendDoesNotApprove() = runBlocking {
        val mock = MockActionPort()
        val directory = FakeDirectory(listOf(ContactCandidate("John", "+15550000001")))
        val c = coordinator(
            router("text john saying hi" to intent(
                AssistantCommand.Action.SEND_MESSAGE, "John", "body" to "hi")),
            executor = mock,
            contacts = directory
        )

        c.submitText("text john saying hi", InputSource.TYPED, CommandMode.EXECUTE)
        val reply = c.submitText(
            "yes but don't send",
            InputSource.TYPED,
            CommandMode.EXECUTE
        )

        assertNull(reply.result)
        assertTrue(mock.operations.isEmpty())
    }

    @Test
    fun consentIsSingleUseAndCannotBeReplayed() = runBlocking {
        val mock = MockActionPort()
        val directory = FakeDirectory(listOf(ContactCandidate("John", "+15550000001")))
        val c = coordinator(
            router("text john saying hi" to intent(
                AssistantCommand.Action.SEND_MESSAGE, "John", "body" to "hi")),
            executor = mock,
            contacts = directory
        )

        c.submitText("text john saying hi", InputSource.TYPED, CommandMode.EXECUTE)
        c.submitText("yes", InputSource.TYPED, CommandMode.EXECUTE)
        c.submitText("yes", InputSource.TYPED, CommandMode.EXECUTE)

        assertEquals(1, mock.operations.size)
    }
}
