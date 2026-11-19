package com.adam.assistant.llm

import com.adam.assistant.core.ArtifactCheck
import com.adam.assistant.core.ModelArtifact
import com.adam.assistant.core.ProvisionVerifier

/**
 * Versioned manifest layer for provisioned models (Task 9).
 *
 * A [ProvisionedModel] records the metadata the brief requires (runtime,
 * version/revision, language, architecture, streaming/batch mode, exact
 * artifact filenames, license/NOTICE). [ModelArtifact.sha256] is optional and
 * must come from a real provenance source — Adam never invents a digest; when
 * it is absent, an artifact can still be [ArtifactCheck.Unverified] by size but
 * is never claimed cryptographically verified.
 */
data class ProvisionedModel(
    val id: String,
    val kind: ModelDownloader.Kind,
    val version: String,
    val revision: String? = null,
    val language: String? = null,
    val architecture: String? = null,
    val runtime: String? = null,
    val batchOrStreaming: String? = null,
    val license: String? = null,
    val notice: String? = null,
    val artifacts: List<ModelArtifact> = emptyList()
)

sealed interface ModelVerification {
    data class Ready(val model: ProvisionedModel) : ModelVerification
    data class Incomplete(
        val missing: List<String>,
        val failed: List<String>
    ) : ModelVerification {
        val isUseful: Boolean get() = missing.isNotEmpty() || failed.isNotEmpty()
    }
}

object ModelRegistry {

    /**
     * Verifies every artifact of [model] from observed facts. [observed]
     * reports (exists, byteLength) per artifact; [hashes] supplies a computed
     * digest when one was obtainable (else null → Unverified by size). The
     * model is Ready only when no artifact is Missing/TooSmall/mismatched, so a
     * truncated file never reaches native init.
     */
    fun verify(
        model: ProvisionedModel,
        observed: (ModelArtifact) -> Pair<Boolean, Long>,
        hashes: (ModelArtifact) -> String? = { null }
    ): ModelVerification {
        val missing = ArrayList<String>()
        val failed = ArrayList<String>()
        for (artifact in model.artifacts) {
            val (exists, bytes) = observed(artifact)
            when (ProvisionVerifier.check(exists, bytes, hashes(artifact), artifact)) {
                is ArtifactCheck.Ok, is ArtifactCheck.Unverified -> Unit
                is ArtifactCheck.Missing -> missing += artifact.name
                is ArtifactCheck.SizeMismatch -> failed += artifact.name
                is ArtifactCheck.HashMismatch -> failed += artifact.name
                is ArtifactCheck.TooSmall -> failed += artifact.name
            }
        }
        return if (missing.isEmpty() && failed.isEmpty()) {
            ModelVerification.Ready(model)
        } else {
            ModelVerification.Incomplete(missing, failed)
        }
    }
}
