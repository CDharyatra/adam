package com.adam.assistant.core

enum class InputSource {
    TYPED,
    VOICE
}

enum class CommandMode {
    PARSE_ONLY,
    DRY_RUN,
    EXECUTE
}

data class ResolvedRecipient(
    val displayName: String,
    val number: String = "",
    val numberLabel: String? = null,
    val contactId: Long? = null,
    val phoneId: Long? = null
)

data class CandidatePlan(
    val rawText: String,
    val command: AssistantCommand,
    val confidence: Float,
    val source: MatchSource,
    val reasonCode: String = "",
    val recipient: ResolvedRecipient? = null
)

data class ExecutionConsent(
    val planId: String,
    val revision: Int,
    val mode: CommandMode,
    val action: AssistantCommand.Action,
    val appName: String,
    val packageName: String,
    val recipientName: String,
    val contactId: Long?,
    val phoneId: Long?,
    val number: String,
    val numberLabel: String,
    val body: String,
    val subject: String,
    val issuedAtMs: Long,
    val expiresAtMs: Long
) {
    fun matches(plan: ValidatedPlan): Boolean {
        val cmd = plan.command
        val recipient = plan.recipient
        return planId == plan.planId &&
            revision == plan.revision &&
            mode == plan.mode &&
            action == cmd.action &&
            appName == cmd.params["app"].orEmpty() &&
            packageName == cmd.params["package"].orEmpty() &&
            recipientName == (recipient?.displayName ?: cmd.target) &&
            contactId == recipient?.contactId &&
            phoneId == recipient?.phoneId &&
            number == resolvedNumber(plan) &&
            numberLabel == (recipient?.numberLabel ?: "").orEmpty() &&
            body == cmd.params["body"].orEmpty() &&
            subject == cmd.params["subject"].orEmpty()
    }
}

data class ValidatedPlan(
    val planId: String,
    val revision: Int,
    val command: AssistantCommand,
    val mode: CommandMode,
    val source: MatchSource,
    val recipient: ResolvedRecipient? = null,
    val consent: ExecutionConsent? = null
) {
    val revisionKey: String get() = "$planId:$revision"
}

sealed interface ValidationDecision {
    data class Valid(val plan: ValidatedPlan) : ValidationDecision
    data class NeedsClarification(val question: String, val field: String) : ValidationDecision
    data class Rejected(val reason: String) : ValidationDecision
}

sealed interface ConsentCheck {
    data object Allowed : ConsentCheck
    data class Denied(val reason: String) : ConsentCheck
}

object ConsentRules {

    private val confirmedActions = setOf(
        AssistantCommand.Action.SEND_MESSAGE,
        AssistantCommand.Action.MAKE_CALL,
        AssistantCommand.Action.SEND_EMAIL,
        AssistantCommand.Action.CLICK,
        AssistantCommand.Action.TYPE,
        AssistantCommand.Action.LONG_CLICK
    )

    fun requiresConfirmation(action: AssistantCommand.Action): Boolean =
        action in confirmedActions

    fun authorize(plan: ValidatedPlan, nowMs: Long, ttlMs: Long): ValidatedPlan {
        if (!requiresConfirmation(plan.command.action)) return plan
        val command = plan.command
        val recipient = plan.recipient
        return plan.copy(
            consent = ExecutionConsent(
                planId = plan.planId,
                revision = plan.revision,
                mode = plan.mode,
                action = command.action,
                appName = command.params["app"].orEmpty(),
                packageName = command.params["package"].orEmpty(),
                recipientName = recipient?.displayName ?: command.target,
                contactId = recipient?.contactId,
                phoneId = recipient?.phoneId,
                number = resolvedNumber(plan),
                numberLabel = (recipient?.numberLabel ?: "").orEmpty(),
                body = command.params["body"].orEmpty(),
                subject = command.params["subject"].orEmpty(),
                issuedAtMs = nowMs,
                expiresAtMs = nowMs + ttlMs
            )
        )
    }

    fun verify(plan: ValidatedPlan, nowMs: Long): ConsentCheck {
        if (!requiresConfirmation(plan.command.action)) return ConsentCheck.Allowed
        val consent = plan.consent
            ?: return ConsentCheck.Denied("confirmation required")
        if (consent.planId != plan.planId || consent.revision != plan.revision) {
            return ConsentCheck.Denied("stale confirmation")
        }
        if (consent.mode != plan.mode) {
            return ConsentCheck.Denied("mode changed after confirmation")
        }
        if (nowMs < consent.issuedAtMs) {
            return ConsentCheck.Denied("confirmation issued in the future")
        }
        if (nowMs >= consent.expiresAtMs) {
            return ConsentCheck.Denied("confirmation expired")
        }
        if (!consent.matches(plan)) {
            return ConsentCheck.Denied("plan changed after confirmation")
        }
        return ConsentCheck.Allowed
    }

}

private fun resolvedNumber(plan: ValidatedPlan): String =
    plan.recipient?.number?.takeIf { it.isNotBlank() }
        ?: plan.command.params["phone"].orEmpty()
