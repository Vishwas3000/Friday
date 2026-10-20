# Navigation and task evaluation harness

The harness separates **what Friday reports** from **what actually happened**. A `COMPLETED` state, model `DONE`, dispatch count, or `evidence.verified` flag cannot satisfy a goal oracle.

The public [suite](../evals/navigation/cases.json) contains 24 synthetic cases. Three cases can run through the connected Friday bridge: `app.clock`, `app.maps`, and `maps.search`. Calendar writes, bill reads, Writer, drafts, Gemini, cancellation, and restart cases require controlled test-environment observations or synthetic fixtures. Their presence in the manifest does not mean they have run on a device.

## Commands

No dependencies beyond Python 3.8+ are required.

```sh
python3 scripts/navigation_harness.py --self-check
python3 scripts/navigation_harness.py validate-suite
python3 scripts/navigation_harness.py score captures/navigation-harness/runs.jsonl
python3 scripts/navigation_harness.py replay captures/navigation-harness/runs.jsonl
```

`--self-check` tests the grader with synthetic positive and false-positive fixtures. It performs no bridge, device, app, or model operation and does not create live records. `validate-suite` prints case revisions, hashes, and execution modes. `score` and `replay` are identical, read-only operations; input is newline-delimited JSON, and `-` reads standard input.

To run one supported navigation case, first select a dedicated test chat in Friday and arrange the existing local bridge forwarding. The harness does not use ADB or change the selected conversation:

```sh
python3 scripts/navigation_harness.py --run-case app.clock --port 8765
python3 scripts/navigation_harness.py --run-case maps.search --port 8765
```

Each command captures raw accessibility state before and after one `ask` request, then reads the task snapshot for the reported status. Only the raw state proves the goal. The runner does not retry. A bridge timeout is recorded as unavailable and does not prove Friday stopped.

```sh
python3 scripts/navigation_harness.py --run-case calendar.jan7_all_day
```

The calendar command records **SKIP without contacting the bridge**. There is no flag to enable writes, send messages, make payments, or buy/book anything. A separate, explicitly controlled test environment must execute those tasks and supply observations to the replay grader.

Default raw records go to `captures/navigation-harness/*.jsonl`, already ignored by Git. New record files use mode `0600`. Raw trees, draft contents, account information and private transcripts must stay there. The public suite uses fictional labels and the reserved address `qa@example.test`.

Use global options before the subcommand:

```sh
python3 scripts/navigation_harness.py --suite captures/navigation-harness/private-cases.json validate-suite
python3 scripts/navigation_harness.py --metadata-json captures/navigation-harness/build-metadata.json --run-case app.maps
```

## Manifest

Each case declares:

| Field | Purpose |
| --- | --- |
| `id`, `revision` | Stable identity and a version for changed expectations. |
| `family`, `allowed_apps` | Capability group and explicit app/provider identity. |
| `request`, `context` | Synthetic request, fixed clock/time zone and selected chat mode. |
| `expected.behavior` | Expected reported state, separate from actual task completion. |
| `expected.outputs` | Exact event fields, text, query/result fields, labeled amounts or response. |
| `oracle` | Registered independent observation rule and any query/chat binding. |
| `risk`, `side_effects`, `policy` | Effects the case requires and the permanent send/payment prohibition. |
| `execution.mode` | `bridge_safe` navigation or `fixture_only`. |

The runner additionally constrains safe requests to Clock/Maps opening and a Maps search with the manifest's exact query. Relabeling a calling or writing request as navigation does not enable it. There is no arbitrary bridge-command execution option.

Calendar fixtures use 20 October 2026 as the request date. A yearless January 7 means **7 January 2027**; the next February 29 is in 2028. All-day provider timestamps are UTC midnight, with the following date as the exclusive end. These fixtures must run with the declared test clock, or use a private, revised suite with the correct expectations. They are not date-relative live mutations.

## JSONL record

Use `base_record(case, suite, source)` from the script when writing an environment collector. It supplies the exact case hash, revision and request binding. `validate-suite` also exposes those hashes. Unknown/mismatched case revisions do not pass.

```json
{
  "schema_version": 1,
  "run_id": "unique-run-id",
  "case_id": "app.clock",
  "case_revision": 1,
  "case_sha256": "copy the exact hash from validate-suite",
  "suite_revision": "2026-10-20.1",
  "source": "test_environment",
  "request": "Open Clock",
  "execution": {"status": "executed"},
  "assistant": {"state": "COMPLETED", "claimed_success": true, "answer": "..."},
  "before": {"package": null, "windows": []},
  "after": {"package": "com.google.android.deskclock", "windows": []},
  "manual_interventions": [],
  "timing": {"wall_ms": 1200, "action_ms": null, "model_ms": null, "wait_ms": null},
  "metadata": {"build": null, "model": null, "dataset": null, "device": null, "app_versions": null}
}
```

This abbreviated example is **not a passing observation**: Clock must expose a readable, visible application tree. Preserve the actual bridge `state` response under `before` and `after`. Visible nodes from overlays, another app, hidden subtrees, or a search input by itself do not prove results.

Sources remain separate:

- `live_bridge`: an actual explicit harness navigation run. A skipped case is still not a live execution.
- `test_environment`: an independent collector observing an actual controlled Android/test application environment.
- `synthetic_fixture`: fabricated grader data, fake drivers, and parser/oracle checks. Never label these live or test-environment acceptance.

Replay validates the supplied observations; it does not attest that a JSON file came from real hardware. The collector owns that provenance. Do not copy Friday's completion proof into an independent observation field.

Optional `--metadata-json` merges supplied build SHA, model/checkpoint hash, dataset revision, device/OS identity, app version codes, or collector revision into `metadata`. Unknown values remain `null`; the harness does not infer versions from filenames or invent device information.

## Independent oracles

### Calendar and retry

Collect an independent, complete query before the task and after it. Query by the manifest's synthetic title and date window, rather than an event ID returned by Friday. The read-only bridge supports:

```json
{"cmd":"calendar_observe","title":"QA review","from_ms":1767225600000,"until_ms":1893456000000}
```

If permission is missing or the query is truncated, keep the observation unavailable. The harness does not grant Calendar access. Assemble the returned raw event rows as:

```json
{
  "provider_observation": {
    "source_package": "com.android.providers.calendar",
    "query_complete": true,
    "query": {"title":"QA review","from_ms":1767225600000,"until_ms":1893456000000},
    "before": [],
    "after": [{"event_id":73,"title":"QA review","start_ms":1799280000000,"end_ms":1799366400000,"all_day":true,"zone":"UTC","busy":true}],
    "checkpoints": [
      {"phase":"first","events":[{"event_id":73,"title":"QA review","start_ms":1799280000000,"end_ms":1799366400000,"all_day":true,"zone":"UTC","busy":true}]},
      {"phase":"retry","events":[{"event_id":73,"title":"QA review","start_ms":1799280000000,"end_ms":1799366400000,"all_day":true,"zone":"UTC","busy":true}]}
    ]
  }
}
```

Exact title, dates, all-day setting, zone and Busy status must match. Multiple saved event IDs fail. Retry requires the **same actual event ID** at both checkpoints. Restart acceptance also requires `recovery_observation: {"trace_complete":true,"process_restart_count":1}` from process instrumentation. A claimed retry or restart is insufficient.

Missing/invalid-input fixtures require unchanged complete before/after provider observations **and** `effect_observation: {"complete":true,"calendar_writes":0}` from independent instrumentation. A title-filtered snapshot alone cannot prove that a wrong-title write never happened. The read-only provider bridge does not supply that write audit. Correctly asking for missing data can pass the behavior contract while goal completion remains zero.

### Apps, search, bill amounts and text

- **Open app:** selected app identity and a readable visible application tree.
- **Wrong app:** a readable different application tree proves navigation failure, including when Friday reports completion. A package name without a readable tree remains unavailable evidence; invalid revisions and skipped runs cannot become proven failures.
- **Search:** exact query read back from an editable/search control plus separate exact result fields. Query order, dates and numbers are preserved; matching a few words is insufficient.
- **Bills:** exact due label and currency amount together on a line, or in adjacent split nodes. An overall total must be explicitly labeled `Grand total due` or as a total across/for all cards. Promotions, credit limits and sums of visible cards do not qualify. Friday's returned answer must include the expected amounts and must not add unsupported numbers. Coverage remains the current visible screen.
- **Text:** exact returned text and a newly visible matching assistant output, with independent `chat_observation.before_chat_id` and `after_chat_id` matching the oracle's `origin_chat_id`. Friday's existing `Friday …\n\n` turn header or a controlled `assistant_output`/`assistant_response` resource ID identifies output; a composer/user field cannot qualify. Old unchanged output cannot satisfy a new request. The SLM fixture additionally requires `generation_observation` with `trace_complete:true` and a positive actual `generation_count`; a rule-produced string cannot establish SLM acceptance. This tests the fixture's exact text task, not unrestricted semantic writing quality.
- **Unavailable Writer:** `generation_observation` needs `trace_complete:true`, `preflight_status:"memory_pressure"`, `generation_count:0`, and `output_count:0` from native-call/output instrumentation. This passes rejection behavior, not generation or task completion.

An inaccessible CRED/Gmail tree is unavailable goal evidence. The CRED unreadable fixture can pass correct `BLOCKED` behavior when raw state independently has no package or application windows. It still contributes **zero goal success**.

### Draft handoff

The Gmail fixture checks exact recipient, subject and body from the declared composer resource IDs. An editable field containing text somewhere in the app is insufficient. It also requires independent `effect_observation: {"complete":true,"messages_sent":0}` from a controlled transport/test application. Missing send-effect coverage is unavailable. Sending anything fails the policy and completion result.

### Gemini, origin chat and partial responses

Controlled environment records need `research_observation` with:

```json
{
  "source_package":"com.google.android.apps.bard",
  "conversation_id":"qa_gemini_conversation_a",
  "turns_complete":true,
  "turns":[
    {"role":"user","request_id":"QA_RESEARCH_ALPHA","text":"the exact manifest request"},
    {"role":"assistant","in_reply_to":"QA_RESEARCH_ALPHA","text":"the exact complete fixture answer"}
  ],
  "publications":[{"chat_id":"qa_friday_chat_a","request_id":"QA_RESEARCH_ALPHA","answer":"the exact complete fixture answer"}]
}
```

Collect provider turns and Friday chat publications independently. The oracle compares the exact expected whole answer, provider conversation, source request and originating Friday chat. It counts matching actual user turns, not a claimed Send count. The fresh-context fixture rejects the other conversation's synthetic label. Existing-chat follow-up is a separate fixture.

The partial-response fixture requires observed incomplete content, exactly one actual matching user turn, and no publication. Correctly withholding a partial answer passes behavior while goal completion stays zero.

### Stop

Cancellation needs a complete instrumented observation window of at least 3000 ms:

```json
{"control_observation":{"trace_complete":true,"observation_window_ms":3000,"actions_after_cancel":0,"writes_after_cancel":0,"late_publications":[]}}
```

An overlay showing “Stopped” does not prove that actions stopped. Any late action, write or publication fails the behavior contract. Cancellation is not goal completion.

## Reports and denominators

The report includes per-source, task-family and app splits. App splits can overlap for multi-app cases and must not be added together.

| Metric | Meaning |
| --- | --- |
| `goal_success` | Independent evidence proves the actual requested result. |
| `behavior_contract_pass` | Observed behavior and reported state match the expected contract; `null` means unavailable evidence. |
| `unassisted_goal_success` | Proven goal with no recorded manual intervention. |
| `false_success` | Friday claims completion while independent observations prove failure. |
| `unverified_success_claim` | Friday claims completion but the outcome cannot be independently checked. |
| `wrong_app` | True/false when app identity is observed; otherwise null. |
| `duplicate_writes` | Extra independently observed saved event IDs/provider user turns; null when not observed. |
| `manual_interventions` | Collector-recorded help such as conversation selection, permission action or corrected setup. |

`goal_success_rate_all_recorded` includes skipped and unavailable records as zero successes. Full-suite latest-result coverage divides by **all 24 manifest cases**, including cases never run. For repeated cases, the last record in input order is the latest result. Behavior observations have their own denominator; proven oracle failures, including wrong calendar dates, count as observed behavior failures. Unavailable checks remain unknown. Synthetic/test-environment/live results never share a headline success rate.

Wall time includes harness before/after collection for a live run; `task_call_ms` separately records the synchronous `ask` duration. Action/model/wait times stay null unless an independent collector supplies actual measurements. Negative, nonfinite, boolean and text timing values also grade as null. They are not derived by subtracting unrelated durations or assigning all wall time to inference. Reports show known/unknown sample counts for every timing category.

If provided, `assistant`, `execution` and `timing` must be JSON objects. Malformed sections produce a structured validation error. Represent an unknown measurement with null inside `timing`, rather than a null section.

## Current limits

The harness does not automate all 24 cases, control process death, scrape inaccessible apps, judge unrestricted generated prose, or reconstruct hidden account data. It provides strict outcome checks and a consistent record format for adding controlled environment runners. No training result, model-quality score, or end-to-end device acceptance follows from `--self-check` alone.
