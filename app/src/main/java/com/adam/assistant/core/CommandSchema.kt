package com.adam.assistant.core

import com.adam.assistant.core.AssistantCommand.Action
import java.util.Locale

enum class ValueKind {
    TEXT,
    INTEGER,
    URL,
    PACKAGE,
    PHONE,
    ID,
    EXPRESSION
}

data class ParamRule(
    val key: String,
    val required: Boolean = false,
    val kind: ValueKind = ValueKind.TEXT,
    val allowedValues: Set<String>? = null,
    val min: Long? = null,
    val max: Long? = null
)

data class ActionSchema(
    val action: Action,
    val targetRequired: Boolean = false,
    val targetKind: ValueKind = ValueKind.TEXT,
    val params: List<ParamRule> = emptyList(),
    val modelAllowed: Boolean = true
) {
    val allowedParams: Set<String> = params.map { it.key }.toSet()

    fun firstMissing(command: AssistantCommand): String? {
        if (targetRequired && command.target.isBlank()) return "target"
        return params.firstOrNull { rule ->
            rule.required && valueOf(command, rule.key).isBlank()
        }?.key
    }

    fun isRequired(field: String): Boolean =
        (field == "target" && targetRequired) || params.any { it.key == field && it.required }

    fun unknownParam(command: AssistantCommand): String? =
        command.params.keys.firstOrNull { it !in allowedParams }

    fun valueError(command: AssistantCommand, phoneRegion: String?): String? {
        if (command.target.isNotBlank()) {
            errorFor("target", command.target, targetKind, null, null, null, phoneRegion)
                ?.let { return it }
        }
        for (rule in params) {
            val value = valueOf(command, rule.key)
            if (value.isBlank()) continue
            errorFor(
                rule.key,
                value,
                rule.kind,
                rule.allowedValues,
                rule.min,
                rule.max,
                phoneRegion
            )?.let { return it }
        }
        return null
    }

    private fun valueOf(command: AssistantCommand, key: String): String =
        if (key == "target") command.target else command.params[key].orEmpty()

    private fun errorFor(
        key: String,
        raw: String,
        kind: ValueKind,
        allowedValues: Set<String>?,
        min: Long?,
        max: Long?,
        phoneRegion: String?
    ): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        if (allowedValues != null &&
            value.lowercase(Locale.ROOT) !in allowedValues
        ) {
            return "invalid $key"
        }
        return when (kind) {
            ValueKind.TEXT -> null
            ValueKind.EXPRESSION -> null
            ValueKind.URL -> if (!looksLikeUrl(value)) "invalid $key" else null
            ValueKind.PACKAGE ->
                if (!PACKAGE.matches(value)) "invalid $key" else null
            ValueKind.PHONE ->
                if (!PhoneNumber.isPlausible(value)) "invalid $key" else null
            ValueKind.ID -> {
                val number = value.toLongOrNull()
                if (number == null || number <= 0L) "invalid $key" else null
            }
            ValueKind.INTEGER -> {
                val number = value.toLongOrNull() ?: return "invalid $key"
                when {
                    min != null && number < min -> "invalid $key"
                    max != null && number > max -> "invalid $key"
                    else -> null
                }
            }
        }
    }

    private fun looksLikeUrl(value: String): Boolean {
        if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) {
            return true
        }
        return Regex("^[\\p{L}\\p{N}.-]+\\.[a-zA-Z]{2,}(?:[/?#].*)?$").matches(value)
    }

    private companion object {
        val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}

object CommandSchemas {

    private fun text(
        key: String,
        required: Boolean = false
    ) = ParamRule(key, required, ValueKind.TEXT)

    private fun integer(
        key: String,
        required: Boolean = false,
        min: Long? = null,
        max: Long? = null
    ) = ParamRule(key, required, ValueKind.INTEGER, min = min, max = max)

    private fun direction() = ParamRule(
        key = "direction",
        required = true,
        kind = ValueKind.TEXT,
        allowedValues = setOf("up", "down", "left", "right", "mute", "unmute", "max")
    )

    private val list = listOf(
        ActionSchema(Action.OPEN_APP, targetRequired = true, params = listOf(
            ParamRule("package", kind = ValueKind.PACKAGE)
        )),
        ActionSchema(Action.PLAY_MUSIC, targetRequired = true, params = listOf(
            text("artist"), text("app"), ParamRule("package", kind = ValueKind.PACKAGE)
        )),
        ActionSchema(Action.SEND_MESSAGE, targetRequired = true, params = listOf(
            text("app"),
            text("body", required = true),
            ParamRule("phone", kind = ValueKind.PHONE),
            ParamRule("package", kind = ValueKind.PACKAGE)
        )),
        ActionSchema(Action.MAKE_CALL, targetRequired = true, params = listOf(
            ParamRule("phone", kind = ValueKind.PHONE),
            ParamRule("package", kind = ValueKind.PACKAGE)
        )),
        ActionSchema(Action.OPEN_URL, targetRequired = true, targetKind = ValueKind.URL),
        ActionSchema(Action.SEARCH_WEB, targetRequired = true),
        ActionSchema(Action.SET_ALARM, params = listOf(
            integer("hour", required = true, min = 0, max = 23),
            integer("minute", required = true, min = 0, max = 59)
        )),
        ActionSchema(Action.SET_TIMER, params = listOf(
            integer("seconds", required = true, min = 1, max = Int.MAX_VALUE.toLong())
        )),
        ActionSchema(Action.ADD_CALENDAR_EVENT, targetRequired = true, params = listOf(
            text("title"),
            text("description"),
            integer("start", min = 0)
        )),
        ActionSchema(Action.SEND_EMAIL, targetRequired = true, params = listOf(
            text("app"),
            text("subject"),
            text("body", required = true),
            ParamRule("package", kind = ValueKind.PACKAGE)
        )),
        ActionSchema(Action.TAKE_PHOTO),
        ActionSchema(Action.NAVIGATE, targetRequired = true),
        ActionSchema(Action.OPEN_SETTINGS),
        ActionSchema(Action.ADJUST_VOLUME, params = listOf(
            ParamRule(
                "direction",
                required = true,
                allowedValues = setOf("up", "down", "mute", "unmute", "max")
            )
        )),
        ActionSchema(Action.READ_SCREEN),
        ActionSchema(
            Action.LONG_CLICK,
            targetRequired = true,
            modelAllowed = false
        ),
        ActionSchema(Action.OPEN_NOTIFICATIONS),
        ActionSchema(Action.CLOSE_NOTIFICATIONS),
        ActionSchema(Action.SWIPE, params = listOf(direction())),
        ActionSchema(
            Action.CALCULATE,
            targetRequired = true,
            targetKind = ValueKind.EXPRESSION
        ),
        ActionSchema(Action.NOTE, targetRequired = true),
        ActionSchema(
            Action.CLICK,
            targetRequired = true,
            modelAllowed = false
        ),
        ActionSchema(Action.SCROLL, params = listOf(
            ParamRule(
                "direction",
                required = true,
                allowedValues = setOf("up", "down")
            )
        )),
        ActionSchema(
            Action.TYPE,
            targetRequired = true,
            modelAllowed = false
        ),
        ActionSchema(Action.BACK),
        ActionSchema(Action.HOME),
        ActionSchema(Action.RECENTS),
        ActionSchema(Action.REPLY, params = listOf(text("message", required = true)))
    )

    val all: Map<Action, ActionSchema> = list.associateBy { it.action }

    fun of(action: Action): ActionSchema? = all[action]
}

interface CapabilityRegistry {
    fun isActionSupported(action: Action): Boolean = CommandSchemas.of(action) != null

    fun isAdapterAvailable(action: Action): Boolean = true

    fun isAppAvailable(appName: String?, packageName: String?): Boolean = true

    fun modelMayPropose(action: Action): Boolean =
        CommandSchemas.of(action)?.modelAllowed ?: false
}

object AllowAllCapabilities : CapabilityRegistry
