package com.adam.assistant.core

/**
 * Backward-compatible package lookup backed by the [LaunchableApps] registry.
 *
 * Kept so existing callers (executor/registry) and tests resolve against one
 * source of truth; new alias/OEM-aware resolution lives in [LaunchableApps].
 */
object KnownApps {

    private val registry = LaunchableApps()

    /** Static mapping only via the registry; null when the name is unknown. */
    fun staticPackage(name: String): String? = registry.packageFor(name)
}
