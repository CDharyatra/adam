package com.adam.assistant.core

import android.content.Context
import com.adam.assistant.llm.ModelDownloader
import java.io.File

/**
 * Independent readiness for every on-device model. Labels, expected sizes and
 * source URLs come from [ModelDownloader] so the UI never re-hardcodes stale
 * numbers (the old copy claimed ~36 MB / ~1.8 GB for models that are now
 * ~128 MB / ~800 MB).
 *
 * Typed Preview never consults this: it works with every model missing.
 */
object ModelSetupChecker {

    data class ModelStatus(
        val key: String,
        val label: String,
        val ready: Boolean,
        val sizeBytes: Long,
        val expectedBytes: Long,
        val sourceUrl: String,
        val optional: Boolean = false
    )

    data class SetupStatus(
        val speechReady: Boolean,
        val mlcReady: Boolean,
        val modelId: String,
        val mlcSizeBytes: Long,
        val speechSizeBytes: Long,
        val instructions: String,
        val models: List<ModelStatus> = emptyList()
    )

    fun check(context: Context): SetupStatus {
        val extDir = context.getExternalFilesDir(null) ?: return SetupStatus(
            speechReady = false,
            mlcReady = false,
            modelId = ModelDownloader.LLM_TASK_MODEL_ID.substringAfterLast('/'),
            mlcSizeBytes = 0L,
            speechSizeBytes = 0L,
            instructions = "External storage not available.",
            models = emptyList()
        )

        val voskDir = File(extDir, ModelDownloader.SPEECH_MODEL_DIR)
        val sherpaDir = File(extDir, ModelDownloader.SHERPA_MODEL_DIR)
        val kwsDir = File(extDir, ModelDownloader.KWS_MODEL_DIR)
        val brainDir = File(extDir, ModelDownloader.LLM_TASK_DIR)

        val speechReady = hasFile(voskDir, "am/final.mdl")
        val asrReady = hasFile(sherpaDir, ModelDownloader.SHERPA_ENCODER_FILE) &&
            hasFile(sherpaDir, ModelDownloader.SHERPA_DECODER_FILE) &&
            hasFile(sherpaDir, ModelDownloader.SHERPA_TOKENS_FILE)
        val vadReady = hasFile(sherpaDir, ModelDownloader.VAD_FILE)
        val kwsReady = hasFile(kwsDir, ModelDownloader.KWS_TOKENS_FILE) &&
            hasFile(kwsDir, ModelDownloader.KWS_ENCODER_FILE)
        val brainReady = hasFile(brainDir, ModelDownloader.LLM_TASK_FILE)

        val speechSize = dirSize(voskDir)
        val sherpaSize = dirSize(sherpaDir)
        val kwsSize = dirSize(kwsDir)
        val brainSize = dirSize(brainDir)

        val models = listOf(
            ModelStatus(
                key = "wake_word",
                label = "Wake word / fallback (Vosk English)",
                ready = speechReady,
                sizeBytes = speechSize,
                expectedBytes = ModelDownloader.DEFAULT_SPEECH_MODEL_BYTES,
                sourceUrl = ModelDownloader.speechSourceUrl
            ),
            ModelStatus(
                key = "accurate_speech",
                label = "Accurate speech (Whisper Small.en)",
                ready = asrReady,
                sizeBytes = sherpaSize,
                expectedBytes = ModelDownloader.SHERPA_MODEL_BYTES,
                sourceUrl = "${ModelDownloader.SHERPA_HF_BASE}/${ModelDownloader.SHERPA_ENCODER_FILE}"
            ),
            ModelStatus(
                key = "vad",
                label = "Voice activity (Silero VAD)",
                ready = vadReady,
                sizeBytes = 0L,
                expectedBytes = 0L,
                sourceUrl = ModelDownloader.SHERPA_VAD_URL
            ),
            ModelStatus(
                key = "keyword_spotter",
                label = "Keyword spotter",
                ready = kwsReady,
                sizeBytes = kwsSize,
                expectedBytes = ModelDownloader.KWS_TARBALL_BYTES,
                sourceUrl = ModelDownloader.KWS_TARBALL_URL
            ),
            ModelStatus(
                key = "ai_brain",
                label = "AI model (Gemma 3 1B, optional)",
                ready = brainReady,
                sizeBytes = brainSize,
                expectedBytes = ModelDownloader.LLM_TASK_BYTES,
                sourceUrl = ModelDownloader.LLM_TASK_URL,
                optional = true
            )
        )

        return SetupStatus(
            speechReady = speechReady,
            mlcReady = brainReady,
            modelId = ModelDownloader.LLM_TASK_MODEL_ID.substringAfterLast('/'),
            mlcSizeBytes = brainSize,
            speechSizeBytes = speechSize,
            instructions = instructionsFor(models),
            models = models
        )
    }

    fun humanBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = listOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return if (index == 0) {
            String.format(java.util.Locale.US, "%d %s", value.toLong(), units[index])
        } else {
            String.format(java.util.Locale.US, "%.1f %s", value, units[index])
        }
    }

    private fun instructionsFor(models: List<ModelStatus>): String {
        val missing = models.filter { !it.ready }
        val sb = StringBuilder()
        sb.appendLine("Typed Preview works without any model or permission.")
        sb.appendLine("Voice models download only when you press Start Adam; the")
        sb.appendLine("optional AI model downloads only when you enable it.")
        if (missing.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Not installed yet:")
            missing.forEach { model ->
                val size = if (model.expectedBytes > 0L) {
                    " (~${humanBytes(model.expectedBytes)})"
                } else {
                    ""
                }
                val optional = if (model.optional) " [optional]" else ""
                sb.appendLine("• ${model.label}$optional$size")
            }
        }
        return sb.toString().trim()
    }

    private fun hasFile(dir: File, name: String): Boolean =
        File(dir, name).let { it.exists() && it.length() > 0L }

    private fun dirSize(dir: File): Long =
        if (dir.exists()) {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            0L
        }
}
