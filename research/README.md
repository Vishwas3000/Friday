# Research: can Laya run an Android phone, fully locally?

_Last updated: 2026-10-17 · Trust tags: [primary] read at source · [secondary] search/blog/forum · [measured] run here · [UNCERTAIN] unconfirmed or conflicting_

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
     - Some banking apps block accessibility services through in-app security SDKs, and ICICI iMobile won't run with Developer options on. For GPay, PhonePe and Paytm there is no public evidence of an accessibility block (GPay blocks overlays and "unsafe apps"). Readability is measured per app with the owner, not assumed (research/11, D-029). Where an app blocks, we don't fight it.
     - Flutter, canvas and some WebView UIs have poor trees [primary/secondary, 02, 03].
   - `uiautomator dump` **unbinds every enabled accessibility service while it runs** [primary]. Once our app exists, the adb dump path must not run alongside it.
5. **One native app with Laya inside is possible but slower** [04].
   - **Runtime:** ONNX Runtime Android on CPU. Laya ships the ONNX exporter and a Java SDK with a parity-tested Gemma-2 tokenizer [primary].
   - **No accelerator:** the Tensor G3 AI chip is closed to third-party apps, and NNAPI is deprecated [primary].
   - **Speed [measured, 06]:** fp16 **~2.4 s/step** at **688 MB PSS** (ORT CPU arena disabled, 4 threads), decisions exact vs the Mac. The RAM driver is ORT's arena, not the weights — gate passed.
   - **Quantization [measured, 06]:** dynamic int8 is 3.5× faster (677 ms) and 389 MB but **breaks the operation decision** (our measure + Laya's own 17% figure) — needs the op head kept fp32.
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
3. **On-phone speed [measured, 06]:** ~2.4 s/step in fp16 (gate passed). Thermal throttling inflates sustained runs (2.4 → 8 s after ~8 back-to-back), so don't loop inference; int8 is 3.5× faster but needs accuracy recovery. Fallback stays Laya on the Mac.
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
| [06-onnx-export.md](06-onnx-export.md) | ONNX export (bit-exact) + fp16/int8 + the on-device Pixel 8 gate — latency/RAM measured |
| [07-ondevice-tokenizer.md](07-ondevice-tokenizer.md) | Tokenizing mmBERT on-device via DJL HuggingFace `tokenizers` (load tokenizer.json) |
| [08-kotlin-input-port.md](08-kotlin-input-port.md) | The Kotlin port of Laya's input build + decode, verified against the Python |
| [09-laya-as-assistant-brain.md](09-laya-as-assistant-brain.md) | What Laya is good at as an assistant's brain (routing, slots, app choice), measured; fine-tuning verdict |
| [10-skills-intents.md](10-skills-intents.md) | Android intents/APIs per skill (alarm, timer, maps, search, music…), what completes without UI |
| [11-upi-apps-feasibility.md](11-upi-apps-feasibility.md) | Can an accessibility assistant read UPI/bank apps? Per-app behaviour, exports, statement-file option |
| [12-app-knowledge.md](12-app-knowledge.md) | Picking the right app: names 36% → purposes 58% → rules + purposes 91% (measured) |
| [13-text-llm.md](13-text-llm.md) | The text "Writer": llama.cpp + LFM2.5-1.2B (runner-up Qwen3.5-0.8B), JSON grammars, Pixel 8 numbers to confirm (D-041) |
| [14-finetune-data.md](14-finetune-data.md) | Fine-tuning data: AndroidControl's app-name leak (98.5% → 32.0% de-named), Indian-app gap, Friday's own Teach/run rows (D-044) |
| [15-finetune-method.md](15-finetune-method.md) | Fine-tuning method: auto profiles 48% zero-shot, BM25 shortlist + one pass, joint fine-tune with replay, pilot results (D-043) |
| [16-wake-word.md](16-wake-word.md) | Wake word: openWakeWord in Friday's ONNX Runtime, "Hey Friday", power per listening mode, platform limits (D-035) |
| [17-operating-apps.md](17-operating-apps.md) | Why Friday "only opened apps" (fixed: trail + open-and-continue), the alarm bug, speed, operational lessons |
| [18-speed.md](18-speed.md) | Faster Laya passes, measured on 52 real (screen, goal) pairs: dynamic int8 fails on ORT 1.20; 8-bit MatMulNBits keeps 52/52 (needs a newer ORT); operation-first is exact at ~0.41× compute; label trims not worth it |
| [19-android-encoder-speed.md](19-android-encoder-speed.md) | How fast Laya can run on a Pixel 8: ORT 1.20 has no fp16 CPU kernels (casts every run), wasted target rows, heat/cores; target ~1 s/step (5–10×) |
| [20-ui-memory-and-step-speed.md](20-ui-memory-and-step-speed.md) | Where a step's time goes (Laya, by tokens) and UI memory: repeat tasks 22.6 s → 2.2 s without Laya (D-040) |



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
| Laya pass on the Pixel 8 by input size (2026-10-17) | 390 tokens 2.9 s · 924 tokens 6–12 s · 1320 tokens 17 s · 1868 tokens 24–39 s (thermal) |
| Screen read + settle on the Pixel 8 | 0.2–1.0 s |
| Repeat task from UI memory (no Laya) | 2.0–2.2 s end to end, vs 22.6 s with Laya |
