package com.adam.assistant.llm

import com.adam.assistant.core.MatchSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IntentPipelineTest {

    private fun stage(json: String, conf: Float, source: MatchSource) =
        IntentPipeline.Stage { IntentPipeline.StageResult(json, conf, source) }

    @Test
    fun firstConfidentMatchWinsRegardlessOfConfidence() = runBlocking {
        val p = IntentPipeline(
            listOf(
                stage("{}", 0.5f, MatchSource.TEMPLATE),
                stage("{}", 0.99f, MatchSource.FUZZY)
            )
        )
        val r = p.route("anything")!!
        assertEquals(MatchSource.TEMPLATE, r.source)
    }

    @Test
    fun belowFloorFallsThrough() = runBlocking {
        val p = IntentPipeline(
            listOf(
                stage("{}", 0.1f, MatchSource.TEMPLATE), // floor is 0.5
                stage("{\"a\":1}", 0.9f, MatchSource.EXPLICIT)
            ),
            mapOf(MatchSource.TEMPLATE to 0.5f, MatchSource.EXPLICIT to 0.9f)
        )
        val r = p.route("anything")!!
        assertEquals(MatchSource.EXPLICIT, r.source)
    }

    @Test
    fun throwingStageIsSkipped() = runBlocking {
        val p = IntentPipeline(
            listOf(
                IntentPipeline.Stage { throw RuntimeException("boom") },
                stage("{}", 0.9f, MatchSource.EXPLICIT)
            )
        )
        assertEquals(MatchSource.EXPLICIT, p.route("x")!!.source)
    }

    @Test
    fun allMissReturnsNull() = runBlocking {
        val p = IntentPipeline(listOf(IntentPipeline.Stage { null }))
        assertNull(p.route("x"))
    }

    @Test
    fun emptyPipelineReturnsNull() = runBlocking {
        assertNull(IntentPipeline(emptyList()).route("x"))
    }

    @Test
    fun suspendStageIsAwaited() = runBlocking {
        val p = IntentPipeline(
            listOf(
                IntentPipeline.Stage { t ->
                    kotlinx.coroutines.delay(10)
                    IntentPipeline.StageResult("{\"a\":\"$t\"}", 0.9f, MatchSource.MEDIAPIPE)
                }
            )
        )
        val r = p.route("hello")!!
        assertEquals(MatchSource.MEDIAPIPE, r.source)
        assertTrue(r.json.contains("hello"))
    }
}
