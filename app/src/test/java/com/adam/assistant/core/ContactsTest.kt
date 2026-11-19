package com.adam.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsTest {

    private val john = ContactCandidate(
        name = "John Smith",
        phone = "+15550000001",
        contactId = 1L,
        phoneId = 10L,
        numbers = listOf(
            ContactNumber(10L, "+15550000001", "Mobile"),
            ContactNumber(11L, "+15550000002", "Home")
        )
    )

    @Test
    fun exactNameResolvesSingleCandidate() {
        val m = Contacts.match(listOf(john), "john smith")
        assertTrue(m is ContactMatch.Resolved)
        assertEquals(john, (m as ContactMatch.Resolved).candidate)
    }

    @Test
    fun closeTypoStillResolves() {
        val m = Contacts.match(listOf(john), "jon smith")
        assertTrue(m is ContactMatch.Resolved)
    }

    @Test
    fun unrelatedTextIsNotFound() {
        assertTrue(Contacts.match(listOf(john), "zzzzz") is ContactMatch.NotFound)
        assertTrue(Contacts.match(listOf(john), "") is ContactMatch.NotFound)
    }

    @Test
    fun duplicateNamesAreAmbiguousNotGuessed() {
        val dupA = john.copy(contactId = 1L)
        val dupB = john.copy(contactId = 2L)
        val m = Contacts.match(listOf(dupA, dupB), "john smith")
        assertTrue(m is ContactMatch.Ambiguous)
        assertEquals(2, (m as ContactMatch.Ambiguous).candidates.size)
    }

    @Test
    fun aliasResolves() {
        val shubh = ContactCandidate(
            name = "Shubhanshu",
            phone = "+919800000001",
            contactId = 7L,
            aliases = listOf("Shubh")
        )
        val m = Contacts.match(listOf(shubh), "shubh")
        assertTrue(m is ContactMatch.Resolved)
    }

    @Test
    fun byNumberMatchesAndRejectsNonNumbers() {
        assertEquals(1, Contacts.byNumber(listOf(john), "+15550000001", null).size)
        assertEquals(1, Contacts.byNumber(listOf(john), "555 000 0001", "US").size)
        assertTrue(Contacts.byNumber(listOf(john), "abc", null).isEmpty())
    }

    @Test
    fun normStripsAccentsAndFillers() {
        assertEquals("cafe", Contacts.norm("café"))
        assertEquals("mom", Contacts.norm("the mom"))
        assertEquals("john smith", Contacts.norm("  John   SMITH "))
    }
}
