# 29 — Completing real tasks: the MakeMyTrip hotel diagnostic

_2026-10-20 · Friday on the connected Pixel 8 · Trust tags: [measured] observed on this device · [code] read in the current repository · [proposed] implementation work, not built or validated · [unknown] not established_

The owner asked Friday to search for a hotel near Koramangala in MakeMyTrip on 28 October.
The diagnostic shows failures before and during app control: app routing, date understanding, maintaining the task across follow-ups, selecting fields, detecting progress, and confirming completion.
The first improvement should be a reliable, checked sequence for the whole task. Making a wrong action faster would still leave the search unfinished.

This note records the observations, pain points at every stage, concrete changes, and future checks. It makes no implementation changes.
Raw screenshots and bridge results stay in the ignored `captures/hotel-diagnostic/` directory. This note omits unrelated screen contents and private request history.

## 1. What is and is not complete

- [measured] The original natural request went to Google search in Chrome instead of MakeMyTrip.
- [measured] Rephrasing it as “open MakeMyTrip and search…” selected the right app, but the run became stuck after seven model steps.
- [measured] Guided instructions reached Hotels and selected Koramangala as the location.
- [measured] A locality-only follow-up finished successfully. Its “Done” describes that subtask.
- [measured] At that checkpoint the dates were unchanged. The original hotel search was still incomplete.
- [measured] Continuing to the check-in picker exposed an exact 28 October 2026 day node in the raw accessibility tree; the candidate builder omitted it. A subsequent guided tap through Friday selected that day: the raw description became “28 OCT 2026 Selected” and the header changed to “Select Check-Out Date”. This was operator-guided progress, not autonomous task completion.
- [unknown] Check-out date, rooms, adults, children, and any intended defaults were absent from the original request. A chosen app default is not evidence of the owner's intent.

**Completion rule for this task:** the correct app shows hotel results for the resolved locality, requested check-in, confirmed check-out and occupancy. A locality selected in a form, an open calendar, a tap on Search, or a model DONE answer does not establish that.

**Live continuation:** the operator is continuing the date and search investigation. Later verified observations should be added to this note; do not turn the locality success into a whole-task success.

## 2. What actually happened

| Stage | Observation [measured] | What it establishes |
|---|---|---|
| Original wording | The task card chose `web_search`; no app was resolved; Chrome opened. | The original request failed before UI planning. |
| Explicit app wording | `open_app`, `com.makemytrip`; the query was “a hotel near Koramangala”; `range` was “October”. | App launch worked; there was no structured 28 October check-in. |
| First model decision | WAIT on a screen with zero candidates, measured as 9,006 ms in the Laya timing bucket. | There was a startup/readiness cost; its components were not separately timed. |
| App navigation | The run chose Clear text, Search, Hotels, then Search three times. | A category choice in the trace did not establish that the expected form was reached. |
| No progress | The final three actions were Search; the loop guard stopped the run. | Recovery happened after spending three more model passes on the repeated action. |
| Guided Hotels tap | The operator's more specific follow-up reached the hotel form. | A useful element could be targeted with a narrower instruction. |
| Guided field tap | A country word in the label fuzzily matched an unrelated installed app; follow-up interpretation was bypassed and the request rerouted. | App-name correction can override a valid instruction about the current screen. |
| Retried field tap | Removing the country word allowed the intended local interaction. | The failure was sensitive to request parsing, not just the UI model. |
| Locality entry | Friday typed Koramangala and selected its suggestion; the form's locality value was checked afterward. | This field was filled successfully. Other fields still needed checking. |
| Check-in calendar | The raw tree exposed “28 OCT 2026 Tap to select”; the day had no actionable flags and was omitted from the 12-candidate model view. | The required date was visible to the service but unavailable to the model as a target. |

The installed model file's SHA-256 matched the S11-A English vocabulary cut:
`8235ea116bc39a28630509697de87dc2be219c113385460efaa595831172b398` [measured].
The generic on-device filename is not a model-version identifier; retain the hash with each benchmark.
This observation means the run used the student candidate; it does not prove the student caused the failure.

### Timing of the failed explicit-app run

| Measurement | Value | Interpretation |
|---|---:|---|
| Friday's logged request duration | 47,559 ms | Failed attempt, not time to complete the hotel task. |
| Host bridge elapsed time | about 47.8 s | Includes transport/host overhead. |
| Laya timing buckets, seven steps | 36,784 ms, about 77% of request time | Includes model access on first use; not a clean inference-only measurement. |
| Screen reads, seven steps | 2,809 ms, about 6% | Includes reported settling; screen reads are not the dominant measured bucket. |
| Other request time | 7,966 ms, about 17% | Launch, action, explicit sleeps, and unseparated overhead. |
| Total reported model tokens | 8,181 | Sum across steps and the operation/target rows, not one prompt length. |
| Final three Search decisions | 16,212 ms | About 34% of the entire failed attempt spent on one repeated choice. |

The successful locality-only follow-up took 6,031 ms: three model steps, 1,814 ms of Laya buckets, 2,641 ms of screen reads, and 1,119 reported tokens [measured].
Its operation confidence for DONE was 0.38; earlier wrong/repeated actions had higher confidence.
Confidence alone cannot serve as a success check.

These runs have different starting screens, goals, and model warmth. **6 seconds versus 48 seconds is not a measured whole-task speedup.**
Separating model load, warm-up, tokenization, operation inference, target inference, actuation, and settling is necessary before attributing the first 9-second bucket.

## 3. Pain points and changes at every stage

| Stage | Current pain point | Smallest useful improvement [proposed] | Evidence to record |
|---|---|---|---|
| Receive the request | Original spelling and wording affect routing and place matching. | Preserve the original request; normalize app aliases and date spellings in parsed fields. | Original/normalized parse, device time and timezone. |
| Resolve the app | “make my trip” does not resolve to the installed label “MakeMyTrip”. | Add known app aliases and match explicit app phrases before skill routing. | Matched phrase, method, package, alternative candidates. |
| Choose the task | “search” chooses a web skill even when an app is explicitly requested. | Route an explicit app search to that app's task executor. | Expected versus chosen skill/app pair. |
| Extract fields | Query contains “a hotel near…”; location and check-in have no dedicated fields. | Parse hotel location, check-in, check-out and occupancy once into the task card. | Field values, where each came from, missing fields. |
| Resolve dates | `28 October` becomes a broad historical range; `oct` is not a supported full month name. | Use a future stay-date parser separate from spending date ranges. | ISO date, year decision, timezone, invalid-date reason. |
| Collect missing input | No explicit state says a hotel stay still needs check-out and guests. | Ask one grouped clarification for missing stay fields; show proposed defaults before using them. | Supplied versus defaulted versus unanswered values. |
| Build the plan | One long goal drives local taps without a field checklist. | Use short verified subgoals: Hotels → locality → dates → occupancy → results. | Current subgoal and remaining fields. |
| Start/resume the app | Fresh opens and follow-up reopen/back logic can lose progress. | Resume a pending form; restart only when the live state cannot satisfy the next subgoal. | Launch mode, prior and resulting screen identity. |
| Wait for readiness | The first pass saw zero candidates; WAIT adds a fixed 2 seconds. | Wait for a known usable field or a loading-state change before invoking the model. | Readiness predicate, wait time, timeout cause. |
| Read the screen | Good labels can hide useful resource-id semantics. | Keep compact field context, such as `check_in_date`, beside the visible value. | Candidate role/id/label and resulting token cost. |
| Pick the target | Large app screens used 45 candidates and about 1,500 reported tokens per targeted step. | Search within the current subgoal's fields; use exact semantic matches when unique. | Candidate count, shortlist coverage, target confidence. |
| Type a value | Every INPUT_TEXT in a run receives one `typeText`; typing follows existing focus. | Bind each field to its own value; resolve, focus, type and read it back. | Intended field, focused field, text-action result, actual value. |
| Operate dates | Calendar day nodes with exact date descriptions are dropped; day numbers also repeat across months. | Expose semantic day nodes, then identify mode, month/year and enabled day before tapping. | Target recall, month/year, day eligibility, selected range after each tap. |
| Confirm an action | Actions are recorded without propagating click/type success. | Return action status, then check the expected change on the live screen. | Target found, API result, gesture fallback, state delta. |
| Detect no progress | Three identical action-history strings trigger STUCK after the extra work. | Stop after an unchanged expected state; retry once with new grounding, then clarify. | Before/after screen and field states, retry reason. |
| Handle correction | App typo matching can discard a valid current-screen follow-up. | Give explicit local corrections priority within the active app; require stronger evidence to switch apps. | Follow-up type, app-switch source, updated pending field. |
| Decide completion | Label overlap, remembered screen words, and model DONE are weaker than a filled form. | Require task-specific field and results checks. | Each completion predicate and observed value. |
| Collect results | Reaching a result list does not yet answer which hotels are available or what they cost. | Extract a small set of hotel cards with their stated price basis and relevant facts. | Name, location/distance, availability, quoted price, taxes/fees basis and source screen. |
| Answer the owner | A subtask says Done while the broader request remains open. | Report completed fields and the next missing item; reserve task completion for verified results. | Parent task status and user-facing summary. |
| Remember/train | A successful local correction can look like success of the entire request. | Keep subtask outcome and parent outcome separate; save only verified actions as positives. | Outcome scope, correction pair, app version and model hash. |

## 4. Understanding the request once

### 4.1 App selection and routing

[code] [`AppResolver.named` and `typo`](../android/app/src/main/java/dev/layatest/agent/AppResolver.kt) match aliases, whole launcher labels, then near-spellings of words throughout the request.
The alias table is small. A plain “search…” request matches `web_search` in [`ruleSkill`](../android/app/src/main/java/dev/layatest/agent/Skills.kt), whose implementation explicitly launches Chrome in [`WebSearch.run`](../android/app/src/main/java/dev/layatest/agent/PeopleSkills.kt).
`Assistant.appFor` can also reject a named app outside a skill's catalog unless the request begins by opening that app.

[proposed] Resolve explicit app context first. An app phrase after “in”, “on” or “using”, and an “open X and…” prefix, should resolve to a package plus the remaining task.
Start with a MakeMyTrip alias and shared handling for explicit app search; extend from observed examples rather than expanding lists of incidental words.
Record whether the match was an exact label, explicit alias, or fuzzy candidate. A fuzzy content word should not silently select a different app.

### 4.2 Stay dates and missing values

[code] [`Slots.parseRange`](../android/app/src/main/java/dev/layatest/agent/Slots.kt) was written for historical queries such as spending last month.
It recognizes full month names and chooses their most recent occurrence. Its output is a `TimeRange`, and `TaskCard.range` keeps the label, not a check-in/check-out pair.
Reusing that meaning for travel would produce incorrect future-date behavior.

[proposed] Add stay dates where needed: `checkIn`, `checkOut`, rooms, adults and child ages/count, with missing values preserved as missing.
Parse `28 Oct`, `28th October`, `October 28`, explicit years, tomorrow, and a stated range using device time.
Validate real calendar dates and require check-out after check-in. For an omitted year, make the future-date rule explicit and confirm when ambiguous.
Do not interpret a month-only historical range as a hotel check-in.

For the owner's original wording, a reasonable parsed state is: app MakeMyTrip, category hotels, locality Koramangala, check-in 28 October with year resolved from the device clock, check-out missing, occupancy missing.
One grouped follow-up can collect the missing fields. If the owner elects to accept app defaults, record and repeat those defaults.

### 4.3 Make the card drive execution

[code] [`TaskCard` and `understand`](../android/app/src/main/java/dev/layatest/agent/Card.kt) exist; several skills still parse the request again.
[`GenericSkill.run`](../android/app/src/main/java/dev/layatest/agent/Skills.kt) builds its own text guess and passes one payload into a UI run.
[`Message.run`](../android/app/src/main/java/dev/layatest/agent/PeopleSkills.kt) already splits contact search and message typing because one run types the same payload at every INPUT_TEXT.

[proposed] Finish the relevant task-card migration and use one field value per subgoal.
An app search needs a structured filter set; a single string cannot represent a locality, two dates and occupancy reliably.
Reuse existing `runLaya`, intents, routes and checks. A new general agent framework is not needed to implement this flow.

## 5. Navigating and filling the form

### 5.1 Prefer deterministic actions when the screen is clear

[code] `ScreenCandidate` retains resource ids for replay, but `toCandidate` drops them before model input.
[`buildCandidates`](../android/app/src/main/java/dev/layatest/agent/Candidates.kt) uses id-derived words only when the visible label is empty.
Thus fields with date values can lose useful distinctions such as check-in versus check-out in the model's view.
The captured hotel form exposes useful field ids; the current pipeline already has access to them.

[proposed] Match a unique, enabled field using package, resource id, visible role, and current form state.
Use the model when that match is absent or ambiguous. Include a compact semantic field name in model labels when it adds information.
Keep the Kotlin and Python candidate/input formats aligned; measure accuracy after format changes because the model was trained on a specific representation.
Do not restore every raw id or add the whole tree to every prompt.

### 5.2 Resolve the intended field before typing

[code] [`AgentService.setText`](../android/app/src/main/java/dev/layatest/agent/AgentService.kt) writes into `FOCUS_INPUT`; the chosen target supplies its label but does not itself choose the input node.
It automatically submits fields recognized as search boxes.
[`AgentService.act`](../android/app/src/main/java/dev/layatest/agent/AgentService.kt) returns no result to `Assistant.perform`, which records the action regardless of underlying success.
These are risks found in code; the locality typing observed here did succeed.

[proposed] Resolve a live target, focus it, set its specific value, and check the field's value before advancing.
Distinguish “type into the suggestion field” from “submit a search”. A hotel lookup can update suggestions without needing an IME search submission.
Propagate missing focus, rejected set-text, failed click and cancelled gesture as action results.
Keep the existing accessibility click-first path and gesture fallback; record which actually worked.

### 5.3 Treat the calendar as a small stateful form

[measured/code] The check-in picker for October 2026 exposed a raw node with content description `28 OCT 2026 Tap to select`, class `c`, visible and enabled, at bounds `[308,1442,462,1599]`.
It was neither clickable nor checkable. The normal candidate loop therefore discarded it.
The fallback loose-text loop also discarded it because its box was inside the actionable `rvCalendarMonth` ancestor.
That ancestor became one long concatenated label, truncated before the needed day. Only 12 candidates remained.
This is a concrete candidate-recall failure: a different model cannot choose a day absent from its target list.

[proposed] Expose enabled semantic date descendants as separate targets when their descriptions identify a complete date, even when an ancestor owns the click.
Resolve their live bounds and use the existing gesture fallback if no node-level action works.
Keep month headers, disabled days and repeated off-month numbers distinct. Avoid adding every passive text descendant across all apps.
The raw description and bounds already support grounding this day; OCR is unnecessary for this captured calendar.

[proposed] A stay-date subgoal needs the following state:

1. The active field is check-in or check-out.
2. The picker shows a known month and year; scrolling direction is correct.
3. The target day belongs to that month and is enabled.
4. Selecting check-in changes the range start as intended.
5. Selecting check-out changes the range end and satisfies the stay length.
6. The picker confirmation returns both expected dates to the hotel form.

Use full accessibility date descriptions if exposed, otherwise month headings plus day position and container context.
If the tree cannot distinguish dates, inspect the screenshot as a fallback; adding a vision model is a later option requiring measurement.
Do not select the first node labelled “28” on a two-month calendar.
If the app resets dates after changing locality, re-check them and correct the fields before searching.

## 6. Progress, corrections, and verified completion

[code] [`Assistant.steps`](../android/app/src/main/java/dev/layatest/agent/Assistant.kt) stops after three identical recent action strings.
It also accepts model DONE, selected-label overlap, a tap whose words match the subject, and similarity to a remembered end screen.
[`replay`](../android/app/src/main/java/dev/layatest/agent/Assistant.kt) returns DONE after replaying steps without a task-specific end check.
[`Routes.screenWords`](../android/app/src/main/java/dev/layatest/agent/Routes.kt) drops words containing digits, so its fingerprints deliberately do not verify dates or counts.

[proposed] Reuse the live screen read to check the expected postcondition after each action.
For a tap on Hotels, expect the location/date/guest form. For locality, expect the chosen canonical locality in its field.
For dates, expect both ISO dates derived from the visible values. For Search, expect a results screen with those parameters or an explicit no-results state.
An API accepting a click is evidence of input delivery; it is not evidence that the task progressed.

[proposed] After the search is verified, collect a small bounded list of hotel cards and report what the screen actually supports: hotel name, locality/distance, rating if shown, availability, and price with its unit and taxes/fees wording.
Keep each fact attached to the same hotel card; do not pair one property's price with another property's name.
Distinguish a per-night starting rate from the total stay cost and do not infer cancellation terms or availability from an advertisement.
Read further only as needed to provide useful choices. An empty or partially loaded list should produce an accurate status, with the requested search parameters retained.
These hotel result extractors are proposed; the current diagnostic has not reached or collected verified hotel offers.

If the expected change is absent after the normal settling budget, re-read and retry once with a fresh target.
An unchanged form should not trigger the same expensive model decision three more times.
Record screen/field deltas; avoid comparing a whole screenshot hash that changes with every banner or clock tick.

[code] [`Conversation.read`](../android/app/src/main/java/dev/layatest/agent/Conversation.kt) asks the app resolver before interpreting “tap…”, “search…”, or a correction.
It can treat a fuzzy word as another app and decline follow-up handling. A local `Next` subgoal also gets its own goal/result.
The session model has a single goal, a finished Boolean and one typed payload; it does not track completed and pending hotel fields.

[proposed] Carry an active task with completed fields, pending fields and current subgoal through corrections.
“No, 28 October”, “two adults”, and “change the check-out to the 30th” should update fields and resume from the current form.
An explicit instruction to open another app can switch tasks; an incidental country or place word cannot justify the switch alone.
After selecting locality, say that locality is selected and dates remain pending. Preserve the original hotel goal until checked results exist or the owner ends it.

## 7. Speed work, in the order supported by this run

| Priority | Change [proposed] | Why this run supports it | How to evaluate it |
|---|---|---|---|
| 1 | Prevent wrong routing and missing fields. | Wrong app and incomplete dates make successful completion impossible. | Exact app/field agreement before any action. |
| 2 | Stop repeated actions when the expected state does not change. | Three Search decisions cost 16.2 seconds. | Repeated-action count and time spent without progress. |
| 3 | Use short field subgoals and unique semantic targets. | Narrow locality work succeeded with much smaller inputs. | Whole-task success, model calls and token totals from matched starts. |
| 4 | Replay only verified task fragments with live pre/post checks. | Routes already exist and can avoid model calls. | Warm repeated task versus unseen locality/dates, including stale routes. |
| 5 | Wait for usable UI before invoking the model. | The first pass saw no candidates; WAIT itself sleeps 2 seconds. | Empty-screen calls, readiness latency, timeout correctness. |
| 6 | Cache tokenization for unchanged labels/history where practical. | The input builder repeatedly encodes overlapping screen context. | Measure tokenization separately first; do not assume a large gain. |
| 7 | Tune model warmth and release using current measurements. | Prewarming and a three-minute warm window already exist. | Cold/warm end-to-end latency, memory and battery under the same task. |
| 8 | Optimize remaining model/runtime cost after task correctness. | Laya buckets are 77% of this failed run. | Success-constrained p50/p95 time, sustained thermal behavior. |

[code] [`LayaEngine.step`](../android/app/src/main/java/dev/layatest/agent/LayaEngine.kt) already runs operation first and skips the target row for untargeted actions.
The app already uses ONNX Runtime, a shared warm engine, startup prewarming, and a 32-target shortlist.
S11-A and the English vocabulary cut are already present on the diagnostic device; do not count adopting them as future work for this baseline.

[`27-laya-at-scale.md`](27-laya-at-scale.md) found that a 16-target shortlist hid gold targets; simply shrinking back to 16 is not a supported accuracy improvement.
Try better semantic grouping and field context before further truncation.
[`28-laya-student.md`](28-laya-student.md) reports about 60% Policy Joint accuracy on held-out AndroidControl steps; it does not establish full hotel-task success.
Replacing the model is an experiment after separating understanding, grounding and completion failures.

### Optional bounded text generation

The owner also asked about an inexpensive small language model for short text [proposed evaluation].
The existing Writer design in [`13-text-llm.md`](13-text-llm.md) is a natural integration point for short text. [`31-slm-text-generation.md`](31-slm-text-generation.md) scopes the candidate comparison and 16 text-quality cases. A bounded, schema-constrained parse is a proposed extension beyond the existing text-only Writer scope; compare it with deterministic rules before adopting it. Short clarifications/answers should use verified state.
Require outputs to name the requested app, quote supported request spans, preserve unknown fields and normalize dates through deterministic validation.
Supply a small amount of current task context and a strict output limit. Invoke it only when a field needs language understanding; measure startup and model switching with Laya.
Evaluate the cheapest model that meets slot exactness, valid JSON, abstention and end-to-end latency requirements on this repository's requests.
No specific model, price, speed or accuracy is endorsed by this diagnostic. A language parser cannot repair omitted calendar targets or verify an unchanged form without the executor changes above.

## 8. Learning from these failures

[proposed] Capture corrections as explicit before/after training examples: wrong route → correct app task; mistaken target → selected hotel field; wrong date → correct range.
Use public synthetic stay requests and sanitized screen fixtures for committed data. Keep real screens, typed personal data and private task rows in ignored captures.

Label outcomes at the right scope:

- A locality change can be a verified positive for that field.
- The seven-step failed attempt is a failed parent task; do not label its every action correct or incorrect indiscriminately.
- A failed action with a known corrected target can supply a preference or supervised correction.
- A model DONE before valid dates/results is a stopping failure even when earlier navigation was correct.
- A replay that worked for one stay still needs parameter substitution and final verification for another stay.

Use app version, model hash, input format, route source and screen state with each example.
Separate held-out localities, dates and app versions from training so memorizing one successful hotel form does not inflate evaluation.
Benchmark new model training against the same task executor; otherwise executor changes and model gains cannot be attributed.

## 9. Actionable backlog

| ID | Order | Work | Existing code to change/reuse | Acceptance criterion [proposed] |
|---|---|---|---|---|
| H01 | P0 | Resolve MakeMyTrip aliases and explicit app search before web routing. | `AppResolver.named`, `Skills.ruleSkill`, `Assistant.appFor`, `WebSearch` | Natural and “open X and…” forms choose `com.makemytrip`; ordinary web search still chooses Chrome. |
| H02 | P0 | Parse and collect stay fields once. | `TaskCard`, `understand`, `Slots`; task-card migration in research/23 | Check-in is a complete date; missing check-out/occupancy are collected or explicitly defaulted. |
| H03 | P0 | Execute a short hotel form plan using field-specific payloads. | `GenericSkill`, `SkillContext.runLaya`, existing skill patterns | All requested fields match on the pre-search form. |
| H04 | P0 | Return action outcomes and read back field changes. | `AgentService.act`, `setText`, `Assistant.perform/record` | Rejected typing/clicks cannot be recorded as successful progress. |
| H05 | P0 | Require hotel results checks, collect a bounded hotel summary, and preserve parent task status. | `Assistant.steps/finish`, `Conversation.Session`, `Routes.replay`, `screenTexts` | Wrong dates/unchanged forms never pass; reported hotels and prices come from the matching verified result cards. |
| H06 | P0 | Detect lack of progress and make one grounded recovery. | `Assistant.steps`, live state and existing loop guard | No three repeated Search model decisions on an unchanged form. |
| H07 | P0 | Restore semantic calendar day targets; include compact field context where needed. | `Candidates`, `LayaInput`, Python equivalents | “28 OCT 2026 Tap to select” is targetable; check-in/out and disabled/off-month days remain distinguishable. |
| H08 | P1 | Interpret local corrections before weak fuzzy app matches. | `Conversation.read`, `AppResolver.typo`, `Session` | Label words do not switch apps; corrected date/guests update the active stay. |
| H09 | P1 | Record separate timing buckets and failure reasons. | `Assistant.trace`, `LayaEngine`, `AgentService`, `requests_log.py` | Cold load, inference, actions, waits and completion-check costs are independently visible. |
| H10 | P1 | Save verified parameterized fragments, with checks on replay. | `Routes`, Teach/run rows, `Assistant.keep/reject` | A second locality/date task works without replaying stale values or skipping checks. |
| H11 | P2 | Train on corrected tasks and evaluate generalization. | Existing distillation/Teach tooling, research/22 and 28 | Higher complete-task success on held-out tasks; no regression hidden by step metrics. |
| H12 | P2 | Evaluate bounded language parsing and short answers when rules fail. | Writer plan in research/13, `understand`, current task state | Exact slots, explicit unknowns and short grounded answers at measured cost/latency. |

P0 items are one connected reliability fix. An alias-only patch would get the owner into MakeMyTrip while leaving dates, repeated taps and success reporting unresolved.
Implement and measure these changes in small commits so regressions can be traced to a specific stage.

## 10. Future validation

The owner explicitly asked for device operation and diagnosis. This note proposes subsequent implementation checks; it does not claim those changes have been built or tested.
Reuse [`scripts/task_suite.py`](../scripts/task_suite.py) and its ignored private task file rather than creating a second benchmark runner.
Extend the checks for structured field values and parent-task state; the present package/text/selected-tab checks are insufficient for a stay search.

| Test family | Cases to include | Required result |
|---|---|---|
| Request wording | “MakeMyTrip”, “make my trip”, explicit app after query, “open X and…”, spelling correction | Same intended app and structured fields. |
| Dates | Abbreviated/full month, ordinal day, explicit year, year boundary, invalid date, reversed range | Correct stay dates or a useful clarification; never silent substitution. |
| Missing values | Check-in only, dates only, occupancy omitted, children without ages | Ask once for required missing fields and retain answered values. |
| Start state | Home, locality picker, date picker, results, stale prior stay, app resumed after Friday reply | Continue from the correct state; no unnecessary reset or lost fields. |
| UI change | Passive day nodes inside actionable calendars, duplicate Search buttons/day numbers, moved field, slow loading, empty tree | Required date survives candidate building; correct grounding or an explicit recoverable failure. |
| Corrections | “not that locality”, “28 October”, “two adults”, “change checkout”, “back” | Update the requested field while retaining the other verified fields. |
| No progress | Click accepted but no transition, failed focus, loading timeout, keyboard overlay | Detect and recover once; avoid repeated inference on the same failed action. |
| Completion | Wrong locality, wrong date, default occupancy, no results, results from a stale search | Pass only when the requested parameters and resulting screen are verified. |
| Result collection | Repeated hotel names, sponsored cards, per-night/total prices, taxes extra, stale/partially loaded offers | Report correctly paired facts with price basis; retain uncertainty where the screen omits details. |
| Memory | No memory, verified replay, missing target, changed app version, changed dates | Accurate task and field checks in every mode. |
| Performance | Cold/warm model; cool/hot device; first and repeated task | Report success with p50/p95 latency, model calls, tokens and clarification count. |

Measure complete-task success, field accuracy, false completion rate, human corrections, calls without progress and elapsed time together.
Record failed and timed-out attempts in the denominator. Use matched starting states and fixed date/time fixtures for parser checks.
For live performance comparisons, log actual device time, temperature/thermal status, app/model versions and model warmth.
External-app loading time and network errors should be visible separately; they still affect what the owner experiences.

## Evidence limits

- This is one live hotel task with guided follow-ups, not a representative success-rate benchmark.
- The guided locality result verifies one field. A separate guided calendar tap then verified the check-in; checkout, occupancy and hotel results remain unverified. See the [device walkthrough](32-hotel-device-walkthrough.md).
- First-use load and warm-up were not isolated in the failed run's Laya timing bucket.
- Code findings identify mechanisms and risks; they do not prove the exact cause of each visually unchanged tap.
- App intents/deep links, calendar behavior beyond the captured state, and all proposed changes need separate verification.
- No implementation files or model artifacts were changed for this note; no new automated tests were added or run.
