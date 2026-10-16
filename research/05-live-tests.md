# Live tests: Laya-Android driving a real Pixel 8 over adb

_Last updated: 2026-10-16 · Trust tags: [primary] read at source · [secondary] search/blog/forum · [measured] run here · [UNCERTAIN] unconfirmed or conflicting_

## TL;DR
- **It works for simple navigation.** Laya-Android reached the right screen in 4 of 5 live episodes and stopped correctly in 2 of them [measured].
- **Taps are confident when elements are labelled**: 0.95–0.997 on "Timers", "Battery" and "About phone" [measured].
- **Stopping is weak.** On the Battery page it kept choosing SCROLL_DOWN (0.40, 0.59) over DONE (0.27, 0.30) [measured]. The card warns that rare actions are weak [primary].
- **Goal phrasing matters.** Both "search for …" goals got BACK ×3 on the home screen, with OPEN_APP only second (0.17) [measured].
- **The adb screen read is the bottleneck.** `uiautomator dump` takes 2.4–3.8 s per step, against 0.12–0.85 s for Laya on the M5 GPU. That is about 90% of each ~3 s step [measured]. A product needs an on-phone AccessibilityService.
- **Laya cannot produce the app name or text** [primary]. Here, simple rules supplied them: an alias table, and quoted spans from the goal.

## Setup [measured]
- **Mac:** Apple M5, 16 GB, macOS 26.2, Python 3.12 via uv. Packages: `laya==0.3.28`, torch 2.14.1, transformers 5.20.0. Model `ByunByun/laya-android` on MPS.
- **Phone:** Pixel 8, Android 16 (SDK 36), patch 2026-05-05, 1080×2400, not rooted, USB debugging on. "Stay awake while charging" was turned on for the tests with `svc power stayon usb`.
- **Harness:** [`scripts/live_adb.py`](../scripts/live_adb.py).
  - Observe: `adb exec-out uiautomator dump /dev/tty`.
  - Build the candidate list with the laya-android rules ([`scripts/laya_android.py`](../scripts/laya_android.py), adapted from `src/candidates.py` and `src/serialization.py`).
  - Decide: `agent.system_one(..., max_len=3072, head_max_len=1536)`.
  - Act: `adb shell input tap/swipe/keyevent/text` and `monkey -p <pkg>`.
- **Payload stubs (System 2 stand-ins):** OPEN_APP matches the goal against an alias table and package names. INPUT_TEXT uses a quoted span in the goal.
- **Gaps vs training data:** uiautomator has no `editable` attribute, so it is inferred from the class name. The dump covers the active window, while AndroidControl used full accessibility forests [UNCERTAIN effect].

## Model speed on the Mac [measured] (`scripts/bench_model.py`, synthetic Settings rows)

| Candidates | State tokens | M5 GPU (MPS) median | M5 CPU median |
|---:|---:|---:|---:|
| 3 | 46 | 37 ms | 102 ms |
| 14 | 140 | 60 ms | 161 ms |
| 50 | 578 | 303 ms | 917 ms |
| 100 | 1192 | 793 ms | 3209 ms |

- The first load took 85 s, including about 66 s of download. Cached loads take 3.4–5.2 s.
- Latency grows faster than linearly, because every candidate appears twice: once in `state.ui` and once as an option of the `target` question.
- The model-card example reproduces: CLICK 0.947, "Connected devices" 0.730.
- With 14 synthetic rows, "Turn off Bluetooth" picked "Display & touch", which is wrong. Real Pixel rows carry subtitles (for example "Connected devices Bluetooth, pairing"), and the live tests used those [measured].

## Phone-side costs [measured]
- `uiautomator dump`: about 4.6 s cold and about 2.5 s warm. `--compressed` is no faster, and streaming to `/dev/tty` instead of a file makes no difference either. The cost is the UiAutomation connection, not I/O.
- `uiautomator dump` sometimes fails with **"ERROR: could not get idle state"**, here right after "About phone" opened. The harness now retries up to 3 times.
- `screencap -p`: 0.65–0.8 s. Querying launcher activities: 0.15 s (206 launchable apps).
- Screen sizes: the lock screen had 59 nodes and 6 actionable. The home screen had **31 candidates, about 1,200 tokens**. Settings pages had 11–21 candidates and 600–950 tokens.
- RAM: 7.75 GB total. "Free" is 4.5 GB, of which 3.5 GB is cached apps, 0.7 GB kernel cache and 0.37 GB free; status normal (`dumpsys meminfo`).

## Live episodes [measured] (logs in `captures/`, git-ignored)

| # | Goal | Steps | Outcome |
|---|---|---|---|
| 1 | Open Settings and show the Battery page | 6 (cap) | 🟡 OPEN_APP Settings (0.80) → BACK, BACK (Settings reopened on a stale sub-page) → CLICK Battery (0.97) → SCROLL_DOWN ×2 instead of DONE |
| 2 | Open the Clock app and go to the Timer tab | 3 | ✅ OPEN_APP Clock (0.99) → CLICK Timers (0.95) → DONE (0.68) |
| 3 | Search Settings for "dark theme" | 3 | ❌ BACK ×3 on the home screen; stopped by the loop guard |
| 4 | Open About phone in Settings… | 4 | 🟡 Reached About phone (CLICK 0.997), then the dump failed with "could not get idle state" (retry added) |
| 4b | (re-run of 4) | 4 | ✅ OPEN_APP Settings → BACK (stale Storage page) → CLICK About phone (0.997) → DONE (0.45) |
| 5 | Open Settings and search for "dark theme" | 3 | ❌ BACK ×3 on the home screen; OPEN_APP second at 0.17 |

## What we learned
1. **Recovery from stale app state works.** Settings reopens on whatever page was last used, and the model chose BACK (0.55–0.78) until it reached the main list [measured].
2. **A completion check is needed beyond the policy.** DONE won 2 of 3 times on the right screen, and once with only 0.45 [measured].
   - Options: a rule-based "target screen reached" check, a second Laya question, or a small local LLM judge.
   - [UNCERTAIN] which works best; to test.
3. **The first step needs help when the goal names an app.** A cheap rule: on the launcher, with an app named in the goal, do OPEN_APP directly. Both failures were this case [measured].
4. **Accessibility labels make the model.** Rows with subtitles ("About phone Pixel 8") get 0.99+. Unlabelled icons show up as `- (FrameLayout)` and cannot be chosen meaningfully [measured; the card's no-vision limitation, primary].
5. **Speed:** over adb a step is about 3 s, with about 90% spent observing. The home screen alone costs 0.4–0.85 s to decide, because of its 31 candidates [measured].

## Dead ends
- `uiautomator dump --compressed` for speed: no gain (2.53–2.57 s vs 2.57 s) [measured].
- Homebrew Python 3.15: `xml.etree` is broken (pyexpat symbol missing on macOS 26.2), so the harness can't parse dumps [measured].

## App-bridge validation (Friday, Phase 1) [measured 2026-10-16]
- The Friday AccessibilityService reads the full window forest in **55–63 ms** (incl. a 300 ms quiet-settle wait), vs ~2,500 ms for `uiautomator dump` — about **40× faster**, and it carries the real `editable` flag the dump lacks.
- Full loop through the app bridge: "Open the Clock app and go to the Timer tab" → OPEN_APP Clock (0.99) → CLICK into Timers → DONE, in 3 steps. Step-1 observe 58 ms, decide 913 ms (31 launcher candidates, 1,225 tokens).
- OPEN_APP uses real PackageManager labels (207 launchable apps), not the adb alias table.
- The approval overlay works end-to-end: it renders the step text, outlines the target, and Run/Skip/Stop round-trips (tapped Run → driver received "run"; overlay cleaned up, 0 leftover windows).
- Service enabled over adb with **no "restricted settings" prompt** on Android 16, confirming research/02 §4.1 [measured].
- **Side-effect lesson:** the goal "go to the Timer tab" made the model tap "1m", which *started* a 1-minute timer that later rang. Keep test goals read-only (view/open), and this is a concrete case for the completion check and for per-step approval.
