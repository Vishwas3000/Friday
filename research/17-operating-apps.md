# Operating apps on the phone: why Friday "only opened apps", the alarm bug, and speed

_Last updated: 2026-10-17 · Trust tags: [primary] read at source · [measured] run here · [UNCERTAIN]_

Live findings from the Pixel 8 on 2026-10-17, using the step trace (`{"cmd":"ask"}` returns the answer
plus every route, app opened, Laya decision, safety verdict and exit) and the phone's logcat.

## TL;DR [measured]
- **Friday opened apps and then stopped, for two reasons, both fixed:**
  1. **The open_app skill stopped after opening.** Every request starting "open…" matched that rule first, so
     "open X and do Y" never reached Laya. Now the rest of the request is handed to Laya inside X (D-036).
  2. **Laya wasn't told the app was open.** Laya-Android learned from AndroidControl episodes that start with
     OPEN_APP; with an empty history it tries to open the app again (0.80–0.95), the policy blocks it ("nothing to
     open") and the run gets stuck. Now Friday keeps a trail of what it did, intent launches included (D-036).
- **Proof:** "open the clock app and go to the stopwatch tab" → opened Clock → **CLICK "Stopwatch" (0.96)** →
  CLICK "Start" (0.55, *not* asked for) → DONE (0.92).
- **Still weak: stopping.** Laya took one step past the goal at low confidence. Fixes: a "goal done?" check and
  Teach-mode DONE examples for fine-tuning (D-033).
- **Alarm bug:** "set an alarm for 8 mins from now" produced a *timer* (Clock › Timer), the owner looked under
  Alarms, and Friday had said "set" without checking. Now "alarm" means an alarm, verified (D-037).
- **Speed:** a cold Laya request went 24 s → 4.6 s, warm 1.4 s per Laya question (D-030); a 3-step in-app task
  took 28 s cold, ~10 s warm (est.).

## 1. Replay: the history decides whether Laya acts inside the app [measured, Mac, recorded screens]
Same screens from earlier live runs (`captures/run-*.jsonl`), the step right after the app opened, asked twice:

| Screen (already inside the app) | history = [] | history = ["OPEN_APP …"] |
|---|---|---|
| Clock, goal "go to the Timer tab" | **OPEN_APP 0.95** | **CLICK Timers 0.96** (correct) |
| Settings, goal "show the Battery page" | **OPEN_APP 0.86** | BACK 0.66 (correct: it was on a stale sub-page) |
| Settings, goal "About phone…" | **OPEN_APP 0.80** | BACK 0.67 (correct, same reason) |

The trail is the cheapest form of "context": Laya reads the last 3 entries of `history`.

## 2. On-device trace after the fixes [measured, Pixel 8]
```
route: open_app
opened: Clock
step 1  CLICK "Stopwatch"  (op 0.96) -> AUTO
step 2  CLICK "Start"      (op 0.55) -> AUTO      <- one step past the goal
step 3  DONE               (op 0.92)
```
28.0 s total, Laya cold after a reinstall. The unwanted step had the lowest confidence of the run, so a
completion check could trigger on low-confidence steps.

## 3. The alarm bug [measured, Pixel 8 logcat]
- The old rule turned "for 8 mins" into a duration, which became `ACTION_SET_TIMER`. Logcat:
  `act=android.intent.action.SET_TIMER cmp=…deskclock/…HandleSetApiCalls (BAL_ALLOW_VISIBLE_WINDOW) result code=0`,
  and Clock's notification showed `RUNNING … Σ0:08:00`. **The timer worked; it was the wrong thing.**
- **Fix:** the owner's word decides. "alarm", "wake me" or "remind" makes an alarm at now + duration, rounded to
  the minute. Only "timer" makes a timer.
- **Verify before claiming:** read `AlarmManager.getNextAlarmClock()` until it shows the new alarm (3 s). If an
  earlier alarm hides it, Friday says it couldn't confirm.
- **Result:** "Alarm set for 5:19 AM (in 8 min) — Clock › Alarms", 0.8 s, confirmed.
- A timer can't be read back through a public API, so its answer says where to look (Clock › Timer).

## 4. Speed [measured, Pixel 8] (D-030)
| | before | now |
|---|---|---|
| first request needing Laya | ~24 s (model 10 s + tokenizer 8 s + first pass 4.5 s, in series) | **4.6 s** (model 2.8 s ‖ tokenizer 2.3 s, warm-up pass, 1.4 s question) |
| later requests (warm, 3 min) | ~24 s each | **1.4 s** per Laya question |

The 4.6 s had the files in Android's page cache; a truly cold cache can still take ~10 s, which pre-warm on
opening Friday hides.

## 5. Operational lessons [measured]
- **Uninstalling wipes Laya's files and the accessibility enable.** Enabling right after an install can race
  Android's re-check of services and be wiped. `setup_phone.py` now retries and re-pushes Laya's files.
- **Silent failures:** a request once stopped after showing its banner, with no log and no crash (most likely the
  setup race). The assistant now catches `Throwable`, and the trace shows exactly where a run ends.
- **The generic fallback must not let Laya pick apps from the launcher.** On 2026-10-17 it opened ICICI iMobile,
  which at once probed for ~14 rooting tools (its security SDK) and refuses to run with Developer options on
  (research/11). Unresolved apps should ask the owner, not guess (research/12).
- **Test side effects are real:** test runs left timers, an alarm and a running stopwatch. Prefer read-only test
  goals, and clean up after tests that change state.

## Open
- A completion ("goal done?") check, triggered on low-confidence steps; measure it on the trace.
- Teach mode (D-033) to collect DONE and in-app examples; fine-tuning per research/14–15.
- Re-run the four in-app tasks (Maps search, Chrome search, Spotify, dark mode) with traces.
