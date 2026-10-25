package com.adam.assistant.llm

import com.adam.assistant.core.MemoryStore

/**
 * On-demand reasoning backend (Option 2 of the hybrid brain).
 *
 * Contract, matching the product design:
 *  - Called ONLY for genuinely novel commands the fast paths can't handle.
 *  - [reason] studies the raw utterance and returns a generalized
 *    [MemoryStore.LearnedPattern] (action + keywords), or null if even the
 *    backend can't make sense of it.
 *  - The caller stores the pattern and replies with a static "noted for next
 *    time" message WITHOUT executing — the fast path owns the next attempt.
 *  - The backend must load lazily inside [reason] and release everything
 *    before returning: no model sits in RAM between novel commands.
 *
 * [NoOpReasoningEngine] is the default until a real backend (MediaPipe LLM,
 * MLC, …) is plugged in via `LlmEngine.reasoning`.
 */
interface ReasoningEngine {

    /** True once a real backend is wired and ready to reason on demand. */
    val isAvailable: Boolean

    /**
     * Reason over [novelCommand] and generalize it to a reusable pattern.
     * Must be safe to call from a background coroutine; must free any loaded
     * model/RAM before returning.
     */
    suspend fun reason(novelCommand: String): MemoryStore.LearnedPattern?
}

/** Placeholder: reasoning track not yet wired — fast paths + noting only. */
class NoOpReasoningEngine : ReasoningEngine {
    override val isAvailable: Boolean = false
    override suspend fun reason(novelCommand: String): MemoryStore.LearnedPattern? = null
}
