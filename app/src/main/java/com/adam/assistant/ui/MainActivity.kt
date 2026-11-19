package com.adam.assistant.ui

import android.Manifest
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
import com.adam.assistant.core.AssistantCore
import com.adam.assistant.core.AssistantService
import com.adam.assistant.core.CommandMode
import com.adam.assistant.core.CoordinatorReply
import com.adam.assistant.core.DialogState
import com.adam.assistant.core.InputSource
import com.adam.assistant.core.ModelSetupChecker
import com.adam.assistant.core.Preferences
import com.adam.assistant.databinding.ActivityMainBinding
import com.adam.assistant.executor.ActionResult
import com.adam.assistant.llm.ModelDownloader
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    // True while the install/download panel owns the voice line + orb
    // (prevents flicker against the service's spoken voiceStatus flow).
    private var downloadingActive = false

    // Permission requests remember why they were launched so Start/Listen can
    // resume after a grant without asking twice.
    private var pendingStartAfterMic = false
    private var pendingListenAfterMic = false

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val startAfter = pendingStartAfterMic
        val listenAfter = pendingListenAfterMic
        pendingStartAfterMic = false
        pendingListenAfterMic = false
        refreshCapabilities()
        if (granted) {
            toast("Microphone ready.")
            if (startAfter) startServices()
            if (listenAfter && isServiceRunning()) {
                sendAction(AssistantService.ACTION_TOGGLE_LISTEN)
            }
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            showPermissionSettingsDialog("microphone")
        } else {
            toast("Microphone not allowed. Typed commands still work.")
        }
    }

    private val contactsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refreshCapabilities()
        if (granted) {
            toast("Contacts allowed for name matching.")
        } else {
            toast("Contacts not allowed. Names stay exactly as typed or heard.")
            if (!shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)) {
                showPermissionSettingsDialog("contacts")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnStartStop.setOnClickListener { toggleAssistant() }
        b.btnListenOnce.setOnClickListener { listenOnce() }
        b.btnAccessibility.setOnClickListener { requestAccessibility() }
        b.btnHowToEnable.setOnClickListener { showHowToEnableDialog() }
        b.btnMicPermission.setOnClickListener { requestMicrophoneForCapability() }
        b.btnContacts.setOnClickListener { requestContacts() }
        b.btnRunTyped.setOnClickListener { runTypedCommand() }
        b.btnCancelCommand.setOnClickListener { cancelCurrentCommand() }
        b.btnStopSpeaking.setOnClickListener { stopSpeaking() }
        b.btnEditTranscript.setOnClickListener { editLastTranscript() }
        b.btnApprove.setOnClickListener { answerDialog("yes") }
        b.btnReject.setOnClickListener { answerDialog("no") }

        b.rbPreview.isChecked = true

        b.swWakeWord.isChecked = Preferences.isWakeWordEnabled(this)
        b.swWakeWord.setOnCheckedChangeListener { _, isChecked ->
            Preferences.setWakeWordEnabled(this, isChecked)
            if (isServiceRunning()) {
                val intent = Intent(this, AssistantService::class.java).apply {
                    action = AssistantService.ACTION_SET_WAKE_WORD
                    putExtra(AssistantService.EXTRA_WAKE_WORD_ENABLED, isChecked)
                }
                startService(intent)
            }
        }

        b.swLlm.isChecked = Preferences.isLlmEnabled(this)
        b.swLlm.setOnCheckedChangeListener { _, isChecked ->
            Preferences.setLlmEnabled(this, isChecked)
            if (isServiceRunning()) {
                val intent = Intent(this, AssistantService::class.java).apply {
                    action = AssistantService.ACTION_SET_LLM
                    putExtra(AssistantService.EXTRA_LLM_ENABLED, isChecked)
                }
                startService(intent)
            }
        }

        observeState()
        observeRunning()
        observeDownloads()
        observeLastHeard()
        observeVoice()
        refreshCapabilities()
        refreshModelStatus()
        refreshControls()
    }

    override fun onResume() {
        super.onResume()
        val before = isAccessibilityEnabled()
        refreshCapabilities()
        refreshModelStatus()
        refreshControls()
        verifyPermissionsAfterReturn(before)
    }

    // ── Start / stop / listen ────────────────────────────────────────────────

    private fun toggleAssistant() {
        if (isServiceRunning()) {
            sendAction(AssistantService.ACTION_STOP)
            return
        }
        requestMicrophoneForStart()
    }

    private fun requestMicrophoneForStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startServices()
            return
        }
        pendingStartAfterMic = true
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Allow Adam to use your microphone?")
                .setMessage(
                    "Voice input needs the microphone. Typed Preview works " +
                        "without it, and no audio is uploaded.\n\n" +
                        "You can deny this and still type commands in the box below."
                )
                .setPositiveButton("Allow") { _, _ ->
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
                .setNegativeButton("Not now") { _, _ ->
                    pendingStartAfterMic = false
                }
                .setOnCancelListener { pendingStartAfterMic = false }
                .show()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun listenOnce() {
        if (!isServiceRunning()) {
            toast("Start Adam first for voice. Typed Preview works without starting.")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            sendAction(AssistantService.ACTION_TOGGLE_LISTEN)
        } else {
            pendingListenAfterMic = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startServices() {
        val svcIntent = Intent(this, AssistantService::class.java)
            .apply { action = AssistantService.ACTION_START }
        ContextCompat.startForegroundService(this, svcIntent)
    }

    private fun stopSpeaking() {
        if (isServiceRunning()) sendAction(AssistantService.ACTION_STOP_SPEAKING)
        else toast("Adam isn't speaking.")
    }

    private fun cancelCurrentCommand() {
        AssistantCore.cancel()
        if (isServiceRunning()) sendAction(AssistantService.ACTION_CANCEL_COMMAND)
        b.tvCommandDetails.text = "Cancelled. Dialog: none."
        toast("Current command cancelled.")
    }

    // ── Typed command path ───────────────────────────────────────────────────

    private fun runTypedCommand() {
        val text = b.etCommand.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            toast("Type a command first.")
            return
        }
        val mode = when {
            b.rbParseOnly.isChecked -> CommandMode.PARSE_ONLY
            b.rbExecute.isChecked -> CommandMode.EXECUTE
            else -> CommandMode.DRY_RUN
        }
        b.btnRunTyped.isEnabled = false
        lifecycleScope.launch {
            try {
                val reply = AssistantCore.coordinator(this@MainActivity)
                    .submitText(text, InputSource.TYPED, mode)
                renderReply(reply, mode)
                if (!reply.awaitingInput) {
                    b.etCommand.text?.clear()
                }
            } catch (t: Throwable) {
                b.tvCommandDetails.text =
                    "Typed command failed: ${t.javaClass.simpleName}"
            } finally {
                b.btnRunTyped.isEnabled = true
            }
        }
    }

    private fun renderReply(reply: CoordinatorReply, mode: CommandMode) {
        val command = reply.plan?.command
        val slots = buildList {
            if (command != null) {
                if (command.target.isNotBlank()) add("target=${command.target}")
                command.params.forEach { (key, value) -> add("$key=$value") }
                if (command.missingFields.isNotEmpty()) {
                    add("missing=${command.missingFields.joinToString(",")}")
                }
            }
        }.joinToString(", ").ifBlank { "—" }

        val validation = when {
            reply.plan != null -> "valid"
            reply.awaitingInput -> "needs input"
            else -> "rejected"
        }
        val adapter = when (mode) {
            CommandMode.PARSE_ONLY -> "none"
            CommandMode.DRY_RUN -> "mock"
            CommandMode.EXECUTE -> "Android adapter"
        }
        val outcome = reply.result?.let { result ->
            when (result) {
                is ActionResult.DraftOpened -> "DraftOpened(${result.app})"
                is ActionResult.SearchOpened -> "SearchOpened(${result.app})"
                is ActionResult.LaunchRequested -> "LaunchRequested(${result.app})"
                is ActionResult.ObservedComplete -> "ObservedComplete"
                is ActionResult.Failed -> "Failed(${result.reason})"
                is ActionResult.OutcomeUnknown -> "OutcomeUnknown"
                ActionResult.TimedOut -> "TimedOut"
                ActionResult.Cancelled -> "Cancelled"
            }
        } ?: "—"

        b.tvCommandDetails.text = buildString {
            appendLine("Action: ${command?.action ?: "—"}")
            appendLine("Slots: $slots")
            appendLine("Provenance: ${reply.plan?.source?.name ?: "—"}")
            appendLine("Validation: $validation")
            appendLine("Dialog: ${reply.dialog.name.lowercase()}")
            appendLine("Adapter: $adapter")
            appendLine("Outcome: $outcome")
            append("Say: ${reply.speech.ifBlank { "—" }}")
        }

        renderApproval(reply)
    }

    private fun renderApproval(reply: CoordinatorReply) {
        val confirming = reply.dialog == DialogState.CONFIRM
        b.approvalPanel.visibility = if (confirming) android.view.View.VISIBLE
        else android.view.View.GONE
        if (!confirming) return
        b.tvApprovalPrompt.text = getString(R.string.approval_prompt)
        b.tvApprovalDetail.text = reply.plan?.let { plan ->
            buildString {
                appendLine("Action: ${plan.command.action}")
                appendLine("Target: ${plan.command.target.ifBlank { "—" }}")
                appendLine("Recipient: ${plan.recipient?.displayName ?: "—"}")
                appendLine("Number: ${plan.recipient?.number?.ifBlank { "—" } ?: "—"}")
                appendLine("Body: ${plan.command.params["body"]?.ifBlank { "—" } ?: "—"}")
                append("Rev: ${plan.revisionKey}")
            }
        } ?: reply.speech
    }

    private fun answerDialog(answer: String) {
        val mode = when {
            b.rbParseOnly.isChecked -> CommandMode.PARSE_ONLY
            b.rbExecute.isChecked -> CommandMode.EXECUTE
            else -> CommandMode.DRY_RUN
        }
        lifecycleScope.launch {
            try {
                val reply = AssistantCore.coordinator(this@MainActivity)
                    .submitText(answer, InputSource.TYPED, mode)
                renderReply(reply, mode)
            } catch (t: Throwable) {
                b.tvCommandDetails.text =
                    "Typed command failed: ${t.javaClass.simpleName}"
            }
        }
    }

    private fun editLastTranscript() {
        val heard = AssistantService.lastHeard.value
        if (heard.isBlank()) {
            toast("No transcript yet.")
            return
        }
        b.etCommand.setText(heard)
        b.etCommand.setSelection(heard.length)
        b.etCommand.requestFocus()
    }

    // ── Permissions / capabilities ───────────────────────────────────────────

    private fun requestMicrophoneForCapability() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            toast("Microphone already allowed.")
            return
        }
        micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestContacts() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            toast("Contacts already allowed.")
            return
        }
        if (!Preferences.isContactsAsked(this)) {
            Preferences.setContactsAsked(this)
            MaterialAlertDialogBuilder(this)
                .setTitle("Match names to your contacts?")
                .setMessage(
                    "Adam can match names like Shubhanshu against your contacts, " +
                        "so mangled speech still reaches the right person.\n\n" +
                        "Contact names never leave your phone. Skip freely — Adam " +
                        "still works, names just stay exactly as typed or heard."
                )
                .setPositiveButton("Allow") { _, _ ->
                    contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                }
                .setNegativeButton("Not now", null)
                .show()
        } else if (shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)) {
            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
        } else {
            showPermissionSettingsDialog("contacts")
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
                "Accessibility is optional. It is only needed for actions that " +
                    "touch other apps (taps, typing, screen reading, calls by name).\n\n" +
                    "Android does not show a runtime dialog for this — enable it once " +
                    "in Settings:\n" +
                    "  1. Find \"Adam Assistant\" in the list\n" +
                    "  2. Tap it, then turn the toggle ON\n" +
                    "  3. Read the warning, then tap \"Allow\"\n" +
                    "  4. Press back to return to Adam"
            )
            .setPositiveButton("Continue") { _, _ -> openAccessibilitySettings() }
            .setNeutralButton("Why?") { _, _ -> showAccessibilityExplainer() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun showHowToEnableDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("How permissions and models are used")
            .setMessage(
                "Typed Preview works right now — no permission and no model needed.\n\n" +
                    "Microphone: only for Listen once and the optional wake word.\n" +
                    "Accessibility: only for actions that touch other apps.\n" +
                    "Contacts: optional, only for matching recipient names.\n\n" +
                    ModelSetupChecker.check(this).instructions
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun showAccessibilityExplainer() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Why Accessibility?")
            .setMessage(
                "Android does not allow normal apps to control other apps' UI. The only " +
                    "official way to do this is via the Accessibility Service framework, " +
                    "which was originally designed to help users with disabilities.\n\n" +
                    "Adam uses it only for commands you initiate, locally on your device. " +
                    "No data is sent anywhere."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showPermissionSettingsDialog(label: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("$label permission blocked")
            .setMessage(
                "You selected \"Don't ask again\". Enable the $label permission " +
                    "manually in system app settings if you want this feature."
            )
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
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
                    refreshCapabilities()
                }
            }
        }
    }

    // ── Observers ────────────────────────────────────────────────────────────

    private fun observeState() {
        AssistantService.state.onEach { state ->
            b.tvStatus.text = "Status: $state"
            // Drive the Siri-style orb from the service state —
            // unless the download panel is actively showing progress
            // (it forces SETUP mode with a progress sweep instead).
            if (!downloadingActive) {
                b.orbView.setAssistantState(state.name)
                if (state == AssistantService.State.IDLE) b.orbView.downloadProgress = 0f
            }
            refreshControls()
        }.launchIn(lifecycleScope)
    }

    private fun observeRunning() {
        AssistantService.running.onEach { refreshControls() }.launchIn(lifecycleScope)
    }

    private fun observeDownloads() {
        val downloader = ModelDownloader.get(applicationContext)
        downloader.progress.onEach { p ->
            val label = when (p.kind) {
                ModelDownloader.Kind.SPEECH -> "Wake-word model"
                ModelDownloader.Kind.KWS -> "Keyword spot model"
                ModelDownloader.Kind.ASR -> "Accurate speech model"
                ModelDownloader.Kind.LLM -> "Optional AI model"
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
                        p.state == ModelDownloader.State.DOWNLOADING &&
                            p.kind == ModelDownloader.Kind.LLM ->
                            "$mb / $totalMb MB  ·  file ${p.fileIndex}/${p.fileCount}: ${p.currentFile}"
                        p.state == ModelDownloader.State.DOWNLOADING ->
                            "$mb / $totalMb MB  ·  ${p.currentFile}"
                        p.state == ModelDownloader.State.EXTRACTING -> p.currentFile
                        else -> ""
                    }
                    b.tvVoiceStatus.text = when (p.state) {
                        ModelDownloader.State.LISTING -> "Looking up $label files…"
                        ModelDownloader.State.DOWNLOADING ->
                            "Dependencies being installed — $label $pct%."
                        ModelDownloader.State.EXTRACTING ->
                            "Dependencies being installed — extracting files…"
                        ModelDownloader.State.VERIFYING -> "Verifying installation…"
                        else -> "Setting things up…"
                    }
                }
                ModelDownloader.State.COMPLETE -> {
                    b.downloadPanel.visibility = android.view.View.GONE
                    downloadingActive = false
                    b.orbView.downloadProgress = 1f
                    b.tvVoiceStatus.text = "Setup complete. Tap Listen once to talk."
                    refreshModelStatus()
                }
                ModelDownloader.State.ERROR -> {
                    downloadingActive = false
                    b.downloadPanel.visibility = android.view.View.VISIBLE
                    b.progressDownload.progress = 0
                    b.tvDownloadTitle.text = "$label download failed"
                    b.tvDownloadDetail.text = p.error ?: "Unknown error"
                    b.tvVoiceStatus.text = "Setup failed. ${p.error ?: "Check your connection."}"
                    refreshModelStatus()
                }
                ModelDownloader.State.CANCELED -> {
                    b.downloadPanel.visibility = android.view.View.GONE
                    downloadingActive = false
                    refreshModelStatus()
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

    private fun refreshControls() {
        val running = isServiceRunning()
        val state = AssistantService.state.value
        val busy = state == AssistantService.State.INITIALISING ||
            state == AssistantService.State.DOWNLOADING
        b.btnStartStop.text = getString(
            if (running) R.string.stop_adam else R.string.start_adam
        )
        // Stop stays reachable while provisioning/downloading.
        b.btnStartStop.isEnabled = true
        b.btnListenOnce.isEnabled = running && !busy
        b.btnStopSpeaking.isEnabled = running
    }

    private fun refreshCapabilities() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        val accessibility = isAccessibilityEnabled()
        b.tvCapabilities.text = buildString {
            appendLine(
                "Microphone: " +
                    if (mic) "ready" else "not granted — voice only, typed still works"
            )
            appendLine(
                "Accessibility: " +
                    if (accessibility) "enabled"
                    else "not enabled — needed for screen actions"
            )
            appendLine(
                "Contacts: " +
                    if (contacts) "allowed" else "not allowed — names stay as typed"
            )
            append("Typed Preview: always ready")
        }
    }

    private fun refreshModelStatus() {
        val status = ModelSetupChecker.check(this)
        b.tvModelStatus.text = if (status.models.isEmpty()) {
            "No external storage — models cannot be installed."
        } else {
            status.models.joinToString("\n") { model ->
                val state = when {
                    model.ready && model.sizeBytes > 0L ->
                        "ready (${ModelSetupChecker.humanBytes(model.sizeBytes)})"
                    model.ready -> "ready"
                    model.expectedBytes > 0L ->
                        "not installed (~${ModelSetupChecker.humanBytes(model.expectedBytes)})"
                    else -> "not installed"
                }
                "• ${model.label}: $state"
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(AccessibilityManager::class.java)
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.id.contains(packageName) }
    }

    private fun isServiceRunning() = AssistantService.running.value

    private fun sendAction(action: String) {
        startService(Intent(this, AssistantService::class.java).apply { this.action = action })
    }

    private fun openAccessibilitySettings() =
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
