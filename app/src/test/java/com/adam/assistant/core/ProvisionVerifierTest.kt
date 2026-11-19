package com.adam.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisionBudgetTest {

    @Test
    fun unknownSizeNeverBlocks() {
        assertTrue(ProvisionBudget.hasRoom(0L, 0L))
        assertTrue(ProvisionBudget.hasRoom(5L, -1L))
        assertEquals(0L, ProvisionBudget.shortfallBytes(0L, 0L))
    }

    @Test
    fun requiresHeadroomMultiple() {
        val need = ProvisionBudget.requiredForDownload(100_000_000L)
        assertEquals(300_000_000L, need)
        assertTrue(ProvisionBudget.hasRoom(300_000_000L, 100_000_000L))
        assertFalse(ProvisionBudget.hasRoom(299_999_999L, 100_000_000L))
    }

    @Test
    fun shortfallIsPositiveWhenLowOnSpace() {
        assertEquals(50_000L, ProvisionBudget.shortfallBytes(250_000L, 100_000L))
        assertEquals(0L, ProvisionBudget.shortfallBytes(400_000L, 100_000L))
    }
}

class ProvisionHashesTest {

    @Test
    fun knownVectors() {
        // Standard SHA-256 test vectors.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ProvisionHashes.sha256Hex(ByteArray(0))
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ProvisionHashes.sha256Hex("abc".toByteArray())
        )
    }

    @Test
    fun streamingAcrossBufferBoundariesMatchesWholeInput() {
        val payload = ByteArray(200 * 1024) { (it % 251).toByte() } // > one 64 KiB chunk
        assertEquals(
            ProvisionHashes.sha256Hex(payload),
            ProvisionHashes.sha256Hex(payload.inputStream())
        )
    }

    @Test
    fun matchesIsCaseInsensitiveAndTrimmed() {
        assertTrue(ProvisionHashes.matches("ABCDEF", "  abcdef  "))
        assertFalse(ProvisionHashes.matches("abcdef", "abcdee"))
    }
}

class ProvisionVerifierTest {

    @Test
    fun missingArtifact() {
        assertEquals(
            ArtifactCheck.Missing,
            ProvisionVerifier.check(false, 0L, null, ModelArtifact("x"))
        )
    }

    @Test
    fun sizeMatchWithNoDigestIsUnverified() {
        val art = ModelArtifact("x", bytes = 42L)
        assertEquals(
            ArtifactCheck.Unverified,
            ProvisionVerifier.check(true, 42L, null, art)
        )
    }

    @Test
    fun sizeMismatchDetected() {
        val art = ModelArtifact("x", bytes = 42L)
        val check = ProvisionVerifier.check(true, 10L, null, art)
        assertEquals(ArtifactCheck.SizeMismatch(10L, 42L), check)
    }

    @Test
    fun digestMatchIsOkAndMismatchDetected() {
        val digest = ProvisionHashes.sha256Hex("hello".toByteArray())
        val art = ModelArtifact("x", sha256 = digest)
        assertEquals(
            ArtifactCheck.Ok,
            ProvisionVerifier.check(true, 5L, digest, art)
        )
        val bad = ProvisionHashes.sha256Hex("hellp".toByteArray())
        assertEquals(
            ArtifactCheck.HashMismatch(digest),
            ProvisionVerifier.check(true, 5L, bad, art)
        )
    }

    @Test
    fun tooSmallWithMinimumBytes() {
        val art = ModelArtifact("x", minBytes = 8L)
        assertEquals(
            ArtifactCheck.TooSmall(3L, 8L),
            ProvisionVerifier.check(true, 3L, null, art)
        )
    }
}
