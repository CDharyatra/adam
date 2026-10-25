# Adam — Fully Local Android Assistant

A privacy-first, offline Android assistant. No cloud. No subscriptions. No data ever leaves your device.

---

## Architecture

```
   ┌────────────────────────────────────────────────────────────────┐
   │  WAKEWORD LISTENING  (Vosk keyword mode — low CPU, background) │
   └─────────────────────────────┬──────────────────────────────────┘
                                 │  "hey adam" detected
                                 ▼   (chime → CONVERSATION mode)
   ┌────────────────────────────────────────────────────────────────┐
   │  CONVERSATION LISTENING  (Vosk full STT, partials → final)     │
   │      → user finishes speaking                                  │
   │      → chime  ("working on it")                                │
   └─────────────────────────────┬──────────────────────────────────┘
                                 ▼
   ┌────────────────────────────────────────────────────────────────┐
   │  CONVERSATION PROCESSING                                        │
   │      [LlmEngine]  ← MLC LLM via JNI (on-device, Qwen2.5-3B)    │
   │          falls back to regex parser if model absent           │
   │      [CommandExecutor]                                         │
   │          ├── openApp()        → PackageManager intent          │
   │          ├── playMusic()      → AccessibilityService → Spotify│
   │          ├── sendMessage()    → WhatsApp deep-link / a11y      │
   │          ├── makeCall / URL / search / alarm / timer /         │
   │          │   calendar / email / photo / navigate / settings /  │
   │          │   volume / read-screen / long-click / notifications │
   │          │   swipe / calculate / note                          │
   │          └── scroll/click/type → AccessibilityService polling  │
   └─────────────────────────────┬──────────────────────────────────┘
                                 ▼
   ┌────────────────────────────────────────────────────────────────┐
   │  CONVERSATION SPEAKING  ([TtsEngine] with barge-in monitor)    │
   │      ↳ if user speaks during response → stop TTS, back to listen│
   └─────────────────────────────┬──────────────────────────────────┘
                                 │  TTS done
                                 ▼  loop back to CONVERSATION LISTENING
                            (or return to WAKEWORD LISTENING
                             after 30 s of silence, or on
                             "goodbye" / "stop listening")

### Key implementation notes
- The floating mic button communicates with `AssistantService` via a registered `BroadcastReceiver` (not just `onStartCommand`)
- UI automation uses **node polling** (`waitForNode`) instead of fixed `delay()` calls — adapts to actual device speed
- MLC LLM is wired via a real JNI bridge (`mlc_llm_jni.cpp`) using `MLCChat::Reload / Generate / ResetChat`; the bridge compiles cleanly with or without the AAR present
- WhatsApp messaging prefers the `wa.me` deep-link when a phone number is available, falling back to Accessibility UI automation
- **Wake word** uses Vosk's built-in keyword spotting (`Recognizer.setKeywords`) — much cheaper than full STT, safe to leave running in the foreground service
- **Barge-in** during TTS uses a second `AudioRecord` monitor that watches mic energy; 3 consecutive frames above threshold cancel the speech coroutine
- **Multi-turn conversation** loops automatically: after each response the service returns to `CONVERSATION_LISTENING` (no button press) until 30 s of silence or an explicit exit phrase ("goodbye", "stop listening", …)

---

## What you need before building

| Tool | Version | Download |
|------|---------|----------|
| Android Studio | Hedgehog (2023.1) or newer | https://developer.android.com/studio |
| Android SDK | API 34 | installed via Android Studio SDK Manager |
| Android NDK | r25c or newer | installed via Android Studio SDK Manager |
| CMake | 3.22.1 | installed via Android Studio SDK Manager |
| JDK | 17 | bundled with Android Studio |
| ADB | any | bundled with Android Studio platform-tools |
| Python | 3.9+ (optional, for MLC model download) | https://python.org |

---

## Part 1 — Build the APK

### Step 1 — Open the project

1. Launch Android Studio
2. Choose **File → Open**
3. Select the `project adam` folder
4. Wait for Gradle sync to finish (first sync downloads ~500 MB of dependencies)

### Step 2 — (Optional) Add the MLC LLM AAR for full AI mode

> Skip this if you just want to test with the regex fallback first.

1. Go to https://github.com/mlc-ai/mlc-llm/releases
2. Download `mlc4j-release.aar` from the latest release
3. Create the folder `app/libs/` inside the project if it doesn't exist
4. Copy `mlc4j-release.aar` into `app/libs/`
5. Extract the AAR's native libraries so CMake can find them:
   ```
   cd app/libs
   mkdir mlc_llm
   cd mlc_llm
   unzip ../mlc4j-release.aar
   # You should now have:  jni/arm64-v8a/libmlc_llm.so
   #                       include/mlc_llm/llm_chat.h
   ```
6. Re-sync Gradle — CMake will auto-detect the `.so` and enable `MLC_LLM_AVAILABLE`

### Step 3 — Build

- **Debug APK** (easiest): **Build → Build Bundle(s) / APK(s) → Build APK(s)**
  Output: `app/build/outputs/apk/debug/app-debug.apk`

- **Release APK**: **Build → Generate Signed Bundle / APK** (requires a keystore)

---

## Part 2 — Install on your phone

### Step 1 — Enable Developer Options on your phone

1. Open **Settings → About phone**
2. Tap **Build number** 7 times until you see "You are now a developer"
3. Go back to **Settings → System → Developer options**
4. Enable **USB debugging**

### Step 2 — Connect and install via ADB

Connect your phone to your PC with a USB cable, then in a terminal:

```bash
# Verify your phone is detected
adb devices
# Should show something like:  R5CT21XXXXX   device

# Install the APK
adb install app/build/outputs/apk/debug/app-debug.apk
```

If `adb` is not on your PATH, use the full path:
- Windows: `C:\Users\<you>\AppData\Local\Android\Sdk\platform-tools\adb.exe`

Alternatively, copy the APK to your phone and open it in a file manager — you'll need to allow **Install unknown apps** for that file manager in Settings.

---

## Part 3 — Model files

**Both models are auto-downloaded on the first time you tap "Start Adam".**
No `adb push`, no manual setup beyond pressing one button. Downloads happen
sequentially:

1. **Speech model** (`vosk-model-small-en-in-0.4`, ~36 MB, Indian English) — fetched from
   `alphacephei.com`, downloaded as a zip and unzipped on device.
2. **AI model** (`mlc-ai/Qwen2.5-3B-Instruct-q4f16_1-MLC`, ~1.8 GB) — fetched
   from `huggingface.co` as a multi-file directory.

Progress (percent, MB counter, current file) is shown in:
- the main app screen,
- the foreground-service notification.

Make sure your phone has at least **2.5 GB of free storage** before first launch.

### Source URLs (displayed in the UI)

- Speech: `https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip`
- AI:     `https://huggingface.co/mlc-ai/Qwen2.5-3B-Instruct-q4f16_1-MLC`

### Switching models

- Change `ModelDownloader.DEFAULT_MODEL_ID` in `ModelDownloader.kt` to use a
  different MLC-LLM model (see alternatives below).
- Change `ModelDownloader.DEFAULT_SPEECH_MODEL_URL` to use a different Vosk model.
  Any model from https://alphacephei.com/vosk/models that exposes a `am/final.mdl`
  inside will work.

### Alternative AI models

| Model | Size | RAM | Notes |
|-------|------|-----|-------|
| `mlc-ai/Qwen2.5-3B-Instruct-q4f16_1-MLC` | ~1.8 GB | ~3 GB | **Default** — Apache 2.0, best balance |
| `mlc-ai/Llama-3.2-1B-Instruct-q4f16_1-MLC` | ~700 MB | ~2 GB | Llama 3.2 Community license |
| `mlc-ai/Llama-3.2-3B-Instruct-q4f16_1-MLC` | ~2.0 GB | ~3 GB | Llama 3.2 Community license |
| `mlc-ai/gemma-2-2b-it-q4f16_1-MLC` | ~1.3 GB | ~3 GB | Gemma license |
| `mlc-ai/Phi-3.5-mini-instruct-q4f16_1-MLC` | ~2.3 GB | ~4 GB | MIT |

> **No model → Adam falls back to its built-in regex parser.** All documented
> commands still work; only free-form / ambiguous commands may misclassify.

### Manual install (offline / firewall'd devices)

If the device cannot reach `huggingface.co` / `alphacephei.com`, you can still
sideload the model folders via ADB — Adam will detect the marker files
(`am/final.mdl` for speech, `mlc-chat-config.json` for AI) and skip the download:

```bash
adb push your-unzipped-vosk-model /sdcard/Android/data/com.adam.assistant/files/vosk-model-en-in
adb push your-mlc-model-dir /sdcard/Android/data/com.adam.assistant/files/models/Qwen2.5-3B-Instruct-q4f16_1-MLC
```

---

## Part 4 — First-time setup on the phone

Do these steps in order the first time you open Adam.

> **Permissions Adam needs (3 total):**
> - **Microphone** — Android shows a standard runtime dialog.
> - **Accessibility Service** — *special* permission, NO runtime dialog. Adam shows a
>   step-by-step guide, then opens Settings where you must flip a toggle.
> - **Display over other apps** — *special* permission, NO runtime dialog. Same pattern.
>
> If you're unsure what any of them do, tap **How to enable these permissions** on
> the main screen.

### 1. Grant Accessibility Service permission

1. Open the **Adam** app
2. Tap **Enable Accessibility Service**
3. Read the in-app guide dialog, then tap **Continue**
4. In the system settings screen, find **Adam Assistant** in the list
   (use the search icon at the top if it's not visible)
5. Tap it → toggle **Use Adam Assistant** ON
6. Tap **Allow** on the confirmation dialog
7. Press back to return to Adam — the status line will show ✓ Enabled

### 2. Grant Overlay permission

1. In the Adam app, tap **Enable Overlay Permission**
2. Read the in-app guide dialog, then tap **Continue**
3. On the next screen, find **Adam** in the list and toggle **Allow display over other apps** ON
4. Press back to return to Adam — the status line will show ✓ Enabled

### 3. Grant Microphone permission

This is requested automatically when you tap **Start Adam** for the first time.
Tap **Allow** on the system dialog.

If you tapped **Deny** by accident:
- Tap **Allow** on the next in-app rationale dialog (shown automatically the next
  time you press Start).
- If you previously selected **Don't ask again**, the in-app dialog will link you
  to the system app-info page where you can re-enable it manually.

### 4. Start the assistant

1. Tap **Start Adam** — the button label changes to **Stop Adam**
2. **First-launch only:** Adam downloads the on-device AI model (~1.8 GB).
   Progress is shown in the app and in the foreground notification.
3. When the download finishes, Adam enters **wake-word listening** mode by default
   (the toggle "Wake word \"Hey Adam\"" is on).

### 5. Use Adam like Siri

**Option A — Wake word (recommended, background):**
- Just say **"Hey Adam"** from anywhere, any app
- You'll hear an ascending two-tone chime
- Now speak your command: *"play Bohemian Rhapsody"* / *"set timer for 5 minutes"* / …
- When you stop talking, you'll hear a short beep — Adam is *"working on it"*
- Adam speaks the result
- **Keep talking!** Adam immediately goes back to listening. No button presses
- Say *"goodbye"* / *"stop listening"* / *"stop Adam"* to end the conversation and
  return to wake-word mode

**Option B — Manual mic button:**
- Tap the floating mic button (or the mic icon in the app)
- Speak one command, get one response
- Useful when you don't want the wake word listening (battery / privacy)

**Barge-in:**
- If Adam is speaking and you want to interrupt, just start talking
- Adam stops mid-response and immediately starts listening for your next command
- A soft "blip" confirms the interruption

**Change of mind mid-sentence:**
- Vosk waits for silence before finalising the transcript
- If you start a sentence, change direction ("…actually, no, play Stairway instead"),
  Adam hears the final version and acts on it

---

## Supported Commands

The on-device LLM can execute any of the following — nothing is sent to the cloud.

### App & UI control
| Voice Command | What happens |
|--------------|-------------|
| "Open Spotify" / "Launch Chrome" | Launches the named app |
| "Click Settings" | Taps any on-screen element labelled "Settings" |
| "Long press the photo" | Long-presses the named element |
| "Type hello world" | Types into the focused text field |
| "Scroll down" / "Scroll up" | Scrolls the screen |
| "Swipe down" / "Swipe left" | Performs a swipe gesture |
| "Go back" / "Go home" / "Recent apps" | Global actions |
| "Open notifications" / "Close notifications" | Notification shade |
| "Read my screen" | Speaks every visible text on screen |

### Communication
| Voice Command | What happens |
|--------------|-------------|
| "Play Bohemian Rhapsody by Queen on Spotify" | Opens Spotify, searches, plays the song |
| "Text John on WhatsApp: I'll be there in 5" | Opens WhatsApp chat with John and sends |
| "Call John" / "Dial 555-1234" | Opens the dialer |
| "Email john@example.com subject Hi body Hello" | Composes an email |
| "Open youtube.com" / "Open https://…" | Opens a URL in the browser |
| "Search for best pizza recipes" | Web search |

### Productivity
| Voice Command | What happens |
|--------------|-------------|
| "Set an alarm for 7 30 AM" | Opens the alarm app with the time preset |
| "Set a timer for 5 minutes" | Opens the timer app |
| "Add calendar event Team meeting tomorrow" | Opens the calendar at a new event |
| "Take a photo" | Opens the camera |
| "Navigate to the Eiffel Tower" | Opens maps with directions |
| "Note: buy groceries" | Appends a timestamped note to `files/notes/notes.txt` |
| "What is 12 times 7" / "Calculate (5+3)*2" | On-device arithmetic |

### Device
| Voice Command | What happens |
|--------------|-------------|
| "Open Wi-Fi settings" / "Bluetooth" / "Battery" / … | Opens the matching Settings page |
| "Volume up" / "Volume down" / "Mute" | Adjusts media stream volume |

---

## Permissions explained

| Permission | How it's requested | Why it's needed |
|-----------|-------------------|----------------|
| `RECORD_AUDIO` | System runtime dialog on first Start (in-app rationale on second+ denial; "Don't ask again" path opens app-info) | Microphone access for Vosk STT |
| `SYSTEM_ALERT_WINDOW` | In-app step-by-step guide → Settings toggle | Draw the floating mic button over other apps |
| `FOREGROUND_SERVICE` | Auto-granted at install | Keep the assistant running while you use other apps |
| `FOREGROUND_SERVICE_MICROPHONE` | Auto-granted at install | Required on Android 12+ for mic in foreground services |
| `QUERY_ALL_PACKAGES` | Auto-granted at install | Look up any installed app by name |
| `VIBRATE` | Auto-granted at install | Haptic feedback on mic tap |
| `INTERNET` | Auto-granted at install | **One-time only** — fetch the on-device AI model from Hugging Face |
| `ACCESS_NETWORK_STATE` | Auto-granted at install | Check connectivity before starting the model download |
| Accessibility Service | In-app step-by-step guide → Settings toggle | Read and interact with other apps' UI |

---

## Troubleshooting

**"Speech model not found" / "AI model" line shows ✗ Missing**
Adam hasn't downloaded the model yet. Tap **Start Adam** on a Wi-Fi connection with at least 2.5 GB free storage — both downloads start automatically in sequence (speech first, then AI) with a progress bar.

**Download fails / "No internet connection"**
- Verify the phone is online: open any browser and load a page.
- Adam uses `https://huggingface.co` and `https://alphacephei.com` — confirm both are reachable from the device.
- If you're behind a corporate firewall, allow both hosts (plus `cdn-lfs.huggingface.co`).
- As a manual workaround, push the model folders yourself:
  ```bash
  # Speech
  wget https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip
  unzip vosk-model-small-en-in-0.4.zip
  mv vosk-model-small-en-in-0.4 vosk-model-en-in
  adb push vosk-model-en-in /sdcard/Android/data/com.adam.assistant/files/vosk-model-en-in
  
  # AI
  git lfs install
  git clone https://huggingface.co/mlc-ai/Qwen2.5-3B-Instruct-q4f16_1-MLC
  adb push Qwen2.5-3B-Instruct-q4f16_1-MLC \
    /sdcard/Android/data/com.adam.assistant/files/models/
  ```
  Then restart the app — it will detect `am/final.mdl` and `mlc-chat-config.json` and skip the download.

**Mic button does nothing**
The Accessibility Service is not enabled. Go to Settings → Accessibility → Adam Assistant and toggle it on.

**"[App] is not installed"**
The app name wasn't recognised. Try the exact name shown on your home screen, e.g. "Open Google Maps" instead of "Open Maps".

**WhatsApp message goes to wrong contact**
WhatsApp's UI labels change between versions. Try using the exact name as it appears in your WhatsApp contact list.

**App crashes on launch**
Check that `minSdk 26` is met — Adam requires Android 8.0 or higher.

**LLM model not loading**
Confirm the model folder is directly inside `.../files/models/Qwen2.5-3B-Instruct-q4f16_1-MLC/` and contains `mlc-chat-config.json`. Check `adb logcat -s AdamLLM` for native error messages.

---

## Project Structure

```
app/
├── CMakeLists.txt                        # Native build — auto-detects MLC LLM AAR
├── libs/
│   ├── mlc4j-release.aar                 # MLC LLM AAR (add manually)
│   └── mlc_llm/                          # Extracted AAR contents (jni/ + include/)
└── src/main/
    ├── cpp/
    │   └── mlc_llm_jni.cpp               # JNI bridge (MLCChat Reload/Generate/Reset)
    ├── java/com/adam/assistant/
    │   ├── accessibility/
    │   │   └── AdamAccessibilityService.kt
    │   ├── core/
    │   │   ├── AssistantCommand.kt
    │   │   ├── AssistantService.kt        # Wake-word loop, conversation loop, barge-in
    │   │   ├── AudioCue.kt                # ToneGenerator-based chimes
    │   │   ├── ModelSetupChecker.kt
    │   │   └── Preferences.kt             # SharedPreferences wrapper
    │   ├── executor/
    │   │   └── CommandExecutor.kt         # Node polling, deep-link, dynamic pkg resolve
    │   ├── llm/
    │   │   ├── LlmEngine.kt
    │   │   └── ModelDownloader.kt         # First-run HF download, resumable
    │   ├── stt/
    │   │   └── SttEngine.kt               # Full STT + keyword spotting flows
    │   ├── tts/
    │   │   └── TtsEngine.kt
    │   └── ui/
    │       ├── MainActivity.kt
    │       └── OverlayService.kt
    └── res/
        ├── layout/activity_main.xml
        ├── layout/overlay_mic.xml
        └── xml/accessibility_service_config.xml
```

---

## Privacy

- All STT, LLM, and TTS run entirely on-device
- No analytics, no telemetry, no accounts, no crash reporting
- Accessibility Service is used solely to execute voice commands you initiate
- The only network access is the **one-time** download of the on-device AI model
  from `huggingface.co` (~1.8 GB). After that the app works fully offline.
  You can skip this download entirely by pushing the model folder via ADB
  (see Troubleshooting).
