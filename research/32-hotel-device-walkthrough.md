# Hotel search on the connected Pixel: observed checkpoints

Date: 2026-10-20. Device: connected Pixel 8, Android 16. App: the existing installed Friday and MakeMyTrip (`com.makemytrip`). No rebuild, install, model replacement, or training was performed for this walkthrough.

**Status: the hotel search is incomplete.** The current verified screen is **Select Check-Out Date**. Check-in is **28 October 2026**; checkout is blank and **Done is disabled**. Checkout date, rooms, adults and children have not yet been supplied for this task. Hotel selection/budget are also unspecified. No result list, reservation or payment is claimed.

The original request was to search for a hotel in the MakeMyTrip app near Koramangala on 28 October. The spelling in the first request was “koramangla” and the app name was written as “make my trip”. Preserve these original words when diagnosing parsing; canonical values should be stored separately.

## Evidence and action ownership

The raw bridge replies, accessibility forests and screenshots live under ignored `captures/hotel-diagnostic/`. They can contain unrelated on-device context and are not copied into this document. The bridge's `state` command reads Friday's own accessibility service; `uiautomator dump` was avoided because it can disconnect that service during this diagnostic.

“Friday” below means its installed routing/model/executor performed the action. “Guided” means a diagnostic operator supplied a narrower request or a coordinate target. Guided progress demonstrates what the device permits, and does not establish autonomous success on the original task.

| Checkpoint | What actually happened | Timing and evidence | Failure or remaining work |
| --- | --- | --- | --- |
| Original natural request | Friday routed the request to `web_search` and opened Chrome. | `original-request.json`; trace opens Chrome at +0.3 s. | It missed the named app and answered “Here's what Google found” without verifying hotel results. Wrong route plus unsupported completion statement. |
| Explicit app spelling | Request beginning “open MakeMyTrip and search…” opened the correct app. | `explicit-app-request.json`; opening at +0.7 s. | TaskCard retained only a query and the label `October`; it did not represent a check-in date, checkout or occupancy. |
| Autonomous app navigation | Seven steps: initial wait, then interactions including repeated Search. Friday stopped as stuck. | Same trace ends at +47.6 s after the same action three times. Individual model passes were about 1.3–9.0 s. | No correct date entry or hotel result list. Repeated model decisions consumed most of this attempt. |
| Open app microtask | Friday opened MakeMyTrip successfully. | `open-only.json`; +0.2 s to open. | Correct for an open-only task; not completion of the hotel task. |
| Hotels and location fields | Guided navigation reached the hotel form and location input. | `guided-hotels-tap.json`, `after-guided-hotels-tap.json`, `guided-location-tap*.json`, `location-entry.json`. | This required operator decomposition of the task. Previous app defaults must not become user intent. |
| Locality microtask | Friday typed Koramangala and chose the matching suggestion. | `guided-locality-search.json`; input at +1.8 s, selection at +3.7 s, DONE at +6.0 s. | The locality microtask ended with model DONE confidence 0.38. Its completion must not finish the parent hotel task. |
| Check-in calendar | Guided navigation opened the calendar. | `open-checkin-calendar.json`, `checkin-calendar*.json`. | Individual date cells were absent from Friday's candidate list. |
| Check-in selected | An operator supplied the visible 28 October cell's center through Friday's bridge tap. | `guided-checkin-28-oct.json`, `after-checkin-28-oct.json`. | This is a guided coordinate action. It is not evidence that Laya chose the correct date. |
| Current readback | Fresh Friday state confirms Select Check-Out Date; check-in 28 Oct Wed, 2026; checkout “-”; Done disabled. | `agent-calendar-before-unknown-values.json`; read command took about 1.52 s, with no mutation. | Await checkout and occupancy details. Search and results remain unobserved. |

Trace offsets are measured within their individual request. They are not a continuous end-to-end task stopwatch; model loading, operator work and waiting for missing details must be recorded separately.

## Calendar failure: the correct target never reaches the model

The current raw accessibility forest contains **31 visible October date nodes**. The current candidate builder produces **12 candidates and zero individual date candidates**.

For example, the raw nodes contain:

| Property | Selected check-in | Next visible date |
| --- | --- | --- |
| `content_desc` | `28 OCT 2026 Selected` | `29 OCT 2026 Tap to select` |
| Bounds | `[308, 1442, 462, 1599]` | `[462, 1442, 616, 1599]` |
| Class / resource id | `c` / empty | `c` / empty |
| Enabled / visible | true / true | true / true |
| Clickable, long-clickable, editable, scrollable, checkable | all false | all false |
| `selected` | false | false |

These bounds are observations from this screen, not reusable selectors.

[`Candidates.kt`](../android/app/src/main/java/dev/layatest/agent/Candidates.kt) first retains nodes with one of five action flags. Every day fails that gate. Its fallback for visible text then excludes text contained within any retained candidate box. Here both a broad clickable calendar parent and the scrollable month container enclose the dates, so the fallback excludes them too. [`scripts/laya_android.py`](../scripts/laya_android.py) mirrors the same rules.

The retained month container's label is truncated to 60 characters: it begins with “October 2026 4 Holidays 1 OCT 2026 Tap to select 2 OCT 2026 …”. The required 28 October target is therefore absent both as an individual option and from that shortened container label. A better model cannot select an option it never receives.

Selection readback has a separate gap: the app puts “Selected” in the date's description while the boolean `selected` stays false. `selectedLabels()` only uses the boolean. The bottom summary (“CHECK-IN DATE 28 Oct Wed, 2026”) is reliable independent readback and should be checked as a field value.

The disabled Done button is still retained as a candidate, with `enabled=false`. The input format can label it disabled, but the executor should independently reject disabled actions and explain which required field is missing. There is no observation here that Friday actually tapped this disabled button.

## Required changes in order

| Priority | Change | Why this walkthrough needs it | Evidence to collect after implementation |
| --- | --- | --- | --- |
| P0 | Resolve a named app before a generic web-search rule; support spaced app-label aliases. | “make my trip app” was routed to Chrome. | Original wording routes to the installed package and opens the intended surface. |
| P0 | Keep typed stay fields on the task: destination/locality, check-in `LocalDate`, checkout `LocalDate`, rooms, adults, children, and the source of each value. | The present TaskCard has no stay or occupancy fields. A generic reporting month is insufficient. | Slot record preserves 28 October 2026 and leaves unknowns explicit. |
| P0 | Collect required missing fields before trying a complete hotel search. | A one-date request does not specify a stay length or occupancy. | One concise clarification gathers checkout and occupancy; app defaults are not silently accepted as user intent. |
| P0 | Expose meaningful bounded leaf targets inside broad containers, including accessible date descriptions. | All 31 calendar dates disappear before inference. | Required date enters the candidate set once; broad parent containers do not suppress it. Kotlin/Python ordering and labels remain aligned. |
| P0 | Ground dates by full day/month/year and the current picker phase. | Clicking “28” alone can select the wrong month or fill checkout instead of check-in. | Match full content description, verify phase, perform action, then read bottom field summary. |
| P0 | Make completion depend on field/result evidence. | Browser launch produced a results claim; locality DONE is weaker than complete search. | Destination, both dates and occupancy match before Search; result header matches after Search. |
| P1 | Use a generic task phase sequence with explicit prerequisites. | The broad goal caused repeated Search interactions without completing fields. | Current phase is location → dates → occupancy → form verification → results, with unresolved prerequisites visible in trace. |
| P1 | Stop repeated ineffective actions based on state change as well as action count. | Repeated Search cost seconds per pass and ended stuck. | If the relevant screen/fields do not change, recover once or ask with the missing prerequisite; do not repeat the same expensive choice. |
| P1 | Preserve text-state semantics independently from tap flags. | “Selected” in description was missed by the boolean-only helper. | Exact selected date is recovered from description or summary; unrelated selected elements cannot complete the task. |
| P1 | Separate action acknowledgment from achieved state. | A bridge tap's `ok=true` proves dispatch, not date correctness. | After every field action, fresh readback records intended value and actual value. |
| P1 | Keep a resumable parent task across guided subgoals and replies. | The locality request became its own finished task. | Replying with checkout/occupancy continues the hotel task and preserves locality/check-in. |
| P1 | Add a result extractor after successful search. | No verified hotel data has yet been collected. | Collect hotel name, displayed price and basis, taxes if shown, rating/count, distance/locality if shown, and matching search parameters. Missing values remain unknown. |
| P2 | Evaluate a bounded text parser only for rules that cannot resolve the wording. | A small Writer may help turn free language into typed fields. It cannot restore filtered-out calendar targets. | Parse accuracy, invented-field rate and full cold-load/swap latency; deterministic validation rejects unsupported dates and occupancy. |

These are generic mechanisms rather than hard-coded MakeMyTrip screen recipes. Calendar descriptions, leaf targets, prerequisites and independent field readback can also support travel, appointments, rides and forms in other apps. The exact candidate policy still needs comparison against ordinary clickable rows so exposing leaves does not flood the model or duplicate every label.

## Next checkpoints, pending the stay details

1. Receive checkout date, room count, adults and children. Preserve 28 October 2026 check-in and the selected locality.
2. Read the current screen again. Find the requested checkout by full accessible date and current bounds; do not reuse an old coordinate blindly.
3. Choose checkout and verify both dates in the bottom summary. Check checkout is later than check-in. Then activate Done and verify the main form.
4. Inspect the occupancy picker. Record initial values as app defaults, set only supplied values, and verify the resulting room/adult/child summary. Child ages may require additional information if the app asks.
5. Before Search, compare every required form value with the parent task. Record each mismatch and correction.
6. Search once; distinguish loading, no availability, network failure and a real result list. Verify result parameters before extracting hotel data.
7. If a concrete hotel/budget is subsequently supplied, inspect its details and final price basis. Reservation/payment is a separate commitment checkpoint; this diagnostic has not reached it.

Every remaining action should record its ownership (Friday decision versus guided action), inference time, read/settle time, expected state, observed state and the next unresolved prerequisite. Do not combine guided completion with the autonomous success metric.

No new build or automated tests were run in this walkthrough. Guest entry, search results and booking remain unobserved until the required details are supplied.
