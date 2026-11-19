package com.adam.assistant.core

/**
 * Deviceless description of one on-screen element and the guarded selection
 * policy the accessibility adapter uses (Task 6).
 *
 * The real adapter ([com.adam.assistant.accessibility.AdamAccessibilityService])
 * builds a flat [ScreenItem] list from the live AccessibilityNodeInfo tree; this
 * pure model + [ScreenQuery] decide *what* may be acted on so the policy can be
 * unit-tested without a device.
 *
 * [origin] carries the concrete node to perform the action on (a clickable item
 * or its nearest clickable ancestor). It stays opaque here so this type has no
 * Android dependency.
 */
data class ScreenItem(
    /** Text or content-description, trimmed; may be empty for a bare field. */
    val label: String,
    val isVisible: Boolean = true,
    val isEnabled: Boolean = true,
    val isEditable: Boolean = false,
    val isFocused: Boolean = false,
    /** True when this item, or an ancestor, is clickable and can be the target. */
    val isActionable: Boolean = false,
    /** The concrete node a click should hit (this item or a clickable ancestor). */
    val origin: Any? = null
)

sealed interface ScreenPick {
    /** Exactly one eligible target. */
    data class Act(val item: ScreenItem) : ScreenPick
    /** More than one eligible target — must not guess; abort and disambiguate. */
    data class Ambiguous(val items: List<ScreenItem>) : ScreenPick
    /** No eligible target (blank label, none visible/enabled/actionable). */
    data object None : ScreenPick
}

object ScreenQuery {

    /**
     * Picks the single actionable element whose label contains [label]. Enforces
     * the Task 6 guards: never acts on a blank label, ignores invisible/disabled
     * or non-actionable nodes, and refuses to guess when the label is non-unique.
     */
    fun pickByLabel(items: List<ScreenItem>, label: String): ScreenPick {
        val needle = label.trim()
        if (needle.isEmpty()) return ScreenPick.None
        val lower = needle.lowercase()
        val matches = items.filter {
            it.isActionable &&
                it.isVisible &&
                it.isEnabled &&
                it.label.isNotBlank() &&
                it.label.lowercase().contains(lower)
        }
        return when (matches.size) {
            0 -> ScreenPick.None
            1 -> ScreenPick.Act(matches[0])
            else -> ScreenPick.Ambiguous(matches)
        }
    }

    /**
     * The editable field to type into: the focused editable only. There is
     * deliberately no fallback to an arbitrary editable field (Task 6).
     */
    fun pickEditable(items: List<ScreenItem>): ScreenItem? =
        items.firstOrNull { it.isEditable && it.isVisible && it.isEnabled && it.isFocused }

    /** Stable, de-duplicated readable text for "read screen". */
    fun readableText(items: List<ScreenItem>): String =
        LinkedHashSet<String>().apply {
            items.forEach { if (it.label.isNotBlank()) add(it.label) }
        }.joinToString(". ")
}
