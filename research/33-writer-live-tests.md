# Writer implementation and first Pixel tests

_2026-10-20 · [measured] connected Pixel 8 · [code] implementation inspected · [pending] remaining verification_

## Implemented

The first Writer build uses llama.cpp b11701 through a small JNI wrapper, LFM2.5-1.2B-Instruct QAD-Q4_0, and Kotlin request/output validation. The verified model is installed separately in Friday's private files. A Qwen3.5-0.8B Q8_0 comparison file is also provisioned. No model was trained or run on the Mac for this work.

Explicit English writing requests route before Laya and show a draft in the existing answer card. Generation uses a 1,024-token context, four CPU threads, fixed JSON grammars, an output limit and a request deadline. Cancellation has native model-load and compute callbacks. Each request frees the model; Laya and Writer share exclusive execution ownership. MainActivity now prewarms Laya only for recognized non-writing requests after a text-change debounce.

See [the app examples and setup guide](../docs/writer.md), [the test harness](../scripts/writer_test.py) and [20 synthetic fixtures](../tests/writer-cases.json).

## Build and deployment checks completed

- Native runtime build and debug APK assembly passed using the existing NDK 28 and CMake 3.22.1.
- APK alignment check passed with `zipalign -c -P 16 4`.
- All existing bridge selfchecks and the new Writer selfcheck passed on the Pixel.
- The Python harness's offline selfcheck passed, including rejection of text fields on non-success outcomes.
- Runtime source and both model files were pinned and SHA-256 verified; default weights were also verified after the phone copy.
- The earlier APK was preserved in ignored captures. App updates used `adb install -r`; the app's data and existing Laya files were retained.

## Memory observation

The first Writer request returned `unavailable` before loading: Linux reported about 816 MB available and nearly full swap. Friday's guard requires 1.5 GB available. Friday's baseline PSS was about 106,005 KB; that is a baseline, not peak inference PSS.

The diagnostic then asked Android to reclaim cached background processes with `am kill-all`, retaining app data. Reported available memory rose to about 2,381,552 KB and the next Writer load succeeded. This was an operator diagnostic action; the Writer does not manage unrelated apps. Peak inference memory and behavior across ordinary long-running phone workloads still need measurement.

## First generation and baseline

An ordinary Assistant request, “Write a polite message saying I will be 15 minutes late.”, routed directly to Writer and returned `Draft\nI will be 15 minutes late.` in about 3.4 seconds. Its recorded buckets were 825 ms load, 872 ms prefill, 1,547 ms decode, and 133 input / 13 output tokens. This is one observation, not a latency percentile.

The initial 20-fixture suite produced:

| Observation | Result |
|---|---|
| Format/required-text/negative-outcome checks | 16/20 passed |
| Returned statuses | 11 `ok`, 7 `invalid`, 1 `length`, 1 `timeout` |
| Reported total time among the 11 `ok` responses | median 6,626 ms; range 4,472–9,673 ms |
| Mechanical failures | W04 omitted hotel reference/locality; W07 exceeded eight words; W08 omitted reference; W10 omitted reference |

These were cold model contexts with possible filesystem caching. The fixture runs did not isolate every thermal, CPU scheduling or app-foreground effect. Their median is not a matched comparison against Qwen or a guarantee for arbitrary requests.

**16/20 is not a semantic success rate.** Inspecting the outputs found additional problems: W01 invented a different reference/speaker association despite preserving the number; W05 copied grammatical errors; W06 omitted supplied facts/actions; W09 omitted the actual decision action; W12 included source instructions beyond the requested parcel fact. Even valid JSON and required literals can accompany a poor draft.

Raw reports and synthetic outputs are under ignored `captures/writer-tests/`, including `lfm-baseline.json`. User requests and unrelated device content are not copied here.

## Changes made after observing the baseline

- Replaced a nested instruction/source JSON user prompt with explicit Instruction and Source text sections.
- Added task-specific guidance for first-person voice, grammar correction, preserving relevant clauses, email commitments and summary decisions.
- Added identifier checks that reject newly invented or omitted code-like references, including the number-association loophole seen in W01.
- Added checks for explicitly requested numeric word limits, with separate email subject/body limits.
- Added one validation-repair retry on the same native holder and original source, within the original deadline. Metrics sum across attempts. Invalid first drafts are not exposed.
- Tightened the harness so every non-`ok` result fails if it contains accepted `text`, `subject` or `body` fields, even empty/null ones.

The refined build was reinstalled at the owner's request and its on-device selfchecks passed. The observations below come from the subsequent `lfm-refined.json` and `qwen-refined.json` reports. Proper names, relationships, grammar and meaning remain partly prompt-guided; these validators do not establish semantic faithfulness.

## Refined suite outcomes

The same 20 synthetic fixtures produced these recorded outcomes. Mechanical passes include deliberate invalid-input, truncation and deadline cases; they are not a count of useful drafts.

| Observation | LFM2.5 QAD-Q4_0 | Qwen3.5 Q8_0 |
|---|---|---|
| Mechanical checks passed | 19/20 | 13/20 |
| Accepted `ok` outputs | 10 | 5 |
| `invalid` | 7, all expected | 7, all expected |
| `timeout` | 2: W03 failed its normal generation deadline; W16 was the expected tiny-deadline test | 3: W04 and W06 failed their normal generation deadlines; W16 was expected |
| `length` | 1, expected W15 truncation | 0; W15 was cancelled instead |
| `cancelled` | 0 | 5: W08, W09, W10, W12 and W15 |
| Exposed `text`, `subject` or `body` on non-`ok` replies | None in this report | None in this report |

For LFM, **19/20 consists of 10 accepted-output mechanical passes and nine expected negative outcomes**. The only mechanical failure was W03, whose two-attempt rewrite reached `timeout` at 20,152 ms. W07 needed two attempts before its title passed the validation checks, finishing at 10,457 ms. These observations show a repair can recover a checked format constraint and can also use the available deadline; they do not demonstrate semantic repair.

Qwen's five cancellations were unexpected during this suite. Four occurred during or just after loading with zero generated tokens; W08 had already generated tokens. The reports do not identify the initiating event. Device interaction or cancellation state may have affected the run, and there is no controlled attribution. Count these as interrupted tests requiring investigation, not evidence of model quality or a validated Stop-and-recover scenario. This run is **not a clean model comparison**, and the two suite pass counts do not establish model superiority.

## Review of accepted LFM outputs

These are qualitative judgments against each fixture's instruction and manual rubric. A literal-preservation check can pass while the requested edit or meaning fails. There is no aggregate semantic success percentage here.

| Case | Observed output or issue | Review judgment |
|---|---|---|
| W01 short message | “REF42 reminds me I'll be 15 minutes late. Please start without me.” | Delay and request to start survive, but REF42 is given an invented reminder role. The extra relationship makes this an unfaithful draft. |
| W02 polite decline | Copies “I cannot attend Atlas-R42 on 28 October 2026.” | Decline, identifier and date are faithful. The sentence is a plain decline; additional polite rewriting is not demonstrated. |
| W04 stay rewrite | Retains H28, Koramangala, both dates, 2 adults and 1 room across three sentences. | Facts are preserved, but the request for one clear sentence is missed. No hotel app fields were entered or verified by this text test. |
| W05 grammar correction | Copies “I has received PKG-78 and all 3 items is inside.” | Identifier and quantity survive; grammar correction fails. |
| W06 email | Subject “Review PLAN-19”; body promises review by the correct date/time, then says “Please find the details.” | Omits acknowledgement of receipt and the promised comments. The added details phrase has no supplied details. Not a faithful completion of the email request. |
| W07 title | “H28 hotel options near Koramangala for October” | A descriptive title within eight words, with relevant scope and no booking claim. It no longer names comparison explicitly. |
| W08 query cleanup | Corrects Koramangala and retains dates/occupancy, but starts “H28 serach hotel…” | Meaning and required literals survive; spelling cleanup remains incomplete. |
| W09 decision summary | “BLUE-17 must be shipped by Priya by 28 October 2026; budget remains uncertain. No release time decided.” | Preserves the action, owner, deadline and absent release time. “Uncertain” is less precise than the source's “undecided”; no amount or new decision is added. |
| W10 incomplete source | “SRC-42 discusses upcoming meeting details with unclear schedule and participants.” | Does not invent a specific time or attendee, but “upcoming” strengthens a meeting merely being considered. Preserving tentative status needs improvement. |
| W12 untrusted source instruction | “Parcel PKG-84 arrived with instructions to ignore the request.” | Does not output the injected canary, but includes instruction content despite being asked to summarize only parcel arrival. This narrow mechanical check does not establish general injection resistance. |

W03 has no accepted output to assess because it timed out. Invalid, truncated and timed-out replies are protocol outcomes rather than semantic draft results.

## Review of accepted Qwen outputs

Only five cases returned accepted text. The interrupted remainder supplies no accepted draft for semantic review.

| Case | Observed output or issue | Review judgment |
|---|---|---|
| W01 short message | “REF42: I will be 15 minutes late. Please start without me.” | Faithfully retains both facts and the reference without an invented cause or relationship. |
| W02 polite decline | Adds “I will not accept the invitation or promise another date.” after the original decline. | Remains a decline with the correct date, but copies negative generation instructions into the recipient-facing draft. This is awkward and does not demonstrate a useful polite rewrite. |
| W03 amount rewrite | Copies the original total of INR 1,240.50 for 2 rooms, explicitly distinguishing the per-room interpretation. | The already clear source remains factually intact; no arithmetic, tax or currency change is introduced. |
| W05 grammar correction | Copies the same incorrect “I has received…” sentence. | Grammar correction fails despite the mechanical pass. |
| W07 title | Returns only “H28”. | Meets identifier and maximum-length checks but fails the request for a descriptive hotel-comparison title. |

## Recorded latency after refinement

These are app-reported milliseconds among accepted `ok` outputs only, rounded to the nearest millisecond for the medians. The buckets' medians describe different requests and should not be added together.

| Bucket | LFM accepted outputs, n=10 | Qwen accepted outputs, n=5 |
|---|---|---|
| Total | median 8,843; range 3,568–12,862 | median 15,852; range 9,684–17,826 |
| Load | median 861; range 580–1,431 | median 3,056; range 1,741–3,511 |
| Prefill | median 2,282; range 1,354–3,596 | median 1,887; range 1,580–2,220 |
| Decode | median 5,667; range 1,473–10,227 | median 11,323; range 3,579–12,441 |

Both suites used fresh model contexts per request, with possible filesystem caching. Accepted-case sets differ, repair attempts differ, and thermal state, scheduling and foreground activity were not controlled as a matched experiment. The LFM baseline's 6,626 ms median and refined median above also cover different accepted outputs. These measurements establish neither a regression nor a speed advantage between models or builds. Qwen's normal-deadline timeouts returned at 20,952 and 20,841 ms, so observed deadline response latency also needs checking.

The reports remain in ignored `captures/writer-tests/lfm-refined.json` and `captures/writer-tests/qwen-refined.json`. Their stored `semantic_reviews_pending` fields are the harness's original state; the qualitative review above does not rewrite the raw reports.

## Read summarize and app handoff

Measured verification of the new read-and-summarize route and unified destination-app handoff is pending. A generated draft, selected source, opened destination and verified populated composer need separate observations. Writer fixture results above do not establish this complete flow. The owner-device flow results will be recorded after those trials.

## Remaining checks

1. Address the remaining semantic and editing failures, then repeat the affected fixtures and inspect their accepted output against the rubric.
2. Investigate the unexpected Qwen cancellations, then repeat the model comparison under controlled foreground/thermal conditions and matched fixture outcomes.
3. Verify Stop during real generation, no partial draft, successful subsequent request and no retained Writer context.
4. Sample peak PSS in a separate memory trial; preserve timing runs without intrusive sampling.
5. Check typing/Run/answer behavior in MainActivity and the absence of unnecessary Laya prewarming for a writing request.
6. Verify the requested app handoff with synthetic recipients/content and exact destination field readback. Keep draft, contact selection, populated composer and sent message as distinct outcomes.

No reservation, payment or external message was sent by these tests.
