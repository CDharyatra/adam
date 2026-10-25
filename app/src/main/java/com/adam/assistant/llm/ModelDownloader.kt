package com.adam.assistant.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * One-time on-device model downloader.
 *
 * Handles two models:
 *  - **LLM** — multi-file directory fetched from Hugging Face (resumable per file)
 *  - **Speech** — single zip from alphacephei.com, unzipped on device
 *
 * The downloader is:
 *  - Idempotent — if the target directory already contains the marker file,
 *    no download happens.
 *  - Resumable (LLM only) — partial files kept in `.partial` and resumed via
 *    HTTP Range on retry.
 *  - Observable — exposes [progress] as a StateFlow tagged with [Kind] so the
 *    UI can label "Downloading AI model" vs "Downloading speech model".
 *  - Transparent — the source URLs are returned by [sourceUrl] / [speechSourceUrl].
 */
class ModelDownloader private constructor(private val context: Context) {

    companion object {
        const val HF_BASE = "https://huggingface.co"

        val DEFAULT_MODEL_ID = "mlc-ai/Qwen2.5-3B-Instruct-q4f16_1-MLC"

        const val DEFAULT_SPEECH_MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip"
        const val DEFAULT_SPEECH_MODEL_BYTES = 128_000_000L   // ~128 MB compressed

        const val SPEECH_MODEL_DIR = "vosk-model-en-us-lgraph"
        private const val SPEECH_MARKER = "am/final.mdl"

        @JvmStatic val speechSourceUrl: String get() = DEFAULT_SPEECH_MODEL_URL

        // ── Accurate offline ASR: Whisper Small.en via sherpa-onnx ──
        // 244M params, multilingual training → best practical accuracy on
        // Indian accents + names. ~350 MB int8, still ~half of Parakeet.
        // Vosk stays for wake-word. Files from HuggingFace (resumable each).
        const val SHERPA_HF_BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small.en/resolve/main"
        const val SHERPA_ENCODER_FILE = "small.en-encoder.int8.onnx"
        const val SHERPA_DECODER_FILE = "small.en-decoder.int8.onnx"
        const val SHERPA_TOKENS_FILE = "small.en-tokens.txt"
        const val SHERPA_MODEL_BYTES = 400_000_000L   // ~400 MB total
        const val SHERPA_MODEL_DIR = "sherpa-whisper-small.en"
        const val SHERPA_VAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"

        @JvmStatic val sherpaSourceUrl: String get() = "$SHERPA_HF_BASE/$SHERPA_ENCODER_FILE"

        // ── Keyword spotter (Zipformer transducer, ~10 MB) ──────────────
        // True wake-word spotting ("hey adam") without full speech decoding.
        const val KWS_TARBALL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"
        const val KWS_MODEL_DIR = "sherpa-kws"
        const val KWS_ENCODER_FILE = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        const val KWS_DECODER_FILE = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
        const val KWS_JOINER_FILE = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        const val KWS_TOKENS_FILE = "tokens.txt"
        const val KWS_KEYWORDS_FILE = "keywords.txt"
        const val KWS_TARBALL_BYTES = 15_000_000L

        // ── On-device LLM brain (Gemma3-1B int4 .task, ~750 MB) ──────────
        // Served by MediaPipe LLM Inference (tasks-genai, no native build).
        // Ungated LiteRT-community build so the app can fetch it directly.
        const val LLM_TASK_MODEL_ID = "litert-community/Gemma3-1B-IT"
        const val LLM_TASK_FILE = "gemma3-1b-it-int4.task"
        const val LLM_TASK_URL =
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task"
        const val LLM_TASK_BYTES = 800_000_000L
        const val LLM_TASK_DIR = "llm"

        // ── Silero VAD (lives inside the Sherpa model dir) ───────────────
        const val VAD_FILE = "silero_vad.onnx"

        private val SKIP_SUFFIXES = listOf(".md", ".gitattributes", ".DS_Store")

        @Volatile private var instance: ModelDownloader? = null

        fun get(context: Context): ModelDownloader =
            instance ?: synchronized(this) {
                instance ?: ModelDownloader(context.applicationContext).also { instance = it }
            }
    }

    enum class Kind { LLM, SPEECH, ASR, KWS }

    data class Progress(
        val state: State = State.IDLE,
        val kind: Kind = Kind.LLM,
        val totalBytes: Long = 0L,
        val downloadedBytes: Long = 0L,
        val currentFile: String = "",
        val fileIndex: Int = 0,
        val fileCount: Int = 0,
        val error: String? = null
    ) {
        val fraction: Float
            get() = if (totalBytes <= 0L) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    enum class State { IDLE, LISTING, DOWNLOADING, VERIFYING, EXTRACTING, COMPLETE, CANCELED, ERROR }

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    val sourceUrl: String get() = "$HF_BASE/$DEFAULT_MODEL_ID"

    @Volatile private var canceled = false
    fun cancel() { canceled = true }

    // ── LLM (Hugging Face) ───────────────────────────────────────────────────

    suspend fun ensureModel(modelId: String = DEFAULT_MODEL_ID): File? = withContext(Dispatchers.IO) {
        canceled = false
        val modelDir = File(context.getExternalFilesDir("models"), modelId.substringAfterLast('/'))
        val configFile = File(modelDir, "mlc-chat-config.json")

        if (configFile.exists() && configFile.length() > 0L) {
            _progress.value = Progress(state = State.COMPLETE, kind = Kind.LLM)
            return@withContext modelDir
        }

        if (!hasNetwork()) return@withContext fail(Kind.LLM, "No internet connection.")

        try {
            _progress.value = Progress(state = State.LISTING, kind = Kind.LLM)
            val files = listModelFiles(modelId)
            if (files.isEmpty()) return@withContext fail(Kind.LLM, "Model has no files on Hugging Face.")

            val totalBytes = computeTotalSizes(modelId, files)
            _progress.value = Progress(
                state = State.DOWNLOADING,
                kind = Kind.LLM,
                totalBytes = totalBytes,
                downloadedBytes = existingBytes(modelDir, files),
                fileCount = files.size
            )

            for ((index, filePath) in files.withIndex()) {
                if (canceled) return@withContext cancel(Kind.LLM)
                val target = File(modelDir, filePath)
                if (target.exists() && target.length() > 0L) {
                    _progress.value = _progress.value.copy(
                        currentFile = filePath,
                        fileIndex = index + 1
                    )
                    continue
                }
                downloadOneFile(modelId, filePath, target) { delta ->
                    _progress.value = _progress.value.copy(
                        downloadedBytes = _progress.value.downloadedBytes + delta,
                        currentFile = filePath,
                        fileIndex = index + 1
                    )
                }
            }

            _progress.value = _progress.value.copy(state = State.VERIFYING)
            if (!File(modelDir, "mlc-chat-config.json").exists()) {
                throw IOException("mlc-chat-config.json missing after download")
            }

            _progress.value = _progress.value.copy(state = State.COMPLETE)
            modelDir
        } catch (e: Exception) {
            fail(Kind.LLM, e.message ?: e.javaClass.simpleName)
        }
    }

    // ── Speech model (alphacephei.com zip) ───────────────────────────────────

    suspend fun ensureSpeechModel(
        url: String = DEFAULT_SPEECH_MODEL_URL,
        totalBytes: Long = DEFAULT_SPEECH_MODEL_BYTES
    ): File? = withContext(Dispatchers.IO) {
        canceled = false
        val modelDir = File(context.getExternalFilesDir(null), SPEECH_MODEL_DIR)
        val marker = File(modelDir, SPEECH_MARKER)

        // One-time migration from older models: don't leave ~40-70 MB
        // of stale files behind when we switch models.
        val legacyDirs = listOf("vosk-model", "vosk-model-en-in")
        for (legacyName in legacyDirs) {
            val legacy = File(context.getExternalFilesDir(null), legacyName)
            if (!marker.exists() && legacy.exists() &&
                legacy.canonicalPath != modelDir.canonicalPath) {
                try { legacy.deleteRecursively() } catch (_: Exception) { }
            }
        }

        if (marker.exists() && marker.length() > 0L) {
            _progress.value = Progress(state = State.COMPLETE, kind = Kind.SPEECH)
            return@withContext modelDir
        }

        if (!hasNetwork()) return@withContext fail(Kind.SPEECH, "No internet connection.")

        try {
            modelDir.deleteRecursively()
            modelDir.mkdirs()

            _progress.value = Progress(
                state = State.DOWNLOADING,
                kind = Kind.SPEECH,
                totalBytes = totalBytes,
                downloadedBytes = 0L,
                currentFile = url.substringAfterLast('/')
            )

            val zipFile = File(modelDir.parentFile, "vosk-model.zip.tmp")
            downloadZip(url, zipFile) { delta ->
                _progress.value = _progress.value.copy(
                    downloadedBytes = _progress.value.downloadedBytes + delta
                )
            }
            if (canceled) {
                zipFile.delete()
                return@withContext cancel(Kind.SPEECH)
            }

            _progress.value = _progress.value.copy(
                state = State.EXTRACTING,
                currentFile = "Extracting…"
            )
            unzipStripTopFolder(zipFile, modelDir)
            zipFile.delete()

            if (!File(modelDir, SPEECH_MARKER).exists()) {
                throw IOException("Extracted archive is missing $SPEECH_MARKER")
            }

            _progress.value = _progress.value.copy(state = State.COMPLETE)
            modelDir
        } catch (e: Exception) {
            fail(Kind.SPEECH, e.message ?: e.javaClass.simpleName)
        }
    }

    // ── Accurate ASR model (Whisper Small.en int8 + Silero VAD) ─────────────
    //
    // Three files from HuggingFace, each resumable. Idempotent via markers.
    // Migrates away the old Parakeet dir (~630 MB) when present.

    suspend fun ensureSherpaModel(): File? = withContext(Dispatchers.IO) {
        canceled = false
        val modelDir = File(context.getExternalFilesDir(null), SHERPA_MODEL_DIR)

        // One-time migration from Parakeet (~630 MB) to Whisper Small.
        val legacyParakeet = File(context.getExternalFilesDir(null), "sherpa-parakeet-0.6b-int8")
        if (legacyParakeet.exists() && legacyParakeet.canonicalPath != modelDir.canonicalPath) {
            try { legacyParakeet.deleteRecursively() } catch (_: Exception) { }
        }
        try {
            File(modelDir.parentFile, "sherpa-parakeet.tar.bz2.tmp").delete()
        } catch (_: Exception) { }

        if (sherpaFilesValid(modelDir)) {
            ensureSherpaVad(modelDir)
            _progress.value = Progress(state = State.COMPLETE, kind = Kind.ASR)
            return@withContext modelDir
        }
        // Invalid state (missing or truncated files): wipe so the download
        // below fetches fresh copies instead of handing corrupt files to the
        // native recognizer, which LOG(FATAL)-aborts on unreadable models.
        try { modelDir.deleteRecursively() } catch (_: Exception) { }

        if (!hasNetwork()) return@withContext fail(Kind.ASR, "No internet connection.")

        try {
            modelDir.mkdirs()
            val files = listOf(SHERPA_ENCODER_FILE, SHERPA_DECODER_FILE, SHERPA_TOKENS_FILE)
            _progress.value = Progress(
                state = State.DOWNLOADING,
                kind = Kind.ASR,
                totalBytes = SHERPA_MODEL_BYTES,
                downloadedBytes = 0L,
                fileCount = files.size,
                currentFile = files.first()
            )
            var done = 0L
            for ((index, name) in files.withIndex()) {
                if (canceled) return@withContext cancel(Kind.ASR)
                _progress.value = _progress.value.copy(
                    currentFile = name,
                    fileIndex = index + 1
                )
                downloadUrlToFile("$SHERPA_HF_BASE/$name", File(modelDir, name)) { delta ->
                    done += delta
                    _progress.value = _progress.value.copy(downloadedBytes = done)
                }
            }
            if (canceled) return@withContext cancel(Kind.ASR)
            ensureSherpaVad(modelDir)

            _progress.value = _progress.value.copy(state = State.COMPLETE)
            modelDir
        } catch (e: Exception) {
            fail(Kind.ASR, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Local Sherpa files are trusted only if every file exists, is non-empty,
     * and (when online) byte-matches the server's Content-Length. A truncated
     * download must never reach the native recognizer: sherpa-onnx
     * LOG(FATAL)-aborts the whole process on unreadable models, which is
     * uncatchable from Kotlin. HEAD failures / unknown lengths fall back to
     * trusting the local files rather than forcing a re-download.
     */
    private fun sherpaFilesValid(dir: File): Boolean {
        val names = listOf(SHERPA_ENCODER_FILE, SHERPA_DECODER_FILE, SHERPA_TOKENS_FILE)
        for (name in names) {
            val f = File(dir, name)
            if (!f.exists() || f.length() <= 0L) return false
        }
        if (!hasNetwork()) return true
        for (name in names) {
            val expected = headSize("$SHERPA_HF_BASE/$name")
            val local = File(dir, name).length()
            if (expected > 0L && local != expected) {
                android.util.Log.w("Adam", "Sherpa $name size mismatch (local=$local server=$expected), re-downloading")
                return false
            }
        }
        return true
    }

    /** Resumable single-file GET (Range + .partial), for HuggingFace blobs. */
    private fun downloadUrlToFile(url: String, target: File, onChunk: (Int) -> Unit) {
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".partial")
        val existing = if (partial.exists()) partial.length() else 0L

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
            doInput = true
        }
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            conn.disconnect()
            throw IOException("HTTP $code for ${target.name}")
        }
        FileOutputStream(partial, code == HttpURLConnection.HTTP_PARTIAL).use { out ->
            conn.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (!canceled) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    onChunk(read)
                }
            }
        }
        conn.disconnect()
        if (canceled) return
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
    }

    private fun ensureSherpaVad(modelDir: File) {
        // Best-effort endpointing nicety — the energy gate covers for it.
        try {
            val vad = File(modelDir, VAD_FILE)
            if (vad.exists() && vad.length() > 0L) return
            downloadFileSimple(SHERPA_VAD_URL, vad)
        } catch (_: Exception) {
        }
    }

    /**
     * Public VAD fetch for [com.adam.assistant.stt.VadGate]: ensures the
     * Silero model exists and returns it, or null when offline/failing.
     */
    suspend fun ensureVadModel(): File? = withContext(Dispatchers.IO) {
        val modelDir = File(context.getExternalFilesDir(null), SHERPA_MODEL_DIR)
        val vad = File(modelDir, VAD_FILE)
        if (vad.exists() && vad.length() > 0L) return@withContext vad
        if (!hasNetwork()) return@withContext null
        return@withContext try {
            modelDir.mkdirs()
            downloadFileSimple(SHERPA_VAD_URL, vad)
            if (vad.exists() && vad.length() > 0L) vad else null
        } catch (_: Exception) {
            null
        }
    }

    // ── Keyword-spotter model (release tarball) ──────────────────────────

    suspend fun ensureKwsModel(): File? = withContext(Dispatchers.IO) {
        canceled = false
        val modelDir = File(context.getExternalFilesDir(null), KWS_MODEL_DIR)

        if (File(modelDir, KWS_TOKENS_FILE).let { it.exists() && it.length() > 0L } &&
            File(modelDir, KWS_ENCODER_FILE).let { it.exists() && it.length() > 0L }
        ) {
            _progress.value = Progress(state = State.COMPLETE, kind = Kind.KWS)
            return@withContext modelDir
        }

        if (!hasNetwork()) return@withContext fail(Kind.KWS, "No internet connection.")

        try {
            modelDir.deleteRecursively()
            modelDir.mkdirs()

            _progress.value = Progress(
                state = State.DOWNLOADING,
                kind = Kind.KWS,
                totalBytes = KWS_TARBALL_BYTES,
                downloadedBytes = 0L,
                currentFile = KWS_TARBALL_URL.substringAfterLast('/')
            )

            val archive = File(modelDir.parentFile, "sherpa-kws.tar.bz2.tmp")
            downloadToFile(KWS_TARBALL_URL, archive) { delta ->
                _progress.value = _progress.value.copy(
                    downloadedBytes = _progress.value.downloadedBytes + delta
                )
            }
            if (canceled) {
                archive.delete()
                return@withContext cancel(Kind.KWS)
            }

            _progress.value = _progress.value.copy(
                state = State.EXTRACTING,
                currentFile = "Extracting…"
            )
            extractTarBz2(archive, modelDir)
            archive.delete()

            if (!File(modelDir, KWS_TOKENS_FILE).exists() ||
                !File(modelDir, KWS_ENCODER_FILE).exists()
            ) {
                throw IOException("KWS archive is missing model files")
            }

            _progress.value = _progress.value.copy(state = State.COMPLETE)
            modelDir
        } catch (e: Exception) {
            fail(Kind.KWS, e.message ?: e.javaClass.simpleName)
        }
    }

    // ── LLM brain model (single .task file) ──────────────────────────────

    suspend fun ensureLlmTask(
        url: String = LLM_TASK_URL,
        file: String = LLM_TASK_FILE,
        totalBytes: Long = LLM_TASK_BYTES
    ): File? = withContext(Dispatchers.IO) {
        canceled = false
        val modelDir = File(context.getExternalFilesDir(null), LLM_TASK_DIR)
        val target = File(modelDir, file)

        if (target.exists() && target.length() > 0L) {
            // Byte-match against the server when online: a truncated .task
            // would fail inside MediaPipe with an opaque native error.
            if (hasNetwork()) {
                val expected = headSize(url)
                if (expected > 0L && target.length() != expected) {
                    android.util.Log.w(
                        "Adam",
                        "LLM task size mismatch (local=${target.length()} server=$expected), re-downloading"
                    )
                    try { target.delete() } catch (_: Exception) { }
                } else {
                    _progress.value = Progress(state = State.COMPLETE, kind = Kind.LLM)
                    return@withContext target
                }
            } else {
                _progress.value = Progress(state = State.COMPLETE, kind = Kind.LLM)
                return@withContext target
            }
        }

        if (!hasNetwork()) return@withContext fail(Kind.LLM, "No internet connection.")

        try {
            modelDir.mkdirs()
            _progress.value = Progress(
                state = State.DOWNLOADING,
                kind = Kind.LLM,
                totalBytes = totalBytes,
                downloadedBytes = 0L,
                fileCount = 1,
                fileIndex = 1,
                currentFile = file
            )
            // Resumable via .partial (same helper as the ASR blobs).
            downloadUrlToFile(url, target) { delta ->
                _progress.value = _progress.value.copy(
                    downloadedBytes = _progress.value.downloadedBytes + delta
                )
            }
            if (canceled) return@withContext cancel(Kind.LLM)

            _progress.value = _progress.value.copy(state = State.COMPLETE)
            target
        } catch (e: Exception) {
            fail(Kind.LLM, e.message ?: e.javaClass.simpleName)
        }
    }

    fun freeSpaceBytes(): Long = context.getExternalFilesDir(null)?.usableSpace ?: 0L

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun fail(kind: Kind, msg: String): File? {
        _progress.value = Progress(state = State.ERROR, kind = kind, error = msg)
        return null
    }

    private fun cancel(kind: Kind): File? {
        _progress.value = _progress.value.copy(state = State.CANCELED, kind = kind)
        return null
    }

    private fun hasNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun listModelFiles(modelId: String): List<String> {
        val apiUrl = URL("$HF_BASE/api/models/$modelId")
        val conn = (apiUrl.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)
        val siblings = json.optJSONArray("siblings") ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until siblings.length()) {
            val name = siblings.getJSONObject(i).optString("rfilename")
            if (name.isBlank()) continue
            if (SKIP_SUFFIXES.any { name.endsWith(it) }) continue
            if (name.startsWith(".") || name.contains("/.")) continue
            out += name
        }
        return out.sorted()
    }

    private fun computeTotalSizes(modelId: String, files: List<String>): Long {
        var total = 0L
        for (f in files) {
            total += headSize("$HF_BASE/$modelId/resolve/main/$f")
        }
        return total
    }

    private fun headSize(urlStr: String): Long = try {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        val size = conn.contentLengthLong
        conn.disconnect()
        if (size > 0) size else 0L
    } catch (e: Exception) { 0L }

    private fun existingBytes(modelDir: File, files: List<String>): Long {
        var sum = 0L
        for (f in files) {
            val file = File(modelDir, f)
            if (file.exists()) sum += file.length()
        }
        return sum
    }

    private fun downloadOneFile(
        modelId: String,
        filePath: String,
        target: File,
        onChunk: (Int) -> Unit
    ) {
        target.parentFile?.mkdirs()
        val downloadUrl = "$HF_BASE/$modelId/resolve/main/$filePath"
        val partial = File(target.parentFile, target.name + ".partial")
        val existing = if (partial.exists()) partial.length() else 0L

        val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
            doInput = true
        }
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            conn.disconnect()
            throw IOException("HTTP $code for $filePath")
        }

        FileOutputStream(partial, /* append = */ code == HttpURLConnection.HTTP_PARTIAL).use { out ->
            conn.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (!canceled) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    onChunk(read)
                }
            }
        }
        conn.disconnect()
        if (canceled) return

        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
    }

    private fun downloadZip(url: String, target: File, onChunk: (Int) -> Unit) =
        downloadToFile(url, target, onChunk)

    /** Generic resumable GET (Range + append) for zips, tarballs, etc. */
    private fun downloadToFile(url: String, target: File, onChunk: (Int) -> Unit) {
        target.parentFile?.mkdirs()
        // Resumable: server must honour Range (GitHub + alphacephei do).
        // Progress resumes from existing bytes so a 600 MB retry doesn't restart.
        val existing = if (target.exists()) target.length() else 0L
        if (existing > 0L) {
            try {
                _progress.value = _progress.value.copy(downloadedBytes = existing)
            } catch (_: Exception) { }
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
        }
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            conn.disconnect()
            throw IOException("HTTP $code")
        }
        val append = code == HttpURLConnection.HTTP_PARTIAL && existing > 0L
        FileOutputStream(target, append).use { out ->
            conn.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (!canceled) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    onChunk(read)
                }
            }
        }
        conn.disconnect()
    }

    private fun downloadFileSimple(url: String, target: File) {
        target.parentFile?.mkdirs()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
        }
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            throw IOException("HTTP ${conn.responseCode} for $url")
        }
        target.outputStream().use { out ->
            conn.inputStream.use { it.copyTo(out) }
        }
        conn.disconnect()
    }

    /** Extracts a .tar.bz2 archive, stripping the top-level folder. */
    private fun extractTarBz2(archive: File, target: File) {
        val targetCanonical = target.canonicalPath
        java.io.FileInputStream(archive).use { fis ->
            org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(fis).use { bz ->
                org.apache.commons.compress.archivers.tar.TarArchiveInputStream(bz).use { tis ->
                    var entry = tis.nextEntry
                    while (entry != null) {
                        // Archives contain a top folder (sherpa-onnx-.../encoder...):
                        // strip it so model files land directly in target.
                        val stripped = entry.name.substringAfter('/')
                        if (stripped.isNotEmpty()) {
                            val outFile = File(target, stripped)
                            val outCanonical = outFile.canonicalPath
                            if (!outCanonical.startsWith(targetCanonical)) {
                                throw IOException("Tar slip attempt: ${entry.name}")
                            }
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { os -> tis.copyTo(os) }
                            }
                        }
                        entry = tis.nextEntry
                    }
                }
            }
        }
    }

    private fun unzipStripTopFolder(zip: File, target: File) {
        val targetCanonical = target.canonicalPath
        ZipInputStream(FileInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val stripped = entry.name.substringAfter('/')
                if (stripped.isNotEmpty()) {
                    val outFile = File(target, stripped)
                    val outCanonical = outFile.canonicalPath
                    if (!outCanonical.startsWith(targetCanonical)) {
                        throw IOException("Zip slip attempt: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { os -> zis.copyTo(os) }
                    }
                }
                entry = zis.nextEntry
            }
        }
    }
}
