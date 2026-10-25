package com.adam.assistant.core

import android.content.Context
import com.adam.assistant.llm.ModelDownloader
import java.io.File

/**
 * Checks whether required model files are present and provides
 * human-readable setup instructions.
 *
 * Both the speech model and the AI model are auto-downloaded on first launch —
 * we only verify presence here.
 */
object ModelSetupChecker {

    data class SetupStatus(
        val speechReady: Boolean,
        val mlcReady: Boolean,
        val modelId: String,
        val mlcSizeBytes: Long,
        val speechSizeBytes: Long,
        val instructions: String
    )

    fun check(context: Context): SetupStatus {
        val extDir = context.getExternalFilesDir(null) ?: return SetupStatus(
            false, false, ModelDownloader.DEFAULT_MODEL_ID, 0L, 0L,
            "External storage not available."
        )

        val voskDir = File(extDir, ModelDownloader.SPEECH_MODEL_DIR)
        val mlcDir  = File(extDir, "models")
        val modelId = ModelDownloader.DEFAULT_MODEL_ID.substringAfterLast('/')
        val modelDir = File(mlcDir, modelId)
        val speechMarker = File(voskDir, "am/final.mdl")
        val mlcConfig = File(modelDir, "mlc-chat-config.json")

        val speechReady = speechMarker.exists() && speechMarker.length() > 0L
        val mlcReady = mlcConfig.exists() && mlcConfig.length() > 0L
        val mlcSizeBytes = if (modelDir.exists()) modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        val speechSizeBytes = if (voskDir.exists()) voskDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L

        val sb = StringBuilder()
        if (!speechReady || !mlcReady) {
            sb.appendLine("── On-device models ──")
            sb.appendLine("Adam downloads its wake-word model (~128 MB), accurate")
            sb.appendLine("speech model (~350 MB, Whisper Small) and its AI model")
            sb.appendLine("(~1.8 GB) automatically the first time you press \"Start Adam\".")
            sb.appendLine()
            sb.appendLine("Sources:")
            sb.appendLine("  ${ModelDownloader.speechSourceUrl}")
            sb.appendLine("  ${ModelDownloader.sherpaSourceUrl}")
            sb.appendLine("  ${ModelDownloader.HF_BASE}/${ModelDownloader.DEFAULT_MODEL_ID}")
            sb.appendLine()
            sb.appendLine("Make sure the phone has at least 4 GB free storage and a Wi-Fi")
            sb.appendLine("connection.")
        }

        return SetupStatus(speechReady, mlcReady, modelId, mlcSizeBytes, speechSizeBytes, sb.toString().trim())
    }
}
