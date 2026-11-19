package com.adam.assistant.executor

import com.adam.assistant.core.AssistantCommand
import com.adam.assistant.core.ConsentCheck
import com.adam.assistant.core.ConsentRules
import com.adam.assistant.core.ValidatedPlan

class CommandExecutorActionPort(
    private val executor: CommandExecutor,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) : ActionPort {

    override suspend fun execute(plan: ValidatedPlan): ActionResult {
        when (val consent = ConsentRules.verify(plan, nowMs())) {
            is ConsentCheck.Denied -> return ActionResult.Failed("blocked: ${consent.reason}")
            ConsentCheck.Allowed -> Unit
        }
        val detail = executor.execute(plan)
        return toResult(plan, detail)
    }

    private fun toResult(plan: ValidatedPlan, detail: String): ActionResult {
        if (detail.startsWith("Blocked:")) {
            return ActionResult.Failed(detail.removePrefix("Blocked:").trim())
        }
        if (detail.contains("not installed", ignoreCase = true) ||
            detail.contains("could not", ignoreCase = true) ||
            detail.contains("couldn't", ignoreCase = true) ||
            detail.contains("no email app", ignoreCase = true) ||
            detail.contains("no calendar app", ignoreCase = true) ||
            detail.contains("no clock app", ignoreCase = true) ||
            detail.contains("no camera app", ignoreCase = true)
        ) {
            return ActionResult.Failed(detail)
        }
        val action = plan.command.action
        return when (action) {
            AssistantCommand.Action.SEND_MESSAGE,
            AssistantCommand.Action.SEND_EMAIL,
            AssistantCommand.Action.MAKE_CALL -> ActionResult.DraftOpened(
                app = plan.command.params["app"] ?: defaultApp(action),
                recipient = plan.recipient?.displayName
                    ?: plan.command.target.takeIf { it.isNotBlank() },
                body = plan.command.params["body"]
            )
            AssistantCommand.Action.PLAY_MUSIC,
            AssistantCommand.Action.SEARCH_WEB -> ActionResult.SearchOpened(
                app = plan.command.params["app"] ?: defaultApp(action),
                query = plan.command.target
            )
            AssistantCommand.Action.OPEN_APP,
            AssistantCommand.Action.OPEN_SETTINGS,
            AssistantCommand.Action.OPEN_URL,
            AssistantCommand.Action.NAVIGATE,
            AssistantCommand.Action.TAKE_PHOTO -> ActionResult.LaunchRequested(
                plan.command.target.ifBlank {
                    plan.command.params["app"] ?: defaultApp(action)
                }
            )
            else -> ActionResult.OutcomeUnknown(detail)
        }
    }

    private fun defaultApp(action: AssistantCommand.Action): String = when (action) {
        AssistantCommand.Action.SEND_MESSAGE -> "Messages"
        AssistantCommand.Action.SEND_EMAIL -> "Email"
        AssistantCommand.Action.MAKE_CALL -> "Dialer"
        AssistantCommand.Action.PLAY_MUSIC -> "Spotify"
        AssistantCommand.Action.SEARCH_WEB -> "Browser"
        AssistantCommand.Action.OPEN_APP,
        AssistantCommand.Action.OPEN_SETTINGS -> "Settings"
        AssistantCommand.Action.OPEN_URL -> "Browser"
        AssistantCommand.Action.NAVIGATE -> "Maps"
        AssistantCommand.Action.TAKE_PHOTO -> "Camera"
        else -> action.name.lowercase().replace('_', ' ')
    }
}
