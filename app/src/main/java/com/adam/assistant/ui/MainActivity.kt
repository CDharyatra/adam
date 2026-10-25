package com.adam.assistant.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.adam.assistant.R
import com.adam.assistant.core.AssistantService
import com.adam.assistant.core.ModelSetupChecker
import com.adam.assistant.databinding.ActivityMainBinding
import com.adam.assistant.llm.ModelDownloader
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    // True while the install/download panel owns the voice line + orb
    // (prevents flicker against the service's spoken voiceStatus flow).
    private var downloadingActive = false

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            toast("Microphone ready.")
            ensureContactsThenStart()
        } else {
            if (!shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)) {
                showPermanentlyDeniedDialog()
            } else {
                toast("Microphone permission is required.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnStartStop.setOnClickListener { toggleAssistant() }
        b.btnAccessibility.setOnClickListener { requestAccessibility() }
        b.btnHowToEnable.setOnClickListener { showHowToEnableDialog() }

        b.swWakeWord.isChecked = com.adam.assistant.core.Preferences.isWakeWordEnabled(this)
        b.swWakeWord.setOnCheckedChangeListener { _, isChecked ->
            com.adam.assistant.core.Preferences.setWakeWordEnabled(this, isChecked)
            if (isServiceRunning()) {
                val intent = Intent(this, AssistantService::class.java).apply {
                    action = AssistantService.ACTION_SET_WAKE_WORD
                    putExtra(AssistantService.EXTRA_WAKE_WORD_ENABLED, isChecked)
                }
                startService(intent)
            }
        }

        observeState()
        observeDownloads()
        observeLastHeard()
        observeVoice()
        refreshPermissionStatus()
        refreshModelStatus()
    }

    override fun onResume() {
        super.onResume()
        val before = isAccessibilityEnabled()
        refreshPermissionStatus()
        refreshModelStatus()
        verifyPermissionsAfterReturn(before)
    }

    private fun toggleAssistant() {
        if (isServiceRunning()) {
            val intent = Intent(this, AssistantService::class.java).apply { action = AssistantService.ACTION_STOP }
            startService(intent)
            return
        }
        if (!isAccessibilityEnabled()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Accessibility Service required")
                .setMessage(
                    "Before Adam can listen to you from any app, you need to enable the " +
                    "Accessibility Service. Tap the button below to enable it."
                )
                .setPositiveButton("Got it", null)
                .show()
            return
        }
        requestMicrophone()
    }

    private fun requestMicrophone() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            ensureContactsThenStart()
            return
        }
        if (shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Allow Adam to use your microphone?")
                .setMessage(
                    "Adam needs microphone access to hear your voice commands.\n\n" +
                    "All audio is processed locally on your device using the Vosk speech " +
                    "engine. Nothing is recorded, uploaded, or sent anywhere."
                )
                .setPositiveButton("Allow") { _, _ ->
                    micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                }
                .setNegativeButton("Not now", null)
                .show()
        } else {
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestAccessibility() {
        if (isAccessibilityEnabled()) {
            toast("Accessibility Service is already enabled.")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Enable Accessibility Service")
            .setMessage(
                "Adam uses Android's Accessibility Service to read what is on screen and " +
                "tap / type for you. Android does not show a runtime permission dialog for " +
                "this — you have to enable it once in Settings.\n\n" +
                "Steps after you tap Continue:\n" +
                "  1. Find \"Adam Assistant\" in the list (you may need to scroll)\n" +
                "  2. Tap it, then turn the toggle ON\n" +
                "  3. Read the warning, then tap \"Allow\"\n" +
                "  4. Press the back button to return to Adam"
            )
            .setPositiveButton("Continue") { _, _ -> openAccessibilitySettings() }
            .setNeutralButton("Why?") { _, _ -> showAccessibilityExplainer() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun showHowToEnableDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("How to enable Adam's permissions")
            .setMessage(
                "Adam needs two permissions:\n\n" +
                "1. Microphone — runtime dialog appears when you first tap Start Adam.\n\n" +
                "2. Accessibility Service — special permission, no dialog. Tap the " +
                "\"Enable Accessibility Service\" button, then find Adam Assistant in the " +
                "Settings list and toggle it on.\n\n" +
                "Once both are enabled, tap Start Adam. Say \"Hey Adam\" and Adam will respond " +
                "with a chime. The first launch will auto-download the speech model (~36 MB) " +
                "and the AI model (~1.8 GB), one time only."
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun showAccessibilityExplainer() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Why Accessibility?")
            .setMessage(
                "Android does not allow normal apps to control other apps' UI. The only " +
                "official way to do this is via the Accessibility Service framework, which " +
                "was originally designed to help users with disabilities.\n\n" +
                "Adam uses it ONLY to act on voice commands you initiate, locally on your " +
                "device. No data is sent anywhere."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showPermanentlyDeniedDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Microphone permission blocked")
            .setMessage(
                "You selected \"Don't ask again\". To use Adam, enable the microphone " +
                "permission manually in the system app settings."
            )
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun verifyPermissionsAfterReturn(before: Boolean) {
        val accBefore = before
        val accNow = isAccessibilityEnabled()
        when {
            !accBefore && accNow -> toast("Accessibility Service enabled.")
            !accBefore && !accNow -> {
                lifecycleScope.launch {
                    delay(800)
                    if (isAccessibilityEnabled()) toast("Accessibility Service enabled.")
                }
            }
        }
    }

    private fun startServices() {
        val svcIntent = Intent(this, AssistantService::class.java)
            .apply { action = AssistantService.ACTION_START }
        ContextCompat.startForegroundService(this, svcIntent)
    }

    private val contactsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Optional either way: name matching degrades gracefully without it.
        startServices()
    }

    /**
     * Contacts are optional (name matching only) — Start Adam proceeds
     * whether granted or not, and the rationale shows at most once.
     */
    private fun ensureContactsThenStart() {
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
            == PackageManager.PERMISSION_GRANTED ||
            com.adam.assistant.core.Preferences.isContactsAsked(this)
        ) {
            startServices()
            return
        }
        com.adam.assistant.core.Preferences.setContactsAsked(this)
        MaterialAlertDialogBuilder(this)
            .setTitle("Match names to your contacts?")
            .setMessage(
                "Adam can match names like Shubhanshu against your contacts, " +
                "so mangled speech still reaches the right person.\n\n" +
                "Contact names never leave your phone. " +
                "Skip freely — Adam still works, just with dumber name matching."
            )
            .setPositiveButton("Allow") { _, _ ->
                contactsPermission.launch(android.Manifest.permission.READ_CONTACTS)
            }
            .setNegativeButton("Skip") { _, _ -> startServices() }
            .setOnCancelListener { startServices() }
            .show()
    }

    private fun observeState() {
        AssistantService.state.onEach { state ->
            b.tvStatus.text = "Status: $state"
            b.btnStartStop.text = when (state) {
                AssistantService.State.DOWNLOADING -> "Downloading model…"
                AssistantService.State.IDLE -> if (isServiceRunning()) "Stop Adam" else "Start Adam"
                else -> "Stop Adam"
            }
            b.btnStartStop.isEnabled = state != AssistantService.State.DOWNLOADING
            // Drive the Siri-style orb from the service state —
            // unless the download panel is actively showing progress
            // (it forces SETUP mode with a progress sweep instead).
            if (!downloadingActive) {
                b.orbView.setAssistantState(state.name)
                if (state == AssistantService.State.IDLE) b.orbView.downloadProgress = 0f
            }
        }.launchIn(lifecycleScope)
    }

    private fun observeDownloads() {
        val downloader = ModelDownloader.get(applicationContext)
        downloader.progress.onEach { p ->
            val label = when (p.kind) {
                ModelDownloader.Kind.SPEECH -> "Wake-word model"
                ModelDownloader.Kind.KWS -> "Keyword spot model"
                ModelDownloader.Kind.ASR -> "Accurate speech model"
                ModelDownloader.Kind.LLM -> "AI model"
            }
            val sourceUrl = when (p.kind) {
                ModelDownloader.Kind.SPEECH -> ModelDownloader.speechSourceUrl
                ModelDownloader.Kind.KWS -> ModelDownloader.KWS_TARBALL_URL
                ModelDownloader.Kind.ASR -> ModelDownloader.sherpaSourceUrl
                ModelDownloader.Kind.LLM -> ModelDownloader.LLM_TASK_URL
            }
            b.tvDownloadSource.text = "Source: $sourceUrl"
            when (p.state) {
                ModelDownloader.State.IDLE -> {
                    b.downloadPanel.visibility = android.view.View.GONE
                    downloadingActive = false
                }
                ModelDownloader.State.LISTING,
                ModelDownloader.State.DOWNLOADING,
                ModelDownloader.State.VERIFYING,
                ModelDownloader.State.EXTRACTING -> {
                    downloadingActive = true
                    b.downloadPanel.visibility = android.view.View.VISIBLE
                    val pct = (p.fraction * 100).toInt()
                    b.progressDownload.progress = pct
                    // Orb switches to SETUP sweep mode while installing.
                    b.orbView.orbMode = com.adam.assistant.ui.AdamOrbView.OrbMode.SETUP
                    b.orbView.downloadProgress = p.fraction
                    val mb = p.downloadedBytes / 1_048_576
                    val totalMb = (p.totalBytes / 1_048_576).coerceAtLeast(1L)
                    b.tvDownloadTitle.text = when (p.state) {
                        ModelDownloader.State.LISTING    -> "Listing $label files…"
                        ModelDownloader.State.DOWNLOADING -> "Downloading $label ($pct%)"
                        ModelDownloader.State.EXTRACTING -> "Extracting $label…"
                        ModelDownloader.State.VERIFYING  -> "Verifying $label…"
                        else -> "Preparing $label…"
                    }
                    b.tvDownloadDetail.text = when {
                        p.state == ModelDownloader.State.DOWNLOADING && p.kind == ModelDownloader.Kind.LLM ->
                            "$mb / $totalMb MB  ·  file ${p.fileIndex}/${p.fileCount}: ${p.currentFile}"
                        p.state == ModelDownloader.State.DOWNLOADING ->
                            "$mb / $totalMb MB  ·  ${p.currentFile}"
                        p.state == ModelDownloader.State.EXTRACTING -> p.currentFile
                        else -> ""
                    }
                    // Talking-style voice line — mirrors what Adam speaks aloud
                    // ("Dependencies being installed…", etc.).
                    b.tvVoiceStatus.text = when (p.state) {
                        ModelDownloader.State.LISTING -> "Looking up $label files…"
                        ModelDownloader.State.DOWNLOADING ->
                            "Dependencies being installed — $label $pct%."
                        ModelDownloader.State.EXTRACTING -> "Dependencies being installed — extracting files…"
                        ModelDownloader.State.VERIFYING -> "Verifying installation…"
                        else -> "Setting things up…"
                    }
                }
                ModelDownloader.State.COMPLETE -> {
                    b.downloadPanel.visibility = android.view.View.GONE
                    downloadingActive = false
                    b.orbView.downloadProgress = 1f
                    b.tvVoiceStatus.text = "Setup complete. You can now talk with me."
                    refreshModelStatus()
                }
                ModelDownloader.State.ERROR -> {
                    downloadingActive = false
                    b.downloadPanel.visibility = android.view.View.VISIBLE
                    b.progressDownload.progress = 0
                    b.tvDownloadTitle.text = "$label download failed"
                    b.tvDownloadDetail.text = p.error ?: "Unknown error"
                    b.tvVoiceStatus.text = "Setup failed. ${p.error ?: "Check your connection."}"
                }
                ModelDownloader.State.CANCELED -> {
                    b.downloadPanel.visibility = android.view.View.GONE
                    downloadingActive = false
                }
            }
        }.launchIn(lifecycleScope)
    }

    /** Shows what Adam is saying/doing (spoken by the service via TTS). */
    private fun observeVoice() {
        AssistantService.voiceStatus.onEach { text ->
            if (text.isNotBlank() && !downloadingActive) {
                b.tvVoiceStatus.text = text
            }
        }.launchIn(lifecycleScope)
    }

    private fun observeLastHeard() {
        AssistantService.lastHeard.onEach { text ->
            b.tvLastHeard.text =
                if (text.isBlank()) "Last heard: —" else "Last heard: \"$text\""
        }.launchIn(lifecycleScope)
    }

    private fun refreshPermissionStatus() {
        b.tvAccessibilityStatus.text =
            "Accessibility: ${if (isAccessibilityEnabled()) "✓ Enabled" else "✗ Disabled"}"
    }

    private fun refreshModelStatus() {
        val s = ModelSetupChecker.check(this)
        val speechLine = "Speech model: ${if (s.speechReady) "✓ Ready (${formatBytes(s.speechSizeBytes)})" else "Will download on first start (~36 MB)"}"
        val mlcLine    = "AI model (${s.modelId}): ${if (s.mlcReady) "✓ Ready (${formatBytes(s.mlcSizeBytes)})" else "Will download on first start (~1.8 GB)"}"
        b.tvModelStatus.text = listOf(speechLine, mlcLine).joinToString("\n")
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = listOf("B", "KB", "MB", "GB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return String.format(Locale.US, if (i == 0) "%d %s" else "%.1f %s", v, units[i])
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(AccessibilityManager::class.java)
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.id.contains(packageName) }
    }

    private fun isServiceRunning() =
        AssistantService.state.value != AssistantService.State.IDLE

    private fun openAccessibilitySettings() =
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
