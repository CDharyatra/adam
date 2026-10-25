package com.adam.assistant.core

import org.junit.Assert.*
import org.junit.Test

class SessionManagerTest {

    private val cmd = AssistantCommand(AssistantCommand.Action.SEND_MESSAGE, "mom")

    @Test
    fun idleByDefault() {
        assertNull(SessionManager().active())
    }

    @Test
    fun beginAndConsume() {
        var now = 1_000L
        val sm = SessionManager(nowMs = { now })
        sm.begin(Session.CONFIRM, cmd)
        val s = sm.active()
        assertNotNull(s)
        assertEquals(Session.CONFIRM, s!!.kind)
        assertEquals(cmd, s.command)
    }

    @Test
    fun expiryClears() {
        var now = 0L
        val sm = SessionManager(nowMs = { now }, ttlMs = 1_000L)
        sm.begin(Session.SLOT, cmd, field = "body")
        now = 500L
        assertNotNull(sm.active())
        now = 1_001L
        assertNull(sm.active())
    }

    @Test
    fun clearKills() {
        val sm = SessionManager()
        sm.begin(Session.CONTACT, cmd)
        sm.clear()
        assertNull(sm.active())
    }

    @Test
    fun carriesFieldAndEntries() {
        val sm = SessionManager()
        val entries = listOf(Contacts.Entry("Mom", "+123"))
        sm.begin(Session.CONTACT, cmd, entries = entries)
        val s = sm.active()!!
        assertEquals(1, s.entries.size)
        assertEquals("Mom", s.entries[0].name)
    }
}
