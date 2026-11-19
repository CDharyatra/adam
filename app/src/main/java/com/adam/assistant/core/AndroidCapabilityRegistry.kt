package com.adam.assistant.core

import android.content.Context
import android.content.Intent
import com.adam.assistant.accessibility.AdamAccessibilityService

class AndroidCapabilityRegistry(
    private val context: Context
) : CapabilityRegistry {

    override fun isAdapterAvailable(action: AssistantCommand.Action): Boolean {
        if (action !in accessibilityActions) return true
        return AdamAccessibilityService.instance.value != null
    }

    override fun isAppAvailable(appName: String?, packageName: String?): Boolean {
        // Explicit package: trust the installer's own launch-intent lookup.
        if (!packageName.isNullOrBlank()) {
            return context.packageManager.getLaunchIntentForPackage(packageName) != null
        }
        // Name-only (LLM/typed): resolve to a candidate package, then verify it
        // can actually launch. Never blanket-true, so an invented app name can't
        // get approval to "open" something that isn't installed.
        val resolved = packageFor(appName) ?: return false
        return context.packageManager.getLaunchIntentForPackage(resolved) != null
    }

    private fun packageFor(appName: String?): String? {
        val name = appName?.trim().orEmpty()
        if (name.isEmpty()) return null
        KnownApps.staticPackage(name)?.let { return it }
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(query, 0)
            .firstOrNull { it.loadLabel(context.packageManager).toString().equals(name, true) }
            ?.activityInfo?.packageName
    }

    private companion object {
        val accessibilityActions = setOf(
            AssistantCommand.Action.READ_SCREEN,
            AssistantCommand.Action.LONG_CLICK,
            AssistantCommand.Action.OPEN_NOTIFICATIONS,
            AssistantCommand.Action.CLOSE_NOTIFICATIONS,
            AssistantCommand.Action.SWIPE,
            AssistantCommand.Action.CLICK,
            AssistantCommand.Action.SCROLL,
            AssistantCommand.Action.TYPE,
            AssistantCommand.Action.BACK,
            AssistantCommand.Action.HOME,
            AssistantCommand.Action.RECENTS
        )
    }
}
