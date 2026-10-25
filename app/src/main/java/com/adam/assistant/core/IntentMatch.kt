package com.adam.assistant.core

/** Which brain layer answered a command. */
enum class MatchSource {
    NATIVE,
    EXPLICIT,
    LEARNED,
    TEMPLATE,
    MEDIAPIPE,
    FUZZY,
    NOVEL
}

/**
 * The result of routing one utterance: the action plan plus how sure we are
 * and which layer produced it. Replaces the old "just a command" contract so
 * every decision is inspectable (and testable).
 */
data class IntentMatch(
    val command: AssistantCommand,
    val confidence: Float,
    val source: MatchSource,
    val reasonCode: String = ""
)
