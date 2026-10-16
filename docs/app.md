# Friday — the agent app (Android)

**Friday** is a personal, sideloaded app that lets Laya read the screen and operate the phone. Everything runs
locally. (Package id stays `dev.layatest.agent`; the display name is Friday.)
Not for the Play Store (it bans autonomous accessibility agents, D-012).

## Phasing (D-008)
- **Phase 1 (now):** the app is the **eyes and hands** — an AccessibilityService that reads the screen tree and
  performs actions. **Laya runs on the Mac** and drives the app over USB (`adb forward`). Fast to iterate, and the
  model's quality is measured on the real device.
- **Phase 2 (built):** Laya runs **inside the app** (ONNX Runtime, fp16, CPU arena off — D-023), so there
  is no PC: type a goal in the app and it decides on-device. Export → quantize → on-device decode are all
  verified (research/06); the run loop is `AgentService.runGoal`. Resource budget below.
- **Phase 3:** smarter decisions — short-listing long screens, a real completion check, calibrated confidence.

## Parts
| File | Role |
|---|---|
| `AgentService.kt` | AccessibilityService: exports every window's tree; performs tap/long-press/swipe/scroll, `ACTION_SET_TEXT`, open-app, and global back/home/recents/notifications/quick-settings. |
| `Bridge.kt` | A line-delimited JSON socket (`localabstract:layatest_agent`), **only** accepting adb (shell/root) connections. |
| `Overlay.kt` | The on-phone approval card (Run / Skip / Stop) and the magenta target outline. |
| `MainActivity.kt` | Status screen: is the service on, and the command to run a task. |
| `../scripts/live_adb.py` | The Mac driver: loads Laya, reads the screen, decides, asks for approval, acts, logs. |
| `../scripts/setup_phone.py` | Install + enable the service + open the bridge, in one command. |

## Run a test
```sh
uv run python scripts/setup_phone.py            # install, enable the service, forward the port
uv run python scripts/live_adb.py --app "Open the Clock app and go to the Battery page"
```
Each step appears **on the phone**; tap Run / Skip / Stop. (`--yes` runs without asking, but risky-looking
steps — send, pay, delete, install… — still ask. Use it only for read-only tests.)

Enabling the service needs no "restricted settings" tap on Android 16 — `settings put secure
enabled_accessibility_services …` is enough [measured 2026-10-16], confirming research/02 §4.1.

## Bridge protocol
One JSON object per line, one reply per request.
- `{"cmd":"state"}` → `{screen:[w,h], package, windows:[{type, root:<node tree>}]}` after the screen settles.
- `{"cmd":"foreground"}` → `{package}`  ·  `{"cmd":"apps"}` → `{apps:[{label, package}]}`
- `{"cmd":"confirm","text":..,"bounds":[l,t,r,b]}` → `{choice:"run"|"skip"|"stop"}`
- `{"cmd":"tap","x","y"}` · `{"cmd":"swipe","x","y","x2","y2","ms"}` · `{"cmd":"text","text"}`
- `{"cmd":"open_app","package"}` · `{"cmd":"global","action":"back|home|recents|notifications|quick_settings"}`

A node carries: text, content_desc, hint, class, resource_id, bounds, visible, and the flags
clickable / long_clickable / editable / scrollable / checkable / checked / selected / enabled.

## Resources (D-016)
**Phase 1 is light:** 2.5 MB APK, no third-party libraries, no persistent storage, no wakelock. The tree is built
only on request and then garbage-collected; `AccessibilityNodeInfo.recycle()` is a no-op since API 33, so there is
nothing to pool. The bridge is one thread.

**Phase 2 budget — strict:**
- Keep the on-phone model **≤ ~0.8 GB**: int8 linear layers, and a memory-mapped or quantized embedding table
  (the 256k×768 table is 61% of the weights).
- **One forward pass at a time;** ask `operation` first and `target` only for CLICK/LONG_PRESS; cap input at
  ~1,536 tokens.
- **Load the model on demand, free it when idle** (`onTrimMemory`/after N idle seconds); reload costs a few seconds.
- **Never** keep a text LLM resident alongside Laya.
- Gate on `MemAvailable` (not MemFree); if it is low, pause and tell the owner rather than risk an OOM kill.
- Measure per build: peak RSS, step latency, and battery per 100 steps.

## Safety (D-011, D-014)
- Default: approve every step on the phone; a big **Stop** ends the run.
- Risky-word targets always ask, even in `--yes`.
- Stop on three identical actions or three skips.
- **No `isAccessibilityTool` flag** — so Android keeps permission dialogs and installer buttons out of the agent's
  reach. The agent does not target banking/UPI apps (they detect and block accessibility by design).
- Every step is logged to `captures/` (git-ignored; it holds on-screen text).
