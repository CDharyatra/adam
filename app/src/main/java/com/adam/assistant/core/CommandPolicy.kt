package com.adam.assistant.core

class CommandPolicy(
    private val capabilities: CapabilityRegistry = AllowAllCapabilities,
    private val phoneRegion: String? = null,
    private val schemas: Map<AssistantCommand.Action, ActionSchema> = CommandSchemas.all
) {

    fun validate(
        candidate: CandidatePlan,
        mode: CommandMode,
        planId: String,
        revision: Int = 1
    ): ValidationDecision {
        val cmd = candidate.command
        if (cmd.action == AssistantCommand.Action.UNKNOWN) {
            return ValidationDecision.Rejected("unknown action")
        }
        if (cmd.status == AssistantCommand.PlanStatus.UNSUPPORTED) {
            return ValidationDecision.Rejected("unsupported action")
        }
        if (!capabilities.isActionSupported(cmd.action)) {
            return ValidationDecision.Rejected("unsupported action")
        }
        val schema = schemas[cmd.action]
            ?: return ValidationDecision.Rejected("unsupported action")

        if (mode == CommandMode.EXECUTE) {
            if (!capabilities.isAdapterAvailable(cmd.action)) {
                return ValidationDecision.Rejected("adapter unavailable")
            }
            appNameFor(cmd)?.let { name ->
                if (!capabilities.isAppAvailable(name, cmd.params["package"])) {
                    return ValidationDecision.Rejected("app unavailable")
                }
            }
        }

        if (isModelSource(candidate.source)) {
            if (!schema.modelAllowed) {
                return ValidationDecision.Rejected("model cannot propose that action")
            }
            if (cmd.params.containsKey("package")) {
                return ValidationDecision.Rejected("invented package")
            }
            if (candidate.recipient == null &&
                !cmd.params["phone"].isNullOrBlank()
            ) {
                return ValidationDecision.Rejected("invented phone")
            }
        }

        schema.unknownParam(cmd)?.let {
            return ValidationDecision.Rejected("unknown parameter: $it")
        }
        schema.valueError(cmd, phoneRegion)?.let {
            return ValidationDecision.Rejected(it)
        }

        if (cmd.status == AssistantCommand.PlanStatus.NEEDS_CLARIFICATION) {
            val requested = cmd.missingFields.firstOrNull()
                ?: return ValidationDecision.Rejected(
                    "clarification requested without a missing field"
                )
            if (!schema.isRequired(requested)) {
                return ValidationDecision.Rejected("unsupported clarification field")
            }
            if (!isMissing(cmd, requested)) {
                val next = schema.firstMissing(cmd)
                    ?: return ValidationDecision.Valid(
                        plan(cmd, candidate, mode, planId, revision)
                    )
                return ValidationDecision.NeedsClarification(
                    questionFor(next, cmd.action),
                    next
                )
            }
            return ValidationDecision.NeedsClarification(
                cmd.question ?: questionFor(requested, cmd.action),
                requested
            )
        }

        schema.firstMissing(cmd)?.let { field ->
            return ValidationDecision.NeedsClarification(
                questionFor(field, cmd.action),
                field
            )
        }

        if (cmd.action == AssistantCommand.Action.MAKE_CALL &&
            candidate.recipient?.number.isNullOrBlank() &&
            cmd.params["phone"].isNullOrBlank() &&
            !PhoneNumber.isPlausible(cmd.target)
        ) {
            return ValidationDecision.NeedsClarification(
                questionFor("phone", cmd.action),
                "phone"
            )
        }

        return ValidationDecision.Valid(plan(cmd, candidate, mode, planId, revision))
    }

    fun requiresConfirmation(action: AssistantCommand.Action): Boolean =
        ConsentRules.requiresConfirmation(action)

    fun authorize(plan: ValidatedPlan, nowMs: Long, ttlMs: Long): ValidatedPlan =
        ConsentRules.authorize(plan, nowMs, ttlMs)

    fun confirmationPreview(plan: ValidatedPlan): String {
        val cmd = plan.command
        val recipient = plan.recipient
        val who = recipient?.displayName ?: cmd.target.ifBlank { "them" }
        val number = recipient?.number
            ?: cmd.params["phone"].orEmpty()
        val label = recipient?.numberLabel.orEmpty()
        val variant = number.takeIf { it.isNotBlank() }?.let {
            " at $it" + if (label.isBlank()) "" else " ($label)"
        }.orEmpty()
        return when (cmd.action) {
            AssistantCommand.Action.SEND_MESSAGE -> {
                val app = cmd.params["app"] ?: "WhatsApp"
                "I'll open $app with a draft to $who$variant: " +
                    "\"${cmd.params["body"].orEmpty()}\". " +
                    "Nothing is sent until you press send. " +
                    "Say yes to continue, or no to cancel."
            }

            AssistantCommand.Action.MAKE_CALL ->
                "I'll open the dialer for $who$variant. " +
                    "Say yes to continue, or no to cancel."

            AssistantCommand.Action.SEND_EMAIL -> {
                val subject = cmd.params["subject"].orEmpty()
                val body = cmd.params["body"].orEmpty()
                val subjectText = if (subject.isBlank()) "" else " subject \"$subject\""
                "I'll open an email draft to $who$subjectText with body \"$body\". " +
                    "Nothing is sent until you press send. " +
                    "Say yes to continue, or no to cancel."
            }

            AssistantCommand.Action.CLICK ->
                "I'll tap \"${cmd.target}\" on the current screen. " +
                    "Say yes to continue, or no to cancel."

            AssistantCommand.Action.LONG_CLICK ->
                "I'll long-press \"${cmd.target}\" on the current screen. " +
                    "Say yes to continue, or no to cancel."

            AssistantCommand.Action.TYPE ->
                "I'll type \"${cmd.target}\" into the current field. " +
                    "Say yes to continue, or no to cancel."

            else -> "Say yes to proceed, or no to cancel."
        }
    }

    fun questionFor(field: String): String = questionFor(field, null)

    private fun questionFor(field: String, action: AssistantCommand.Action?): String =
        when (field) {
            "target" -> targetQuestion(action) ?: "What should I act on?"
            "body" -> if (action == AssistantCommand.Action.SEND_EMAIL) {
                "What should the email say?"
            } else {
                "What should the message say?"
            }
            "phone" -> "Which phone number should I use?"
            "subject" -> "What subject should the email use?"
            "hour" -> "What hour should the alarm be?"
            "minute" -> "Which minute should the alarm be?"
            "seconds" -> "How long should the timer be?"
            "direction" -> "Which direction?"
            "message" -> "What should I say?"
            else -> "What is the $field?"
        }

    private fun isMissing(command: AssistantCommand, field: String): Boolean =
        when (field) {
            "target" -> command.target.isBlank()
            else -> command.params[field].isNullOrBlank()
        }

    private fun plan(
        command: AssistantCommand,
        candidate: CandidatePlan,
        mode: CommandMode,
        planId: String,
        revision: Int
    ): ValidatedPlan = ValidatedPlan(
        planId = planId,
        revision = revision,
        command = command.copy(
            status = AssistantCommand.PlanStatus.READY,
            missingFields = emptyList(),
            question = null
        ),
        mode = mode,
        source = candidate.source,
        recipient = candidate.recipient
    )

    private fun targetQuestion(action: AssistantCommand.Action?): String? = when (action) {
        AssistantCommand.Action.OPEN_APP -> "Which app should I open?"
        AssistantCommand.Action.PLAY_MUSIC -> "What should I play?"
        AssistantCommand.Action.SEND_MESSAGE -> "Who should I message?"
        AssistantCommand.Action.MAKE_CALL -> "Who should I call?"
        AssistantCommand.Action.OPEN_URL -> "Which link should I open?"
        AssistantCommand.Action.SEARCH_WEB -> "What should I search for?"
        AssistantCommand.Action.NAVIGATE -> "Where should I navigate?"
        AssistantCommand.Action.SEND_EMAIL -> "Who should I email?"
        AssistantCommand.Action.OPEN_SETTINGS -> "Which settings page should I open?"
        AssistantCommand.Action.CLICK,
        AssistantCommand.Action.LONG_CLICK -> "Which item should I select?"
        AssistantCommand.Action.TYPE -> "What should I type?"
        AssistantCommand.Action.NOTE -> "What should the note say?"
        AssistantCommand.Action.CALCULATE -> "What should I calculate?"
        AssistantCommand.Action.ADD_CALENDAR_EVENT -> "What should the event be?"
        else -> null
    }

    private fun isModelSource(source: MatchSource): Boolean =
        source == MatchSource.NATIVE || source == MatchSource.MEDIAPIPE

    /**
     * The app this command refers to, or null for actions with no app target.
     * OPEN_APP carries its app in [AssistantCommand.target]; music/message/email
     * carry it in the "app" param and may default to a known app in the executor,
     * so a missing app name means "use the default" rather than "user asked for
     * an unavailable app" — in that case availability is left to the executor.
     */
    private fun appNameFor(cmd: AssistantCommand): String? = when (cmd.action) {
        AssistantCommand.Action.OPEN_APP -> cmd.target.takeIf { it.isNotBlank() }
        AssistantCommand.Action.PLAY_MUSIC,
        AssistantCommand.Action.SEND_MESSAGE,
        AssistantCommand.Action.SEND_EMAIL -> cmd.params["app"]?.takeIf { it.isNotBlank() }
        else -> null
    }
}
