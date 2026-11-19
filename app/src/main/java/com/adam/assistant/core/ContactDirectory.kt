package com.adam.assistant.core

data class ContactNumber(
    val id: Long? = null,
    val number: String,
    val label: String? = null
)

data class ContactCandidate(
    val name: String,
    val phone: String? = null,
    val contactId: Long? = null,
    val phoneId: Long? = null,
    val numbers: List<ContactNumber> = emptyList(),
    val aliases: List<String> = emptyList()
) {
    fun allNumbers(): List<ContactNumber> {
        val source = if (numbers.isEmpty()) {
            listOfNotNull(phone?.takeIf { it.isNotBlank() }?.let {
                ContactNumber(phoneId, it)
            })
        } else {
            numbers
        }
        return source
            .filter { it.number.isNotBlank() }
            .distinctBy { (it.id?.toString() ?: "") + "|" + it.number }
    }
}

sealed interface ContactMatch {
    data class Resolved(val candidate: ContactCandidate) : ContactMatch
    data class Ambiguous(val candidates: List<ContactCandidate>) : ContactMatch
    data object NotFound : ContactMatch
}

interface ContactDirectory {
    suspend fun load(): List<ContactCandidate>
    fun match(entries: List<ContactCandidate>, heard: String): ContactMatch
}

object EmptyContactDirectory : ContactDirectory {
    override suspend fun load(): List<ContactCandidate> = emptyList()

    override fun match(
        entries: List<ContactCandidate>,
        heard: String
    ): ContactMatch = ContactMatch.NotFound
}
