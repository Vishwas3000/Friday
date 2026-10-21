# Shared coordinator and evaluation checkpoint

_20 October 2026 · installed Pixel 8, Android 16/API 36 · measured checks are distinguished from proposals_

## What changed in execution

Research and registered device plans use one persistent Kotlin coordinator backed by Room. Device plans save typed inputs, dependency edges and output bindings. Attempts, source evidence, cancellation epochs and publication remain attached to the originating Friday chat. Registered application code validates the plan and verifies each result; app or model text cannot add executable capabilities.

The registered device capabilities are `calendar.create`, `bills.read`, `app.open`, `app.search`, `text.draft` and `task.publish`. Request interpretation is deterministic. A general model planner, arbitrary cross-app composition and complete migration of legacy workflows are **not implemented**. Message drafts with an app destination retain the existing handoff path.

Completion checks compare actual output fields, source package, observation freshness and the canonical input binding. Dependencies consume only verified outputs. Interrupted external writes reconcile against the destination before retry; an uncertain outcome stops with an explicit reason rather than dispatching the write again. Missing inputs and capabilities persist as `WAITING_INPUT` or `UNSUPPORTED`. The current input-correction flow asks for a complete corrected request; it does not patch an existing task in place.

### Calendar

Calendar creation now uses Android CalendarProvider. The parser supports common English calendar requests, named months, strict dates, next-occurrence year inference, explicit time ranges/durations and all-day blocks. Ambiguous or unsupported fields require clarification. All-day timestamps use UTC midnight and an exclusive end date. A writable calendar is selected and bound to the task before insertion.

The driver queries for an exact existing event before inserting, then reads back title, dates, time zone, all-day state and Busy status. A separate read-only `calendar_observe` bridge command queries by title/date scope rather than trusting Friday's returned event ID. Runtime permission prompts remain with the owner under D-014.

### Bills and unreadable screens

Plural bill requests now route to bill reading instead of plain app opening. The reader accepts explicitly labeled currency amounts, rejects promotional/credit-limit values and retains source text and coverage. It does not add visible card amounts into an assumed account-wide total; an overall total requires an explicit all-cards label. No readable app tree produces a blocked outcome, with no fabricated balance or bill amount.

Generic model `DONE` and an empty read result no longer establish task success. Unverified paths return an explicit inability to verify. These changes cannot supply missing observations from protected or otherwise inaccessible screens.

## What changed in evaluation

The [harness guide](../docs/NAVIGATION_HARNESS.md), [runner/graders](../scripts/navigation_harness.py) and [public cases](../evals/navigation/cases.json) provide:

- Independent outcome checks from raw app observations or complete provider queries. Friday's state or `verified` flag is not accepted as proof.
- Exact case/revision/hash binding and replayable JSONL records with build, model, dataset, device and app metadata. Unknown versions and timing components remain unknown.
- Separate task success, correct blocked behavior, false completion, duplicate writes, wrong-app outcomes, interventions and timing.
- Distinct synthetic, controlled-environment and live sources. Skipped/unavailable cases remain visible in coverage denominators.
- Checks for calendar fields and duplicate/retry identity, exact search inputs/results, labeled bills, draft fields, research origin/whole-answer capture, cancellation and recovery.

There are **24 case definitions across eight families**. Only `app.clock`, `app.maps` and `maps.search` have automatic bridge runners. The other 21 need controlled observations; the harness does not automatically run calendar mutations, send messages, make bookings or operate payments. Raw reports and personal app data remain in ignored `captures/`, not public fixtures.

## Checks and live evidence

| Check | Measured result | Scope |
| --- | --- | --- |
| Coordinator checks | **35 passed, 0 failed** | Real Room on the Pixel with fake capability/provider drivers; not 35 live task completions. |
| Focused Research selectors | **4 groups passed** | Chat, research, Copy and hotel fixtures; no model inference or actual Gemini interaction. |
| Component selfcheck | **19 groups passed** | Installed Pixel build, including calendar/routing checks. |
| Harness grader selfcheck | **42 checks passed** | Synthetic positive and false-positive observations; zero device operations from this command. |
| Manifest validation | **24 cases valid** | Three automatic navigation cases; 21 controlled-observation cases. |
| Native calendar live task | Saved outcome independently confirmed | One matching active saved event; correct date range, all-day state, time zone and Busy status. |
| Automatic live navigation | **1 of 3 goals passed** | Clock passed; the two Maps requests exposed wrong-app routing. This is three recorded cases, not full-suite acceptance. |
| CRED list and follow-up total | Both `BLOCKED`; no bill data retrieved | No readable CRED application window. Correctly withholding an answer is not goal completion. |

The live calendar task recorded **36.449 seconds of active execution**, including the permission flow. The permission interaction was not independently instrumented, so this is not an unassisted acceptance claim or a model-latency measurement. A fresh complete provider query found exactly one matching event and independently matched the saved ID. No before-query or real process-death/retry collector was recorded for that task; duplicate-free restart acceptance remains open.

The installed APK SHA-256 is `d619ea0afa59f095c0f856db73fda12755be03346f4b873ee259fa33496093f1`. This identifies the tested artifact, built before the checkpoint commit; do not imply it was built from a later documentation-only commit. Private component reports live under `captures/coordinator-tests/` and `captures/calendar-tests/`.

### First automatic navigation run

| Case | Independent observation | Harness wall time | Synchronous task-call time |
| --- | --- | --- | --- |
| `app.clock` | Correct Clock application tree; goal passed | 5.219 s | 4.399 s |
| `app.maps` | Google application tree, not Maps; goal failed | 39.168 s | 38.448 s |
| `maps.search` | Chrome application tree, not Maps; goal failed | 2.441 s | 1.073 s |

These are one attempt per case on Pixel 8, with a dedicated test chat selected as recorded setup intervention. The failures used legacy routes and did not produce matching durable task snapshots. A previous Clock task snapshot was not accepted as their state. Model/action/wait breakdowns are unknown. Clock's persistent task recorded 1.010 s active execution; it is different from the synchronous bridge call and full collection interval.

The first replay incorrectly described a readable wrong-app tree as unavailable because allowed-app nodes were absent. The grader now treats independently readable wrong-app navigation as a proven failure, while retaining unavailable outcomes for opaque trees, invalid revisions and non-executed records. Four added checks cover that distinction. Replaying the original unchanged raw records now identifies both wrong-app completion signals as false success. No successful rerun is substituted for these failures. Latest live coverage is **3/24 recorded cases, 1/24 independently successful goals**, not a 24-task success claim. Private raw records and scores are in `captures/navigation-harness/`.

An initial Pixel calendar fixture failed because Android formatted the local zone differently from the host JVM. Zone comparison now uses zone rules rather than an assumed display abbreviation; the final host and Pixel checks pass. Earlier failed attempts remain part of the diagnostic history.

The CRED list request and same-chat total follow-up took **6.467 s / 5.582 s** as synchronous bridge calls, with **6.043 s / 4.837 s** recorded task activity. Both used `bills.read` and stopped with an explicit inability to read bills or calculate a total. Independent snapshots exposed System UI and zero application windows; the cause is not established. This is live blocked-behavior evidence, not a passed bill-retrieval case or evidence that the account has no dues. The total follow-up retained the saved bill-app context. Raw records remain in `captures/calendar-tests/live-cred-readback.json`.

## 21 October follow-up: interpretation and calendar clarification

The calendar flow now runs before model routing. Weekdays resolve against the device date and zone; a timed
event needs an end time or duration. A pending request saves its original text, date reference, zone and
missing field in the originating chat. A field-shaped reply creates a linked plan using those saved details;
unrelated commands cannot become event values. An already supplied relative date keeps its reference across
midnight; a newly supplied relative date uses the day of that reply. Unsupported or ambiguous constraints
still require a corrected full request. The original waiting task is retained as history.

On the Pixel, `block my calender on friday at 2pm to meet some one` reached `WAITING_INPUT` with
`What end time or duration should I use?`. The saved reference was 21 October 2026 in the device's India
time zone. There were zero calendar attempts or dispatches, and no matching event existed in the independent
before-query. The collector observed the pending state after 7.631 seconds, including request startup and
polling; the task's zero active execution time is not an end-to-end latency measurement. Parser fixtures
verify that `30 minutes` produces 23 October, 14:00–14:30, with the supplied purpose as its title. This is
fixture evidence, not a claim that this timed event was inserted. The live insertion awaits the owner's
duration choice; UI navigation also awaits an unlocked Pixel.

The installed build passed focused calendar, routing, Writer/intent and media self-check groups. The pending
request survived an update installation. Its APK SHA-256 was
`2825d602ba28d9028ec61f1a937d57f95d07c2e8f515776bcc20ffc02403650e`, independently matched to the installed APK.
These checks do not establish cloud synchronization or acceptance of every calendar phrasing.

Interpreted app plans now open their selected app and keep its identity separate from content. A complete
command matching one visible navigation label can resolve without the SLM; other commands retain grounded
model interpretation. In an earlier live Spotify run, interpretation took 167 ms and the Library tab was
opened correctly, but playback remained paused, so Friday correctly withheld completion. New recovery
reads visible player children beneath structural containers and may press one observed Play control for a
verified paused item in the requested scope. Regression fixtures exclude player text and ordinary item
titles from navigation labels and still reject paused playback. The latest live retry stopped at the locked
device before Spotify became accessible; the new playback recovery remains unverified live. Memory pressure
also prevented a live SLM inference with the new destination envelope. Private reports stay in ignored
`captures/reinstall-20261021/`.

### Time-zone clarification regression

A later selected chat exposed a missing continuation field. `block my calendar for on Friday on 2:00 p.m.
Indian time` asked for local-zone confirmation but saved no `calendar_missing`. The reply `2:00 p.m. ist`
therefore reached independent model routing, selected `alarm_timer`, and dispatched a Clock alarm request.
The task eventually reported `BLOCKED`; withholding completion did not prevent the wrong action.

Zone confirmation now has typed pending metadata. A reply can replace the saved zone and one start time;
the original date and other constraints stay in the same chat. Older pending inputs without the missing-field
key are reparsed using their saved date/zone. A semantically rejected field correction keeps the prior draft.
Alarm contracts reject a route that has no alarm/timer/reminder request, including bare times and calendar
requests, before inspection or dispatch. The UI shows the retained calendar request and offers a short reply
for supported missing fields.

The exact two-turn sequence was replayed through the production bridge in the same selected chat:

| Turn | Observed result | Collection time |
| --- | --- | --- |
| Original calendar request | `WAITING_INPUT`, missing zone | 1.040 s |
| `2:00 p.m. ist` | `WAITING_INPUT`, missing end/duration; calendar request retained | 0.552 s |

Both new plans contained only `calendar.create` and `task.publish`, with zero attempts or dispatch records.
Room readback confirmed the reply's link to the preceding task, the originating chat, 21 October reference
date and original device zone. Independent complete before/after queries for the matching calendar title/date
window were unchanged and empty. Focused calendar/routing and Writer/intent/media contract checks passed;
fixtures cover the two replies, malformed corrections, repeated start time, and rejected orphan alarm plans.
The installed APK matched SHA-256 `42b1a5839b0a972460ceb1533d2b08d7dd38ff921951679d3b3a0dbe8ee75510`.

These are focused regression results, not a new full-suite acceptance result. The duration is still missing,
so this timed event was not saved. The Pixel was locked during the attempted inspection of existing Clock
alarms; the earlier 14:00 alarm's actual state remains unverified. Private records are in ignored
`captures/session-alarm-20261021/`.

## Shared pending-input context

The next session exposed another input failure: an initial calendar-domain turn was misrouted, a later
request referred to the "above task", and a reply supplied date, time and time zone together. The owner
turns existed in Room but had never been published to chat history. The former single-field continuation
could not combine them and fell through to independent routing. The alarm authority guard prevented dispatch.

`TaskIntake` now runs before routing. `TaskInputContext` is saved in typed plan inputs, with chat ownership,
the original goal, bounded owner sources, exact source spans, normalized fields, decoder provenance, pending
question and prior-task link. Calendar creation and missing search text in an already selected app use it.
Failed/unpublished turns can seed a legacy calendar draft; completed/cancelled tasks and other chats are
boundaries. The old calendar-only command-string continuation was removed. No database schema migration or
new dependency is required.

Clear replies collect every field before validation. Ambiguous replies can use one constrained local Writer
`task_update` call. Its output cannot select an app, authorize an action or introduce unknown fields/source
text. Type/span/native-consistency checks run before merging. Model-derived normalizations remain predictions;
they need held-out semantic evaluation. Resource failures retain the draft. Any earlier dispatched external
write stops fresh execution and requests reconciliation. Unsupported calendar constraints require a full
corrected request. Other capability families still need migration to this input context.

Focused Pixel component checks cover the three-turn regression, multi-field updates and corrections, time-zone
aliases, missing title, invalid/ambiguous dates and times, duration conflicts, unsupported constraints, scoped
search continuation, new/cancelled/completed task boundaries, chat isolation, exact-span validation, model
failure and uncertain-write refusal. These fixtures create no actual events, alarms, messages or searches.
Calendar/routing and Writer/media checks passed independently as well.

The current chat's date/time/zone reply was replayed through the production entry point. Friday retained
23 October 2026 at 14:00 India time and asked only for end time/duration in **2.682 seconds** of bridge wall
time, including polling. The saved plan contained only `calendar.create` and `task.publish`, both with zero
attempts. A matching before/after calendar query was unchanged. This proves input recovery and refusal to
write an incomplete event; the timed event remains unsaved because no duration is present in the captured
owner turns. Raw evidence stays in ignored `captures/task-context-20261021/`.

The actual local Writer patch test was rejected before inference with `memory_pressure`: 1,029,713,920 bytes
available, 1,500,000,000 required. There is no live generation-quality result for the new patch prompt.
Synthetic patch validation passed. `INPUT_PREPARED` now records context/interpretation time, and recorded
task active time includes that work in addition to step attempts.

The owner subsequently supplied the missing duration in the same chat. That task completed with one calendar
attempt and one publication attempt, recording **11.205 seconds** of active execution (11.179 seconds in the
calendar step and 0.026 seconds publishing). An independent complete CalendarProvider query confirmed one
matching Busy event on **23 October 2026, 14:00–14:30 India time**. The event remains saved. No harness input
or extra task replay was used for that completion. It occurred before the final timing revision; no
interpretation-stage duration is present for it. This individual owner task is outside suite acceptance totals.
After the final reinstall, focused context/calendar/Writer/media checks and **46 synthetic coordinator checks**
passed. The coordinator checks include persistence of preparation time while awaiting input without dispatch.
A second incomplete replay was intentionally stopped because the latest owner task had already completed.
The final installed APK matched SHA-256 `5e385f4e64af9723474e853ca831ec52a51d667c6444594fa8b3c0f187a5830a`.

The owner's run recorded 10.950 seconds from calendar-attempt creation to the dispatch gate, then 0.032 seconds
to provider readback. Permission checks, calendar selection and the existing-event query are not separately
instrumented in that interval, so the delay cannot be assigned to any one of them. No SLM inference was used.

## Remaining acceptance work

1. Expand controlled collectors for actual readable bill fixtures, text generation, draft transport, cancellation and interrupted saves. The fixture manifest alone is not device coverage.
2. Measure end-to-end and per-stage latency with explicit cold/warm state, retries and interventions. Existing unknown model/action/wait components must not be guessed.
3. Complete clean Research restart/conversation recovery and content-constraint acceptance; the earlier M1 gate remains unpassed. The Nepal hotel workflow remains unverified end to end.
4. Compare the same tasks with runtime held fixed and stronger teachers held fixed, following the [architecture plan](38-navigation-architecture-and-harness-plan.md), before training on the offered GPU. No new teacher or student has been trained or deployed in this checkpoint.

See the [V-Droid](42-vdroid-paper-review.md) and [AnyAppBench](43-anyappbench-paper-review.md) reviews for the research behind the proposed next experiments.
