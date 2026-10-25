package com.adam.assistant.core

/**
 * One conversation session: dialog state as data, not scattered flags.
 *
 * Replaces the old pendingConfirm/pendingSlot/pendingContact vars. A session
 * arms when Adam asks something ([begin]) and dies on consumption, explicit
 * clear, or [ttlMs] expiry — so abandoned follow-ups can never wedge the
 * assistant. The clock is injectable for tests.
 */
data class Session(
    val id: String = java.util.UUID.randomUUID().toString(),
    /** "confirm" | "slot" | "contact" | null (idle). */
    val kind: String? = null,
    val command: AssistantCommand? = null,
    /** Slot field awaiting a value (kind == "slot"). */
    val field: String? = null,
    /** Contact candidates (kind == "contact"). */
    val entries: List<Contacts.Entry> = emptyList(),
    val expiresAt: Long = 0L
) {
    companion object {
        const val CONFIRM = "confirm"
        const val SLOT = "slot"
        const val CONTACT = "contact"
    }
}

class SessionManager(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = 90_000L
) {
    private var session = Session()

    fun begin(
        kind: String,
        command: AssistantCommand,
        field: String? = null,
        entries: List<Contacts.Entry> = emptyList()
    ) {
        session = Session(
            kind = kind,
            command = command,
            field = field,
            entries = entries,
            expiresAt = nowMs() + ttlMs
        )
    }

    /** The live session, or null when idle/expired (expired ones are cleared). */
    fun active(): Session? {
        val s = session
        if (s.kind == null || s.command == null) return null
        if (nowMs() > s.expiresAt) {
            session = Session()
            return null
        }
        return s
    }

    fun clear() {
        session = Session()
    }
}
