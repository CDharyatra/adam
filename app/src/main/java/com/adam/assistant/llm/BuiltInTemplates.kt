package com.adam.assistant.llm

import com.adam.assistant.core.AssistantCommand.Action

/**
 * Seed templates: paraphrase coverage for the supported commands, in
 * hassil-style syntax (`{slot}`, `[optional]`, `(a|b)`). Slot named `target`
 * becomes the command target; other non-blank slots become params.
 *
 * Add paraphrases here — never in code branches — when users phrase known
 * commands in new ways. Truly new *capabilities* still go to the novelty flow.
 */
val BUILT_IN_TEMPLATES = listOf(
    Template("open_app", Action.OPEN_APP, "(open|launch|start|open up) {target}"),
    Template(
        "play_music", Action.PLAY_MUSIC,
        "play {target} [by {artist}] [on spotify]",
        mapOf("app" to "Spotify")
    ),
    Template(
        "play_music_listen", Action.PLAY_MUSIC,
        "(listen to|hear) {target}",
        mapOf("app" to "Spotify")
    ),
    Template(
        "message", Action.SEND_MESSAGE,
        "(text|message|send) {target} [(that|saying) {body}]",
        mapOf("app" to "WhatsApp")
    ),
    Template("call", Action.MAKE_CALL, "(call|dial|ring|phone) {target}"),
    Template("calculate", Action.CALCULATE, "(calculate|what is|what's|whats|how much is) {target}"),
    Template("search", Action.SEARCH_WEB, "(search|google|look up) [for] {target}"),
    Template("navigate", Action.NAVIGATE, "(navigate to|directions to|take me to|drive to|go to) {target}"),
    Template("note", Action.NOTE, "(note|remember|note down|remind me to) {target}"),
    Template("volume", Action.ADJUST_VOLUME, "(volume|sound) {direction}"),
    Template("mute", Action.ADJUST_VOLUME, "mute", mapOf("direction" to "mute")),
    Template("louder", Action.ADJUST_VOLUME, "(louder|turn it up|volume up)", mapOf("direction" to "up")),
    Template("quieter", Action.ADJUST_VOLUME, "(quieter|turn it down|volume down)", mapOf("direction" to "down"))
)
