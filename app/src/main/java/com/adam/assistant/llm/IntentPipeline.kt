package com.adam.assistant.llm

import com.adam.assistant.core.IntentMatch
import com.adam.assistant.core.MatchSource

/**
 * Ordered intent pipeline (first confident match wins — never globally rank
 * scores from different matchers, they share no calibrated scale).
 *
 * Each [Stage] is a pure function: text in, scored JSON out (or null).
 * Stages that throw are skipped so one bad matcher can't kill a command.
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
        suspend fun match(text: String): StageResult?
    }

    data class StageResult(
        val json: String,
        val confidence: Float,
        val source: MatchSource
    )

    suspend fun route(text: String): StageResult? {
        for (stage in stages) {
            val r = try {
                stage.match(text)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: continue
            if (r.confidence >= (floors[r.source] ?: 0f)) return r
        }
        return null
    }
}

/** Convenience: adapts an [IntentMatch]-producing router into the pipeline. */
fun IntentMatch.toStageResult(json: String): IntentPipeline.StageResult =
    IntentPipeline.StageResult(json, confidence, source)
