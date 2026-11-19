package com.adam.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchableAppsTest {

    private val apps = LaunchableApps()

    @Test
    fun resolvesByNameAndAliasCaseAndSpaceInsensitive() {
        assertEquals("com.spotify.music", apps.packageFor("Spotify"))
        assertEquals("com.spotify.music", apps.packageFor("music"))
        assertEquals("com.google.android.gm", apps.packageFor("EMAIL"))
        assertEquals("com.google.android.apps.maps", apps.packageFor("  Google Maps "))
    }

    @Test
    fun unknownNameReturnsNull() {
        assertNull(apps.packageFor("some random app"))
        assertNull(apps.packageFor(""))
    }

    @Test
    fun whatsappVersusBusinessIsDisambiguated() {
        assertTrue(apps.resolve("whatsapp") is AppLookup.Found)
        assertTrue(apps.resolve("whatsapp business") is AppLookup.Found)
        val ambiguous = apps.resolve("whats")
        assertTrue(ambiguous is AppLookup.Ambiguous)
        assertTrue((ambiguous as AppLookup.Ambiguous).apps.isNotEmpty())
    }

    @Test
    fun oemAliasWordsResolveToExpectedApps() {
        assertEquals("com.android.dialer", apps.packageFor("keypad"))
        assertEquals("com.google.android.apps.messaging", apps.packageFor("sms"))
        assertEquals("com.android.deskclock", apps.packageFor("alarm"))
        assertEquals("com.google.android.apps.photos", apps.packageFor("gallery"))
    }

    @Test
    fun knownAppsDelegateToRegistry() {
        // The executor/registry path resolves through the same registry.
        assertEquals("com.google.android.gm", KnownApps.staticPackage("gmail"))
        assertNull(KnownApps.staticPackage("not an app"))
    }
}
