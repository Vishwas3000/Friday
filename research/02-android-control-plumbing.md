# Controlling a non-rooted Pixel 8 (Android 16) for Laya-Android: reading the screen and acting on it

_Last updated: 2026-10-16 · Trust tags: [primary] read at source · [secondary] search/blog/forum · [measured] run here · [UNCERTAIN] unconfirmed or conflicting_

**Scope.** Reading the screen and acting on it for Laya-Android on the user's own Pixel 8: Android 16, SDK 36, patch 2026-05-05, user build, not rooted. This is a personal research agent operating the owner's own device. Live numbers from the adb harness come from [research/05-live-tests.md](05-live-tests.md) and are tagged [measured].

**Version notes.**
- "16 r1" / "16 r4" = AOSP tags `android-16.0.0_r1` (2025-06) and `android-16.0.0_r4` (2025-11-07). "17 r1" = `android-17.0.0_r1`.
- API levels come from the SDK's `platforms/android-37.0/data/api-versions.xml`.
- AOSP `main` on googlesource has been frozen since 2025-03-26 [primary], so it is **not** cited for Android 16 behaviour.
- Nothing here was run on the phone. Every [primary] claim is a reading of code or docs.

## TL;DR
- **adb works today, but reading the screen costs about 2.5 s per step.** Each `uiautomator dump` starts a JVM, connects a fresh UiAutomation, and waits for **1 s with no accessibility events**, giving up after 10 s with "could not get idle state" [primary, 16 r1]. While it runs it **unbinds every enabled accessibility service** [primary]. Measured: 2.5 s warm, 4.6 s cold [measured]. Acting is cheap: since Android 12, `input`, `am` and `pm` are `cmd` binder calls with no JVM start [primary].
- **An on-device AccessibilityService covers (a)-(f) without root** [primary]: a forest of every window + `takeScreenshot`; node actions and `dispatchGesture` with up to 20 strokes (pinch/drag/multi-touch); `getLaunchIntentForPackage` (exempt from the background-launch block); unicode `ACTION_SET_TEXT`; global actions for back/home/recents/notifications/quick-settings/lock; and an event stream to debounce. One author measured tree export at **18-33 ms** on a Pixel 7 [secondary].
- **Laya-Android must see its training format.** It was trained on AndroidControl's android_env forests, built with `getWindows()`, important-views-only, carrying `editable` [primary]. `uiautomator dump` has no `editable` and covers only the active window unless you pass `--windows` [primary]. AndroidWorld's Apache-2.0 `AccessibilityTreeCreator.kt` is the reference to port.
- **Enabling the agent's own service is a soft step for an `adb install`ed app on Android 16** [primary code; UNCERTAIN until run here]. "Restricted settings" (ECM) targets installs from local/downloaded files; the accessibility bind path in 16 r1-r4 has no ECM check (Android 15 did). So `adb install` + `settings put secure enabled_accessibility_services` should suffice.
- **Declare `android:isAccessibilityTool="true"`.** The platform never verifies it [primary]. It lets the service read views marked `accessibilityDataSensitive`, avoids the privacy notification, and survives Android 17's opt-in Advanced Protection block. FLAG_SECURE blanks captured pixels but not the tree [primary].
- **Banking/UPI and other high-security apps are out of scope by design.** Any app can list enabled accessibility services and refuse to run, and Play Integrity flags non-Play automation tools [primary]. The agent should not target them; this note treats that as a boundary, not an obstacle to work around.
- **Shizuku and Termux are add-ons, not foundations.** Shell UID without root needs wireless debugging, hence Wi-Fi and a restart after every reboot [primary]. Upstream Shizuku has been dormant since 2025-06 and breaks on Android 17 [primary/secondary].
- **Recommendations.** POC: read the tree with uiautomator2 3.7 (MIT; no idle floor; all windows), keep the existing adb actions. End state: an own Kotlin AccessibilityService that emits forwarder-format forests and runs Laya in-process.

## 1. What the plumbing must hand to Laya-Android

### 1.1 Training data and candidate format [primary: github.com/Byun11/laya-android @ab216d4]
- **Training data:** AndroidControl only. Its trees are android_env `AndroidAccessibilityForest` protos: one tree per window, built from `AccessibilityNodeInfo` [primary: laya-android README + `src/data/android_control.py`; AndroidControl README].
- **Capture devices:** physical Pixels on Android 8+ [primary: arXiv 2406.03679]. The capture app is unnamed [UNCERTAIN].
- **Fields read:** text, content_desc, hint, class, package, resource_id; the flags checkable, checked, clickable, **editable**, enabled, focusable, focused, long_clickable, password, scrollable, selected, visible; plus bounds, child_ids, depth, window_type.
- **Candidate rules** (`src/candidates.py`): keep a node if it is not in an IME window, has at least one of clickable/long_clickable/editable/scrollable/checkable, is visible, and still has area after its box is clipped to the screen.
- **Duplicates and windows:** nodes with the same (box, label) collapse to the deepest, flags OR-ed. **Every non-IME window is used, system windows included.**
- **Option string** (`src/serialization.py`): `"<label[:40] or '-'> (<short class>) <flags>"`. Label = own text, else content_desc, else hint, else descendant texts, capped at 60. Flags in order: `edit`, `long` (only if not clickable), `scroll` (only if not clickable), `on`, `selected`, `disabled`.
- **Typical size:** 222 nodes/screen mean, 17 candidates mean (p99 60) [primary].
- **The runtime must supply two things the model never produces:** the text to type and the app name. The repo has no device-control code [primary].

### 1.2 Tree-source parity [primary unless tagged]
| Source | Windows | `editable` | Invisible nodes | Not-important views | Verdict |
|---|---|---|---|---|---|
| AndroidControl / android_env forwarder | all, `getWindows()` | yes | kept, flagged | **excluded** (`flagDefault\|flagRetrieveInteractiveWindows\|flagReportViewIds`) | reference |
| `uiautomator dump` (16 r1, default) | **active window only** | **missing** | dropped | **included** (default sets `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS`) | 3 mismatches |
| `uiautomator dump --compressed --windows` | all | missing (infer from class, as research/05 does) | dropped | excluded | closest adb-only option; `--compressed` no slower [measured] |
| uiautomator2 `dump_hierarchy()` | all by default; adds `visible-to-user` | missing | `compressed=` flag | configurable | fine for a POC [UNCERTAIN on exact attrs] |
| own AccessibilityService, modelled on the forwarder | all | yes | your choice | your choice | exact match possible |
| Mobilerun (DroidRun) Portal `a11y_tree_full` | app windows above active; system only if no active root | yes | 1%-visible filter (`?filter=false`) | included | recompute candidates |

- Dump attributes `hint` and `drawing-order` first appear in 16 r1; the android-15 dumper lacks them [primary].
- The accuracy cost of each mismatch is unmeasured [UNCERTAIN].

### 1.3 Mapping each Laya op to a mechanism
| Op | A: adb from host | B: AccessibilityService | Notes |
|---|---|---|---|
| CLICK | `input tap x y` | `ACTION_CLICK` on the node or clickable ancestor; else a 50-60 ms `dispatchGesture` stroke | Blurr: click, diff the tree, fall back to a tap [primary] |
| LONG_PRESS | `input swipe x y x y 800` | `ACTION_LONG_CLICK`; else a stroke of `getLongPressTimeout()` + margin | timeout defaults to 400 ms, user-adjustable [primary] |
| SCROLL_* | `input swipe`. **SCROLL_DOWN = finger moves up** | `ACTION_SCROLL_DOWN/UP/LEFT/RIGHT` (API 23) or `SCROLL_FORWARD/BACKWARD`; else a swipe | AndroidWorld: `down` ends at `y_min`, "swipe" is the "inverse of scroll" [primary]. AndroidControl's own convention unstated [UNCERTAIN] |
| OPEN_APP | `cmd package resolve-activity --components -a android.intent.action.MAIN -c android.intent.category.LAUNCHER <pkg>`, then `am start -W -n <comp>` | `getLaunchIntentForPackage(pkg)` + `FLAG_ACTIVITY_NEW_TASK` | needs a label->package resolver (§2.3, §3.3) |
| INPUT_TEXT | `input text` (ASCII only) or an IME broadcast | `ACTION_SET_TEXT`, or `InputMethod.commitText` (API 33) | §2.4, §3.4 |
| BACK / HOME | `input keyevent 4` / `3` | `GLOBAL_ACTION_BACK` / `HOME` | |
| WAIT / DONE | sleep, re-read / stop | settle wait / stop | the stop decision is weak live [measured] |

## 2. Path A: adb from the host (Mac or Windows, over USB)

### 2.1 Reading the screen (a)
- **`uiautomator dump [--compressed] [--windows] [file]`** (16 r1) [primary: `DumpCommand.java`, `UiAutomationShellWrapper.java`, `UiAutomation.java`, `uiautomator.sh`].
  - Each call runs `app_process … uiautomator.Launcher`: new JVM, fresh UiAutomation, then `waitForIdle(1000, 10000)`.
  - A fresh connection counts idle from its own start, so **each dump waits >=1 s**; any accessibility event resets the wait; after 10 s it prints "ERROR: could not get idle state."
  - A null active root prints "ERROR: null root node returned by UiTestAutomationBridge."
  - Animated screens/videos are the usual trigger. MobileAgentBench: dumps "fail if the app contains persistent animations or videos" [secondary: arXiv 2406.08184]. The main session hit it right after "About phone" opened [measured].
  - **Mitigation:** `settings put global window_animation_scale 0` (and `transition_animation_scale`, `animator_duration_scale`), then retry [secondary].
- **It suppresses accessibility services.** `UiAutomation.connect()` uses flags 0; `suppressingAccessibilityServicesLocked()` is true unless `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` is set. **Every dump unbinds every enabled accessibility service** [primary].
- **One UiAutomation per device.** A second client fails with "UiAutomationService … already registered!" — a dump fails while uiautomator2/Appium/mobilecli is connected, and vice versa [primary].
- **UiAutomation is an accessibility tool by default**, so dumps include `accessibilityDataSensitive` views [primary].
- **Timing.** Measured: ~2.5 s warm, ~4.6 s cold; `--compressed` and `/dev/tty` make no difference [measured]. Split traversal from overhead by reading the dumper's own `"Fetch time: Nms"` via `adb logcat -s AccessibilityNodeInfoDumper` [primary].
- **`/dev/tty` can fail silently.** An `IOException` on the output file is only logged, and the tool still prints "UI hierchary dumped to:". `exec-out uiautomator dump /dev/tty` can "succeed" with no XML. Split stdout at `</hierarchy>` [primary].
- **Node attributes (16 r1):** `index, text, resource-id, class, package, content-desc, checkable, checked, clickable, enabled, focusable, focused, scrollable, long-clickable, password, selected, bounds, drawing-order, hint`, plus `NAF`. `--windows` adds window-level `title, active, focused, layer, type`. Invisible children are skipped [primary].
- **openatx/uiautomator2** (MIT; 3.7.0 on PyPI 2026-06-26; u2.jar 0.4.0) [primary].
  - v3 dropped atx-agent: it pushes `/data/local/tmp/u2.jar`, runs `app_process … com.wetest.uia2.Main -p 9008`, JSON-RPC over an adbutils tunnel. No APK for the core.
  - The server sets `setWaitForIdleTimeout(0)`, so its `waitForIdle(500, 0)` returns at once: **no 1 s floor, no idle-state failure.**
  - `dump_hierarchy()` covers all windows by default; `root_in_active=True` limits it to the active one.
  - Caveats: it also connects with flags 0, so it suppresses services for the whole session [primary]; its NanoHTTPD server binds all interfaces, no auth, exposes `executeShellCommand` [primary; reachability UNCERTAIN]; no published dump timings [secondary].
- **Appium UiAutomator2 driver** (Apache-2.0, 8.7.0, 2026-09-14) [primary]. Every page source calls `waitForIdle` with defaults (500 ms quiet, 10 s cap) unless `waitForIdleTimeout=0`. Three hops: client -> Node -> device HTTP. `appium:disableSuppressAccessibilityService: true` keeps other services alive. No credible page-source timings found [secondary].
- **Screenshot.** `adb exec-out screencap -p` takes **0.65-0.8 s** [measured]. Options `-a`, `-d <id>`, `-p`; raw is ~10.4 MB for 1080x2400 RGBA; no JPEG. FLAG_SECURE windows come out black [primary]. minicap works without root only on SDK <= 28 [primary].

### 2.2 Gestures (b)
- **`input` subcommands** (16 r1): `text`, `keyevent [--longpress|--duration ms] [--doubletap] [--async] [--delay ms]`, `tap`, `swipe x1 y1 x2 y2 [ms]`, `draganddrop x1 y1 x2 y2 [ms]`, `press`, `roll`, `motionevent DOWN|UP|MOVE|CANCEL x y`, `scroll`, `keycombination [-t ms]`, plus `-d DISPLAY` [primary: `InputShellCommand.java`]. `draganddrop`/`motionevent`/`keycombination` since Android 12; `scroll`/`--duration` since 15.
- **Timing.** Swipe defaults to 300 ms, MOVE at 120 Hz; `draganddrop` first holds for the long-press timeout; motion events wait for the app to consume them. Since Android 12 the on-device cost of `tap` is claimed "<10 ms from 500ms" [primary: AOSP commit 9a7d7dbf message; claimed]. A trivial `adb shell` round trip measured 46-86 ms on an emulator [secondary].
- **No multi-touch through adb alone.** `input` hard-codes one pointer; `sendevent` is blocked (shell.te `neverallow shell input_device:chr_file`, 13-16) [primary]. Shell can write `/dev/uhid` (scrcpy uses it), so a virtual multi-touch HID is conceivable [UNCERTAIN].
- **Multi-touch that works without root** uses `UiAutomation.injectInputEvent` from a shell-UID server [primary]: uiautomator2 `pinch_in/out`/`gesture`, Appium `mobile: pinchOpenGesture`/W3C actions, scrcpy's control channel (up to 10 pointers), YADB (LGPL-3.0 jar) `-pinch`/`-longPressDrag`.

### 2.3 Opening apps and mapping labels (c)
- **Launch command speed** [primary: `am.sh`, `pm.sh`, `monkey.sh`]: `am` (except `instrument`) is `cmd activity`, `pm` is `cmd package` — both fast; `monkey` is an `app_process` JVM, and `monkey -p pkg -c android.intent.category.LAUNCHER 1` picks a random launcher activity when there are several and sets `isUserAMonkey()`.
- **Better launch:** `cmd package resolve-activity --components -a android.intent.action.MAIN -c android.intent.category.LAUNCHER <pkg>`, then `am start -W -n pkg/.Act`. `-W` waits for launch completion, doubling as a settle signal [primary].
- **No shell command prints labels** [primary]: `pm list packages` has none; `dumpsys package`/resolve show only `labelRes`/`nonLocalizedLabel`; `cmd launcherapps` has only `dump-view-hierarchies`.
- **Ways to get labels:** a shell-UID helper calling `getApplicationLabel` (scrcpy `--list-apps`, "may take … several seconds") [primary]; a small on-device app; or the main session's alias table [measured] (launcher query 0.15 s, 206 apps [measured]).

### 2.4 Typing (d)
- **`input text` limits** [primary: `InputShellCommand.sendText`]: only `%s` -> space (so no literal "%s"); characters go through `KeyCharacterMap(VIRTUAL_KEYBOARD).getEvents()`, which returns null for unmappable chars with no null check, so **any non-ASCII/emoji aborts the whole string**; quote for the device shell: `adb shell "input text 'a%sb'"`.
- **ADBKeyBoard** (senzhk, **GPL-2.0**; fine as a separate APK, don't link its code) [primary]. Use **v2.5-dev (2026-04-23)**; PR #84 fixed "keyboard unusable on android-36". Setup: `ime enable com.android.adbkeyboard/.AdbIME && ime set …`. Type with `am broadcast -a ADB_INPUT_B64 --es msg <base64 utf-8>` (base64 dodges quoting/code-page issues). Others: `ADB_INPUT_TEXT`, `ADB_INPUT_CHARS`, `ADB_INPUT_CODE`, `ADB_EDITOR_CODE`, `ADB_CLEAR_TEXT`. While it is active the normal soft keyboard is gone [secondary]; `ime reset` restores it.
- **Clipboard.** There is **no `cmd clipboard`** [primary]. A shell-UID helper (scrcpy server, u2.jar) can set the clip, then `input keyevent 279` (PASTE) [primary]. u2's `send_keys` uses the clipboard or its own IME; it hit a `set_clipboard` SecurityException in 2024 (issue #1010) [secondary/UNCERTAIN].
- **Replacing a field's text:** u2 `set_text` or Appium `mobile: replaceElementValue`, both via `ACTION_SET_TEXT` [primary].

### 2.5 Global navigation (e)
- **Keycodes** [primary: `KeyEvent.java` 16]: HOME 3, BACK 4, APP_SWITCH 187 (recents), POWER 26, SLEEP 223 (lock), WAKEUP 224, PASTE 279. `KEYCODE_LOCK` (324) is behind a feature flag.
- **Panels:** `cmd statusbar expand-notifications | expand-settings | collapse | click-tile` [primary].
- **Focus:** `mCurrentFocus=` / `mFocusedApp=` from `dumpsys window displays`; `topResumedActivity=` from `dumpsys activity activities` [primary]. Scripts grepping `mResumedActivity` may break on 16 [UNCERTAIN].

### 2.6 Settle detection (f)
- adb has no settle primitive; the next dump's 1 s idle wait settles implicitly [primary]. Other options: `am start -W` for launches; poll until the candidate-list hash is stable; uiautomator2's `waitForIdle`/`waitForWindowUpdate` RPCs (no Python wrapper — call via `d.jsonrpc`) [primary].
- **Fixed sleeps elsewhere:** Open-AutoGLM 1.0 s after every action, Roubao 500 ms [primary], Maestro 750 ms [secondary].

### 2.7 Transport and watching
- **Wireless debugging** [primary: developer.android.com/tools/adb, 2026-09-25]: `adb pair ip:port` + code (11+); pairing persists until revoked; mDNS auto-connects; "ADB Wi-Fi 2.0" auto-reconnect needs Android 17 + adb 37; `adb tcpip 5555` gives a fixed port but needs USB first. The toggle and port reset on reboot or Wi-Fi change [secondary].
- **scrcpy v5.0.1** (2026-10-08, Apache-2.0) [primary]: `--no-control` to watch only; its "35-70 ms" video latency is a 2019 figure; its control socket also does multi-touch, clipboard, panels and app start.
- **Windows:** "The Google USB Driver is required to perform adb debugging on Windows with Google devices" [primary]. The Python+adb harness is otherwise cross-platform [UNCERTAIN for uiautomator2 on Windows, likely fine].

## 3. Path B: an on-device AccessibilityService (Kotlin)

### 3.1 Reading the tree (a) [primary: AOSP 16 r1 + api-versions.xml]
- **`getRootInActiveWindow()`** (API 16) returns only the *active* window. Since API 33 it takes a prefetch strategy; the default `FLAG_PREFETCH_DESCENDANTS_HYBRID` prefetches at most 50 nodes per request, so a big tree costs extra binder round trips.
- **`getWindows()`** (API 21) / `getWindowsOnAllDisplays()` (API 30) gives AndroidControl's forest. It returns "only the windows that a sighted user can interact with … in a descending layer order", always including the focused window. It needs `canRetrieveWindowContent` + `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`, else an empty list. The IME, system UI and dialogs come back as separate typed windows.
- **Node fields (API level):** `getText`/`getContentDescription`/`getClassName` (14); `getHintText` (26); `getViewIdResourceName` (18, needs `flagReportViewIds`); `isClickable`/`isLongClickable`/`isScrollable`/`isCheckable`/`isEnabled`/`isSelected`/`isPassword` (14); `isEditable` (18); `isVisibleToUser` (16); `isChecked` (14, deprecated in 36 for tri-state `getChecked`); `getBoundsInScreen` (14); `getUniqueId` (33); `getActionList` (21); `getPaneTitle`/`getTooltipText` (28); `getStateDescription` (30); `isAccessibilityDataSensitive` (34).
- **Compose** maps semantics to legacy class names: `android.view.View` by default, `android.widget.EditText` for text fields, `TextView` for text, role names otherwise. `testTag` becomes a resource-id only with `testTagsAsResourceId=true` [primary: androidx-main delegate].
- **Chrome/WebView** build the web tree lazily, and its richness depends on the event types running services request [primary: chromium main `AccessibilityStateDelegateImpl.java`]. Requesting `TYPE_VIEW_SCROLLED`/`TYPE_VIEW_SELECTED`/`TYPE_ANNOUNCEMENT` marks a service "complex user interaction"; a thinner "password-manager-like" mask gets optimisations. **Use `typeAllMask`.** Portal requests only window-state + content-changed, which may get a thinner web tree [UNCERTAIN].

### 3.2 Acting (b) and (e) [primary]
- **Node actions:** API 21 `ACTION_CLICK`/`LONG_CLICK`/`SET_TEXT`/`SCROLL_FORWARD`/`BACKWARD`/`FOCUS`/`SET_SELECTION`/`PASTE`; API 23 `ACTION_SCROLL_UP/DOWN/LEFT/RIGHT`/`SHOW_ON_SCREEN`; later `PAGE_*` (29), `IME_ENTER`/`PRESS_AND_HOLD` (30), `DRAG_START/DROP/CANCEL` (32), `SCROLL_IN_DIRECTION` (34). The platform doc itself suggests a dispatched tap as the fallback when `ACTION_CLICK` isn't implemented.
- **`dispatchGesture`** (API 24, `canPerformGestures`): "cancels any gestures currently in progress, whether from the user, this service, or another service"; plays as a user gesture (magnification/explore-by-touch apply). **Limits in 16 r1: 20 strokes, 60 s total** (older releases 10 strokes [secondary]). Pinch = two concurrent strokes; drag-and-hold uses `willContinue=true` (API 26): "Continued strokes keep their pointers down". Defaults: tap 100 ms, long-press 400 ms (user-adjustable), double-tap 300 ms (`ViewConfiguration`).
- **`performGlobalAction`** by API level: 16 `BACK`/`HOME`/`RECENTS`/`NOTIFICATIONS`; 17 `QUICK_SETTINGS`; 21 `POWER_DIALOG`; 24 `TOGGLE_SPLIT_SCREEN`; 28 `LOCK_SCREEN`/`TAKE_SCREENSHOT` (saves to gallery); 31 `DISMISS_NOTIFICATION_SHADE`/`ACCESSIBILITY_ALL_APPS`; 33 `DPAD_*`; 36 `MENU`/`MEDIA_PLAY_PAUSE`. **Every global op on the list has a constant.**
- **`takeScreenshot(display, executor, cb)`** (API 30, `canTakeScreenshot`) returns a `HardwareBuffer`, no consent prompt. A request within **333 ms** of the previous fails with `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`. Secure layers are captured only by a system-app tool, so a sideloaded app gets them redacted; `takeScreenshotOfWindow` (API 34) returns `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` on a FLAG_SECURE window [primary: `AbstractAccessibilityServiceConnection`]. ~60 ms/screenshot on a Pixel 7 [secondary].
- **MediaProjection is worse for an agent** [primary: media-projection guide]: consent "before each media projection session"; from 14 a `mediaProjection` foreground service; from 15 QPR1 a status-bar chip and auto-stop on lock.
- **`setAnimationScale(0)`** (API 33) disables animations, so "services receive window change events more quickly".

### 3.3 Opening apps (c) [primary]
- **Background launches are allowed.** The system binds every accessibility service with `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS` (+ `BIND_FOREGROUND_SERVICE_WHILE_AWAKE`, foreground priority while awake) in `AccessibilityServiceConnection.bindLocked`. `startActivity(getLaunchIntentForPackage(pkg).addFlags(FLAG_ACTIVITY_NEW_TASK))` works from the service.
- **Package visibility.** Accessibility services get no automatic visibility of other apps. Either declare `<queries><intent><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent></queries>` (categories are allowed inside a `<queries>` intent), or request `QUERY_ALL_PACKAGES` (the docs list "Accessibility apps" as appropriate; Play policy applies only if published).
- **Labels:** `queryIntentActivities(MAIN+LAUNCHER)` -> `loadLabel`, then fuzzy-match `app_name`.

### 3.4 Typing (d), WebView and Compose [primary]
- **`ACTION_SET_TEXT`** (API 21) takes a `CharSequence` (any unicode) and replaces the whole field. Compose maps `SemanticsActions.SetText` to it [androidx-main]; Chrome/WebView handles it for editable web nodes via `setTextFieldValue`, then moves the cursor to the end [chromium main `WebContentsAccessibilityImpl.java`].
- **IME path** (API 33): set `flagInputMethodEditor`, then `getInputMethod().getCurrentInputConnection().commitText(t, 1, null)`; `sendKeyEvent`/`performEditorAction` too. Use for fields reacting only to key/IME input (some OTP boxes, Flutter) [UNCERTAIN until tested].
- **Clipboard fallback:** set the clip, then `ACTION_PASTE` [primary API; untested].

### 3.5 Settle detection (f)
- **`notificationTimeout` does not debounce content changes** [primary: `AbstractAccessibilityServiceConnection.notifyAccessibilityEvent`, 16 r1]: other event types are coalesced (pending replaced, timer restarted), but **`TYPE_WINDOW_CONTENT_CHANGED` events are each delivered**, only delayed.
- **Settle rule:** track the last `WINDOW_STATE_CHANGED`/`WINDOW_CONTENT_CHANGED`/`WINDOWS_CHANGED`/`VIEW_SCROLLED` time; settle after *q* ms quiet, with a hard cap — the same rule `uiautomator` runs with q=1000 ms, 10 s cap [primary]. Suggested q~250-400 ms, cap 2-3 s, plus "candidate-list hash unchanged across two reads" [UNCERTAIN: engineering guess]. On never-settling screens (video, Lottie, clocks), act at the cap.

### 3.6 Lifecycle gotchas [primary]
- **A crashed service stays "enabled but crashed"** (`mCrashedServices`); not rebound until toggled off/on or the package is updated.
- **Before first unlock**, non-`directBootAware` services are ignored.
- **A device-admin allowlist** can block the service (managed phones only).
- **Don't mix in path A:** `uiautomator dump`, uiautomator2 and Appium (without `disableSuppressAccessibilityService`) unbind the service while they run.
- **Non-tool services trigger a privacy notification** after 24 h [primary: attrs.xml + `PolicyWarningUIController`].

### 3.7 Config and snippets
`res/xml/agent_service.xml` keeps the forwarder's tree flags (no `flagIncludeNotImportantViews`) for training parity, plus gestures, screenshots, IME and the tool flag. Flag names checked against attrs.xml in 16 r1 [primary]:
```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows|flagReportViewIds|flagInputMethodEditor"
    android:notificationTimeout="0"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:canTakeScreenshot="true"
    android:isAccessibilityTool="true"
    android:description="@string/agent_desc" />
```
The manifest needs `<service android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE" android:exported="true">` with the `android.accessibilityservice.AccessibilityService` intent filter and `android.accessibilityservice` meta-data [primary: developer guide].

```kotlin
// One primitive covers tap, long-press, swipe and pinch.
fun stroke(vararg p: PointF, ms: Long, start: Long = 0L) = GestureDescription.StrokeDescription(
    Path().apply { moveTo(p[0].x, p[0].y); p.drop(1).forEach { lineTo(it.x, it.y) } }, start, ms)

fun AccessibilityService.gesture(vararg s: GestureDescription.StrokeDescription) =
    dispatchGesture(GestureDescription.Builder().apply { s.forEach { addStroke(it) } }.build(), null, null)

// tap:        gesture(stroke(PointF(540f, 1200f), ms = 60))
// long-press: gesture(stroke(PointF(540f, 1200f), ms = ViewConfiguration.getLongPressTimeout() + 300L))
// scroll down (finger moves up): gesture(stroke(PointF(540f, 1800f), PointF(540f, 700f), ms = 300))
// pinch out:  gesture(stroke(PointF(500f,1200f), PointF(250f,1200f), ms=400),
//                     stroke(PointF(580f,1200f), PointF(830f,1200f), ms=400))

fun AccessibilityNodeInfo.setText(t: CharSequence) = performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t) })
```
- **Reference forest builder:** AndroidWorld's `AccessibilityForwarder.kt` + `AccessibilityTreeCreator.kt` (Apache-2.0) walk `getWindows()` breadth-first per window and emit android_env `AndroidAccessibilityNodeInfo` with `is_editable`, `is_visible_to_user`, actions and depth, polling every 100 ms and pushing over gRPC [primary]. Port the tree builder; replace the gRPC push with a `127.0.0.1` socket or in-process calls.

## 4. Android 13-17 restrictions that affect enabling and running the agent

The agent runs on the owner's own device and operates the owner's own apps. The items below are the platform rules that decide whether the owner can enable the agent's own accessibility service, and where the platform keeps even an enabled service out.

### 4.1 "Restricted settings" (ECM) and whether `adb install` triggers it [primary unless tagged]
- **What it is.** Since Android 13, an app installed outside a store that requests Accessibility, a notification listener and similar access is blocked from being enabled until the owner opens App info -> menu -> **Allow restricted settings** (support.google.com/android/answer/12623953). CDD 16 §9.8 makes it a MUST for apps "installed from a local file"; it is on by default and the confirmation comes from the App info page.
- **Which installs it covers:**
  - Android 13/14 restrict only `PACKAGE_SOURCE_LOCAL_FILE` / `DOWNLOADED_FILE` installs (`InstallPackageHelper`).
  - Android 16 decides lazily in `EnhancedConfirmationService.isPackageEcmGuarded`: preinstalled or signature-allowlisted packages are never guarded; local-file / downloaded-file sources are "always considered dangerous"; otherwise a package is guarded only if its immediate installer is neither preinstalled nor allowlisted **and** the device defines at least one trusted installer. AOSP's `enhanced-confirmation.xml` defines none, which trusts every installer.
  - The state lives in the `ACCESS_RESTRICTED_SETTINGS` app-op (allowed / errored / ignored / decide-lazily), mapped from the ECM state in `EnhancedConfirmationService`.
- **`adb install`:** the shell path sets `INSTALL_FROM_ADB` and leaves the package source unspecified (`PackageInstallerService.createSessionInternal`) — not a "dangerous" source. Pixel's trusted-installer list was empty in the Android 14-era firmware a sub-agent could reach; a Pixel-16 dump was unavailable [UNCERTAIN].
- **Bind-time check, by version** (the decisive difference):

| Version | ECM checked at bind? | Source |
|---|---|---|
| Android 15 (r1) | **yes**: `isAccessibilityTargetAllowed` -> `EnhancedConfirmationManager.isRestricted(pkg, OPSTR_BIND_ACCESSIBILITY_SERVICE)` | [primary] |
| Android 16 (r1, r4) | **no**: device-admin allowlist only; ECM appears only in the Settings-dialog path (`sendRestrictedDialogIntent`) | [primary] |
| Android 17 (r1) | no ECM; checks the device-admin list and the Advanced Protection strict list (`isAccessibilityFeaturePermittedLocked`) | [primary] |
| AOSP `main` (frozen 2025-03) | yes; not what ships on 16 | [primary] |

  - There is still a registration gate: `canRegisterService` requires the `BIND_ACCESSIBILITY_SERVICE` app-op to be `MODE_ALLOWED`; that is its default and no ECM flow changes it [primary].
- **On this Pixel (16):** `adb install app.apk`, then `adb shell settings put secure enabled_accessibility_services <existing-list>:<pkg>/<svc>`, then `settings put secure accessibility_enabled 1` should enable the service without the extra UI tap, because shell holds `WRITE_SECURE_SETTINGS` [primary: Shell manifest] and there is no bind-time ECM check in 16. **Append** to the existing list; overwriting it would turn off TalkBack and any other enabled service. If a future build does restrict it, the owner taps "Allow restricted settings" in App info, or shell runs `appops set <pkg> ACCESS_RESTRICTED_SETTINGS allow` (shell holds `MANAGE_APP_OPS_MODES`), which is what that button does [primary]. **[UNCERTAIN until run here]:** Pixel builds can carry changes not in AOSP tags; NeuralBridge's README reports needing "Allow restricted settings" on "Android 15+" [secondary], which matches the Android 15 code.
- **In-call protection:** with an unknown-number call in progress, ECM also blocks enabling a non-tool accessibility service (`UNTRUSTED_CALL_RESTRICTED_SETTINGS`, behind a flag) [primary].

### 4.2 Advanced Protection Mode (AAPM)
- **Android 16 AAPM** is opt-in and off by default [primary: developer.android.com advanced-protection-mode; support 16339980]. Features: no 2G, no installs from unknown sources (adb installs use a separate `INSTALL_FROM_ADB` path [primary]), no USB data while locked, no WEP, MTE, 72 h inactivity reboot. **It has no accessibility restriction** (`AdvancedProtectionManager` at 16 r4) [primary].
- **Android 17** adds `FEATURE_ID_RESTRICT_NON_TOOL_A11Y_SERVICES`: "services not declaring themselves as … isAccessibilityTool are disabled and blocked from running" [primary: 17 r1]. `getPermittedServicesStrictApm` allows a service only if it is a system app, or a tool whose package contains no non-tool service. Relevant only if this Pixel moves to 17 **and** the owner turns AAPM on. Declaring `isAccessibilityTool="true"` keeps the agent running under it.
- **Android 17 release status** [UNCERTAIN]: the `android-17.0.0_r1` tag exists and press reports a mid-2026 Pixel rollout; this phone is on 16.
- An Android Authority teardown (2026-06-22) suggests AAPM may later also block Developer options, which would end paths A/C/D [secondary/UNCERTAIN].

### 4.3 `accessibilityDataSensitive` and `isAccessibilityTool` [primary]
- `isAccessibilityTool` is API 31; `setAccessibilityDataSensitive` is API 34.
- **Which views are data-sensitive:** one set to YES, one that uses `filterTouchesWhenObscured`, or any descendant of such a view (`View.calculateAccessibilityDataSensitive`). The system marks the runtime-permission dialog, the PackageInstaller install buttons, the ECM dialog, and the SystemUI screen-share/record "Start/Stop" dialog this way.
- **Effect on a non-tool service:** `includeForAccessibility(forNodeTree=true)` returns false for a data-sensitive view when the request is not from a tool, so the nodes are not in the tree and events are dropped; and `onFilterTouchEventForSecurity` drops a `dispatchGesture` that an accessibility service injects onto such a view. A service that declares `isAccessibilityTool="true"` is exempt from both. `adb input` is an ordinary injected event (`KeyCharacterMap.VIRTUAL_KEYBOARD`, device id 0) and is not subject to the accessibility-injection filter [primary].
- **Declaring the flag:** `AccessibilityServiceInfo` parses `isAccessibilityTool` straight from the service XML with no platform verification; it becomes `FLAG_SERVICE_IS_ACCESSIBILITY_TOOL`. Verification exists only as a Google Play policy (demo video + review) for published apps [primary: support/googleplay 10964491]. For the owner's own sideloaded agent it is a one-line manifest attribute. The honest meaning of the flag is "this is assistive technology"; using it on an automation agent is a labelling choice the owner makes for their own device.

### 4.4 FLAG_SECURE [primary]
- **It does not hide the accessibility tree** — there is no FLAG_SECURE gating in the node-retrieval path; the flag governs screenshots and non-secure displays only. Reading the UI of a FLAG_SECURE screen via accessibility still works; only the pixels are protected.
- `adb shell screencap`: secure layers are captured black/redacted. `AccessibilityService.takeScreenshot` (API 30): secure layers redacted unless `isAccessibilityTool() && isSystemApp()`, so a third-party tool gets a redacted image, not an error; rate-limited to one per 333 ms. `takeScreenshotOfWindow` (API 34) on a FLAG_SECURE window -> `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`. MediaProjection blacks out secure windows and (15 QPR1+) auto-stops on lock.
- Android 14 adds a screenshot-detection callback for hardware-button screenshots only (adb/instrumentation excluded); Android 15 adds `addScreenRecordingCallback` so an app can tell it is being recorded [primary].

### 4.5 Apps that detect accessibility services -- a scope boundary, not a target [primary]
- Any app can call `AccessibilityManager.getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)` (`@RequiresNoPermission`, not filtered by the tool flag), see the agent and its flags, and refuse to run or require an allowlist [primary API; secondary: Guardsquare]. Play Integrity's "app access risk" returns capturing/controlling/overlay verdicts and "excludes any verified accessibility services known to Google Play", so a sideloaded non-Play tool shows up as an unknown controlling app [primary].
- Apps can also harden individual surfaces with `setAccessibilityDataSensitive`, `filterTouchesWhenObscured`, `Window.setHideOverlayWindows` (+`HIDE_OVERLAY_WINDOWS`, API 31) and FLAG_SECURE [primary].
- **Consequence for this project:** banking, UPI and comparable high-security apps are expected to detect and block the agent, and the agent is not intended to drive them. This note treats that as the correct boundary — the plan is to operate everyday apps the owner chooses, and to leave these apps to their own front ends. We do not pursue ways to defeat these protections.

### 4.6 Keyguard / lock screen [primary]
- **Insecure keyguard (no PIN):** `adb shell wm dismiss-keyguard` (via `KeyguardDelegate.dismiss`), or `input keyevent KEYCODE_WAKEUP`/`MENU`, wakes/dismisses a swipe lock. A service can wake via buttons.
- **Secure keyguard (PIN/biometric):** neither adb nor a service can bypass it; this is a hard stop, and the agent should wait for the owner to unlock. `GLOBAL_ACTION_LOCK_SCREEN` (API 28) can *lock* the device.
- **Before first unlock / direct boot:** a non-`directBootAware` service is ignored until first unlock.
- Notifications/quick-settings global actions exist (4/5/15); their behaviour over a secure lock screen depends on the owner's lock-screen privacy setting [UNCERTAIN].
- **Inactivity reboot:** a device locked ~3 days reboots into before-first-unlock (Play services 25.14, Apr 2025) [secondary]; AAPM adds a 72 h version [primary]. A long-idle headless agent will land in that state and need an unlock.

### 4.7 Background activity launch -- non-issue [primary]
- The service is bound with `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS`, and the BAL doc lists "bound by a service that has been granted permission to start background activities" as an exception, so `startActivity` from the service works. The 14/15 PendingIntent opt-ins don't apply (direct `startActivity`, not a PendingIntent).

## 5. Path C (Shizuku) and Path D (Termux + Python)

Both give shell-UID powers without root, and both depend on wireless debugging. That means Wi-Fi, and a restart after every reboot [primary]. They suit prototyping, not a shipped product.

### 5.1 Shizuku [primary unless tagged]
- **Status.** Upstream v13.6.0 (2025-05-25) notes "Support Android16 QPR1"; last commit 2025-06-18 — effectively dormant [primary]. App management "fails on Android 17" (issue #2180) [secondary]; a fork (thedjchi/Shizuku) added "Android 17 support" in v13.7.0 but is paused [primary]. Several Android-16 `UserService` NPE reports cluster on MediaTek phones, none on Pixel [secondary].
- **Licence.** App Apache-2.0 (name/icon/app-id/permissions not reusable); Shizuku-API client MIT 13.1.5 [primary].
- **Start on a non-rooted Pixel 8.** Pair once over wireless debugging, restart after every reboot [primary]. Wi-Fi is mandatory: `AdbDebuggingManager` turns adb-over-Wi-Fi off when Wi-Fi is off, unconnected, or the BSSID changes [primary]. 13.6.0 auto-start on 13+ works only on a network marked "always allow". No-Wi-Fi fallback: start it from the Mac over USB each boot (`adb shell <nativeLibraryDir>/libshizuku.so`, or after `adb tcpip 5555` via `127.0.0.1:<service.adb.tcp.port>`). Android 17 + platform-tools 37 ("ADB Wi-Fi 2.0") would remove the Wi-Fi friction [primary].
- **What a client gets** [primary: Shizuku-API]: `ShizukuBinderWrapper` for system-service binder calls as shell (uid 2000); `UserService` runs your AIDL class in a shell-UID process (but `registerReceiver`/`getContentResolver` don't work there); `newProcess` is deprecated. The Shell UID holds `INJECT_EVENTS`, `RETRIEVE_WINDOW_CONTENT`, `READ_FRAME_BUFFER`, `WRITE_SECURE_SETTINGS`, `MANAGE_APP_OPS_MODES`, `MANAGE_ENHANCED_CONFIRMATION_STATES`, `QUERY_ALL_PACKAGES`, `STATUS_BAR` — but not `CAPTURE_SECURE_VIDEO_OUTPUT`, so secure screens can't be captured [primary: Shell manifest].
- **Best pattern: a long-lived UiAutomation with shell privileges.** It reads the same node tree as a service, with no accessibility service enabled (so no restricted-settings question). GKD (gkd-kit/gkd, GPL-3.0) keeps a UiAutomation in-process and relays privileged calls through Shizuku; xjunz/AutoTask (Apache-2.0) builds `new UiAutomation(looper, UiAutomationConnection())` in a UserService [primary]. **Connect with `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`**, or it unbinds every enabled service; **only one UiAutomation at a time**, so it conflicts with `uiautomator dump`, u2 and Appium [primary].
- **Capabilities.** Tree: a connected UiAutomation, or `uiautomator dump` (same 1 s floor). Screenshot: `screencap` / `UiAutomation.takeScreenshot` (secure screens black). Gestures: `input` (single-finger; `cmd input` with no fork on 12+), multi-touch via injected `MotionEvent`s. Text: `input text` (ASCII only), or clipboard+PASTE, or ADBKeyboard. Nav: `input keyevent`, `cmd statusbar`. Settle: UiAutomation's `waitForIdle`.
- **Enabling the agent's own service from shell:** a settings write plus, if needed on this build, `appops set <pkg> ACCESS_RESTRICTED_SETTINGS allow`, appending to the existing service list [UNCERTAIN result, from code]. On Android 16 the plain settings write should already suffice (§4.1).

### 5.2 Termux + Python [primary unless tagged]
- **Status.** Stable v0.118.3 (2025-05-22) fixes an Android-16 QPR1 crash; builds target SDK 28; the Play build is "experimental" [primary]. `pkg install` gives android-tools 37.0.0p1, python 3.14, lxml, pillow [primary].
- **Termux alone can't tap or read the screen** — it is an ordinary app without `INJECT_EVENTS` or `RETRIEVE_WINDOW_CONTENT`. It must go through loopback adb or Shizuku's `rish` [primary + inference].
- **Loopback adb:** enable wireless debugging, `adb pair 127.0.0.1:<pair-port> <code>` (Settings > Wireless debugging, in split-screen), `adb connect 127.0.0.1:<connect-port>`, then `uiautomator dump` / `input` / `screencap` on-device. Ports change when a window moves or adbd restarts [primary/secondary]. `rish -c '<cmd>'` runs a shell command through Shizuku with no pairing (copy the rish file into Termux home; it must not be writable on 14+) [primary].
- **Phantom process killer (12+):** Android kills app child processes beyond 32. Android 14+ has a Developer-options toggle "Disable child process restrictions"; 12L+ also `settings put global settings_enable_monitor_phantom_procs false` [primary/secondary].
- **uiautomator2 on-device:** u2 runs its jar via `app_process` over `adb shell`, so it should work inside Termux over loopback adb; the same jar could be started from `rish` or a UserService and called at `127.0.0.1:9008` [UNCERTAIN: no report found].

## 6. How existing tools do it

All read at their repos, not run. Commits as of 2026-10-16.

| Tool (license) | Eyes | Hands | Text | App launch | Settle | Reusable? |
|---|---|---|---|---|---|---|
| **Mobilerun Portal** (ex-DroidRun; **MIT up to v0.7.17 (2026-07-02); AGPL-3.0 from `1f67cb73`**) | AccessibilityService (`isAccessibilityTool=true`, events WINDOW_STATE+CONTENT_CHANGED). `a11y_tree_full` JSON with `isEditable`/`isVisibleToUser`/bounds; `takeScreenshot` or MediaProjection | `dispatchGesture`: 50 ms tap, single-stroke swipe; no pinch/drag (their choice) | own IME (base64 UTF-8), fallback `ACTION_SET_TEXT` | `getLaunchIntentForPackage`; `packages` endpoint returns labels | none | **Yes, v0.7.17 APK or fork (MIT).** Caveats below |
| droidrun Python client (`mobilerun-core-local` 0.6.3, MIT) | Portal over `adb forward` :8080 ("significantly faster", no number), fallback content provider, fallback `uiautomator dump /dev/tty` | **adb `input`**, not Portal gestures; `drag` unimplemented | ASCII `input text` | `cmd package resolve-activity --brief` + `am start` | none | setup recipe below |
| **mobile-next/mobile-mcp** (Apache-2.0) | `uiautomator dump /dev/tty`, <=10 retries on null root; `screencap -p` | `input tap/swipe`; long-press = zero-distance swipe | ASCII `input text`; non-ASCII via DeviceKit clipboard + PASTE (DeviceKit archived 2026-09) | `monkey … LAUNCHER 1`; `appName = packageName` (no labels) | none | small reference |
| **Open-AutoGLM** (zai-org, Apache-2.0) | screenshots only | `input` | ADBKeyBoard (GPL-2.0) via `ADB_INPUT_B64`, restores prior IME | ~160-entry name->pkg dict + `monkey`; current app from `dumpsys window` | fixed 1.0 s | patterns only |
| **StepFun GELab-Zero** (code MIT) | screenshots | `input` + **YADB** (LGPL-3.0 `app_process` jar): long-press, inertia-free swipe, `-longPressDrag`, `-pinch`, `-keyboard` unicode | YADB | hardcoded map + `monkey` | fixed sleeps | YADB shows `app_process` can give adb-only multi-touch |
| **Roubao** (Turbo1123, MIT) | Shizuku `UserService` `sh -c` -> `screencap` + 500 ms delay | `input tap/swipe` | ASCII `input text`; else clipboard + PASTE; else ADBKeyBoard | `QUERY_ALL_PACKAGES` + `getApplicationLabel` + pinyin; `monkey` | fixed delays | only Shizuku agent found |
| **Blurr / Panda** (custom **non-commercial**) | AccessibilityService; largest TYPE_APPLICATION root -> uiautomator-compatible XML | `ACTION_CLICK` then tree-signature diff; fallback `dispatchGesture` tap | `ACTION_SET_TEXT` | `getLaunchIntentForPackage` + `QUERY_ALL_PACKAGES` | signature diff | idea only (license) |
| **AndroidWorld forwarder** (android_world, Apache-2.0; android_env @8e41dd0) | AccessibilityService, `getWindows()` forest -> AndroidAccessibilityForest proto every 100 ms over gRPC | none | none | none | none | **Yes: the reference tree builder** |
| **openatx/uiautomator2** (MIT, 3.7.0) | on-device server, JSON-RPC :9008; `dump_hierarchy()` all windows; `waitForIdleTimeout` settable | UiAutomation injection (gestures, pinch) | own IME | `app_start` | `waitForIdleTimeout` | **yes, the POC tree+action library** |
| **NeuralBridge** (dondetir, Apache-2.0) | in-process AccessibilityService, HTTP | in-process | in-process | in-process | — | perf reference (§7) |

**Portal reuse caveats** [primary, read at tag v0.7.17]:
- Its `a11y_tree_full` has every field Laya-Android needs, but differs from the training trees: `flagIncludeNotImportantViews` adds nodes, system windows are dropped while an app window is active, and a 1%-visibility filter applies. Call it with `?filter=false` and recompute candidates with laya-android's `candidates.py`. The accuracy cost is [UNCERTAIN].
- The v0.7.17 manifest requests `RECEIVE_SMS`, `READ_CONTACTS`, `MANAGE_EXTERNAL_STORAGE`, `REQUEST_INSTALL_PACKAGES`, `SYSTEM_ALERT_WINDOW`, `KILL_BACKGROUND_PROCESSES`, `INTERNET`. droidrun installs with `adb install -g` (grants all runtime permissions). **Install without `-g`** if reusing it.
- The cloud reverse WebSocket (`wss://api.mobilerun.ai/...`) is off by default (`reverseConnectionEnabled=false`). The REST (:8080) and WebSocket (:8081) servers are off by default; when on they bind a `ServerSocket(port)` on all interfaces behind a bearer token (LAN-reachable on Wi-Fi). The content provider allows only UID 2000/root/self [primary].
- Setup recipe: `adb install`, `settings put secure enabled_accessibility_services com.mobilerun.portal/...MobilerunAccessibilityService`, `settings put secure accessibility_enabled 1`, `ime enable/set`. Package `com.mobilerun.portal` from v0.7.0 (was `com.droidrun.portal` to v0.6.5).

## 7. Latency budget per step

| Operation | Number | Status |
|---|---|---|
| `input tap`, on-device, Android 12+ | <10 ms (was 500 ms) | claimed, AOSP commit 9a7d7dbf message [primary doc, claimed] |
| trivial `adb shell` round trip (getprop/settings/dumpsys top) | 46 / 69 / 86 ms | measured by V2EX author on an emulator [secondary] |
| `uiautomator dump` (this phone) | ~2.5 s warm, ~4.6 s cold | measured here [measured] |
| `uiautomator dump` floor | >=1 s (idle wait) + JVM start + traversal | derived from 16 r1 code [primary] |
| `uiautomator dump` general | 1-5 s | claimed (NeuralBridge/a11y-bridge authors) [secondary] |
| `screencap -p` (this phone) | 0.65-0.8 s | measured here [measured] |
| Laya decision on M5 GPU | 37 ms (3 cands) to 793 ms (100 cands) | measured here [measured] |
| accessibility-service tree export | 18-33 ms (Pixel 7, A14, Wi-Fi, 100 runs) | measured by NeuralBridge author, self-reported [secondary] |
| accessibility-service tap / text / screenshot | ~2 ms / ~1.4 ms / ~60 ms | same self-reported run [secondary] |
| a11y-bridge tree read | ~50 ms | claimed [secondary] |
| Appium page source | <=10 s idle cap, 3 hops | floor from code [primary]; no step number found |
| uiautomator2 dump | no idle floor | code [primary]; no published number [secondary] |

Floors from code [primary]: dump >=1 s + JVM; Appium <=10 s idle cap; u2 0 idle wait; swipe >= its duration (300 ms default); drag >= 400 ms + duration; service screenshot >= 333 ms apart.

**Step budget (observe + decide + act):**
- adb today [measured]: ~2.5 s dump + 0.1-0.8 s decide + ~0.1 s act ~= **2.7-3.4 s**, about 90% in the dump.
- adb with uiautomator2 [projected]: no 1 s idle floor; a sub-second dump is plausible, so **~1-1.5 s/step** [UNCERTAIN until measured].
- on-device service [projected from secondary]: ~20-60 ms tree + Laya (tens of ms once the model runs on the phone) + ~2-60 ms act ~= **sub-200 ms**, if Laya runs fast on-device (see research/04).

## 8. Capability matrix

Legend: Y works; Y* works with a caveat; N not without another layer; — n/a.

| Capability | A1 raw adb | A2 uiautomator2 (host) | B AccessibilityService | C Shizuku + UiAutomation | D Termux loopback |
|---|---|---|---|---|---|
| (a) UI tree | Y, active window, no `editable`, ~2.5 s, suppresses services | Y, all windows, no idle floor | **Y, forest, `editable`, parity, ~20-60 ms** | Y (UiAutomation), shell | Y (= raw adb on-device) |
| (a) screenshot | Y `screencap`, secure=black | Y | Y `takeScreenshot`, 333 ms gap, secure redacted | Y, secure=black | Y |
| (b) tap/long-press/swipe | Y `input` | Y | Y actions + `dispatchGesture` | Y `input`/inject | Y |
| (b) pinch/multi-touch | **N** (single pointer) | Y (inject) | **Y** (<=20 strokes) | Y (inject) | N raw / Y via u2 |
| (c) open app by name | Y* (label map needed) | Y* | Y `getLaunchIntentForPackage` (+queries) | Y* | Y* |
| (d) type ASCII | Y `input text` | Y | Y `ACTION_SET_TEXT` | Y | Y |
| (d) type unicode/emoji | **N** w/o ADBKeyboard | Y (IME/clipboard) | **Y** `ACTION_SET_TEXT`/`commitText` | Y* (clipboard/IME) | N w/o ADBKeyboard |
| (e) back/home/recents/notif/QS/lock | Y keyevents + `cmd statusbar` | Y | Y `performGlobalAction` | Y | Y |
| (f) settle detection | Y* (implicit 1 s) | Y* (`waitForIdle` RPC) | **Y** (event debounce) | Y* (`waitForIdle`) | Y* |
| Training-format parity | poor (3 mismatches) | fair | **exact (portable)** | good (same tree) | poor |
| Runs with no PC | N | N | **Y** | N (Wi-Fi + reboot restart) | Y* (Wi-Fi + reboot) |
| Setup friction | **lowest** (adb only) | low (`pip install`, pushes jar) | medium (build+install APK, enable service) | high (Shizuku app, pair, per-boot) | high (pair, phantom-proc) |
| Suppresses other a11y services | yes | yes | no | avoidable (flag) | yes |
| Survives on Android 17 | Y | Y | Y (tool flag) | at risk (Shizuku) | at risk |

## Recommendations

1. **POC tethered to the Mac over USB — read with uiautomator2 3.7, keep adb actions.**
   - *Why:* uiautomator2 removes the ~1 s idle floor and the "could not get idle state" failures that dominate the current ~2.5 s step [primary + measured], dumps all windows (closer to the training forest), needs no APK, and is MIT. Actions already work over `adb input` [measured]. Add `--compressed`-style important-views filtering and infer `editable` from class, as research/05 does, to approximate the training format.
   - *Rejected:* raw `uiautomator dump` (the 1 s floor and idle failures are the measured bottleneck); DroidRun/Mobilerun Portal (fastest tree, but HEAD is AGPL and asks for SMS/contacts/storage — only v0.7.17 is MIT, and its tree still needs `?filter=false` + candidate recompute; reconsider if u2 parity proves too low); Appium (a 10 s idle cap and three hops, heavier for no gain here).

2. **End state, fully on the phone, still local — an own Kotlin AccessibilityService that runs Laya in-process.**
   - *Why:* it is the only path that needs no PC and gives the exact training format — a `getWindows()` forest with `editable`, important-views-only — by porting AndroidWorld's Apache-2.0 `AccessibilityTreeCreator.kt`. It covers every capability (a)-(f) on Android 16 without root [primary], with a tree read in tens of ms [secondary]. Declare `isAccessibilityTool="true"` to read data-sensitive views, suppress the privacy notice, and survive Android 17 AAPM. Enable it once with `adb install` + `settings put secure …` (no bind-time ECM gate on 16); after that it is self-contained. Pair it with the research/04 on-device Laya runtime.
   - *Rejected:* Shizuku/Termux (shell UID without root is powerful and avoids the restricted-settings question, but both need Wi-Fi and a restart after every reboot, and upstream Shizuku is dormant and breaks on Android 17 — too fragile for a product); MediaProjection for pixels (per-session consent, a foreground service, and auto-stop on lock); bundling Portal (AGPL at HEAD, heavy permissions).

3. **Keep the scope to the owner's everyday apps.** Banking/UPI and similar high-security apps detect and block accessibility services by design, and Play Integrity flags non-Play tools [primary]. Treat those as out of scope rather than something to defeat, and gate irreversible actions behind an owner confirmation, as the Laya model card itself advises [primary].

## Open questions
- Does `adb install` + `settings put secure enabled_accessibility_services` enable the service on this exact Pixel-8 build without "Allow restricted settings"? Code says yes for Android 16 [primary]; confirm with one install [UNCERTAIN]. This is the single highest-value thing the main session can verify.
- How much accuracy does Laya lose on a `uiautomator dump`/uiautomator2 tree versus the training forest (missing `editable`, extra not-important views, window coverage)? Unmeasured [UNCERTAIN].
- Will this Pixel update to Android 17, and will the owner enable AAPM? That decides whether the tool flag is load-bearing [UNCERTAIN].
- uiautomator2 `dump_hierarchy` timing on this phone, and whether it un-suppresses other services via `setConfigurator` [UNCERTAIN].
- Behaviour of notification/quick-settings global actions over a secure lock screen [UNCERTAIN].
- Which exact capture app produced AndroidControl's trees (for byte-level parity) [UNCERTAIN].

## Dead ends
- Raw `uiautomator dump` for speed: a hard ~1 s idle floor plus JVM start; `--compressed` and `/dev/tty` gave no gain [measured + primary].
- `sendevent` for multi-touch: SELinux-blocked for shell on 13-16 [primary].
- `cmd clipboard`: does not exist [primary].
- `input text` for unicode: aborts the whole string on any unmappable char [primary].
- minicap without root: SDK <= 28 only [primary].
- Wireless debugging without Wi-Fi: no documented way [primary].
- AOSP `main` as an Android-16 reference: frozen since 2025-03 [primary]; use the release tags.
- Homebrew Python 3.15 for parsing dumps: `xml.etree`/pyexpat is broken on macOS 26.2 [measured]; use the system `/usr/bin/python3`.

## Sources
- laya-android: github.com/Byun11/laya-android · AndroidControl: github.com/google-research/google-research/tree/master/android_control · arXiv 2406.03679
- android_env (forwarder + protos, Apache-2.0): github.com/google-deepmind/android_env @8e41dd0 · AndroidWorld: github.com/google-research/android_world (`AccessibilityForwarder.kt`, `AccessibilityTreeCreator.kt`, `env/actuation.py`)
- AOSP (android.googlesource.com/platform/frameworks/base, tags android-16.0.0_r1 / r4 / android-17.0.0_r1 / android-15.0.0_r1): AccessibilityService.java, AccessibilityServiceInfo.java, GestureDescription.java, AccessibilityNodeInfo.java, InputMethod.java, AbstractAccessibilityServiceConnection.java, AccessibilityServiceConnection.java, AccessibilityManagerService.java, AccessibilityUserState.java, AccessibilitySecurityPolicy.java, UiAutomationManager.java, UiAutomation.java, ViewConfiguration.java, View.java, KeyEvent.java, InputShellCommand.java, PackageInstallerService.java, AppOpsManager.java, cmds/{input,am,pm,uiautomator}, cmds/screencap/screencap.cpp, packages/Shell/AndroidManifest.xml, core/res/res/values/attrs.xml
- AOSP (packages/modules/Permission, android-16.0.0_r1): service/java/com/android/ecm/EnhancedConfirmationService.java; data/etc/enhanced-confirmation.xml
- Chromium (main): content/public/android/.../WebContentsAccessibilityImpl.java; ui/accessibility/android/.../AccessibilityState(DelegateImpl).java; docs/accessibility/browser/android.md
- androidx (androidx-main): compose/ui/.../AndroidComposeViewAccessibilityDelegateCompat.android.kt
- developer.android.com: accessibilityservice/AccessibilityService; guide/topics/ui/accessibility/service; training/package-visibility/{automatic,declaring,use-cases}; guide/components/activities/background-starts; media/grow/media-projection; privacy-and-security/advanced-protection-mode; about/versions/{14,15,16,17}; developer-verification/guides; google/play/integrity/verdicts; tools/adb; studio/run/win-usb
- source.android.com/docs/compatibility/16/android-16-cdd (§9.8) · support.google.com/android/answer/12623953, /16339980 · support.google.com/googleplay/android-developer/answer/10964491
- Tools: github.com/droidrun/mobilerun-portal (v0.7.17) · pypi mobilerun-core-local 0.6.3 · github.com/mobile-next/mobile-mcp · github.com/zai-org/Open-AutoGLM · github.com/stepfun-ai/gelab-zero · github.com/ysbing/YADB · github.com/Turbo1123/roubao · github.com/Ayush0Chaudhary/blurr · github.com/openatx/uiautomator2 · github.com/appium/appium-uiautomator2-driver · github.com/senzhk/ADBKeyBoard · github.com/Genymobile/scrcpy · github.com/dondetir/NeuralBridge_mcp
- Shizuku: shizuku.rikka.app · github.com/RikkaApps/Shizuku(-API) · github.com/gkd-kit/gkd · github.com/xjunz/AutoTask · Termux: github.com/termux/termux-app · android-developers.googleblog.com/2026/09 (ADB Wi-Fi 2.0)
- Perf claims: hackernoon.com/i-built-a-100x-faster-android-automation-tool · arXiv 2406.08184 (MobileAgentBench) · v2ex.com/t/1214432
