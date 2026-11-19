package com.adam.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KnownAppsTest {

    @Test
    fun resolvesKnownAppsCaseInsensitivelyAndTrimmed() {
        assertEquals("com.spotify.music", KnownApps.staticPackage("Spotify"))
        assertEquals("com.whatsapp", KnownApps.staticPackage("whatsapp"))
        assertEquals("com.google.android.gm", KnownApps.staticPackage("Gmail"))
        assertEquals("com.google.android.apps.maps", KnownApps.staticPackage("  Google Maps "))
    }

    @Test
    fun unknownNameReturnsNull() {
        assertNull(KnownApps.staticPackage("some random app"))
        assertNull(KnownApps.staticPackage(""))
    }

    @Test
    fun gmailIsFixedToTheRealPackage() {
        // Regression: Gmail was once mapped to the non-launchable gms package.
        assertEquals("com.google.android.gm", KnownApps.staticPackage("gmail"))
    }
}
