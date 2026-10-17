# Wake word ("Hey Friday"): free engines, Android 16 rules, power, and a design

_Last updated: 2026-10-17 · Trust tags: [primary] read at source · [secondary] search/blog/forum · [measured] run here · [UNCERTAIN] unconfirmed or conflicting_

**Scope.** A wake word for Friday on the owner's Pixel 8, with very low standby power:
- The phone: Tensor G3, Android 16, not rooted. Friday is sideloaded and runs fully on-device.
- This is the first step toward voice. Speech-to-text comes next, owner-voice verification later (D-021).
- The owner wants a **free** solution (2026-10-17).

**Method and limits.**
- **Sources.**
  - AOSP at `android-16.0.0_r4`: frameworks/base, frameworks/av, packages/modules/Permission.
  - The Pixel 8 device tree (`device/google/shusky`, main).
  - The SDK 36 `android.jar` (`javap`).
  - developer.android.com and Google support pages.
  - Engine repos, docs and pricing pages; papers.
- **Nothing was run on the phone** (as asked), so there are no [measured] tags. §7 turns the load-bearing claims into measurements.
- **How the work was split.** Engines and licensing were researched by one parallel sub-agent, power by another. Their load-bearing claims were re-read at source here:
  - **openWakeWord:** README, `utils.py`, `model.py`, `train.py`, the custom-verifier docs, and the graphs of its two front-end models and the community "Hey Friday" model;
  - **Picovoice:** the FAQ (fetched 2026-10-17) and the phrase guide;
  - **other engine sources:** the Jaco-Assistant benchmark README, and Dicio's `OpenWakeWordDevice.kt` and training config;
  - **Pixel 8 and AOSP:** shiba's `power_profile.xml` and `sound_trigger_configuration.xml`, `Threads.cpp`, `roles.xml`, and AOSP commit 22f37b8;
  - **pages:** the Home Assistant Android docs and the EC DMA page.
- **No training on the Mac.** All training is for the owner's GPU box (§4.4). The Mac work was light: about 11 MB of source files and small models in a scratch folder.

## TL;DR
1. **Use openWakeWord with a "Hey Friday" model. It is the only free option that fits.**
   - **Free:** the code is Apache-2.0.
   - **No new dependency:** its small ONNX models (1.1 + 1.3 MB shared, ~0.2 MB per phrase) run in the ONNX Runtime Friday already ships. No new Gradle dependency, no INTERNET permission.
   - **First try needs no training:** a community "Hey Friday" model already exists (MIT, 207 KB).
   - **Then our own model**, trained only on the owner's GPU box (D-034, never the Mac): ≈ 1.5–3 h [UNCERTAIN] and ~26 GB of data, from synthetic speech (§4.4).
   - **Accuracy:** an independent benchmark (pre-trained "alexa") measured **6.4% missed at 0.1 false accepts/hour on read speech, but 25.5% in dinner-party noise** (10.6% at 0.5/h) [primary].
   - **Free second stage:** a verifier trained on ~5 minutes of the owner's voice cuts false wakes from other people.
   - **The other options lose:**
     - Porcupine is no longer free for individuals ("no dedicated free or paid plans for personal or non-commercial use", Picovoice FAQ), and it needs the network.
     - microWakeWord is hard to train and scored poorly independently.
     - sherpa-onnx needs no training, but it clashes with our ONNX Runtime and likely false-triggers more.
2. **Google's low-power path is closed to Friday, even as the default assistant** [primary, AOSP 16]:
   - The hotword and SoundTrigger APIs are system-only.
   - Pixel 8's low-power audio chip runs only Google's three models.
   - Becoming the default assistant adds no mic privilege and switches off "Hey Google".
   - **This may change.** An EU DMA decision (16 Jul 2026) orders "always-on hotword detection" for third-party assistants in **Android 18, by 1 Aug 2027**. Whether it reaches India and accepts our own model is [UNCERTAIN]. Revisit then.
3. **Friday's accessibility service already has the mic rights** [primary code; UNCERTAIN until measured]:
   - Its system binding passes system_server's capabilities, including the microphone, into Friday's process. Listening needs **no foreground service and no notification**.
   - The same binding lets Friday start a `microphone` foreground service from the background, if we ever need one.
   - The green mic dot shows whenever Friday listens, and nothing can hide it.
4. **Power: the model is cheap; keeping the phone awake is not.** Estimates from Pixel 8's `power_profile.xml` and papers [UNCERTAIN until measured]:
   - Listening while the screen is on or unlocked costs **≈ 0.5–1.5% of battery a day**.
   - Listening 24/7, screen off included, costs **≈ 15–22%/day**, because an open mic stops the phone from ever sleeping (`AudioIn` wakelock).
   - Listening only while charging costs **0%**.
   - For comparison, Google's low-power path costs ≈ 0.9%/day in total (Now Playing, Pixel 2).
5. **Design (D-035):**
   - Listen while unlocked (Google's Voice Access stops when locked, too), and while charging.
   - On "Hey Friday": a haptic tick, then Friday's request box (STT later).
   - Pause during calls.
   - Audio stays in RAM only: nothing stored, nothing sent.
   - 24/7 screen-off listening only if the owner opts in after seeing the measured cost.
6. **Biggest risks:**
   - False wakes on "Friday" in conversation and TV, and misses in noisy rooms. Say "Hey Friday" (7 phonemes, not 5) and add the verifier.
   - The training recipe is fragile (the official notebook is broken; a patched fork exists).
   - The accessibility mic path is unmeasured. The fallback is a microphone foreground service.
   - The recording-path current on a Tensor chip is unknown. §7 measures it.

## 1. Engines compared: free for a personal, offline Friday?
"Accuracy" is the independent Jaco-Assistant benchmark (Sept 2026): the miss rate at 0.1 / 0.5 false accepts per hour, for pre-trained "alexa" models, on a 24.7 h LibriSpeech mix and on DiPCo dinner-party noise. Picovoice's own benchmark is vendor-run [primary, both READMEs].

| Engine | Free? | Android path | "Hey Friday" | Accuracy (read speech · dinner party) | Size | Verdict |
|---|---|---|---|---|---|---|
| **openWakeWord** | **Yes.** Code Apache-2.0; bundled models CC BY-NC-SA 4.0 (fine for personal use) | ONNX, opset 13/15, standard ops, in **Friday's existing ORT 1.20**. Dicio ships it on Android (TFLite) | A community "Hey Friday" model exists (MIT, 207 KB, `[1,16,96]→[1,1]`, untested). Or train our own on the GPU box (§4.4) | 6.4% / 4.0% · 25.5% / 10.6%. Fires 0.04 s after the word ends | 1.1 MB mel + 1.3 MB embedding (shared) + ~0.2 MB per word | **Pick** |
| Porcupine (Picovoice) | **No.** The FAQ says "no dedicated free or paid plans for personal or non-commercial use"; the trial is one-time. Free keys stopped 2026-06-30 [secondary]. The key is checked at start, and the demo app declares INTERNET | AAR | Console custom words for companies only. v1.9 is keyless but has 14 fixed words, and its personal custom models run on x86_64 only | 8.5% / 5.8% · 9.7% / 4.3% (v1.9). Vendor: 2.7% missed at 1 false accept per 10 h | small | Out: cost and network |
| microWakeWord | Yes. Apache-2.0, weights included | LiteRT plus a native C feature front end (40 features per 10 ms). HA's Android app ships a port (Apache-2.0) since 2026.2.3, "experimental" | Train our own; the author: the notebook model "will most likely not be usable", and a good model is "still very difficult" | README (2024): 0.67–3.49% at 0.08–0.49/h. Independent: 46.5% / 38.9% · 56.2% / 45.6% | 52–60 KB | Not now |
| sherpa-onnx KWS | Yes. Apache-2.0 | AAR with **its own `libonnxruntime.so`**, which clashes with ours (duplicate path). The static AAR adds ~24 MB and a second ORT in RAM | Type "HEY FRIDAY" as text; no training | No official numbers. One port: 88% recall, 0.27 false positives per utterance (only 60 clips) [secondary] | ~5 MB int8; +~320 ms latency | Fallback only |
| local-wake | Yes. MIT | **Python only**; a port is needed | Matches 3–4 of the owner's own recordings; no training | 4.6% / 3.4% · 8.0% / 4.9% (best in the benchmark) | ? | Worth a look later (also owner-specific) |
| Sensory (TrulyHandsfree / VoiceHub) | No. VoiceHub is free for prototypes; shipping needs a licence from sales [secondary] | via sales | VoiceHub | — | — | Out |
| LiteRT / TF keyword models (Speech Commands, YAMNet) | Yes | LiteRT | No "Friday": 35 fixed words, or sound classes only | — | — | Out (means training our own anyway) |
| Android's own hotword path | — | System-only (§2.1) | — | — | — | Closed |

**Notes:**
- **openWakeWord is the only option that is free, runs on what Friday already ships, and is reasonably accurate.**
  - Its weak spot is noisy rooms: about 25% missed in dinner-party noise at 0.1 false accepts/h.
  - Raising the false-accept budget to 0.5/h roughly halves that.
- **Dicio**, the closest Android prior art, runs openWakeWord in a `microphone` foreground service at 16 kHz with threshold 0.8.
  - Its users report "works 1 in 20" (#237), misses during music (#363), and a phone hot overnight (#453) [secondary].
  - **Pitfall for our port** [primary, both files read]: Dicio divides the samples by 32768 before the mel model (`OpenWakeWordDevice.kt` L107). The reference feeds **raw int16 values as float** (`utils.py`). Our port must match the reference.
  - Whether the scaling causes Dicio's misses is [UNCERTAIN].
- **Watch: livekit-wakeword** (Apache-2.0). It claims ~100× fewer false positives than openWakeWord on its own test set, but it has no Android build [secondary, vendor claim].
- **Picovoice's pages conflict.** Cached pricing pages still show a "Forever-Free" plan. The live FAQ (fetched 2026-10-17) and Picovoice's email quoted on the HA forum say it is gone.

## 2. What Android 16 lets Friday do with the mic

Read at `android-16.0.0_r4` (frameworks/base, frameworks/av, packages/modules/Permission) and the SDK 36 `android.jar`, unless noted. Line numbers are for that tag. Pixel builds are not AOSP, so every claim here is **[UNCERTAIN until measured]** on the phone (checklist in §7).

### 2.1 The platform hotword path is closed to Friday, even as the default assistant [primary]
- **Not in the SDK.** `AlwaysOnHotwordDetector`, `HotwordDetector`, `HotwordDetectionService` and `SoundTriggerManager` are absent from the public SDK 36 `android.jar`. `VoiceInteractionService` has no `create…HotwordDetector` method.
- **Locked on the server too** (`VoiceInteractionManagerService`):
  - `initAndVerifyDetector` and `updateState` need `MANAGE_HOTWORD_DETECTION`, which is `internal|preinstalled`.
  - `startListeningFromMic` needs `CAPTURE_AUDIO_HOTWORD`, which is `signature|privileged|role`.
  - Enrolling a keyphrase model needs `MANAGE_VOICE_KEYPHRASES` (`signature|privileged`) or `KEYPHRASE_ENROLLMENT_APPLICATION`.
- **The assistant role doesn't unlock it.** `roles.xml` grants the ASSISTANT holder SMS, call log, overlay and a few other permissions.
  - It does not grant `CAPTURE_AUDIO_HOTWORD` or `MANAGE_SOUND_TRIGGER`. Those come from `SYSTEM_AMBIENT_AUDIO_INTELLIGENCE`, a `systemOnly` role.
  - It does not grant `RECORD_BACKGROUND_AUDIO` either (`internal|role`, "not intended to be held by apps"), which comes from the `systemOnly` `SYSTEM_SHELL` role.
- **So the Tensor G3's low-power audio DSP is out of reach.**
  - It is reachable only through these APIs.
  - Its Pixel 8 configuration carries only Google's "Hey Google" model and two Now Playing models (§3.3).
  - The Tensor TPU is also closed to third-party apps on G3 (research/04).
- **What becoming the default assistant would add.** The system binds a voice-interaction service with `BIND_FOREGROUND_SERVICE | BIND_INCLUDE_CAPABILITIES | BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS` (`VoiceInteractionManagerServiceImpl` L1075–1078). Those are the same mic privileges Friday's accessibility binding already has (§2.2), plus the hold-power-button gesture.
  - The cost: only one voice-interaction service is active, so "Hey Google" and Gemini's hotword stop [secondary].
  - Not worth it for a wake word.

### 2.2 Friday's AccessibilityService already holds the mic privileges [primary code; UNCERTAIN until measured]
- **How the system binds it.** Every enabled accessibility service is bound with `BIND_AUTO_CREATE | BIND_FOREGROUND_SERVICE_WHILE_AWAKE | BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS | BIND_INCLUDE_CAPABILITIES` (`AccessibilityServiceConnection` L190–193).
- **The service process gets the mic capability.**
  - `OomAdjusterImpl` L1847 copies the client's capabilities into the bound process when `BIND_INCLUDE_CAPABILITIES` is set.
  - The client is system_server, a persistent process pinned at `PROCESS_CAPABILITY_ALL` (L1160–1171).
  - That includes `FOREGROUND_MICROPHONE`, the capability AppOps checks for `RECORD_AUDIO`. `RECORD_AUDIO` is foreground-only: its `backgroundPermission` is the unobtainable `RECORD_BACKGROUND_AUDIO`.
  - So `AudioRecord` inside `AgentService`'s process should get real audio with the screen on or off, and **no foreground service is needed**.
- **It may also start a microphone foreground service from the background.**
  - `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS` gives the process an `ALLOW_BAL` token (`ActiveServices` L4326, `ServiceRecord` L1479–1481). That token also allows foreground-service starts.
  - `shouldAllowFgsWhileInUsePermissionLocked` then returns `REASON_ACTIVITY_STARTER`. Its comment says: "The binding flag BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS is also allowed by the check here" (L8789–8797).
  - The start itself follows the while-in-use verdict (L9068).
- **Google's documented exemption works the same way.** "The service starts by an app which provides the `VoiceInteractionService`" is a documented while-in-use exemption [primary, developer.android.com]. It is implemented with that same bind flag.
- **Prior art: Home Assistant's Android app** uses the voice-interaction route. It must be the default assistant, and its `AssistVoiceInteractionService` starts a `microphone` foreground service for its wake word [primary, home-assistant/android].
- **Prior art: Google's Voice Access** is an accessibility service that listens for commands [primary, Google support]:
  - "uses almost no battery when it's inactive, but it uses more battery when listening".
  - Google suggests keeping it on a charger if the battery drains.
  - It stops listening when the device is locked.
  - It shows the green mic indicator on Android 12+.
- **The AppOps check is capability-only** (`AppOpsUidStateTrackerImpl` L144–177): `OP_RECORD_AUDIO` is allowed exactly when the uid holds `PROCESS_CAPABILITY_FOREGROUND_MICROPHONE`; procstate does not matter.

### 2.3 Who hears what: concurrent capture [primary: `AudioPolicyService::updateUidStates_l`, frameworks/av 16 r4]
- **Friday counts as an accessibility client.** Every *bound* accessibility service's uid is passed to the audio policy (`AccessibilityManagerService` L3129–3143); `isAccessibilityTool` is not required.
- **Accessibility clients never mask other apps.** They are "not considered for top or latest active". Friday's always-open stream won't silence a WhatsApp voice note or a Meet call.
- **With the screen on, almost nothing silences Friday.** Friday's process is BOUND_FOREGROUND_SERVICE while the device is fully awake and IMPORTANT_FOREGROUND when asleep (`OomAdjusterImpl` L2040–2054). The audio policy calls any accessibility uid in TOP…BOUND_FOREGROUND_SERVICE "on top" (`isA11yOnTop`, L1711), and an on-top accessibility client using the `VOICE_RECOGNITION` or `HOTWORD` source is always allowed to capture. So with the screen on, Friday hears even during calls, VoIP, video recording, or while Gemini is open. The docs agree: during a call "the app can capture audio if it is an accessibility service" [primary docs].
- **With the screen off, Friday is silenced (receives zeros) when:**
  - Gemini is on top;
  - a privacy-sensitive capture is running (`VOICE_COMMUNICATION`, `CAMCORDER`, `setPrivacySensitive(true)`);
  - a call is in progress.
- **Always silenced** when the owner's microphone privacy toggle is off (`silenceAllRecordings_l`).
- So Friday must **pause itself during calls and VoIP** (`AudioManager` mode `IN_CALL` / `IN_COMMUNICATION`). With the screen on, the platform won't do it for us.
- **Detecting silencing:**
  - `AudioRecord.registerAudioRecordingCallback` reports `AudioRecordingConfiguration.isClientSilenced()` (API 29, public).
  - There is no public "mic toggle is off" query: `SensorPrivacyManager` only exposes `supportsSensorToggle`.
  - So treat a silenced or all-zero stream as "paused".

### 2.4 If we use a foreground service [primary]
- **Permissions.** Type `microphone` needs `FOREGROUND_SERVICE_MICROPHONE` (normal) plus `RECORD_AUDIO` (runtime). It is foreground-only, so a start from the background needs a while-in-use exemption; Friday has one through §2.2 (`ForegroundServiceTypePolicy`).
- **No time limit.** Only `dataSync` and `mediaProcessing` are time-limited (`ActiveServices` L3789–3806).
- **Not from a boot receiver.** For target ≥ 35, a `BOOT_COMPLETED` receiver may start only these types: location, connectedDevice, remoteMessaging, health, systemExempted, specialUse. Not microphone. (`FGS_BOOT_COMPLETED_RESTRICTIONS`, L369–383.)
  - Not needed: the accessibility service re-binds after the first unlock, and it can start the service then.
  - **Contrast:** Dicio starts from a boot receiver, so after a reboot it can only post a "tap to start" notification [secondary, read by the sub-agent].

### 2.5 Doze and wakelocks [primary: `PowerManagerService` L4603–4642]
- **Friday's own wakelocks die in Doze.** In deep Doze, an app's partial wakelocks are disabled if its process is below BOUND_FOREGROUND_SERVICE and the app isn't battery-unrestricted. With the screen off, the accessibility process drops to IMPORTANT_FOREGROUND (the binding is WHILE_AWAKE), so Friday's own wakelocks would be dropped.
  - Friday doesn't need one: a blocking `AudioRecord.read()` wakes the thread when audio arrives.
- **The capture keeps the phone awake.** Its wakelock is AudioFlinger's `"AudioIn"` (`Threads.cpp` L1191), owned by audioserver, a system uid that Doze never disables. An open mic therefore keeps the phone awake in Doze. That is both why listening works with the screen off and why it costs power (§3).

### 2.6 The privacy indicator [primary: `PermissionManager.getIndicatorExemptedPackages`, SystemUI `AppOpsControllerImpl`]
- The green dot and chip show for every recording app, except holders of the system "intelligence" roles (ambient audio, UI, audio, notification, text, visual). On a Pixel that means Android System Intelligence.
- **Friday shows the green dot whenever it listens, and nothing hides it.** We treat this as a feature: the owner sees exactly when the mic is open.

### 2.7 Coming in Android 17 [primary: developer.android.com/about/versions/17/changes/bg-audio]
- **Background playback is restricted for all apps.** `AudioTrack` writes are silenced, focus requests fail, and volume calls are ignored, unless the app has a visible activity or a foreground service other than `shortService`. Targeting API 37 also requires that service to hold while-in-use.
- **Capture is not covered.**
- **For Friday:** acknowledge the wake word with a **haptic tick and the overlay**, not a chime. The reply-by-voice step (TTS) will need a foreground service on Android 17.

## 3. Power: the model is cheap; keeping the phone awake is not

### 3.1 Numbers
| What | Number | Device | Tag |
|---|---|---|---|
| Battery | 4,575 mAh typical. 1%/day ≈ 1.9 mA average | Pixel 8 | [primary, Google spec] |
| Asleep (`cpu.suspend`) | 10 mA | Pixel 8 (shiba `power_profile.xml`) | [primary file; Google's battery-stats estimate, not a measurement] |
| **Awake but idle** (`cpu.idle`, extra cost of holding a wakelock) | **+28.6 mA** | same | same |
| Any CPU running (`cpu.active`) / little cluster | +12.37 mA / +0.24 mA | same | same |
| One little core at 324 / 615 MHz | 1.89 / 6.15 mA | same | same |
| Recording path (mic + codec + audioserver) | no entry in the file (the 75 mA `audio` item is playback) | — | [primary] |
| Recording keeps the phone awake | RecordThread holds the `"AudioIn"` partial wakelock while any capture is active. It is owned by audioserver, so Doze doesn't cancel it | Android 16 | [primary, `Threads.cpp` L1191, `PowerManagerService` L4603] |
| CPU path vs low-power chip, measured | wakelock with screen off 295 mW, mic +47 mW; asleep 30 mW; mic on the low-power chip 2–4 mW | Snapdragon 800 board (2014) | [primary, DSP.Ear, arXiv 1409.3206; old hardware] |
| Small KWS model compute | 0.5–1.6 ms per 20 ms of audio (≈ 2.5–8% of a core) | Pixel 4 | [primary, arXiv 2005.06720] |
| openWakeWord compute | one Raspberry Pi 3 core runs 15–20 models in real time | Pi 3 | [primary, README] |
| Google's low-power path (Now Playing) | detector on the low-power chip < 0.4 mA. With ~100 main-CPU wakeups a day, ≈ **0.9%/day** in total | Pixel 2 | [primary, arXiv 1711.10958] |
| Home Assistant app (microWakeWord on the CPU) | "noticeable impact on battery life"; "uses more battery than 'Ok Google'" because Google's low-power hardware isn't open to third parties | Android | [primary, home-assistant.io] |
| The same app, as reported | its battery share went from "roughly 1% to 15%" with the wake word on | unknown | [secondary, Open Home Foundation blog] |
| Dicio (openWakeWord) users | phone hot overnight on the charger; a request to listen only while charging or driving | Pixel 10 Pro | [secondary, GitHub #453, #407] |
| Pixel 8 normal screen-off drain | ≈ 0.6–1%/h (27–46 mA) | Pixel 8, GrapheneOS | [secondary, forum] |

### 3.2 Estimate for the owner's Pixel 8 [UNCERTAIN until measured, §7]
**Assumptions:**
- The screen is on 4 h a day and off 20 h.
- The model uses 2–10% of one little core: ≈ 0.4–1.9 mA.
- The recording path costs 5–15 mA. The file has no number for it; the bracket comes from DSP.Ear's 47 mW (≈ 12 mA), measured in 2014.

| Mode | Arithmetic | Battery per day |
|---|---|---|
| **Screen on / unlocked only** (Voice Access does this) | the phone is awake anyway: (0.4–1.9) + (5–15) mA × 4 h ≈ 22–68 mAh | **≈ 0.5–1.5%** |
| **Only while charging** | the charger pays | **≈ 0%** (a little heat) |
| **24/7, screen off too** | above, plus 20 h × (28.6 stay-awake + compute + recording) ≈ 34–46 mA → 680–920 mAh | **≈ 15–22%**, about double the normal overnight drain and ~20× Google's low-power path |

**What this means:**
- **The wake-word model is not the cost.** With the screen off, the cost is that the phone can never sleep. Of the 15–22%, 12.5% is the stay-awake current alone.
- **A cheaper model or a voice-activity gate saves almost nothing with the screen off.** The open mic keeps the system awake regardless.
- **The biggest unknown is the recording-path current on a Tensor chip**; no measurement was found. The `power_profile.xml` figures are Google's estimates. Measure before promising a number.

### 3.3 What Google Assistant / Gemini do differently [primary unless noted]
- **First stage on the low-power audio chip.** On the Pixel 8 this goes through the SoundTrigger HAL. shiba's `sound_trigger_configuration.xml` lists exactly three models: `CLIENT_HOTWORD` ("Hey Google") and two Now Playing models. There is no slot for anyone else's.
- **The main CPU sleeps until the chip fires.** Then a sandboxed second stage (`HotwordDetectionService`, plus Voice Match) checks the buffered audio.
- **No mic indicator while listening.** Since AOSP commit 22f37b8 (2021-08-30), "SoundTrigger events and hotword-source audio do not incur the mic indicator". The indicator appears only when a detection is delivered. (Android 12-era; whether later releases changed it is [UNCERTAIN].)
- **The permissions are system-only.** `CAPTURE_AUDIO_HOTWORD` and `MANAGE_SOUND_TRIGGER` come from the `SYSTEM_AMBIENT_AUDIO_INTELLIGENCE` role (`systemOnly`). `RECORD_BACKGROUND_AUDIO` comes from `SYSTEM_SHELL` (`systemOnly`) (`roles.xml`, 16 r4).
- **This may open up: the EU DMA decision of 16 Jul 2026.** It requires Google to give third-party assistants "always-on hotword detection", working even "if the device's display is turned off… or in standby", free of charge, not conditional on the default-assistant role, on devices that already have the chip.
  - **When:** in Android 18, by 1 Aug 2027 at the latest. Concurrent hotwords follow in Android 19, by 1 Aug 2028 [primary, EC DMA portal].
  - **Open questions [UNCERTAIN]:** whether it reaches phones outside the EU (the owner is in India), and whether it accepts our own model.
  - **Revisit then:** it would cut screen-off listening from ≈ 15–22%/day to about Now Playing's ≈ 1%.

## 4. Custom "Hey Friday": training effort and error rates

### 4.1 openWakeWord (recommended) [primary: README, docs]
- **The easy path, as published.** A Google Colab notebook "allows anyone to produce a custom model very quickly (<1 hour) and doesn't require any development experience, but the performance of the model may be low in some deployment scenarios." We don't use Colab: training runs on the owner's box (§4.4).
- **The full path.**
  - Data: "a minimum of several thousand" synthetic clips of the phrase, from open TTS voices. The bundled models used ~30,000 h of negative speech, noise and music.
  - Model: a small classifier trained on top of the frozen, shared feature extractor.
- **The shared extractor** is Google's `speech_embedding`, Apache-2.0, pre-trained on large data. It is why "100% synthetic" training works.
- **Accuracy target for its own models:** < 5% false-reject rate and < 0.5 false accepts per hour on the Dinner Party Corpus (5.5 h of far-field speech, music and noise), at the default threshold of 0.5.
- **Accents.** "Only English models are supported, but they should be reasonably robust across different types speaker accents" [primary]. The TTS voices are mostly US/UK, so test with the owner's own accent [UNCERTAIN].
- **Owner verifier (free, optional).**
  - A logistic regression on the same features.
  - It needs ≥ 3 owner clips of the phrase plus ≥ 10 s of other owner speech: "<5 minutes of effort".
  - It cuts false accepts from other voices and from TV.
  - It is "not intended to perform the task of speaker verification directly". The owner check stays a later step (D-021).

### 4.2 "Hey Friday", not "Friday"
- **Picovoice's guide** [primary]:
  - "Choosing a wake phrase with fewer than six phonemes is not recommended."
  - "A short wake word can be made more effective by prepending it with e.g. 'Hey', or 'OK'."
  - "Pick a phrase that has a variety of different sounds."
- **"Friday"** /f r aɪ d eɪ/ has 5 phonemes, and it is a weekday people say all the time ("see you Friday", TV).
- **"Hey Friday"** has 7 phonemes and three syllables. Sensory also suggests about three syllables [UNCERTAIN, search snippet].
- **Train with near-miss negatives:** "friday", "on friday", "friday night", "hey freddy", "fry day" (`custom_negative_phrases`).
- **Spelling variants as positives** only if the TTS mispronounces the phrase. Dicio needed "hey dicio" / "hey dicho" / …; "friday" is an ordinary word Piper says correctly [UNCERTAIN].

### 4.3 Training effort, by engine
| Engine | Effort | Notes |
|---|---|---|
| openWakeWord, community "Hey Friday" | **none.** Download a 207 KB file | Quality unknown; measure it first (§7) |
| openWakeWord, our own model | ~1.5–3 h of GPU plus ~26 GB of downloads, on the owner's GPU box (§4.4) | The official notebook "fails out of the box for at least eight separate reasons" per a patched fork (`alfiedennen/openwakeword-colab-2026`, MIT; ~75–90 min on a paid GPU) [secondary]. Dicio's config is a worked example [primary] |
| openWakeWord verifier on the owner | ~5 minutes of recording | §4.1 |
| microWakeWord | the same synthetic pipeline, but the result is "most likely not usable" without expert tuning | — |
| sherpa-onnx / local-wake | none (text / 3–4 recordings) | §1 |

### 4.4 Training recipe for the owner's GPU box (D-034; to run when the port is known — nothing was run)
**Rules (owner, 2026-10-17):**
- All training runs on the owner's Vast GPU box (`ssh -p <PORT> root@<GPU_BOX>`), **never on the Mac**.
- The address is not written here because this repo is public. The coordinator has it, and the port changes on every restart.
- Work only in `/root/friday-laya/wakeword/`, with its own venv.
- No global installs, and never kill anyone else's processes.
- Check `nvidia-smi` before starting (D-034).

```sh
mkdir -p /root/friday-laya/wakeword && cd /root/friday-laya/wakeword
df -h . && nvidia-smi                                  # need ~40 GB free and an idle GPU
python3 -m venv .venv && . .venv/bin/activate
git clone https://github.com/dscripka/openWakeWord
git clone https://github.com/rhasspy/piper-sample-generator
git clone https://github.com/alfiedennen/openwakeword-colab-2026   # patched steps, if the official ones break
pip install -e ./openWakeWord   # plus the training deps listed in the notebook (pinned versions from the patched fork)
wget -P piper-sample-generator/models https://github.com/rhasspy/piper-sample-generator/releases/download/v2.0.0/en_US-libritts_r-medium.pt  # voice used by the official notebook
# Data (~26 GB, all on the box):
#   huggingface.co/datasets/davidscripka/openwakeword_features:
#     openwakeword_features_ACAV100M_2000_hrs_16bit.npy (17.3 GB, CC BY-NC-SA) + validation_set_features.npy (~185 MB)
#   MIT room impulse responses -> ./mit_rirs; one AudioSet tar -> ./audioset_16k; FMA small -> ./fma (16 kHz wav)
```

- **Config** `hey_friday.yml`: start from Dicio's `meta/openwakeword_training_config.yml` and change these fields:
  - `model_name: hey_friday`
  - `target_phrase: ["hey friday"]`
  - `custom_negative_phrases: ["friday", "on friday", "friday night", "hey freddy", "fry day"]`
  - Keep `n_samples: 50000`, `n_samples_val: 2000`, `steps: 120000`, `target_false_positives_per_hour: 0.2`, `target_recall: 0.7`, `model_type: dnn`, `layer_size: 32`.
- **Run:**

```sh
python openWakeWord/openwakeword/train.py --training_config hey_friday.yml --generate_clips
python openWakeWord/openwakeword/train.py --training_config hey_friday.yml --augment_clips
python openWakeWord/openwakeword/train.py --training_config hey_friday.yml --train_model   # writes hey_friday.onnx (~0.2 MB)
```

- **Time:** ≈ 1.5–3 h on one GPU, plus the downloads [UNCERTAIN]. Skip `--convert_to_tflite`; Friday uses ONNX.
- **Evaluate on the box.**
  - Score the false-accept rate on held-out negatives with the openWakeWord Python model.
  - Score the same 10 WAV files used for the phone parity test (§7 item 6).
  - If the owner records 30–50 clips, score the community and our model on them (the false-reject rate). The clips stay in `wakeword/owner/` and are deleted after, like Teach data (D-033).
- **Bring back only the model:** `scp -P <PORT> root@<GPU_BOX>:/root/friday-laya/wakeword/my_custom_model/hey_friday.onnx .` (a ~0.2 MB file, fine for the Mac), then push it to the phone with `setup_phone.py`.

## 5. Recommended design

### 5.1 Engine
- **openWakeWord with a "Hey Friday" model**, run in the ONNX Runtime that Friday already ships. It is free, and Friday stays offline with no INTERNET permission.
  - **Start** with the community `hey_Friday!.onnx`. Its interface matches: `[1,16,96]` in, `[1,1]` out [primary, read here].
  - **Replace it** with our own GPU-box model (§4.4) if §7 shows too many misses or false wakes.
- **The pipeline**, every 80 ms (1,280 samples at 16 kHz) [primary: `openwakeword/utils.py`]:
  1. **Mel features.** `melspectrogram.onnx` (input `input`, float32 `[1, n]` of raw int16 values) runs on the last 1,760 samples, gives 8 frames × 32 mel bins, then `x/10 + 2`.
  2. **Embedding.** `embedding_model.onnx` (input `input_1`, `[1, 76, 32, 1]`, the last 76 mel frames) gives one 96-d embedding.
  3. **Score.** `hey_friday.onnx` reads the last 16 embeddings (`[1, 16, 96]`, 1.28 s) and returns a score from 0 to 1.
  4. **Fire** when the score is ≥ threshold for `patience` consecutive frames. Then wait about 2 s before it can fire again.
- **Free upgrade later: a verifier model on the owner's voice.** It is a logistic regression over the same embeddings. It needs ≥3 owner clips plus ~10 s of other speech, "<5 minutes of effort", and cuts false accepts from other voices [primary: openWakeWord docs]. It is a filter, not speaker verification; that stays a later step (D-021).

### 5.2 Where it runs
- **Phase A, the smallest version: inside `AgentService`.** No new service and no notification.
  - One new class, `WakeWord.kt`. It owns the `AudioRecord` (16 kHz, mono, 16-bit, `VOICE_RECOGNITION`), three ORT sessions (1 thread each, arena off as in D-023), a 2 s ring buffer, and the score loop on one thread.
  - **When it listens:** while the phone is unlocked, and while it is charging (screen off included, since the charger pays).
    - It starts on `ACTION_USER_PRESENT`, or at connect if the phone is already unlocked.
    - It stops on `ACTION_SCREEN_OFF` unless charging; power connect and disconnect toggle it.
    - Voice Access, Google's own accessibility voice app, also stops listening when the phone is locked.
  - This relies on §2.2: the accessibility binding gives the process the mic capability. **Gate:** §7 item 1 must show real audio from a backgrounded Friday, with the screen on and off.
- **Phase B, only if the gate fails:** wrap the same `WakeWord` in a `microphone` foreground service.
  - `AgentService` starts it, which §2.2 allows from the background.
  - It shows a notification with a **Stop** action.
  - Android 17 also needs this for a spoken reply (§2.7).
- **One switch for the owner in v1:** "Listen for 'Hey Friday'", meaning unlocked + charging.
  - **Skipped:** 24/7 listening on battery (≈ 15–22%/day, §3.2). Add it as an opt-in only if the owner asks after seeing the measured number.

### 5.3 On "Hey Friday"
- A haptic tick (`VibrationEffect.EFFECT_TICK`; no chime, see §2.7).
- Open Friday's request box with the keyboard up (`MainActivity`, `NEW_TASK`). The accessibility service may start activities from the background (research/10).
- When STT lands (next step), the same `AudioRecord` keeps running and hands the post-trigger audio to STT, so the mic isn't reopened. The 2 s pre-roll buffer is also what owner-voice verification will score.

### 5.4 Pauses (no audio processed)
- **Call or VoIP:** `AudioManager` mode `IN_CALL` / `IN_COMMUNICATION`. Watch it with `addOnModeChangedListener`, which is API 31, so guard it (minSdk is 30; the Pixel 8 is on 36). With the screen on, the platform would otherwise let Friday hear calls (§2.3).
- **Silenced by the system:** `isClientSilenced()`, or an all-zero stream. This covers the mic toggle and, with the screen off, Gemini, camera recording and VoIP.
- **While Friday is running a request,** so it can't trigger itself.
- **Battery saver on, or battery < 15% and not charging.**

### 5.5 Privacy (audio never leaves the phone, nothing stored)
- **No INTERNET permission.** Neither the merged manifest (build of 2026-10-16) nor today's working-tree manifest has one [primary]. Keep it out, and check it with `aapt dump permissions` on every build. This guarantee is stronger than any setting.
- **Audio stays in RAM.** The ring buffer (2 s ≈ 64 KB) is overwritten every 80 ms and zeroed on stop. No audio, feature or score is written to disk or logs. Only a count of detections is kept, for tuning.
- **The green mic dot is on whenever Friday listens** (§2.6). The owner always knows.
- **Models live in `filesDir`**, pushed by `setup_phone.py` like Laya's. Retraining means pushing a new file, not rebuilding the APK.

### 5.6 Power budget
- **Default (unlocked) mode:** ≤ 2%/day, estimated at 0.5–1.5%.
- **Charging-only mode:** 0%.
- **24/7:** ≈ 15–22%/day. Not built in v1; built only if the owner asks after seeing the measured number.
- **CPU:** one ORT thread, no Friday wakelock, and models of a few MB, loaded only while listening and freed on stop. Laya's budget is untouched (D-016, D-030).

### 5.7 Integration steps (Kotlin, AGP 9, minSdk 30, arm64; no new Gradle dependency)
1. **Model:** start with the community "Hey Friday" model. Train our own **only on the owner's GPU box** (§4.4, D-034), never on the Mac, if §7 says it's needed. The owner's own clips stay on the box and are deleted after (D-033).
2. **Manifest:** add `RECORD_AUDIO`. Nothing else for Phase A.
   - Phase B adds `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, and a `<service android:foregroundServiceType="microphone" android:exported="false">`.
   - Never add `INTERNET`.
3. **Grant once:**
   - `MainActivity` asks for RECORD_AUDIO when the owner turns on "Listen for 'Hey Friday'" (§5.2).
   - Alternatively, `setup_phone.py` runs `adb shell pm grant dev.layatest.agent android.permission.RECORD_AUDIO`.
4. **Models:** `setup_phone.py` pushes `melspectrogram.onnx`, `embedding_model.onnx` and `hey_friday.onnx` to `filesDir/wakeword/`. `WakeWord` checks they exist, like `LayaEngine`.
5. **`WakeWord.kt`** (new, about 150 lines):
   - `AudioRecord(VOICE_RECOGNITION, 16000, MONO, PCM_16BIT)` with a 2 s buffer, read in 1,280-sample blocks on one thread.
   - The three sessions get `setIntraOpNumThreads(1)` and `setCPUArenaAllocator(false)`.
   - Buffers as in §5.1: 1,760-sample mel window, 76 × 32 mel ring (start: ones), 16 × 96 embedding ring.
   - Skip the first 5 scores after start, as openWakeWord does.
   - Threshold 0.5 with patience 2 to begin with; tune on the owner's clips.
   - Register an `AudioRecordingCallback` for `isClientSilenced`.
   - `close()` zeroes the buffers.
6. **`AgentService`:**
   - In `onServiceConnected`, register a runtime receiver for `ACTION_USER_PRESENT`, `ACTION_SCREEN_OFF`, `ACTION_POWER_CONNECTED` and `ACTION_POWER_DISCONNECTED`, plus `AudioManager.addOnModeChangedListener`, and start or stop `WakeWord` per §5.2 and §5.4.
   - Close it in `onDestroy`.
   - `onWake` → a haptic tick, then `startActivity(MainActivity, NEW_TASK)`.
   - Skip while `running` (a request is in progress).
7. **Bridge test commands** (house pattern):
   - `{"cmd":"wake_score","wav":"<filesDir path>"}` returns per-frame scores, for parity with Python openWakeWord.
   - `{"cmd":"wake_stats"}` returns listening, silenced, frames, detections and ms/frame.
8. **Phase B, only if needed:** `WakeService` (a `microphone` foreground service with a Stop action) holds the same `WakeWord`. `AgentService` starts it instead of running `WakeWord` itself.

## 6. Risks (ranked)
1. **False wakes from "Friday" in speech and TV.** It is a common word, and synthetic-only notebook models "may be low" in some settings [primary, README].
   - **Mitigations:** say "Hey Friday", tune the threshold and patience on the owner's own audio, then add the free owner-voice verifier (§5.1).
   - **A false wake costs little.** It only opens the request box; nothing acts without the owner (D-025).
2. **The accessibility mic path is source-read, not measured** (§2.2). Pixel builds can differ from AOSP.
   - **Fallback:** the `microphone` foreground service, which is the documented way.
   - **If neither works:** the zero-power triggers (accessibility button, Quick Settings tile).
3. **Screen-off listening on battery costs ≈ 15–22%/day** (§3.2), so v1 doesn't do it. The recording-path current is unmeasured.
4. **Our Kotlin pipeline could drift from openWakeWord's Python** (input scaling, the mel transform, buffer edges). Dicio's /32768 scaling is the example to avoid (§1). Gate it with a score-parity test on WAV files, as research/06–07 did for Laya.
5. **The Mac bench conflicts with listening.** `uiautomator dump` unbinds every accessibility service (research/02). That stops listening, and in Phase A it also takes the mic capability away. Don't run the dump path while testing the wake word.
6. **Licensing.** openWakeWord's bundled models and its negative-training features are CC BY-NC-SA 4.0 [primary]. That is fine for a personal app and must be re-checked before Friday is ever shared or sold.
7. **Training is fragile.** The official notebook is reported broken (a patched fork exists), and our own model's accuracy is unknown until §7. Start with the community model.
8. **Android 17** silences background playback (§2.7). Wake feedback is haptic, so the wake word is unaffected; a spoken reply later will need a foreground service.

## 7. Checklist: turn this into [measured] (needs the phone; not run)
1. **Gate for Phase A** (§2.2).
   - **Setup:** RECORD_AUDIO granted. Friday in the background, with another app on top and the screen on.
   - **Check:** `AudioRecord` RMS is non-zero and `isClientSilenced()` is false. The recording list in `adb shell dumpsys audio` should agree [UNCERTAIN which dump shows it].
   - **Then screen off:** repeat with the screen off and `adb shell dumpsys deviceidle force-idle` for 10 min.
   - **If silent:** go to Phase B, the foreground service.
2. **Concurrency** (§2.3).
   - **Screen on:** record a WhatsApp voice note and open Gemini. The other app should work normally, because Friday never masks it, and Friday should keep hearing (the "accessibility on top" rule).
   - **Screen off:** during a VoIP call, `isClientSilenced()` should be true.
   - **Phone call:** Friday pauses itself and processes no frames.
   - **Mic toggle off:** silenced.
3. **Indicator.** The green dot shows exactly while Friday listens.
4. **Power** (§3.2).
   - **Method:** read `dumpsys battery` "Charge counter" (µAh) over 2 h, screen off, with listening off and then always-on. Do a 30 min screen-on run with and without listening. Use fixed brightness, Wi-Fi only, and the same room.
   - **Report** mA per mode, and check `dumpsys batterystats` for `AudioIn` charged to Friday's uid.
   - **Go/no-go:** "unlocked" mode should add < 2%/day.
5. **CPU.** Measure the wake-word thread's share of a core (`top -H`), and the ms per 80 ms frame through `{"cmd":"wake_stats"}`.
6. **Parity.** `{"cmd":"wake_score"}` on 10 WAV files (5 positive, 5 negative) should give max |Δscore| < 0.01 against Python openWakeWord, run on the GPU box (or on the Mac as one small process).
7. **Accuracy** (community model first, then ours).
   - **False rejects:** the owner says "Hey Friday" 50 times at 0.3 m, 1 m and 3 m, quiet and with TV on. Target ≤ 5–10%.
   - **False accepts:** 3 h of TV and podcasts, then a normal day in "unlocked" mode. Count wakes only; store no audio. Target ≤ 1 per 10 h of listening.

## Plan (D-035)
- **Engine:** openWakeWord in Friday's existing ORT. Start with the community "Hey Friday" model, then our own, trained on the GPU box only (§4.4). Free, offline, and no INTERNET permission.
- **Where:** inside the accessibility service (Phase A), listening while the phone is unlocked and while charging. If §7 item 1 fails, use a microphone foreground service (Phase B).
- **On wake:** haptic tick, then the request box. Later, STT, which reuses the same audio stream.
- **Privacy:** RAM only, no audio stored, green dot on while listening, no network.
- **Not now:** 24/7 screen-off listening (≈ 15–22%/day), the platform hotword path (closed), and becoming the default assistant.
- **Revisit:** when Android 18 ships the EU-mandated third-party hotword API (§3.3).

## Dead ends
- **The platform hotword path** (`AlwaysOnHotwordDetector`, `HotwordDetectionService`, SoundTrigger, the low-power audio chip). These are system APIs with system-only permissions. Pixel 8's chip carries only Google's three models [primary].
- **Becoming the default assistant for mic access.** It adds no mic privilege beyond the accessibility binding (same bind flags) and switches off "Hey Google" [primary/secondary]. It remains an option only for the hold-power-button trigger.
- **A boot receiver that starts a microphone foreground service.** This is banned for target ≥ 35 [primary], and not needed: the accessibility service re-binds after unlock.
- **A voice-activity gate to save screen-off power.** The open mic keeps the phone awake anyway, so the saving is close to zero (§3.2).
- **Duty-cycling the mic** (listen 1 s in every N). It misses wake words, and each reopen wakes the phone again.
- **Root or Snapdragon DSP hacks** (e.g. JPInert/android-dsp-wakeword). They need root and a Qualcomm chip [secondary].
- **Porcupine.** No personal or free plan any more, the key is checked at startup, and it needs INTERNET (§1) [primary].
- **Sensory.** Prototype-only free tier [secondary].
- **LiteRT / TF keyword models.** No "Friday": 35 fixed words or sound classes.
- **Snowboy** (company shut down, idle since 2021), **Mycroft Precise** (idle since 2023; Mycroft is gone), **Howl** (dead), **Nyumaya** (idle since 2024; custom words from the vendor), **DaVoice** (custom words by email; no free licence published) [secondary].
- **PocketSphinx** (52% missed in Picovoice's benchmark), **EfficientWord-Net** (heavy, no Android), **Rhasspy Raven** (tied to one speaker, sensitive to noise) [secondary].
- **Vosk as a wake word.** It is full speech-to-text (~50 MB, far more CPU). It belongs in the STT study, not here.
- **Not dead, just later:**
  - microWakeWord: tiny, but training is hard and it scored poorly in the independent benchmark.
  - sherpa-onnx KWS: a fallback with no training, but a duplicate `libonnxruntime.so`.
  - local-wake: the best benchmark numbers from 3–4 owner recordings, but Python only.

## Sources
- **AOSP `android-16.0.0_r4`.**
  - frameworks/base:
    - `services/accessibility/.../AccessibilityServiceConnection.java`
    - `AccessibilityManagerService.java`
    - `services/core/.../am/ActiveServices.java`, `ServiceRecord.java`, `OomAdjusterImpl.java`
    - `services/core/.../wm/BackgroundLaunchProcessController.java`
    - `core/java/android/app/BackgroundStartPrivileges.java`, `ForegroundServiceTypePolicy.java`
    - `services/core/.../appop/AppOpsUidStateTrackerImpl.java`
    - `services/core/.../power/PowerManagerService.java`
    - `services/voiceinteraction/.../VoiceInteractionManagerService.java`, `VoiceInteractionManagerServiceImpl.java`
    - `core/res/AndroidManifest.xml`
    - `core/java/android/permission/PermissionManager.java`
    - `packages/SystemUI/.../appops/AppOpsControllerImpl.java`
  - frameworks/av: `services/audiopolicy/service/AudioPolicyService.cpp`, `services/audioflinger/Threads.cpp`.
  - packages/modules/Permission: `PermissionController/res/xml/roles.xml`.
  - AOSP commit 22f37b8 (frameworks/base).
- **Pixel 8 device tree** (`device/google/shusky`, main): `shiba/overlay/.../power_profile.xml`, `audio/shiba/config/sound_trigger_configuration.xml`.
- **SDK 36 `android.jar`** (javap) and `platforms/android-37.0/data/api-versions.xml`.
- **developer.android.com:**
  - `develop/background-work/services/fgs/restrictions-bg-start`
  - `media/platform/sharing-audio-input`
  - `about/versions/17/changes/bg-audio`
  - `guide/topics/ui/accessibility/service`
- **Google support (Voice Access):** support.google.com/accessibility/android/answer/6151848 and /6377053.
- **Home Assistant:** home-assistant.io/voice_control/android/; openhomefoundation.org/blog/a-big-win-for-android-interoperability/.
- **EC DMA:** digital-markets-act.ec.europa.eu/developer-portal/interoperability/alphabet-specification-proceedings-interoperability-ai-services_en.
- **Papers:**
  - arXiv 1711.10958 (Now Playing)
  - arXiv 1409.3206 (DSP.Ear)
  - arXiv 2005.06720 (streaming KWS on mobile)
- **GrapheneOS forum:** discuss.grapheneos.org/d/10546.
- **openWakeWord:**
  - github.com/dscripka/openWakeWord (README, `openwakeword/utils.py`, `model.py`, `train.py`, `examples/custom_model.yml`, `docs/custom_verifier_models.md`)
  - huggingface.co/datasets/davidscripka/openwakeword_features
  - Patched notebook: github.com/alfiedennen/openwakeword-colab-2026
  - Community model: github.com/fwartner/home-assistant-wakewords-collection/tree/main/en/hey_friday
- **Dicio:**
  - github.com/DicioTeam/dicio-android: `meta/openwakeword_training_config.yml`, `.../io/wake/WakeService.kt`, `.../io/wake/oww/OpenWakeWordDevice.kt`
  - issues #237, #363, #407, #453
- **Picovoice:**
  - picovoice.ai/docs/faq/general/
  - picovoice.ai/docs/tips/choosing-a-wake-word/
  - github.com/Picovoice/porcupine (Android demo manifest; v1.9 README licence)
  - github.com/Picovoice/wake-word-benchmark
  - community.home-assistant.io/t/fyi-picovoice-confirmed-free-tier-accesskeys-will-stop-working-after-june-30-2026/1012744
- **Independent benchmark:** gitlab.com/Jaco-Assistant/Benchmark-KeywordSpotting (README).
- **microWakeWord:**
  - github.com/OHF-Voice/micro-wake-word
  - README at github.com/kahrendt/microWakeWord/blob/577e5d4c99/README.md
  - github.com/esphome/micro-wake-word-models
  - HA Android module: github.com/home-assistant/android/tree/HEAD/microwakeword
- **sherpa-onnx:**
  - k2-fsa.github.io/sherpa/onnx/kws/
  - github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8
  - soniqo.audio/guides/wake-word (port numbers)
- **Others:**
  - github.com/st-matskevich/local-wake
  - github.com/livekit/livekit-wakeword
  - Sensory VoiceHub (sensory.com news, 2020; voicebot.ai 2020-10-21)
