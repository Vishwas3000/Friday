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

## Remaining acceptance work

1. Expand controlled collectors for actual readable bill fixtures, text generation, draft transport, cancellation and interrupted saves. The fixture manifest alone is not device coverage.
2. Measure end-to-end and per-stage latency with explicit cold/warm state, retries and interventions. Existing unknown model/action/wait components must not be guessed.
3. Complete clean Research restart/conversation recovery and content-constraint acceptance; the earlier M1 gate remains unpassed. The Nepal hotel workflow remains unverified end to end.
4. Compare the same tasks with runtime held fixed and stronger teachers held fixed, following the [architecture plan](38-navigation-architecture-and-harness-plan.md), before training on the offered GPU. No new teacher or student has been trained or deployed in this checkpoint.

See the [V-Droid](42-vdroid-paper-review.md) and [AnyAppBench](43-anyappbench-paper-review.md) reviews for the research behind the proposed next experiments.
