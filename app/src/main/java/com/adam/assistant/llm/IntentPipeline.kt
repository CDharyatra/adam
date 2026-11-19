package com.adam.assistant.llm

import com.adam.assistant.core.AssistantCommand
import com.adam.assistant.core.IntentMatch
import com.adam.assistant.core.MatchSource

/**
 * Ordered intent pipeline (first confident match wins — never globally rank
 * scores from different matchers, they share no calibrated scale).
 *
 * Each [Stage] is a pure function: text in, typed candidate out (or null).
 * Stages that throw are skipped so one bad matcher can't kill a command.
 * Malformed or unsupported candidates abstain before selection.
 */
class IntentPipeline(
    private val stages: List<Stage>,
    private val floors: Map<MatchSource, Float> = mapOf(
        MatchSource.EXPLICIT to 0.9f,
        MatchSource.LEARNED to 0.5f,
        MatchSource.TEMPLATE to 0.5f,
        MatchSource.MEDIAPIPE to 0.5f,
        MatchSource.FUZZY to 0.4f
    )
) {
    fun interface Stage {
        suspend fun match(text: String): Candidate?
    }

    data class Candidate(
        val command: AssistantCommand,
        val confidence: Float,
        val source: MatchSource,
        val reasonCode: String = "",
        val spans: Map<String, IntRange> = emptyMap()
    )

    suspend fun route(text: String): Candidate? {
        for (stage in stages) {
            val r = try {
                stage.match(text)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: continue
            if (r.confidence < (floors[r.source] ?: 0f)) continue
            if (!valid(r.command)) continue
            return r
        }
        return null
    }

    private fun valid(command: AssistantCommand): Boolean {
        if (command.action == AssistantCommand.Action.UNKNOWN) return false
        if (command.status == AssistantCommand.PlanStatus.UNSUPPORTED) return false
        if (command.status == AssistantCommand.PlanStatus.NEEDS_CLARIFICATION &&
            command.missingFields.isEmpty()
        ) {
            return false
        }
        return true
    }
}

/** Adapts a typed routing result into a pipeline candidate. */
fun IntentMatch.toCandidate(): IntentPipeline.Candidate =
    IntentPipeline.Candidate(command, confidence, source, reasonCode, spans)
