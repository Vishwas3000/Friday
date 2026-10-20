# Nepal workflow: live failures, progress and timing

_2026-10-20 · measured failures and implemented fixes; end-to-end acceptance pending_

## Current result

**The Nepal Gemini → MakeMyTrip workflow is still pending.** A later combined run captured a complete 2,387-character Gemini answer, then failed destination verification in MakeMyTrip. A separate hotel continuation entered and read back the test stay correctly, but automatic result verification failed because the result header was offscreen. A manual diagnostic scroll exposed the matching header and a hotel card; that intervention does not establish an autonomous end-to-end pass.

A separate Andaman request also exposed a routing failure: Friday opened Chrome and returned a search acknowledgement without running the requested Gemini research or stay search. A quick acknowledgement is not evidence of completed trip planning.

This note records technical observations and aggregate timings. Private account details, full prompts, device identifiers and raw accessibility trees remain outside the repository documentation. Diagnostic artifacts stay under ignored `captures/`.

## Measured diagnostic attempts

These are different failing attempts and starting states. They are not a speed comparison or a successful end-to-end benchmark.

| Attempt | Measured duration | Observed outcome |
|---|---:|---|
| Unverified Send followed by a wait | Request: **79.647 s**; workflow: **79.641 s** | The research prompt remained unsent, but the workflow entered `gemini_waiting`. The owner stopped the attempt. |
| Ambiguous normal-chat selection | Request: **4.066 s** | A temporary Gemini chat was detected and refused. Submission stayed unverified. |
| Restart with an owned unsent prompt | Request: **7.872 s**; workflow: **7.860 s** | A normal new chat could not be verified. The state remained `gemini_submission_unverified`; the workflow did not wait for an answer. |
| Revised submission, complete Gemini capture, then hotel handoff | Request: **54.995 s**; workflow: **54.983 s** | Captured a complete 2,387-character answer through `full_semantic_textview`; hotel destination verification failed. Overall task stayed pending. |
| Separate hotel continuation | Request: **23.176 s**; workflow: **23.164 s** | Destination, 28–30 October 2026 dates and occupancy were read back correctly. Actual Kathmandu result cards loaded, but the offscreen header blocked automatic verification. |
| Andaman trip request under passive monitoring | Request: **0.165 s** | Routed to `web_search`, opened Chrome and returned a Google-search acknowledgement. Neither Gemini research nor stay search ran. |

### Where the 79.647 s attempt spent time

| Recorded workflow phase | Time |
|---|---:|
| Gemini open and screen qualification | 1.047 s |
| Submission callback, before actual-turn readback was enforced | 2.409 s |
| Wait and response-capture loop | **75.621 s** |
| Total workflow | 79.641 s |

The submission callback returned before proving that Gemini had added the question to the conversation. Most of the observed delay therefore came from waiting for an answer to an unsent question. This measurement does not establish that model inference or networking was slow.

Phase times need not sum exactly to the total. The total also includes task preparation, intermediate state writes and work between the measured phases. The request duration additionally includes the surrounding assistant work.

For the 7.872 s restart attempt, opening/qualification took 1.385 s and the submission callback took 5.939 s. The callback stopped at a verification failure. No answer-wait phase was entered.

Local evidence: `nepal-unsent-stopped-result.json`, `nepal-live-result-1.json`, `nepal-readback-result-1.json` and `nepal-readback-workflow-1.json` under ignored `captures/writer-tests/`. Mid-run state exports can contain only the phases completed at the time of export; completed request logs supply the terminal duration.

### Later combined run: research succeeded, hotel handoff remained pending

| Recorded workflow phase | Time |
|---|---:|
| Gemini open and screen qualification | 0.299 s |
| Normal-chat preparation, submission and readback | 9.238 s |
| Wait and complete-response capture | 23.579 s |
| Hotel handoff | 21.803 s |
| Total workflow | **54.983 s** |
| Entire assistant request | **54.995 s** |

This run advanced past the earlier unsent-question failure and captured a structured research proposal with the full semantic response. Its terminal stage was still `hotel_search_pending`, because Kathmandu was not verified as the selected MakeMyTrip destination. The measured research phase does not validate the hotel phase.

The separate 23.176 s hotel continuation spent 23.132 s in the hotel callback and recorded 23.164 s of workflow time. It reused retained research rather than repeating Gemini. It is therefore not comparable to the complete 54.995 s attempt as an end-to-end speedup.

Additional local evidence: `nepal-hotel-run-result.json`, `nepal-hotel-workflow-2.json`, `nepal-dates-latest-turn.json` and `mmt-kathmandu-header-after-scroll.json` under ignored `captures/writer-tests/`. The last file follows manual diagnostic intervention.

## Failure 1: “New chat” selected the wrong surface

### Observed pain point

Gemini exposes more than one control described as “New chat.” A toolbar description was insufficient to identify the intended normal conversation. One attempt reached a temporary-chat surface instead. The temporary-chat guard correctly refused submission, but the task still failed.

### Implemented correction

[GeminiChat](../android/app/src/main/java/dev/layatest/agent/GeminiChat.kt) uses the explicit sidebar text row beneath the observed sidebar container. It qualifies the Gemini host, opens the sidebar when needed, and selects that row through a bounded clickable ancestor.

A normal blank-chat check then requires:

- A qualified Gemini surface with the sidebar closed.
- The expected account-menu control, without opening or reading account details.
- No visible temporary-chat notice.
- No existing user or answer turns.
- One enabled editable Gemini input that is blank or contains its placeholder.

An exact prompt already owned by this research workflow can be recognized during recovery. An unrelated unsent draft blocks the restart. This exception does not authorize discarding arbitrary text.

### Remaining acceptance

The later combined run reached question submission and complete-answer capture. Recovery from other starting states still requires separate checks, including stopped attempts and unrelated drafts. Selector self-checks and a successful app launch alone do not prove every live transition.

## Failure 2: a click did not prove submission

### Observed pain point

The input contained the intended public question and a Send action was attempted, yet Gemini did not display a matching submitted user turn. The previous code advanced into answer waiting based on the attempted action. This produced the 75.621 s wasted wait.

### Implemented correction

[Assistant.submitResearch](../android/app/src/main/java/dev/layatest/agent/Assistant.kt) now checks for an app-owned `assistant_robin_user_message_text` node whose normalized text matches the exact authorized question. A generic action return value or a visible Send button is insufficient.

The revised path permits one bounded gesture fallback only after re-reading the screen and confirming all of the following:

- A matching submitted turn has not appeared.
- The focused editable field still contains the exact owned unsent prompt.
- The field belongs to the qualified Gemini host and remains visible.
- No authentication prompt or active-generation indication blocks the attempt.
- The public research question is still authorized by the retained workflow state.
- A fresh enabled Send target is available and passes the action guard.

The gesture uses the newly observed target. A matching user turn must then appear within the bounded readback period. The unchanged-field condition limits duplicate-submission risk; it is not a general retry permission for messaging, purchases or arbitrary app actions.

If readback fails, the stage remains `gemini_submission_unverified`. The workflow must not count that attempt as successful submission or response generation.

### Remaining acceptance

The later combined run passed submitted-question readback and collected a complete fresh semantic answer. The failure and one-retry branches still need repeatable regression coverage; a dispatched gesture or a shorter pending result alone remains insufficient.

## Failure 3: work appeared idle during waits

### Observed pain point

The status pill was hidden before each accessibility screen read. Repeated polling could leave no visible progress while the workflow waited, making an active attempt look stalled. The owner also needed an obvious Stop control during long phases.

### Implemented correction

[Overlay.Banner](../android/app/src/main/java/dev/layatest/agent/Overlay.kt) shows a native indeterminate spinner, current status, elapsed time and Stop. It reports no fabricated percentage. Stop changes the status to “Stopping…” while cancellation completes.

Read-only screen polling preserves the pill. Actual actions and screenshot-sensitive work temporarily hide it to avoid covered-touch filtering or contaminating image captures; the previous status resumes afterward. Timer callbacks stop on hide/removal, and permanent removal prevents queued status updates from resurrecting the pill.

The research workflow publishes these phases when entered:

1. Open Gemini.
2. Start a normal Gemini conversation and submit the question.
3. Wait for the complete answer.
4. Collect a complete semantic response or attempt whole-response copying.
5. Prepare the hotel search for verification.

The numbering indicates the current phase, not percentage complete. Missing stay inputs can still leave the hotel handoff pending at phase five. A copy attempt is not proof that full-response capture passed validation.

Completed Friday chat turns also show their recorded request duration. This makes previous attempts reviewable without interpreting the spinner as a completion signal.

## Failure 4: destination navigation and offscreen result context

The 54.995 s combined run reached MakeMyTrip but could not verify Kathmandu as the selected destination. A direct destination adapter was subsequently installed to handle the observed location field and matching city suggestion instead of relying on generic navigation alone.

The separate continuation then typed and read back Kathmandu, check-in/check-out of 28–30 October 2026, and the expected occupancy. It submitted the verified stay and loaded actual Kathmandu hotel cards. Automatic extraction still returned pending because the matching result header was outside the visible viewport. The answer's generic loading/network explanation did not identify this observed verification problem.

One manual upward scroll revealed the matching result header and a Kathmandu Royal Chamber card. This shows that result data existed after the search; it does not prove that the autonomous reader located and checked it. It also does not turn the earlier pending response into an autonomous success.

At the time of this update, the final header-recovery build was ready but **not installed**. The owner had begun other requests, so observation remained passive. The installed destination adapter and the uninstalled header recovery must not be described as the same tested build.

Acceptance requires bounded automatic recovery of the result header, verification against the retained destination/dates/occupancy, and collection of actual hotel cards on that same run. An unread header should be reported as unverified result context rather than assumed loading or network failure.

## Failure 5: a different destination bypassed the workflow

During the owner's own monitoring session, a request to plan an Andaman trip, research its suitable season and duration in Gemini, then search for a stay was handled as a plain web search. The recorded request took **165 ms**, routed to `web_search`, opened Chrome and returned a Google-search acknowledgement. No Gemini research or stay task ran.

The passive monitor recorded **20 samples and 6 state changes** over 10.036 s, with no monitor errors. Its `task_completed` status means the assistant request finished; the separate failure analysis correctly records that the requested objective failed at routing. Neither that monitor status nor the 165 ms duration is an application-level success metric.

The observed routing lost the compound task and its requested research app. The current Nepal-specific workflow is insufficient evidence that the same intent generalizes to another destination. This is a generalization and task-routing failure; adding a destination-named production branch would not establish general travel support.

Sanitized evidence is summarized from ignored `captures/state-monitor/20261019T210207Z/summary.json` and `failure-analysis.json`. Full request text and captured trees are not reproduced here.

### Generalization and retained-context regression cases

| Case | Required behavior |
|---|---|
| An unfamiliar destination with explicit Gemini research followed by stay search | Retain both requested phases and the chosen app; use a supported generic workflow or clearly report the unsupported scope before acting. |
| A destination supported by the current prototype with the same compound wording | Route to research and hotel preparation, rather than treating a browser launch as task completion. |
| A request for suitable season and number of days, without exact stay dates | Retain those research questions; ask for or confirm required booking dates and occupancy before searching. |
| A plain web-search request mentioning a destination | Preserve the ordinary web-search intent; mentioning travel alone must not create a hotel workflow. |
| A continuation after a complete research capture | Reuse that chat's verified source and supplied stay inputs; do not repeat Gemini unnecessarily. |
| A correction to dates, destination or occupancy | Invalidate dependent result verification and read back the corrected app fields. |
| A new trip request while an earlier trip is pending in the same chat | Distinguish the new objective from a continuation; do not silently attach it to the earlier destination. |
| A different Friday conversation | Keep research, pending prompts and stay details isolated from the previously selected chat. |
| Result cards visible while the header is offscreen | Recover the matching context within a bounded search or return an explicit verification limit; never infer the stay context from card names alone. |

These are acceptance cases, not claims that a generic planner or the header recovery has passed them.

## Timing contract

[ResearchWorkflow.run](../android/app/src/main/java/dev/layatest/agent/ResearchWorkflow.kt) uses `SystemClock.elapsedRealtime()` for durations. Wall-clock timestamps identify when an attempt began; wall-clock adjustments do not change the measured duration.

| Saved field | Measurement boundary |
|---|---|
| `last_run_timing.started_at` | Wall-clock start of this workflow invocation. |
| `gemini_open_ms` | Initial Gemini screen qualification, app opening when needed, and qualified-screen wait. |
| `gemini_submit_ms` | The entire submission callback, including normal-chat preparation and submission readback. |
| `gemini_wait_capture_ms` | Waiting for the fresh answer, stability checks and any complete-response collection attempt. |
| `hotel_handoff_ms` | The hotel helper callback, including its verification or pending outcome. |
| `last_run_timing.total_ms` | Active elapsed time for this invocation, recorded in `finally`. |
| `last_run_timing.stage` | The actual retained stage at exit, including pending/error paths. |
| `workflow_total_ms` | Sum of measured invocation durations across this retained workflow. |
| `workflow_run_count` | Number of recorded workflow invocations. |

Phase keys appear only when entered. The phase fields belong to `last_run_timing`; the cumulative total and run count belong to the workflow state. A resume can skip already completed research phases. The cumulative total measures active execution across invocations, not the time a saved task sat idle between requests. The latest invocation replaces `last_run_timing`; request traces retain earlier invocation measurements.

Each completed phase and the total are emitted through the existing trace callback. The outer `finally` records timing when execution stops, remains pending or throws. A timing write failure is logged without changing the task's outcome into a success.

The answer wait remains bounded at **300,000 ms**. The budget has not been shortened to make failed attempts appear fast. This is a maximum wait budget, not a prediction of normal response time.

## Next live acceptance evidence

The next autonomous run must separately establish all of the following. Some research and form-entry steps have passed in individual attempts; the whole chain has not passed together:

1. Normal Gemini conversation verified, with no unrelated draft discarded.
2. Exactly one matching public question appears as a submitted user turn.
3. A fresh complete answer for that question is captured, with its source and capture method retained.
4. The answer reaches the originating Friday conversation, and proposed dates remain unconfirmed until supplied or accepted by the owner.
5. Required hotel destination, date year, check-in, checkout and occupancy are read back from MakeMyTrip.
6. Matching live result context and actual hotel cards are collected before reporting search completion.
7. Phase durations, overall duration and any owner-wait time are reported with the observed terminal stage.

The current evidence covers a complete Gemini capture, independently verified hotel-form entry, loaded result cards, and the failures above. Manual scrolling was needed to expose the matching result header. It does not yet establish an autonomous end-to-end Nepal hotel search, a successful Andaman workflow, a reservation or a payment.

Related notes: [cross-app workflow scope](34-cross-app-reading-and-research.md), [hotel accessibility failures](32-hotel-device-walkthrough.md), [speed and accuracy backlog](29-task-completion-speed-and-accuracy.md).

## M1 Pixel Research trial: 20 October

The local Room coordinator build was installed on the Pixel 8. Its first 21 synthetic checks passed there; after the first capture fix, the expanded 22-check suite and focused selectors also passed. These checks used fake providers and are separate from the live outcomes below.

### Nepal question: complete response present, capture blocked

Friday prepared a normal Gemini chat and verified submission. Gemini produced a 3,511-character response with the correct unique marker. Capture stalled because the question node was removed from the offscreen accessibility tree and the renderer inserted U+200B before the marker. The task survived a Friday restart, then was recorded as cancelled. It was not resumed successfully, and no duplicate Send was used to override that cancellation.

The capture fix binds recovery to the persisted exact-prompt digest, completed submission attempt, task revision and cancellation epoch. Footer comparison tolerates limited edge formatting characters while preserving answer content. Missing, changed or stale submission proof cannot authorize capture or another Send.

### Mongolia question: measured false completion

The owner's subsequent group-trip request was monitored through the generic Research entry. Gemini received it in a normal new chat. The recorded timings were:

| Step | Duration |
| --- | ---: |
| Prepare normal chat | 6.960 s |
| Submit and verify question | 7.004 s |
| Wait and capture | 99.276 s |
| Publish | 0.294 s |
| Sum of recorded segments | 113.534 s |
| Plan-saved event to completion event | 113.578 s |

**This run failed acceptance.** Friday marked it complete and published 2,889 characters from the final response block. Earlier observed blocks already contained more than 4,000 characters. A footer in the last TextView did not prove that the preceding RecyclerView blocks had been collected. No stay app was searched, and no booking was made.

### Whole-response fix and remaining gate

The subsequent build requires a scoped Gemini Copy operation for the whole response, the exact unique final marker, and substantive anchor coverage across observed response blocks. Copying only the footer-bearing block must fail validation. Unsupported Copy controls return pending rather than publishing partial content.

An explicit result recheck preserves the saved question and completed submission, reruns capture/publication, and atomically replaces the prior Friday turn by its task/chat identity and old-answer hash. New publication keys distinguish corrected results; replay does not append another turn or repeat Send.

The build passes 24 synthetic coordinator checks and focused Gemini Copy fixtures on an isolated API 35 emulator. The Pixel disconnected before this final whole-response/recheck build could be installed. Its Copy controls, complete-answer coverage, correction of the Mongolia turn and fresh autonomous Research completion remain unverified on the Pixel. The M1 gate remains **not passed**. Raw captures remain in ignored local diagnostics.
