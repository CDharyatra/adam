package com.adam.assistant.core

/**
 * Free-space gating for model provisioning (Task 9). Pure arithmetic so the
 * policy is unit-testable; the downloader calls [hasRoom] before it starts
 * writing bytes. Downloads, extraction and staging all need room at once, so we
 * require a headroom multiple of the download size to be free — otherwise a low
 * disk would silently produce a truncated model that later fails natively.
 */
object ProvisionBudget {

    /** Require this multiple of the download size free (archive + staging). */
    const val EXTRACTION_HEADROOM_MULTIPLIER = 3L

    /** Lower multiple for single-file / per-file downloads with no extraction. */
    const val DOWNLOAD_HEADROOM_MULTIPLIER = 2L

    /**
     * Bytes that must be free for a download of [downloadBytes] using
     * [multiplier] headroom; 0 = unknown, never blocks.
     */
    fun requiredForDownload(
        downloadBytes: Long,
        multiplier: Long = EXTRACTION_HEADROOM_MULTIPLIER
    ): Long = if (downloadBytes <= 0L || multiplier <= 0L) 0L else downloadBytes * multiplier

    fun hasRoom(
        freeBytes: Long,
        downloadBytes: Long,
        multiplier: Long = EXTRACTION_HEADROOM_MULTIPLIER
    ): Boolean {
        val need = requiredForDownload(downloadBytes, multiplier)
        return need <= 0L || freeBytes >= need
    }

    fun shortfallBytes(
        freeBytes: Long,
        downloadBytes: Long,
        multiplier: Long = EXTRACTION_HEADROOM_MULTIPLIER
    ): Long = (requiredForDownload(downloadBytes, multiplier) - freeBytes).coerceAtLeast(0L)
}
