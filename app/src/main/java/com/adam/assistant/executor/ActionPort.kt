package com.adam.assistant.executor

import com.adam.assistant.core.ValidatedPlan

interface ActionPort {
    suspend fun execute(plan: ValidatedPlan): ActionResult
}

sealed interface ActionResult {
    data class DraftOpened(
        val app: String,
        val recipient: String?,
        val body: String?
    ) : ActionResult

    data class SearchOpened(val app: String, val query: String) : ActionResult

    data class LaunchRequested(val app: String) : ActionResult

    data class ObservedComplete(val detail: String) : ActionResult

    data class Failed(val reason: String) : ActionResult

    data class OutcomeUnknown(val detail: String? = null) : ActionResult

    data object TimedOut : ActionResult
    data object Cancelled : ActionResult
}
