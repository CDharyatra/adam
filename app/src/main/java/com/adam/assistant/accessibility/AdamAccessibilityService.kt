package com.adam.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Core accessibility service.
 *
 * Exposes a singleton [instance] so [CommandExecutor] can call it directly.
 * All UI interactions are performed here: find nodes, click, type, scroll, etc.
 */
class AdamAccessibilityService : AccessibilityService() {

    companion object {
        private val _instance = MutableStateFlow<AdamAccessibilityService?>(null)
        val instance: StateFlow<AdamAccessibilityService?> = _instance

        val isConnected: Boolean get() = _instance.value != null
    }

    override fun onServiceConnected() {
        _instance.value = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        _instance.value = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    // ── Public actions ───────────────────────────────────────────────────────

    fun performBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun performHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun performRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    /**
     * Finds the first node whose text or content-description contains [label]
     * (case-insensitive) and clicks it.
     */
    fun clickNodeWithText(label: String): Boolean {
        val node = findNodeByText(rootInActiveWindow, label) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK).also { node.recycle() }
    }

    /** Types [text] into the currently focused input field. */
    fun typeText(text: String): Boolean {
        val focused = findFocusedInput(rootInActiveWindow) ?: return false
        val args = Bundle().apply { putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args).also { focused.recycle() }
    }

    /**
     * Alternative to [typeText]: types [text] directly into [node] without
     * relying on the field being focused. Prefer this when you have located a
     * specific editable node (e.g. a search box you just clicked).
     */
    fun typeInto(node: AccessibilityNodeInfo?, text: String): Boolean {
        node ?: return false
        val args = Bundle().apply { putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args).also { node.recycle() }
    }

    /** Clicks [node] and recycles it. */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK).also { node.recycle() }
    }

    /** Finds the first editable text field in the tree (e.g. a search box or EditText). */
    fun findEditableInput(root: AccessibilityNodeInfo? = rootInActiveWindow): AccessibilityNodeInfo? {
        root ?: return null
        if (root.isEditable) return root
        if (root.className?.toString()?.contains("EditText") == true) return root
        for (i in 0 until root.childCount) {
            val r = findEditableInput(root.getChild(i))
            if (r != null) return r
        }
        return null
    }

    /** Scrolls the first scrollable view in the given direction. */
    fun scroll(direction: String): Boolean {
        val node = findScrollable(rootInActiveWindow) ?: return false
        val action = if (direction.equals("up", ignoreCase = true))
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        else
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        return node.performAction(action).also { node.recycle() }
    }

    /**
     * Performs a tap gesture at the centre of [node]'s bounds.
     * Useful when ACTION_CLICK doesn't work on custom views.
     */
    fun tapNode(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /** Finds the first node containing [label] and long-presses it. */
    fun longClickNodeWithText(label: String): Boolean {
        val node = findNodeByText(rootInActiveWindow, label) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK).also { node.recycle() }
    }

    /**
     * Performs a long-press gesture at the centre of [node]'s bounds.
     * Used when ACTION_LONG_CLICK is not handled by the target view.
     */
    fun longPressNode(node: AccessibilityNodeInfo, durationMs: Long = 600): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /** Opens the notification shade. */
    fun openNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    /** Closes the notification shade / dialogs by pressing back. */
    fun closeNotifications() = performGlobalAction(GLOBAL_ACTION_BACK)

    /**
     * Swipes the screen in [direction] ("up", "down", "left", "right").
     * Uses real display metrics so it works regardless of window bounds.
     */
    fun swipe(direction: String, durationMs: Long = 300): Boolean {
        val m = displayMetrics()
        val cx = m.widthPixels / 2f
        val cy = m.heightPixels / 2f
        val (x1, y1, x2, y2) = when (direction.lowercase()) {
            "up"    -> floatArrayOf(cx, cy * 1.4f, cx, cy * 0.6f)
            "down"  -> floatArrayOf(cx, cy * 0.6f, cx, cy * 1.4f)
            "left"  -> floatArrayOf(cx * 1.4f, cy, cx * 0.6f, cy)
            "right" -> floatArrayOf(cx * 0.6f, cy, cx * 1.4f, cy)
            else    -> return false
        }
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /**
     * Walks the current window's accessibility tree and returns every visible
     * text/content-description concatenated into one string. Used to "read
     * the screen" back to the user.
     */
    fun readScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        val out = LinkedHashSet<String>()
        collectTexts(root, out)
        root.recycle()
        return out.joinToString(". ")
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableSet<String>) {
        val text = node.text?.toString()?.trim()
        val cd   = node.contentDescription?.toString()?.trim()
        if (!text.isNullOrEmpty()) out.add(text)
        if (!cd.isNullOrEmpty() && cd != text) out.add(cd)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTexts(child, out)
            child.recycle()
        }
    }

    private fun displayMetrics(): DisplayMetrics {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val metrics = DisplayMetrics()
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        return metrics
    }

    // ── Node search helpers ──────────────────────────────────────────────────

    fun findNodeByText(root: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        root ?: return null
        val lower = label.lowercase()
        if (root.text?.toString()?.lowercase()?.contains(lower) == true ||
            root.contentDescription?.toString()?.lowercase()?.contains(lower) == true
        ) return root
        for (i in 0 until root.childCount) {
            val result = findNodeByText(root.getChild(i), label)
            if (result != null) return result
        }
        return null
    }

    private fun findFocusedInput(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        if (root.isFocused && root.isEditable) return root
        for (i in 0 until root.childCount) {
            val r = findFocusedInput(root.getChild(i))
            if (r != null) return r
        }
        // Fall back to any editable field
        if (root.isEditable) return root
        for (i in 0 until root.childCount) {
            val r = findFocusedInput(root.getChild(i))
            if (r != null) return r
        }
        return null
    }

    private fun findScrollable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        if (root.isScrollable) return root
        for (i in 0 until root.childCount) {
            val r = findScrollable(root.getChild(i))
            if (r != null) return r
        }
        return null
    }
}
