# AnyAppBench: what Friday should change in its evaluation

Checked **2026-10-20**, against the full paper body and appendices, the official project, and selected official harness definitions. No benchmark was executed, installed or trained for this review.

Primary links: [paper, version 1](https://arxiv.org/abs/2609.34139v1), [full HTML](https://arxiv.org/html/2609.34139v1), [30-page PDF](https://arxiv.org/pdf/2609.34139), [project](https://anyappbench.github.io/), [official repository](https://github.com/AnyAppBench/AnyAppBench-Code).

## 1. Verified paper findings

AnyAppBench fixes goals, parameters and success conditions across **520 task–app pairs, 52 apps and ten categories**, using 100 templates (§3). Programmatic durable-state, UI-state or hybrid verifiers determine outcomes; VLMs only diagnose failed runs (§3.1, Appendix B).

C1 executes raw instructions; C2 prepends instruction-only, app-independent subgoals (§3.3). Across eleven instruction-only agents, equal-app-weighted success falls from **38.7%** on original apps to **27.3%** on alternatives (§4.2). C2 changes success by **+3.2 points** in one five-category cohort and **−3.5** in the other, not a universal planner result (§4.4, Appendix F).

Failure labels distinguish planning, grounding, mixed, tooling, environment/evaluator and unknown. On **559** human-labeled episodes, Gemini's agreement is **45.1%** over six classes and **75.3%** after coarsening (Appendices C–D). The human set lacks completed double annotation; model training exposure is unknown, so this is not strict unseen-app evaluation (§6). Failure under both C1/C2 is inconclusive when the supplied decomposition may be wrong (§3.3). [Source: full paper](https://arxiv.org/html/2609.34139v1).

## 2. What this means for Friday

**Keep developing the persistent coordinator and independently test whether it helps.** Friday's problem includes selecting the right capability, retaining inputs, observing asynchronous completion, binding outputs to the originating chat, and recovering after interruption. A static list of steps and an operational coordinator are different experimental interventions. The current evidence does not justify removing planning, nor promising that planning alone will fix execution.

The calendar and CRED incidents expose a more immediate measurement failure: Friday returned **“Done” without the requested result**. Calendar correctness needs an actual saved event with the specified month/year and times. Bill retrieval needs visible, source-bound due amounts and declared coverage. A model's stopping token cannot establish either outcome.

We should record three distinct facts per run:

1. **What the owner requested:** immutable goal, exact values, allowed apps and completion condition.
2. **What Friday did and claimed:** proposed plan, actions, input transport, terminal state and answer.
3. **What an external check observed:** saved fields or visible source contents, before/after effects, and uncertainty.

The grader must not read `COMPLETED`, `verified=true` or the answer's confidence as proof. It can compare those claims with the independent result and report false completion.

## 3. Where the repository stands

These observations describe source inspected during this review; the parallel implementation is still changing.

| Current component | What it establishes | What it does not establish |
|---|---|---|
| [coordinator_test.py](../scripts/coordinator_test.py) | Invokes Android Room and selector self-checks, reporting their own declared scope. | Its passing count is not a count of real user goals completed in apps. |
| [TaskCoordinatorChecks.kt](../android/app/src/main/java/dev/layatest/agent/coordinator/TaskCoordinatorChecks.kt), C01–C35 | Fake-driver checks cover persistence, cancellation, publication, exact calendar fields, stale evidence, interrupted writes, dependency bindings, bill-total admission and text-generation failure. | Fake provider results cannot establish CalendarProvider permissions, real CRED observability, UI targeting or model accuracy. |
| [navigation_harness.py](../scripts/navigation_harness.py) | Separates synthetic/live sources and behavior versus goal success; checks raw app/provider observations, identities, exact field values, duplicates and manual intervention. | A grader implementation is not evidence that each oracle has been connected to a live capture. |
| [24-case navigation manifest](../evals/navigation/cases.json), revision `2026-10-20.1` | Provides explicit expected outputs, allowed packages, risks, effects and oracle kinds. | Its live runner currently admits only app opening/search. Calendar, bills, drafts, research and restart cases are fixture-only. |
| [Pixel acceptance record](37-pixel-research-live-acceptance.md) | Retains live passes, failures, assisted recovery and measured timings separately. | Successful selectors, captures or simulated tasks do not establish general task completion. |

The new harness already addresses several earlier reporting mistakes. Preserve that boundary: **an honest BLOCKED result can pass its behavior contract while the user's goal remains uncompleted**. A fixture self-check and a live acceptance run need separate counts throughout documentation and UI.

## 4. Useful official harness details to adopt

The official README describes shared semantic task IDs for parameter seeding and C2 plan lookup, immutable schedules, app/version/hash metadata, and an explicit infrastructure-invalid outcome. It also distinguishes the original evaluation goal from the augmented prompt goal, and rejects a C2 run with a missing required plan instead of quietly treating it as C1. Its runtime can stop on the first successful verifier result; strict final-state scoring is a separate switch. These are implementation details to inspect and pin before comparison, not proof that our harness behaves the same way. [Official README, sections 4–8](https://github.com/AnyAppBench/AnyAppBench-Code/blob/main/README.md).

Proposed Friday additions:

- Add `semantic_task_id`, `instance_seed`, `run_seed`, app package/version, APK hash, coordinator revision, model revision, oracle revision and observation method to every record.
- Derive task values from the semantic ID and seed before choosing an app. A January event must have identical title, date, timezone, duration and busy setting in every app variant.
- Store `owner_goal` separately from `execution_prompt`; adding subgoals must not change what the oracle grades.
- Freeze a run schedule before evaluation. Keep interrupted, excluded and rerun records rather than overwriting a failed run with a later success.
- Record whether scoring is final-state-only or whether an external observer can terminate execution early. Use the same rule in all compared arms; disclose observer assistance.

### Verified calendar identifiers for a future isolated test environment

The following identifiers were read from the official app catalog and profiles. The concrete package is more reliable than a display name such as “Calendar.” [App catalog](https://github.com/AnyAppBench/AnyAppBench-Code/blob/main/benchmark/app_generalization_apps.csv), [profiles](https://github.com/AnyAppBench/AnyAppBench-Code/blob/main/benchmark/app_generalization_profiles.py).

| Official app ID | Package | Proposed Friday use |
|---|---|---|
| `calendar_simple_calendar_pro` | `com.simplemobiletools.calendar.pro` | Reference calendar UI in the isolated emulator lane. |
| `calendar_etar` | `ws.xsoh.etar` | Alternative UI backed by a provider oracle. |
| `calendar_fossify_calendar` | `org.fossify.calendar` | Alternative UI; verify its actual storage/adapter path. |
| `calendar_calendar` | `com.vayunmathur.calendar` | A second different provider-backed interface. |
| `calendar_kashcal` | `org.onekash.kashcal` | Separate emulator adapter; its private database check is not a stock-Pixel permission grant. |

The catalog also lists Google Calendar, but the inspected profile has no implemented tasks for it because that emulator lacks writable provider calendar/account rows. Samsung Calendar is similarly excluded for its system-image dependency. Do not mistake catalog membership for runnable coverage. [Official profiles, calendar section](https://github.com/AnyAppBench/AnyAppBench-Code/blob/main/benchmark/app_generalization_profiles.py).

Concrete declared task IDs include `SimpleCalendarAddOneEventForEtar`, `SimpleCalendarAddRepeatingEventForEtar` and `SimpleCalendarEditEventForEtar`. The profile also declares `SimpleCalendarAddTimedEventForEtar`, `SimpleCalendarMoveEventForEtar`, `SimpleCalendarEventsOnDateForEtar` and `SimpleCalendarAddReminderForEtar` through its shared naming scheme. Audit registration and oracle behavior before scheduling these; this review did not run them. [Calendar implementation](https://github.com/AnyAppBench/AnyAppBench-Code/blob/main/benchmark/android_world/task_evals/single/app_generalization_generated/calendar_cross_app_tasks.py), [task profile construction](https://github.com/AnyAppBench/AnyAppBench-Code/blob/main/benchmark/app_generalization_profiles.py).

Our existing `calendar.jan7_all_day`, `calendar.jan7_timed`, `calendar.year_rollover`, `calendar.leap_day`, `calendar.missing_end`, `calendar.invalid_day` and `calendar.retry_same_event` should become a small reusable semantic set across the supported calendar apps. Add a controlled **wrong month with the correct day** mutation and a **permission denied with zero writes** case. These proposed extensions are Friday tests, not claims that the external benchmark already covers those exact requests.

**Separate API and UI tracks.** If Friday creates an event directly through CalendarProvider, changing the foreground calendar app does not measure its ability to navigate each UI. Report provider capability tests separately from UI-only actor tests. The external grader may inspect state in either track, while the executor's permitted capabilities remain explicit.

## 5. Diagnostics before blaming the model

Proposed record fields: `preflight_status`, `observation_status`, `dispatch_status`, `oracle_status`, `assistant_claim`, `goal_success`, `behavior_contract_pass`, `diagnosis`, `diagnosis_evidence`.

| Observation | Record and next action | Scoring |
|---|---|---|
| App has no requested function, or the selected fixture cannot be provisioned | `unsupported`; name the unavailable capability or fixture. | No task success. Report coverage separately; do not silently remove it from the product request denominator. |
| Required calendar permission or writable account is absent | `missing_permission` or `missing_provider`; retain the actual permission/provider check. | No write and no completion. A prompt or explanation may pass a behavior check. |
| CRED opens but Friday receives no readable app observation | `observation_unavailable`; retain package/window/readability evidence. | No retrieved bill or computed total. Do not infer a zero balance, a privacy policy explanation or an app defect from the empty tree alone. |
| Exact input is wrong before dispatch | `input_validation` or `planning`, supported by original input comparison. | Fail the appropriate contract without navigating further. |
| Correct target is chosen but the click/parser/command fails | `tool_execution`, with dispatch receipt and changed/unchanged observation. | Fail or reconcile; do not relabel as wrong planning without evidence. |
| Model selects the wrong candidate on a readable screen | `grounding`, supported by candidate ID/box and observed result. | Goal failure unless it subsequently recovers within the original budget. |
| Calendar event exists but the independent query is malformed or incomplete | `oracle_unavailable` or `suspected_oracle_error`; audit independently. | Unknown result, not an automatic model failure or success. |
| Friday says “Done” but the exact event/bill result is missing | `false_success` when the external failure is established. | Preserve the failed goal and the misleading claim as separate failures. |

Use automated failure diagnosis to organize reviews, not to rewrite success. For the first suite, have two people independently label a small stratified failure sample with the same complete evidence and hidden automated labels. Retain disagreements and `unknown`; measure agreement before using fine-grained label proportions to choose an architecture.

This is an **evaluation** proposal. It does not change Friday's accessibility declaration or permit reading private app databases on the owner's Pixel. Keep the current financial-app read-only policy. Benchmark finance fixtures do not demonstrate access to an owner's CRED account.

## 6. A controlled experiment for Friday

### Primary 2 × 2: system contribution versus model contribution

Use one frozen task set, independently graded for every arm. “Baseline” below means a pinned pre-coordinator execution path in a separate build; it does not remove safety checks or the external grader.

| | Current model stack | Stronger teacher/model stack |
|---|---|---|
| Pinned baseline execution | A: measures current actor/text decisions with the old control loop. | B: changes models while retaining the old control loop. |
| Persistent coordinator execution | C: changes orchestration while retaining the same model stack. | D: changes both; shows whether their interaction helps. |

Keep screen access, allowed tools, app versions, input instances, step/time budgets, memory settings and oracle identical within each matched pair. Declare the teacher/model identities and whether it receives images; otherwise “stronger model” also changes the available observation. Count any network/model load time in end-to-end latency.

Interpret the contrasts conservatively:

- C − A estimates the coordinator's contribution under the current model stack.
- B − A estimates the model-stack contribution under baseline execution.
- D − C estimates that contribution with the coordinator.
- `(D − C) − (B − A)` estimates the interaction on this frozen suite, with an uncertainty interval.

Do not use a coordinator's own verifier as the only external oracle: the same defect could manufacture a pass in C/D. Preserve raw observations for all four arms and grade them through a separately versioned harness.

### Secondary: isolate static decomposition from adaptive planning

Within a fixed stack/control loop, compare:

1. Raw instruction.
2. One frozen, app-independent decomposition generated from that instruction alone.
3. Persisted plan with screen-aware revision and verified fact transfer.

Use the same exact decomposition for every app of the same task instance. Freeze it before execution. If it fails validation or is missing, record an invalid test setup rather than silently substituting the raw-instruction condition. Grade the untouched original request. This comparison distinguishes whether an error is helped by a better plan, better grounding, or runtime recovery; a failure in one arm does not identify its cause by itself.

### First schedule and metric definitions

Propose **four goal families × three apps × five instances × three seeds = 180 task runs per arm**, or 720 for the primary 2 × 2. Families: create a calendar block, create a note, find a place, and inspect a seeded expense summary. Use isolated data and no real sends/payments. This is a proposed workload, not an ETA or a set of runs already completed.

Maintain two denominators:

- **Product fulfillment:** independently verified, unassisted goals completed ÷ all scheduled requests, including unsupported/unavailable results. This matches the user's experience and the harness's full-suite reporting intent.
- **Controlled valid-run success:** verified goals completed ÷ runs with valid initialization and an available external oracle. Report every exclusion and its cause; do not merge this number with product fulfillment.

Also report success per app, an equal-app macro average, the paired reference-to-alternative difference, and application variability. Include p50/p95 wall time, known/unknown timing coverage, wrong-app rate, actual duplicate writes, cancellation behavior, manual interventions, false-success claims and unverified claims. Keep synthetic and live sources separate. Negative fixture cases contribute to behavior-contract metrics, never inflate completed user goals.

Use paired outcomes for matched model/coordinator comparisons, with confidence intervals grouped by task and app. Keep first-attempt and retry-inclusive results distinct. Do not turn the harness's current last-record-per-case summary into a best-of-retries success rate. A second run after a fix belongs to a new build/revision with its own record.

## 7. Next implementation steps

1. Preserve the 35 fake-driver checks as component checks; keep their labels explicit.
2. Finish independent live captures for CalendarProvider and readable bill fixtures before expanding the suite's auto-run scope.
3. Add semantic IDs, shared instances, versions and immutable run IDs to the navigation manifest/records.
4. Add cross-app calendar variants in an isolated emulator environment; verify each adapter and distinguish provider execution from UI execution.
5. Freeze the primary 2 × 2 before changing teachers or collecting new training data. Keep final app/task families out of tuning.
6. Review failures with source observations and independent labels. Train on a separate development set; preserve the final suite for acceptance.

The useful change is measurable: Friday should finish more requested goals, preserve exact values, and stop making unsupported completion claims. More component passes alone will not establish that improvement.
