package com.adam.assistant.core

fun interface CommandRouter {
    suspend fun route(text: String): IntentMatch
}
