# Adam — Handoff and Device-Test Guide

State as of 2026-11-19. Companion to `ai_implementation_tasks.pdf`.
Read the PDF for the exact grading language; this file records what is
actually implemented, what is verified, and what must be done on a phone.

---

## 0. TL;DR

| Task | Code/JVM state | What is left |
|---|---|---|
| 1 Build baseline | Done, verified | Release alignment/16 KB audit on a real release pipeline |
| 2 Coordinator + mock executor | Done, verified | — |
| 3 Typed input / PTT / readiness | Done, verified | Device UI/TalkBack/permission acceptance |
| 4 Deterministic routing | Done, verified | — |
| 5 Schemas / dialogs / consent | Done, verified | Adapter-side negative cases on device |
| 6 Bounded app adapters | Implemented, JVM-tested | Missing mock negative sequences, app-ambiguity UI, real-device launch/search/draft verification |
| 7 Cancellation / single-owner state | **Not started** | Entire task is runtime/device-bound |
| 8 Capture-only diagnostics / replay | **Not started** | Entire task is audio/device-bound |
| 9 Hash-verified provisioning / offline | Implemented, JVM-tested, offline flavor verified | Real manifests/digests, SAF import, per-job cancel, Range identity, on-device acceptance |
| 10 One mobile STT branch | **Not started** | Model selection + held-out phone evaluation |
| 11 Offline TTS / half-duplex | **Not started** | Entire task is audio/device-bound |
| 12 Lifecycle / resource limits | **Not started** | Entire task is device/API-bound |
| 13 Optional LLM proposal-only | Implemented (default OFF), JVM-tested | LiteRT-LM pin/migration, MediaPipe hardening, held-out evaluation |
| 14 Stage-separated tests / gates | **Not started** | Test assets + measured phone metrics |
| 15 VAD / wake | **Not started** | Only after 14 passes |

Work in the brief's dependency order: **6 → 7 → 8 → 9 → 10 → 11 → 12 → 13 → 14 → 15**.
Task 9's real-artifact work can run alongside 8, but do not skip 7/8.

Current verified state on the original machine:

- `:app:testOnlineDebugUnitTest` — **162 tests, 0 failures**
- `:app:lintOnlineDebug` and `:app:lintOfflineDebug` — **0 errors / 0 fatal**
- `:app:assembleDebug` and `:app:assembleRelease` — build all four variants
- Offline merged manifest has **no INTERNET / ACCESS_NETWORK_STATE**; online keeps them
- APKs: `app/build/outputs/apk/{online,offline}/{debug,release}/`

These are JVM/build results. They are **not** proof of Android device behavior.

---

## 1. What is already implemented

### Task 1 — reproducible build baseline
- AGP 8.5.1, Gradle 8.7, Kotlin 1.9.22, compile/target SDK 34, minSdk 26,
  NDK 25.1.8937393, Java/JVM target 11, CMake 3.22.1.
- MLC native scaffolding isolated and disabled; no MLC enablement.
- `local.properties` is intentionally gitignored.

### Task 2 — shared coordinator and mock executor
- `core/AssistantCoordinator.kt` owns parse → validate → dialog → consent →
  execute, independent of mic/TTS/download singletons.
- `submitText(text, source, mode)` is the single seam for typed and recognized
  text. Modes: `PARSE_ONLY`, `DRY_RUN`, `EXECUTE`.
- Typed `CandidatePlan` → `ValidatedPlan`; JSON exists only at model boundaries.
- `executor/ActionPort.kt` + `MockActionPort.kt` record operations without
  intents/clicks/messages; outcomes distinguish DraftOpened, SearchOpened,
  LaunchRequested, ObservedComplete, Failed, TimedOut, Cancelled, Unknown.
- Pure tests cover Spotify open, arithmetic, message draft, yes/no follow-ups,
  and malformed/timeout LLM output failing closed.

### Task 3 — typed input, visible PTT, capability readiness
- MainActivity exposes typed Parse/Preview/Execute, hold-to-talk and
  tap-start/tap-stop, transcript editing, Cancel current command, Stop speaking,
  Stop Adam; large labeled controls.
- Separate capability readiness from service existence; typed preview works with
  no models and denied permissions.
- New installs default to foreground PTT with LLM/wake OFF; existing choices
  migrate.
- Visible approval panel (`approvalPanel`, `btnApprove`, `btnReject`) exists.

### Task 4 — deterministic routing and payload preservation
- Immutable raw text is preserved; arguments extracted case-insensitively
  without lowercasing/rewriting names, bodies, or URLs.
- Specific settings/notifications/camera forms match before generic open;
  `open up` handled; `unmute` distinguished; negated/unsupported compounds
  abstain instead of matching a substring.
- Typed stage candidates; malformed/invalid model candidates abstain; vague
  music asks for clarification; template cutoff bounded; sensitive executable
  learning disabled; templates deletable.

### Task 5 — schemas, dialogs, immutable consent
- `llm/StrictJson.kt`: strict single-value JSON (rejects duplicate keys,
  prose, fences, trailing junk, wrong types, multiple objects).
- Action-specific schemas + allowlisted capability registry.
- Recipient-first flow: validate → resolve recipient → fill slots → exact
  preview → consent. Stable contact/phone IDs, multiple numbers, same-name
  ambiguity, Unicode-aware matching, explicit region for international numbers.
- Consent binds plan revision, app, operation, recipient variant, exact body,
  and expiry. Edits/mode change/error/cancel/stop invalidate it. `"yes but
  don't send"` is not assent. Executor cannot run without `ValidatedPlan` and
  consent.
- Model cannot invent package/phone/contact IDs (`reservedParamKeys`), and
  generic CLICK/TYPE/LONG_CLICK are not exposed to the LLM.
- Visible final approval control wired.

### Task 6 — bounded app adapters and no false success
- `core/LaunchableApps.kt`: user-facing registry with aliases, OEM names,
  `Found/Ambiguous/NotFound`, package resolution. `KnownApps` delegates to it.
- Gmail fixed to `com.google.android.gm`.
- Package-scoped WhatsApp/Spotify intents, missing-handler/`SecurityException`
  handling, deliberate fallbacks.
- `QUERY_ALL_PACKAGES` removed; scoped `<queries>` for MAIN/LAUNCHER, web,
  DIAL, mailto, geo, spotify, SET_ALARM, SET_TIMER, calendar INSERT,
  IMAGE_CAPTURE, TTS_SERVICE, and known packages.
- Spotify = open/search only; never auto-playback.
- WhatsApp = canonical-number prefilled draft; no auto-Send path.
- Calls use ACTION_DIAL; email compose; calendar draft.
- Alarm/timer require `SET_ALARM`; timer honors `params.seconds` and caps at
  86,400 s; no dispatch-only "created" claims.
- Calendar does not invent a start date; asks when missing.
- Accessibility service uses `ScreenQuery` with `ScreenItem` guards:
  nonblank, unique, visible, enabled, actionable; focused-only typing; no
  arbitrary editable-field fallback; ancestor clickable handling.
- Honest result strings; no "sent/delivered/playing/created" without evidence.

### Task 9 — explicit, hash-verified, independently offline provisioning
- `core/ProvisionSafety.kt`: archive containment via real descendants; rejects
  blank/absolute/traversal/symlink/special entries.
- `core/ProvisionBudget.kt`: download/extraction free-space gates in every
  `ensure*` path.
- `core/ProvisionHashes.kt`, `core/ProvisionVerifier.kt`,
  `llm/ModelRegistry.kt`: streaming SHA-256, typed artifact checks
  (`Ok/Missing/TooSmall/SizeMismatch/HashMismatch/Unverified`), versioned
  registry with runtime/arch/language/revision/license/artifacts.
- Verification wired into sherpa and LLM-task on-device size checks.
- Resumable downloads: Range resume via `.partial`, 416 → restart, premature
  EOF → fail (never promote a truncated file), atomic rename.
- Atomic staging + activation for speech zip and KWS tar models; a failed
  update leaves the previous good profile intact.
- Strict `offline` product flavor: INTERNET and ACCESS_NETWORK_STATE removed
  from the **merged** manifest via `tools:node="remove"`; `OFFLINE_BUILD`
  compile flag makes `hasNetwork()` false. Merged-manifest check passed.

**Still missing in code:** SAF/OpenDocument import via ContentResolver,
per-download-job cancellation (the `canceled` flag is still shared),
Range/Content-Range identity checks, `downloadToFile`/`downloadZip` EOF
handling, a native smoke test before activation, and real registry data.

### Task 13 — optional LLM is genuinely optional and proposal-only
- Default OFF (`Preferences.isLlmEnabled` default `false`), ON_DEMAND/warm are
  separate; deterministic readiness never waits for the LLM.
- Strict single-object schema rejects unknown/duplicate keys, trailing prose,
  fences, multiple objects, wrong types; multiple-object rejection is tested.
- Reserved params (`package`, `phone`, `contact_id`, `phone_id`) are stripped;
  model proposals still pass full policy and consent.
- MediaPipe remains real but maintenance-only; MLC stays disabled;
  ReasoningEngine no-op.

**Still missing in code:** LiteRT-LM 0.19.0 pinning/migration and compatibility
verification, MediaPipe serialized-generation/cancellation/release hardening,
output/context bounds, and the held-out evaluation.

---

## 2. New machine setup

### 2.1 Repo + toolchain

```powershell
git clone <your-repo-url>
cd "project adam"   # or whatever the clone folder is named
```

Install Android Studio (for its JBR + SDK manager), SDK Platform 34,
Build-Tools 34.x, NDK **25.1.8937393**, CMake **3.22.1**.

Create `local.properties` (it is gitignored; the original one is not in git):

```properties
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

Windows build shell:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testOnlineDebugUnitTest :app:lintOnlineDebug :app:lintOfflineDebug :app:assembleDebug :app:assembleRelease --no-daemon --console=plain
```

macOS/Linux: set `JAVA_HOME` to Android Studio's `jbr` directory and use
`./gradlew` with the same task list.

**Do not use `:app:testDebugUnitTest` or `:app:lintDebug`** — adding product
flavors made them ambiguous. Use the flavor-qualified names above. `assembleDebug`
and `assembleRelease` still build all flavors.

### 2.2 Install on the phone

Enable Developer Options → USB debugging; connect; authorize the RSA prompt.

```powershell
adb devices
adb install -r app\build\outputs\apk\online\debug\app-online-debug.apk
adb shell am start -n com.adam.assistant/.ui.MainActivity
```

Online debug = provisioning/download tests. Offline debug =
`app\build\outputs\apk\offline\debug\app-offline-debug.apk` (no INTERNET).

Grant runtime permissions as needed:

```powershell
adb shell pm grant com.adam.assistant android.permission.RECORD_AUDIO
adb shell pm grant com.adam.assistant android.permission.READ_CONTACTS
adb shell pm grant com.adam.assistant android.permission.POST_NOTIFICATIONS
```

`POST_NOTIFICATIONS` exists only on API 33+; a failure on older Android is fine.

Enable accessibility manually: Settings → Accessibility → Adam. (ADB enabling
is possible but version-finicky; prefer the UI.) The component is
`com.adam.assistant/com.adam.assistant.accessibility.AdamAccessibilityService`.

Clean-state tests:

```powershell
adb uninstall com.adam.assistant   # also deletes app-private models
```

### 2.3 Evidence loop

Keep an `evidence/` folder outside git or add it to `.gitignore`.

```powershell
adb logcat -c
adb logcat -v threadtime > evidence\taskX-logcat.txt
adb shell screenrecord --time-limit 180 /sdcard/adam-taskX.mp4
adb pull /sdcard/adam-taskX.mp4 evidence\
adb shell dumpsys activity services com.adam.assistant
adb shell dumpsys meminfo com.adam.assistant
adb shell dumpsys thermalservice
adb shell dumpsys batterystats com.adam.assistant
```

Airplane mode (API 30+):

```powershell
adb shell cmd connectivity airplane-mode enable
adb shell cmd connectivity airplane-mode disable
```

---

## 3. Device task playbooks

### Task 6 — real-device adapter acceptance

**Goal:** prove the app hands off to launch/search/draft/dialer/compose screens
and never claims more than it observed.

**Before the phone work:** finish the JVM mock sequences if any are missing:
unavailable service/app, wrong window/package, duplicate/blank label, clickable
ancestor, stale node, type failure, canceled gesture, timeout, background-launch
denial. Use `ScreenItem` fixtures and assert `ScreenPick.Ambiguous/None` or an
aborted action. Also surface `LaunchableApps.Ambiguous` to the user as a
question rather than picking a winner.

**On the phone (synthetic content only):**
1. “Open <uninstalled app>” → honest not-installed/fallback, no fake launch.
2. “Open Spotify” → Spotify home. “Search <query> on Spotify”/play-on-Spotify →
   search screen, **no playback**.
3. “Message Adam Test saying hello” → approve → WhatsApp draft opens with the
   number/body prefilled. **Do not press Send.** Adam must say draft opened,
   not sent.
4. “Call Adam Test” → dialer prefilled, no auto-call.
5. “Email Adam Test about …” → compose draft; “text …” → SMS compose draft.
6. “Set alarm for 07:30” → clock alarm screen prefilled, result says confirm to
   save. “Set a timer for 5 minutes” → timer screen prefilled, confirm to start.
   “Add calendar event …” → calendar draft, or asks for a missing date.
7. With accessibility off, accessibility-dependent actions fail clearly (no
   hang). With it on, switch apps mid-action and confirm wrong-package abort;
   change the screen and confirm stale-node abort.
8. Check API 30+ package visibility: launchable apps resolve; unavailable ones
   do not silently pretend.

**Pass:** no sent/delivered/playing/created claim without evidence; no
auto-Send; no arbitrary editable-field fallback; no QUERY_ALL_PACKAGES.

**Evidence:** screen recordings, logcat, a short table of command → observed
screen → reported result.

---

### Task 7 — cancellation and single-owner state

**Goal:** Stop/Cancel invalidates authorization immediately, stops capture/TTS,
prevents further adapter steps, and rejects late callbacks; one recorder/one
native operation per owner.

1. Add temporary structured log markers for state transitions and generation
   IDs if they are inadequate (remove or keep behind debug).
2. Rapid Start/Stop (20×), then Stop during: download/provision, capture,
   decode, planning, TTS, adapter action. Repeat one-shot starts.
3. Revoke `RECORD_AUDIO` mid-capture, then re-grant.
4. Switch STT backend mid-session; background the app; rotate; screen off/on.
5. Kill the process (`adb shell am force-stop com.adam.assistant`) during work,
   relaunch; confirm STOPPED, no replay of old actions.
6. Trigger a late final/partial after Stop; confirm generation-ID rejection.
7. Measure cancellation-to-quiescence separately from immediate side-effect
   invalidation. A coroutine deadline is **not** proof native decoding stopped;
   if interruption is unsafe, show Canceling and discard the eventual result —
   never free a live recognizer or invent a stop JNI call.
8. Confirm pending dialogs/consent are cleared and no post-Stop audio plays.

**Pass:** exactly one recorder and one native operation per owner; no stale
action after Stop; no unexpected playback; one-shot returns to ready exactly
once; concurrent/repeated Start is single-flight.

**Evidence:** timestamped logcat, screen recording, cancellation-timing table,
`dumpsys activity services` before/after.

---

### Task 8 — capture-only diagnostics and canonical audio replay

**Goal:** one app-owned capture path, exact PCM saved only with consent, and
replay that can never execute anything.

1. First capture with wake/TTS/cues disabled; opt in; record a short command, a
   long dictation, and genuine silence; use manual end.
2. Verify the WAV is 16 kHz mono PCM16, includes first and last syllables, and
   plays at correct speed. Confirm the saved data never uploads and can be
   deleted.
3. Replay local audio: no microphone, wake, TTS, parser, or real executor.
   Show raw recognition separately from normalized policy and mock plan.
4. Fixtures: zero, minimum negative, maximum positive, known tone, short reads,
   stereo/downmix/resample, empty/corrupt files, EOF, repeated decode. Decode
   deterministically; Vosk accumulates finals and flushes EOF without
   duplication.
5. Distinguish capture/no-speech timeout, endpoint silence, hard cap, and
   manual end — genuine silence must not become a 15-second command decode.
6. Compare `MIC` vs `VOICE_RECOGNITION`; `UNPROCESSED` is optional measurement.
7. Confirm normal logs contain IDs/durations/versions/read/error/route/thermal
   categories only — no audio, transcript, contacts, bodies, screen trees, or
   app inventory.

**Pass:** replay cannot send/launch; WAV is correct; route/silencing diagnostics
separate capture failure from ASR error; retention/deletion works.

**Evidence:** WAV samples, replay logs, fixture results, log-redaction check.

---

### Task 9 — real-artifact and on-device provisioning acceptance

**First finish the code gaps:**
- SAF `OpenDocument` import via `ContentResolver`, copied into persistent
  app-private storage; never treat a content URI as a path; no all-files
  permission; canceled import leaves the old profile.
- Per-job download cancellation (not the shared flag).
- Range/Content-Range/identity validation, ignored ranges, 416, premature EOF
  for every download path.
- Native smoke test before replacing a good profile.
- Real `ModelRegistry` entries: exact filenames, bytes, immutable revision,
  license/NOTICE, tokenizer/template compatibility.

**Digests:** use checksums published by the upstream artifact source. If none
exist, record `Unverified` and say so. **Do not invent or self-certify
checksums.**

**On the phone:**
1. Cold/warm startup with verified models: zero Adam DNS/HTTP requests even
   with networking enabled; airplane mode also works. Use
   `dumpsys netstats detail` filtered to Adam's UID plus logs; the offline
   build is the strongest check because it has no INTERNET permission.
2. Install the offline APK, import via SAF, confirm no download path runs.
   Note: third-party apps may still need their own network for WhatsApp
   delivery or Spotify remote playback — that is not Adam's network.
3. Negative cases: wrong format/language/architecture, same-size corruption,
   interrupted download, 401/403, low space, canceled import, failed update.
   None may become Ready; the previous good profile must survive.
4. Verify full models before native creation; smoke test before activation.
5. Confirm provisioning is off the typed-command critical path.

**Pass:** strict flavor has no INTERNET in merged manifest/APK (already true);
no false Ready; no working-profile loss; no download on typed-command path.

**Evidence:** registry snippets + upstream digest source, merged manifest,
netstats/logs, before/after model listings, negative-case log excerpts.

---

### Task 10 — evaluate one real mobile STT branch at a time

**Goal:** choose the smallest candidate that meets held-out command/entity and
mobile budgets on the actual phone.

- Keep Vosk and sherpa Whisper Small.en as references, not mandatory defaults.
- Branch A (least change): pinned sherpa 2.0.1 AAR + endpointed English
  Moonshine tiny v2 (encoder/decoder/tokens via
  `OﬄineMoonshineModelConfig.mergedDecoder`; 44,243,206 bytes).
- Branch B: Moonshine Streaming 0.1.5 with the supplied-audio Transcriber and
  Adam's audio owner; eight core files; 45,233,659 bytes (Tiny); never load
  streaming files into sherpa's offline config; deduplicate segment IDs;
  never execute partials.
- Branch C: sherpa online Zipformer 20M (then June26) with matching
  modelType/files; hotwords only with `modified_beam_search`, BPE, and
  `bpe.vocab`.
- Hindi/Hinglish: evaluate multilingual Whisper tiny/base (`hi` vs `auto`,
  `translate=false`); English-only `.en` is wrong.
- Verify real licenses/manifests and debug/release loads; same-PCM outputs;
  no canned success when files are missing.

**Measure:** WER/CER, supported semantic intent exactness, critical entity
exactness, abstention/clarification, false actionable negatives, cold load,
last-sound→final p50/p95, memory, and stability. Record device/OS/ABI/model
revision/settings/routes.

**Pass:** exact real model/license/runtime manifests; debug and release load;
held-out results favor the chosen candidate; missing artifacts stay
unavailable.

---

### Task 11 — offline TTS and half-duplex audio

1. Select an installed voice for the user's language with
   `isNetworkConnectionRequired=false`; exclude not-installed features; await
   language/voice configuration and inspect support codes. Provision missing
   voices only by a deliberate supported flow. Silent/haptic fallback is valid
   and must be truthful.
2. Airplane mode, fixed non-sensitive phrase: playback works offline or the app
   says it is falling back silently. Engine name/locale alone is not proof.
3. Serialize TTS through one listener and unique IDs; filter every terminal
   callback. Timeout/cancel stops playback and releases focus.
4. Default half-duplex with touch Stop; measure acoustic tail before capture;
   do not assume AEC.
5. Concurrent speech, old stop/error callbacks, and cancellation must not
   finish another utterance or continue audio after Stop.
6. TTS speaking “Adam”/wake phrases must yield no executable plan.
7. Test cues on/off and headphones; check route changes.

**Pass:** cold airplane-mode phrase works or truthful silent fallback; no
cross-utterance completion; no post-stop audio; no self-triggered command.

---

### Task 12 — lifecycle and mobile resource limits

Test API 26 (emulator) and API 34+ (phone) separately; label unavailable APIs
as untested.

1. Keep microphone FGS declarations. Start optional background capture only
   from a visible authorized interaction; promote to foreground before heavy
   work; catch platform start/security failures.
2. API 31 background-start exemptions and API 34 while-in-use mic rules are
   separate. Accessibility/FGS/overlay grants no unlimited mic/activity rights.
   No boot/job/accessibility-reconnect auto-listening.
3. Prefer non-sticky PTT; null restart/process death never replays actions.
4. API 33 notification permission: denial still leaves PTT usable; drawer
   notification is hidden. Provide an ongoing privacy-safe Stop control.
5. System Stop may kill without cleanup; next launch must start stopped.
   Background-launch denial → deliberate foreground/notification handoff, not
   an invisible overlay.
6. Idle PTT: no recorder, inference loop, wake lock, or screen scanning.
7. Bound capture duration, queues, context, concurrent workloads, and resident
   models. Respond to thermal/power saver/memory pressure by reducing optional
   work, not blanket exemptions.
8. Inspect actual mic/Bluetooth routes and disconnects with API guards; do not
   force call mode globally.
9. Measure PSS/RSS, battery, thermal, idle cost; run sustained sessions.

**Pass:** no implicit restart, hidden listening, or post-stop plan; permission
denial does not break typed/PTT use; route loss and interruptions are handled
honestly.

---

### Task 13 — optional LLM on device

1. Keep default OFF; ON_DEMAND and opt-in warm are separate; never await
   provisioning/loading before deterministic readiness; failures abstain.
2. Preserve a lawfully installed working MediaPipe 0.10.27/Gemma pair while
   hardening serialized generation, cancellation, and release. Apply actual
   output/context bounds (the 256-output constant is unused; 2048 maxTokens is
   total context). Do not guess cancellation APIs or close an active native
   object unsafely.
3. Handle gated Gemma 401/403 through lawful license onboarding/import; never
   bypass or embed tokens.
4. Pin LiteRT-LM 0.19.0; verify compiler/ABI/minSdk/native compatibility,
   feature-flag migration, and keep automatic tools off. Evaluate at most one
   justified alternative (Gemma 1B/Qwen3-0.6B `.litertlm`, or pinned llama.cpp
   with Qwen2.5-0.5B-Instruct Q4_K_M GGUF). Qwen3 thinking must be disabled via
   verified runtime/template control.
5. Held-out typed and ASR-text comparison with LLM OFF: the optional model must
   improve useful proposals without violating safety/resource budgets. Record
   cold/warm memory/latency/cancel on the phone, never desktop/emulator
   numbers as phone evidence.
6. A hardcoded 0.8 is not safety confidence.

**Already done:** strict schema rejection, reserved-key stripping, default OFF,
policy/consent gating.

---

### Task 14 — stage-separated tests and measured promotion gates

Build three independent tools and keep their diagnoses separate:
1. typed parse/dry run;
2. approved local capture/replay;
3. mock executor.

Diagnosis rules:
- wrong saved waveform → capture/endpoint/route
- complete waveform, wrong raw text → ASR/settings/model
- correct typed text, wrong plan → parser/policy
- correct validated plan, wrong observed result → adapter/platform

Test assets: synthetic contacts/content; app-owned screens with duplicate
labels and editable fields; 20–30 useful command types with repetitions per
session and separate held-out sessions; user language/accent only with consent;
pauses/quiet/noise; negation; missing slots; confirmation; unsupported
compounds; silence; background voices; own TTS; canceled clips. No real
send/call is an assertion.

Score separately: raw/normalized WER/CER, supported semantic intent exactness,
critical entity exactness, abstention/clarification, false actionable
negatives, clipping, wake metrics, verified result category; cold load,
last-sound→final p50/p95, parsing/action timing, streaming backlog/RTF, PSS/RSS,
thermal/battery/idle, cancellation quiescence.

Provisional targets (not achieved results): ≥95% held-out supported intent,
≥98% critical entity exactness before correction/clarification, zero
unauthorized executor calls in safety regressions, p95 ≤ 2 s last sound→final,
RTF < 1 without sustained queue growth. Agree resource/power budgets after
measuring the phone. Run 100 repeated decodes and stop/start/cancel cycles.
Fail-closed recipient/consent correctness is mandatory regardless of aggregate
scores.

Delivery: changed files, exact commands/results, real manifests/licenses/digests,
redacted phase traces, replay instructions, observed phone metrics, and an
explicit untested/unsupported list. Exported diagnostics must contain none of
the fixture transcript/body/recipient strings. Never claim sent/playing/total
control or achieved accuracy without independent support.

---

### Task 15 — VAD and wake only after 14 passes

1. Run validated VAD in shadow mode against retained PCM before it cuts audio.
   Unavailable is not probability zero.
2. Use correct window/context, recurrent reset, stateful segment API; tune
   onset/pre-roll, minimum speech, pauses/hangover, message duration on held-out
   data.
3. If wake is desired: fix dormant KWS model-tokenized keywords, complete
   artifact validation, readiness/decode/reset loop, and metadata/modelType
   before instantiation. Start opt-in “hey adam”/“ok adam”; bare “adam” is an
   explicit false-wake tradeoff.
4. One PCM ring-buffer owner bridges wake to command; re-transcribe preserved
   command audio with the selected ASR (never promote Vosk remainder text).
   Remove wake only by identified leading token span.
5. Acceptance: one-breath wake+command, pause-after-wake, cue-delayed command,
   short “no”, local names, long dictation, negative TV/TTS clips. Partials and
   stale finals never act. Report misses/false wakes per measured negative
   duration and idle energy.
6. Full-duplex barge-in stays OFF until own-TTS/route/volume negatives pass.
   KWS is not AEC or speaker verification.

---

## 4. Hard rules for the next agent

- Do not invent checksums, device results, performance numbers, or “working APK”
  claims. Cite the command/log/screenshot that produced each number.
- JVM tests (`unitTests.isReturnDefaultValues = true`) do not prove Android
  adapter correctness.
- WhatsApp is draft-only; never press Send in tests. Spotify is open/search,
  never auto-play. No real calls/purchases/destructive settings.
- Never enable dormant MLC, fake JNI/model results, or report success from a
  dispatched intent/click alone.
- Keep `local.properties` and the model/replay evidence out of git.
- Do not commit or push unless the user asks.
- The strict offline flavor cannot make third-party apps (WhatsApp delivery,
  Spotify remote) work offline; Adam itself makes no network calls there.
- `ai_implementation_tasks.pdf` is gitignored — copy it manually to the new
  clone (or `git add -f` it) if it is not already in the remote.
- MLC AAR is optional and absent; the app runs without it.

## 5. Recommended first pass on the phone

1. Build + unit tests + lint on the new machine.
2. Install online debug; permissions; accessibility; clean state.
3. Task 6: 8 real-device checks above + finish missing mock sequences.
4. Task 7: rapid Start/Stop, Stop during each phase, permission revoke,
   force-stop, late callbacks; record timings.
5. Task 8: capture 3 clips + replay + fixtures; verify no side effects.
6. Task 9: real manifests, SAF import, offline APK, negative provisioning.
7. Then 10 → 11 → 12 → 13 → 14 → 15 in order, recording evidence as you go.
