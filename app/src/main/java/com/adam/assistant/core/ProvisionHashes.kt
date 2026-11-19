package com.adam.assistant.core

import java.io.InputStream
import java.security.MessageDigest

/**
 * SHA-256 helpers for verified provisioning (Task 9).
 *
 * Digest values must come from a real manifest/provenance source — Adam never
 * invents a checksum. These are the primitives that verify a downloaded artifact
 * against a known digest and classify the outcome with
 * [com.adam.assistant.core.ProvisionVerifier].
 */
object ProvisionHashes {

    /** Lowercase hex SHA-256 over a stream (does not close [input]). */
    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    /** Lowercase hex SHA-256 over bytes. */
    fun sha256Hex(bytes: ByteArray): String = sha256Hex(bytes.inputStream())

    /** Compares a computed digest to an expected one, case-insensitively. */
    fun matches(actual: String, expected: String): Boolean =
        actual.equals(expected.trim(), ignoreCase = true)

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append("%02x".format(b))
        return sb.toString()
    }
}
