# Research: can Laya run an Android phone, fully locally?

_Last updated: 2026-10-16 · Trust tags: [primary] read at source · [secondary] search/blog/forum · [measured] run here · [UNCERTAIN] unconfirmed or conflicting_

Distilled from the topic files below. Decisions that follow from it are in [../docs/DECISIONS.md](../docs/DECISIONS.md).

## Answer in one screen

1. **What Laya is.** ConvAI's open-source decision model: Apache-2.0, released 2026-09-18, `laya` on PyPI.
   - It answers multiple-choice questions about a text state in one pass and **never generates text** [primary].
   - The usable phone checkpoint is the community fine-tune **Laya-Android** (`ByunByun/laya-android`, 322M, 644 MB fp16) [primary]:
     - It reads the accessibility tree only and picks 1 of 12 ops plus a target element.
     - Offline scores only: AndroidControl-High Type 76.7 / Grounding 61.7.
     - It cannot output app names or typed text.
     - Base Laya zero-shot is near useless for this (29.0 / 11.8).
2. **It works on a real phone for simple navigation** [measured, 05].
   - **Results:** on the Pixel 8, 4 of 5 live episodes reached the target screen. Two stopped cleanly with DONE (Clock → Timers; Settings → About phone).
   - **Failures:** "search for …" goals (BACK loops on the home screen), and weak DONE.
   - **Speed:** about 3 s per step over adb. About 90% of that is `uiautomator dump`, and Laya takes 0.12–0.85 s on the M5 GPU.
3. **Nobody has published this before.** No one has shown Laya, or any decision model of its kind, driving a real phone locally with a task success rate [primary, search-based; UNCERTAIN as a "first"].
   - **Closest design:** Jev-Mobile (arXiv 2609.30186). A planner model sets goals and a decision model picks the taps. It scored 0.79 on AndroidWorld vs 0.84 for a model that runs every step, with 94.5% less executor time. It is all cloud.
   - **Closest local work:** V-Droid (tree-only, AW 59.5) [primary, 03].
4. **Android allows it, through an AccessibilityService** (the TalkBack API), without root [primary, 02]:
   - It reads every window's tree in about 18–33 ms [secondary].
   - Gestures go through `dispatchGesture`, with up to 20 strokes: tap, long-press, swipe, drag, pinch.
   - Unicode typing via `ACTION_SET_TEXT` works in Compose, Chrome and WebView.
   - Global back, home, recents, notifications, quick settings and lock are available.
   - Apps launch with `getLaunchIntentForPackage`.
   - **Enabling it on Android 16:** the restricted-settings check is gone from the accessibility bind path, so `adb install` plus `settings put secure enabled_accessibility_services` should enable our own service with no extra tap [primary code; **UNCERTAIN until run here**].
   - **Google Play bans autonomous accessibility agents** (2025-10-30), so this ships as a personal sideloaded app [primary, 03].
   - **Hard limits:**
     - It cannot get past the PIN lock.
     - FLAG_SECURE blanks screenshots, but the tree is still readable.
     - The system hides permission dialogs and installer buttons (`accessibilityDataSensitive`) from non-assistive services. We keep that wall on purpose (D-014).
     - Banking and UPI apps detect accessibility services and refuse to run. That is a scope boundary, and we don't fight it.
     - Flutter, canvas and some WebView UIs have poor trees [primary/secondary, 02, 03].
   - `uiautomator dump` **unbinds every enabled accessibility service while it runs** [primary]. Once our app exists, the adb dump path must not run alongside it.
5. **One native app with Laya inside is possible but slower** [04].
   - **Runtime:** ONNX Runtime Android on CPU. Laya ships the ONNX exporter and a Java SDK with a parity-tested Gemma-2 tokenizer [primary].
   - **No accelerator:** the Tensor G3 AI chip is closed to third-party apps, and NNAPI is deprecated [primary].
   - **Speed:** an estimated **1.3–2.7 s per step (int8 linears) or 3–6.5 s (fp32)** on normal screens [UNCERTAIN until measured].
   - **Quantization:** Laya's quick dynamic int8 changes 17% of decisions [primary], so precision needs a parity gate.
6. **Text and app names need a "System 2"** [01, 03, 04].
   - **First choice:** rules plus Laya choosing among options. Use quoted spans in the goal for text, and installed-app labels for OPEN_APP, run as a short-list tournament.
   - **Fallback:** a small local LLM, loaded only on those steps. On the Mac: Qwen3.5-4B. On the phone: Qwen3.5-0.8B or FunctionGemma 270M.
   - **Gemini Nano is out:** the Pixel 8 is unsupported, and it only answers while the calling app is in the foreground [primary].
7. **Browser.** Drive the user's own Chrome through the same accessibility path. It exposes page elements, keeps logins, and matches Laya-Android's training.
   - Chrome DevTools only works from a USB host.
   - An in-app WebView has none of the user's sessions [UNCERTAIN until tested].
   - Web pages have many elements, which makes limiting the list mandatory.

## Biggest risks (ranked)
1. **Target accuracy drops as the candidate list grows.**
   - Measured: "Turn off Bluetooth" is right with 3 Settings rows and wrong with 14 [measured].
   - Prior art: 61.7% at 2 options falls to about 2% at ~151 [primary, laya-browser]. Laya's option budget is about 20 [primary].
   - Our home screen has 31 candidates [measured].
   - **Mitigations to test:** a tournament in chunks of 5–8, and per-candidate `noul` scoring. V-Droid's per-candidate scoring beat multiple choice 47.4 vs 35.8 [primary].
2. **Stopping and loops.**
   - DONE recall is 0.55–0.75 [primary]. Ours was weak too [measured].
   - **Mitigations:** a separate completion check, plus the loop guard (already in the harness).
3. **On-phone speed.** Seconds per step on the CPU [UNCERTAIN]. Measure it before committing; the fallback is Laya on the Mac over USB or Wi-Fi.
4. **Accessibility holes:** unlabelled icons appear as `- (FrameLayout)`, and the model has no vision [measured/primary].
5. **Safety.**
   - Never auto-accept permission pop-ups (arXiv 2608.04755).
   - Hand logins, payments and CAPTCHAs to the human.
   - Re-check the foreground app before acting [primary, 03].

## Files
| File | Topic |
|---|---|
| [01-laya-and-laya-android.md](01-laya-and-laya-android.md) | Laya API, Laya-Android input format and limits, ONNX path, community |
| [02-android-control-plumbing.md](02-android-control-plumbing.md) | ADB vs AccessibilityService vs Shizuku; Android 13–16 permissions and limits |
| [03-prior-art.md](03-prior-art.md) | Who has tried phone agents (local and cloud), benchmarks, lessons |
| [04-on-device-inference.md](04-on-device-inference.md) | Running Laya on the Pixel 8 (ONNX Runtime), quantization, System-2 LLMs, RAM |
| [05-live-tests.md](05-live-tests.md) | Our measurements: model speed, adb costs, live episodes on the Pixel 8 |

## Quick reference: measured on our hardware (2026-10-16)
| What | Number |
|---|---|
| Laya-Android, M5 GPU (MPS), 3 / 14 / 50 / 100 candidates | 38 / 92 / 495 / 1069 ms (realistic labels; 100 is truncated) |
| Same, M5 CPU (shorter synthetic labels) | 102 / 161 / 917 / 3209 ms |
| Model load from cache | 2.8–5.2 s |
| Parity vs the laya-android quickstart (transformers 5.20, MPS) | same choices; p 0.9338 vs 0.9347 and 0.9339 vs 0.9313 |
| `uiautomator dump` on the Pixel 8 | ~2.5 s warm, ~4.6 s cold; sometimes "could not get idle state" |
| `screencap` | 0.65–0.8 s |
| Candidates per screen | launcher 31; Settings pages 11–21 |
| Pixel 8 RAM "Free" (`dumpsys meminfo`) | 4.5 GB, of which 3.5 GB is cached apps |
