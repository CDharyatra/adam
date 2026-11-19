package com.adam.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumberTest {

    @Test
    fun internationalPlusIsNormalizedStably() {
        assertEquals("+15550000001", PhoneNumber.normalize("+1 555 000 0001", null))
        assertEquals("+15550000001", PhoneNumber.normalize("+15550000001", null))
    }

    @Test
    fun doubleZeroIsTreatedAsInternational() {
        assertEquals("+15550000001", PhoneNumber.normalize("0015550000001", null))
    }

    @Test
    fun localNumberNeedsExplicitRegion() {
        assertEquals("+15551234567", PhoneNumber.normalize("5551234567", "US"))
        assertNull(PhoneNumber.normalize("5551234567", null))
        assertNull(PhoneNumber.normalize("5551234567", "ZZ"))
    }

    @Test
    fun garbageIsRejected() {
        assertNull(PhoneNumber.normalize("abc", null))
        assertNull(PhoneNumber.normalize("not a number", "US"))
        assertNull(PhoneNumber.normalize("12", null))
    }

    @Test
    fun plausibilityBounds() {
        assertTrue(PhoneNumber.isPlausible("+15550000001"))
        assertTrue(PhoneNumber.isPlausible("5550000001"))
        assertFalse(PhoneNumber.isPlausible("123"))
        assertFalse(PhoneNumber.isPlausible("abc"))
        assertFalse(PhoneNumber.isPlausible(""))
    }

    @Test
    fun sameIgnoresFormatting() {
        assertTrue(PhoneNumber.same("+1 555 000 0001", "+15550000001", null))
        assertTrue(PhoneNumber.same("5551234567", "+15551234567", "US"))
        assertFalse(PhoneNumber.same("+15550000001", "+15559999999", null))
    }

    @Test
    fun digitsAndDisplay() {
        assertEquals("15550000001", PhoneNumber.digits("+1 (555) 000-0001"))
        assertEquals("+15550000001", PhoneNumber.display("+15550000001", null))
        assertEquals("not a number", PhoneNumber.display("not a number", null))
    }
}
