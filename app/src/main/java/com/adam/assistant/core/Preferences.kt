package com.adam.assistant.core

import android.content.Context

/**
 * Single source of truth for user-controlled preferences.
 *
 * Kept tiny on purpose — only things the user explicitly toggles. Anything
 * derived from runtime state (current model, last error, etc.) stays in
 * its respective engine class.
 */
object Preferences {

    private const val PREFS = "adam_prefs"
    private const val KEY_WAKE_WORD = "wake_word_enabled"
    private const val KEY_CONTACTS_ASKED = "contacts_asked"

    fun isWakeWordEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WAKE_WORD, true)

    fun setWakeWordEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WAKE_WORD, enabled).apply()
    }

    /** Whether the optional contacts rationale was already shown once. */
    fun isContactsAsked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONTACTS_ASKED, false)

    fun setContactsAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_CONTACTS_ASKED, true).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
