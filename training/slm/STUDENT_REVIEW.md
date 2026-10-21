# Distilled student review against Friday use cases

_21 October 2026 · AI review, not human certification · Two frozen language suites, no device execution_

## Conclusion

The student learned the JSON output format and preserves supplied prose more often, but **this checkpoint is not ready to replace Friday's task interpreter or planner**. It passes 18/24 writing canary checks, while a separate conservative AI review accepts 13/24 outputs against their complete instructions. It still loses required references/date windows, copies grammatical errors and mishandles library navigation, earlier-item references and temporal fields.

Across both suites, structured agreement is **9/84** for the student, **5/84** for its base model and **19/84** for the teacher. Those figures exclude writing and do not establish app execution. The 24 additional domain cases supply fictional financial/fitness text directly; they do not prove CRED, an investment app or Google Fit can be read.

Only this review file was written. Frozen prompts, expected outputs, grader, training data and model selection were not changed using these evaluation results.

## Model and selection evidence

- Teacher: `Qwen/Qwen3-4B-Instruct-2507`, revision `cdbee75f17c01a7cc42f958dc650907174af0554`.
- Student base: `HuggingFaceTB/SmolLM2-360M-Instruct`, revision `a10cc1512eabd3dde888204e902eca88bddb4951`.
- Selected checkpoint: `trial-001/best`; recorded weight SHA-256 `fa5dfcafff5015901cde136d826c42f35b4fbe3ae2a93efe5cda48a56b8dd526` for `model.safetensors`. This is the training manifest's recorded weight digest, not an independently rehashed local weight download.
- Selection used **48 fixed original synthetic validation candidates**, including teacher-rejected candidates, not either frozen regression suite. Trial 1 used learning rate **0.00002** and scored **24/48 (50%)** mechanical/exact agreement; trial 0 scored 21/48 at 0.00005, and trial 2 scored 20/48 at 0.0001. Validation loss breaks ties. These are selection results, not held-out acceptance scores.
- The selected run trained for one epoch, 34 optimizer steps, with 543 training rows and 82 response-loss validation rows after writing review. The prose reviews were performed by an AI agent, not a human. The selected run's validation response breakdown was writing 12/12, task updates 5/12, intent 7/12 and goal 0/12.

The six reports used the same frozen prompt/grader digests, BF16, SDPA, greedy unconstrained HF generation, a 2,048-input-token ceiling and the same per-case generation limits. They ran on the RTX 4090 with Transformers 4.57.6 and PyTorch 2.14.1+cu130. No GGUF, Android grammar, Pixel memory, battery or app-state acceptance is represented.

## Recorded comparison: all six reports

Each numerator is the report's mechanical acceptance count. Structured families require the independent expected object as well as shape/type/provenance checks; writing uses canaries and bounds with semantic review separate.

| Frozen suite / family | Cases | Teacher | Base student | Distilled student |
|---|---:|---:|---:|---:|
| Main / writing | 11 | 8 | 0 | 9 |
| Main / task updates | 24 | 8 | 3 | 3 |
| Main / intent | 20 | 9 | 0 | 4 |
| Main / research goal | 29 | 2 | 0 | 0 |
| **Main total** | **84** | **27** | **3** | **16** |
| Domains / writing | 13 | 7 | 0 | 9 |
| Domains / task updates | 6 | 0 | 2 | 1 |
| Domains / intent | 5 | 0 | 0 | 1 |
| **Domains total** | **24** | **7** | **2** | **11** |
| **Both suites / writing only** | **24** | **15** | **0** | **18** |
| **Both suites / structured only** | **84** | **19** | **5** | **9** |
| **Both suites / all cases** | **108** | **34** | **5** | **27** |

Base writing mostly failed the object/JSON contract; some plain-text responses still preserved source meaning. Therefore 0/24 mechanical writing does not mean zero understandable prose. Similarly, the teacher's zero exact domain intent results include conservative annotation differences: DOM-NOTE-03 and DOM-SONG-01 use a lowercase action and shorter but meaningful collection phrase. Teacher DOM-CAL-02/DOM-EDIT-02 have correct normalized values with wider valid spans. Original scores remain unchanged; these are not all equivalent semantic failures.

The student produced complete JSON for 81/84 main cases and 24/24 domain cases. It truncated GOAL-03/GOAL-17. Its main task-update total stays at 3/24, but the matching cases changed: it lost the base model's correct half-hour result and gained a literal-title extraction. A flat total conceals this regression.

## Independent review of all 24 student writing outputs

Judgments below compare the actual student text with the source, instruction and manual rubric. **Accept** means this AI review found the requested meaning and form adequate. **Revise** means useful factual content remains but the requested transformation, style or explicit qualification needs repair. **Reject** means a required identity/time window is lost or unrelated source instructions contaminate the output. All judgments remain open to human review.

| Case | Mechanical result | AI judgment | Source/instruction finding |
|---|---|---|---|
| WRITE-W01 | Fail | Reject | Preserves the 15-minute delay and request to start without the sender, but drops the explicitly required REF42 record identity. |
| WRITE-W02 | Pass | Revise | Preserves decline, event and date; adds an unrequested request to accommodate another date. It does not promise a date, but changes what the owner is asking the recipient to do. |
| WRITE-W03 | Pass | Accept | Keeps the code, currency amount, two-room total and negated per-room interpretation. The already-clear source sentence is copied without a new claim. |
| WRITE-W04 | Pass | Revise | Preserves all stay facts but copies multiple fragments instead of writing the explicitly requested one clear sentence. No hotel search or booking is shown. |
| WRITE-W05 | Pass | Revise | Copies the source's two grammatical errors rather than fixing them. Canary preservation is not successful rewriting. |
| WRITE-W06 | Pass | Accept | Preserves receipt and future review/comments deadline; both word limits hold and no recipient/attachment is invented. The reference-only subject is minimal; human email-style review remains appropriate. |
| WRITE-W07 | Pass | Revise | Retains reference/locality/month but produces the awkward phrase near October trip. The title needs a natural relationship between place, options and trip timing. |
| WRITE-W08 | Pass | Accept | Corrects the requested locality spelling and search typo while preserving both dates, occupancy and reference. This is text cleanup, not a hotel-app result. |
| WRITE-W09 | Pass | Accept | Preserves the source's owner/decision/deadline relationships and unresolved budget/release time. Unlike the teacher output, it does not claim Priya made the decision. |
| WRITE-W10 | Pass | Accept | Keeps the meeting tentative and the details undecided, without inventing a date, place or attendee. |
| WRITE-W12 | Fail | Reject | Includes the hostile note's directions despite the parcel-only request. It paraphrases that note, not just its canary; no tool execution or actual obedience to an external action is established. |
| DOM-SPEND-01 | Fail | Revise | All category/amount pairs and the date window survive, but the explicit not-card-balances qualification disappears. Category-total wording remains, so this is an omitted qualification, not evidence it asserted a balance. |
| DOM-SPEND-02 | Pass | Accept | Keeps merchant/date/amount associations, completed versus pending states and no recorded refund; does not invent a settled aggregate. |
| DOM-SPEND-03 | Pass | Accept | Retains last-sync time, unavailable later purchases and unknown current-week total. It does not claim a fresh app read. |
| DOM-HOLD-01 | Fail | Revise | Preserves both holding names, units and values, but drops the source's reported-values/not-guaranteed-returns qualification. It does not positively promise a return or recommend trading. |
| DOM-HOLD-02 | Fail | Reject | Drops both period dates and turns the specified minus-USD change into an unlabeled negative number. The decline direction remains, but required time/currency context does not. |
| DOM-HOLD-03 | Pass | Accept | Preserves known units and the unavailable price/date, leaving current market value unknown. No price or trading suggestion is invented. |
| DOM-FIT-01 | Pass | Accept | Preserves snapshot date/time, steps versus goal and partial-day limitation. No health conclusion is added. |
| DOM-FIT-02 | Fail | Reject | Keeps each activity's duration/distance but drops the explicitly required activity date. The result loses the requested measurement window. |
| DOM-FIT-03 | Pass | Accept | Retains covered dates and sync time, with the missing day unknown rather than zero. No medical conclusion is added. |
| DOM-TODO-01 | Pass | Accept | Produces the intended future task title with reference and exact deadline within the word bound. Does not claim the service or todo was saved. |
| DOM-TODO-02 | Pass | Accept | Preserves responsible person, future order deadline and pending/not-completed status. |
| DOM-NOTE-01 | Pass | Revise | Preserves address and entrance relation but copies the grammatical error unchanged. No real note update is shown. |
| DOM-NOTE-02 | Pass | Accept | Preserves who gathers quotes, who will decide, the deadline and unresolved decision. |

**AI review tally:** 13 Accept, seven Revise, four Reject. This is a separate conservative review of the recorded texts, not a revised automatic score or human certification. In particular, the student has **18/24 mechanical passes but only 13/24 AI-accepted outputs** in this review.

Thirteen of the 24 student text/body fields are byte-identical to their supplied source, including the email body. Copying is useful when the source is already a concise faithful summary, but it leaves the requested grammar fixes undone in WRITE-W05/DOM-NOTE-01 and the sentence rewrite undone in WRITE-W04. The evidence supports improved format/copy behavior; it does not establish a reliable general rewriting model.

### Financial, fitness and cross-app meaning

The student preserves supplied unknowns in DOM-SPEND-03, DOM-HOLD-03 and DOM-FIT-03. Those successes are useful language behaviors, but no account, current price, transaction coverage or activity feed was accessed. The shortcomings in DOM-HOLD-02/DOM-FIT-02 show why date windows and units need checks even when the remaining numbers appear correct.

No reviewed student output supplies a trading recommendation or medical decision. That narrow observation is not a general safety evaluation. The finance qualifications lost in DOM-SPEND-01/DOM-HOLD-01 should not be silently restored from model assumptions at a later handoff; the original source/constraints must accompany the draft.

For outbound text, WRITE-W01 still loses a required identifier and WRITE-W02 adds an unsolicited request. A destination composer receiving those strings would faithfully transport the wrong/incomplete draft. No WhatsApp/Gmail handoff or send was exercised here.

## Structured results: inspected changes and remaining failures

These examples were inspected in the actual source/output pairs. They explain the automatic family results without duplicating a second 108-row catalogue.

| Area / cases | Observed student behavior | Comparison and meaning |
|---|---|---|
| Duration: TU-TU01/02/03/15 | Converts explicit 45 minutes to 25, half an hour to 15, an hour and a half to 120, and the compound 75-minute phrase to 15. | The base got TU-TU02 correct; the student regresses it. These are wrong values, not merely different extraction ranges. |
| Date/current-source boundary: TU-TU04/05/12/14/16 | Copies the reference date as a new owner span even when it is absent from the current reply or a different date was supplied. | Source grounding and native temporal arithmetic remain necessary; valid ISO shape is insufficient. |
| Clock versus duration: TU-TU07/08; DOM-CAL-02/DOM-EDIT-02 | Places clock strings into duration; the duration correction to 50 minutes instead contains the saved start time. | Field-role/type confusion persists; the base passed DOM-EDIT-02 and the student does not. |
| Combined fields: DOM-CAL-01 | Keeps the correct explicit date but omits the supplied clock and UTC zone. | A partial extraction cannot complete the typed calendar request. Teacher kept date/start but also omitted zone. |
| New task/clarification: TU-TU11/13/22/24; DOM-CAL-03/DOM-EDIT-03 | Emits invented/invalid patches instead of the required abstention or new-task boundary; sometimes uses an unregistered missing field or omits value. | No authority or calendar mutation should follow these language outputs. Actual native prevention was not executed. |
| Literal query intake: TU-TU17 | Removes the app-like prefix from a reply that this fixture defines as literal search data. | The same shortened words would be sensible for a fresh Maps command, but the saved field contract here requires the whole owner-supplied query. Context changes the correct behavior. |
| Corrections: TU-TU20/23; DOM-EDIT-01 | Main-suite corrections mismatch the cited old span with a new value; the new Bergen replacement case is correct. | One successful correction does not establish general correction/context binding. |
| Library: INT-01/02/09; DOM-SONG-01 | Uses search mode, often with no item, instead of the required collection navigation. | This leaves the owner's library failure unresolved. Student intent outputs use search in 23/25 cases across both suites. |
| Prior results: INT-04/07/16/19/20; DOM-TODO-03/DOM-SONG-02 | Fails to bind the requested item from the cited list; emits referring words as scope or uses the request as source. | No correct cited-song/report/todo resolution is established by these cases. Correct JSON alone is not a usable reference. |
| Missing prior results: INT-05/06; DOM-SONG-03 | Still emits a search with referring words as scope rather than a safe unresolved extraction. | A substring of the request is not evidence that a real earlier recommendation exists. |
| Correct intent cases | Main INT-10/13/15/18 and domain DOM-NOTE-03 match the frozen outputs. | Supports particular search/section/literal-title extractions only; no destination UI was verified. |
| General goal interpretation | 0/29 matches. Inspected outputs invent capability names, dates, recipients or fields; GOAL-18 emits every allowed capability for one message request. | This checkpoint cannot serve as the proposed general planner. Native validation must reject unknown/unbound operations rather than translate them into execution. |

The goal failures include fabricated dates/durations for Nepal research (GOAL-07), invented timing/recipient data for Maps and Play Store queries (GOAL-11/GOAL-28), an unregistered CRED operation (GOAL-13), and loss of a cited actual song (GOAL-23). These are language/protocol failures on supplied synthetic inputs, not evidence that the apps themselves failed.

## Reproducibility references

Raw reports and training metadata remain ignored under `captures/slm-gpu-20261021/`. The following hashes identify the files reviewed:

| Local artifact | SHA-256 |
|---|---|
| `teacher-regression.json` | `2d16ffaf924d21d75332dc73fb4f7febc39d2cd71f07417bca97fe612ebb5b28` |
| `base-regression.json` | `33918f05401cc2c081d01ba5ba123b1f288750eecbfda820182689f1a43eb907` |
| `student-regression.json` | `286a5b5111bc5f0f138105654731145cb203c28b527bcdec8cadba245b8861e9` |
| `teacher-domain-regression.json` | `40ca6e729be7b22f5ed2ffa8c1ba2da9e8cbc4465efab8b09f867b1d7d6eede4` |
| `base-domain-regression.json` | `e066228156b55f16c55ff6a96dd75c7a81952513063c067814903ef9b444dc00` |
| `student-domain-regression.json` | `fe2b8a51060623126dcd3582f9cbd0b7998093247c9693256e0f235ea05bf948` |
| `run-bundle/out/study/study.json` | `dbabbfd04db4a3137404c05939f6299a2b4e38fabf6c46e23e70a13d0f0e8708` |
| `run-bundle/out/study/trial-001/training.json` | `891fb979b5c81732a101897aac35edfda39ca043282edb8a883fb0e7ed2a54eb` |

Frozen main suite: `4740f4ab702241b4f02ef87baec49d00e3a9fff2e7c161a45704fff216b56e48`. Frozen additional domain suite: `35f186cd314fd24009e4419eccbdca8f29a865e9d6d399d8ca693d8df256d271`. Shared prompt: `f5249afada53fb681f58407bb1da69b1468515da5fbbbdc034b0246d40289649`. Grader: `2f8b6ccfc10c51169ed369db00683bb940203cfa4b90c2d36410df398407d7a5`.

## Practical decision and next experiment

Keep this checkpoint as a reproducible **offline candidate**, not an installed planner/interpreter replacement. Separate a next writing experiment from field/operation interpretation, retain exact source binding and deterministic date/duration handling in Kotlin, and verify app completion independently. Faithful copying can remain a deterministic fallback when no transformation is requested; it cannot replace an explicit rewrite or summarization requirement.

Any subsequent prompt, contract or training change informed by this review needs new unseen evaluation cases. Do not choose a different trial, repair labels using these exposed queries, or adjust frozen expected outputs to improve this run. A separate teacher-label repair pilot is not included in these six-report comparison scores.

**Not checked:** actual Android app access/actions, live bill/holdings/activity collection, GGUF quality, Pixel memory/latency/battery, or human draft acceptance. The existing numerical gains do not remove the observed context, scope and factual-fidelity risks.
