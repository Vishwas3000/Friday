# Skills: direct intents and APIs before UI automation (Android 14–16, Pixel 8)

_Last updated: 2026-10-16 · Trust tags: [primary] read at source · [secondary] search/blog/forum · [measured] run here · [UNCERTAIN] unconfirmed or conflicting_

**Scope.** For each kind of request, this note gives the most direct Android mechanism (an Intent or a platform API) that a normal, sideloaded, non-system app can use on the owner's Pixel 8 (Android 16, not rooted). UI automation is the fallback.

What Friday has today [primary: `android/app`]:
- `targetSdk 36`, `minSdk 30`.
- No `<uses-permission>` entries.
- `<queries>` for MAIN+LAUNCHER.
- Every launch comes from `AgentService`, an AccessibilityService.

**Method and limits.**
- **Sources.**
  - AOSP read at the release tags `android-16.0.0_r1` and `r4`, not `main` (frozen since 2025-03, research/02).
  - Constant strings come from the SDK 36 `android.jar` (`javap -constants`).
  - API levels come from `platforms/android-37.0/data/api-versions.xml`.
- **Nothing was run on the phone.** No device was attached, so there are no [measured] tags.
  - Spotify, YouTube Music, YouTube, Google Clock, Maps and Chrome are closed source. Their behaviour is [secondary] or [UNCERTAIN] unless the vendor documents it.
  - The checklist at the end turns these into [measured].
- **How the work was split.**
  - The media, Google-app and toggle sections were researched by parallel sub-agents.
  - Their load-bearing claims were re-read at source here: DeskClock `HandleApiCalls.kt`, Chromium `SearchActivity`, the Settings manifest, `RequestToggleWiFiActivity`, and `WindowState`.

## TL;DR
1. **Every skill can launch straight from `AgentService` on Android 16** [primary, 16 r4].
   - The system binds accessibility services with `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS`, which yields a `BAL_ALLOW_TOKEN` verdict.
   - Add `FLAG_ACTIVITY_NEW_TASK`.
   - Launching needs no `<queries>`. The existing MAIN+LAUNCHER entry already makes every launcher app, with all its components, visible for pre-checks.
2. **These complete with no UI:**
   - alarm and timer (`SET_ALARM`/`SET_TIMER` + `SKIP_UI`, Int extras, a normal permission);
   - turn-by-turn navigation (`google.navigation:`);
   - web search (VIEW `google.com/search?q=` in Chrome);
   - flashlight (`setTorchMode`, no permission);
   - brightness (after a one-time WRITE_SETTINGS grant);
   - pause/next/previous (`dispatchMediaKeyEvent`, no permission);
   - every device query in §8.
   - Dial stops at the Call button by design.
3. **Music by search is the weak skill.** `MEDIA_PLAY_FROM_SEARCH` only promises to play "when possible".
   - Spotify reportedly opens search without playing [secondary]. YouTube Music is unknown. YouTube ignores the query [secondary].
   - Plan: fire the intent, check `isMusicActive()`, and if it isn't playing, tap the first result on the app's own results screen.
4. **A target-36 app cannot flip Wi-Fi, Bluetooth, mobile data or dark mode directly** [primary].
   - Wi-Fi and Bluetooth have **one-tap system Allow dialogs** reachable by hidden action strings.
   - Mobile data and dark mode need a scripted tap: the Internet panel or a Quick Settings tile.
5. **Android 15 changed DND for apps targeting 35 or higher.** `setInterruptionFilter` toggles an app-owned "Friday" mode, not global DND [primary]. Friday can turn DND on, but **cannot turn off DND the owner switched on**; that needs the UI.
6. **Fallback design:** open the *narrowest* screen with an intent, then do a deterministic node action. Use Laya only if that fails. Short candidate lists are where Laya is accurate (research/05: right with 3 rows, wrong with 14).

## Summary table

Permission legend: *normal* is granted at install. *appop* or *runtime* is a one-time owner or adb grant (see "One-time grants").

| # | Skill | Best direct mechanism | Completes without UI? | Permission | Reliability notes |
|---|---|---|---|---|---|
| 1 | Play music by search | `MEDIA_PLAY_FROM_SEARCH` + `query` + focus extras + `setPackage(spotify / ytmusic)` | **Partly.** Opens the app; plays only "when possible" | none | Spotify text query: opens search, no play [secondary]. YouTube Music: [UNCERTAIN]. Check `isMusicActive()`; fall back to tapping the first result |
| 1b | Pause / next / previous / resume | `AudioManager.dispatchMediaKeyEvent` (DOWN then UP) | **Yes** | none | Most reliable media skill; reaches the current media session [primary] |
| 2 | YouTube search | `ACTION_SEARCH` + `query`, or VIEW `youtube.com/results?search_query=` + `setPackage` | Shows results; a tap plays | none | No intent plays "the first result" [UNCERTAIN, none found]. YouTube ignores `MEDIA_PLAY_FROM_SEARCH` [secondary] |
| 2b | YouTube known video | VIEW `https://www.youtube.com/watch?v=<id>` + `NEW_TASK` + `CLEAR_TOP` | **Yes** | none | NEW_TASK alone can resurface the old player [secondary] |
| 3a | Set alarm | `SET_ALARM` + `HOUR`/`MINUTES` (Int) + `SKIP_UI=true` | **Yes**: toast only, works on the lock screen (AOSP) | `com.android.alarm.permission.SET_ALARM` (normal) | AOSP re-uses an identical alarm instead of duplicating [primary]. Google Clock assumed to behave like AOSP [UNCERTAIN]. Verify with `getNextAlarmClock()` |
| 3b | Set timer | `SET_TIMER` + `LENGTH` (Int seconds, 1–86400) + `SKIP_UI=true` | **Yes** | same | String/Long extras silently fail [primary] |
| 4 | Navigate | `google.navigation:q=…&mode=d/b/l/w` + `setPackage(maps)` | **Yes**: navigation starts | none | Free text takes the first match. `mode=l` is two-wheeler (India). No transit. Shows "Preview" if location is off [secondary] |
| 4b | Show place / route preview | `geo:0,0?q=…`, or Maps URL `/maps/dir/?api=1&destination=…` | Results or preview; tap to pick | none | `Uri.encode` everything |
| 5 | Web search | VIEW `https://www.google.com/search?q=<enc>` + `setPackage("com.android.chrome")` | **Yes**: results page | none | `ACTION_WEB_SEARCH` to Chrome only pre-fills the omnibox [primary] |
| 6 | Dial | `ACTION_DIAL` + `Uri.fromParts("tel", n, null)` | Stops at the dialer; the owner taps Call (wanted) | none | `ACTION_CALL` needs CALL_PHONE (dangerous). Calling by name needs READ_CONTACTS |
| 7a | Wi-Fi on/off | `startActivity("android.net.wifi.action.REQUEST_ENABLE"` / `"…REQUEST_DISABLE")` | **One tap** on the system Allow dialog | `CHANGE_WIFI_STATE` (normal) | Hidden action string. Finishes at once if Wi-Fi is already in that state. `setWifiEnabled` is dead for target ≥ 29 [primary] |
| 7b | Bluetooth on/off | `BluetoothAdapter.ACTION_REQUEST_ENABLE` / literal `"android.bluetooth.adapter.action.REQUEST_DISABLE"` | **One tap** on Allow | `BLUETOOTH_CONNECT` (runtime) | `enable()`/`disable()` are dead for target ≥ 33 [primary] |
| 7c | Mobile data | No API. `Settings.Panel.ACTION_INTERNET_CONNECTIVITY` opens SystemUI's Internet dialog, which has a "Mobile data" toggle | No: scripted tap | none | Needs MODIFY_PHONE_STATE (privileged); writing `MOBILE_DATA` is ignored [primary] |
| 7d | Dark mode | No clean API. Quick Settings "Dark theme" tile (a Switch), or `"android.settings.DARK_THEME_SETTINGS"` | No: scripted tap | — | `setNightMode` is a silent no-op [primary]. Car-mode trick with WRITE_SECURE_SETTINGS [UNCERTAIN] |
| 7e | Brightness | `Settings.System.SCREEN_BRIGHTNESS` (1–255), after `SCREEN_BRIGHTNESS_MODE=0` | **Yes** | `WRITE_SETTINGS` (appop) | The slider is perceptual: 50% is about 22, not 128 [primary code] |
| 7f | DND on | `NotificationManager.setInterruptionFilter(PRIORITY)` | **Yes**, as a "Friday" mode | `ACCESS_NOTIFICATION_POLICY` (normal + access grant) | Target ≥ 35 gets an implicit app rule, not global DND [primary] |
| 7g | DND off | Not possible by API if the owner turned DND on. Use the Quick Settings DND tile or the DND mode page | No: scripted tap | — | Compare `getCurrentInterruptionFilter()` before and after |
| 7h | Flashlight | `CameraManager.setTorchMode(id, on)` | **Yes** | none | Fails while the camera is in use. Turns off if Friday's process dies [primary] |
| 8 | Battery, next alarm, storage, network, DND/BT/data state | See §8 | **Yes** | none, or normal (`ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`) | All read-only |
| 9 | Launching any of the above | `startActivity(intent.addFlags(NEW_TASK))` from `AgentService` | — | — | Exempt from background-launch limits; catch `ActivityNotFoundException` |

## Proposed dispatch order (not decided — Open questions)
1. **Answer.** No UI at all (§8).
2. **Direct.** An intent or API that completes on its own, followed by a state read to confirm (§0).
3. **Narrow screen + scripted tap.** An intent opens the smallest screen: a panel, results page, settings page or Quick Settings. Then a deterministic node action: find the node by text, role or `checkable`, `ACTION_CLICK`, and re-read `checked`. No Laya.
4. **Laya on the narrow screen.** Few candidates; this is where Laya is reliable (README risk 1).
5. **Laya from wherever the phone is.** Today's path.

## 0. Rules for every skill: launching from the service (item 9)
- **Background launch from Friday's AccessibilityService is allowed on 16** [primary, AOSP 16 r4].
  - `AccessibilityServiceConnection.bindLocked()` binds each enabled service with `BIND_AUTO_CREATE | BIND_FOREGROUND_SERVICE_WHILE_AWAKE | BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS | BIND_INCLUDE_CAPABILITIES`, then calls `setAllowAppSwitches(...)` for the service's uid.
  - The chain, step by step:
    1. `ActiveServices` accepts that flag only from a binder holding `START_ACTIVITIES_FROM_BACKGROUND`. system_server does.
    2. The service is marked `ServiceRecord.mIsAllowedBgActivityStartsByBinding`.
    3. Its process gets an `ALLOW_BAL` token.
    4. `BackgroundLaunchProcessController.isBackgroundStartAllowedByToken` hands the token to NotificationManagerService's trampoline callback. The callback returns "allowed" for any token that is not the notification token.
    5. The verdict is `BAL_ALLOW_TOKEN`, with `allowNewTask()`.
  - The docs list the same exception: "The app is bound by a service that has been granted permission to start background activities" [primary].
  - This matches research/02 §4.7. `open_app` already depends on it.
- **The 14/15/16 background-launch hardening does not touch a direct `startActivity`** [primary: behavior-changes-14/15, background-starts]. It covers PendingIntent/IntentSender opt-ins, services bound by a visible app, task-stack rules and non-visible windows.
  - It *does* apply if Friday sends another app's PendingIntent, e.g. `AlarmClockInfo.getShowIntent()`.
    - A target-34+ sender must opt in with `ActivityOptions.setPendingIntentBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)` (API 36).
    - `ALLOW_IF_VISIBLE` won't help, because Friday has no visible window. `MODE_…_ALLOWED` is deprecated in 36 [primary].
  - Simpler: send `ACTION_SHOW_ALARMS` instead.
- **`FLAG_ACTIVITY_NEW_TASK` is mandatory** [primary, ContextImpl 16 r4].
  - For targetSdk 28 and up, without a launch task id, `ContextImpl.startActivity` throws `AndroidRuntimeException("Calling startActivity() from outside of an Activity context requires the FLAG_ACTIVITY_NEW_TASK flag…")`.
- **Package visibility: launching needs none; pre-checks do.**
  - "You can start another app's activity using either an implicit or explicit intent regardless of whether that app is visible to your app." The docs' pattern is to launch and catch `ActivityNotFoundException` [primary].
  - `resolveActivity`, `queryIntentActivities` and `getPackageInfo` are filtered. Friday needs them only to *choose* a handler first, e.g. Spotify vs YouTube Music.
  - Visibility is recorded **per app pair**: `mQueriesViaComponent.add(queryingAppId, targetAppId)` when any `<queries>` intent matches any component [primary, AppsFilterImpl/AppsFilterUtils 16 r4]. So the existing MAIN+LAUNCHER entry exposes every app with a launcher icon, all components included: Spotify, YouTube Music, YouTube, Maps, Clock, Phone, Chrome, Google and Settings.
  - No new `<queries>` and no `QUERY_ALL_PACKAGES` needed.
- **Always set an action; prefer `setPackage()` to `setComponent()`.**
  - Android 16 adds opt-in, receiver-side "safer intents": an explicit intent must match the target's filter, and an intent with no action matches nothing [primary, behavior-changes-16].
  - `setPackage()` still resolves through filters, so it always matches.
  - The 16 intent-redirection hardening only affects re-launching an Intent pulled out of another Intent's extras. Friday doesn't do that [primary].
- **Confirm the result; don't assume it.** A returning `startActivity` only means the launch was accepted.
  - Wait for `TYPE_WINDOW_STATE_CHANGED` from the expected package; the bridge's `foreground` reports it.
  - Then read state where possible: `isMusicActive()`, `getNextAlarmClock()`, `getCurrentInterruptionFilter()`, `isWifiEnabled()` (§8).
- **Task gotcha** [secondary; per app UNCERTAIN].
  - `NEW_TASK` brings an existing task forward, and a `singleTask`/`singleTop` target gets `onNewIntent`, which some apps ignore. YouTube is a reported case.
  - `NEW_TASK|CLEAR_TOP` is Google's own test flag for media apps [primary, Assistant media doc].
  - `CLEAR_TASK` discards the owner's place in the app. Keep it for retries.

## 1. Play music by search
**Mechanism.** `Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)` + `setPackage(<music app>)` + `NEW_TASK`. No permission.
- The action string is `"android.media.action.MEDIA_PLAY_FROM_SEARCH"` (API 9).
- Javadoc: "An intent to perform a search for music media and automatically play content from the result when possible" [primary]. **"When possible" is the catch.**

**Extras** (strings from the SDK 36 jar) [primary: MediaStore, intents-common#PlaySearch]:

| Focus (`"android.intent.extra.focus"`) | Constant | Also send |
|---|---|---|
| `vnd.android.cursor.item/*` | — | `query` only. `""` means "play anything" (e.g. last playlist); otherwise it is an unstructured search |
| `vnd.android.cursor.item/artist` | `Audio.Artists.ENTRY_CONTENT_TYPE` | `"android.intent.extra.artist"` |
| `vnd.android.cursor.item/album` | `Audio.Albums.ENTRY_CONTENT_TYPE` | `"android.intent.extra.album"` (+ artist) |
| `vnd.android.cursor.item/genre` | `Audio.Genres.ENTRY_CONTENT_TYPE` | `"android.intent.extra.genre"` (21) |
| `vnd.android.cursor.item/audio` (song) | `Audio.Media.ENTRY_CONTENT_TYPE` | `"android.intent.extra.title"` (+ artist, album) |
| `vnd.android.cursor.item/playlist` | `Audio.Playlists.ENTRY_CONTENT_TYPE` | `"android.intent.extra.playlist"`, deprecated in 31 |
| `vnd.android.cursor.item/radio` | `Audio.Radio.ENTRY_CONTENT_TYPE` | `"android.intent.extra.radio_channel"` (21) |

- **`SearchManager.QUERY` (`"query"`) is always required**, even with structured extras. Put the raw phrase there.
- **Use the MediaStore constants.** Third-party code ships wrong focus strings such as `…/audio_albums` [secondary].
- **`CATEGORY_DEFAULT`** is added implicitly by `startActivity` [primary].

**Handlers and auto-play.**
- **Assistant doesn't use this intent.** It drives media apps through their MediaSession (`onPlayFromSearch`). The docs list the intent filter only as "legacy support for voice actions" [primary, developer.android.com/media/implement/assistant]. So apps may let the intent path rot.
- **Spotify (`com.spotify.music`)**
  - In 2015, `MainActivity` (singleTask) declared the filter [primary for that build]. Whether today's build does is [UNCERTAIN].
  - A free-text query "searches … but the song is not played" (logged-in Premium user, 2020) [secondary, Spotify Community].
  - An ACTION_VIEW of a `spotify:` URI with a `:play` suffix started playback (2019 Tasker thread) [secondary]. Officially, a `spotify:`/open.spotify.com link only "brings Spotify to the foreground with the selected content" [primary, Spotify content-linking docs].
  - `spotify:search:<q>` is undocumented [secondary].
- **YouTube Music (`com.google.android.apps.youtube.music`)**
  - Apps have aimed the intent at its `MusicActivity` since 2017 [secondary]. A 2026 code comment says it auto-plays, but that was not measured [UNCERTAIN].
  - The search link `https://music.youtube.com/search?q=<enc>` + `setPackage` opens results [secondary].
  - Whether free-tier playback stops when the app is backgrounded or the screen goes off is [UNCERTAIN]. Friday moves on after launching, so this matters.
- **YouTube** declares the filter but ignores the query and opens home [secondary]. Don't use it.
- **`VIDEO_PLAY_FROM_SEARCH`** (`"android.media.action.VIDEO_PLAY_FROM_SEARCH"`, API 17) exists [primary]. Nobody is known to honour it.

**Flow (proposed).**
1. Pick the app: the owner's preference, else the first installed of Spotify / YouTube Music. `queryIntentActivities` works under today's `<queries>`.
2. Fire the intent with structured focus when the request names an artist, album, song or genre.
3. Poll `AudioManager.isMusicActive()` (no permission) for about 4 s, and watch window events for that package.
4. If nothing plays, the app is on its own short results list. Tap the first result with a scripted rule or Laya (tier 3/4).
5. Don't send a PLAY media key to "finish" the job. It resumes the *previous* session, not the search hit [UNCERTAIN, not tested].

**Heavier options.**
- **Spotify App Remote SDK** really plays a URI [primary, developer.spotify.com]. It needs:
  - a developer-dashboard client ID, a redirect URI and the package fingerprint;
  - the owner's consent to the `app-remote-control` scope;
  - internet;
  - Premium for single tracks ("A Spotify Premium account is required to play a single track uri").
  - It is beta.
- **MediaBrowser / `TransportControls.playFromSearch`** is the Assistant path. Seeing sessions needs `MEDIA_CONTENT_CONTROL` (signature) or an enabled NotificationListenerService [primary, MediaSessionManager]. Whether Spotify or YouTube Music accept unknown controller packages is [UNCERTAIN].

**Transport controls are direct.** For pause, next, previous and resume: `AudioManager.dispatchMediaKeyEvent(KeyEvent(ACTION_DOWN, KEYCODE_MEDIA_…))`, then `ACTION_UP`.
- API 19, no permission; goes to the current media session.
- AOSP 16 refuses only during setup or for a global-priority session [primary, AudioManager + MediaSessionService].

## 2. Play / search video on YouTube (`com.google.android.youtube`)
| Want | Intent (+ `setPackage`, `NEW_TASK`) | Result |
|---|---|---|
| Search | `Intent(Intent.ACTION_SEARCH).putExtra("query", q)`, or VIEW `https://www.youtube.com/results?search_query=<enc>` | Results screen [secondary] |
| Play a known video | VIEW `https://www.youtube.com/watch?v=<id>` (or `vnd.youtube:<id>`), with `CLEAR_TOP` | Plays [secondary] |
| Play "first result for X" | No intent found [UNCERTAIN] | Look the ID up, or tap the first result |

- **Getting the ID.**
  - The YouTube Data API v3 `search.list` (`type=video`, API key only) has a separate daily quota of "100 search.list calls" [primary, developers.google.com/youtube/v3]. Enough for one owner, but it adds a network key.
  - Preferred: a scripted tap on the first video node of the results screen. Friday stays key-free.
- **`"android.intent.extra.START_PLAYBACK"`** (`IntentCompat.EXTRA_START_PLAYBACK`): "Your app should start playback when it receives an intent with EXTRA_START_PLAYBACK" [primary, Assistant doc]. It is free to add to VIEW links; whether YouTube honours it is [UNCERTAIN].

## 3. Set alarm / set timer
**Mechanism.** `Intent(AlarmClock.ACTION_SET_ALARM or ACTION_SET_TIMER)` + `EXTRA_SKIP_UI=true` + `setPackage("com.google.android.deskclock")` + `NEW_TASK`.
- Declare `<uses-permission android:name="com.android.alarm.permission.SET_ALARM"/>`. It is `protectionLevel="normal"` [primary, core/res/AndroidManifest.xml 16 r1].

| Action (API) | String | Extras (`android.intent.extra.alarm.*`) |
|---|---|---|
| SET_ALARM (9) | `android.intent.action.SET_ALARM` | `HOUR` Int 0–23 · `MINUTES` Int 0–59 · `MESSAGE` · `DAYS` an `ArrayList` of Int `Calendar.SUNDAY…SATURDAY` (19) · `RINGTONE` URI or `"silent"` (19) · `VIBRATE` (19) · `IS_PM` (23) · `SKIP_UI` (11) |
| SET_TIMER (19) | `android.intent.action.SET_TIMER` | `LENGTH` Int seconds 1–86400 · `MESSAGE` · `SKIP_UI` |
| SHOW_ALARMS (19) / SHOW_TIMERS (26) | `android.intent.action.SHOW_ALARMS` / `SHOW_TIMERS` | — |
| DISMISS_ALARM / SNOOZE_ALARM (23) | `android.intent.action.DISMISS_ALARM` / `SNOOZE_ALARM` | `SEARCH_MODE` = `android.time` / `android.next` / `android.all` / `android.label` · `SNOOZE_DURATION` |
| DISMISS_TIMER (28) | `android.intent.action.DISMISS_TIMER` | — |

- **The contract** [primary, AlarmClock.java 16 r1]:
  - "If … EXTRA_SKIP_UI is true, and the alarm is not repeating, the implementation should remove this alarm after it has been dismissed. If an identical alarm exists matching all parameters, the implementation may re-use it."
  - Without `HOUR` or `LENGTH`, the clock opens its UI.
- **AOSP DeskClock** [primary, `packages/apps/DeskClock` 16 r1 `HandleApiCalls.kt` + manifest, re-read here]:
  - **Handler:** `Theme.NoDisplay`, `singleInstance`, `showWhenLocked="true"`, guarded by `SET_ALARM` (so SHOW, DISMISS and SNOOZE need the permission too). With SKIP_UI nothing comes to the front, **even on the lock screen**.
  - **Bad input:** an out-of-range hour or minute returns silently. A missing hour opens "new alarm".
  - **De-dup:** it looks for an alarm with the same hour, minutes and days (plus label, vibrate and ringtone if given). If found, it **re-enables that one** instead of duplicating it.
  - **New alarm:** otherwise it creates one with `deleteAfterUse = !repeating && skipUi`. It always toasts "Alarm set for…".
  - **Timers:** under 1 s is rejected, a matching reset timer is re-used, and SKIP_UI timers delete themselves after use.
  - **Gotcha:** the extras are read with `getIntExtra`/`getBooleanExtra`. **A String or Long `HOUR`/`LENGTH` silently falls back to the default.**
- **Google Clock (`com.google.android.deskclock`) is closed source.** We found no public evidence that it differs from AOSP [UNCERTAIN]. Treat it as AOSP and run the checklist.
- **Confirm:**
  - Alarm: read `AlarmManager.getNextAlarmClock()` before and after (§8).
  - Timer: no public read API exists; the Clock notification appears in the tree.

## 4. Navigation / directions (Google Maps, `com.google.android.apps.maps`)

Always use `setPackage`, and `Uri.encode` every value.

| Want | URI | Completes without UI? |
|---|---|---|
| Turn-by-turn now | `google.navigation:q=<address or lat,lng>&mode=d` (d drive, b bicycle, l two-wheeler, w walk) `&avoid=t` (t tolls, h highways, f ferries, combinable) | **Yes**. Navigation starts from the current location |
| Place or search on the map | `geo:0,0?q=<query>` · `geo:lat,lng?q=<query>` (biased near lat,lng) · `geo:0,0?q=lat,lng(label)` · `geo:lat,lng?z=0..21` | Results; the owner picks |
| Route preview, transit | `https://www.google.com/maps/dir/?api=1&destination=…&travelmode=driving / walking / bicycling / two-wheeler / transit` (+`&dir_action=navigate`) | A preview needs a tap. `navigate` starts navigation when the origin is unset or near you |

Sources: [primary] developer.android.com/guide/components/google-maps-intents; developers.google.com/maps/documentation/urls/get-started.

- **Free-text destinations take the first hit:** "If it's a query string that returns more than one result, the first result is selected" [primary]. For ambiguous names (chains, common street names), resolve with `geo:` first or ask the owner.
- **`mode=l` (two-wheeler)** is documented with an India example. Maps URLs say two-wheeler "works only if you're physically located in the country that supports two-wheelers" [primary]. That fits the owner.
- **No transit in `google.navigation`.** Use the Maps URL with `travelmode=transit` (needs a tap).
- **Without `setPackage`,** other `geo:` handlers can raise a chooser [primary].
- **Maps shows "Preview" instead of "Start"** when location is off [secondary]. First-run and location dialogs are undocumented [UNCERTAIN].

## 5. Web search
- **Use VIEW `https://www.google.com/search?q=${Uri.encode(q)}` + `setPackage("com.android.chrome")`.** It lands on results in the owner's own Chrome, which fits D-010.
- **Avoid `Intent.ACTION_WEB_SEARCH`** (`"android.intent.action.WEB_SEARCH"`, extra `"query"`).
  - The javadoc promises "If it is plain text, Google search will be applied" [primary, Intent.java 16 r1].
  - Chrome registers it on `searchwidget.SearchActivity`, but **only pre-fills the omnibox** (`setUserText(session.query)` + `SELECT_ALL`) and waits for Enter [primary, Chromium main, re-read here].
  - The Google app may also handle it [UNCERTAIN]. No role pins it, so a chooser is possible [UNCERTAIN until `resolve-activity` runs].

## 6. Dial a number
- **`ACTION_DIAL` + `Uri.fromParts("tel", number, null)`.** No permission. The dialer opens pre-filled and "the user must tap the Call button" [primary, intents-common]. This is the wanted behaviour, and it keeps a human on the money/safety step (D-011).
  - The DIALER role pins DIAL to Google Phone, so there is no chooser [primary, roles.xml 16 r1].
- **Skip `ACTION_CALL`.**
  - It needs `CALL_PHONE`: dangerous, `costsMoney`, and it can run carrier MMI codes [primary].
  - Telecom rewrites emergency numbers and "potentially dangerous MMI or vertical codes" from non-dialer apps to `ACTION_DIAL` anyway [primary, Telecomm 16 r1].
- **Build the URI with `Uri.fromParts`.** `Uri.parse("tel:*123#")` drops `#` as a fragment; `fromParts` encodes it [primary, Uri javadoc].
- **Calling by name** needs `READ_CONTACTS` (runtime) and a `ContactsContract.CommonDataKinds.Phone` query. The owner must grant it themselves, because the service can't see permission dialogs (D-014). Fallback: UI automation in the Phone app's search.

## 7. Device toggles (normal app, targetSdk 36, Android 16)
**Protection levels** [primary, core/res/AndroidManifest.xml 16 r4]:
- Normal: `CHANGE_WIFI_STATE`, `ACCESS_NOTIFICATION_POLICY`.
- Runtime: `BLUETOOTH_CONNECT`.
- `WRITE_SETTINGS` is `signature|preinstalled|appop|pre23|role`, so it is an appop grant.
- `WRITE_SECURE_SETTINGS` is `signature|privileged|development|role|installer`, so `pm grant` works over adb.
- `MODIFY_DAY_NIGHT_MODE` is `signature|privileged|role`: **no `development`, so adb can't grant it.**
- `MODIFY_PHONE_STATE` is `signature|privileged|role`.
- `NETWORK_SETTINGS`, `NETWORK_AIRPLANE_MODE` and `CONTROL_DISPLAY_BRIGHTNESS` are signature.

**Wi-Fi**
- **Direct API: dead.** `WifiServiceImpl.setWifiEnabled` returns false when `isThirdParty && !isTargetSdkLessThanQ` [primary, modules/Wifi 16 r4].
- **One tap.** Start the literal action `"android.net.wifi.action.REQUEST_ENABLE"` or `"…REQUEST_DISABLE"` (`@SystemApi` constants, absent from the public SDK).
  - Settings' `wifi.RequestToggleWiFiActivity` is exported with `android:permission="android.permission.CHANGE_WIFI_STATE"` (normal) [primary, Settings manifest 16 r4].
  - It reads `getLaunchedFromPackage`, shows "<app> wants to turn on Wi-Fi" with Allow/Deny, and Settings toggles Wi-Fi itself.
  - It **finishes with RESULT_OK at once if Wi-Fi is already in the requested state** [primary, re-read here]. Whether it works from the service is [UNCERTAIN until run].
- **Screens.**
  - `Settings.Panel.ACTION_INTERNET_CONNECTIVITY` (API 29, not deprecated): Settings broadcasts to SystemUI, which shows the **Internet dialog** with `wifi_toggle` and `mobile_toggle` [primary, PanelFeatureProviderImpl].
  - `Panel.ACTION_WIFI` opens full Wi-Fi settings or an old slice panel, depending on the server flag `slices_retirement`. Pixel value [UNCERTAIN].
  - `Settings.ACTION_WIFI_SETTINGS` (1).

**Bluetooth**
- **Direct API: dead.** Compat change `RESTRICT_ENABLE_DISABLE` (218493289) is enabled from 33, and `enable()`/`disable()` return false unless the caller is privileged, system or device/profile owner [primary, modules/Bluetooth 16 r4].
- **One tap.** `BluetoothAdapter.ACTION_REQUEST_ENABLE` (public, API 5), or the literal `"android.bluetooth.adapter.action.REQUEST_DISABLE"` (`@SystemApi`).
  - Both go to Settings' `bluetooth.RequestPermissionActivity`, exported with `android:permission="android.permission.BLUETOOTH_CONNECT"` [primary, re-read here].
  - So Friday needs the runtime grant once. It shows Allow/Deny and finishes if Bluetooth is already in that state.
- **Screen.** `Settings.ACTION_BLUETOOTH_SETTINGS`.
- **Overlay check.** Both Settings dialogs set `SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS`. That hides only system-alert-window types and toasts (`WindowState.setForceHideNonSystemOverlayWindowIfNeeded`). Friday's card is `TYPE_ACCESSIBILITY_OVERLAY`, so **it stays visible** [primary, WindowState/WindowManager 16 r4, Overlay.kt].

**Mobile data**
- **No API.**
  - `setDataEnabled` (deprecated 31) and `setDataEnabledForReason` need `MODIFY_PHONE_STATE` or carrier privilege.
  - Writing `Settings.Global.MOBILE_DATA` is not observed by telephony's DataSettingsManager [primary, 16 r4].
- **Narrowest screen.** The Internet dialog via `Panel.ACTION_INTERNET_CONNECTIVITY` (content description "Mobile data"), or `ACTION_DATA_ROAMING_SETTINGS` / `ACTION_NETWORK_OPERATOR_SETTINGS`, which open MobileNetworkActivity. Then do a scripted tap.

**Dark mode**
- **No API.** `UiModeManagerService.setNightModeInternal` returns silently (`Slog.e`) when `isNightModeLocked()` and the caller lacks `MODIFY_DAY_NIGHT_MODE`. `config_lockDayNightMode` defaults to true [primary, 16 r4].
  - `setApplicationNightMode` (31) changes only Friday's own theme.
  - Writing `Secure.UI_NIGHT_MODE` alone only updates a system property; it is not applied live [primary].
- **Workaround** [primary code; side effects UNCERTAIN]: with adb-granted `WRITE_SECURE_SETTINGS`, write `ui_night_mode` (1 off / 2 on), then `enableCarMode(0)` + `disableCarMode(0)`. Leaving car mode re-reads night mode from settings, and default-priority car mode needs no permission.
  - Expect two configuration changes in every app and possibly a car-mode notification flash.
- **Scripted tap.** The Quick Settings "Dark theme" tile is a Switch and toggles in place. Or open the literal `"android.settings.DARK_THEME_SETTINGS"` (hidden constant, exported in Settings 16 r4), or `ACTION_DISPLAY_SETTINGS`.

**Brightness**
- **Direct with `WRITE_SETTINGS`.**
  - Grant: owner via `Settings.ACTION_MANAGE_WRITE_SETTINGS` + `package:` URI, or `adb shell appops set dev.layatest.agent WRITE_SETTINGS allow`. Check with `Settings.System.canWrite()`.
  - Set `SCREEN_BRIGHTNESS_MODE=0` first (automatic mode overrides), then `SCREEN_BRIGHTNESS` 1–255.
  - It still applies live on 16: BrightnessSynchronizer turns the int into `DisplayManager.setBrightness(…)` [primary, 16 r4]. An adb-granted `WRITE_SECURE_SETTINGS` also passes the check.
- **Gotcha: the int maps linearly, but the slider is perceptual** (SettingsLib `BrightnessUtils`, HLG curve) [primary code].
  - Slider 50% is about value **22**. Value 128 shows about 87% on the slider (computed from the formula, assuming the minimum float is 0).
  - Convert "set brightness to N%" with the same curve.

**Do Not Disturb**
- **Access.** Declare `ACCESS_NOTIFICATION_POLICY`. The owner grants it under `Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`, or run `adb shell cmd notification allow_dnd dev.layatest.agent`. It is **not** an ECM restricted setting [primary].
- **Android 15+ behaviour for target ≥ 35** [primary, behavior-changes-15]:
  - "can no longer change the global state or policy of Do Not Disturb (DND) … (either by modifying user settings, or turning off DND mode)". `setInterruptionFilter` instead creates or updates an implicit AutomaticZenRule.
  - Code: compat change `MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES` (308670109) is enabled since V. ZenModeHelper turns "off" into "deactivate the app's own rule if active, else ignore" [primary, 16 r4].
  - **On:** works, as a "Friday" mode copying the DND policy. **Off:** cannot end the owner's DND.
  - It can't be opted out: `am compat disable` on a user build for target 36 returns `DISABLED_TARGET_SDK_TOO_HIGH` [primary].
- **Read state with `getCurrentInterruptionFilter()`** (global, no permission). Compare before and after.
- **Off, or the owner's DND.** Scripted tap on the Quick Settings DND tile (toggles in place unless set to ask for a duration).
  - Or `Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS` (35) + `EXTRA_AUTOMATIC_ZEN_RULE_ID="MANUAL_RULE"`, which opens the DND mode page with Turn on/off [primary code; Pixel UNCERTAIN].
  - `ACTION_ZEN_MODE_PRIORITY_SETTINGS` (26) opens the modes list.

**Flashlight**
- **Direct.** `CameraManager.setTorchMode(cameraId, on)` (23). No permission and no foreground check in `CameraService::setTorchMode` [primary, frameworks/av 16 r4].
- Pick a camera with `FLASH_INFO_AVAILABLE`.
- **Failure cases.** It throws `CAMERA_IN_USE` / `MAX_CAMERAS_IN_USE` while the camera is busy.
- **"If the latest application that turned on the torch mode exits, the torch mode will be turned off"** (torch client binder death) [primary]. The torch goes off if Friday's process dies.
- State comes only through `registerTorchCallback`. `turnOnTorchWithStrengthLevel` (33) needs a maximum level above 1; Pixel 8 support [UNCERTAIN].

**Quick Settings route** (`GLOBAL_ACTION_QUICK_SETTINGS`, API 17) [primary code; Pixel tile set and layout UNCERTAIN]
- Switch-class tiles toggle in place: Dark theme, Flashlight, DND.
- Internet and Bluetooth tiles are Button-class and open dialogs. The Bluetooth dialog sits behind `releasedFlag("bluetooth_qs_tile_dialog")`.

**Option not taken: a legacy-target helper APK.**
- Android 16 still installs target ≥ 24 (`MIN_INSTALLABLE_TARGET_SDK`). A tiny helper targeting 28, bound by Friday, could toggle Wi-Fi (< 29), Bluetooth (< 33) and global DND (< 35) with no UI [primary code].
- Costs: a second APK, old-target or Play Protect warnings, and Android 17 may close these exemptions [UNCERTAIN]. Not recommended for v1.

## 8. Device queries answerable with no UI
| Query | API (since) | Permission | Notes |
|---|---|---|---|
| Battery %, charging, plug | `registerReceiver(null, IntentFilter(ACTION_BATTERY_CHANGED))`: `level`/`scale`, `status`, `plugged`; or `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)` (21), `isCharging()` (23) | none | A null receiver just returns the sticky intent, so the 14+ `RECEIVER_EXPORTED` rule doesn't apply (it also exempts system broadcasts) [primary, Context 16 r4] |
| Time to full | `BatteryManager.computeChargeTimeRemaining()` (28) | none | −1 while discharging or when unknown [primary] |
| Time left on battery | `PowerManager.getBatteryDischargePrediction()` (31) | none | null while charging. Whether the Pixel fills it is [UNCERTAIN] |
| Cycle count, charging status | sticky `EXTRA_CYCLE_COUNT`, `EXTRA_CHARGING_STATUS` (34) | none | `BATTERY_PROPERTY_STATE_OF_HEALTH` is flagged and missing from the public SDK 36/37 [primary] |
| Next alarm | `AlarmManager.getNextAlarmClock()` (21): `triggerTime`, `showIntent` | none | Covers alarms any app set with `setAlarmClock`; null if none [primary] |
| Storage | `StorageStatsManager.getTotalBytes/getFreeBytes(StorageManager.UUID_DEFAULT)` (26) | none ("NOTE: No permissions required") | Total is rounded to the marketed size; free includes clearable cache, as Settings shows [primary, StorageStatsService 16 r4]. `StatFs` gives raw partition numbers |
| Online? Wi-Fi or mobile? | `ConnectivityManager.getActiveNetwork()` + `getNetworkCapabilities()`: `TRANSPORT_WIFI`/`CELLULAR`, `NET_CAPABILITY_VALIDATED` | `ACCESS_NETWORK_STATE` (normal) | `getActiveNetworkInfo` deprecated in 29. Location fields are redacted [primary] |
| Wi-Fi on? | `WifiManager.isWifiEnabled()` | `ACCESS_WIFI_STATE` (normal), enforced in WifiServiceImpl [primary] | SSID needs location permission and location on: skip |
| Bluetooth on? | `BluetoothAdapter.isEnabled()` | none on 31+ (`BLUETOOTH` only for target ≤ 30) [primary] | Listing paired devices needs `BLUETOOTH_CONNECT` |
| Mobile data on? | `TelephonyManager.isDataEnabled()` (26) | `ACCESS_NETWORK_STATE` is enough [primary] | User setting only. Throws without `FEATURE_TELEPHONY_DATA` |
| DND | `NotificationManager.getCurrentInterruptionFilter()` (23) | none [primary] | Global state, including the owner's DND |
| Dark mode | `resources.configuration.uiMode and UI_MODE_NIGHT_MASK` | none | |
| Brightness | `Settings.System.getInt(cr, SCREEN_BRIGHTNESS / SCREEN_BRIGHTNESS_MODE)` | none to read | |
| Music playing? | `AudioManager.isMusicActive()` (1) | none [primary] | Success check for skill 1 |
| Torch | `CameraManager.registerTorchCallback` (23) | none | Callback only |

## One-time grants (owner, or adb in `setup_phone.py`; not changed here)
Normal permissions are granted at install once declared: `SET_ALARM`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `ACCESS_NOTIFICATION_POLICY`. The rest:

| Grant | How | Unlocks |
|---|---|---|
| `WRITE_SETTINGS` (appop) | `adb shell appops set dev.layatest.agent WRITE_SETTINGS allow` | Brightness |
| DND access | `adb shell cmd notification allow_dnd dev.layatest.agent` | DND on (as the Friday mode) |
| `BLUETOOTH_CONNECT` (runtime) | `adb shell pm grant dev.layatest.agent android.permission.BLUETOOTH_CONNECT` | Bluetooth Allow dialogs |
| `WRITE_SECURE_SETTINGS` (optional) | `adb shell pm grant dev.layatest.agent android.permission.WRITE_SECURE_SETTINGS` | Brightness without the appop; dark-mode car-mode trick. **Not** Wi-Fi, mobile data, DND (`Global.ZEN_MODE` writes are reverted) [primary] |
| `READ_CONTACTS` (runtime, only for call-by-name) | `pm grant`, or the owner | Name to number |

Each needs the `<uses-permission>` declared first. Runtime grants through adb avoid the permission dialog Friday can't see (D-014).

## Verification checklist for the Pixel (not run)
The adb shell holds `SET_ALARM` and `CALL_PHONE` [primary, Shell manifest 16 r1], so `am start` exercises the **handler apps**. It does not test Friday's own permissions or launch rules, because shell is a different uid. Re-tag results as [measured] here.

**Read-only, safe anytime:**
```sh
adb shell cmd package query-activities --brief -a android.media.action.MEDIA_PLAY_FROM_SEARCH
adb shell cmd package query-activities --brief -a android.media.action.VIDEO_PLAY_FROM_SEARCH
adb shell cmd package resolve-activity --brief -a android.intent.action.WEB_SEARCH
adb shell cmd package resolve-activity --brief -a android.intent.action.SET_ALARM
adb shell dumpsys package com.google.android.deskclock | grep -A6 -E "SET_ALARM|SET_TIMER"
adb shell dumpsys package com.spotify.music | grep -B2 -A6 MEDIA_PLAY_FROM_SEARCH
adb shell dumpsys package com.google.android.apps.youtube.music | grep -B2 -A6 MEDIA_PLAY_FROM_SEARCH
adb shell dumpsys uimode | grep mNightModeLocked
```

**Changes phone state: one at a time, with the owner watching, then undo:**
```sh
# music: does it auto-play? (state=3 means playing)
adb shell am start -a android.media.action.MEDIA_PLAY_FROM_SEARCH -p com.spotify.music \
  --es query "Arijit Singh" --es android.intent.extra.focus vnd.android.cursor.item/artist --es android.intent.extra.artist "Arijit Singh"
adb shell dumpsys media_session | grep -E "package=|state=PlaybackState"
#   repeat with -p com.google.android.apps.youtube.music
# timer and alarm: headless? de-duplicated? (send the alarm twice; delete both afterwards)
adb shell am start -a android.intent.action.SET_TIMER --ei android.intent.extra.alarm.LENGTH 60 --ez android.intent.extra.alarm.SKIP_UI true
adb shell am start -a android.intent.action.SET_ALARM --ei android.intent.extra.alarm.HOUR 6 --ei android.intent.extra.alarm.MINUTES 1 \
  --es android.intent.extra.alarm.MESSAGE friday-test --ez android.intent.extra.alarm.SKIP_UI true
# maps: does navigation start at once in two-wheeler mode?
adb shell am start -a android.intent.action.VIEW -d "google.navigation:q=Indiranagar%2C%20Bengaluru&mode=l" -p com.google.android.apps.maps
# panels: what does Pixel 16 show?
adb shell am start -a android.settings.panel.action.INTERNET_CONNECTIVITY
adb shell am start -a android.settings.panel.action.WIFI
# brightness applies live? (note the old value first; restore it)
adb shell settings get system screen_brightness; adb shell settings put system screen_brightness 22
```
The Wi-Fi/Bluetooth Allow dialogs and the DND implicit rule need Friday's own uid. Test them from the app once the skill code exists.

## Open questions
- **Spotify and YouTube Music:** do today's builds still handle `MEDIA_PLAY_FROM_SEARCH`, and does either auto-play? Does free-tier YouTube Music keep playing in the background? [UNCERTAIN, checklist]
- **Google Clock:** does it match AOSP (silent create, de-dup, lock screen)? [UNCERTAIN, checklist]
- **WEB_SEARCH:** chooser, Google app, or Chrome on this phone? Moot if we use the VIEW URL.
- **Pixel overlays and flags:** `Panel.ACTION_WIFI` (`slices_retirement`), the Quick Settings tile layout, and torch strength levels.
- **Policy, for the owner:** may Friday auto-tap **Allow** on the Wi-Fi/Bluetooth consent dialogs?
  - It is the system confirming a third-party request. Auto-accepting resembles the "never auto-accept permission pop-ups" rule (README risk 5).
  - Proposed: the owner taps Allow (one tap), and Friday's approval card stays visible above it (§7).
- **Launches while locked:** an AOSP SKIP_UI alarm/timer works, being `showWhenLocked`. Other launches land behind the keyguard [UNCERTAIN]. Proposed: check `KeyguardManager.isKeyguardLocked` and ask the owner to unlock (research/02 §4.6).
- **Decision log:** if the dispatch order and "no auto-Allow" are accepted, add them to docs/DECISIONS.md. This note does not change it.

## Dead ends
- `WifiManager.setWifiEnabled` (target ≥ 29), `BluetoothAdapter.enable/disable` (target ≥ 33), and `TelephonyManager.setDataEnabled(ForReason)` (privileged) [primary].
- **Dark mode by API:** `UiModeManager.setNightMode` / `setNightModeActivated` (silent no-op), `pm grant MODIFY_DAY_NIGHT_MODE` (not a development permission), and writing only `Secure.UI_NIGHT_MODE` [primary].
- **Writing settings directly:** `Global.WIFI_ON` (no observer), `Global.MOBILE_DATA` (not observed), `Global.ZEN_MODE` (reverted: "Fixing zen mode setting") [primary].
- **Turning off the owner's DND** with `setInterruptionFilter(ALL)` or listener/AutomaticZenRule APIs at target ≥ 35; `am compat disable 308670109` on a user build [primary].
- `ACTION_WEB_SEARCH` into Chrome only pre-fills the omnibox [primary].
- `MEDIA_PLAY_FROM_SEARCH` to YouTube: ignores the query [secondary].
- **Spotify via `MEDIA_PLAY_FROM_SEARCH`** with `spotify:…:play` data broke in 8.4.83 (2018–19); VIEW worked instead [secondary].
- `google.navigation` has no transit mode [primary].
- `BATTERY_PROPERTY_STATE_OF_HEALTH` is not in the public SDK 36/37 [primary].
- **Process:**
  - Current Spotify/YouTube Music manifests could not be read: APK mirrors returned 403 or were blocked.
  - The shared WebSearch quota ran out mid-research, so 2023–26 forum coverage for Spotify/YouTube Music is thin.
  - In zsh, a shell function variable named `path` clobbers `PATH`; use another name.

## Sources
- **AOSP** (android.googlesource.com, tags `android-16.0.0_r1` / `r4`):
  - frameworks/base:
    - Accessibility and background launch: `services/accessibility/…/AccessibilityServiceConnection.java`; `services/core/…/am/{ActiveServices,ServiceRecord}.java`; `services/core/…/wm/{BackgroundActivityStartController,BackgroundLaunchProcessController,WindowState}.java`; `services/core/…/notification/NotificationManagerService.java`.
    - Package visibility: `services/core/…/pm/{AppsFilterImpl,AppsFilterUtils}.java`.
    - Client APIs: `core/java/android/app/{ContextImpl,ActivityOptions,NotificationManager,UiModeManager}.java`; `apex/jobscheduler/framework/…/AlarmManager.java`; `core/java/android/os/{BatteryManager,PowerManager}.java`; `core/java/android/content/Context.java`; `core/java/android/view/WindowManager.java`; `core/java/android/provider/AlarmClock.java`; `media/java/android/media/AudioManager.java`; `telephony/java/android/telephony/TelephonyManager.java`.
    - System services and manifests: `services/usage/…/StorageStatsService.java`; `core/res/AndroidManifest.xml`; `packages/Shell/AndroidManifest.xml`.
  - Modules:
    - Wifi: `framework/…/WifiManager.java`, `service/…/WifiServiceImpl.java`.
    - Connectivity: `framework/src/android/net/ConnectivityManager.java`.
    - Bluetooth: `BtPermissionUtils`, `BluetoothAdapter`.
    - Permission: `roles.xml`, `EnhancedConfirmationService`.
  - Apps and services:
    - packages/apps/Settings: `AndroidManifest.xml`, `wifi/RequestToggleWiFiActivity.java`, `panel/PanelFeatureProviderImpl.java`.
    - packages/apps/DeskClock: `HandleApiCalls.kt`, `AndroidManifest.xml`.
    - packages/services/Telecomm: `NewOutgoingCallIntentBroadcaster.java`.
    - frameworks/av: `CameraService`.
- **Chromium** (main): `chrome/android/java/AndroidManifest.xml`; `browser/searchwidget/{SearchActivity,SearchActivityUtils,SearchUiCoordinator}.java`.
- **Local SDK:** `/opt/homebrew/share/android-commandlinetools/platforms/android-36/{android.jar, android-stubs-src.jar}` and `android-37.0/data/api-versions.xml`.
- **developer.android.com:**
  - guide/components/{intents-common, intents-filters, google-maps-intents, activities/background-starts}
  - training/package-visibility/{automatic, declaring, use-cases}
  - about/versions/{14/behavior-changes-14, 15/behavior-changes-15, 16/behavior-changes-16, 16/behavior-changes-all}
  - media/implement/assistant
- **Vendors:**
  - developers.google.com/maps/documentation/urls/get-started
  - developers.google.com/youtube/v3 (getting-started, docs/search/list)
  - developer.spotify.com/documentation/android (content-linking, getting-started)
- **Secondary:**
  - community.spotify.com threads 4898125 (2020) and 4633926 (2019)
  - github.com/nachtmaar/androlyzedoc (2015 Spotify manifest)
  - github.com: yunusemreuluc/airon-mobile, NickMonrad/kernel-ai-assistant, kentuckyfriedtakahe/smart-custom, radixdev/whats-that-song, lihsun82/nubo-voice
  - bgr.com/2208592
