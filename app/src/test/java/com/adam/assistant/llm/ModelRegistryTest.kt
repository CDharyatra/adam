package com.adam.assistant.llm

import com.adam.assistant.core.ModelArtifact
import com.adam.assistant.core.ProvisionHashes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryTest {

    private val model = ProvisionedModel(
        id = "sherpa-whisper-small.en",
        kind = ModelDownloader.Kind.ASR,
        version = "small.en-int8",
        language = "en",
        architecture = "arm64-v8a",
        runtime = "sherpa-onnx",
        batchOrStreaming = "batch",
        license = "Apache-2.0",
        artifacts = listOf(
            ModelArtifact("encoder.onnx", bytes = 100L),
            ModelArtifact("tokens.txt", bytes = 10L)
        )
    )

    private fun observed(vararg sizes: Long): (ModelArtifact) -> Pair<Boolean, Long> =
        { artifact ->
            val idx = model.artifacts.indexOfFirst { it.name == artifact.name }
            val size = sizes.getOrNull(idx) ?: 0L
            (size >= 0L) to size
        }

    @Test
    fun readyWhenAllPresentAndSized() {
        val result = ModelRegistry.verify(model, observed(100L, 10L))
        assertTrue(result is ModelVerification.Ready)
    }

    @Test
    fun incompleteWhenFileMissing() {
        val result = ModelRegistry.verify(
            model,
            { a -> if (a.name == "tokens.txt") false to 0L else true to 100L }
        )
        assertTrue(result is ModelVerification.Incomplete)
        assertEquals(listOf("tokens.txt"), (result as ModelVerification.Incomplete).missing)
    }

    @Test
    fun incompleteWhenSizeMismatch() {
        val result = ModelRegistry.verify(model, observed(50L, 10L))
        assertTrue(result is ModelVerification.Incomplete)
        assertEquals(listOf("encoder.onnx"), (result as ModelVerification.Incomplete).failed)
    }

    @Test
    fun digestVerificationOkAndMismatch() {
        val digest = ProvisionHashes.sha256Hex("payload".toByteArray())
        val digestModel = model.copy(
            artifacts = listOf(
                ModelArtifact("encoder.onnx", sha256 = digest, bytes = 100L)
            )
        )
        val ok = ModelRegistry.verify(digestModel, { true to 100L }, { digest })
        assertTrue(ok is ModelVerification.Ready)

        val bad = ProvisionHashes.sha256Hex("payloadX".toByteArray())
        val mismatch = ModelRegistry.verify(digestModel, { true to 100L }, { bad })
        assertTrue(mismatch is ModelVerification.Incomplete)
        assertEquals(listOf("encoder.onnx"), (mismatch as ModelVerification.Incomplete).failed)
    }
}
