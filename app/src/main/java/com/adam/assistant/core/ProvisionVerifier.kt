package com.adam.assistant.core

/**
 * Typed verification of a downloaded model artifact (Task 9).
 *
 * [sha256] is optional: it must come from a real manifest/provenance source,
 * never invented. When absent the artifact is [ArtifactCheck.Unverified] — size
 * checks may still pass, but Adam does not claim cryptographic integrity it
 * cannot prove. Pure: the caller supplies [actualBytes]/[actualHash] and decides
 * whether a digest could be computed.
 */
data class ModelArtifact(
    val name: String,
    val sha256: String? = null,
    val bytes: Long? = null,
    val minBytes: Long = 1L
)

sealed interface ArtifactCheck {
    data object Ok : ArtifactCheck
    data object Missing : ArtifactCheck
    data class TooSmall(val actual: Long, val min: Long) : ArtifactCheck
    data class SizeMismatch(val actual: Long, val expected: Long) : ArtifactCheck
    data class HashMismatch(val expected: String) : ArtifactCheck
    /** Size checks passed but no digest is available — not proof of integrity. */
    data object Unverified : ArtifactCheck
}

object ProvisionVerifier {

    fun check(
        exists: Boolean,
        actualBytes: Long,
        actualHash: String?,
        artifact: ModelArtifact
    ): ArtifactCheck {
        if (!exists) return ArtifactCheck.Missing
        if (actualBytes < artifact.minBytes) {
            return ArtifactCheck.TooSmall(actualBytes, artifact.minBytes)
        }
        artifact.bytes?.let { expected ->
            if (actualBytes != expected) {
                return ArtifactCheck.SizeMismatch(actualBytes, expected)
            }
        }
        val expectedHash = artifact.sha256 ?: return ArtifactCheck.Unverified
        if (actualHash == null) return ArtifactCheck.Unverified
        return if (ProvisionHashes.matches(actualHash, expectedHash)) {
            ArtifactCheck.Ok
        } else {
            ArtifactCheck.HashMismatch(expectedHash)
        }
    }
}
