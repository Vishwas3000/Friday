# 23 — The task card: understand each request once

_2026-10-18 · Design only, nothing built · Line numbers are at commit 1871da5. Assistant.kt, AgentService.kt, Candidates.kt, LayaEngine.kt and Skills.kt have uncommitted edits right now (D-049 and more), so their lines will move; function names are given too · Trust tags: [primary] read in the code ("re-run": the Kotlin regexes re-run in Python on the Mac, not on the phone) · [UNCERTAIN] not checked_

The owner's problem: Friday is slow and sometimes does the wrong thing. We split each request into five stages:
1 Understand → 2 Plan (intent or deep link → remembered route → Laya) → 3 Act → 4 Check → 5 Answer & learn.
This note designs stage 1's output, the **task card**: one structured reading of the request, made once, used by every later stage.

**Short answer.**
- Today about a dozen places re-read the raw request, each with its own word lists, and they disagree (§1). Several wrong actions come straight from that. "open youtube and search for lofi music" runs a Google search in Chrome for the whole sentence [primary, re-run].
- The card is one Kotlin data class of plain fields, filled by today's own rules in one function (§2). It also says how Friday will know the task is finished (§3), which is the general fix for overshooting.
- Ship it in four steps, behaviour unchanged first: log the card; skills read it; checks run on it; the Writer fills it when rules can't (§5).

## 1. Where understanding happens today [primary]
| Where | What it reads from the request |
|---|---|
| `Skills.kt:87-109` RULES, `:114-121` `ruleSkill` | the skill, by keywords, first match wins; "open X and <task>" is routed on the task (`OPEN_AND`, `:147`) |
| `Skills.kt:174-181` `route` | no rule: Laya picks one of the 16 `SKILLS` (`:59-76`) at ≥ 0.7 (`:111`), else the owner picks from the top 3 |
| `Skills.kt:145` `taskPart` | the text after "open X and/then/to"; feeds `Assistant.slots` (`Assistant.kt:22`) and `mediaQuery` (`MediaSkills.kt:112`) |
| `Skills.kt:166-171` `taskAfterOpening` | a second "what's left after open X" (strips the verb and the app's label): fresh start, or "open X" alone (`:194`, `:201`) |
| `Skills.kt:203-206` `GenericSkill` | text to type: quotes, else the words after "search for", "find", "play"…; else Laya picks a span (`SLOT_Q`, `:112`) |
| `Slots.kt:19-139` | quotes, clock time, duration, date range, 1–4-word spans, `after(keys)`; its own `STOP` and `CUT` lists (`:122-128`) |
| `MediaSkills.kt:104-137` | `mediaKey` (pause, next…), `mediaQuery` with five more word lists (`:139-146`), `musicFocus`; strips the app's name itself (`:133-137`) |
| `PeopleSkills.kt:150-219` | destination, search query, callee, number, email or chat, subject, recipient and body, item, tracking, photo subject; own lists (`:154-160`) |
| `PhoneSkills.kt:35-40, 65-97, 132-185` | battery / storage / alarm words; reminder label; alarm vs timer by the word "timer" (`:82`, `:94`); on/off, which setting, brightness level |
| `AppResolver.kt:40-46` `named`; `Assistant.kt:73-82` `appFor` | the app: an alias, else the longest installed label in the request; kept only if it can do the skill (`:75`); else the only app, the remembered pick, or ask |
| `Assistant.kt:212-274` `runLaya` | the remembered path's key (`:215`, `Routes.kt:82-101`), the hint (`:221`), what the goal is about (`:228`), the app to open (`:271`), the text to type (`:272`) |
| `Routes.kt:62-76` | `contentWords` / `aboutWords` with their own `STOP` and `NAV` lists; they drive the skim, "done on tap", "already selected" and hints |
| `Teach.kt:153, 166` | whether typed text came from the goal; the taught path's key |
| `Safety.kt:14-39` | risk, per step, from the foreground app (money apps, `AppResolver.kt:85`) and the element's label; never from the request |

**In progress while this was written (uncommitted `Skills.kt`):**
- A `COUNT` regex reads "how many transactions" from the request.
- spend_query runs with the fixed goal "show the transaction history", so every phrasing shares one remembered path.

Both are more request-reading inside a skill. The fixed goal is the card's idea done by hand for one skill. If they land, `count` joins the card's slots, and the fixed goal becomes spend_query's `task`.

**Duplication and inconsistencies:**
1. **"open X and search for Y" searches the whole sentence in Chrome.** The task "search for Y" routes to web_search (`Skills.kt:101`, `:119`). WebSearch strips "search for" only at the start of the *whole* request (`PeopleSkills.kt:161`, `:175`) and always uses Chrome (`:36`). "open youtube and search for lofi music", "open maps and search for coffee" and "open the play store and search for whatsapp" all become Google searches for the full sentence [re-run].
2. **Skills parse the full request, not the task.** `Assistant.slots` uses the task part (`Assistant.kt:22`), but six PeopleSkills functions build `Slots(request)` (`PeopleSkills.kt:170-217`). "open google maps to navigate to MG road" navigates to "navigate to MG road"; "open uber to book a cab to the airport" sets the destination "book a cab to the airport" [re-run].
3. **"when's my next alarm" asks what time to set an alarm.** The alarm rule comes before device_status (`Skills.kt:93-94`), so DeviceStatus's next-alarm answer (`PhoneSkills.kt:30`, `:39`) can't be reached by rules, and AlarmTimer asks (`:86`). Same for "turn off my alarm" [re-run].
4. **"set a timer for 5" sets a 5:00 AM alarm.** "for 5" parses as a clock time (`Slots.kt:67-71`) and there is no duration, so the alarm branch runs (`PhoneSkills.kt:105-110`). D-037 says only "timer" makes a timer [re-run parse; outcome read in the code].
5. **A named app is dropped when it isn't on the skill's list.** `appFor` keeps it only if it's a candidate (`Assistant.kt:75`), so "open whatsapp and call mom" calls from the Phone app (`AppResolver.kt:74`). D-027 says the named app wins.
6. **The logged skill isn't the one that ran.** "open youtube and play despacito" routes to play_music (`Skills.kt:151`), and PlayMusic hands it to PlayVideo (`MediaSkills.kt:25-26`). The trace says `route: play_music` (`Assistant.kt:53`); the comment at `Skills.kt:116-117` says play_video.
7. **Understood, then dropped.** The date range (`Slots.kt:31-32`) is used only for photos (`PeopleSkills.kt:218`). "what were my expenses last month" reads the newest dated rows on screen, whatever the month (`Skills.kt:207-210`).
8. **Two "what is it about" lists disagree.** `Slots.STOP` has "last" (`Slots.kt:125`) and `Routes.STOP` doesn't (`Routes.kt:62-63`); `NAV` has "check" (`Routes.kt:72`) and `Slots.STOP` doesn't. So "check my last payment in gpay" is about {last, payment}: the skim wants one element with both words (`Assistant.kt:399`), while the slot spans offer "check" [re-run]. The in-progress fixed goal sidesteps this for spend_query only.
9. **The same small jobs, done three ways.** The rest after "open X": `OPEN_AND` (`Skills.kt:147`), `taskAfterOpening` (`:166-171`), `NAV` (`Routes.kt:72-76`). Removing the app's name: `MediaSkills.kt:133-137`, `Skills.kt:168`, `Routes.kt:76`. Picking a span: all spans (`Skills.kt:206`) or without skill words (`PeopleSkills.kt:136-139`).
10. **Money apps can waste a Laya pass on text.** The text to type, and the span pick behind it, is worked out before the verdict that blocks typing in money apps (`Assistant.kt:270-279`, `Safety.kt:36`).

## 2. The card
One `understand()` call fills it before routing. It uses today's helpers (`taskPart`, `ruleSkill`, `Slots`, `AppResolver`, `Routes.aboutWords`, the media and people parsers), not new parsers. JSON goes through org.json, like `RouteStep.json()` (`Routes.kt:35-36`).
```kotlin
/** What one request means: made once by understand(), read by every later stage (research/23). */
data class TaskCard(
    val request: String,              // as typed; stays the goal Laya and remembered paths see unless a skill sets its own (§6)
    val task: String,                 // what to do once the app is open
    val skill: String?,               // a SKILLS key; null = "something else"
    val app: String?,                 // package: named in the request, else the remembered or only app; null = Plan asks
    val about: List<String>,          // what the goal is about: the words the finished screen should show
    val query: String? = null,        // the thing to play, search, order, find, set or check: "despacito", "bluetooth", "timer"
    val person: String? = null,       // who to call or message
    val text: String? = null,         // the owner's own words to type: a message body, a reminder label
    val place: String? = null,        // where to go
    val clock: String? = null,        // "06:00"
    val seconds: Int? = null,         // a duration
    val range: String? = null,        // "2026-09-01/2026-09-30", from "last month"
    val control: String? = null,      // on | off | up | down | 40% | pause | resume | next | previous | stop
    val mode: String = "act",         // "read": look and report, change nothing | "act"
    val risk: String = "none",        // "money": read-only app (D-026) | "commit": the last tap is the owner's | "none"
    val done: Done = Done("subject"), // how Friday knows it has finished (§3)
    val fg: String? = null,           // context: the app in front before Friday's window (§4)
    val by: String = "rules",         // who filled it: rules | laya | owner | writer | previous
) { fun json(): JSONObject = TODO() }
data class Done(val kind: String, val arg: String? = null)
```
No amount field: Friday never pays (D-026), and amounts are read from the screen, not the request (`Skills.kt:125-142`). The only number-as-target today is a brightness percent (`PhoneSkills.kt:167`), which goes in `control`.

| Field | Filled today by |
|---|---|
| task | `taskPart` (`Skills.kt:145`); one definition with `taskAfterOpening` in step (b) |
| skill | `ruleSkill`, then Laya, then the owner (`Skills.kt:174-181`); the PlayMusic → PlayVideo hand-off (`MediaSkills.kt:25-26`) moves here |
| app | `AppResolver.named` (`AppResolver.kt:40-46`), else `default` or the only candidate (`:25-37`); the "which app?" question stays in Plan |
| about | `Routes.aboutWords(task, the app's label)` (`Routes.kt:76`), exactly as `runLaya` computes it (`Assistant.kt:228`) |
| query | `mediaQuery` (`MediaSkills.kt:111`), `searchQuery`, `shoppingItem`, `photoQuery` (`PeopleSkills.kt:175, 209, 216`), quotes or `after(…)` (`Skills.kt:203-204`); DeviceStatus / DeviceSetting words (`PhoneSkills.kt:37-39, 135-137, 177-183`); "timer" or "alarm" (`:94`) |
| person, text | `callee` (`PeopleSkills.kt:178`), `messageParts` (`:195-206`), `reminderText` (`PhoneSkills.kt:65-66`) |
| place | `destination` (`PeopleSkills.kt:169-172`) |
| clock, seconds, range | `Slots.clock`, `durationSec`, `timeRange` (`Slots.kt:26-32`) |
| control | `mediaKey` (`MediaSkills.kt:104-108`); DeviceSetting's off-words and brightness (`PhoneSkills.kt:133, 165-167`) |
| mode | new, from existing lists: read for `READ_SKILLS` (`Skills.kt:124`), device_status, order tracking (`PeopleSkills.kt:213`), and in-app tasks whose verb is in `NAV` (`Routes.kt:72-73`) |
| risk | money if `app` is in `AppResolver.MONEY` (`AppResolver.kt:85`); commit for call, message, ride and buying, the taps `Safety.RISKY` asks about (`Safety.kt:14-19`) |
| done | §3 |

Examples, from today's rules except where the "today" column differs:

| Request | Card (key fields) | Today |
|---|---|---|
| check my last payment in gpay | spend_query · GPay · about [last, payment] · read · money · done amount(dated) | same; "last" should not be an about word (§1.8) |
| open youtube and play despacito | play_video · YouTube · query despacito · done playing | logged as play_music (§1.6) |
| open clock and show the stopwatch | open_app · Clock · task "show the stopwatch" · about [stopwatch] · read · done subject | same |
| open youtube and search for lofi music | open_app · YouTube · query "lofi music" · done subject (the query on screen once typed) | Chrome, whole sentence (§1.1) |
| set a timer for 5 | alarm_timer · query timer · no time → ask "how long?" · done none | a 5:00 AM alarm (§1.4) |

**How each stage reads it.** Plan: skill and slots pick the intent (D-028); `app`, `request` and the typed text look up a remembered path (`Routes.find`, `Routes.kt:97-101`); else Laya. Act: `runLaya` takes `about`, the text to type and `mode` from the card instead of recomputing them (`Assistant.kt:228, 270-274`); skill-made sub-goals ("open the chat with rahul") keep computing their own. Check: `done`. Answer & learn: a run is kept as "verified" only when `done` passed (today `succeeded()`, `Assistant.kt:203`). When Plan resolves a missing app, it copies the card with `app` and recomputes `about`.

## 3. Done checks
The card names one check. Kinds mirror the D-049 task suite's checks (foreground app, screen words, selected tab, media playing; `scripts/task_suite.py`, uncommitted), so a suite failure can be compared with what Friday believed.

| Skill | Done when (facts on the phone) | Today [primary] | Missing |
|---|---|---|---|
| play_music, play_video | `playing`: audio became active after Friday paused what was playing | `isMusicActive` + pause first + `succeeded()` (`MediaSkills.kt:68, 77-83, 98-101`) | *which* app plays: MediaSession needs a notification listener (research/10 line 166); the suite reads it over adb |
| alarm_timer: alarm | `alarm hh:mm`: Android's next-alarm record is this alarm | `AlarmManager.nextAlarmClock`, 3 s (`PhoneSkills.kt:113-120`) | nothing; an earlier alarm hides it, and the answer says so |
| alarm_timer: timer | `none`: no public API reads a timer back | the answer says where to look (`PhoneSkills.kt:102-103`) | never call it verified |
| spend_query | `amount dated`: at least one amount with a date beside it on screen | `readAmounts(onlyDated)`, only after Laya says DONE (`Skills.kt:208-210`) | check before each step; use `range` |
| balance_query, bill_query | `amount`: an amount on screen | any amount, an offer included when nothing is dated (`Skills.kt:140`), then kept as verified | require a dated amount, or one beside the subject's words |
| open_app + task, something else | `subject`: a selected tab names the subject, or the tap just did, or the screen looks like a remembered finished screen; an in-app search: its query on screen after Friday typed it [UNCERTAIN] | `selectedLabels` (`Assistant.kt:244-249`), done-on-tap (`:297-303`), `looksFinished` with a hint (`:239-242`, `:329-332`) | only inside `runLaya`; replay runs every remembered step without checking first (`:335-366`) |
| device_setting via Settings | `switch on/off`: the switch named by the subject shows the wanted state (`checked`, `Candidates.kt:21-22`) | "Done" on Laya's DONE (`PhoneSkills.kt:188-189`) | all of it; it would also stop a second tap undoing the first |
| device_setting direct, device_status | `api`: the answer comes from an API (torch, brightness, battery, storage) | `PhoneSkills.kt:34-57, 143-172` | Wi-Fi is the system's Allow dialog (`:152-156`): `owner`; reading `isWifiEnabled` needs ACCESS_WIFI_STATE, not declared (`AndroidManifest.xml:6-8`) |
| navigate, web_search, dial, email draft, open X | `front pkg`: the intent's app came to the front | not checked (`PeopleSkills.kt:25, 37, 48-49, 76-77`); `GenericSkill` waits but ignores the result (`Skills.kt:195, 201`) | `waitUntil(foreground == pkg)`, as `openAndWait` does (`PeopleSkills.kt:129-133`) |
| call by name, message, ride, buying | `owner`: the commit tap (Call, Send, Book, Buy) is the owner's, through Safety's ask | "Calling …", "Sent to …", "set up" on Laya's DONE alone (`PeopleSkills.kt:53, 70, 94-95`), even if the owner skipped that tap | track the owner's Run on the commit tap; a sent message should show its body on screen [UNCERTAIN] |
| order tracking, photos | `subject` | Laya's DONE (`PeopleSkills.kt:109, 125`) | the subject check |

**Overshooting.** The rule: run the check before every Laya pass and after every action, and stop the moment it passes; Laya is not asked again. Today only the `subject` checks do this, which already covers the two known overshoots: the "1m" preset that started a real timer (research/20 §3) and Start on the stopwatch (D-048). Still open: switch goals (a second tap undoes the first), read goals (checked only after Laya's own DONE), and replay of a remembered path when the goal is already met. A remembered "turn on dark mode" tap would turn it off if it was already on [UNCERTAIN: not run]. A check that can't be run (`none`) never claims success (research/21 §7.1: Autobile, LayaBrowse).

## 4. Context v1
The smallest context that helps:
- **The app in front before Friday.** The owner types in Friday's own screen (`MainActivity.kt:31-37`), so at Run the foreground is Friday, and `state()` skips Friday's windows (`AgentService.kt:115`). The service already gets every window change (`agent_service.xml:5`, `AgentService.kt:34-37`). It remembers the last package that isn't Friday, System UI or the launcher. Uses, each its own measured change:
  - the app, when the request names none and that app can do the skill (before the remembered pick, `Assistant.kt:79`);
  - for "something else", reopen it before Laya acts. Today Laya may start with only system bars to read [UNCERTAIN: not run].
- **The previous card**, in memory next to `lastAsk` (`AgentService.kt:265`), for 10 minutes [UNCERTAIN: the number is a guess]. Explicit words only:
  - "do it again", "again", "repeat that": the same card, `by = previous`;
  - "now in X", "in X instead", "same on X": the same card with `app` = X.
  - A re-run still asks before every risky tap (Safety is per step).
- **Installed-app labels:** `AppResolver.installed` (`AppResolver.kt:19-22`), as today, for `named`. Not put into the Writer's prompt (too long); the Writer names an app in words, and `named` resolves it.
- **App preferences:** the remembered pick per skill (`AppResolver.kt:34-37`), as today.

**Out of scope for v1:** what is on the screen at request time beyond its package; notifications, contacts, calendar, location, usage stats (D-045 item 6); more than one previous card; pronouns ("call him back"); two tasks in one request ("set an alarm and play music"); Hinglish rules (research/22 §5.7).

## 5. Migration plan
Each step ships alone, keeps every self-check passing, and is compared on the D-049 suite before and after.
- **(a) Make the card, log it, change nothing.**
  - `Card.kt`: `TaskCard`, `Done`, `understand(request, installed, fg, prev)`, `json()`, `cardSelfCheck()`. It calls today's functions; PeopleSkills' private parsers become `internal`, and `AppResolver.named` gets a pure twin over a list of apps.
  - In `Assistant.run` (`Assistant.kt:50-53`): build the card first, add `card: {…}` to the trace, and copy in the skill when Laya or the owner routes. Add `"card"` to the request log entry (`:65-68`). The service records the previous app (logged only).
  - Skills still parse for themselves. Check: the trace's `route:` equals the card's skill, except the PlayMusic → PlayVideo hand-off.
- **(b) Skills read the card instead of re-parsing**, one skill family per commit:
  - **People.** Fixes §1.2 and, by turning "open X and …" into an in-app task when X can't do the task's skill, §1.1 and §1.5. That is D-036's intent.
  - **Media.** The hand-off moves into `understand`, which fixes §1.6.
  - **Phone.** Fixes §1.3 and §1.4.
  - **Generic.** `query` replaces the `after(…)` guess, and one `task` definition replaces `OPEN_AND` plus `taskAfterOpening`.
  - Each intended change becomes a row of the Mac test below.
  - Keep the string passed to `runLaya` as it is today, or remembered paths stop matching.
- **(c) Checks run on the card.**
  - `met(card.done, screen)` runs before each Laya pass and after each action (generalising `Assistant.kt:239-249, 297-303`), and before a replay.
  - Add the `switch` and `front` kinds. Read checks run before each step. Keep a run as verified only on `playing`, `alarm`, `amount`, `switch`.
  - Answers say "couldn't confirm" when a check fails.
  - Optional, measured: in `read` mode a risky tap is blocked, not asked; then blocked elements never reach Laya (`Assistant.kt:257-259`). Watch for a needed "Proceed".
  - Skip the text pick when `risk = money` (§1.10).
- **(d) The Writer hook.**
  - **When:** only when rules give no skill and Laya's route is below 0.7, before asking the owner [UNCERTAIN: its place versus Laya's route is unmeasured]. A missing slot first tries Laya's span pick, as today.
  - **Output:** one grammar-forced JSON: `{"skill": <the 16 ids or "other">, "app", "task", "query", "person", "text", "place", "time", "control": string or null}`. The GBNF is made offline from the JSON schema (research/13 §6.4).
  - **Rules redo the rest:**
    - a string not found in the request is dropped;
    - `named` resolves the app phrase;
    - `Slots` parses the time phrase;
    - `about`, `mode`, `risk` and `done` come from rules, never from the model.
  - **Cost:** about 1–2 s to load, about 3 s to fill, and Laya must be freed first and reloaded after (D-016, D-030, research/13). So it is never used when rules or a confident route fill the card.
  - **Gate:** on the Mac, its card accuracy on the fixture file below (one model job at a time, D-042); on the Pixel 8, its latency. Needs D-041 built.

**Self-checks that must keep passing** (the "selfcheck" bridge command, `AgentService.kt:63-65`):
- `routingSelfCheck` (`Skills.kt:150-163`); `slotsSelfCheck` (`Slots.kt:142-174`); `mediaSelfCheck` (`MediaSkills.kt:160-180`); `routesSelfCheck` (`Routes.kt:144-168`); `peopleSelfCheck` (`PeopleSkills.kt:222-251`).
- `Skills.kt:151` asserts play_music for "open youtube and play despacito". That stays true of `ruleSkill`; the card's own check asserts play_video.

**Mac-side test: request → expected card, no phone.**
- `scripts/cards.json` holds generic requests with only the fields that matter per row, plus a `today` note for the rows that document a current bug.
- `CardTest` (JUnit, `android/app/src/test`) runs `understand()` on each row with a fixed app list built from public package ids already in `AppResolver.CATALOG`. Run it with `./gradlew :app:testDebugUnitTest`.
- `app/build.gradle.kts` has no test dependencies yet (`:27-35`). It needs JUnit, and org.json too, because android.jar's copy only stubs it in local tests [UNCERTAIN: not set up here].
- The same file scores the Writer later.
- Rows, beyond the five examples in §2:

| Request | Expected |
|---|---|
| set an alarm for 6 am | alarm_timer · clock 06:00 · done alarm 06:00 |
| start a 10 minute timer | alarm_timer · query timer · seconds 600 · done none |
| when's my next alarm | device_status · query "next alarm" · done api |
| open google maps to navigate to MG road | navigate · place "MG road" · done front(Maps) |
| message rahul that I'll be late | message · person rahul · text "I'll be late" · risk commit · done owner |
| turn on bluetooth | device_setting · query bluetooth · control on · done switch on |
| what were my expenses last month | spend_query · range = last month · read · done amount dated |
| do it again (after the alarm) | the alarm's card · by previous |

## 6. Risks and don'ts
- **Safety.** The card never relaxes Safety. `Safety.verdict` stays per step, on the foreground app (`Safety.kt:32-43`). Card fields may only tighten it.
- **The Writer.** It never decides `mode`, `risk`, `done` or the app's package; it only quotes words from the request.
- **Remembered paths.** Don't change Laya's goal string or `Routes.key` (`Routes.kt:82-85`) while refactoring. Saved paths are keyed on the goal's words (D-040).
- **Word lists.** Don't "fix" about-words or the stop lists inside the refactor. They drive the skim, done-on-tap, "already selected", hints and the D-049 shortlist. Change them in their own measured commit, starting with "last" (§1.8).
- **Privacy.** Don't put what a check reads (amounts, payees) into the card or the trace. The card describes the request. Side finding: D-026 says money answers are "shown, not logged", but the request log stores every answer (`Assistant.kt:65-68`), including amounts read out (`Skills.kt:209`).
- **Checks can be fooled.**
  - `isMusicActive` is true for any app.
  - A selected label can be a list item, not a tab (`Candidates.kt:116`).
  - An offer looks like a balance.
  - Count false "done" on the suite before a check may keep paths as verified.
- **Scope.** No speculative fields (no amounts to pay, no multi-step plans, no per-field confidence) and no plugin system: one data class, one function.
- **Concurrency.** Assistant.kt, AgentService.kt and Skills.kt are being edited now (D-049, spend_query); step (a) touches only the routing lines and the log line in Assistant.kt.

## Not checked
- Nothing ran on the phone. §1's bugs come from reading the code and re-running its regexes in Python.
- Whether `subject` checks fire on list items in real apps; how often `read` mode would block a needed tap.
- The Writer's card accuracy and real latency including the Laya swap.
- JVM unit tests in this project (none exist yet).
- **Open question for the owner:** "open settings and check the storage" routes to device_status, which answers without opening Settings. The D-049 suite expects the Settings page. Should "open X and …" always show X?
