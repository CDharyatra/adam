package com.adam.assistant.core

data class AppTarget(val name: String, val packageName: String? = null)

fun interface AppRegistry {
    fun resolve(name: String): AppTarget?
}

object PassthroughAppRegistry : AppRegistry {
    override fun resolve(name: String): AppTarget? =
        name.trim().takeIf { it.isNotEmpty() }?.let { AppTarget(it) }
}
