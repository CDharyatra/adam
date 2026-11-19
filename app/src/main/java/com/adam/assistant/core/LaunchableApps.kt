package com.adam.assistant.core

/**
 * User-facing launchable-app registry with OEM-aware aliases and ambiguity
 * handling (Task 6).
 *
 * A spoken name may map to a known package (via its canonical name or an
 * alias). When more than one distinct app matches, the result is
 * [AppLookup.Ambiguous] so callers must disambiguate rather than guess. When
 * nothing matches, callers fall back to a launcher-label query at runtime,
 * which is how OEM-relabelled system apps are still found.
 */
data class LaunchableApp(
    val name: String,
    val packageName: String,
    val aliases: List<String> = emptyList()
)

sealed interface AppLookup {
    data class Found(val app: LaunchableApp) : AppLookup
    data class Ambiguous(val apps: List<LaunchableApp>) : AppLookup
    data object NotFound : AppLookup
}

class LaunchableApps(private val entries: List<LaunchableApp> = Default) {

    fun resolve(raw: String): AppLookup {
        val query = norm(raw)
        if (query.isEmpty()) return AppLookup.NotFound

        val exact = entries.filter { entry ->
            query == norm(entry.name) || entry.aliases.any { query == norm(it) }
        }
        val pool = if (exact.isNotEmpty()) exact else entries.filter { entry ->
            norm(entry.name).contains(query) || entry.aliases.any { norm(it).contains(query) }
        }
        return when (pool.size) {
            0 -> AppLookup.NotFound
            1 -> AppLookup.Found(pool.first())
            else -> AppLookup.Ambiguous(pool.sortedBy { it.name })
        }
    }

    /** The package for a clear single match, else null (caller falls back). */
    fun packageFor(raw: String): String? =
        (resolve(raw) as? AppLookup.Found)?.app?.packageName

    private fun norm(value: String): String =
        value.trim().lowercase().replace(WHITESPACE, " ")

    companion object {
        private val WHITESPACE = Regex("\\s+")

        val Default: List<LaunchableApp> = listOf(
            LaunchableApp("Spotify", "com.spotify.music", listOf("music", "sportify")),
            LaunchableApp("WhatsApp", "com.whatsapp", listOf("whats app", "wa")),
            LaunchableApp(
                "WhatsApp Business", "com.whatsapp.w4b",
                listOf("whatsapp business", "wa business")
            ),
            LaunchableApp(
                "Google Maps", "com.google.android.apps.maps",
                listOf("maps", "navigation", "map")
            ),
            LaunchableApp("Gmail", "com.google.android.gm", listOf("gmail", "email", "mail")),
            LaunchableApp("YouTube", "com.google.android.youtube", listOf("youtube", "yt")),
            LaunchableApp("Chrome", "com.android.chrome", listOf("browser", "internet")),
            LaunchableApp(
                "Messages", "com.google.android.apps.messaging",
                listOf("messages", "sms", "text messages")
            ),
            LaunchableApp("Camera", "com.android.camera2", listOf("camera")),
            LaunchableApp("Phone", "com.android.dialer", listOf("phone", "dialer", "keypad")),
            LaunchableApp("Clock", "com.android.deskclock", listOf("clock", "alarm", "timer")),
            LaunchableApp("Settings", "com.android.settings", listOf("settings")),
            LaunchableApp(
                "Photos", "com.google.android.apps.photos",
                listOf("photos", "gallery")
            ),
            LaunchableApp("Instagram", "com.instagram.android", listOf("instagram", "insta")),
            LaunchableApp("Telegram", "org.telegram.messenger", listOf("telegram")),
            LaunchableApp("Netflix", "com.netflix.mediaclient", listOf("netflix")),
            LaunchableApp("X (Twitter)", "com.twitter.android", listOf("twitter", "x"))
        )
    }
}
