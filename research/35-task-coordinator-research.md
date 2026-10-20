# Task coordinator research and proposed Friday redesign

_2026-10-20 · primary research and proposed architecture · implementation and live acceptance remain separate_

The proposed redesign adds a durable task coordinator above Friday's models and app adapters. It would replace the current orchestration loop with a Kotlin coordinator that owns task state, dependencies, missing inputs, execution, verification and recovery, while keeping Laya, Writer and app adapters replaceable behind explicit capability contracts. A capable planner can propose the next subgoal; the coordinator must decide whether its inputs and permissions are sufficient and whether the app actually reached the expected state.

**Latest sequence:** the owner subsequently tested an Andaman request, identified generalization failures and requested a proper system plan before further implementation. Follow the [coordinator implementation plan](../docs/COORDINATOR_PLAN.md). The Nepal end-to-end test remains open. M1 generic Research now has a Room coordinator and passing synthetic Android checks, with a partial-response false completion found on the Pixel and the final whole-response fix awaiting a live rerun; general composition and the remaining capability migrations below are still planned.

The installed bounded prototype already has per-chat saved research context and Gemini/MakeMyTrip adapters. The latest full-response capture and hotel date/result-header guards are under live verification; the Nepal end-to-end task is not yet verified. The future coordinator targets ordinary messages, reading and summarizing DMs, and research-to-search workflows. This proposal does not establish that those workflows currently pass.

## Evidence from Android agent research

Dates are first paper submission dates; revisions are shown where relevant. Results below use each study's own models, budgets, app versions and evaluation procedure. They are not a matched ranking and do not predict Friday's accuracy or latency on the connected Pixel.

| Primary source and date | Android evidence | Mechanism to reuse | Deployment and cost limits |
| --- | --- | --- | --- |
| [AndroidWorld and M3A](https://arxiv.org/html/2405.14573v2), 2024-05-23; paper revised 2025-04-06 | 116 parameterized tasks across 20 Android apps; original best agent completed 30.6%. | Compare before screen, action and after screen; save a compact outcome; distinguish completion, infeasibility and an actual answer. Evaluate against independent state checks. | M3A uses multimodal models; T3A is text-only. These remain capable generative agents, unlike a narrow action selector. Benchmark state inspection privileges do not give Friday access to arbitrary private app databases. [Official code](https://github.com/google-research/android_world). |
| [Mobile Agent v2](https://arxiv.org/html/2406.01014v1), 2024-06-03 | Real Android English tasks and HarmonyOS other-language tasks. Within-paper advanced-task ablation: 61.4% for the full system versus 29.5% without its planner. The multi-app sample is small. | Separate planning, action decisions, reflection and focus-content memory; carry facts across steps and diagnose unsuccessful actions. | Uses GPT-4 for planning and GPT-4V for decisions/reflection through APIs. Multiple calls per action add cost and latency. Its ablation is not an AndroidWorld result. [Official code](https://github.com/X-PLUG/MobileAgent/tree/main/Mobile-Agent-v2). |
| [Mobile Agent v3](https://arxiv.org/html/2508.15144v2), 2025-08-21; revised 2025-09-01 | Reports 73.3% AndroidWorld for its framework. | Manager, Worker, Reflector and Notetaker; pending/completed subgoals; plan revision after failures; bounded timeout/stalemate; retained facts across apps. | GUI-Owl uses 7B/32B visual models and service-based deployment. In-phone Pixel performance is not established. Model reflection needs an independent final verifier. Do not treat the standalone 7B score and framework score as a controlled architecture ablation. [Official code](https://github.com/X-PLUG/MobileAgent/tree/main/Mobile-Agent-v3). |
| [Mobile Agent v3.5](https://arxiv.org/html/2602.16855v1), 2026-02-15 | GUI-Owl-1.5 reports 67.9% AndroidWorld for 2B-Instruct and 71.6% for 8B-Thinking. Android execution scripts are released. | Train observation, memory, reflection and progress reasoning together; route between visual actions and tools. | Released model sizes include 2B–32B. Mobile examples use a vLLM endpoint, which can be self-hosted but is not proof of offline phone deployment. Measure RAM, load time, tokens per second and accuracy before selecting it. Native model memory does not provide process-death recovery. [Official code](https://github.com/X-PLUG/MobileAgent/tree/main/Mobile-Agent-v3.5). |
| [UI TARS 2](https://arxiv.org/html/2509.02544v2), 2025-09-02; revised 2025-09-05 | Reports 73.3% AndroidWorld. | Keep recent steps in detailed working memory and compress older experience into episodic memory; combine GUI actions and tools in a repeated observation/action loop. | This is a trained multimodal policy with substantial training infrastructure. Public UI-TARS-1.5 weights must not be confused with the UI-TARS-2 report. No small offline Pixel deployment is established here. [Official repository](https://github.com/bytedance/UI-TARS). |
| [Agent S2](https://arxiv.org/html/2504.00906v1), 2025-04-01 | Reports 54.3% AndroidWorld, alongside separate desktop evaluations. | Generalist manager/worker plus specialist grounding; replan after each subgoal; retain task experience. | Requires capable generative models and grounding components. The paper's Android adaptation is not a ready-made Android SDK; the current repository emphasizes desktop use. Desktop spreadsheet and filesystem capabilities do not transfer across Android app sandboxes. [Official repository](https://github.com/simular-ai/Agent-S). |

The reusable pattern is hierarchical progress with explicit memory and feedback. It does not imply that assigning planner and reflector prompts to the current Writer will supply the capability demonstrated by these models. None of these selected sources establishes Friday's full production cancellation behavior on Android.

## What the Friday observations require

| Evidence | Architectural requirement |
| --- | --- |
| The original hotel request opened Chrome; later app navigation repeated Search and stopped without correct stay fields or results. Guided actions eventually selected Koramangala and check-in, with checkout and occupancy still missing. See [the hotel walkthrough](32-hotel-device-walkthrough.md). | Resolve named apps, keep the parent goal alive, represent required fields explicitly and gate Search on verified form values. A successful microtask cannot complete its parent. |
| A later owner-session diagnostic observed Laya selecting Search while old Pondicherry values remained, before the requested fields were populated. This was an unverified hotel-search attempt, not a completed result collection. | Reject Search unless fresh field readback matches the current task revision. App defaults and a model's action choice cannot establish the user's intent. |
| The owner-session Gemini attempt waited about 45 seconds and timed out without completing the research handoff. Current source has a longer bounded wait and response-copy logic; that code change alone does not establish live success. | Persist a waiting step, identify the matching request and completed answer, permit cancellation, and resume without submitting the question twice. |
| Refined Writer fixtures passed 19/20 mechanical checks, including expected negative outcomes; accepted drafts still contained semantic failures. See [Writer observations](33-writer-live-tests.md). | Keep schema, literal and length checks separate from semantic review. Do not promote a mechanical pass count into planner, summary or end-to-end task accuracy. |

Existing building blocks include [Assistant](../android/app/src/main/java/dev/layatest/agent/Assistant.kt), [ResearchWorkflow](../android/app/src/main/java/dev/layatest/agent/ResearchWorkflow.kt), [GeminiResponse](../android/app/src/main/java/dev/layatest/agent/GeminiResponse.kt), [HotelSearch](../android/app/src/main/java/dev/layatest/agent/HotelSearch.kt), [ReadMessages](../android/app/src/main/java/dev/layatest/agent/ReadMessages.kt) and [ChatHistory](../android/app/src/main/java/dev/layatest/agent/ChatHistory.kt). The proposal consolidates their orchestration responsibilities; it does not claim the repository has no persistence or adapters today.

## Runtime choice

| Option | Verified mechanism | Proposed role |
| --- | --- | --- |
| Native Kotlin with Room and SQLite | Room supplies an on-device SQLite abstraction, query checking, entities, DAOs and migration support. Android recommends Room over direct SQLite APIs. Documentation updated 2026-10-14. [Official Room documentation](https://developer.android.com/training/data-storage/room). | **Recommended phone runtime.** Store task/step state, facts, observations and attempts in Friday's private database. Choose a Room release compatible with the existing build; this proposal does not pin or install a version. Persistence itself requires no cloud service. |
| LangGraph with local SQLite checkpoints | Checkpoints retain graph state by thread, support interruption/resumption and preserve successful node writes during recovery. Its SQLite saver is intended for experimentation/local workflows. Documentation accessed 2026-10-20. [Official checkpointer documentation](https://docs.langchain.com/oss/python/langgraph/checkpointers). | Useful desktop development harness for planner experiments and replay. Local Python execution need not use cloud models. An embedded Python graph runtime is not needed for the recommended Android implementation; reuse its checkpoint concepts. |
| Temporal | Activities have serialized inputs/results, retry policies and timeouts. Activities can execute more than once, so the application must make writes idempotent. Documentation accessed 2026-10-20. [Official Activity documentation](https://docs.temporal.io/activity-definition). | Consider later for distributed workers or a device fleet with an explicitly approved server boundary. It adds service/worker operations and possibly hosting cost; it is excessive for the present single-phone coordinator. It does not make UI clicks exactly once. |

Room stores Friday's workflow records, not other apps' private databases. Keep captured source text bounded, app-private and deletable with its chat; avoid copying private device content into repository fixtures. The coordinator, local model and target app have distinct network boundaries: opening Gemini or MakeMyTrip can let those apps use their own network. Adding a direct cloud planner/API would be a separate scope and privacy decision.

## Proposed coordinator contract

Use typed identifiers for `chat_id`, `task_id`, `step_id`, `attempt_id` and `observation_id`, plus a monotonically increasing task revision. A chat can contain multiple tasks; a task belongs to one chat. A normal chat reply can answer a pending question, change a task or start a new task. It must not accidentally resume another chat's workflow.

Store these records independently:

- **Task:** original request, normalized goal, current revision, status, deadline, cancellation epoch and final verified outcome.
- **Fact:** typed value or explicit unknown, provenance, source observation/message, capture time, confidence category and whether the user confirmed it. A research proposal and a confirmed hotel destination are different facts.
- **Step:** capability, dependencies, required facts, expected package/allowed host, preconditions, postcondition verifier, retry/replan budgets and action boundary.
- **Attempt:** input revision, intended operation, before/after observations, dispatch status, verification result, elapsed times and recovery decision.

The planner proposes a dependency graph from declared capabilities. A deterministic validator rejects unsupported capabilities, cycles, unresolved required inputs, invalid dates and actions outside the task's authority. The planner cannot create permission grants or treat source instructions as user instructions.

### Example stored object

This synthetic example illustrates a pending trip, not an actual captured Gemini answer. In the database, facts, steps and attempts can be separate tables.

```json
{
  "schema_version": 1,
  "chat_id": "chat_demo",
  "task_id": "trip_demo",
  "revision": 2,
  "goal": "Research a suitable season in Nepal and search hotels",
  "status": "waiting_input",
  "cancel_epoch": 0,
  "facts": [
    {
      "key": "research.city_proposal",
      "type": "city",
      "value": "Kathmandu",
      "state": "proposed",
      "provenance": {"kind": "app_answer", "observation_id": "obs_demo"},
      "user_confirmed": false
    },
    {
      "key": "hotel.check_out",
      "type": "local_date",
      "value": null,
      "state": "unknown",
      "provenance": null,
      "user_confirmed": false
    }
  ],
  "steps": [
    {
      "step_id": "hotel_form",
      "capability": "hotel.populate_form",
      "depends_on": ["research_capture", "trip_choices"],
      "expected_app": "com.makemytrip",
      "required_facts": [
        "hotel.destination", "hotel.check_in", "hotel.check_out",
        "hotel.adults", "hotel.rooms", "hotel.children"
      ],
      "status": "waiting_input",
      "preconditions": ["stay_valid", "all_required_facts_confirmed"],
      "postcondition_verifier": "hotel.form_matches_task_revision",
      "retry_policy": {"max_attempts": 2, "reconcile_before_retry": true}
    }
  ]
}
```

Store dates as ISO local dates and retain the interpretation timezone/year. Zero children is a supplied value; null children is unknown. Budget can be an explicit no-filter choice. If children require ages, those become required facts. Missing input does not justify accepting app defaults.

### Persisted execution states

| State | Meaning and permitted next action |
| --- | --- |
| `ready` | Dependencies, required inputs and authorization are satisfied; acquire the foreground executor. |
| `running` | A bounded attempt is underway with recorded intent and cancellation epoch. |
| `verifying` | Inspect fresh app state and compare it to the expected postcondition. Dispatch acknowledgment is insufficient. |
| `waiting_input` | Ask one concise question and retain the task for the user's normal chat reply. |
| `waiting_approval` | Retain the exact proposed action where the existing confirmation policy requires approval. Approval applies only to that action and revision. |
| `outcome_unknown` | An interruption or ambiguous response means the action might have happened. Reconcile the external state before considering another attempt. |
| `completed` | Store the verifier's evidence and return the actual outcome or bounded data coverage. |
| `cancelled` | Prevent further mutations and reject late model results. Cancellation does not undo an earlier external action. |

A failed verifier can return a step to `ready` within its recovery budget, request input, or end with an explicit failed/blocked result. Process restart must turn interrupted `running`/`verifying` attempts into reconciliation work rather than silently resetting them to `ready`.

## Execution and recovery

### One foreground device executor

Allow only one owner of device UI mutations at a time across all chats. App switching, accessibility actions, keyboard input and response-copy operations share this executor. Hold or reacquire ownership when a response wait requires observing the foreground app. Pure planning, local source parsing and read-only research on already captured data may run in parallel, with revision checks before committing their outputs. Two agents operating separate apps on the same foreground display are not independent workers.

Each adapter declares supported operations, input/output schemas, required permissions, permitted app hosts, side effects, timeouts, cancellation behavior and its verifier. A package being installed does not prove that its current screen supports the operation. Laya remains one UI action-selection backend; Writer remains a bounded text backend. Alternate local or hosted models can be evaluated behind these contracts without rewriting the task store.

### Reconcile before retrying UI actions

A database transaction cannot atomically commit both a workflow record and a click in another app. Persist the attempt intent, execute the action, then verify the resulting app state. A crash between those operations leaves uncertainty.

On recovery, inspect the current app: the requested field may already be set, a search may already be loading, or a message may already exist. Continue from verified progress. If a consequential operation cannot be reconciled, return `outcome_unknown` and ask for review instead of repeating it. An internal attempt identifier alone cannot deduplicate a third-party UI action; only a target-supported idempotency mechanism or reliable readback can do that.

Avoid repeatedly submitting a prompt or clicking Search because the model selected the same control. Detect unchanged relevant fields/screens, record the failed precondition, refresh observation and choose one bounded recovery or replan. Cancellation checks belong before model calls, after results return, before each mutation and inside waits. Late results for an older task revision or cancellation epoch are discarded.

### Gemini response and normal chat

Retain the exact public research question and submission evidence. Qualify Gemini through the known Gemini/Google host pair and Gemini-specific controls, then associate the answer with the submitted question. Keep private DMs outside that research prompt.

Wait for a fresh completed response with a deadline and visible progress. A fixed sleep, a stable short fragment or absence of a loading label alone cannot prove completeness. Use the adapter's verified response controls or full-answer copy route where available; otherwise retain partial/unreadable status. Distinguish slow generation, login, network error, lost foreground and inaccessible text. Stop must work throughout this wait without submitting another question.

Return the captured answer into the originating Friday chat, with source app and coverage. Keep source text untrusted, parse any structured proposal separately and leave dates/guests unknown until supplied or approved. A normal follow-up such as “use two nights” updates the pending task; another chat stays isolated. Show the full captured answer within declared size limits, or mark truncation explicitly. Do not replace the answer with only a machine footer or open MakeMyTrip as proof the research is complete.

For the hotel step, verify destination, both dates, guests and rooms before Search and again in the results context. Extract hotel names, displayed prices/currency/basis and availability only from actual results. Research recommendations are not live inventory. Booking and payment remain separate consequential actions under the existing confirmation boundary.

## Staged migration

Begin this migration after the current Nepal test is complete and its remaining failures are recorded. These stages are future implementation work, not changes delivered by this research.

| Stage | Change | Exit evidence |
| --- | --- | --- |
| 1 | Introduce typed task/step/fact records and an append-only attempt log behind existing chat IDs; migrate current research state conservatively. | Restart retains the right chat/task and unknown fields; old app defaults do not become confirmed facts. |
| 2 | Put existing adapters behind declared capability and verifier contracts; add one foreground executor and revision/cancellation checks. | Existing microtasks dispatch through the contract; cancellation prevents later actions; unsupported screens return an explicit outcome. |
| 3 | Replace orchestration for the five workflows below with dependency scheduling, missing-input handling and reconciliation. | End-to-end evidence includes exact field readback, source coverage and recovery, not only model output or app launch. |
| 4 | Add a generative planner at task/subgoal boundaries and compare it with deterministic workflow templates. | It improves verified task outcomes at acceptable latency and cost, without inventing inputs or bypassing capability checks. |
| 5 | Evaluate stronger visual workers and planning models, including server-backed alternatives only if that scope is approved. | Matched device/tasks/budgets; recorded RAM, load/prefill/decode, thermal conditions, privacy boundary and total completion time. |

Keep the first release to a small complete set. Do not train a new agent or replace every adapter before the coordinator and its evaluation can distinguish useful progress from false completion.

## Proposed end to end acceptance benchmark

Use synthetic content and controlled accounts. Run the same cases against the existing flow and each migration stage; record autonomous and operator-guided runs separately. The table defines acceptance work, not completed tests.

| Case | Setup and request | Completion oracle | Required interruption or negative variant |
| --- | --- | --- | --- |
| C01 Chat message | Explicit target app, synthetic recipient and facts for a short message. | Correct conversation and populated composer; no invented fact. Sent status is separate and requires the existing authorization boundary. | Ambiguous recipient; Stop after draft but before handoff; restart after an uncertain action must not duplicate a message. |
| C02 DM summary | A known synthetic conversation with visible facts, a tentative decision and a source-instruction canary. | Captured messages belong to the requested conversation; summary preserves meaning and uncertainty; coverage excludes unseen history and composer/control text. | Unreadable/protected screen or partial capture returns limited coverage; private content is not sent to Gemini. |
| C03 Gemini to MakeMyTrip | Public Nepal season question; later supply explicit destination, stay, rooms/adults/children and optional budget choice. | Matching complete Gemini answer retained in Friday chat; exact hotel fields verified; real result context and bounded hotel data captured. | Response exceeds 45 seconds; partial/hidden answer; old Pondicherry form defaults; no results; Stop during wait. No claim of completed hotels from app launch. |
| C04 Context resume | Stop after research, restart Friday, then continue in the original chat; create a separate unrelated chat too. | Original goal, source, confirmed inputs and pending step survive; no duplicate Gemini submission; unrelated chat does not inherit the trip. | Crash between action dispatch and verification requires reconciliation; cancellation remains effective after restart. |
| C05 Input change | Change dates, destination or occupancy after a proposed plan or populated form. | Task revision increases; dependent form/results are invalidated and reverified; old asynchronous model results cannot overwrite the update. | Conflicting dates, missing checkout, zero versus unknown children, and expired approval leave the appropriate input/approval state. |

For every run record: verified completion versus false completion, exact-field correctness, semantic rubric review, missing-input behavior, source coverage, duplicate external actions, cancellation latency, restart recovery and per-stage time. Split time into observation, planning/inference, app action, network/app wait, verification and user wait. Report sample sizes and distributions after enough repetitions; do not infer end-to-end reliability from Writer's 19/20 mechanical result or from unmatched paper benchmarks.

## Remaining decisions

General coordinator development is deferred until the current Nepal test is complete. Planner model choice, measured phone feasibility, final live workflow results and distribution-policy constraints remain separate decisions. The research supports explicit planning, memory and verification; only the acceptance benchmark can establish whether Friday's resulting implementation completes the owner's tasks reliably.
