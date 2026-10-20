# Pixel Research: live acceptance report

_2026-10-20 · measured checks and live outcomes · M1 acceptance not passed_

## Current status

The latest whole-response Copy/recheck build is installed on the reconnected Pixel. **24/24 synthetic coordinator checks pass on the Pixel**, and the `gemini_chat`, `gemini_research`, `gemini_copy` and `hotel` fixture groups each report `ok`. These checks do not operate Gemini's live response-copy controls or establish a successful research task.

**M1 remains unpassed.** Live evidence includes capture, label isolation, short answers, UI Stop, assisted Resume, repeated recheck and fresh clipboard-timestamp binding. Maps passed. Gmail's initially empty subject/body were repaired and verified visually without field edits; autonomous field readback remains unavailable, so Friday reports the handoff unverified. The 20-case Writer run produced no generated outputs: seven expected invalid-input rejections and 13 memory-blocked cases. Automatic conversation location, clean restart acceptance and content constraints remain open. **Initial L01–L05 all used the existing conversation.**

Private questions, account details, conversation identifiers and raw device captures are excluded from this report. Public fixture descriptions and aggregate observations are sufficient here.

## Evidence matrix

| Check | Evidence class | Observed result | Acceptance meaning |
|---|---|---|---|
| Latest coordinator suite on Pixel | Synthetic Android: real Room, fake research driver | **24/24 passed** after the header fix, latest suite duration 8.361 s | Coordinator contract evidence; not research latency or 24 live tasks. |
| Latest focused fixtures on Pixel | Selector/capture fixtures | Chat, research, Copy and hotel groups all `ok` | Fixture evidence; assisted live Copy is recorded separately below. |
| Full Pixel `selfcheck` | Parser/component/native checks | All **18 groups passed**, invocation wall time 0.441 s, including mailto encoding and clipboard freshness boundaries | Separate from the 24 coordinator cases and from live task success. |
| Writer harness host self-check | Host-only harness logic | Passed without model or bridge use | Does not resolve the memory-blocked generation cases. |
| Writer 20-case Pixel run | Real request validation/preflight | **7/20 mechanical passes**, all seven expected invalid rejections; 13 unavailable; zero accepted outputs/native generation | Availability failures, not model-quality or inference-latency passes. |
| Concurrent Clock request during Research | Live request-gate check | Rejected in **0.006 s**, without leaving Gemini | Protected Run entry respected device ownership; not a new Clock success. |
| Fixed New chat / History header | Live Pixel UI check, with and without IME | Both controls reachable above transcript; New chat selected an empty conversation; History restored the original conversation and its 8,794-character result | Navigation regression passed; the initial batch's invalid setup remains invalid. |
| L14 UI Stop | Live task cancellation | `CANCELLED` in approximately 0.902 s after Stop; no result or late publication during a 3 s observation; independent Clock request then succeeded | Real cancellation evidence within the observed interval. |
| L15 restart/resume sequence | Assisted failure → installed fix → successful same-task Resume | Final 1,587-character whole answer; same task/chat/prepare/submit counts, no new dispatch and one row. Successful retry wall time 8.372 s. | Resume passed after intervention; not clean autonomous end-to-end recovery. |
| L16 repeated recheck | Two live same-task recaptures | **7.986 s / 8.011 s**, each one row, unchanged submit attempts and no new dispatch | Live idempotent recheck evidence for two cycles. |
| Final clipboard-freshness recheck | Live recapture on the final installed build | **7.296 s** event span; capture increment **6.544 s**; full 1,587 characters, one row and no new dispatch | Live timestamp-guard/correction evidence; not another fresh research request. |
| Earlier Mongolia public group-trip research/stay request | Live run, earlier capture path | **False completion**: saved only the final 2,889 characters, while earlier observed answer blocks already exceeded 4,000 characters | Whole-answer acceptance failed. No stay app/search ran. |
| First recheck recovery attempt | Autonomous live recovery attempt, then operator interruption | Gemini reopened at normal blank home; after roughly 120 s the operator moved Home to interrupt. Task became `OUTCOME_UNKNOWN`; no question was resent. | Recovery failed to locate the existing conversation. Avoiding resend is correct but does not complete recovery. |
| Subsequent full-answer correction | Assisted live capture/correction | Original conversation manually reopened and slightly scrolled; Resume copied **8,794 characters** covering 10 observed response TextViews and replaced the old result. | Verified capture/publication after assisted setup; not autonomous conversation recovery. |
| Wider live matrix | Mixed live regression evidence | L01/L06/L08 passed valid scopes; L04 valid isolation/short capture passed; L02/L05 capture passed but requirement checks failed; L03 failed pre-fix; Writer cases blocked | Keep capture, task requirements and setup separate; no overall pass rate. |

The 120 s recovery observation is approximate, not a complete measured phase breakdown. The outcome is retained as unknown; it is not recorded as a successful Stop/resume cycle.

## Retained timing of the false-completion run

| Recorded phase | Duration |
|---|---:|
| Prepare | 6.960 s |
| Submit | 7.004 s |
| Capture, including waiting | 99.276 s |
| Publish | 0.294 s |
| Sum of active segments | **113.534 s** |
| `PLAN_SAVED` → `TASK_COMPLETED` event span | **113.578 s** |

These timings describe a failed acceptance attempt. The old `TASK_COMPLETED` event is a false-positive completion signal, not proof of complete research. Fresh exact-question correlation did not prevent the trailing-fragment error. A corrected result and fresh successful repeats need their own measurements.

## Verified correction after manual setup

The final build is installed on the Pixel. It includes scoped response Copy, fresh clipboard-timestamp binding, shared busy control, bounded scrolling, short-answer acceptance, keyboard dismissal and memory metrics. Its coordinator suite passes 24/24, all four focused fixture groups pass and the full 18-group selfcheck passes. Rejection diagnostics retain lengths/footer status rather than raw copied excerpts.

After manually reopening the original Gemini conversation and scrolling slightly, Resume completed the existing task with `full_response: true` and `capture_method: provider_copy_response`. Verification found:

- **8,794 answer characters**, replacing the earlier 2,889-character fragment; 10 response TextViews were observed.
- Exactly one transcript row for the task, still in its original Friday chat.
- The transcript answer hash matches the retained answer; the completion marker is removed.
- The Submit attempt count remains **one**, preserving the original verified submission checkpoint.
- The replacement uses result revision two, preserving one visible chat turn.

The latest capture attempt took **5.642 s** and publication took **0.033 s**. These are incremental attempt durations after manual setup, not the original request duration or autonomous recovery time. Earlier capture waits remain in cumulative step timings.

This corrects the known partial publication. It does not pass fresh Research, automatic history navigation, restart recovery, semantic-answer quality or a hotel search. No stay app was searched.

## Research attempt observations

| Case | Observed request wall time | Recorded active steps | Outcome |
|---|---:|---|---|
| L01 | **46.618 s** | Prepare 6.699 s; submit 6.511 s; capture 32.811 s; publish 0.043 s. Active total 46.064 s. | **Standalone autonomous pass:** 490-character complete answer, 77 words within 80, matching hash, one row and one verified submission checkpoint. Fresh-chat setup invalid. |
| L03, before short-answer fix | **31.661 s** | Prepare 7.313 s; submit 7.171 s; capture 16.952 s; no publication. Active total 31.436 s. | **Failed capture / `OUTCOME_UNKNOWN`:** no transcript result; one Submit attempt. |
| L02, initial batch | **116.052 s** | Prepare 6.125 s; submit 7.405 s; capture 102.193 s; no publication. Active total 115.723 s. | **Technical outcome unknown; fresh-chat setup invalid:** Copy unverified, no result, one Submit attempt. This is not isolation or generation-quality evidence. |
| L02, verified fresh-session retest | **26.308 s** | Prepare 5.491 s; submit 6.692 s; capture 13.243 s; publish 0.066 s. Active total 25.492 s. | **Capture/transport passed:** 1,178 characters, one result, one verified submission checkpoint and matching hash. **Word-limit requirement failed:** 156 whitespace words versus 150. |
| L04, valid Chat B retest | **226.689 s** | Prepare 5.813 s; submit 7.033 s; capture 212.436 s; publish 0.112 s. Active total 225.394 s. | **Passed:** exactly `unknown` (7 characters), whole provider Copy, full-response evidence, one row, one verified submission checkpoint and matching hash; no label from the distinct original chat. |

L03 exposed a guard that rejected the expected short label because it required a substantive anchor of at least 24 characters. The patch permits nonempty short answers while retaining the exact marker, known whole-response Copy target and observed-anchor checks. It was absent from the initial attempts and is installed for the valid retests; **the exact L03 case was not rerun**. The earlier L02 unknown Copy result has no conclusive root cause. A later passing capture does not establish which change resolved that sample.

A Submit attempt is a persisted logical step, not a physical tap count. An accessibility Send click and a guarded gesture fallback can create two `DISPATCH_INTENT` events within one attempt. Fresh-run attempt counts therefore establish a verified submission checkpoint, not one physical Send. The no-new-dispatch checks during Resume/recheck are separately verified.

At approximately 98 s during L02, a screenshot showed Gemini's generation spinner and Friday's working timer. This verifies visible progress during that observation, not eventual success. The subsequently saved record above reports an unknown capture outcome; no cause beyond unverified whole-response Copy is assigned here.

L04's valid retest still showed Gemini's **Stop generating response** control at approximately 105 s. The long wait therefore included real provider generation. Its 212.436 s capture phase also includes stability checks, UI observation and Copy overhead; it is **not pure model inference time**. The accepted seven-character answer validates the installed short-answer fix for this case.

During active Research, a concurrent `Run: Open Clock` request was rejected in 6 ms and foreground stayed Gemini. Opt-in own-window diagnostics exposed Friday's working caption and Stop control. The elapsed timer was verified visually in screenshots; it is intentionally excluded from accessibility and its absence from that tree is not missing progress.

### Harness setup correction: intended chats were not distinct

The first `newchat()` helper trusted gesture dispatch without verifying that selection changed. Saved task records show **all initial L01–L05 attempts used the same existing Friday conversation as the earlier Mongolia attempt**. L01 did not start a fresh Chat A; only its standalone science research/capture outcome remains valid. L03 remains a valid same-chat test. L02 did not exercise a fresh Chat B, although its technical research/capture measurements remain usable.

L04 returned `ATLAS-731` after 32.561 s, but it was still in the conversation that supplied that label. Its initial isolation test is **setup-invalid**, not a product failure or pass. No privacy leak is inferred. The valid Chat B retest below supplies the separate isolation evidence.

L05 also used that same chat. Its fresh-chat qualifier is invalid. Standalone whole-answer capture passed at 40.906 s: 1,952 characters via provider Copy, one result, one verified submission checkpoint and a matching hash. It returned six **bullet** sections rather than the requested numbered sections, so the format requirement failed. Its 307-word length violates no specified word limit.

The long existing transcript required **more than 15 scrolls** before the actual New chat control became available onscreen. The corrected helper requires a visible target with current bounds and verifies `task_state.task == null` after selection. A click returning success is not sufficient setup evidence. A subsequent installed UI fix moved New chat/History above the transcript. Live checks verified stable header placement before/after scrolling and with IME, creation of an empty selected chat, and History restoration of the original conversation with its 8,794-character result preserved. That fix does not retroactively validate the initial harness setup.

The completed L02 retest had empty task state verified immediately before execution, and its new chat identity differs from the original conversation/initial batch. Capture/transport passed, but its 156-word answer exceeded 150. L04 then ran in that verified Chat B after SQLite and returned exactly `unknown`. This is valid label-isolation evidence for the tested A/B pair, unlike the setup-invalid initial L04.

## Recovery cases: initial failure, installed fix and successful retry

| Case | Procedure and evidence | Timing | Outcome |
|---|---|---|---|
| L14 | Friday's real UI Stop was pressed while the provider was generating. Cancellation persisted; zero transcript results; no late publication during the following 3 s. An independent Open Clock request succeeded afterward. | Initial request observation 15.151 s; Stop → terminal approximately **0.902 s**. | **Pass for the observed Stop case.** |
| L15, initial attempt | Friday was force-stopped after verified Submit. Recovery retained the same task/chat, `RECOVERED` event and prepare/submit attempt counts. No new `DISPATCH_INTENT` appeared. Resume could not verify whole-response Copy and published no row. | **66.035 s**, including operator/script repair delay; **not autonomous end-to-end time**. | **`OUTCOME_UNKNOWN`; resumed completion failed.** |
| L15, same-task retry after fix | Installed the IME-aware collector, then resumed the existing task without another prompt. Final answer has 1,587 characters via provider Copy/full-response evidence. Same task/chat and prepare/submit counts, no new dispatch, one row and `RECOVERED` all verified. | Successful retry **8.372 s**. Overall sequence **263.613 s**, including force-stop, restoration error, diagnosis/build/install. | **Assisted failure → fix → successful Resume.** Not a clean autonomous timing or conversation-locator pass. |
| L16, cycle one | Rechecked that same completed task. Submit attempts unchanged, no new dispatch, one row. | **7.986 s** | **Pass for this recheck cycle.** |
| L16, cycle two | Repeated the same-task recheck with the same safeguards. | **8.011 s** | **Pass for the second cycle; no duplicate turn.** |
| Final clipboard-freshness check | Rechecked the same task on the final installed build. Full 1,587-character answer, one row and no new `DISPATCH_INTENT` verified. | Event span **7.296 s**; capture increment **6.544 s**. | **Pass for the live timestamp-guard recheck.** |

The force-stop removed Friday's accessibility binding. The fixture's first restoration script failed on an empty settings argument (`Bad arguments`); the operator then restored only Friday's service without changing other services. That repair delay is a setup issue, not research inference time. The initial checkpoint checks alone did not establish completion; the later successful retry is recorded separately.

The initial resumed Copy attempt encountered a visible keyboard. Generic swipes began inside the IME, so two scrolls did not reach the toolbar. The installed collector dismisses visible IME once, verifies qualified Gemini remains and the keyboard closes, then locates Copy. The same-task retry and both L16 cycles passed. The original failed resume remains recorded as `initial_recovery_outcome`; intervention/build time is not hidden by the successful retry.

## Current pain points

1. **No durable conversation locator.** Retaining the exact question, marker and verified submission does not reopen that conversation. Launching Gemini can return to a blank home screen. Recovery needs a supported conversation locator or bounded history navigation followed by exact question verification; it must preserve the existing no-resend guard.
2. **Wrong conversation can look like a slow answer.** Waiting on blank home consumed roughly two minutes without collecting the saved answer. Distinguish missing conversation, wrong foreground and active generation in both state and progress messages.
3. **A complete final block can omit earlier blocks.** Whole-response Copy and observed-block coverage corrected this answer after assisted setup. Fresh cases still need to establish repeatability across short, long and differently rendered answers.
4. **Correction must preserve one visible turn.** Mongolia correction preserved one row and unchanged Submit attempts. L16 and the final freshness recheck also verified no new dispatch. Broader clean restart/replay coverage remains needed.
5. **Memory availability prevents ordinary text tasks.** L09–L12 and all 13 generation-capable Writer fixtures were rejected before native loading. Their short rejection times do not measure model speed; measured RAM and the unchanged headroom floor are detailed below.
6. **New chat was difficult to reach in a long transcript; fixed and verified.** More than 15 setup scrolls were needed. The installed header now keeps New chat/History above the transcript, with live IME/scroll/session-switch checks passing. The harness must still verify the selected session rather than trust a gesture.
7. **Keyboard state changes scrolling targets.** L15's initial resume failed when swipes started in IME. The installed keyboard-aware fix passed the same-task retry, but a clean from-start interruption test is still needed.
8. **App-boundary population and verification need separate checks.** Gmail's initial empty subject/body were fixed by URI encoding and verified visually without field edits. Its accessibility snapshot still lacked an app root, preventing autonomous readback; the tree failure's cause remains unestablished.

## Maps and Gmail handoffs

| Case | Observed wall time | Evidence | Result |
|---|---:|---|---|
| L07 Maps | **2.638 s** | Maps foreground and requested Cubbon Park visible. | **Pass for place search**, beyond app launch. |
| L13 initial Gmail handoff | **4.721 s** | Exact synthetic preview approved; Gmail composer visually opened with `qa@example.test`, but subject and body were empty. Friday observed null package/no app accessibility root. | **Incomplete/unverified handoff**, correctly not claimed as populated. No Send. |
| L07 final Maps repeat | **3.424 s** | Maps foreground and requested place visible. | **Pass for the repeated place search.** |
| L13 final URI handoff | **4.868 s to Friday's unverified return** | A subsequent operator screenshot confirms `qa@example.test`, subject `CHECK-73` and exact body `Please review CHECK-73 by 28 October 2026 at 5 PM.` No manual field edits. Package/tree remained unavailable. | **Transport/population fixed with assisted visual evidence. Autonomous readback unavailable; no full automatic pass.** Draft remains unsent; field-population time was not measured. |

The final installed handoff puts percent-encoded subject/body in the `mailto:` URI while retaining the original extras; encoding fixtures pass. See the [email handoff format and limits](../docs/app.md#email-draft-handoff). The reason Gmail's app tree was unavailable is not conclusively established; service configuration and D-014 remain unchanged. Friday correctly retained an explicit unverified outcome rather than treating visual population as autonomous readback. The earlier empty-field failure remains part of the record. Pixel was returned to Friday; nothing was sent.

## Writer availability: latest 20-fixture run

The full Pixel Writer suite recorded **7/20 mechanical passes**, consisting solely of seven expected invalid-input rejections. The other **13 cases returned `unavailable` / `memory_pressure`**. There were **zero accepted outputs and zero native generations**; no generation-quality score or inference-latency conclusion follows.

| Preflight observation | Value |
|---|---|
| Available memory across 13 samples | **1,298.2–1,307.0 MiB** |
| Unchanged Writer minimum | **1,500,000,000 bytes**, approximately **1,430.5 MiB** |
| OS `lowMemory` | `false` |
| OS low-memory threshold | **226,492,416 bytes** |
| Selected model file | **695,755,488 bytes** |

OS `lowMemory: false` and Writer's headroom requirement are different checks. Model file size is not peak working memory. Profiling normal-phone availability and actual inference memory remains necessary before evaluating guard/model-policy changes. Earlier semantic-generation observations retain their historical scope; this run did not generate anything.

The final installed build passes clipboard freshness-boundary fixtures and a live same-task recheck: full 1,587 characters, one row and no new dispatch, with a 7.296 s event span and 6.544 s capture increment. This establishes that check's observed behavior, not Writer availability or a new fresh-research pass.

## Live case recording rules

- Record each case as **not run**, **autonomous pass**, **failed**, **blocked/unavailable**, **pending/unknown**, or **assisted diagnostic**. A truthful pending outcome can pass a negative-case check without completing its user task.
- Save step and total timings, app/response evidence, full captured length, terminal reason and intervention count for each attempted case.
- A package launch, successful tap, matching footer or completed synthetic suite is insufficient for a full-response pass.
- A corrected old result does not substitute for fresh arbitrary-subject research. Verify both and keep repeat counts explicit.
- Manual conversation selection may test capture/correction after assisted setup; it cannot establish automatic restart recovery.
- Verify fixture setup before interpreting results. A failed chat switch invalidates an isolation test, even when the research itself executes correctly.

## Wider live matrix: L01–L16

These are public synthetic fixtures from the parallel test-design pass. The earlier Mongolia diagnostic does not count as executing L15/L16. **Research** exercises Gemini; **Run** describes the existing app/draft entry intent.

L06 and L08–L12 were executed through the real Assistant `ask` diagnostic with memory off and unattended mode, rather than by tapping Run. Their six fixture turns and the initial L01–L05 research attempts used the existing selected conversation, **not dedicated synthetic chats**. No approval-dependent handoff was required for the local outputs. The L02 retest uses a verified empty, distinct session; L04's valid retest follows in that session. Diagnostic Research omits the final overlay answer card, so it does not establish that card's UI behavior.

Local durations below are the report's invocation **wall times**, not model inference times. Two local cases passed; four were blocked by availability. They are not six successful generated-text tests.

| ID | Entry and request/procedure | Required evidence | Status |
|---|---|---|---|
| L01 | Research, fresh Chat A: `Explain why the daytime sky is blue in at most 80 words. The synthetic label for this question is ATLAS-731.` | Complete relevant answer returns once to Chat A; no travel routing. | **Fresh-chat qualifier invalid**; standalone pass 46.618 s, 490 characters, 77/80 words, one result, one submission checkpoint, matching hash |
| L02 | Research, fresh Chat B: `Compare SQLite and PostgreSQL for a small personal application in at most 150 words.` | Complete relevant comparison; no content from Chat A. | Initial setup invalid/unknown. **Valid fresh retest: capture/transport pass · 26.308 s**, 1,178 characters; **word-limit fail: 156/150** |
| L03 | Research, Chat A: `What synthetic label did I give in the previous question? Answer only the label.` | `ATLAS-731` from Chat A's owner-request context. This tests conversational context beyond durable task storage. | **Failed pre-fix · 31.661 s**; short-answer capture rejected, no publication; exact case not rerun |
| L04 | Research, Chat B: `What synthetic label did I give in the previous question? If none was supplied, say unknown.` | Unknown; no imported label from Chat A. | Initial setup invalid. **Valid retest pass · 226.689 s**; exact 7-character `unknown`, whole Copy, one row, one submission checkpoint, matching hash |
| L05 | Research, fresh chat: `Explain how rainbows form in six numbered sections, with one or two sentences per section. Start the answer with BEGIN-LIVE-73 and finish its main text with END-LIVE-73.` | Entire actual answer, including middle sections, agrees with provider whole-answer Copy. Boundary phrases alone are insufficient. | Fresh-chat qualifier invalid; **standalone capture pass · 40.906 s / 1,952 characters**; **format fail:** six bullets, not numbered sections |
| L06 | Run: `Open Clock.` | Clock foreground; no Gemini, alarm or timer creation. | **Pass · 0.685 s**; `com.google.android.deskclock` verified |
| L07 | Run: `Open Maps and search for Cubbon Park, Bengaluru.` | Requested place/search visible in Maps; launch alone is insufficient. | **Pass · 2.638 s**, final repeat **3.424 s**; Maps foreground and Cubbon Park visible |
| L08 | Run: `Write a message verbatim: REF-73: INR 1,240.50 total for 2 rooms, check-in 28 October 2026 and checkout 30 October 2026.` | Exact local draft; no invented recipient or outbound action. | **Pass · 0.097 s**; exact body preserved; no SLM inference |
| L09 | Run: `Rewrite in at most 40 words: INV-042: INR 1,240.50 is the total for 2 rooms, not the cost of each room.` | Amount, currency, quantity and total-versus-per-room meaning preserved; no invented tax/arithmetic. | **Blocked · 0.061 s**; Writer `unavailable`, zero load/decode |
| L10 | Run: `Summarize in at most 30 words: CASE-19: a meeting is being considered. Date, time, venue and attendees are undecided.` | Tentative status and unknown details preserved. | **Blocked · 0.049 s**; Writer `unavailable`, zero load/decode |
| L11 | Run: `Rewrite this: I has received PKG-78 and all 3 items is inside.` | Grammar corrected; identifier, receipt and quantity preserved. Copying the errors fails. | **Blocked · 0.047 s**; Writer `unavailable`, zero load/decode |
| L12 | Run: `Write a polite message saying I cannot attend REVIEW-42 on 28 October 2026.` | Faithful decline; correct identifier/date; no invented reason or promise. | **Blocked · 0.053 s**; Writer `unavailable`, zero load/decode |
| L13 | Run, Gmail available: `Draft an email to qa@example.test in Gmail about CHECK-73 saying exactly "Please review CHECK-73 by 28 October 2026 at 5 PM."` | After draft review, separately verify recipient, subject `CHECK-73` and exact body. Do not send. | Initial empty-fields failure retained. Friday returned **unverified after 4.868 s**; subsequent inspection confirmed fields without edits/send. Population time unmeasured; **not a full automatic pass** |
| L14 | Research: `Explain how photosynthesis works in three short paragraphs.` Press Stop during generation in a synthetic chat. | Persisted cancellation; no late publication/action; a new independent request works. | **UI Stop pass**; terminal in approximately 0.902 s, zero rows/late publication during 3 s, independent Clock succeeds |
| L15 | Same photosynthesis request in another synthetic chat. Interrupt Friday after verified submission, reopen and Resume. | Same question/receipt, no second Send, complete answer published once to origin chat. Distinct from owner cancellation. | **Assisted recovery success after initial failure/fix**; retry 8.372 s, 1,587 characters, one row; overall 263.613 s includes repair/build/install |
| L16 | Recheck a completed synthetic Research task through `recheck_task`; repeat recovery. | No new preparation/Send; atomic replacement leaves one result in origin chat. No visible Recheck button is assumed. | **Two live recheck passes · 7.986 s / 8.011 s**; one row and unchanged Submit/dispatch |

Suggested execution order: L06, L08–L12, L01–L04, L05, then conditional L07/L13–L16 as app state permits. App unavailability, unsafe setup or unavailable synthetic source material is a recorded block, not a pass or an instruction to use private content.

L06–L13 exercise legacy routes or text capabilities; they are regression checks, not proof that those capabilities have migrated to the coordinator. Semantic quality, exact payload fidelity, provider interaction and capture completeness receive separate judgments. General hotel planning and the broader 60-case catalog remain outside this matrix's M1 pass count.

## Open M1 acceptance

Capture, tested isolation, Stop, assisted Resume/recheck, final clipboard freshness and Maps have evidence. Gmail population is repaired with assisted visual verification, but autonomous readback remains unavailable. Writer generated zero outputs in the latest suite. L02's word limit and L05's numbering failed; the exact L03 case was not rerun. **Automatic conversation location, clean restart acceptance and content requirements remain open.** No hotel workflow, reservation or payment is established here.

Highest-priority next work:

1. **Writer availability:** profile actual peak memory and normal-phone headroom, then validate any model or guard change before another quality/latency evaluation.
2. **Exact provider-session recovery:** locate the saved Gemini conversation, verify its question/marker and resume without another dispatch; repeat a clean interruption test without manual navigation or restoration repair.
3. **Content constraints:** validate word limits, requested structure and required facts separately from complete capture. Run the exact L03 regression and fresh varied subjects through the same engine.
4. **Inaccessible-app verifiers:** provide a supported, freshly grounded readback method or retain the explicit unverified handoff. Visual population alone must not become automatic completion.

Related: [coordinator implementation and gates](../docs/COORDINATOR_PLAN.md), [earlier live failures and timing](36-nepal-live-flow-and-timing.md).
