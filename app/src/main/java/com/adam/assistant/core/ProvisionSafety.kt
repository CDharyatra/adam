package com.adam.assistant.core

/**
 * Pure, deviceless guards for model provisioning (Task 9). Kept free of I/O so
 * archive containment and entry rejection are unit-testable; the downloader
 * calls these before writing any extracted byte.
 */
object ProvisionSafety {

    /**
     * Why an archive entry must be rejected, or null when it is safe to extract.
     * Rejects blank/absolute/traversal names, symbolic links, and special files.
     */
    fun entryRejection(
        entryName: String?,
        isSymlink: Boolean = false,
        isSpecial: Boolean = false
    ): String? {
        if (entryName.isNullOrBlank()) return "empty entry name"
        if (isSymlink) return "symbolic link entry: $entryName"
        if (isSpecial) return "special-file entry: $entryName"
        val name = entryName.replace('\\', '/')
        if (name.startsWith("/")) return "absolute entry: $entryName"
        if (name.split('/').any { it == ".." }) return "traversal entry: $entryName"
        return null
    }

    /** Strips one leading path component (the archive's top folder). */
    fun stripLeadingComponent(entryName: String): String {
        if (entryName.isBlank()) return ""
        val name = entryName.replace('\\', '/')
        val idx = name.indexOf('/')
        return if (idx >= 0) name.substring(idx + 1) else ""
    }

    /**
     * True when [childCanonical] is [parentCanonical]'s descendant by real path
     * component — not a string prefix, so a sibling like "…-evil" cannot pass.
     */
    fun isDescendantOf(childCanonical: String, parentCanonical: String): Boolean {
        val parent = parentCanonical.replace('\\', '/').trimEnd('/')
        val child = childCanonical.replace('\\', '/')
        if (parent.isEmpty() || child.isEmpty()) return false
        return child == parent || child.startsWith("$parent/")
    }
}
