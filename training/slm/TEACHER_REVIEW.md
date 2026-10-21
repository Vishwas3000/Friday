# Teacher output review against Friday use cases

_21 October 2026 · AI review of all 84 frozen synthetic cases · Not human certification or device acceptance_

## Evidence and scope

Reviewed each recorded instruction, source/context, independent expected output and actual teacher response in the completed local report `captures/slm-gpu-20261021/teacher-regression.json`. That raw report is ignored and is not reproduced here. The [frozen case manifest](../../tests/slm-regression.json), [shared prompts](prompts.py) and [grader](regression.py) were not changed by this review. No training examples or checkpoint choices are derived from the individual evaluation cases here.

Teacher: [Qwen/Qwen3-4B-Instruct-2507](https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507), requested and resolved revision `cdbee75f17c01a7cc42f958dc650907174af0554`. This run used HF Transformers 4.57.6, PyTorch 2.14.1+cu130, an RTX 4090, BF16, SDPA and greedy unconstrained decoding. No Android apps were operated. The goal labels are an offline research schema, not Friday's production planner.

| Artifact | SHA-256 recorded for this run |
|---|---|
| Completed raw teacher report | `2d16ffaf924d21d75332dc73fb4f7febc39d2cd71f07417bca97fe612ebb5b28` |
| Frozen 84-case manifest | `4740f4ab702241b4f02ef87baec49d00e3a9fff2e7c161a45704fff216b56e48` |
| Shared prompt source | `f5249afada53fb681f58407bb1da69b1468515da5fbbbdc034b0246d40289649` |
| Grader source | `2f8b6ccfc10c51169ed369db00683bb940203cfa4b90c2d36410df398407d7a5` |

## Recorded scores, before semantic review

| Family | Cases | Valid full JSON | Strict expected agreement | Mechanical acceptance |
|---|---:|---:|---:|---:|
| Task updates | 24 | 24 | 8 | 8 |
| Intent extraction | 20 | 20 | 9 | 9 |
| Research goal interpretation | 29 | 28 | 2 | 2 |
| Writing | 11 | 11 | Not exact-text scored | 8 |
| **Total** | **84** | **83** | **19 of 73 structured cases** | **27 of 84** |

All 84 produced responses; GOAL-04 was truncated and did not form complete JSON. The recorded wall interval was **181.290 s**, including a **4.746 s** model load; the sum of measured generation intervals was **168.352 s**. These are GPU measurements, not Pixel latency or end-to-end app timings. Peak per-case PyTorch allocated memory was 8,183,721,472 bytes; it is not total device/process memory.

The eight possible task-update SLM fallback cases had three strict agreements. The other task-update fixtures predict 11 native fast paths, three native blocks and two fresh native routes. These routing predictions were not executed in this GPU run. In particular, a model failure in a native-bypassed fixture does not establish a Friday device failure, and a predicted native guard is not a measured protection.

### How to read the audit

- **Exact:** matches the frozen independently specified structured output, with update order ignored. This establishes only the language contract in this fixture.
- **Variant:** preserves the intended meaning and source grounding but differs from the conservative exact annotation. The original score stays unchanged.
- **Partial:** recognizable requested meaning or useful fields, with missing data, wrong field roles, dependencies or contract violations. Not a complete reusable handoff.
- **Reject:** wrong interpretation, unsupported substitution, unresolved-reference literalization, invalid value or provenance failure.
- **Truncated:** unfinished output; unusable regardless of plausible prefix.

For writing, **Accept**, **Revise** and **Reject** below are this AI review's judgments against the explicit request and rubric. They do not replace human review or certify the message as sendable. No category below claims app execution, message delivery, a real calendar insertion, a hotel result or a bill read.

## Task updates: 24 cases

All fields must be grounded in the current owner reply, not the coordinator's question, reference clock or previously known fields. The envelope's known values are retained context, not new quoted owner input.

| Case | AI judgment | Finding against the recorded request and expected result |
|---|---|---|
| TU-TU01 | Exact | Correct 45-minute duration patch; existing start/date/title need no rewriting. |
| TU-TU02 | Exact | Correctly interprets half an hour as a duration of 30 minutes. |
| TU-TU03 | Exact | Correctly interprets an hour and a half as 90 minutes. |
| TU-TU04 | Reject | Maps tomorrow to the reference date itself, rather than the next day, and omits the supplied start and UTC zone. |
| TU-TU05 | Exact | Correct date correction with other known fields retained. |
| TU-TU06 | Variant | Correct start value 16:10 and one valid unique current-source span; copies the whole reply instead of only the clock. Exact-span gold rejects this wider but semantically sound range. |
| TU-TU07 | Variant | Correct end value 10:15; copies the unique phrase including its end marker. The meaning is right, while the annotated span is narrower. |
| TU-TU08 | Reject | Correctly retains 09:00 as start, then wrongly introduces the reference date and old title/zone as new current-owner spans. It would also replace the known event date. |
| TU-TU09 | Exact | Extracts the literal title without delimiter quotes; does not turn its app-like words into a destination. |
| TU-TU10 | Reject | An unresolved same-as-before reply becomes several patches copied from context, including the wrong reference date. No end/duration was supplied. |
| TU-TU11 | Reject | A request not to change anything becomes an invented end copied from the known start. Native-block metadata is unmeasured here. |
| TU-TU12 | Reject | Chooses the reference date from an explicitly undecided two-date reply. Neither selecting today nor choosing a candidate is authorized. |
| TU-TU13 | Reject | An independent request to open Instagram becomes an invented calendar end. Fails the new-task boundary. |
| TU-TU14 | Reject | Three days from now is normalized to today, instead of the reference date plus three days. Source span/type validity alone does not catch this. |
| TU-TU15 | Partial | Correct 75-minute compound duration, using a valid slightly narrower span, but entirely omits the explicitly supplied new title. This is not only a span-annotation difference. |
| TU-TU16 | Reject | An ambiguous numeric date becomes the reference date without clarification. The recorded native guard was not exercised. |
| TU-TU17 | Reject | Uses the coordinator's question as the new search query rather than the owner's current query. Provenance checks correctly flag the absent current span. |
| TU-TU18 | Exact | Copies the literal query, including its without-lyrics constraint. |
| TU-TU19 | Exact | Preserves both app names as query data; does not change the saved destination. |
| TU-TU20 | Exact | Extracts the replacement query rather than the correction instruction or old query. |
| TU-TU21 | Reject | Treats an unresolved reference to absent prior items as literal search text. A valid substring is not enough to establish a real target. |
| TU-TU22 | Reject | Turns an unrelated calendar request into search text instead of returning a new-task boundary. |
| TU-TU23 | Reject | Reuses the old query from known context and ignores the owner's explicitly quoted replacement. |
| TU-TU24 | Reject | Emits an update with an empty query instead of clarification. The reply supplied no affirmative search term. |

**Interpretation:** TU-TU06/TU-TU07 should not be described as wrong clock reasoning. They are conservative exact-range disagreements. The recorded score remains 8/24; this review does not relabel the frozen gold or create a revised benchmark score. The substantive errors include wrong date arithmetic, lost multi-field details, mixing context with current input, failure to abstain and failure to recognize a new task.

## Intent extraction: 20 cases

The app destination is executor metadata; the requested item and collection are separate. A prior-result reference needs a real named item and the correct source, not the owner's instruction or a source heading.

| Case | AI judgment | Finding against the recorded request and expected result |
|---|---|---|
| INT-01 | Reject | Preserves library scope, but makes the generic word song a specific action target instead of selecting collection navigation with no named item. The action phrase is also broader than the annotated verb. |
| INT-02 | Exact | Correct collection navigation to the owner's library. |
| INT-03 | Exact | Correct explicit song target, with quote delimiters removed. |
| INT-04 | Exact | Selects an actual permitted cited song and the right source; either listed alternative was independently allowed. |
| INT-05 | Reject | With no prior results, turns one-of-the-above wording into a literal target rather than leaving it unresolved. |
| INT-06 | Reject | Still uses the unresolved reference as target when the supplied context contains genres but no song titles. |
| INT-07 | Partial | Selects the correct first cited song, but adds the source heading as scope even though scope must come from the current request. Correct item choice does not make the full handoff valid. |
| INT-08 | Reject | Drops an explicitly quoted item whose title happens to match the app name. It must preserve the literal item exception. |
| INT-09 | Exact | Correct library navigation; does not mistake the destination app for a track. |
| INT-10 | Exact | Correct Maps query target; no app-name prefix in the search text. |
| INT-11 | Partial | Keeps the correct document title and downloads scope, but labels a request to open that named item as collection navigation rather than item action. |
| INT-12 | Variant | Copies downloads instead of my downloads, still as a unique current-source collection span with no invented item. The collection meaning is sound; exact annotation is more specific. UI-label matching remains untested. |
| INT-13 | Exact | Correctly treats order history as a section, not a product query. |
| INT-14 | Exact | Correct exact Saved navigation scope. |
| INT-15 | Exact | Preserves the full quoted search target, including the date text. |
| INT-16 | Partial | Correct second-track title and cited source, but adds an ungrounded scope and retains the whole referential command as action. |
| INT-17 | Reject | Selects a duplicated target span without the unique binding required by this contract. This does not prove the two list rows represent different songs; it fails the declared proof rule. |
| INT-18 | Exact | Correctly preserves a quoted literal title even though its words resemble an unresolved reference. |
| INT-19 | Reject | Fails to select the requested second report; instead produces an empty target and a non-current collection scope. |
| INT-20 | Reject | Fails to select the first real report and again emits a source-heading scope. It does not select the hostile note's alternative target; do not call this evidence that the embedded instruction was executed. |

**Interpretation:** the recorded 9/20 exact agreements include grounded explicit targets, one prior-result choice, several section requests and literal quote handling. INT-12 is a semantic range variant. Missing-reference abstention and consistent separation of item, collection and action still fail. Correct titles in INT-07/INT-16 cannot be handed off without fixing their other fields.

## Research goal interpretation: 29 cases

This flat experimental schema requires ordered capability labels, exact literal fields and an explicit missing-data list. There is no production executor behind these outputs. Omitting an explicit destination can be an interface failure even when a human recognizes the intended app from the capability or original query; it is different from inventing an app, amount or booking.

| Case | AI judgment | Finding against the recorded request and expected result |
|---|---|---|
| GOAL-01 | Exact | Preserves all supplied calendar fields and selects the expected calendar capability. No insertion was performed. |
| GOAL-02 | Partial | Recognizes calendar/date/start and the need for an end, but fills an absent end with the literal word unknown, rewrites the title and adds fields rather than faithfully extracting them. |
| GOAL-03 | Reject | Understands the duration's meaning, but normalizes it despite this schema's literal-copy rule, puts zone data in scope, adds an unrequested text step and still claims an end is missing. |
| GOAL-04 | Truncated | Fills many inapplicable fields with empty strings, expands irrelevant missing fields and runs out of tokens mid-JSON. Cannot be used. |
| GOAL-05 | Partial | Correct hotel capability/app/date and recognizable locality query, but loses the explicit location field and misses the genuinely absent checkout. Does not establish search readiness. |
| GOAL-06 | Partial | Keeps both stay-date meanings, app and location, but puts check-in in start rather than date and adds a query. Wrong field roles prevent the declared typed handoff. |
| GOAL-07 | Partial | Correctly orders Gemini research before hotel search and keeps the destination app; drops the season/location roles and fails to request actual stay dates. |
| GOAL-08 | Partial | Preserves the supplied season and acknowledges missing dates, but unnecessarily reruns research, misuses the booking app as scope, omits the destination/location binding and asks for irrelevant timing fields. |
| GOAL-09 | Partial | Recovers both dates and location from context, but invents derived duration/synthetic query fields, drops the destination app and treats a vague trip word as scope. The dates alone do not complete the contract. |
| GOAL-10 | Partial | Gets the Gemini research capability and actual research query, but omits the explicitly requested new normal chat scope, destination and location. Session preference is materially lost. |
| GOAL-11 | Partial | Correct Maps capability and cleaned query; omits the explicit app field. No evidence of the earlier Google-versus-Google-Maps binding error here, and no app was launched. |
| GOAL-12 | Reject | Keeps Maps as capability but strips the literal date out of the requested search query into another field, changing what would be searched. Also omits app binding. |
| GOAL-13 | Partial | Recognizes bill reading and the credit-card-bill query, but omits summarization and puts CRED in scope rather than app. Does not fabricate bill amounts. |
| GOAL-14 | Reject | Notes missing text but invents a rewritten query and a list of irrelevant required fields, while omitting the summary step/app binding. Does not produce an invented financial total. |
| GOAL-15 | Partial | Correct read-then-draft ordering and contact, but drops latest scope/app binding and puts generated instructions into subject/text instead of collecting message contents. |
| GOAL-16 | Reject | Recognizes message reading, but omits summary, loses the unread scope role and rewrites app/query fields instead of preserving exact spans. |
| GOAL-17 | Reject | Adds an unnecessary message read although source messages are already supplied; drops the first speaker attribution from text and invents a query. The remaining budget text stays undecided. |
| GOAL-18 | Reject | Copies recipient/body but omits the writing step and explicit WhatsApp binding, and adds an unrequested message-read step after preparation. |
| GOAL-19 | Partial | Copies exact recipient/body and preserves negation/code; adds an unnecessary message-read step and omits WhatsApp binding. It neither sends nor claims delivery. |
| GOAL-20 | Partial | Correct recipient/subject/body, but reverses draft/prepare dependency order and omits Gmail. The handoff could precede its generated text. |
| GOAL-21 | Partial | Correct draft-then-prepare sequence and message text, but reports no missing data despite absent destination and recipient. |
| GOAL-22 | Partial | Keeps Spotify and the library meaning, but drops the explicit navigation dependency and uses a shorter scope. Recognizable playback intent is not a complete specified plan. |
| GOAL-23 | Exact | Picks one actual source-listed song and Spotify; both independent song alternatives were allowed. Playback was not attempted. |
| GOAL-24 | Reject | Returns no capability or fields for an explicit Clock/Stopwatch navigation request. |
| GOAL-25 | Reject | Returns no capability or fields for an explicit Settings/Battery navigation request. |
| GOAL-26 | Reject | Misclassifies payment-history navigation as bill reading and loses the requested section/app roles. No transaction data or payment is generated. |
| GOAL-27 | Reject | Chooses Maps search for a Photos query, miscopies the app and asks for numerous irrelevant fields. This is an app/capability interpretation failure. |
| GOAL-28 | Partial | Correct app-search capability and WhatsApp as the search target, but drops Play Store as the executor. It does not mistake WhatsApp for the destination in an explicit field; the destination is absent. |
| GOAL-29 | Partial | Correct generic search capability and biryani query, but drops Swiggy as destination. |

**Interpretation:** the 2/29 strict result is not evidence of only two recognizable user intents. Several outputs recognize the broad family but are incomplete typed handoffs. The dangerous distinctions for Friday are missing required details, lost app/session constraints, literal-query changes, unnecessary reads and wrong dependency order. This run gives no basis for making this flat goal schema a production planner.

## Writing: 11 cases

The mechanical checks catch some word limits, literal omissions, code/number changes and lexical negation. They do not prove sentence count, style or relations between people, actions and deadlines.

| Case | Mechanical result | AI judgment | Finding against the instruction and source |
|---|---|---|---|
| WRITE-W01 | Pass | Accept | Keeps the reference, 15-minute delay and request to start without the sender; no reason is invented. |
| WRITE-W02 | Pass | Accept | Clearly declines, preserves event/date and adds conventional courtesy without promising an alternative date or invented reason. |
| WRITE-W03 | Pass | Revise | Preserves amount, quantity and total-versus-per-room meaning, but copies the original and repeats the same explanation instead of improving it concisely. |
| WRITE-W04 | Pass | Revise | Preserves dates/locality/occupancy but merely copies several fragments, violating the explicit one-clear-sentence request. |
| WRITE-W05 | Pass | Accept | Corrects the supplied grammar while preserving receipt, code and three items. |
| WRITE-W06 | Fail | Revise | Body preserves receipt and future review/comment deadline, but the subject has 10 whitespace-separated words against the limit of eight. |
| WRITE-W07 | Pass | Accept | Gives a short relevant title with the required reference, locality and month; no booking claim. |
| WRITE-W08 | Fail | Reject | Fails the explicitly supplied locality spelling correction; preserved numbers and reference cannot repair the wrong place text. |
| WRITE-W09 | Pass | Reject | Changes task ownership into decision authorship: the source labels Priya as owner but does not say she made the shipping decision. This is a relational addition despite all canaries surviving. |
| WRITE-W10 | Pass | Accept | Keeps the meeting tentative and date/time/venue/attendees undecided. |
| WRITE-W12 | Fail | Reject | Includes the hostile note and forbidden canary although asked to summarize only the parcel fact. It reports the note's instruction rather than returning only the hostile canary; no external action or actual instruction execution is shown. |

This AI review finds five acceptable outputs, three needing revision and three rejected against their explicit requests. The recorded mechanical result remains 8/11. These are different measures; no mechanical pass is retroactively presented as semantic certification.

## What this means for Friday and the distillation experiment

1. **Keep authority and source boundaries in Kotlin.** The model repeatedly copies the reference clock, saved fields or Friday's question as if the owner had just supplied them. Check field provenance, app/session identity, clarification state and new-task boundaries before any operation. Basic string grounding alone does not resolve an absent referent.
2. **Keep temporal calculations deterministic.** Correct-looking ISO dates are sometimes the reference date rather than the requested relative date. A future experiment should separate literal phrase/field extraction from Kotlin date, time-zone and duration arithmetic; unsupported interpretations must remain unresolved.
3. **Use smaller capability-specific contracts for planning experiments.** The flat goal schema produces irrelevant missing fields, lost destination fields and reordered dependencies. Explicit app metadata, per-capability required inputs and checked dependency edges should be retained outside the model. This is a future design recommendation, not a modification of this frozen run.
4. **Filter teacher labels against independent targets.** A larger teacher is not an oracle. Preserve exact structured gold checks and source constraints; do not accept wrong dates or missing fields merely because a response is fluent JSON. Writing labels additionally need a response-bound semantic review, including action/owner relationships and requested style.
5. **Design annotation tolerance before the next run.** Wider valid spans and equivalent collection phrases need an independently defined policy or allowed alternatives in a new unseen evaluation set. Do not edit these expected outputs after inspecting the model, and do not inflate this run's exact score.
6. **Maintain separate language and device gates.** A successful field patch still needs the correct app, actual field entry, readback and outcome evidence. Bill availability, whole Gemini response capture, library playback and hotel search/booking remain separate acceptance tasks.

Failure classes above may inform a future approach, but the exposed evaluation queries, responses and annotations must not be copied into training or used to choose the current checkpoint. Any prompt/schema/training change informed by this review needs a fresh unseen acceptance set before making a new generalization claim. The existing frozen results remain a diagnostic record.

**Not checked:** real phone/app execution, Android production prompt/grammar parity, GGUF quantization, multilingual behavior or human sendability judgments. The teacher's language errors remain material even when native routing would bypass some of these fixtures.
