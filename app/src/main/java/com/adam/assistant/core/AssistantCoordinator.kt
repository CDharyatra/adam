package com.adam.assistant.core

import com.adam.assistant.executor.ActionPort
import com.adam.assistant.executor.ActionResult
import com.adam.assistant.executor.MockActionPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class DialogState {
    NONE,
    CONFIRM,
    SLOT,
    CONTACT,
    NUMBER
}

data class CoordinatorReply(
    val speech: String,
    val plan: ValidatedPlan? = null,
    val result: ActionResult? = null,
    val awaitingInput: Boolean = false,
    val inputSource: InputSource? = null,
    val dialog: DialogState = DialogState.NONE
)

class AssistantCoordinator(
    private val router: CommandRouter,
    private val executor: ActionPort,
    private val policy: CommandPolicy = CommandPolicy(),
    private val contacts: ContactDirectory = EmptyContactDirectory,
    private val appRegistry: AppRegistry = PassthroughAppRegistry,
    private val previewExecutor: ActionPort = MockActionPort(),
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sessionTtlMs: Long = 90_000L,
    private val routeTimeoutMs: Long = 10_000L,
    private val executionTimeoutMs: Long = 20_000L,
    private val planIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val phoneRegion: String? = null
) {

    private sealed interface Session {
        val mode: CommandMode
        val expiresAtMs: Long

        data class Confirm(
            val plan: ValidatedPlan,
            override val mode: CommandMode,
            override val expiresAtMs: Long
        ) : Session

        data class Slot(
            val command: AssistantCommand,
            val planId: String,
            val revision: Int,
            val source: MatchSource,
            val recipient: ResolvedRecipient?,
            val field: String,
            override val mode: CommandMode,
            override val expiresAtMs: Long
        ) : Session

        data class Contact(
            val command: AssistantCommand,
            val planId: String,
            val revision: Int,
            val source: MatchSource,
            val entries: List<ContactCandidate>,
            override val mode: CommandMode,
            override val expiresAtMs: Long
        ) : Session

        data class Number(
            val command: AssistantCommand,
            val planId: String,
            val revision: Int,
            val source: MatchSource,
            val candidate: ContactCandidate,
            val numbers: List<ContactNumber>,
            override val mode: CommandMode,
            override val expiresAtMs: Long
        ) : Session
    }

    private sealed interface RecipientOutcome {
        data class Resolved(
            val command: AssistantCommand,
            val recipient: ResolvedRecipient
        ) : RecipientOutcome

        data class AskContact(
            val command: AssistantCommand,
            val entries: List<ContactCandidate>,
            val prompt: String
        ) : RecipientOutcome

        data class AskNumber(
            val command: AssistantCommand,
            val candidate: ContactCandidate,
            val numbers: List<ContactNumber>,
            val prompt: String
        ) : RecipientOutcome

        data class AskPhone(
            val command: AssistantCommand,
            val prompt: String
        ) : RecipientOutcome

        data class Invalid(val speech: String) : RecipientOutcome
        data object NotApplicable : RecipientOutcome
    }

    private var session: Session? = null

    suspend fun submitText(
        text: String,
        source: InputSource,
        mode: CommandMode
    ): CoordinatorReply = process(text, mode).copy(
        inputSource = source,
        dialog = currentDialog()
    )

    private fun currentDialog(): DialogState = when (session) {
        is Session.Confirm -> DialogState.CONFIRM
        is Session.Slot -> DialogState.SLOT
        is Session.Contact -> DialogState.CONTACT
        is Session.Number -> DialogState.NUMBER
        null -> DialogState.NONE
    }

    private suspend fun process(
        text: String,
        mode: CommandMode
    ): CoordinatorReply {
        expireIfNeeded()
        if (session?.mode != mode) session = null
        val trimmed = text.trim()
        val active = session

        if (trimmed.isEmpty()) {
            return if (active != null) {
                CoordinatorReply(questionFor(active), awaitingInput = true)
            } else {
                CoordinatorReply("I didn't get that.")
            }
        }

        if (active != null) {
            return when (active) {
                is Session.Confirm -> handleConfirm(active, trimmed)
                is Session.Slot -> handleSlot(active, trimmed)
                is Session.Contact -> handleContact(active, trimmed)
                is Session.Number -> handleNumber(active, trimmed)
            }
        }

        return parseFresh(trimmed, mode)
    }

    fun cancel() {
        session = null
    }

    fun pendingContactCandidates(): List<ContactCandidate> {
        expireIfNeeded()
        return (session as? Session.Contact)?.entries.orEmpty()
    }

    // ── Fresh command ────────────────────────────────────────────────────────

    private suspend fun parseFresh(
        text: String,
        mode: CommandMode
    ): CoordinatorReply {
        val routed = try {
            withTimeoutOrNull(routeTimeoutMs) { router.route(text) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } ?: return CoordinatorReply("I couldn't understand that. Please try again.")

        val planId = planIdFactory()
        val command = canonicalize(routed.command)
        return advance(
            text = text,
            command = command,
            recipient = null,
            source = routed.source,
            confidence = routed.confidence,
            planId = planId,
            revision = 1,
            mode = mode,
            allowDialogs = mode != CommandMode.PARSE_ONLY
        )
    }

    // ── Follow-up sessions ───────────────────────────────────────────────────

    private suspend fun handleConfirm(
        active: Session.Confirm,
        text: String
    ): CoordinatorReply {
        val answer = normalizeAnswer(text)
        return when {
            isCancel(answer) -> {
                session = null
                CoordinatorReply("Okay, cancelled.")
            }
            isYes(answer) -> {
                session = null
                val plan = active.plan
                val candidate = CandidatePlan(
                    rawText = text,
                    command = plan.command,
                    confidence = 1f,
                    source = plan.source,
                    recipient = plan.recipient
                )
                when (val decision = policy.validate(
                    candidate,
                    plan.mode,
                    plan.planId,
                    plan.revision
                )) {
                    is ValidationDecision.Rejected -> {
                        session = null
                        CoordinatorReply(friendlyRejection(decision.reason))
                    }
                    is ValidationDecision.NeedsClarification ->
                        applyDecision(decision, candidate, plan.planId, plan.revision, plan.mode)
                    is ValidationDecision.Valid -> {
                        val authorized = policy.authorize(
                            decision.plan,
                            nowMs(),
                            sessionTtlMs
                        )
                        execute(authorized)
                    }
                }
            }
            else -> {
                session = null
                parseFresh(text, active.mode)
            }
        }
    }

    private suspend fun handleSlot(
        active: Session.Slot,
        text: String
    ): CoordinatorReply {
        session = null
        if (isCancel(normalizeAnswer(text))) {
            return CoordinatorReply("Okay, cancelled.")
        }
        val value = text.trim()
        var recipient = active.recipient
        val filled = when (active.field) {
            "body" -> active.command.copy(
                params = active.command.params + ("body" to value)
            )
            "target", "title" -> {
                recipient = null
                active.command.copy(target = value)
            }
            "phone" -> {
                val normalized = PhoneNumber.normalize(value, phoneRegion)
                if (normalized == null) {
                    session = active.copy(expiresAtMs = expiry())
                    return CoordinatorReply(
                        "Please include the country code, for example +1 555 000 0001.",
                        awaitingInput = true
                    )
                }
                recipient = if (recipient != null) {
                    recipient.copy(number = normalized, phoneId = null)
                } else {
                    ResolvedRecipient(
                        displayName = active.command.target.ifBlank { normalized },
                        number = normalized
                    )
                }
                active.command.copy(
                    target = active.command.target.ifBlank { normalized },
                    params = active.command.params + ("phone" to normalized)
                )
            }
            else -> active.command.copy(
                params = active.command.params + (active.field to value)
            )
        }
        return advance(
            text = text,
            command = filled,
            recipient = recipient,
            source = active.source,
            confidence = 1f,
            planId = active.planId,
            revision = active.revision + 1,
            mode = active.mode,
            allowDialogs = true
        )
    }

    private suspend fun handleContact(
        active: Session.Contact,
        text: String
    ): CoordinatorReply {
        session = null
        if (isCancel(normalizeAnswer(text))) {
            return CoordinatorReply("Okay, cancelled.")
        }

        if (PhoneNumber.isPlausible(text)) {
            val matches = try {
                Contacts.byNumber(active.entries, text, phoneRegion)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
            if (matches.size == 1) {
                return resolveCandidate(
                    active,
                    matches.first(),
                    text
                )
            }
            if (matches.size > 1) {
                return askContact(
                    active.command,
                    matches,
                    "I found ${matches.size} contacts with that number. Which one?",
                    active
                )
            }
            val normalized = PhoneNumber.normalize(text, phoneRegion)
            if (normalized == null) {
                return askContact(
                    active.command,
                    active.entries,
                    "Please include the country code for that number. Which contact?",
                    active
                )
            }
            val command = active.command.copy(
                target = normalized,
                params = active.command.params + ("phone" to normalized)
            )
            return advance(
                text = text,
                command = command,
                recipient = ResolvedRecipient(normalized, normalized),
                source = active.source,
                confidence = 1f,
                planId = active.planId,
                revision = active.revision + 1,
                mode = active.mode,
                allowDialogs = true
            )
        }

        val index = ordinalIndex(text)
        if (index != null && index in active.entries.indices) {
            return resolveCandidate(active, active.entries[index], text)
        }

        return when (val match = contacts.match(active.entries, text)) {
            is ContactMatch.Resolved -> resolveCandidate(active, match.candidate, text)
            is ContactMatch.Ambiguous -> askContact(
                active.command,
                match.candidates,
                "I found ${match.candidates.size} matching contacts. " +
                    "Which one? Say the full name or number.",
                active
            )
            ContactMatch.NotFound -> askContact(
                active.command,
                active.entries,
                "I still couldn't match that. Which contact should I use?",
                active
            )
        }
    }

    private suspend fun handleNumber(
        active: Session.Number,
        text: String
    ): CoordinatorReply {
        session = null
        if (isCancel(normalizeAnswer(text))) {
            return CoordinatorReply("Okay, cancelled.")
        }
        val selected = selectNumber(active.numbers, text)
        if (selected == null) {
            session = active.copy(expiresAtMs = expiry())
            return CoordinatorReply(
                numberPrompt(active.candidate.name, active.numbers),
                awaitingInput = true
            )
        }
        val command = attach(active.command, active.candidate, selected)
        return advance(
            text = text,
            command = command,
            recipient = recipient(active.candidate, selected),
            source = active.source,
            confidence = 1f,
            planId = active.planId,
            revision = active.revision + 1,
            mode = active.mode,
            allowDialogs = true
        )
    }

    // ── Policy → session → execution ─────────────────────────────────────────

    private suspend fun advance(
        text: String,
        command: AssistantCommand,
        recipient: ResolvedRecipient?,
        source: MatchSource,
        confidence: Float,
        planId: String,
        revision: Int,
        mode: CommandMode,
        allowDialogs: Boolean
    ): CoordinatorReply {
        var cmd = command
        var resolved = recipient
        if (allowDialogs && resolved == null) {
            when (val outcome = resolveRecipient(cmd)) {
                is RecipientOutcome.Resolved -> {
                    cmd = outcome.command
                    resolved = outcome.recipient
                }
                is RecipientOutcome.AskContact -> {
                    session = Session.Contact(
                        command = outcome.command,
                        planId = planId,
                        revision = revision,
                        source = source,
                        entries = outcome.entries,
                        mode = mode,
                        expiresAtMs = expiry()
                    )
                    return CoordinatorReply(
                        outcome.prompt,
                        awaitingInput = true
                    )
                }
                is RecipientOutcome.AskNumber -> {
                    session = Session.Number(
                        command = outcome.command,
                        planId = planId,
                        revision = revision,
                        source = source,
                        candidate = outcome.candidate,
                        numbers = outcome.numbers,
                        mode = mode,
                        expiresAtMs = expiry()
                    )
                    return CoordinatorReply(
                        outcome.prompt,
                        awaitingInput = true
                    )
                }
                is RecipientOutcome.AskPhone -> {
                    session = Session.Slot(
                        command = outcome.command,
                        planId = planId,
                        revision = revision,
                        source = source,
                        recipient = null,
                        field = "phone",
                        mode = mode,
                        expiresAtMs = expiry()
                    )
                    return CoordinatorReply(
                        outcome.prompt,
                        awaitingInput = true
                    )
                }
                is RecipientOutcome.Invalid -> return CoordinatorReply(outcome.speech)
                RecipientOutcome.NotApplicable -> Unit
            }
        }
        if (resolved != null && resolved.number.isNotBlank()) {
            cmd = cmd.copy(params = cmd.params + ("phone" to resolved.number))
        }
        val candidate = CandidatePlan(
            rawText = text,
            command = cmd,
            confidence = confidence,
            source = source,
            recipient = resolved
        )
        val decision = policy.validate(candidate, mode, planId, revision)
        return applyDecision(decision, candidate, planId, revision, mode)
    }

    private suspend fun applyDecision(
        decision: ValidationDecision,
        candidate: CandidatePlan,
        planId: String,
        revision: Int,
        mode: CommandMode
    ): CoordinatorReply = when (decision) {
        is ValidationDecision.Rejected -> {
            session = null
            CoordinatorReply(friendlyRejection(decision.reason))
        }
        is ValidationDecision.NeedsClarification -> {
            if (mode == CommandMode.PARSE_ONLY) {
                CoordinatorReply(decision.question)
            } else {
                val recipient = if (decision.field == "target") {
                    null
                } else {
                    candidate.recipient
                }
                session = Session.Slot(
                    command = candidate.command,
                    planId = planId,
                    revision = revision,
                    source = candidate.source,
                    recipient = recipient,
                    field = decision.field,
                    mode = mode,
                    expiresAtMs = expiry()
                )
                CoordinatorReply(decision.question, awaitingInput = true)
            }
        }
        is ValidationDecision.Valid -> continueToExecution(decision.plan)
    }

    private suspend fun continueToExecution(plan: ValidatedPlan): CoordinatorReply {
        if (plan.mode != CommandMode.PARSE_ONLY &&
            policy.requiresConfirmation(plan.command.action)
        ) {
            session = Session.Confirm(plan, plan.mode, expiry())
            return CoordinatorReply(
                policy.confirmationPreview(plan),
                plan = plan,
                awaitingInput = true
            )
        }
        return execute(plan)
    }

    private suspend fun execute(plan: ValidatedPlan): CoordinatorReply {
        if (plan.mode == CommandMode.PARSE_ONLY) {
            return CoordinatorReply(parsedSpeech(plan.command), plan = plan)
        }
        val consent = ConsentRules.verify(plan, nowMs())
        if (consent is ConsentCheck.Denied) {
            return CoordinatorReply(
                "I didn't run that: ${consent.reason}.",
                plan = plan
            )
        }
        val port = if (plan.mode == CommandMode.DRY_RUN) previewExecutor else executor
        val result = try {
            withTimeoutOrNull(executionTimeoutMs) { port.execute(plan) }
                ?: ActionResult.TimedOut
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            ActionResult.Failed(t.message ?: t.javaClass.simpleName)
        }
        return CoordinatorReply(speechFor(plan, result), plan = plan, result = result)
    }

    // ── Contacts / app names ─────────────────────────────────────────────────

    private suspend fun resolveRecipient(command: AssistantCommand): RecipientOutcome {
        if (command.action != AssistantCommand.Action.SEND_MESSAGE &&
            command.action != AssistantCommand.Action.MAKE_CALL
        ) {
            return RecipientOutcome.NotApplicable
        }
        if (command.status == AssistantCommand.PlanStatus.UNSUPPORTED) {
            return RecipientOutcome.NotApplicable
        }
        val target = command.target.trim()
        if (target.isBlank()) return RecipientOutcome.NotApplicable

        if (PhoneNumber.isPlausible(target)) {
            val normalized = PhoneNumber.normalize(target, phoneRegion)
                ?: return RecipientOutcome.Invalid(
                    "I need the phone number with a country code, " +
                        "for example +1 555 000 0001."
                )
            val updated = command.copy(
                target = normalized,
                params = command.params + ("phone" to normalized)
            )
            return RecipientOutcome.Resolved(
                updated,
                ResolvedRecipient(normalized, normalized)
            )
        }

        val explicitPhone = command.params["phone"]
        if (!explicitPhone.isNullOrBlank()) {
            val normalized = PhoneNumber.normalize(explicitPhone, phoneRegion)
                ?: return RecipientOutcome.Invalid(
                    "I need the phone number with a country code, " +
                        "for example +1 555 000 0001."
                )
            val updated = command.copy(
                params = command.params + ("phone" to normalized)
            )
            return RecipientOutcome.Resolved(
                updated,
                ResolvedRecipient(target, normalized)
            )
        }

        val entries = try {
            contacts.load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
        if (entries.isEmpty()) {
            return RecipientOutcome.AskPhone(
                command,
                "I can't read your contacts, so which phone number should I use " +
                    "for $target? Include the country code."
            )
        }

        return when (val match = contacts.match(entries, target)) {
            is ContactMatch.Resolved ->
                candidateOutcome(command, match.candidate)
            is ContactMatch.Ambiguous -> RecipientOutcome.AskContact(
                command,
                match.candidates,
                "I found ${match.candidates.size} contacts matching \"$target\". " +
                    "Which one? Say the full name or number."
            )
            ContactMatch.NotFound -> RecipientOutcome.AskContact(
                command,
                entries,
                "I couldn't find $target in your contacts. " +
                    "Who should I contact? Say the name or number."
            )
        }
    }

    private fun candidateOutcome(
        command: AssistantCommand,
        candidate: ContactCandidate
    ): RecipientOutcome {
        val numbers = normalizedNumbers(candidate)
        val updated = command.copy(target = candidate.name)
        return when {
            numbers.isEmpty() -> RecipientOutcome.AskPhone(
                updated,
                "I don't have a number for ${candidate.name}. " +
                    "Which phone number should I use? Include the country code."
            )
            numbers.size > 1 -> RecipientOutcome.AskNumber(
                updated,
                candidate,
                numbers,
                numberPrompt(candidate.name, numbers)
            )
            else -> {
                val number = numbers.first()
                RecipientOutcome.Resolved(
                    attach(updated, candidate, number),
                    recipient(candidate, number)
                )
            }
        }
    }

    private fun normalizedNumbers(candidate: ContactCandidate): List<ContactNumber> =
        candidate.allNumbers().map { number ->
            number.copy(
                number = PhoneNumber.normalize(number.number, phoneRegion)
                    ?: number.number
            )
        }

    private fun attach(
        command: AssistantCommand,
        candidate: ContactCandidate,
        number: ContactNumber?
    ): AssistantCommand {
        val params = command.params.toMutableMap()
        if (number != null && number.number.isNotBlank()) {
            params["phone"] = number.number
        }
        return command.copy(target = candidate.name, params = params)
    }

    private fun recipient(
        candidate: ContactCandidate,
        number: ContactNumber?
    ): ResolvedRecipient = ResolvedRecipient(
        displayName = candidate.name,
        number = number?.number.orEmpty(),
        numberLabel = number?.label,
        contactId = candidate.contactId,
        phoneId = number?.id ?: candidate.phoneId
    )

    private suspend fun resolveCandidate(
        active: Session.Contact,
        candidate: ContactCandidate,
        text: String
    ): CoordinatorReply {
        val numbers = normalizedNumbers(candidate)
        if (numbers.isEmpty()) {
            session = Session.Slot(
                command = active.command.copy(target = candidate.name),
                planId = active.planId,
                revision = active.revision + 1,
                source = active.source,
                recipient = null,
                field = "phone",
                mode = active.mode,
                expiresAtMs = expiry()
            )
            return CoordinatorReply(
                "I don't have a number for ${candidate.name}. " +
                    "Which phone number should I use? Include the country code.",
                awaitingInput = true
            )
        }
        if (numbers.size > 1) {
            session = Session.Number(
                command = active.command.copy(target = candidate.name),
                planId = active.planId,
                revision = active.revision + 1,
                source = active.source,
                candidate = candidate,
                numbers = numbers,
                mode = active.mode,
                expiresAtMs = expiry()
            )
            return CoordinatorReply(numbersPrompt(candidate, numbers), awaitingInput = true)
        }
        val number = numbers.firstOrNull()
        val command = attach(active.command, candidate, number)
        return advance(
            text = text,
            command = command,
            recipient = recipient(candidate, number),
            source = active.source,
            confidence = 1f,
            planId = active.planId,
            revision = active.revision + 1,
            mode = active.mode,
            allowDialogs = true
        )
    }

    private suspend fun askContact(
        command: AssistantCommand,
        entries: List<ContactCandidate>,
        prompt: String,
        active: Session.Contact
    ): CoordinatorReply {
        session = Session.Contact(
            command = command,
            planId = active.planId,
            revision = active.revision + 1,
            source = active.source,
            entries = entries,
            mode = active.mode,
            expiresAtMs = expiry()
        )
        return CoordinatorReply(prompt, awaitingInput = true)
    }

    private fun numbersPrompt(
        candidate: ContactCandidate,
        numbers: List<ContactNumber>
    ): String = numberPrompt(candidate.name, numbers)

    private fun numberPrompt(name: String, numbers: List<ContactNumber>): String {
        val choices = numbers.mapIndexed { index, number ->
            "${index + 1}) ${number.label ?: "number"} ${number.number}"
        }.joinToString("; ")
        return "Which number for $name? $choices"
    }

    private fun selectNumber(
        numbers: List<ContactNumber>,
        text: String
    ): ContactNumber? {
        ordinalIndex(text)?.let { index ->
            if (index in numbers.indices) return numbers[index]
        }
        val normalized = normalizeAnswer(text)
        numbers.firstOrNull { number ->
            normalizeAnswer(number.label.orEmpty()) == normalized
        }?.let { return it }
        if (PhoneNumber.isPlausible(text)) {
            numbers.firstOrNull {
                PhoneNumber.same(it.number, text, phoneRegion)
            }?.let { return it }
        }
        return null
    }

    private fun ordinalIndex(text: String): Int? {
        val answer = normalizeAnswer(text)
        return when (answer) {
            "1", "one", "first", "the first" -> 0
            "2", "two", "second", "the second" -> 1
            "3", "three", "third", "the third" -> 2
            "4", "four", "fourth", "the fourth" -> 3
            "5", "five", "fifth", "the fifth" -> 4
            else -> null
        }
    }

    private fun canonicalize(command: AssistantCommand): AssistantCommand =
        when (command.action) {
            AssistantCommand.Action.OPEN_APP -> {
                val target = appRegistry.resolve(command.target)
                if (target == null) command else command.copy(
                    target = target.name,
                    params = withPackage(command.params, target)
                )
            }
            AssistantCommand.Action.PLAY_MUSIC,
            AssistantCommand.Action.SEND_MESSAGE,
            AssistantCommand.Action.SEND_EMAIL -> {
                val app = command.params["app"]
                val target = app?.let { appRegistry.resolve(it) }
                if (target == null) command else command.copy(
                    params = withPackage(command.params + ("app" to target.name), target)
                )
            }
            else -> command
        }

    private fun withPackage(
        params: Map<String, String>,
        target: AppTarget
    ): Map<String, String> =
        if (target.packageName.isNullOrBlank()) params
        else params + ("package" to target.packageName)

    // ── Speech ───────────────────────────────────────────────────────────────

    private fun questionFor(active: Session): String = when (active) {
        is Session.Confirm -> policy.confirmationPreview(active.plan)
        is Session.Slot -> policy.questionFor(active.field)
        is Session.Contact -> "Who should I contact? Say the name or number."
        is Session.Number -> numberPrompt(active.candidate.name, active.numbers)
    }

    private fun friendlyRejection(reason: String): String = when (reason) {
        "unknown action", "unsupported action" -> "I'm not sure how to do that yet."
        else -> "I can't do that: $reason."
    }

    private fun parsedSpeech(command: AssistantCommand): String {
        val target = command.target.ifBlank { command.params["app"].orEmpty() }
        val suffix = if (target.isBlank()) "" else ": $target"
        val action = command.action.name.lowercase().replace('_', ' ')
        return "Parsed $action$suffix."
    }

    private fun speechFor(plan: ValidatedPlan, result: ActionResult): String {
        val base = when (result) {
            is ActionResult.DraftOpened -> when (plan.command.action) {
                AssistantCommand.Action.SEND_MESSAGE ->
                    "I opened ${result.app} with a draft for " +
                        "${result.recipient ?: "your contact"}. " +
                        "Review it and press send yourself."
                AssistantCommand.Action.MAKE_CALL ->
                    "I opened ${result.app} for ${result.recipient ?: "the number"}. " +
                        "Press call to continue."
                AssistantCommand.Action.SEND_EMAIL ->
                    "I opened ${result.app} with a draft for " +
                        "${result.recipient ?: "your contact"}. " +
                        "Review it and press send yourself."
                else -> "Opened ${result.app}."
            }
            is ActionResult.SearchOpened ->
                "Opening ${result.app} for \"${result.query}\"."
            is ActionResult.LaunchRequested -> "Opening ${result.app}."
            is ActionResult.ObservedComplete -> result.detail
            is ActionResult.Failed -> "I couldn't do that: ${result.reason}"
            is ActionResult.OutcomeUnknown -> result.detail
                ?: "I sent the request, but I can't confirm it completed."
            ActionResult.TimedOut -> "That took too long, so I stopped."
            ActionResult.Cancelled -> "Okay, cancelled."
        }
        return if (plan.mode == CommandMode.DRY_RUN) "Dry run: $base" else base
    }

    // ── Session plumbing ─────────────────────────────────────────────────────

    private fun expiry(): Long = nowMs() + sessionTtlMs

    private fun expireIfNeeded() {
        val active = session ?: return
        if (nowMs() >= active.expiresAtMs) session = null
    }

    private fun normalizeAnswer(text: String): String =
        text.lowercase()
            .trim()
            .trim { it in ".,!?;:\"'()[]{}" }
            .replace(Regex("\\s+"), " ")

    private fun isYes(text: String): Boolean {
        val answer = normalizeAnswer(text)
        if (answer.split(' ').any { it in modifiedAssent }) return false
        return answer in yesExact || answer in yesPhrases
    }

    private fun isCancel(text: String): Boolean {
        val answer = normalizeAnswer(text)
        val first = answer.substringBefore(' ')
        return first in cancelFirstWords ||
            "never mind" in answer || "forget it" in answer
    }

    private companion object {
        val yesExact = setOf(
            "yes", "yeah", "yep", "yup", "sure", "ok", "okay", "alright",
            "confirm", "affirmative"
        )
        val yesPhrases = setOf(
            "do it", "go ahead", "please do", "yes please", "yeah sure",
            "confirm it", "send it"
        )
        val modifiedAssent = setOf(
            "but", "not", "dont", "don't", "except", "instead", "change", "wait",
            "maybe", "however", "only", "if", "unless", "without", "never",
            "cancel", "stop", "no"
        )
        val cancelFirstWords = setOf(
            "no", "nope", "nah", "cancel", "stop", "don't", "dont", "nevermind"
        )
    }
}
