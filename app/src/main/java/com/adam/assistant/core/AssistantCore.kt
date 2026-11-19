package com.adam.assistant.core

import android.content.Context
import com.adam.assistant.executor.CommandExecutor
import com.adam.assistant.executor.CommandExecutorActionPort
import com.adam.assistant.executor.MockActionPort
import com.adam.assistant.llm.LlmEngine
import java.util.Locale

/**
 * App-scoped coordinator for typed commands.
 *
 * It deliberately never calls [LlmEngine.init], so constructing it downloads
 * nothing and loads no model. Routing uses the deterministic explicit /
 * learned / template / fuzzy pipeline; the optional brain only participates
 * after the service initialises it elsewhere. This is what lets a clean
 * install with denied permissions and zero models still parse and preview a
 * typed command.
 *
 * The voice path keeps its own coordinator in [AssistantService] so the
 * service owns its model lifecycle. Both call the same
 * [AssistantCoordinator.submitText] seam.
 */
object AssistantCore {

    @Volatile private var coordinator: AssistantCoordinator? = null

    fun coordinator(context: Context): AssistantCoordinator {
        coordinator?.let { return it }
        return synchronized(this) {
            coordinator ?: build(context.applicationContext).also { coordinator = it }
        }
    }

    fun cancel() {
        coordinator?.cancel()
    }

    private fun build(context: Context): AssistantCoordinator {
        val engine = LlmEngine(context)
        val region = deviceRegion()
        return AssistantCoordinator(
            router = CommandRouter { text -> engine.processDetailed(text) },
            executor = CommandExecutorActionPort(CommandExecutor(context)),
            policy = CommandPolicy(
                capabilities = AndroidCapabilityRegistry(context),
                phoneRegion = region
            ),
            contacts = AndroidContactDirectory(context),
            previewExecutor = MockActionPort(),
            phoneRegion = region
        )
    }

    fun deviceRegion(): String? = Locale.getDefault().country
        .takeIf { it.length == 2 && it != "ZZ" }
}
