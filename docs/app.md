# Friday — the agent app (Android)

**Friday** is a personal, sideloaded app that reads the screen and operates the phone. Its action and Writer models run
locally; requested Gemini Research uses Gemini's external service through its app. (Package id stays `dev.layatest.agent`; the display name is Friday.)
Not for the Play Store (it bans autonomous accessibility agents, D-012).

## Phasing (D-008)
- **Phase 1 (historical tooling):** the app is the **eyes and hands** — an AccessibilityService that reads the screen tree and
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
| `../scripts/setup_phone.py` | Install + open permission setup + forward the bridge, in one command. |

## Run a test
```sh
uv run python scripts/setup_phone.py            # install, prompt for access, forward the port
uv run python scripts/live_adb.py --app "Open the Clock app and go to the Battery page"
```
Each step appears **on the phone**; tap Run / Skip / Stop. (`--yes` runs without asking, but risky-looking
steps — send, pay, delete, install… — still ask. Use it only for read-only tests.)

Friday shows a permission checklist on first launch. Enable **Accessibility** to control apps; approve
**Microphone** for voice, **Calendar** for event tasks, and **System settings** for brightness control as needed.
Android presents the actual permission/settings screens and the owner approves them. Setup never grants access
through adb. Reopen the checklist with **Show tools → Permissions**; denied optional access can be granted later.
Approving the microphone does not enable wake-word listening. The Friday bubble uses an accessibility overlay,
so it does not need a separate draw-over-other-apps permission.
When Accessibility is missing, **Run**, **Research**, **Teach**, and **Resume** open Android Accessibility
settings directly and explain where to enable Friday. The request remains in the composer for retry after setup.

## Bridge protocol
One JSON object per line, one reply per request.
- `{"cmd":"state"}` → `{screen:[w,h], package, windows:[{type, root:<node tree>}]}` after the screen settles.
- `{"cmd":"state","include_own":true}` → opt into Friday's own windows for diagnostic UI inspection. The default excludes Friday UI; this flag does not change normal app observation.
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

## The assistant (D-024)

The current build also routes supported device requests through the persistent coordinator: native calendar creation, visible bill reading, app opening/search and standalone text drafting. It passes 35 synthetic coordinator checks and 19 component groups on the Pixel. Calendar access is an owner-controlled runtime permission; the saved event is checked through CalendarProvider. Unreadable app screens produce a blocked/unverified result. See the [capability checkpoint](../research/41-capability-checkpoint.md) and [evaluation harness](NAVIGATION_HARNESS.md) for test scope and remaining gaps. Earlier Research measurements below describe that separate acceptance sequence.

Interpreted app requests pass the selected app's package and label separately from content sources. The local
interpreter cannot change that destination or use its name as an unquoted current-request item. The saved
interpretation binds this identity for recovery. Rejected model fields and a diagnostic code remain only in the
private failed-attempt record, marked unverified; they cannot authorize the next step.
Scoped media navigation first matches an exact visible control, ignoring possessives and accessibility tab-position
announcements. It dismisses a visible keyboard when needed and checks the selected destination after tapping.
Opening a library alone does not verify playback; the existing item, app, and playback-state checks still apply.
New interpreted plans open the selected app before resolving the request. A complete request that uniquely matches
a visible navigation label uses a local label match and preserves the requested action without loading Writer.
Other interpretations keep the model path. Memory admission failures produce a blocked resource error, rather
than asking the owner to repeat already supplied task details.

`TaskIntake` restores a pending task **before routing**. Its local Room plan stores the goal, owner turns,
typed fields with exact source spans, pending question and previous-task link. This currently serves calendar
creation and missing search text in an already selected app. It collects multiple supplied fields from one
reply; failed/unpublished owner turns also remain available in that chat. Completed/cancelled tasks and other
chats do not supply pending inputs. A clear new goal starts separately. Published app answers retain their
existing, separate provenance.

Calendar weekdays resolve against each source turn's saved date and time zone. Country time phrases can use
Android's country/zone data when the country has one set of zone rules; ambiguous zones require clarification.
A timed event still needs an end time or duration. CalendarProvider readback must match the saved fields and
Busy status before completion. An uncertain dispatched write requires reconciliation instead of a fresh write.
The task card shows the retained goal and supplied fields. Unsupported constraints stay blocked; restate a full
corrected event to replace them. A stand-alone time cannot authorize an alarm, timer or reminder.

Clear replies use native field decoders. Ambiguous continuations can request one local Writer `task_update`
call (192 tokens, 20 seconds): a schema-limited field patch quoting exact current-owner text. Native checks
reject unknown fields, invented spans, invalid types and contradictions with explicit decoded values. Writer
cannot select an app or authorize an action. Resource/validation failures preserve the draft. Model-derived
normalizations remain marked in the saved fields; these checks do not prove arbitrary-language interpretation.

Type a request in Friday and tap **Run on this phone**. `Assistant` handles it:

1. Recover pending owner inputs in the selected chat; resolve supported field replies before model routing.
2. For fresh requests, select a registered capability using native interpretation, Writer or the existing skill router.
3. Save typed steps and bindings through `DevicePlanner` before app operations.
4. Execute through the coordinator using platform APIs, app intents or Laya navigation; verify each result before its dependencies continue.
5. Publish a verified answer to the originating chat, or show the pending question/resource/access failure. The banner provides progress and **Stop**.

Recorded active time includes input interpretation/context recovery (`INPUT_PREPARED`) and attempted steps;
idle waiting for an owner reply is excluded. Bridge polling wall time is a separate measurement.

Laya loads on demand and can prewarm while a recognized navigation request is being typed (D-030).
Laya and icon model sessions unload after **three idle minutes**; active tasks keep their sessions. Android
low-memory signals request cleanup when the current task finishes, and service shutdown releases task models.
Writer frees its model after every generation. Wake-word listening stays off unless enabled by the owner;
when enabled, it retains the existing charging and call restrictions and releases audio/models when stopped.
The next task may take longer while its model reloads. Idle cleanup uses an uptime timer: it can wait while
the phone sleeps, and Friday does not wake the phone just to unload models. RAM and battery savings have not
been measured for this change.
Bridge tests: `{"cmd":"selfcheck"}` (parsers), `{"cmd":"route","text":…}` (routing + timings), `{"cmd":"run","goal":…}`.

## Durable Research (M1 development)

Type a question and tap **Research**. Friday saves four steps in local `friday_tasks.db` storage before opening Gemini: prepare, submit, capture and publish. A normal new task waits automatically; interrupted tasks expose **Resume**, which reconciles the exact saved question/marker rather than blindly resending. Stop invalidates the worker. The general Run skills have not all migrated to this coordinator.

**M1 has not passed live acceptance.** An earlier Pixel build passed 22/22 fake coordinator checks and focused selectors, but a Mongolia research/stay request falsely published only the final 2,889 characters of a longer answer. No stay app was searched. The recorded active segments totalled 113.534 s; the plan-to-completion event span was 113.578 s. Neither is a verified-success time.

The installed Pixel build passes 24/24 fake coordinator checks, all four focused groups and the 18-group component selfcheck. Whole Copy corrected that result to **8,794 characters** after manual setup. Live fresh-session capture, label isolation, short answers and UI Stop also have evidence. L15 resumed after an initial failure/operator repair/installed IME fix: its same-task retry took 8.372 s, retaining one row with no new dispatch. Two L16 rechecks passed at 7.986 s / 8.011 s without duplication. A final clipboard-freshness recheck passed with a 7.296 s event span and 6.544 s capture increment. Automatic conversation location, clean restart acceptance and content constraints remain open; M1 has not passed. See the [live report](../research/37-pixel-research-live-acceptance.md) for interventions and failed attempts.

**New chat** and **History** remain above the scrollable transcript. Live Pixel checks verified both with the keyboard open, an empty newly selected chat, and restoration of the original conversation without changing its result. Switching remains subject to the active-task gate.

After forwarding the bridge, `python3 scripts/coordinator_test.py` runs the Room/fake-provider and focused selector checks without native model inference or external app actions. Use `--port` for a different forwarded port. Raw reports stay in ignored local captures.

| Bridge request | Result |
| --- | --- |
| `{"cmd":"run","goal":"<question>","research":true}` | Accept a Research request through the same executor as the app button. |
| `{"cmd":"task_state"}` | Latest task in the selected chat, with saved steps, events, timing and evidence. |
| `{"cmd":"resume_task","task_id":"<id>"}` | Reconcile and continue the saved task. |
| `{"cmd":"recheck_task","task_id":"<id>"}` | Reverify the saved answer and replace its old published turn after matching identity/hash checks, without another Send. |
| `{"cmd":"cancel_task","task_id":"<id>"}` | Signal Stop; confirm the subsequent saved task state. |
| `{"cmd":"coordinator_selfcheck"}` | Run synthetic coordinator checks on Android. |
| `{"cmd":"research_selfcheck"}` | Run focused Gemini/hotel fixtures, including whole-response Copy checks reported as `gemini_copy`. |

The fake-driver and Copy fixtures do not operate Gemini or establish full live capture. The broad legacy `selfcheck` includes native wake-model execution and crashes with SIGILL on the emulator; use focused checks for this work. See the [coordinator plan](COORDINATOR_PLAN.md) for evidence and the open M1 gate.

## Email draft handoff

Email uses `ACTION_SENDTO` with `mailto:`. Subject/body URI fields use percent encoding; Friday retains the original extras as well. These formats follow [Android common intents](https://developer.android.com/guide/components/intents-common#Email) and [RFC 6068](https://www.rfc-editor.org/rfc/rfc6068). Friday rejects multiline/control subjects and retains the draft with an explicit failure.

The initial Gmail test opened a composer with empty subject/body. After the URI fix, an operator screenshot verified the exact synthetic recipient, subject and body without manual edits; nothing was sent. Gmail still exposed no app accessibility root/package, so Friday reported **unverified** rather than claiming automatic readback. Transport/population has assisted visual evidence; autonomous field verification remains open. The latest Writer suite generated no text because memory preflight blocked loading.

## Safety (D-011, D-014, D-025, D-026)
- **Approvals (D-025):** safe steps run on their own; typing and taps on send/pay/buy/book/delete/call/confirm/allow…
  always ask (Run / Skip / Stop). Stop on three identical actions or three skips.
- **Money apps are read-only (D-026):** Friday never types or taps Pay/Send/Transfer in UPI/bank/card apps, even if
  you tap Run. On fingerprint, screen-lock and UPI-PIN screens it waits for you and never acts.
- **No `isAccessibilityTool` flag** — so Android keeps permission dialogs and installer buttons out of the agent's
  reach. System Allow dialogs (Wi-Fi, Bluetooth) are yours to tap. Apps that block accessibility are respected.
- Diagnostic captures stay in ignored `captures/`. Friday stores conversations, answers and bounded task evidence in its private files/database. Clear this chat cancels and purges its tasks and transcript; late workers cannot recreate cleared results.
