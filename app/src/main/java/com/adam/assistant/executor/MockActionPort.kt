package com.adam.assistant.executor

import com.adam.assistant.core.AssistantCommand
import com.adam.assistant.core.ValidatedPlan

class MockActionPort : ActionPort {

    data class Operation(
        val planId: String,
        val revision: Int,
        val action: AssistantCommand.Action,
        val target: String,
        val params: Map<String, String>
    )

    private val recorded = mutableListOf<Operation>()

    val operations: List<Operation>
        get() = synchronized(recorded) { recorded.toList() }

    override suspend fun execute(plan: ValidatedPlan): ActionResult {
        val cmd = plan.command
        synchronized(recorded) {
            recorded += Operation(
                planId = plan.planId,
                revision = plan.revision,
                action = cmd.action,
                target = cmd.target,
                params = cmd.params
            )
        }
        val body = cmd.params["body"]
        val app = cmd.params["app"]
        return when (cmd.action) {
            AssistantCommand.Action.SEND_MESSAGE ->
                ActionResult.DraftOpened(
                    app ?: "Messages",
                    cmd.target.takeIf { it.isNotBlank() },
                    body
                )

            AssistantCommand.Action.SEND_EMAIL ->
                ActionResult.DraftOpened(
                    "Email",
                    cmd.target.takeIf { it.isNotBlank() },
                    body
                )

            AssistantCommand.Action.MAKE_CALL ->
                ActionResult.DraftOpened(
                    "Dialer",
                    cmd.target.takeIf { it.isNotBlank() },
                    null
                )

            AssistantCommand.Action.PLAY_MUSIC ->
                ActionResult.SearchOpened(app ?: "Spotify", cmd.target)

            AssistantCommand.Action.SEARCH_WEB ->
                ActionResult.SearchOpened("Browser", cmd.target)

            AssistantCommand.Action.OPEN_APP,
            AssistantCommand.Action.OPEN_SETTINGS,
            AssistantCommand.Action.OPEN_URL,
            AssistantCommand.Action.NAVIGATE,
            AssistantCommand.Action.TAKE_PHOTO ->
                ActionResult.LaunchRequested(
                    cmd.target.ifBlank { app ?: cmd.action.name }
                )

            else -> ActionResult.ObservedComplete(
                "Simulated ${cmd.action.name.lowercase().replace('_', ' ')}."
            )
        }
    }
}
