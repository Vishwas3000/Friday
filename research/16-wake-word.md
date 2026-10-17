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
0. **Update, same day: built and measured on the Pixel 8 (§8) [measured].**
   - The community model fires on both "hey friday" and "hi friday". Its weak spot is near misses ("friday", "hey freddy").
   - The owner ruled out an always-on mic after seeing the green dot. Listening is now **opt-in and only while charging**.
   - Every launch of Friday opens ready to type, so "Hey Google, open Friday", Quick Tap and the icon need no mic of Friday's own.
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
_Superseded by §4.5, the checked recipe for one model on both phrases; this sketch would fail as written._
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

### 4.5 To train on the box: one model for "hey friday" and "hi friday"
_Researched 2026-10-17. Nothing was run on the box or the phone. Tags: [primary] read at source · [secondary] other repos or posts · [measured] checked on the Mac (the package set was resolved for Linux, the config was parsed, the scripts were syntax-checked and their logic tested on synthetic data; no model code ran) · [UNCERTAIN] not confirmed._

- **Why train at all.** The community model already fires on both phrases, but it also fires on near misses (§8.4). This model adds them as negatives.
- **This replaces the §4.4 sketch, which would fail as written.** Upstream piper-sample-generator, today's scipy, a missing `onnx` package and a dead AudioSet link each break the run (see Pitfalls).

**Rules (owner; D-034, D-042)**
- **Where:** only on the owner's Vast box, `ssh -p <PORT> root@<GPU_BOX>`. The address is not written here, and the port changes on every restart.
- **Isolation:** everything lives in `/root/friday-laya/wakeword/`, with its own uv, Python, venv, uv cache and HF cache.
- **No global installs:** no global pip, apt, driver or shell-rc changes. Nothing below needs apt. If `git` is missing, ask the owner; the only alternative is an apt install.
- **Check first:** read `nvidia-smi` and `df -h` before starting.
- **Shared box:** never kill other people's processes, and leave VRAM for their jobs.
- **What comes back:** only the ~0.2 MB `.onnx` returns to the Mac.

**What the recipe rests on**
- **One model, both phrases.** `target_phrase` takes a list and trains one binary output. The example config says: "Adding multiple unique words/phrases will still only train a binary model ... it will activate on any one of the provided words/phrases" [primary: `examples/custom_model.yml`].
- **How `n_samples` is split.**
  - Positives: Piper walks the text list in order (`it.cycle(text)`), so they alternate: 25,000 "hey friday" and 25,000 "hi friday" for `n_samples: 50000`.
  - Negatives: train.py asks `generate_adversarial_texts` for `n_samples // len(target_phrase)` phonetic look-alikes per phrase. About 5% of them are "friday" alone, and some are "hey" or "hi" alone [primary: `train.py` L676–713, `data.py`].
- **The ONNX export.** `--train_model` writes `<output_dir>/<model_name>.onnx` via `torch.onnx.export(model, torch.rand(1,16,96), …, opset_version=13)`.
  - There are no dynamic axes, and the sigmoid is inside the model, so the interface is `[1,16,96]` → `[1,1]`, with scores from 0 to 1. That is what `WakeDetector` loads.
  - The input is 16 frames because clips are padded to 32,000 samples, unless the median TTS clip runs over ~1.75 s. Our phrases are ~1 s [primary: `export_model`, L754–758].
  - There is no tflite step once patch 3 below is applied.
- **Keys train.py ignores.** It never reads `target_recall` or `target_accuracy`, although the notebook and Dicio set them. Only `steps`, `max_negative_weight` and `target_false_positives_per_hour` steer `auto_train` [primary].
- **Same runtime as the app.** `onnxruntime==1.20.1` on the box is the app's ORT version.

#### 0. Recon (read-only)
```sh
nvidia-smi
nvidia-smi --query-gpu=index,name,driver_version,memory.used,memory.total --format=csv
nvidia-smi --query-compute-apps=pid,process_name,used_memory --format=csv   # other people's jobs: leave them alone
df -h /root; free -g; nproc
```
- **Go only if:** ≥ 45 GB of free disk, ≥ 8 GB of free VRAM on one GPU, and ≥ 16 GB of RAM.
- **More than one GPU:** pick an idle one with `export CUDA_VISIBLE_DEVICES=<index>` in every shell, before step 4.

#### 1. Setup (~5–10 min)
```sh
mkdir -p /root/friday-laya/wakeword && cd /root/friday-laya/wakeword
cat > env.sh <<'EOF'
cd /root/friday-laya/wakeword
export UV_CACHE_DIR=$PWD/.uv-cache UV_PYTHON_INSTALL_DIR=$PWD/.uv-python HF_HOME=$PWD/.hf PYTHONUNBUFFERED=1 PATH=$PWD/.uv-bin:$PATH
if [ -f .venv/bin/activate ]; then . .venv/bin/activate; fi
EOF
. ./env.sh                                                                     # re-run this in every new shell
curl -LsSf https://astral.sh/uv/install.sh | env UV_INSTALL_DIR=$PWD/.uv-bin UV_NO_MODIFY_PATH=1 sh
uv venv --python 3.11 .venv && . ./env.sh       # 3.11: piper-phonemize has Linux wheels only for 3.9-3.11

cat > requirements.txt <<'EOF'
torch==2.7.1
torchaudio==2.7.1
numpy==1.26.4
scipy==1.13.1
onnx==1.17.0
protobuf==5.29.5
onnxruntime==1.20.1
piper-phonemize==1.1.0
webrtcvad-wheels==2.0.14.post1
speechbrain==1.0.3
audiomentations==0.33.0
torch-audiomentations==0.11.0
acoustics==0.2.6
pronouncing==0.2.0
mutagen==1.47.0
torchinfo==1.8.0
torchmetrics==1.7.4
pyyaml==6.0.2
soundfile==0.13.1
pyarrow==17.0.0
huggingface_hub==0.30.2
scikit-learn==1.5.2
pandas==2.2.3
matplotlib==3.9.2
tqdm==4.67.1
requests==2.32.3
EOF
uv pip install --torch-backend=auto -r requirements.txt   # torch 2.7.1 from the newest CUDA index the driver allows that has it (cu128 on recent drivers)

git clone https://github.com/dscripka/openWakeWord
git -C openWakeWord checkout 368c03716d1e92591906a84949bc477f3a834455             # main, 2025-12-30
git clone https://github.com/rhasspy/piper-sample-generator
git -C piper-sample-generator checkout 9c1019c932eda098aa70d63cdeac803dea983f53    # last pre-v3 commit, 2024-02-27
uv pip install --no-deps -e ./openWakeWord     # --no-deps skips ai-edge-litert (tflite) and speexdsp-ns; neither is used

# Three one-line patches; each pattern occurs exactly once (see Pitfalls 3, 6, 7)
sed -i 's/torch.load(model_path)/torch.load(model_path, weights_only=False)/' piper-sample-generator/generate_samples.py
sed -i 's/num_workers=n_cpus, prefetch_factor=16)/num_workers=0)/' openWakeWord/openwakeword/train.py
sed -i 's/if args.convert_to_tflite:/if args.convert_to_tflite is True:/' openWakeWord/openwakeword/train.py
git -C openWakeWord diff --stat; git -C piper-sample-generator diff --stat        # 1 file each

# Front-end models (the same v0.5.1 files the app uses) and the Piper LibriTTS-R generator (204,089,915 B)
M=openWakeWord/openwakeword/resources/models && mkdir -p $M
for f in melspectrogram.onnx embedding_model.onnx; do curl -fL -o $M/$f https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/$f; done
curl -fL -o piper-sample-generator/models/en_US-libritts_r-medium.pt https://github.com/rhasspy/piper-sample-generator/releases/download/v2.0.0/en_US-libritts_r-medium.pt
```
[measured] This exact package set resolves for Linux x86_64 / Python 3.11 with no conflicts (96 packages). It is not install-tested on CUDA; step 4 catches that in about a minute.

#### 2. Data (~19 GB download)
```sh
B=https://huggingface.co/datasets/davidscripka/openwakeword_features/resolve/main
curl -fL -C - -O $B/openwakeword_features_ACAV100M_2000_hrs_16bit.npy   # 17,280,000,128 B: (5625000,16,96) float16 negatives, CC BY-NC-SA 4.0
curl -fL -C - -O $B/validation_set_features.npy                          # 184,836,608 B: (481345,96) float32 = 10.7 h
python -c "from huggingface_hub import snapshot_download as d; d('davidscripka/MIT_environmental_impulse_responses', repo_type='dataset', local_dir='mit_rirs', allow_patterns=['16khz/*.wav'])"
ls mit_rirs/16khz | wc -l                                                 # 271 RIRs, already 16 kHz (8 MB)
for n in 00 01; do curl -fL -C - -o bal_train$n.parquet https://huggingface.co/datasets/agkphysics/AudioSet/resolve/main/data/bal_train/$n.parquet; done   # ~0.69 GB each
cat > audioset_to_wav.py <<'EOF'
# AudioSet parquet shard(s) -> 16 kHz mono 16-bit WAVs in ./audioset_16k (background noise for augmentation)
import io, sys, pathlib, pyarrow.parquet as pq, soundfile as sf
from scipy.signal import resample_poly
out = pathlib.Path("audioset_16k"); out.mkdir(exist_ok=True)
for shard in sys.argv[1:]:
    t = pq.read_table(shard, columns=["video_id", "audio"])
    for vid, a in zip(t.column("video_id").to_pylist(), t.column("audio").to_pylist()):
        try:
            x, sr = sf.read(io.BytesIO(a["bytes"]), dtype="float32", always_2d=True)
        except Exception as e:  # skip the odd empty/corrupt clip
            print("skip", vid, e); continue
        sf.write(out / f"{vid}.wav", resample_poly(x.mean(axis=1), 16000, sr), 16000, subtype="PCM_16")
print(len(list(out.glob("*.wav"))), "background wavs")
EOF
python audioset_to_wav.py bal_train00.parquet bal_train01.parquet && rm bal_train0?.parquet   # ~1,100 clips of 10 s [UNCERTAIN count]
```
- **Sizes, shapes and dtypes** were read from the files' `.npy` headers and HF file listings [primary].
- **The 271 RIRs** are 24-bit, 16 kHz, mono [primary].
- **AudioSet columns** are `video_id` and `audio{bytes,path}` [primary].

#### 3. Config `friday.yml`
```sh
cat > friday.yml <<'EOF'
# One binary model that fires on both phrases (train.py alternates TTS clips between them).
model_name: hey_friday                  # -> my_custom_model/hey_friday.onnx
target_phrase: ["hey friday", "hi friday"]
# Near misses that must NOT fire. Listed once here; the next command repeats them x60,
# because train.py synthesizes each list entry only once.
custom_negative_phrases: ["friday", "on friday", "friday night", "happy friday", "see you friday",
  "this friday", "fry day", "hey freddy", "hi freddy", "high five", "hi fido", "hey siri",
  "hey google", "hey there", "hi there", "hey friend"]
n_samples: 50000                        # positives (25k per phrase) and adversarial negatives
n_samples_val: 2000
tts_batch_size: 50                      # negatives use 50//7 = 7; raise only if the GPU is otherwise idle
augmentation_batch_size: 16
augmentation_rounds: 1
piper_sample_generator_path: ./piper-sample-generator
output_dir: ./my_custom_model
rir_paths: [./mit_rirs/16khz]           # only .wav files may be in these folders (no .cache)
background_paths: [./audioset_16k]
background_paths_duplication_rate: [1]
false_positive_validation_data_path: ./validation_set_features.npy
feature_data_files:
  ACAV100M_sample: ./openwakeword_features_ACAV100M_2000_hrs_16bit.npy
batch_n_per_class:                      # 1,124 rows per step
  ACAV100M_sample: 1024
  adversarial_negative: 50
  positive: 50
model_type: dnn
layer_size: 32
steps: 120000                           # sequence 1; sequences 2 and 3 add 12k each
max_negative_weight: 2700               # Dicio's value (example yml: 1500); higher = fewer false wakes
target_false_positives_per_hour: 0.2
EOF
python -c "import yaml; c = yaml.safe_load(open('friday.yml')); c['custom_negative_phrases'] = list(dict.fromkeys(c['custom_negative_phrases'])) * 60; yaml.safe_dump(c, open('friday.yml', 'w'), sort_keys=False)"   # idempotent
```
- **Checks [measured]:** the file supplies all 21 keys train.py reads, with no unused extras. The expansion is idempotent (16 phrases × 60 = 960), and no negative contains a target phrase.
- **Where the values come from:**
  - 50k samples, 2k validation samples, 120k steps, weight 2700, 0.2 false accepts/h, dnn with 32 units: Dicio's working config [primary].
  - 100k samples were no better than 50k, and 150k steps no better than 100k [secondary: atlas-voice-training].

#### 4. Preflight (~1 min; it catches everything that would otherwise kill the long run)
```sh
cat > preflight.py <<'EOF'
# ~1 min: fails on anything that would otherwise kill the long run (CUDA build, imports, TTS, ONNX export)
import sys, glob, wave; sys.path.insert(0, "piper-sample-generator")
import torch
assert (torch.ones(2, device="cuda") * 2).sum().item() == 4  # "no kernel image" here = wrong CUDA build for this GPU
print(torch.__version__, torch.version.cuda, torch.cuda.get_device_name(0))
from generate_samples import generate_samples
from openwakeword.data import generate_adversarial_texts
from openwakeword.train import Model
import onnxruntime as ort
print(generate_adversarial_texts("hi friday", 8, include_partial_phrase=1.0, include_input_words=0.2))
generate_samples(text=["hey friday", "hi friday"], output_dir="preflight_tts", max_samples=2, batch_size=2)
for f in glob.glob("preflight_tts/*.wav"):
    with wave.open(f) as w: assert (w.getframerate(), w.getnchannels(), w.getsampwidth()) == (16000, 1, 2), f
m = Model(n_classes=1, input_shape=(16, 96), model_type="dnn", layer_dim=32)
m.export_model(m.model, "preflight", ".")  # the same export call the real run makes
s = ort.InferenceSession("preflight.onnx")
assert s.get_inputs()[0].shape == [1, 16, 96] and s.get_outputs()[0].shape == [1, 1]
print("PREFLIGHT OK")
EOF
python preflight.py && rm -rf preflight_tts preflight.onnx
```

#### 5. Train (≈ 1–2 h, in the background)
```sh
cat > run.sh <<'EOF'
. /root/friday-laya/wakeword/env.sh
set -euo pipefail
T=openWakeWord/openwakeword/train.py
python $T --training_config friday.yml --generate_clips   # Piper: 50k pos + 50k adversarial neg + 2k + 2k WAVs; resumable
python $T --training_config friday.yml --augment_clips    # reverb/noise/background mix -> my_custom_model/hey_friday/*_features_*.npy
python $T --training_config friday.yml --train_model      # auto_train, then writes my_custom_model/hey_friday.onnx (opset 13)
EOF
nohup bash run.sh > train.log 2>&1 &
tail -f train.log
```
The output is `/root/friday-laya/wakeword/my_custom_model/hey_friday.onnx` (~0.2 MB, ~50k parameters). `--overwrite` only matters for re-running `--augment_clips`. `--convert_to_tflite` is not used.

#### 6. Check the ONNX interface
```sh
python - <<'EOF'
import onnxruntime as ort
s = ort.InferenceSession("my_custom_model/hey_friday.onnx"); i, o = s.get_inputs()[0], s.get_outputs()[0]
print(i.name, i.shape, i.type, "->", o.name, o.shape); assert i.shape == [1, 16, 96] and o.shape == [1, 1]
EOF
```
- **Input name:** torch generates it automatically. The Colab fork forces `onnx::Flatten_0`; the name here is [UNCERTAIN].
- **The app doesn't care.** `WakeDetector` reads `session.inputNames`, as openWakeWord's own `Model` does [primary].

#### 7. Evaluate on the box
1. **train.py's own numbers:** `grep -A2 "Final Model Accuracy" train.log`. It reports recall on the synthetic test clips at 0.5, and false positives per hour on the validation features at 0.5.
2. **The same false-accept check on the exported file**, plus wakes per hour under the app's rule (2 frames ≥ threshold, then a 2 s refractory). It takes ~30 s.
```sh
cat > eval_fa.py <<'EOF'
# False accepts of the exported ONNX on the ~10.7 h validation features (the set behind train.py's FP/hour)
import sys, numpy as np, onnxruntime as ort
s = ort.InferenceSession(sys.argv[1]); name = s.get_inputs()[0].name
f = np.load(sys.argv[2])                                          # (481345, 96) float32, one row per 80 ms
win = np.lib.stride_tricks.sliding_window_view(f, (16, 96))[:, 0]  # (N-15, 16, 96), no copy
p = np.array([s.run(None, {name: np.ascontiguousarray(x[None])})[0].item() for x in win])
h = len(f) * 0.08 / 3600

def wakes(p, t):  # app rule (research/16 §8.2): 2 frames >= t, then a 2 s (25-frame) refractory
    on = np.flatnonzero((p[1:] >= t) & (p[:-1] >= t))
    return int(np.sum(np.diff(on, prepend=-99) > 25))

assert wakes(np.array([0, .9, .9, .9, 0] + [0] * 30 + [.9, .9]), .5) == 2
for t in (0.5, 0.7, 0.9):
    print(f"threshold {t}: {np.sum(p >= t) / h:.2f} frames/h (train.py's metric), {wakes(p, t) / h:.2f} wakes/h")
EOF
python eval_fa.py my_custom_model/hey_friday.onnx validation_set_features.npy
```
3. **Folders of 16 kHz mono int16 WAVs**, scored through openWakeWord's own streaming `Model`, printing the maximum score per file:
```sh
cat > score_wavs.py <<'EOF'
# Max wake score per WAV (16 kHz, mono, 16-bit), through openWakeWord's own streaming pipeline
import sys, glob, os, wave, openwakeword
onnx_path, folder = sys.argv[1], sys.argv[2]
m = openwakeword.Model(wakeword_models=[onnx_path], inference_framework="onnx")
key = os.path.splitext(os.path.basename(onnx_path))[0]
for f in sorted(glob.glob(os.path.join(folder, "*.wav"))):
    with wave.open(f) as w:
        if (w.getframerate(), w.getnchannels(), w.getsampwidth()) != (16000, 1, 2):
            print("skip, not 16 kHz mono 16-bit:", f); continue
    m.reset()
    print(f"{max(p[key] for p in m.predict_clip(f)):.3f}  {os.path.basename(f)}")
EOF
python score_wavs.py my_custom_model/hey_friday.onnx my_custom_model/hey_friday/positive_test | sort -n | head    # weakest synthetic positives
python score_wavs.py my_custom_model/hey_friday.onnx my_custom_model/hey_friday/negative_test | sort -nr | head   # strongest near misses
```
4. **The owner's voice** (§7 targets):
   - Convert each recording on the Mac with `afconvert -f WAVE -d LEI16@16000 -c 1 in.m4a out.wav`. It is built into macOS [primary: `afconvert -h`].
   - Copy them over: `scp -P <PORT> *.wav root@<GPU_BOX>:/root/friday-laya/wakeword/owner/`.
   - Score: `python score_wavs.py my_custom_model/hey_friday.onnx owner`.
   - Then `rm -rf owner` (D-033).
   - The phone-side check is `uv run python scripts/wake_test.py DIR` with the new model built in.

#### 8. Bring back only the model (on the Mac, from the repo root)
```sh
scp -P <PORT> root@<GPU_BOX>:/root/friday-laya/wakeword/my_custom_model/hey_friday.onnx android/app/src/main/assets/wakeword/
ssh -p <PORT> root@<GPU_BOX> sha256sum /root/friday-laya/wakeword/my_custom_model/hey_friday.onnx; shasum -a 256 android/app/src/main/assets/wakeword/hey_friday.onnx
```
- **Update path:** the app bundles the models as assets (§8.2), so replace `hey_friday.onnx`, rebuild and run `setup_phone.py`. The shared front-end models don't change.
- **Licence note:** update `assets/wakeword/NOTICE.txt`. Our own model is trained on CC BY-NC-SA features.
- **Cleanup on the box:** `rm -rf my_custom_model/hey_friday/{positive_train,negative_train,negative_test}` frees ~3 GB.
  - Keep `positive_test`: train.py samples clip lengths from it on every run.
  - Keep the ACAV file and the features, so a retrain only needs `--train_model`.

#### Time and disk (4090/5090-class GPU; estimates)
| Step | Time | Disk |
|---|---|---|
| Setup (uv, Python 3.11, venv, clones, models) | 5–10 min | ~7 GB (venv and caches) + 0.2 GB |
| Downloads + AudioSet conversion | 5–30 min, bandwidth-bound | 17.5 GB, + 1.4 GB transient → 0.35 GB of WAVs |
| `--generate_clips` | ~20–40 min | ~2.5–3.5 GB (104k WAVs) |
| `--augment_clips` (CPU-bound) | ~15–45 min | ~0.65 GB of features (+0.3 GB transient) |
| `--train_model` (120k + 12k + 12k steps) | ~25–45 min | — |
| **Total** | **≈ 1.5–3 h** | **peak ≈ 31 GB, so have ≥ 45 GB free** |

- **Peaks:** VRAM ≈ 6 GB (TTS at batch 50; a 3 GB validation batch during training). RAM ≈ 8 GB. All of this is [UNCERTAIN].
- **Anchor:** about 1 h end to end on a 4090 at 50k samples and 100k steps [secondary: atlas-voice-training].

#### Pitfalls (all handled above)
1. **The Piper generator version.**
   - HEAD (v3.2) moved `generate_samples.py` into a package, so train.py's `from generate_samples import generate_samples` fails.
   - v3.0–3.1 require `model=` and write 22,050 Hz WAVs, which `augment_clips` rejects ("Clip does not have the correct sample rate").
   - Pre-v3 `9c1019c` defaults the model, resamples to 16 kHz and trims silence. v3's "Upgrade to torch 2" commit left `piper_train/` untouched, so the old model code is what upstream runs on torch 2 [primary].
2. **Python 3.11.**
   - piper-phonemize 1.1.0 has Linux wheels for cp39–cp311 only. It bundles espeak-ng, so no apt is needed [primary].
   - openWakeWord HEAD needs Python ≥ 3.10 [primary].
   - `webrtcvad` is source-only and needs gcc; `webrtcvad-wheels` provides the same module prebuilt [primary].
3. **The torch 2.7.1 pin.**
   - From torch 2.6, `torch.load` is weights-only by default; patch 1 handles that. `TORCH_FORCE_NO_WEIGHTS_ONLY_LOAD=1` also works.
   - torch 2.9 switches `torch.onnx.export` to the dynamo exporter, and torchaudio 2.9 changes audio loading. The May-2026 Colab fork patches `set_audio_backend` and `info` for that.
   - With torchaudio 2.7.1, `set_audio_backend` is a no-op and `info` still exists, so no patch is needed [primary].
4. **scipy must be below 1.17.** scipy 1.17 removed `scipy.special.sph_harm`. `acoustics` 0.2.6 imports it at load, and openWakeWord's `data.py` imports `acoustics`. With today's default scipy, train.py dies at startup [primary: scipy 1.17.0 release notes].
5. **`onnx` must be installed.**
   - torch 2.7's exporter always imports it, so without it the run dies at the final export.
   - train.py never saves the trained weights, so that run is lost.
   - The preflight exports a dummy model to catch this [primary].
6. **The tflite flag.** `--convert_to_tflite` defaults to the string `"False"`, which is truthy, and is tested with a bare `if`. Conversion therefore always runs, then crashes on the missing TensorFlow after the .onnx is written, with a non-zero exit. Patch 3 fixes it [primary].
7. **DataLoader workers.**
   - train.py starts `cpu_count()//2` workers on an un-sharded IterableDataset. PyTorch: "The same dataset object is replicated on each worker process" [primary], so on a 64-core box each batch repeats 32 times.
   - The workers also need `/dev/shm`. Docker's default is 64 MB, and atlas needed `--shm-size=32g` [secondary].
   - Patch 2 sets 0 workers. The training speed impact is small [UNCERTAIN].
8. **Near-miss negatives.**
   - train.py appends ~50k generated texts after `custom_negative_phrases`, and Piper walks that list once, so each listed phrase would get about 1 clip. The ×60 expansion gives ~60 each, about 2% of negatives [primary]. The ×60 multiplier is a judgment call [UNCERTAIN].
   - Side effect: train.py extends that list in place, so the 2,000 test negatives start with the 960 near misses. Checkpoint selection then weighs them more [primary].
9. **Dead or heavy notebook links.**
   - AudioSet's `bal_train09.tar` is gone; the repo now holds parquet shards.
   - `rudraml/fma` is a loading script that pulls the 7.2 GiB FMA zip through an old `datasets`.
   - So the recipe uses two AudioSet shards (music, speech, noise) and never needs `datasets` [primary].
10. **Data folders must hold only audio.** train.py lists every entry in `rir_paths` and `background_paths`, and a Hugging Face `.cache` folder there breaks augmentation. Hence the recipe points at `mit_rirs/16khz` [primary code; secondary].
11. **Resume rules.**
    - `--generate_clips` skips a folder once it is ≥ 95% full. After changing phrases, delete `my_custom_model/hey_friday/negative_*`.
    - `--augment_clips` skips whenever `positive_features_train.npy` exists, even half-written after a crash. Re-run it with `--overwrite`.
    - `--train_model` always restarts from zero [primary].
12. **The printed numbers are optimistic.**
    - The printed FP/hour uses the same file that picks checkpoints, and divides by a hard-coded 11.3 h; the file is 10.7 h.
    - Recall is measured on synthetic TTS clips.
    - The owner's real audio (§7) is the real test [primary].
13. **New GPUs.**
    - RTX 50xx (Blackwell) needs a cu128 build. With `--torch-backend=auto`, uv searches every CUDA index the driver allows for torch 2.7.1, so recent drivers get cu128 [primary: uv source].
    - If the preflight shows "no kernel image", run `uv pip install --torch-backend=cu126 torch==2.7.1 torchaudio==2.7.1`.
14. **Small quirks to ignore.**
    - The generator's homophone filter compares a string to a list, so "hay friday" and "high friday" slip into the negatives as ~0.1% label noise.
    - train.py's INFO lines only print because Piper sets DEBUG logging, so `train.log` is noisy [primary].
15. **Licences.** The ACAV features are CC BY-NC-SA 4.0 and AudioSet is CC BY 4.0. Both are fine for a personal app; re-check before sharing (risk 6) [primary].

**Not checked:**
- Nothing was installed or run on Linux/CUDA, so only step 4 proves these packages work together on the box.
- Left out: FMA background music, the tflite export and the owner verifier.
- The ×60 near-miss weight and all timings are estimates.
- **The biggest risk is upstream drift:** an unpinned piper-sample-generator or scipy already breaks the official path. Keep the pins.

**Sources (§4.5):**
- **openWakeWord @ `368c037`:** github.com/dscripka/openWakeWord — `openwakeword/train.py`, `data.py`, `utils.py`, `model.py`, `setup.py`, `examples/custom_model.yml`, `notebooks/automatic_model_training.ipynb`.
- **piper-sample-generator:** github.com/rhasspy/piper-sample-generator — tags v2.0.0 and v3.0–3.2, commits `9c1019c` and `4057c1a`, `generate_samples.py`, CHANGELOG, and the v2.0.0 release asset.
- **Colab fork:** github.com/alfiedennen/openwakeword-colab-2026 @ `da4d92a`.
- **Dicio:** github.com/DicioTeam/dicio-android, `meta/openwakeword_training_config.yml`.
- **Hugging Face datasets:** davidscripka/openwakeword_features (npy headers via range requests), davidscripka/MIT_environmental_impulse_responses, agkphysics/AudioSet (parquet layout), rudraml/fma.
- **PyPI file lists:** piper-phonemize, webrtcvad, webrtcvad-wheels, speexdsp-ns, ai-edge-litert.
- **PyTorch:** pytorch/pytorch v2.7.1–v2.9.0 (`torch/onnx`, `serialization.py`); pytorch/audio v2.5.1–v2.8.0; the docs.pytorch.org 2.7 data docs.
- **Other libraries:** scipy 1.17.0 release notes; speechbrain v0.5.14 and v1.0.3; python-acoustics v0.2.6; cmudict and pronouncing; uv's installer and PyTorch docs.
- **[secondary]:** github.com/briankelley/atlas-voice-training, github.com/IT-BAER/hawake-wakeword.

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
_Superseded in part by the owner's call (§8.1): listening is opt-in and only while charging; "while unlocked" is dropped._
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
- **Models live in `filesDir`**, pushed by `setup_phone.py` like Laya's. Retraining means pushing a new file, not rebuilding the APK. _As built (§8.2): bundled as app assets instead (2.6 MB); retraining means replacing the asset and rebuilding._

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
4. **Models:** `setup_phone.py` pushes `melspectrogram.onnx`, `embedding_model.onnx` and `hey_friday.onnx` to `filesDir/wakeword/`. `WakeWord` checks they exist, like `LayaEngine`. _As built: app assets in `assets/wakeword/` (§8.2)._
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
_Items 1 (screen off), 3, 5 and 6 were measured on 2026-10-17: see §8._
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

## 8. Built and measured on the Pixel 8 (2026-10-17) [measured]
Items 1 (part), 3, 5 and 6 of §7 were measured. Items 2, 4 and 7 were not.

### 8.1 The owner's call: no always-on mic
- **What happened.** During the test the owner saw the green mic dot stay on. They asked for "Hey Google"-style listening with no indicator.
- **Why it can't be done.** §2.1 and §2.6 explain it: indicator-free background listening needs system-only permissions.
- **Decision (owner, 2026-10-17).**
  - Listening is **opt-in and only while charging**: one switch, "Listen for "Hey Friday" while charging" (desk or bedside). It is off by default.
  - "While unlocked" is dropped.
  - Off or unplugged, the `AudioRecord` is released and the dot goes away (§8.6).
- **Triggers that need no mic of Friday's own.** Every launch of `MainActivity` now opens ready to type (goal box focused, keyboard up; `windowSoftInputMode="stateAlwaysVisible"`). These all land there:
  - "Hey Google, open Friday" (Google's own hotword);
  - Pixel Quick Tap (back double-tap → Open app → Friday);
  - the launcher icon.
- **The name resolves.** The app label is exactly "Friday" (`aapt2 dump badging`: `application-label:'Friday'`; the launcher activity inherits it).
- **Not done (owner hasn't chosen them):** an accessibility floating button, or making Friday the default assistant.

### 8.2 What was built (Phase A: inside the accessibility service, no foreground service, no notification)
- **`WakeWord.kt`** holds three parts:
  - `WakeDetector`: the three models from `assets/wakeword/`, one ORT thread each, plus XNNPACK. Its rings hold 1,760 samples, 76 × 32 mel frames and 16 × 96 embeddings, as in §5.1.
  - `WakeWord`: listens when the switch is on, RECORD_AUDIO is granted, the phone is plugged in and the audio mode is `NORMAL` (a call or VoIP pauses it). It reacts to `POWER_CONNECTED`/`DISCONNECTED` and the API 31 mode listener.
  - Bridge commands: `wake` (state and stats; `{"on": bool}` flips the switch), `wake_test` (scores a WAV in filesDir) and a `selfcheck` entry. The self-check covers shapes, the int16 scaling trap and the WAV reader.
- **Firing:** a score ≥ 0.5 held for 2 frames (160 ms), then a 2 s refractory period. On a detection:
  - a short vibration;
  - `MainActivity` opens ready to type;
  - skipped while a request runs or the owner is teaching.
- **Switch and permission.** The switch is in `MainActivity` (SharedPreferences `friday`, key `wake_charging`). Turning it on asks for RECORD_AUDIO if it isn't granted. `setup_phone.py` grants it over adb.
- **Test script.** `scripts/wake_test.py DIR` pushes a folder of WAVs, scores them on the phone, prints a table and then deletes the WAVs from the phone.
- **Models:** bundled as assets (2.6 MB). Licences are in `assets/wakeword/NOTICE.txt`:
  - The feature models are openWakeWord v0.5.1 release assets (sizes match). Its README puts "all of the included pre-trained models" under CC BY-NC-SA 4.0, and the embedding model is Google's Apache-2.0 speech_embedding. We follow the stricter reading: non-commercial, attributed.
  - `hey_friday.onnx` is MIT, "Copyright (c) 2023 Florian Wartner"; its git blob hash matches the repo. It was trained with openWakeWord's pipeline, whose negative data is CC BY-NC-SA.
  - All fine for a personal app [primary].

### 8.3 Offline scores (phone, `wake_test`)
- **Test set:** 12 macOS `say` voices (US ×3, UK ×3, AU, IE, ZA, and Indian English ×3), plus 3 s of silence.
- **Each clip** gets 2 s of digital silence before it and 1 s after.
- **Reading the table:** each cell is the clip's max score; **bold** = fires (≥ 0.5 held for 2 frames).

| phrase | fires | samantha | eddy_us | reed_us | daniel | flo_uk | shelley_uk | karen | moira | tessa | rishi | aman | tara |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| hey friday | **11/12** | **0.88** | **0.78** | **0.84** | **0.88** | **0.77** | 0.48 | **0.88** | **0.88** | **0.87** | **0.87** | **0.64** | **0.87** |
| hi friday | **9/12** | **0.87** | **0.85** | **0.85** | **0.89** | 0.64 | 0.03 | 0.36 | **0.87** | **0.87** | **0.88** | **0.88** | **0.88** |
| friday | **1/12** | **0.87** | 0.01 | 0.01 | 0.07 | 0.01 | 0.00 | 0.00 | 0.77 | 0.51 | 0.12 | 0.10 | 0.22 |
| hey freddy | **3/12** | 0.54 | 0.01 | 0.01 | 0.19 | 0.01 | 0.01 | **0.65** | **0.88** | 0.47 | 0.01 | 0.00 | **0.61** |
| friday night | 0/12 | 0.08 | 0.00 | 0.00 | 0.01 | 0.00 | 0.00 | 0.00 | 0.03 | 0.02 | 0.00 | 0.00 | 0.00 |
| high five | 0/12 | 0.00 | 0.00 | 0.00 | 0.01 | 0.00 | 0.00 | 0.00 | 0.01 | 0.00 | 0.00 | 0.00 | 0.12 |
| hey siri | 0/12 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 |
| okay google | 0/12 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 |
| silence (3 s) | 0/1 | 0.001 | | | | | | | | | | | |

- **Port parity (§7 item 6):** the phone matches a Python mirror of openWakeWord's streaming code (`utils.py` `_streaming_features` + `predict`) on the same 97 clips, max |Δ score| 0.0005, which is the phone's print rounding. XNNPACK and the default CPU EP give identical scores.
- **"hi friday": the community model already fires on it** (9/12 here; 10/12 with a noise floor). One model serves both phrases for now. One voice (Shelley UK) is missed on both phrases.
- **Digital silence is a pessimistic test.** Zeros give the floor mel value (-8), unlike any room.
  - With only 1 s of silence before the clip (openWakeWord's `predict_clip` default), "hey friday" fired 12/12 and "hi friday" 11/12.
  - With a Gaussian noise floor of RMS 100–300 mixed in (the phone mic's quiet-room level, measured RMS 150–310), hits were 21–22/24 at patience 1 and 20–21/24 at patience 2 (Mac reference, same pipeline).

### 8.4 The weak spot: near misses
- **"friday" alone and "hey freddy" fire too.**
  - In digital silence: 4/24 at threshold 0.5 and patience 2.
  - With a noise floor: **10–12/24**.
  - "friday night", "high five", "hey siri" and "okay google" stayed at 0–1/48.
- **No threshold or patience separates them**; raising either costs hits faster than it removes near misses. Mac reference, counts out of 24 positive / 24 near-miss / 48 other clips:

| threshold, patience | digital silence | noise RMS 100 | noise RMS 300 |
|---|---|---|---|
| 0.5, 1 | 21 / 7 / 0 | 23 / 13 / 3 | 22 / 12 / 1 |
| **0.5, 2 (shipped)** | **20 / 4 / 0** | **21 / 12 / 0** | **20 / 10 / 1** |
| 0.5, 3 | 16 / 3 / 0 | 21 / 8 / 0 | 17 / 6 / 0 |
| 0.7, 2 | 15 / 2 / 0 | 21 / 9 / 0 | 17 / 9 / 0 |
| 0.8, 3 | 13 / 1 / 0 | 18 / 3 / 0 | 14 / 3 / 0 |

- **What this means:**
  - Patience 2 is the shipped default: it cut bare "friday" from 4/12 to 1/12 in silence at no cost.
  - The real fix is our own model trained with these phrases as negatives (§4.5).
  - A false wake costs little: it only opens the request box (D-025).

### 8.5 CPU and RAM
- **Compute per 80 ms frame, on the listening thread:**
  - Default CPU EP: 36–40 ms.
  - With XNNPACK: 24–27 ms, so XNNPACK is shipped.
  - The `wake_test` bridge thread does a frame in 9.5 ms (15.5 ms without XNNPACK): it runs frames back to back, so it gets faster cores.
- **`top -H`:** the `friday-wake` thread used 47–57% of one core on the default EP, and **31–37% with XNNPACK**. It runs on a Cortex-A510 little core (CPU 1) at ~600 MHz. The phone was on AC with the screen off.
  - Estimated ≈ 2 mA from `power_profile.xml`'s little-core figures (6.15 mA at 615 MHz, fully loaded) [estimate]. While charging, the charger pays.
- **Where the time goes:** the embedding model is ~92% of it (Mac, 1 thread: mel 0.08 ms, embedding 1.10 ms, wake model 0.01 ms per frame).
- **RAM** (`dumpsys meminfo`, Laya not loaded):
  - The app is 18.4 MB PSS before the detector ever runs, 61.1 MB while listening, and 55.5 MB after it stops.
  - So listening itself costs **~5.6 MB**. The first start also maps ORT's native library and XNNPACK (~37 MB), which stay until the process ends. Laya uses the same library.

### 8.6 The mic in the background, and the indicator
- **§7 item 1, answered for the screen-off case.** Friday's activity was not on screen; the phone was dozing, locked (pattern) and on AC. `AudioRecord(VOICE_RECOGNITION)` in the accessibility process got real audio:
  - frame RMS 150–310, `isClientSilenced()` false;
  - no foreground service, no notification.
- **Screen on:** with the screen on and unlocked (launcher on top), it also heard: spoken phrases fired (§8.7).
- **Indicator (§7 item 3), checked on the new build:**
  - **What was read:** SystemUI's `AppOpsControllerImpl` "Active Items" (what drives the green dot), the `privacy_chip` view, and `dumpsys audio` RecordActivityMonitor.
  - **Off (default after install):** no RECORD_AUDIO op for Friday, chip GONE, no recording.
  - **Switch on, on the charger:** one op, chip VISIBLE, one session (`VOICE_RECOGNITION`, 16 kHz client, not silenced).
  - **Switch off:** all three gone within 2 s.
  - **Simulated unplug** (`dumpsys battery unplug`) with the switch on: released within 3 s, chip GONE. `dumpsys battery reset`: listening again.
  - The dot shows exactly while Friday listens.

### 8.7 Live test (Mac speakers, phone on the desk, Friday in the background)
- **Setup:** phrases played by `say` from the MacBook's speakers at volume 50, the phone on the desk (AC, screen on, launcher on top).
- **Hits:**
  - US/UK voices fired **3/3**: "hey friday" Samantha 0.87 and Daniel 0.88; "hi friday" Samantha 0.87.
  - Each time, Friday came to the front with the keyboard up (`mInputShown=true`), and the vibration played (`finished`, usage ACCESSIBILITY, 62–64 ms).
- **Misses:** Indian-English voices fired **0/8** live: Rishi, Aman and Tara on both phrases, max 0.00–0.42. Offline, the same clips scored up to 0.88.
  - Loudness doesn't explain it: Rishi's clip is as loud as Samantha's (-16 dBFS), and Daniel's is quieter (-20 dBFS) yet fired.
  - The owner's own voice is the real test (§7 item 7).
  - Our own model's Piper voices (LibriTTS-R, §4.5) are mostly US speakers, so check the owner's clips before trusting it.
- **Negatives:** "friday night" and "high five" did not fire live (0.20, 0.01).
- **Not played aloud:** "hey siri" and "okay google", which would wake the Mac's Siri and the phone's own assistant. Offline they scored ≤ 0.004.
- **Launches:** two launcher-intent launches (the icon's path, also used by "Hey Google, open Friday" and Quick Tap), cold and warm, both opened ready to type (`mInputShown=true`). The launcher label is "Friday", the only app with that name.

### 8.8 Vibration: touch feedback is off on this phone
- **What we saw.** The first wake's vibration was dropped (`dumpsys vibrator_manager`: `ignored_for_settings`, usage TOUCH). A bare predefined click counts as touch feedback, and the owner has touch feedback off; SystemUI's own clicks are dropped too.
- **The fix.** The wake buzz now uses `VibrationAttributes.USAGE_ACCESSIBILITY` (API 33+). Android exempts it from the haptics settings, and Friday is an accessibility service.

### 8.9 Open issues
- **Near-miss false wakes** ("friday", "hey freddy"; §8.4). Fix: our own model (§4.5). Count false wakes in the charging mode first, from the `wake` stats.
- **Misses, and the accent risk.** One TTS voice in 12 is missed offline on both phrases, and Indian-English voices missed 0/8 live over the speakers (§8.7). The owner's own voice and accent are untested: §7 item 7.
- **Not exercised:**
  - The call pause (no safe way to place a call from here).
  - Concurrency with other apps (§7 item 2).
  - Power over hours (§7 item 4; while charging, the charger pays).
- **After a detection while locked,** `MainActivity` opens behind the lock screen; it is visible once the owner unlocks.

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
