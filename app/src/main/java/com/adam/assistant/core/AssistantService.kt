package com.adam.assistant.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.adam.assistant.R
import com.adam.assistant.executor.CommandExecutor
import com.adam.assistant.executor.CommandExecutorActionPort
import com.adam.assistant.llm.LlmEngine
import com.adam.assistant.llm.ModelDownloader
import com.adam.assistant.stt.SttEngine
import com.adam.assistant.tts.TtsEngine
import com.adam.assistant.ui.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class AssistantService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "adam_assistant"
        private const val NOTIF_ID = 1
        const val ACTION_START = "START"
        const val ACTION_STOP  = "STOP"
        const val ACTION_TOGGLE_LISTEN = "TOGGGLE_LISTEN"
        const val ACTION_STOP_SPEAKING = "STOP_SPEAKING"
        const val ACTION_CANCEL_COMMAND = "CANCEL_COMMAND"
        const val ACTION_SET_WAKE_WORD = "SET_WAKE_WORD"
        const val ACTION_SET_LLM = "SET_LLM"
        const val EXTRA_WAKE_WORD_ENABLED = "wake_word_enabled"
        const val EXTRA_LLM_ENABLED = "llm_enabled"

        private val _state = MutableStateFlow(State.IDLE)
        val state: StateFlow<State> = _state

        // Service existence is independent from ready/listening state: IDLE
        // while running means "awake but not listening", not "stopped".
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        private val _lastHeard = MutableStateFlow("")
        val lastHeard: StateFlow<String> = _lastHeard

        // Spoken + on-screen narration of what Adam is doing
        // (install progress, listening, thinking, speaking).
        private val _voiceStatus = MutableStateFlow("Hey, I'm Adam.")
        val voiceStatus: StateFlow<String> = _voiceStatus
    }

    enum class State {
        IDLE,
        INITIALISING,
        DOWNLOADING,
        WAKEWORD_LISTENING,
        CONVERSATION_LISTENING,
        CONVERSATION_PROCESSING,
        CONVERSATION_SPEAKING,
        LISTENING, PROCESSING, SPEAKING,
        ERROR
    }

    private lateinit var stt: SttEngine
    private lateinit var tts: TtsEngine
    private lateinit var llm: LlmEngine
    private lateinit var executor: CommandExecutor
    private lateinit var coordinator: AssistantCoordinator
    private val audioCue = AudioCue()

    private var listenJob: Job? = null
    private var processJob: Job? = null
    private var wakeWordJob: Job? = null
    private var conversationJob: Job? = null
    private var startupJob: Job? = null
    private var downloadObserveJob: Job? = null
    private var started = false

    private var lastNotifText = ""
    private var lastNotifTime = 0L

    private val wakeWords = listOf("hey adam", "ok adam", "adam")
    private val exitPhrases = setOf(
        "stop listening", "goodbye", "bye adam", "exit conversation",
        "stop adam", "shut up", "go to sleep"
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_TOGGLE_LISTEN -> toggleListening()
                ACTION_STOP_SPEAKING -> stopSpeaking()
                ACTION_CANCEL_COMMAND -> cancelCurrentCommand()
                ACTION_SET_WAKE_WORD -> {
                    val enabled = intent.getBooleanExtra(EXTRA_WAKE_WORD_ENABLED, true)
                    Preferences.setWakeWordEnabled(this@AssistantService, enabled)
                    applyWakeWordPreference()
                }
                ACTION_SET_LLM -> {
                    val enabled = intent.getBooleanExtra(EXTRA_LLM_ENABLED, false)
                    applyLlmPreference(enabled)
                }
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        stt = SttEngine(this)
        tts = TtsEngine(this)
        llm = LlmEngine(this)
        executor = CommandExecutor(this)
        val region = AssistantCore.deviceRegion()
        coordinator = AssistantCoordinator(
            router = CommandRouter { text ->
                llm.processDetailed(text).also { routed ->
                    Log.i(
                        "Adam",
                        "Engine: ${routed.source} conf=${"%.2f".format(routed.confidence)}"
                    )
                }
            },
            executor = CommandExecutorActionPort(executor),
            policy = CommandPolicy(
                capabilities = AndroidCapabilityRegistry(this),
                phoneRegion = region
            ),
            contacts = AndroidContactDirectory(this),
            phoneRegion = region
        )
        audioCue.init()
        createNotificationChannel()
        val filter = IntentFilter().apply {
            addAction(ACTION_TOGGLE_LISTEN)
            addAction(ACTION_STOP_SPEAKING)
            addAction(ACTION_CANCEL_COMMAND)
            addAction(ACTION_SET_WAKE_WORD)
            addAction(ACTION_SET_LLM)
        }
        // ContextCompat backports RECEIVER_NOT_EXPORTED to API < 33: the
        // framework 3-arg overload doesn't exist before Android 13 and used
        // to crash onCreate with NoSuchMethodError on older phones.
        ContextCompat.registerReceiver(
            this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START          -> start()
            ACTION_STOP           -> stop()
            ACTION_TOGGLE_LISTEN  -> toggleListening()
            ACTION_STOP_SPEAKING  -> stopSpeaking()
            ACTION_CANCEL_COMMAND -> cancelCurrentCommand()
            ACTION_SET_WAKE_WORD  -> {
                val enabled = intent.getBooleanExtra(EXTRA_WAKE_WORD_ENABLED, true)
                Preferences.setWakeWordEnabled(this, enabled)
                applyWakeWordPreference()
            }
            ACTION_SET_LLM -> {
                val enabled = intent.getBooleanExtra(EXTRA_LLM_ENABLED, false)
                applyLlmPreference(enabled)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        started = false
        _running.value = false
        startupJob?.cancel(); startupJob = null
        cancelAllJobs()
        try { llm.downloader.cancel() } catch (_: Throwable) { }
        try { unregisterReceiver(receiver) } catch (_: Throwable) { }
        try { stt.release() } catch (_: Throwable) { }
        try { tts.release() } catch (_: Throwable) { }
        try { llm.release() } catch (_: Throwable) { }
        try { audioCue.release() } catch (_: Throwable) { }
        _state.value = State.IDLE
        super.onDestroy()
    }

    private fun cancelAllJobs() {
        listenJob?.cancel();          listenJob = null
        processJob?.cancel();         processJob = null
        wakeWordJob?.cancel();        wakeWordJob = null
        conversationJob?.cancel();    conversationJob = null
        downloadObserveJob?.cancel(); downloadObserveJob = null
    }


    private fun start() {
        // A second Start tap (or a re-delivered intent) must not re-run the
        // installer or spawn a second wake loop.
        if (started) return
        try {
            startForeground(NOTIF_ID, buildNotification("Adam is initialising"))
        } catch (t: Throwable) {
            Log.e("Adam", "startForeground failed", t)
            _running.value = false
            _state.value = State.ERROR
            _voiceStatus.value = "Couldn't start background mode: ${t.message}"
            return
        }
        started = true
        _running.value = true
        startupJob = lifecycleScope.launch {
            // Every init step below touches native code / network / TTS, any
            // of which can throw (including Errors like UnsatisfiedLinkError
            // or OutOfMemoryError). An uncaught throw in this coroutine used
            // to crash the app right after "dependencies being installed".
            // Fail soft: land in a visible ERROR/IDLE state with a message.
            try {
                _state.value = State.INITIALISING
                _voiceStatus.value = "Waking up…"
                try { tts.init() } catch (t: Throwable) {
                    Log.e("Adam", "TTS init failed (non-fatal)", t)
                }
                // ── Talking installer: Adam says what it is doing ──
                try { sayStatus("Hello! I am Adam. Setting up dependencies.") } catch (t: Throwable) {
                    Log.e("Adam", "Status announcement failed (non-fatal)", t)
                }
                observeDownloads()
                _state.value = State.DOWNLOADING
                updateNotification("Preparing speech model…")
                try { sayStatus("Dependencies being installed. Downloading speech model.") } catch (t: Throwable) {
                    Log.e("Adam", "Status announcement failed (non-fatal)", t)
                }
                try {
                    stt.init()
                } catch (t: Throwable) {
                    Log.e("Adam", "Speech init crashed (contained)", t)
                }
                if (!stt.isReady) {
                    val msg = "Speech model isn't ready — check storage and " +
                        "connection, then tap Start Adam again."
                    Log.e("Adam", msg)
                    _voiceStatus.value = msg
                    updateNotification("Speech model unavailable")
                    try { tts.speak("Speech model isn't ready. Please check your connection and try again.") } catch (t: Throwable) {
                        Log.e("Adam", "TTS speak failed (non-fatal)", t)
                    }
                    goIdle("Speech model unavailable")
                    return@launch
                }
                try { sayStatus("Speech ready. Preparing command parser.") } catch (t: Throwable) {
                    Log.e("Adam", "Status announcement failed (non-fatal)", t)
                }
                if (Preferences.isLlmEnabled(this@AssistantService)) {
                    updateNotification("Preparing optional AI model…")
                    try { sayStatus("Downloading the optional AI model. This may take a while.") } catch (t: Throwable) {
                        Log.e("Adam", "Status announcement failed (non-fatal)", t)
                    }
                    try {
                        llm.init()
                    } catch (t: Throwable) {
                        Log.e("Adam", "LLM init crashed (contained, regex fallback active)", t)
                    }
                } else {
                    updateNotification("Using built-in command parser")
                    try { sayStatus("AI model is off. Using built-in commands.") } catch (t: Throwable) {
                        Log.e("Adam", "Status announcement failed (non-fatal)", t)
                    }
                }
                try { sayStatus("Setup complete. Tap Listen once to talk to me.") } catch (t: Throwable) {
                    Log.e("Adam", "Status announcement failed (non-fatal)", t)
                }
                applyWakeWordPreference()
            } catch (t: Throwable) {
                Log.e("Adam", "Startup failed", t)
                _state.value = State.ERROR
                _voiceStatus.value = "Startup failed: ${t.message ?: t.javaClass.simpleName}"
                updateNotification("Startup failed — tap Start Adam to retry")
            }
        }
    }

    /** Update on-screen + notification narration and speak it aloud. */
    private suspend fun sayStatus(text: String) {
        _voiceStatus.value = text
        updateNotification(text)
        tts.speak(text)
    }

    private fun applyWakeWordPreference() {
        if (Preferences.isWakeWordEnabled(this)) startWakeWordMode()
        else {
            _voiceStatus.value = "Adam is ready. Tap the mic to talk."
            goIdle("Adam is ready")
        }
    }

    private fun goIdle(notification: String) {
        cancelAllJobs()
        _state.value = State.IDLE
        if (_voiceStatus.value.isBlank()) _voiceStatus.value = notification
        updateNotification(notification)
    }

    private fun stop() {
        started = false
        _running.value = false
        startupJob?.cancel(); startupJob = null
        try { llm.downloader.cancel() } catch (_: Throwable) { }
        cancelAllJobs()
        clearFollowUps()
        _state.value = State.IDLE
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopSpeaking() {
        try { tts.stop() } catch (_: Throwable) { }
        audioCue.bargeIn()
    }

    private fun cancelCurrentCommand() {
        coordinator.cancel()
        processJob?.cancel(); processJob = null
        listenJob?.cancel(); listenJob = null
        conversationJob?.cancel(); conversationJob = null
        wakeWordJob?.cancel(); wakeWordJob = null
        try { tts.stop() } catch (_: Throwable) { }
        audioCue.bargeIn()
        _state.value = State.IDLE
        _voiceStatus.value = "Cancelled."
        updateNotification("Adam is ready")
        if (Preferences.isWakeWordEnabled(this) && stt.isReady) startWakeWordMode()
    }

    private fun applyLlmPreference(enabled: Boolean) {
        Preferences.setLlmEnabled(this, enabled)
        lifecycleScope.launch {
            try {
                if (enabled) {
                    _voiceStatus.value = "Loading optional AI model…"
                    updateNotification("Loading optional AI model…")
                    llm.init()
                    _voiceStatus.value = if (llm.brain.isReady) {
                        "Optional AI model ready."
                    } else {
                        "AI model unavailable — built-in parser stays active."
                    }
                } else {
                    llm.release()
                    _voiceStatus.value = "Optional AI model off."
                }
                updateNotification(_voiceStatus.value)
            } catch (t: Throwable) {
                Log.e("Adam", "LLM preference change failed", t)
                _voiceStatus.value = "AI model change failed."
            }
        }
    }

    fun toggleListening() {
        when (_state.value) {
            State.CONVERSATION_SPEAKING, State.SPEAKING -> {
                tts.stop()
                audioCue.bargeIn()
            }
            else -> {
                wakeWordJob?.cancel()
                conversationJob?.cancel()
                listenJob?.cancel()
                processJob?.cancel()
            }
        }
        startOneShotListening()
    }

    private fun startOneShotListening() {
        if (!stt.isReady) {
            val busy = _state.value == State.INITIALISING ||
                _state.value == State.DOWNLOADING
            lifecycleScope.launch {
                tts.speak(
                    if (busy) "Still getting ready. Please try again in a moment."
                    else "Speech model not found."
                )
            }
            return
        }
        listenJob?.cancel(); processJob?.cancel()
        listenJob = lifecycleScope.launch {
            _state.value = State.LISTENING
            updateNotification("Listening…")
            try {
                stt.listen()
                    .catch { e ->
                        Log.e("Adam", "STT error", e)
                        _state.value = State.ERROR
                        tts.speak("Microphone error.")
                    }
                    .collect { result ->
                        if (result.isFinal) {
                            val stale = listenJob
                            listenJob = null
                            stale?.cancel()
                            processJob = lifecycleScope.launch {
                                handleOneShotResult(result.text)
                            }
                        }
                    }
            } finally {
                if (isActive && Preferences.isWakeWordEnabled(this@AssistantService)) {
                    startWakeWordMode()
                } else if (isActive) {
                    _state.value = State.IDLE
                    updateNotification("Adam is ready")
                }
            }
        }
    }

    private suspend fun handleOneShotResult(text: String) {
        _lastHeard.value = text
        Log.i("Adam", "Heard one-shot input (${text.length} chars)")
        if (text.isBlank()) {
            _state.value = State.SPEAKING
            _voiceStatus.value = "I didn't hear anything."
            updateNotification("Speaking…")
            tts.speak("I didn't hear anything.")
            return
        }
        _state.value = State.PROCESSING
        _voiceStatus.value = "Working on it…"
        updateNotification("Working on it…")
        val response = processUtterance(text)
        Log.i("Adam", "Reply ready (${response.length} chars)")
        _state.value = State.SPEAKING
        _voiceStatus.value = response
        updateNotification("Speaking…")
        tts.speak(response)
    }

    // ── Follow-ups: confirmations + slot filling ─────────────────────────────
    //
    // High-risk actions (message/call/email) never execute without a yes, and
    // clarification questions are answered by the next utterance — in both the
    // conversation loop and the one-shot mic path below. The coordinator owns
    // that state; this service only feeds it text and speaks the reply.

    private fun clearFollowUps() {
        coordinator.cancel()
    }

    private suspend fun processUtterance(text: String): String {
        // Bare wake words inside a conversation ("hey adam?") get an
        // acknowledgement instead of polluting the novelty log.
        if (stripWakeWords(text).isBlank() && containsWakeWord(text)) {
            return "Yes, I'm listening."
        }
        return coordinator.submitText(text, InputSource.VOICE, CommandMode.EXECUTE).speech
    }

    private fun startWakeWordMode() {
        if (!stt.isReady) {
            goIdle("Adam is ready (speech model missing)")
            return
        }
        wakeWordJob?.cancel(); conversationJob?.cancel()
        listenJob?.cancel(); processJob?.cancel()
        wakeWordJob = lifecycleScope.launch {
            _state.value = State.WAKEWORD_LISTENING
            _voiceStatus.value = "Say \"Hey Adam\" to talk…"
            updateNotification("Say \"Hey Adam\" to start…")
            var failures = 0
            try {
                // Bounded retry: a dead mic used to park the service in ERROR
                // forever with no recovery. After several failures, idle out
                // with a message instead of dying silently.
                while (isActive && failures < 5) {
                    try {
                        stt.listenForWakeWord(wakeWords)
                            .collect { wakeText ->
                                failures = 0
                                _lastHeard.value = wakeText
                                Log.i("Adam", "Wake word detected")
                                audioCue.wakeDetected()
                                // Users speak in one breath ("hey adam what's 12 times 7"):
                                // keep whatever came after the wake phrase as the first
                                // command instead of throwing it away and listening again.
                                startConversation(stripWakeWords(wakeText))
                                return@collect
                            }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failures++
                        Log.e("Adam", "Wake-word error ($failures)", e)
                        _state.value = State.ERROR
                        _voiceStatus.value = "Microphone trouble — retrying…"
                        updateNotification("Microphone trouble — retrying…")
                        delay(2000)
                    }
                }
                if (failures >= 5 && isActive) {
                    _state.value = State.IDLE
                    _voiceStatus.value = "Microphone unavailable. Tap Start Adam to retry."
                    updateNotification("Microphone unavailable")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal teardown (conversation started, service stopping).
            } catch (e: Exception) {
                Log.e("Adam", "Wake word loop crashed", e)
            }
        }
    }

    private fun startConversation(firstUtterance: String = "") {
        wakeWordJob?.cancel(); conversationJob?.cancel()
        listenJob?.cancel(); processJob?.cancel()
        conversationJob = lifecycleScope.launch {
            var idleRounds = 0
            var pending: String? = firstUtterance.takeIf { it.isNotBlank() }
            if (pending != null) {
                Log.i("Adam", "Leading with captured text (${pending.length} chars)")
            }
            while (isActive) {
                _state.value = State.CONVERSATION_LISTENING
                _voiceStatus.value = "Listening… speak now."
                updateNotification("Listening…")

                val contactAsk = coordinator.pendingContactCandidates()
                val utterance = pending?.also { pending = null }
                    ?: listenForCommand(contactAsk)

                if (utterance.isBlank()) {
                    idleRounds++
                    if (idleRounds >= 2) {
                        audioCue.bargeIn()
                        clearFollowUps()
                        break
                    }
                    continue
                }
                idleRounds = 0
                _lastHeard.value = utterance
                Log.i("Adam", "Heard input (${utterance.length} chars)")

                if (isExitCommand(utterance)) {
                    audioCue.commandHeard()
                    clearFollowUps()
                    break
                }

                audioCue.commandHeard()
                _state.value = State.CONVERSATION_PROCESSING
                _voiceStatus.value = "Working on it…"
                updateNotification("Working on it…")
                val response = processUtterance(utterance)
                Log.i("Adam", "Reply ready (${response.length} chars)")

                _state.value = State.CONVERSATION_SPEAKING
                _voiceStatus.value = response
                updateNotification("Speaking…")
                val interruption = speakWithBargeIn(response)
                if (interruption != null) {
                    // Cut in with "hey adam …" mid-reply: handle the rest now
                    // instead of dropping it and listening again.
                    val remainder = stripWakeWords(interruption)
                    Log.i("Adam", "Interrupted with captured text (${remainder.length} chars)")
                    if (remainder.isBlank() || isExitCommand(remainder) || remainder == "stop") {
                        audioCue.commandHeard()
                        break
                    }
                    pending = remainder
                }
            }

            if (isActive && Preferences.isWakeWordEnabled(this@AssistantService)) {
                startWakeWordMode()
            } else if (isActive) {
                _state.value = State.IDLE
                updateNotification("Adam is ready")
            }
        }
    }

    private fun isExitCommand(text: String): Boolean {
        val t = text.lowercase().trim()
        return exitPhrases.any { t == it || t.contains(it) }
    }

    /**
     * Captures one command utterance. The accurate (Sherpa) path records audio
     * first and decodes second, so each phase gets its own budget: a single
     * 15 s cap over both starved transcription whenever recording ran long
     * (continuous speech deterministically produced silence). The Vosk /
     * contact-grammar paths stream finals and keep the original 15 s cap.
     */
    private suspend fun listenForCommand(contactAsk: List<ContactCandidate>): String {
        if (contactAsk.isEmpty() && stt.isAccurateReady) {
            val samples = withTimeoutOrNull(20_000L) {
                try {
                    stt.captureUtterance()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e("Adam", "STT capture failed", t)
                    FloatArray(0)
                }
            } ?: FloatArray(0)
            if (samples.isEmpty()) return ""
            Log.i("Adam", "Captured ${samples.size} samples, transcribing…")
            return withTimeoutOrNull(90_000L) { stt.transcribeSamples(samples) } ?: ""
        }
        return withTimeoutOrNull(15_000L) {
            val flow = if (contactAsk.isNotEmpty()) {
                Log.i("Adam", "Listening with contact vocabulary…")
                stt.listenWithGrammar(Contacts.grammarJson(contactAsk))
            } else {
                stt.listen()
            }
            flow
                .catch { Log.e("Adam", "STT error", it) }
                .first { it.isFinal }
                ?.text
        } ?: ""
    }

    private fun containsWakeWord(text: String): Boolean {
        val padded = " ${text.lowercase()} "
        return padded.contains(" hey adam ") ||
            padded.contains(" ok adam ") ||
            padded.contains(" adam ")
    }    private fun stripWakeWords(text: String): String {
        val lower = text.lowercase()
        val hit = listOf("hey adam", "ok adam", "adam")
            .mapNotNull { w ->
                lower.indexOf(w).takeIf { it >= 0 }?.let { it to w.length }
            }
            .minByOrNull { it.first }
            ?: return ""
        return text.substring(hit.first + hit.second).trim { it in " ,.!?'" }.trim()
    }

    /**
     * Speaks [text], racing it against wake-word detection so the user can cut
     * in with "hey adam …". Keyword interruption — not mic energy — is immune
     * to speaker echo, coughs and TV noise: Vosk transcribes our own TTS
     * output perfectly well, but it never contains the wake phrase.
     *
     * @return the interrupting utterance, or null if the reply completed.
     */
    private suspend fun speakWithBargeIn(text: String): String? = coroutineScope {
        val finished = CompletableDeferred<Finisher>()
        val interrupted = CompletableDeferred<String>()

        val speakJob = launch {
            try {
                tts.speak(text)
                finished.complete(Finisher.TTS)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                finished.complete(Finisher.TTS)
            }
        }

        val monitorJob = launch(Dispatchers.IO) {
            try {
                val heard = stt.listenForWakeWord(wakeWords).first()
                interrupted.complete(heard)
                finished.complete(Finisher.MIC)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Mic trouble mid-reply: degrade to no-interruption so the
                // reply still completes instead of hanging the conversation.
            }
        }

        val who = finished.await()

        when (who) {
            Finisher.TTS -> {
                monitorJob.cancel()
                null
            }
            Finisher.MIC -> {
                tts.stop()
                speakJob.cancel()
                audioCue.bargeIn()
                interrupted.await()
            }
        }
    }

    private enum class Finisher { TTS, MIC }

    // ── Downloads ────────────────────────────────────────────────────────────

    private fun observeDownloads() {
        downloadObserveJob?.cancel()
        downloadObserveJob = lifecycleScope.launch {
            llm.downloadProgress.collectLatest { p ->
                val label = when (p.kind) {
                    ModelDownloader.Kind.SPEECH -> "wake-word model"
                    ModelDownloader.Kind.KWS -> "keyword spot model"
                    ModelDownloader.Kind.ASR -> "accurate speech model"
                    ModelDownloader.Kind.LLM -> "AI model"
                }
                when (p.state) {
                    ModelDownloader.State.LISTING -> {
                        _state.value = State.DOWNLOADING
                        updateNotification("Listing $label files…")
                    }
                    ModelDownloader.State.DOWNLOADING -> {
                        _state.value = State.DOWNLOADING
                        val pct = (p.fraction * 100).toInt()
                        val mb = p.downloadedBytes / 1_048_576
                        val totalMb = (p.totalBytes / 1_048_576).coerceAtLeast(1L)
                        updateNotification("Downloading $label: $mb / $totalMb MB ($pct%)")
                    }
                    ModelDownloader.State.EXTRACTING -> {
                        updateNotification("Extracting $label…")
                    }
                    ModelDownloader.State.VERIFYING -> {
                        updateNotification("Verifying $label…")
                    }
                    ModelDownloader.State.ERROR -> {
                        updateNotification("$label download failed: ${p.error}")
                    }
                    ModelDownloader.State.COMPLETE, ModelDownloader.State.IDLE -> Unit
                    ModelDownloader.State.CANCELED -> {
                        updateNotification("$label download canceled")
                    }
                }
            }
        }
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Adam Assistant", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Adam")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // The download path reports every 64 KB chunk; without coalescing the
        // system sheds our updates ("rate limit exceeded" spam) and burns battery.
        if (text == lastNotifText) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifTime < 1200L) return
        lastNotifText = text
        lastNotifTime = now
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
