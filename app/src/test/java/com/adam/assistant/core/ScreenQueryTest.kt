package com.adam.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenQueryTest {

    private fun item(
        label: String,
        visible: Boolean = true,
        enabled: Boolean = true,
        actionable: Boolean = true,
        editable: Boolean = false,
        focused: Boolean = false,
        origin: Any? = "node:$label"
    ) = ScreenItem(
        label = label,
        isVisible = visible,
        isEnabled = enabled,
        isEditable = editable,
        isFocused = focused,
        isActionable = actionable,
        origin = origin
    )

    @Test
    fun singleMatchIsSelected() {
        val pick = ScreenQuery.pickByLabel(listOf(item("Send"), item("Cancel")), "send")
        assertTrue(pick is ScreenPick.Act)
        assertEquals("Send", (pick as ScreenPick.Act).item.label)
    }

    @Test
    fun blankLabelIsNeverActedOn() {
        assertTrue(ScreenQuery.pickByLabel(listOf(item("Send")), "   ") is ScreenPick.None)
        assertTrue(ScreenQuery.pickByLabel(listOf(item("Send")), "") is ScreenPick.None)
    }

    @Test
    fun noMatchIsNone() {
        assertTrue(ScreenQuery.pickByLabel(listOf(item("Send")), "share") is ScreenPick.None)
    }

    @Test
    fun invisibleOrDisabledOrNonActionableAreSkipped() {
        val items = listOf(
            item("Send", visible = false),
            item("Send", enabled = false),
            item("Send", actionable = false)
        )
        assertTrue(ScreenQuery.pickByLabel(items, "send") is ScreenPick.None)
    }

    @Test
    fun duplicatedLabelIsAmbiguousNotGuessed() {
        val items = listOf(item("Send", origin = "a"), item("Send", origin = "b"))
        val pick = ScreenQuery.pickByLabel(items, "send")
        assertTrue(pick is ScreenPick.Ambiguous)
        assertEquals(2, (pick as ScreenPick.Ambiguous).items.size)
    }

    @Test
    fun uniqueVisibleWinsOverHiddenTwin() {
        val items = listOf(
            item("Send", visible = false, origin = "hidden"),
            item("Send", visible = true, origin = "shown")
        )
        val pick = ScreenQuery.pickByLabel(items, "send")
        assertTrue(pick is ScreenPick.Act)
        assertEquals("shown", (pick as ScreenPick.Act).item.origin)
    }

    @Test
    fun editablePickRequiresFocusAndNoArbitraryFallback() {
        assertNull(ScreenQuery.pickEditable(listOf(item("Search box", editable = true))))
        val focused = ScreenQuery.pickEditable(
            listOf(
                item("Search box", editable = true, focused = true, origin = "field"),
                item("Other", editable = true, origin = "other")
            )
        )
        assertEquals("field", focused?.origin)
    }

    @Test
    fun readableTextDeDuplicatesAndSkipsBlanks() {
        val text = ScreenQuery.readableText(
            listOf(item("Hello"), item(""), item("Hello"), item("World"))
        )
        assertEquals("Hello. World", text)
    }
}
