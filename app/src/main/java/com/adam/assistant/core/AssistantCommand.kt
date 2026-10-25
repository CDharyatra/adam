package com.adam.assistant.core

/**
 * Represents a parsed command from the LLM.
 * action  : what to do  (OPEN_APP, PLAY_MUSIC, SEND_MESSAGE, …)
 * target  : primary target (app name, contact name, song title, URL …)
 * params  : extra key/value pairs (artist, playlist, message body, direction …)
 * status  : typed plan outcome — READY to execute, NEEDS_CLARIFICATION when a
 *           required slot is missing (see missingFields/question), UNSUPPORTED
 *           when nothing can handle the request.
 */
data class AssistantCommand(
    val action: Action,
    val target: String = "",
    val params: Map<String, String> = emptyMap(),
    val status: PlanStatus = PlanStatus.READY,
    val missingFields: List<String> = emptyList(),
    val question: String? = null
) {
    enum class PlanStatus {
        READY,
        NEEDS_CLARIFICATION,
        UNSUPPORTED
    }

    enum class Action {
        OPEN_APP,
        PLAY_MUSIC,
        SEND_MESSAGE,

        MAKE_CALL,
        OPEN_URL,
        SEARCH_WEB,

        SET_ALARM,
        SET_TIMER,
        ADD_CALENDAR_EVENT,

        SEND_EMAIL,
        TAKE_PHOTO,
        NAVIGATE,

        OPEN_SETTINGS,
        ADJUST_VOLUME,

        READ_SCREEN,
        LONG_CLICK,
        OPEN_NOTIFICATIONS,
        CLOSE_NOTIFICATIONS,
        SWIPE,

        CALCULATE,
        NOTE,

        CLICK,
        SCROLL,
        TYPE,
        BACK,
        HOME,
        RECENTS,

        REPLY,
        UNKNOWN
    }
}
