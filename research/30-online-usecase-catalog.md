# Online inspired Android assistant test cases

_Research date: 2026-10-20 · [primary] official documentation, papers and benchmark repositories · [inference] original task designs · [proposed] implementation required_

The first acceptance target is a complete typed handoff: collect the requested data, enter each field in the destination app, reread the actual values, and verify the requested outcome. The 60 cases below expand that target across travel, planning, shopping, finance, files, media, work and device control. Their machine-readable companion is [online-usecases.json](online-usecases.json).

All 60 entries are proposed specifications, with no live success or latency score assigned. The separate Pixel hotel trial remains incomplete: its latest reported checkpoint has **28 OCT 2026 selected as check-in**, with checkout still required and occupancy/results unverified. A prepared draft, populated cart, search results page and completed booking are different outcomes.

Friday keeps its local model and offline manifest. Authorised destination apps such as MakeMyTrip, Maps and Gmail may use their own network. A case can use an accessible app UI, a documented intent, a selected content URI or a permissioned Android provider. Android isolates private app storage; this catalog assumes no arbitrary app database access. [Android sandbox](https://source.android.com/docs/security/app-sandbox)

## What the web sources contribute

Public requests describe practical joins: mail or SMS into calendars and reminders; calendars, messages and tasks into a daily plan; and trip details into a message. These are examples of expectations, with no claim about their population frequency or the reliability of any named product. [Personal secretary request](https://www.reddit.com/r/AI_Agents/comments/1wbkbsl/looking_to_use_ai_as_my_personal_secretary/), [Android use discussion](https://www.reddit.com/r/GooglePixel/comments/1phg9nh/in_what_ways_is_the_ai_assistant_actually_useful/), [Calendar email and task request](https://www.reddit.com/r/AIToolBench/comments/1wqjjnm/best_ai_personal_assistant_for_everyday_tasks_and/)

Official product examples make scoped handoffs a reasonable baseline. Claude documents drafts, maps, calendars, clocks and Health Connect, while explicitly limiting existing message/email reads. Samsung demonstrates place search to messages and video text to notes. Gemini lists connected apps with account and device conditions. Their features are evidence of user-facing workflow designs, not proof of equivalent Friday app access. [Claude](https://support.claude.com/en/articles/11869629-use-claude-with-android-apps), [Samsung](https://www.samsung.com/us/support/answer/ANS10010644/), [Gemini](https://support.google.com/gemini/answer/13695044)

| Source | Coverage to borrow | How Friday should use it |
|---|---|---|
| [AndroidWorld](https://github.com/google-research/android_world) | 116 parameterised tasks across 20 apps, with durable state checks | Reset fixture state and verify actual outcome; vary arguments |
| [AndroidControl](https://github.com/google-research/google-research/blob/master/android_control/README.md) | More than 15,000 human demonstrations across 833 apps; screenshots, trees, instructions and actions | Keep episode/subgoal alignment; measure full tasks separately from action accuracy |
| [Mobile-Bench](https://arxiv.org/abs/2407.00993) | 832 entries including more than 200 multi-app tasks and checkpoint evaluation | Define a typed payload and oracle for each app boundary |
| [SPA-Bench](https://ai-agents-2030.github.io/SPA-Bench/) | 340 tasks, including 40 across apps; completion and resource measures | Include daily third-party workflows and record time, retries and stop reason |
| [AnyAppBench paper](https://arxiv.org/abs/2609.34139) | Same functional goal across different applications | Repeat a case across app alternatives, holding values and success criteria fixed |
| [Mobile-Agent-E](https://github.com/X-PLUG/MobileAgent/blob/main/Mobile-Agent-E/README.md) | Complex tasks and experience reuse | Borrow recovery/task design ideas; its default model API configuration is outside Friday scope |

Prompts below are original synthetic adaptations of these workflow families. They are not a copied dataset. Commercial app compatibility, login state and UI exposure must be established per installed version. No structured MakeMyTrip hotel-search deep link was verified; those cases use the app UI.

## First twelve cases

P0 means first acceptance work; P1 means the next expansion; P2 means a later workflow with heavier prerequisites. Priority reflects proposed build order, not measured demand.

| ID | First target | Why it comes first |
|---|---|---|
| [T01](#t01-hotel-search-with-an-explicit-stay) | Hotel search with an explicit stay | Full hotel date, locality and occupancy loop plus result collection |
| [T02](#t02-missing-checkout-requires-clarification) | Missing checkout requires clarification | Clarification and typo handling without inventing stay dates |
| [T05](#t05-hotel-address-becomes-map-directions) | Hotel address becomes map directions | One verified place handoff |
| [T06](#t06-booking-email-becomes-calendar-draft) | Booking email becomes calendar draft | Source facts into a structured event draft |
| [T11](#t11-selected-message-becomes-an-event) | Selected message becomes an event | Message date/time extraction into the same event primitive |
| [T13](#t13-reply-to-selected-email-with-exact-context) | Reply to selected email with exact context | Recipient, thread and faithful draft checks |
| [T15](#t15-availability-check-across-calendars) | Availability check across calendars | Read-only interval reasoning and calendar coverage |
| [T18](#t18-poster-photo-becomes-event-draft) | Poster photo becomes event draft | Selected-image extraction with uncertainty |
| [T23](#t23-grocery-note-becomes-a-cart) | Grocery note becomes a cart | Multi-item quantity and variant preservation |
| [T33](#t33-selected-bill-becomes-due-date-reminder) | Selected bill becomes due-date reminder | Bill extraction and deterministic relative-date calculation |
| [T37](#t37-selected-pdf-becomes-email-attachment-draft) | Selected PDF becomes email attachment draft | Selected URI, attachment and recipient verification |
| [T59](#t59-connectivity-panel-handoff) | Connectivity panel handoff | Protected system handoff and truthful state confirmation |

## Fixture and evidence contract

Freeze the synthetic baseline at **20 October 2026, Asia/Kolkata, en-IN**. Before a device run, record its actual date/timezone, Android build, app versions, selected profile/account, authentication and permission state. Create synthetic source messages/files in test accounts using the typed values below; the JSON marks those fixtures as **to create**. For a private-device trial, use only content selected or authorised by its owner. Apps and specific fixtures are not installed or seeded by this catalog.

Each specification includes the request, source and destination, typed payload, mechanism/access, completion oracle, current Friday gaps and action boundary. The JSON additionally includes per-case setup, applicable negative-variant IDs, direct source links and evidence fields. An unavailable handler, missing permission, inaccessible app control or absent source data is recorded separately from a reasoning failure.

For every attempted case, collect: interpreted fields; source reference and extraction evidence; fresh app/window state before each consequential action; verified destination fields or permitted exported state; stage times, actions and retries; final independent oracle and stop reason. Do not count launch, a tap returning true, or model DONE as proof of completed search or accurate data collection.

An unresolved required value is `null`, with a question or explicit owner-approved default. For T02, “28th Oct” is a check-in candidate under the fixed baseline; checkout and guest count are not supplied by the request. Never read old app values as user intent. A safe outcome before clarification is **waiting for required information**, not **completed**.

Use test contacts and example.test addresses. External message sends, bookings, purchases, event commits and destructive file operations stop at owner review. Finance cases remain read only. Health cases use consented data for factual summaries. The catalog itself performs none of these actions.

## Implementation milestones

The twelve P0 cases are the first acceptance set. Build their shared prerequisites in this order; a priority label does not mean the fixture or integration is already available.

| Milestone | Work | First acceptance evidence |
|---|---|---|
| 1. Recover the hotel flow | Implement the connected fixes H01–H07 in [research/29](29-task-completion-speed-and-accuracy.md): app routing, typed stay fields, missing input, calendar targets, field entry and result verification. | T01 exact fields and bounded result collection; T02 asks for missing values. Keep the observed [device walkthrough](32-hotel-device-walkthrough.md) separate from synthetic fixture scores. |
| 2. Establish repeatable evaluation | Create synthetic source records, reset destination state, freeze clock/locale for parser checks, and record model/app versions. Capture independent before/after oracles and failed attempts. | Each attempted case has actual fixture IDs and pass/fail/waiting/unavailable status. Live inventory may change; validate observed result structure rather than a fixed hotel price. |
| 3. Add structured handoffs | Implement selected-content receiving, typed extraction, Maps/editor/composer intents and destination readback. Add permitted calendar reads only where required. | T05, T06, T11, T13, T15 and T59. A reply stays in its original thread; a draft remains distinguishable from a saved/sent item. |
| 4. Add selected files and image extraction | Implement URI grants, attachment checks and local parsers/OCR for the relevant fixtures. | T18, T33 and T37 preserve source facts and uncertainty; denied access has a distinct outcome. |
| 5. Handle multi-item app state | Preserve product identity, quantity and existing cart state; recover without duplicating items. | T23 cart readback matches the grocery note and stops before checkout. |
| 6. Add bounded text generation | Compare rules/templates and the small Writer candidates using [research/31](31-slm-text-generation.md), then connect validated text to drafts. | T51–T54 plus TXT-01–TXT-16 check meaning, exact facts, missing values and total generation/swap cost. |

The JSON is a specification input for a future harness. It does not seed accounts, reset third-party apps or execute these cases yet. Replace placeholder fixture addresses, file references and contacts with concrete synthetic records before a live run; record the exact resolved destination used by the oracle.

## Technical paths and access

| Path | Mechanism | Access |
|---|---|---|
| `UI` | Observe foreground app, read fresh accessible controls, act, and reread. App login and exposed UI determine feasibility. An inaccessible control can require owner handoff. [Create an accessibility service](https://developer.android.com/guide/topics/ui/accessibility/service) | Owner-enabled Friday AccessibilityService; destination app installed, authenticated and allowed. |
| `SHARE` | Transfer text or selected content URIs through Android sharing. Match MIME types, grant URI read access and inspect the receiving draft. [Send data to other apps](https://developer.android.com/develop/ui/compose/sharing/send) | Destination supports payload; access to every shared URI; owner chooses or verifies destination. |
| `RECEIVE` | User shares selected text/file into a Friday receiving activity for local extraction. [Receive data from other apps](https://developer.android.com/develop/ui/compose/sharing/receive) | New Friday share receiver and local parser; source explicitly exposes selected content. |
| `CAL_EDITOR` | Open Calendar event editor with fields prefilled; inspect fields before owner saves. [Calendar provider overview](https://developer.android.com/identity/providers/calendar-provider) | Calendar intent handler; no calendar provider permission for editor handoff. |
| `CAL_READ` | Read local calendar events with the provider, or inspect authorised calendar UI. [Calendar provider overview](https://developer.android.com/identity/providers/calendar-provider) | New READ_CALENDAR consent and implementation for provider reads; UI alternative needs accessibility. |
| `FILE` | User selects a document URI; parse supported local content. Persist only the offered grant and recover if document moves. [Storage Access Framework documents](https://developer.android.com/training/data-storage/shared/documents-files) | System document picker and URI grant; new local parser/export path; no private app database access. |
| `PHOTO` | Use selected image URIs and on-device text extraction when required. [Android photo picker](https://developer.android.com/training/data-storage/shared/photo-picker) | Photo picker or explicit sharing; new local OCR/extraction capability, not Laya decision output. |
| `MAP` | Open encoded place search or directions in Maps; verify the resolved place. [Google Maps URLs](https://developers.google.com/maps/documentation/urls/get-started) | Installed handler or browser; destination app may need network and its own location consent. |
| `COMPOSE` | Open supported email/SMS composer; verify recipient and exact draft text. [Android common intents](https://developer.android.com/guide/components/intents-common) | Installed composer; recipient identity resolved; owner sends. |
| `CLOCK` | Use supported alarm/timer handoff and inspect actual clock state, including the intended date. [Android common intents](https://developer.android.com/guide/components/intents-common) | SET_ALARM and compatible Clock app; absolute future-date reminders need a suitable scheduler or calendar. |
| `MEDIA` | Try a supported media search/play intent or app UI, then inspect actual content and playback. [Android common intents](https://developer.android.com/guide/components/intents-common) | Compatible authenticated media app; content/network/entitlement may be required. |
| `NOTIF` | Read active/new authorised notifications, open source for missing content, then extract facts. [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService) | New listener implementation and owner notification-access grant; no historical-inbox guarantee, no OTP extraction. |
| `HEALTH` | Read authorised Health Connect types locally; aggregate cumulative data rather than adding overlapping source records. [Read Health Connect data](https://developer.android.com/health-and-fitness/health-connect/read-data) | New Health Connect implementation, compatible populated store and per-type user consent; history/background access separate. |
| `PANEL` | Open system connectivity panel and verify requested network or ask owner to select it. [Android Settings panels](https://developer.android.com/reference/android/provider/Settings.Panel) | Supported settings panel and user interaction for protected changes. |
| `LOCAL` | Validate structured values and compute/export locally from granted inputs; retain source provenance.  | New schema-aware extraction and deterministic calculation, with minimal retained personal data. |

## Current Friday gaps

The inspected manifest explicitly removes INTERNET. Existing primitives include launching, accessibility actions, message/email handoff, navigation, shopping search/order reads, alarms, media and device controls. Code presence does not establish end-to-end app support. The inspected source has no dedicated Calendar, SAF/share receiver, notification-listener or Health Connect integration. TaskCard needs an extensible structured payload for complete hotel stays and multi-app outputs. [Manifest](../android/app/src/main/AndroidManifest.xml), [TaskCard](../android/app/src/main/java/dev/layatest/agent/Card.kt), [skills](../android/app/src/main/java/dev/layatest/agent/Skills.kt), [people and places skills](../android/app/src/main/java/dev/layatest/agent/PeopleSkills.kt)

- **STAY:** No dedicated typed hotel stay contract in the inspected TaskCard: locality, check-in, checkout, occupancy and result rows need explicit fields and checks.
- **READ:** Extend source reading to preserve item identity, provenance, completeness and typed fields across apps.
- **PLAN:** Add sequential subtask state, typed handoffs and independent completion checks for the whole request.
- **DRAFT:** Current message/email primitives exist; general local text composition and destination draft validation need expansion.
- **ENTITY:** Resolve ambiguous recipients, app accounts and place branches before entering data.
- **DATE:** Extend date understanding to absolute dates, intervals, timezones, calendar controls and corrections.
- **CALENDAR:** No Calendar skill/provider integration found in the inspected Android sources; editor/read support is proposed.
- **FILES:** No SAF import/export or generic shared-file receiving path found; add selected URI handling and local parsers.
- **OCR:** Add local OCR for explicitly selected images; Laya chooses actions and cannot generate extracted prose.
- **COMPARE:** Add structured comparison, source timestamps and deterministic constraints across result lists.
- **CART:** Shopping currently focuses on search/order history; lists, variants, quantities and cart verification need a workflow.
- **HEALTH:** No Health Connect integration or permissions found in inspected manifest; this is a new scoped feature.
- **NOTIFICATIONS:** No notification listener found; full inbox access cannot be inferred from notification access.
- **VERIFY:** Existing primitive needs a stronger content/state oracle and recovery; launching an app is insufficient.
- **PROFILE:** Track personal/work profile and account ownership; stop when Android policy prevents the handoff.

The local SLM cases T51–T54 need bounded output plus deterministic validation of immutable facts. SLM output is a proposed candidate payload; the destination UI still needs independent verification. See [local SLM text generation](31-slm-text-generation.md).

## Travel

### T01 Hotel search with an explicit stay

**P0 · Proposed · Not run.** Request: “Search MakeMyTrip for hotels near Koramangala, Bengaluru, checking in 28 October 2026 and out 29 October, for 2 adults in 1 room, with no children. Show three options.”

- **Flow:** User request → MakeMyTrip + Friday.
- **Typed handoff:** `locality: string` = "Koramangala"; `city: string` = "Bengaluru"; `check_in: date` = "2026-10-28"; `check_out: date` = "2026-10-29"; `adults: integer` = 2; `rooms: integer` = 1; `children: integer` = 0; `result_count: integer` = 3.
- **Path and setup:** `UI` → `LOCAL`. Use the fixture/access contract above. Resolve Hotels product, select destination suggestion, set each date, set occupancy, reread summary, submit search, collect complete result rows.
- **Success:** Search summary shows locality, both dates and occupancy. Three distinct visible hotel rows have names, location and displayed price basis; otherwise report actual fewer/zero results.
- **Friday work:** STAY, DATE, READ. **Boundary:** read; stop before reservation or payment.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16. **Sources:** [SPA](https://ai-agents-2030.github.io/SPA-Bench/) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service)

### T02 Missing checkout requires clarification

**P0 · Proposed · Not run.** Request: “Serach for a hotel in Make My Trip near Koramangla on 28th Oct.”

- **Flow:** User request → Friday + MakeMyTrip.
- **Typed handoff:** `raw_locality: string` = "Koramangla"; `candidate_locality: string` = "Koramangala Bengaluru"; `check_in: date` = "2026-10-28"; `check_out: date` = null; `adults: integer` = null; `rooms: integer` = null.
- **Path and setup:** `UI` → `LOCAL`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Ask for missing checkout and unresolved occupancy or show explicit owner-approved defaults. Do not report hotel search complete with stale app dates. After answer, exact fields match the completed request.
- **Friday work:** STAY, DATE, ENTITY. **Boundary:** read; no invented checkout or silently accepted old dates.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16, V06, V10, V14. **Sources:** [AW](https://github.com/google-research/android_world) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service)

### T03 Trip message becomes a hotel search

**P1 · Proposed · Not run.** Request: “Use the trip dates and area from my selected WhatsApp message to search hotels in MakeMyTrip for one adult. Ask if the checkout is missing.”

- **Flow:** WhatsApp selected message → MakeMyTrip + Friday.
- **Typed handoff:** `message_ref: string` = "synthetic-trip-01"; `locality: string` = "Koramangala Bengaluru"; `check_in: date` = "2026-10-28"; `check_out: date` = "2026-10-30"; `adults: integer` = 1; `rooms: integer` = 1.
- **Path and setup:** `RECEIVE` → `LOCAL` → `UI`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Captured dates/locality link to the selected message; MMT search summary exactly matches them and one adult; result facts retain hotel identity.
- **Friday work:** READ, STAY, PLAN. **Boundary:** read; only selected chat content.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16. **Sources:** [USER2](https://www.reddit.com/r/GooglePixel/comments/1phg9nh/in_what_ways_is_the_ai_assistant_actually_useful/) [RECEIVE](https://developer.android.com/develop/ui/compose/sharing/receive) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service)

### T04 Compare hotel totals across apps

**P2 · Proposed · Not run.** Request: “Compare the same one-night Koramangala stay for 28 October 2026 in MakeMyTrip and Booking.com, 2 adults, 1 room. Find up to three cancellable options under INR 5000 total.”

- **Flow:** User request → MakeMyTrip + Booking.com + Friday.
- **Typed handoff:** `check_in: date` = "2026-10-28"; `check_out: date` = "2026-10-29"; `adults: integer` = 2; `rooms: integer` = 1; `budget: money` = "INR 5000 total"; `free_cancellation: boolean` = true.
- **Path and setup:** `UI` → `LOCAL`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Both app summaries match dates and occupancy. Comparison reports observed currency, taxes/fee inclusion, cancellation deadline and evidence; unknown total is not declared within budget.
- **Friday work:** STAY, COMPARE, READ. **Boundary:** read; no purchase, availability guarantee or guessed tax.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16. **Sources:** [ANY](https://arxiv.org/abs/2609.34139) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service)

### T05 Hotel address becomes map directions

**P0 · Proposed · Not run.** Request: “Take the address of the selected hotel in MakeMyTrip and open walking directions to it in Google Maps.”

- **Flow:** MakeMyTrip hotel detail → Google Maps.
- **Typed handoff:** `hotel_id: string` = "fixture-hotel-01"; `address: string` = "fixture address in Koramangala Bengaluru"; `travel_mode: enum` = "walking".
- **Path and setup:** `UI` → `LOCAL` → `MAP`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Destination name/address matches selected hotel, route mode is walking, and Maps route is visible; ambiguity asks user.
- **Friday work:** READ, ENTITY, VERIFY. **Boundary:** read; no hotel booking.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V06, V10, V14. **Sources:** [MB](https://arxiv.org/abs/2407.00993) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service) [MAP](https://developers.google.com/maps/documentation/urls/get-started)

### T06 Booking email becomes calendar draft

**P0 · Proposed · Not run.** Request: “From the selected hotel confirmation in Gmail, draft a calendar event covering the check-in and checkout dates with the hotel address.”

- **Flow:** Gmail selected confirmation → Calendar editor.
- **Typed handoff:** `booking_ref: string` = "synthetic-HTL-082"; `title: string` = "Koramangala stay"; `check_in: date` = "2026-10-28"; `check_out: date` = "2026-10-30"; `timezone: iana_timezone` = "Asia/Kolkata"; `address: string` = "fixture hotel address".
- **Path and setup:** `RECEIVE` → `LOCAL` → `CAL_EDITOR`. Use the fixture/access contract above. Extract check-in and checkout dates from the selected confirmation. For an all-day stay use checkout as the exclusive calendar end date and include the checkout date in the description; never invent a check-in time. Preview before owner saves.
- **Success:** Draft event dates, timezone, all-day semantics or stated check-in times and address match confirmation; booking reference appears only where intended.
- **Friday work:** READ, CALENDAR, DATE. **Boundary:** draft; owner saves; protect booking reference.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16. **Sources:** [USER2](https://www.reddit.com/r/GooglePixel/comments/1phg9nh/in_what_ways_is_the_ai_assistant_actually_useful/) [RECEIVE](https://developer.android.com/develop/ui/compose/sharing/receive) [CAL](https://developer.android.com/identity/providers/calendar-provider)

### T07 Meeting location becomes a route

**P1 · Proposed · Not run.** Request: “Open tomorrow morning's Bengaluru client meeting in Calendar and show a driving route to its exact location in Maps.”

- **Flow:** Calendar → Google Maps.
- **Typed handoff:** `event_ref: string` = "fixture-client-01"; `date: date` = "2026-10-21"; `destination: string` = "fixture meeting address"; `travel_mode: enum` = "driving".
- **Path and setup:** `CAL_READ` → `LOCAL` → `MAP`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Correct event/account selected; route destination matches event location; route is displayed without claiming a live ETA from old data.
- **Friday work:** CALENDAR, ENTITY, PLAN. **Boundary:** read; ambiguous meetings require selection.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16, V06, V10, V14. **Sources:** [MB](https://arxiv.org/abs/2407.00993) [CAL](https://developer.android.com/identity/providers/calendar-provider) [MAP](https://developers.google.com/maps/documentation/urls/get-started)

### T08 Selected itinerary becomes alarm setup

**P1 · Proposed · Not run.** Request: “Read my selected itinerary PDF and prepare the 5 AM departure alarm for the flight on 28 October.”

- **Flow:** Files itinerary PDF → Clock + Friday.
- **Typed handoff:** `document_ref: uri` = "selected-itinerary.pdf"; `flight_date: date` = "2026-10-28"; `alarm_time: local_time` = "05:00"; `timezone: iana_timezone` = "Asia/Kolkata".
- **Path and setup:** `FILE` → `LOCAL` → `CLOCK`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Flight date and intended alarm date match. If Clock cannot represent that future date, report the limitation and offer a calendar reminder; do not create an alarm for tomorrow.
- **Friday work:** FILES, DATE, VERIFY. **Boundary:** local schedule; exact date must be representable.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16, V12, V15. **Sources:** [AW](https://github.com/google-research/android_world) [SAF](https://developer.android.com/training/data-storage/shared/documents-files) [INTENT](https://developer.android.com/guide/components/intents-common)

### T09 Saved place becomes a ride estimate

**P1 · Proposed · Not run.** Request: “Use the address of the place I selected in Maps as the drop-off in Uber and show the fare estimate from my current pickup.”

- **Flow:** Google Maps selected place → Uber.
- **Typed handoff:** `place_ref: string` = "fixture-venue-01"; `dropoff: string` = "fixture venue address"; `pickup: string` = "owner-verified current pickup".
- **Path and setup:** `UI` → `LOCAL`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Uber pickup and drop-off match intended places and an observed fare estimate is shown; state estimate time and availability.
- **Friday work:** READ, ENTITY, PLAN. **Boundary:** draft ride; stop before request/confirm.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V06, V10, V14. **Sources:** [MB](https://arxiv.org/abs/2407.00993) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service)

### T10 Ticket attachment becomes local file

**P1 · Proposed · Not run.** Request: “Save the selected train ticket attachment from Gmail as a PDF in a folder I choose.”

- **Flow:** Gmail selected attachment → System file picker + Files.
- **Typed handoff:** `attachment_ref: string` = "synthetic-ticket.pdf"; `mime: string` = "application/pdf"; `filename: string` = "train-2026-10-28.pdf".
- **Path and setup:** `UI` → `SHARE` → `FILE`. Use the fixture/access contract above. Open the exact selected attachment, invoke the source app export/save/share flow and a chosen output location, then reopen the written PDF. Source bytes must be exported or explicitly granted; screen text is insufficient.
- **Success:** Selected output URI opens as the correct PDF and metadata matches chosen file; no unrelated attachment copied.
- **Friday work:** FILES, PLAN, VERIFY. **Boundary:** local file creation; owner chooses folder; no overwrite.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V12, V15. **Sources:** [SPA](https://ai-agents-2030.github.io/SPA-Bench/) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service) [SHARE](https://developer.android.com/develop/ui/compose/sharing/send) [SAF](https://developer.android.com/training/data-storage/shared/documents-files)

## Communication and planning

### T11 Selected message becomes an event

**P0 · Proposed · Not run.** Request: “Make a calendar draft from the selected SMS: dentist at 4 PM on 28 October 2026 at the Koramangala clinic, for 30 minutes.”

- **Flow:** Messages selected SMS → Calendar editor.
- **Typed handoff:** `title: string` = "Dentist"; `start: datetime` = "2026-10-28T16:00:00+05:30"; `duration: minutes` = 30; `timezone: iana_timezone` = "Asia/Kolkata"; `location: string` = "Koramangala clinic".
- **Path and setup:** `RECEIVE` → `LOCAL` → `CAL_EDITOR`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Draft has 16:00-16:30 on 28 October, correct zone/location and source reference; any missing clinic branch asks user.
- **Friday work:** DATE, CALENDAR, READ. **Boundary:** draft; owner saves.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16. **Sources:** [USER1](https://www.reddit.com/r/AI_Agents/comments/1wbkbsl/looking_to_use_ai_as_my_personal_secretary/) [RECEIVE](https://developer.android.com/develop/ui/compose/sharing/receive) [CAL](https://developer.android.com/identity/providers/calendar-provider)

### T12 Chat action item becomes a task draft

**P1 · Proposed · Not run.** Request: “From the selected WhatsApp message, draft a task to send the design by Friday 6 PM with a link back to the conversation if the app exposes one.”

- **Flow:** WhatsApp selected message → Task app draft.
- **Typed handoff:** `title: string` = "Send design"; `due: datetime` = "2026-10-23T18:00:00+05:30"; `source_ref: string` = "fixture-message-12"; `conversation_link: uri` = null.
- **Path and setup:** `RECEIVE` → `LOCAL` → `UI`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Task title/deadline match source; deep link included only if actually provided; preview stays unsaved unless owner confirms.
- **Friday work:** READ, DATE, PLAN. **Boundary:** draft; no message send and no invented chat URL.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16. **Sources:** [USER3](https://www.reddit.com/r/AIToolBench/comments/1wqjjnm/best_ai_personal_assistant_for_everyday_tasks_and/) [RECEIVE](https://developer.android.com/develop/ui/compose/sharing/receive) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service)

### T13 Reply to selected email with exact context

**P0 · Proposed · Not run.** Request: “Draft an email reply to the selected Gmail message confirming I can meet on 28 October at 11 AM. Keep the existing subject and recipient.”

- **Flow:** Gmail selected email → Gmail reply draft.
- **Typed handoff:** `thread_ref: string` = "synthetic-thread-13"; `recipient: string` = "alex@example.test"; `subject: string` = "Re: Site visit"; `meeting_start: datetime` = "2026-10-28T11:00:00+05:30".
- **Path and setup:** `UI` → `LOCAL` → `COMPOSE`. Use the fixture/access contract above. Read the selected thread and account, use Gmail Reply UI to preserve thread identity, insert the validated draft, reread recipient and body. A generic mailto composer does not guarantee an in-thread reply.
- **Success:** Correct account/thread/recipient and subject; draft contains requested date/time and no invented commitments; Send remains untouched.
- **Friday work:** DRAFT, ENTITY, DATE. **Boundary:** draft; owner sends.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16, V06, V10, V14. **Sources:** [CLAUDE](https://support.claude.com/en/articles/11869629-use-claude-with-android-apps) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service) [INTENT](https://developer.android.com/guide/components/intents-common)

### T14 Identical contact names require selection

**P1 · Proposed · Not run.** Request: “Draft a WhatsApp message to Rahul saying I will arrive at 7 PM. There are two Rahuls in my contacts.”

- **Flow:** User request + WhatsApp contacts → WhatsApp draft.
- **Typed handoff:** `recipient_label: string` = "Rahul"; `recipient_id: string` = null; `body: string` = "I will arrive at 7 PM.".
- **Path and setup:** `UI` → `LOCAL`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Assistant asks which Rahul using distinguishable existing contact metadata; selected conversation and exact draft verified before any send.
- **Friday work:** ENTITY, DRAFT, VERIFY. **Boundary:** draft; owner selects recipient and sends.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V06, V10, V14. **Sources:** [ANY](https://arxiv.org/abs/2609.34139) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service)

### T15 Availability check across calendars

**P0 · Proposed · Not run.** Request: “Check my personal and work calendars for 28 October from 2 to 4 PM and tell me which 30-minute slots are free.”

- **Flow:** Calendar personal account + Calendar work account → Friday.
- **Typed handoff:** `start: datetime` = "2026-10-28T14:00:00+05:30"; `end: datetime` = "2026-10-28T16:00:00+05:30"; `slot_size: minutes` = 30; `calendar_scope: string` = "owner-authorised personal and work calendars".
- **Path and setup:** `CAL_READ` → `LOCAL`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Returned free intervals exclude every accessible overlapping busy interval, handle all-day/recurring events, and label calendars that could not be read.
- **Friday work:** CALENDAR, DATE, PROFILE. **Boundary:** read; no event changes or cross-profile bypass.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16. **Sources:** [USER3](https://www.reddit.com/r/AIToolBench/comments/1wqjjnm/best_ai_personal_assistant_for_everyday_tasks_and/) [CAL](https://developer.android.com/identity/providers/calendar-provider)

### T16 Task duration becomes a calendar draft

**P2 · Proposed · Not run.** Request: “Use my selected 45-minute review task and draft a calendar block in a free slot tomorrow afternoon. Ask which slot if there are several.”

- **Flow:** Task app selected item + Calendar → Calendar editor.
- **Typed handoff:** `task_ref: string` = "fixture-review-16"; `duration: minutes` = 45; `window_start: datetime` = "2026-10-21T13:00:00+05:30"; `window_end: datetime` = "2026-10-21T18:00:00+05:30".
- **Path and setup:** `UI` → `CAL_READ` → `LOCAL` → `CAL_EDITOR`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Selected interval is free in stated calendar scope and exactly 45 minutes; draft links to the task only when source supports it.
- **Friday work:** CALENDAR, PLAN, DATE. **Boundary:** draft; owner chooses slot and saves.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16. **Sources:** [USER3](https://www.reddit.com/r/AIToolBench/comments/1wqjjnm/best_ai_personal_assistant_for_everyday_tasks_and/) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service) [CAL](https://developer.android.com/identity/providers/calendar-provider)

### T17 Reschedule an owned event

**P2 · Proposed · Not run.** Request: “Prepare moving my own dentist event on 28 October from 4 PM to 5 PM, preserving its duration and location.”

- **Flow:** Calendar owned event → Calendar editor.
- **Typed handoff:** `event_ref: string` = "fixture-dentist-17"; `old_start: datetime` = "2026-10-28T16:00:00+05:30"; `new_start: datetime` = "2026-10-28T17:00:00+05:30"; `duration: minutes` = 30.
- **Path and setup:** `CAL_READ` → `LOCAL` → `UI`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Editor shows same event identity/calendar/location, new 17:00-17:30 interval; ownership checked and clashes reported.
- **Friday work:** CALENDAR, DATE, VERIFY. **Boundary:** draft edit; owner saves; do not alter someone else event.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16. **Sources:** [CLAUDE](https://support.claude.com/en/articles/11869629-use-claude-with-android-apps) [CAL](https://developer.android.com/identity/providers/calendar-provider) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service)

### T18 Poster photo becomes event draft

**P0 · Proposed · Not run.** Request: “Use the event date, time and venue from this selected poster photo to draft a calendar event. Ask me about any unreadable text.”

- **Flow:** Photo picker selected poster → Friday + Calendar editor.
- **Typed handoff:** `image_ref: uri` = "selected-poster.jpg"; `title: string` = "Fixture design talk"; `start: datetime` = "2026-10-28T18:30:00+05:30"; `venue: string` = "fixture venue"; `end: datetime` = null.
- **Path and setup:** `PHOTO` → `LOCAL` → `CAL_EDITOR`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** All legible fields match poster; missing end time/ambiguous zone is clarified or visibly left unresolved; OCR uncertainty does not become fabricated certainty.
- **Friday work:** OCR, CALENDAR, DATE. **Boundary:** draft; selected image only; owner saves.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16, V12, V15. **Sources:** [USER2](https://www.reddit.com/r/GooglePixel/comments/1phg9nh/in_what_ways_is_the_ai_assistant_actually_useful/) [PHOTO](https://developer.android.com/training/data-storage/shared/photo-picker) [CAL](https://developer.android.com/identity/providers/calendar-provider)

### T19 Meeting details become a message draft

**P1 · Proposed · Not run.** Request: “From my selected calendar event, draft an SMS to the contact I choose with its time and venue.”

- **Flow:** Calendar selected event + Contact picker → Messages draft.
- **Typed handoff:** `event_ref: string` = "fixture-event-19"; `recipient_id: string` = "owner-selected"; `start: datetime` = "2026-10-28T11:00:00+05:30"; `venue: string` = "fixture meeting room".
- **Path and setup:** `CAL_READ` → `LOCAL` → `COMPOSE`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Recipient is explicitly selected; draft matches source event time/venue and includes timezone when needed.
- **Friday work:** CALENDAR, ENTITY, DRAFT. **Boundary:** draft; owner sends.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16, V06, V10, V14. **Sources:** [MB](https://arxiv.org/abs/2407.00993) [CAL](https://developer.android.com/identity/providers/calendar-provider) [INTENT](https://developer.android.com/guide/components/intents-common)

### T20 Notes become a task checklist

**P1 · Proposed · Not run.** Request: “Turn the selected packing note into a checklist in my task app, preserving item wording and quantities.”

- **Flow:** Notes selected text → Task app checklist draft.
- **Typed handoff:** `source_ref: string` = "fixture-packing-note"; `items: array<string>` = ["2 shirts", "charger", "passport"].
- **Path and setup:** `RECEIVE` → `LOCAL` → `UI`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Draft contains exactly three distinct items with quantity 2 retained; repeated execution does not duplicate a saved list.
- **Friday work:** READ, PLAN, VERIFY. **Boundary:** draft; owner saves; do not mark items complete.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13. **Sources:** [AW](https://github.com/google-research/android_world) [RECEIVE](https://developer.android.com/develop/ui/compose/sharing/receive) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service)

### T21 Notification becomes a follow-up draft

**P2 · Proposed · Not run.** Request: “From this delivery notification, draft a reminder to check the parcel tomorrow at 7 PM. Open the source if the notification is truncated.”

- **Flow:** Authorised delivery notification → Reminder or Calendar draft.
- **Typed handoff:** `notification_ref: string` = "fixture-notif-21"; `order_ref: string` = "synthetic-ORD-21"; `due: datetime` = "2026-10-21T19:00:00+05:30".
- **Path and setup:** `NOTIF` → `LOCAL` → `CAL_EDITOR`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Reminder contains only verified parcel reference and exact intended date/time; no claim to read full historical messages.
- **Friday work:** NOTIFICATIONS, DATE, CALENDAR. **Boundary:** draft; ignore OTP/security content; owner saves.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16. **Sources:** [USER1](https://www.reddit.com/r/AI_Agents/comments/1wbkbsl/looking_to_use_ai_as_my_personal_secretary/) [NOTIF](https://developer.android.com/reference/android/service/notification/NotificationListenerService) [CAL](https://developer.android.com/identity/providers/calendar-provider)

### T22 Flight timezones remain distinct

**P2 · Proposed · Not run.** Request: “From my selected flight confirmation, draft departure and arrival calendar events with their local timezones and correct dates.”

- **Flow:** Gmail selected flight confirmation → Calendar editor.
- **Typed handoff:** `flight_ref: string` = "synthetic-BLR-LHR-22"; `departure: datetime` = "2026-10-28T04:00:00+05:30"; `departure_zone: iana_timezone` = "Asia/Kolkata"; `arrival: datetime` = "2026-10-28T09:30:00+00:00"; `arrival_zone: iana_timezone` = "Europe/London".
- **Path and setup:** `RECEIVE` → `LOCAL` → `CAL_EDITOR`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Each local time/zone/date and underlying instant matches fixture; no same-zone conversion assumption; source airports retained.
- **Friday work:** DATE, CALENDAR, READ. **Boundary:** draft; owner saves.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16. **Sources:** [MB](https://arxiv.org/abs/2407.00993) [RECEIVE](https://developer.android.com/develop/ui/compose/sharing/receive) [CAL](https://developer.android.com/identity/providers/calendar-provider)

## Shopping and food

### T23 Grocery note becomes a cart

**P0 · Proposed · Not run.** Request: “Use my selected grocery list to build a Blinkit cart with 1 litre milk, 6 bananas and 500 grams oats. Ask before substituting anything.”

- **Flow:** Notes selected grocery list → Blinkit cart.
- **Typed handoff:** `items: array<shopping_item>` = [{"product": "milk", "pack_size": {"value": 1, "unit": "L"}, "quantity": 1}, {"product": "banana", "unit": "piece", "quantity": 6}, {"product": "oats", "pack_size": {"value": 500, "unit": "g"}, "quantity": 1}]; `substitutions: enum` = "ask"; `delivery_area: string` = "owner-verified address".
- **Path and setup:** `RECEIVE` → `LOCAL` → `UI`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Cart rows match product, pack size and quantity; unavailable items reported; discounts and total read from cart; checkout untouched.
- **Friday work:** CART, PLAN, VERIFY. **Boundary:** cart preparation; owner checks out; no payment.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13. **Sources:** [SPA](https://ai-agents-2030.github.io/SPA-Bench/) [RECEIVE](https://developer.android.com/develop/ui/compose/sharing/receive) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service)

### T24 Photo list becomes a cart

**P2 · Proposed · Not run.** Request: “Read the grocery list in this selected photo and prepare matching items in Zepto. Show what you could not read.”

- **Flow:** Photo picker selected list → Zepto cart.
- **Typed handoff:** `image_ref: uri` = "fixture-grocery.jpg"; `items: array<shopping_item>` = [{"product": "rice", "pack_size": {"value": 1, "unit": "kg"}, "quantity": 1}, {"product": "eggs", "unit": "piece", "quantity": 6}]; `substitutions: enum` = "ask".
- **Path and setup:** `PHOTO` → `LOCAL` → `UI`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** OCR list is reviewed before shopping; exact units/quantities verified in cart; unreadable item remains unresolved.
- **Friday work:** OCR, CART, VERIFY. **Boundary:** cart preparation; owner confirms ambiguous text and checkout.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V12, V15. **Sources:** [SPA](https://ai-agents-2030.github.io/SPA-Bench/) [PHOTO](https://developer.android.com/training/data-storage/shared/photo-picker) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service)

### T25 Compare identical product variants

**P1 · Proposed · Not run.** Request: “Compare the same 256 GB phone model in Amazon and Flipkart and record current total price and delivery estimate for my selected postcode.”

- **Flow:** User request → Amazon + Flipkart + Friday.
- **Typed handoff:** `model: string` = "fixture phone model"; `storage: string` = "256 GB"; `colour: string` = "black"; `postcode: string` = "560034"; `currency: string` = "INR".
- **Path and setup:** `UI` → `LOCAL`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Same variant/condition/seller terms compared, observed total and postcode/delivery estimate recorded with timestamp; no guessed offers.
- **Friday work:** COMPARE, READ, ENTITY. **Boundary:** read; no purchase, financing or postcode change without owner intent.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V06, V10, V14. **Sources:** [ANY](https://arxiv.org/abs/2609.34139) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service)

### T26 Unavailable item requires substitution approval

**P1 · Proposed · Not run.** Request: “If the 500 g oats in my prepared cart is unavailable, show equivalent pack sizes and ask before changing it.”

- **Flow:** Shopping cart → Shopping cart + Friday.
- **Typed handoff:** `original_item: string` = "oats 500 g"; `allow_substitution: boolean` = false; `candidate_sizes: array<string>` = ["1 kg", "250 g x2"].
- **Path and setup:** `UI` → `LOCAL`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Original remains unchanged until owner picks; per-unit cost is computed from observed price and size; selected replacement and quantity verified.
- **Friday work:** CART, COMPARE, VERIFY. **Boundary:** cart only; owner approves substitution and checkout.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13. **Sources:** [AW](https://github.com/google-research/android_world) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service)

### T27 Restaurant discovery becomes a food search

**P1 · Proposed · Not run.** Request: “Find a restaurant near Koramangala in Maps with the cuisine I asked for, then look for that exact branch in Swiggy.”

- **Flow:** Google Maps results → Swiggy search.
- **Typed handoff:** `cuisine: string` = "South Indian"; `locality: string` = "Koramangala Bengaluru"; `restaurant_ref: string` = "owner-selected result"; `branch: string` = "verified branch address".
- **Path and setup:** `UI` → `LOCAL`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Swiggy result matches restaurant branch, not only name; unavailable branch reported without silently choosing another.
- **Friday work:** ENTITY, PLAN, READ. **Boundary:** read; no order.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V06, V10, V14. **Sources:** [SAMSUNG](https://www.samsung.com/us/support/answer/ANS10010644/) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service)

### T28 Menu constraints produce a shortlist

**P1 · Proposed · Not run.** Request: “In the selected Zomato restaurant menu, show vegetarian mains under INR 350 that the app explicitly labels as containing no egg.”

- **Flow:** Zomato selected restaurant menu → Friday.
- **Typed handoff:** `diet: string` = "vegetarian"; `exclude: string` = "egg"; `max_price: money` = "INR 350"; `category: string` = "main course".
- **Path and setup:** `UI` → `LOCAL`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Each returned item meets visible labels and displayed item price; missing allergen information is labelled unknown and never inferred from dish name.
- **Friday work:** READ, COMPARE, VERIFY. **Boundary:** read; no allergy safety guarantee or order.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13. **Sources:** [SPA](https://ai-agents-2030.github.io/SPA-Bench/) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service)

### T29 Food receipt becomes an expense row

**P1 · Proposed · Not run.** Request: “Read my selected Swiggy receipt PDF and prepare a local expense row with date, merchant, total and order reference.”

- **Flow:** Files selected receipt → Friday local CSV export.
- **Typed handoff:** `receipt_ref: uri` = "fixture-food-receipt.pdf"; `date: date` = "2026-10-19"; `merchant: string` = "fixture restaurant"; `total: money` = "INR 420.00"; `order_ref: string` = "synthetic-ORD-29".
- **Path and setup:** `FILE` → `LOCAL`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Exported row exactly matches receipt total and date, separates fee/tax only if shown, and has one row per order reference.
- **Friday work:** FILES, READ, VERIFY. **Boundary:** read-only finance input; local new file; no bank access.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V12, V15. **Sources:** [USER1](https://www.reddit.com/r/AI_Agents/comments/1wbkbsl/looking_to_use_ai_as_my_personal_secretary/) [SAF](https://developer.android.com/training/data-storage/shared/documents-files)

### T30 Delivery estimate becomes reminder draft

**P1 · Proposed · Not run.** Request: “From the selected Amazon order, draft a reminder to be available on the delivery day at 10 AM.”

- **Flow:** Amazon selected order → Calendar editor.
- **Typed handoff:** `order_ref: string` = "synthetic-ORD-30"; `delivery_date: date` = "2026-10-28"; `reminder_time: local_time` = "10:00"; `timezone: iana_timezone` = "Asia/Kolkata".
- **Path and setup:** `UI` → `LOCAL` → `CAL_EDITOR`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Draft uses observed delivery date and correct order; a delivery range asks user to select a day, and an estimate stays labelled an estimate.
- **Friday work:** READ, DATE, CALENDAR. **Boundary:** draft; owner saves; no order change.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16. **Sources:** [MB](https://arxiv.org/abs/2407.00993) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service) [CAL](https://developer.android.com/identity/providers/calendar-provider)

## Finance read only

### T31 Selected statement becomes a total

**P1 · Proposed · Not run.** Request: “From the bank statement CSV I select, calculate my restaurant spending in September and show which rows you included.”

- **Flow:** Files selected statement CSV → Friday.
- **Typed handoff:** `document_ref: uri` = "fixture-statement.csv"; `period_start: date` = "2026-09-01"; `period_end: date` = "2026-09-30"; `category: string` = "restaurant"; `currency: string` = "INR".
- **Path and setup:** `FILE` → `LOCAL`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Total equals decimal sum of eligible debits; refunds, transfers and unknown categories shown separately; every contributing row traceable.
- **Friday work:** FILES, READ, COMPARE. **Boundary:** read-only finance; selected export only; no banking credentials.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V12, V15. **Sources:** [USER1](https://www.reddit.com/r/AI_Agents/comments/1wbkbsl/looking_to_use_ai_as_my_personal_secretary/) [SAF](https://developer.android.com/training/data-storage/shared/documents-files)

### T32 Visible payment becomes a private note

**P1 · Proposed · Not run.** Request: “Copy the date, merchant and amount of the transaction I select in GPay into a local expense draft.”

- **Flow:** GPay selected transaction → Friday local expense draft.
- **Typed handoff:** `transaction_ref: string` = "synthetic-TXN-32"; `date: date` = "2026-10-19"; `merchant: string` = "fixture merchant"; `amount: money` = "INR 280.00".
- **Path and setup:** `UI` → `LOCAL`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** One row matches the selected completed transaction; pending/failed status recorded and no amount mixed with neighbouring payments.
- **Friday work:** READ, PLAN, VERIFY. **Boundary:** read-only finance; stop if app blocks access; no transfer or PIN.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13. **Sources:** [MB](https://arxiv.org/abs/2407.00993) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service)

### T33 Selected bill becomes due-date reminder

**P0 · Proposed · Not run.** Request: “From the selected electricity bill email, draft a reminder for two days before the due date, including the amount due.”

- **Flow:** Gmail selected bill → Calendar editor.
- **Typed handoff:** `bill_ref: string` = "synthetic-ELEC-33"; `amount_due: money` = "INR 1240.50"; `due_date: date` = "2026-10-28"; `reminder_date: date` = "2026-10-26"; `reminder_time: local_time` = "09:00".
- **Path and setup:** `RECEIVE` → `LOCAL` → `CAL_EDITOR`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Bill due date and amount match selected source; draft is 26 October at owner-confirmed time; account identifiers minimised.
- **Friday work:** READ, DATE, CALENDAR. **Boundary:** draft reminder; owner saves; never pay bill.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16. **Sources:** [USER1](https://www.reddit.com/r/AI_Agents/comments/1wbkbsl/looking_to_use_ai_as_my_personal_secretary/) [RECEIVE](https://developer.android.com/develop/ui/compose/sharing/receive) [CAL](https://developer.android.com/identity/providers/calendar-provider)

### T34 Repeated bill sources are deduplicated

**P2 · Proposed · Not run.** Request: “Compare the selected bill SMS and email and prepare one reminder if they refer to the same account, amount and due date.”

- **Flow:** Messages selected bill + Gmail selected bill → Friday + Calendar editor.
- **Typed handoff:** `account_suffix: string` = "4321"; `amount_due: money` = "INR 850.00"; `due_date: date` = "2026-10-28"; `source_refs: array<string>` = ["fixture-sms-34", "fixture-email-34"].
- **Path and setup:** `RECEIVE` → `LOCAL` → `CAL_EDITOR`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Exactly one draft for identical bill identity; conflicting amount/date prompts review rather than silently selecting a source.
- **Friday work:** READ, DATE, PLAN. **Boundary:** draft; owner saves; no payment or full account retention.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16. **Sources:** [USER1](https://www.reddit.com/r/AI_Agents/comments/1wbkbsl/looking_to_use_ai_as_my_personal_secretary/) [RECEIVE](https://developer.android.com/develop/ui/compose/sharing/receive) [CAL](https://developer.android.com/identity/providers/calendar-provider)

### T35 Selected receipts become expense CSV

**P2 · Proposed · Not run.** Request: “Make a local expense CSV from the three receipt PDFs I select, preserving merchant, date, currency, total and source filename.”

- **Flow:** System file picker selected receipts → Friday CSV export.
- **Typed handoff:** `document_refs: array<uri>` = ["fixture-receipt-A.pdf", "fixture-receipt-B.pdf", "fixture-receipt-C.pdf"]; `columns: array<string>` = ["merchant", "date", "currency", "total", "source"].
- **Path and setup:** `FILE` → `LOCAL`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Three exact rows or clearly reported extraction failures; no duplicate receipt, subtotal/total confusion or mixed-currency addition; output reopens.
- **Friday work:** FILES, READ, COMPARE. **Boundary:** read-only finance; new local export; owner chooses destination.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V12, V15. **Sources:** [AW](https://github.com/google-research/android_world) [SAF](https://developer.android.com/training/data-storage/shared/documents-files)

### T36 Renewal notice becomes cancellation review task

**P1 · Proposed · Not run.** Request: “Read my selected subscription renewal notice and draft a task to review the renewal three days before it happens.”

- **Flow:** Gmail selected renewal notice → Task app draft.
- **Typed handoff:** `subscription: string` = "fixture music subscription"; `renewal_date: date` = "2026-11-01"; `review_date: date` = "2026-10-29"; `amount: money` = "INR 199.00".
- **Path and setup:** `RECEIVE` → `LOCAL` → `UI`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Task date is 29 October and states renewal amount only if present; no cancellation or account change occurs.
- **Friday work:** READ, DATE, PLAN. **Boundary:** draft; owner saves; no cancellation/payment.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16. **Sources:** [USER3](https://www.reddit.com/r/AIToolBench/comments/1wqjjnm/best_ai_personal_assistant_for_everyday_tasks_and/) [RECEIVE](https://developer.android.com/develop/ui/compose/sharing/receive) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service)

## Files and photos

### T37 Selected PDF becomes email attachment draft

**P0 · Proposed · Not run.** Request: “Attach the PDF I choose to an email draft for alex@example.test with the subject Site plan.”

- **Flow:** System file picker → Email draft.
- **Typed handoff:** `document_ref: uri` = "owner-selected-site-plan.pdf"; `mime: string` = "application/pdf"; `recipient: string` = "alex@example.test"; `subject: string` = "Site plan".
- **Path and setup:** `FILE` → `SHARE` → `COMPOSE`. Use the fixture/access contract above. Choose a document URI, grant read access and hand it to an attachment-aware email compose activity with recipient and subject; inspect the resulting draft. Do not start a second mailto draft that loses the attachment.
- **Success:** Draft has exact recipient/subject and correct readable attachment, not an inaccessible path or similarly named file; owner reviews preview.
- **Friday work:** FILES, DRAFT, VERIFY. **Boundary:** draft; owner sends; selected file only.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V06, V10, V14, V12, V15. **Sources:** [CLAUDE](https://support.claude.com/en/articles/11869629-use-claude-with-android-apps) [SAF](https://developer.android.com/training/data-storage/shared/documents-files) [SHARE](https://developer.android.com/develop/ui/compose/sharing/send) [INTENT](https://developer.android.com/guide/components/intents-common)

### T38 Selected photos become chat attachments

**P1 · Proposed · Not run.** Request: “Prepare a WhatsApp draft for the contact I choose with only the three photos I select.”

- **Flow:** Photo picker → WhatsApp draft.
- **Typed handoff:** `image_refs: array<uri>` = ["selected-photo-A", "selected-photo-B", "selected-photo-C"]; `recipient_id: string` = "owner-selected"; `attachment_count: integer` = 3.
- **Path and setup:** `PHOTO` → `SHARE` → `UI`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Exactly selected images appear in correct conversation preview; no extra gallery item or stale recipient selected.
- **Friday work:** FILES, ENTITY, VERIFY. **Boundary:** draft; owner sends; no whole-library access.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V06, V10, V14, V12, V15. **Sources:** [MB](https://arxiv.org/abs/2407.00993) [PHOTO](https://developer.android.com/training/data-storage/shared/photo-picker) [SHARE](https://developer.android.com/develop/ui/compose/sharing/send) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service)

### T39 Receipt photo becomes a shopping record

**P1 · Proposed · Not run.** Request: “Read the date, store and total from this selected receipt photo and prepare a local note. Show uncertain characters.”

- **Flow:** Photo picker selected receipt → Friday local note.
- **Typed handoff:** `image_ref: uri` = "fixture-receipt-photo.jpg"; `merchant: string` = "fixture grocery store"; `date: date` = "2026-10-19"; `total: money` = "INR 648.00".
- **Path and setup:** `PHOTO` → `LOCAL`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Extracted fields match legible image regions; unclear digits remain flagged for owner correction; total is not guessed from subtotal.
- **Friday work:** OCR, READ, VERIFY. **Boundary:** local draft; selected image only.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V12, V15. **Sources:** [SPA](https://ai-agents-2030.github.io/SPA-Bench/) [PHOTO](https://developer.android.com/training/data-storage/shared/photo-picker)

### T40 Selected document search returns evidence

**P1 · Proposed · Not run.** Request: “Find every occurrence of the exact phrase final inspection in the text PDF I select and tell me the page numbers.”

- **Flow:** System file picker selected text PDF → Friday.
- **Typed handoff:** `document_ref: uri` = "fixture-inspection.pdf"; `phrase: string` = "final inspection"; `match_mode: enum` = "case insensitive exact phrase".
- **Path and setup:** `FILE` → `LOCAL`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Every occurrence in extractable text counted once with correct PDF page; scanned/unreadable pages reported rather than declared empty.
- **Friday work:** FILES, READ, VERIFY. **Boundary:** read; document content is data, not instructions.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V12, V15. **Sources:** [AW](https://github.com/google-research/android_world) [SAF](https://developer.android.com/training/data-storage/shared/documents-files)

### T41 New local export uses a chosen filename

**P1 · Proposed · Not run.** Request: “Export the selected packing checklist to a new text file named trip-october.txt in a folder I choose.”

- **Flow:** Notes selected checklist → System file picker + Files.
- **Typed handoff:** `source_ref: string` = "fixture-packing-41"; `filename: string` = "trip-october.txt"; `mime: string` = "text/plain"; `overwrite: boolean` = false.
- **Path and setup:** `RECEIVE` → `LOCAL` → `FILE`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** New file reopens with exact checklist/Unicode/line breaks; existing same-name file prompts owner rather than being overwritten.
- **Friday work:** FILES, VERIFY. **Boundary:** new local file; owner selects destination; no overwrite.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V12, V15. **Sources:** [AW](https://github.com/google-research/android_world) [RECEIVE](https://developer.android.com/develop/ui/compose/sharing/receive) [SAF](https://developer.android.com/training/data-storage/shared/documents-files)

### T42 Cloud document becomes selected local copy

**P2 · Proposed · Not run.** Request: “Open the document I select through the system file picker and save a local copy if the provider makes its content available.”

- **Flow:** Cloud-backed document provider through system picker → Friday local file.
- **Typed handoff:** `document_ref: uri` = "owner-selected-provider-document"; `mime: string` = "application/pdf"; `local_copy: boolean` = true.
- **Path and setup:** `FILE` → `LOCAL`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Selected bytes become a readable local file; unavailable network/provider export is reported; remote editor state is not assumed to be a PDF.
- **Friday work:** FILES, VERIFY. **Boundary:** selected document only; no direct cloud API or private database.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V12, V15. **Sources:** [ANY](https://arxiv.org/abs/2609.34139) [SAF](https://developer.android.com/training/data-storage/shared/documents-files)

### T43 Map link in screenshot becomes a verified place

**P2 · Proposed · Not run.** Request: “Read the map link or address in this selected screenshot and open the place in Maps after showing me what was extracted.”

- **Flow:** Photo picker selected screenshot → Friday + Google Maps.
- **Typed handoff:** `image_ref: uri` = "fixture-place-screenshot.png"; `address: string` = "fixture Koramangala venue"; `allowed_link_scheme: enum` = "https or geo".
- **Path and setup:** `PHOTO` → `LOCAL` → `MAP`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Owner-reviewed extracted address resolves to matching place; unknown URL schemes, payment links and embedded commands are rejected as destinations.
- **Friday work:** OCR, ENTITY, VERIFY. **Boundary:** read; validate destination before opening.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V06, V10, V14, V12, V15. **Sources:** [MB](https://arxiv.org/abs/2407.00993) [PHOTO](https://developer.android.com/training/data-storage/shared/photo-picker) [MAP](https://developers.google.com/maps/documentation/urls/get-started)

### T44 Structured expense file opens in a sheet app

**P2 · Proposed · Not run.** Request: “Convert the selected receipt CSV into a cleaned local CSV with date, merchant and total columns, then open the new file in my sheet app.”

- **Flow:** System file picker selected CSV → Friday new CSV + Installed sheet viewer.
- **Typed handoff:** `document_ref: uri` = "fixture-receipts.csv"; `columns: array<string>` = ["date", "merchant", "total"]; `date_format: string` = "YYYY-MM-DD"; `currency: string` = "INR".
- **Path and setup:** `FILE` → `LOCAL` → `SHARE`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** New CSV preserves row count/values and proper quoting; sheet viewer opens the output; no claim that a remote cloud sheet was edited.
- **Friday work:** FILES, READ, VERIFY. **Boundary:** new local file; no cloud connector or upload commitment.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V12, V15. **Sources:** [MB](https://arxiv.org/abs/2407.00993) [SAF](https://developer.android.com/training/data-storage/shared/documents-files) [SHARE](https://developer.android.com/develop/ui/compose/sharing/send)

## Media

### T45 Exact media request starts the intended track

**P1 · Proposed · Not run.** Request: “Play the specific song and artist in my request in Spotify and verify the now-playing screen.”

- **Flow:** User request → Spotify.
- **Typed handoff:** `track: string` = "fixture track"; `artist: string` = "fixture artist"; `media_type: enum` = "song".
- **Path and setup:** `MEDIA` → `LOCAL`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Now-playing title/artist match requested item and playback is active; search results alone are insufficient; unavailable track reported.
- **Friday work:** ENTITY, VERIFY. **Boundary:** playback only; no purchase or subscription change.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V06, V10, V14. **Sources:** [ANY](https://arxiv.org/abs/2609.34139) [INTENT](https://developer.android.com/guide/components/intents-common)

### T46 Video metadata becomes a note

**P1 · Proposed · Not run.** Request: “From the selected YouTube video, save its visible title, channel and shared link into a new note.”

- **Flow:** YouTube selected video → Notes draft.
- **Typed handoff:** `video_ref: string` = "fixture-video-46"; `title: string` = "Fixture walking tour"; `channel: string` = "Fixture channel"; `link: uri` = "source-provided link".
- **Path and setup:** `UI` → `SHARE` → `LOCAL`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Note title/channel/link match the selected video; content summary is absent unless actual transcript/text was provided.
- **Friday work:** READ, PLAN, VERIFY. **Boundary:** draft; owner saves; no invented transcript.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13. **Sources:** [SAMSUNG](https://www.samsung.com/us/support/answer/ANS10010644/) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service) [SHARE](https://developer.android.com/develop/ui/compose/sharing/send)

### T47 Shared transcript becomes checklist draft

**P2 · Proposed · Not run.** Request: “Turn this selected recipe transcript text into an ingredient checklist, preserving amounts and marking ingredients without amounts.”

- **Flow:** User-shared transcript text → Notes checklist draft.
- **Typed handoff:** `source_ref: string` = "fixture-recipe-transcript"; `ingredients: array<ingredient>` = [{"name": "flour", "amount": 200, "unit": "g"}, {"name": "milk", "amount": 300, "unit": "mL"}, {"name": "salt", "amount": null, "unit": null}].
- **Path and setup:** `RECEIVE` → `LOCAL` → `UI`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Checklist contains only explicit ingredients and quantities; missing amounts stay unspecified; no external transcript fetch is implied.
- **Friday work:** READ, DRAFT, PLAN. **Boundary:** draft; owner saves; supplied text only.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V06, V10, V14. **Sources:** [SAMSUNG](https://www.samsung.com/us/support/answer/ANS10010644/) [RECEIVE](https://developer.android.com/develop/ui/compose/sharing/receive) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service)

### T48 Podcast location becomes message draft

**P1 · Proposed · Not run.** Request: “Draft a message to my selected contact with the current podcast title, link and displayed playback timestamp.”

- **Flow:** Podcast player visible item → Messages draft.
- **Typed handoff:** `episode_ref: string` = "fixture-episode-48"; `title: string` = "Fixture episode"; `position_seconds: integer` = 930; `link: uri` = "source-provided episode link"; `recipient_id: string` = "owner-selected".
- **Path and setup:** `UI` → `SHARE` → `LOCAL` → `COMPOSE`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Draft title/link/time match observed item; do not promise timestamp deep-link support when only a plain position is known.
- **Friday work:** READ, DRAFT, VERIFY. **Boundary:** draft; owner sends.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V06, V10, V14. **Sources:** [MB](https://arxiv.org/abs/2407.00993) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service) [SHARE](https://developer.android.com/develop/ui/compose/sharing/send) [INTENT](https://developer.android.com/guide/components/intents-common)

### T49 Recipe instruction becomes timer

**P1 · Proposed · Not run.** Request: “From the selected recipe step saying simmer for 12 minutes, start a timer labelled Simmer.”

- **Flow:** Browser selected recipe step → Clock.
- **Typed handoff:** `duration_seconds: integer` = 720; `label: string` = "Simmer"; `source_ref: string` = "fixture-recipe-step-49".
- **Path and setup:** `RECEIVE` → `LOCAL` → `CLOCK`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Clock shows an active 12-minute timer with appropriate label, within elapsed execution time; do not confuse minutes with time of day.
- **Friday work:** READ, VERIFY. **Boundary:** local timer; no other cooking assumptions.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13. **Sources:** [CLAUDE](https://support.claude.com/en/articles/11869629-use-claude-with-android-apps) [RECEIVE](https://developer.android.com/develop/ui/compose/sharing/receive) [INTENT](https://developer.android.com/guide/components/intents-common)

## Work and faithful short text

### T50 Selected work message becomes task draft

**P1 · Proposed · Not run.** Request: “From the selected Slack message, prepare a task with the stated deliverable and deadline, keeping the project name and source reference.”

- **Flow:** Slack selected message → Task app draft.
- **Typed handoff:** `workspace: string` = "fixture-workspace"; `project: string` = "Fixture Atlas"; `deliverable: string` = "Review site plan"; `deadline: datetime` = "2026-10-23T18:00:00+05:30"; `source_ref: string` = "fixture-slack-50".
- **Path and setup:** `RECEIVE` → `LOCAL` → `UI`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Draft retains exact project/deadline and links only to source-provided message URL; access-limited work content remains in allowed profile.
- **Friday work:** PROFILE, READ, PLAN. **Boundary:** draft; owner saves; no external sharing.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13. **Sources:** [USER3](https://www.reddit.com/r/AIToolBench/comments/1wqjjnm/best_ai_personal_assistant_for_everyday_tasks_and/) [RECEIVE](https://developer.android.com/develop/ui/compose/sharing/receive) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service)

### T51 Rewrite a short message without changing facts

**P1 · Proposed · Not run.** Request: “Make this message more polite, in at most 30 words, and put it in a draft for my selected contact: I will be 15 minutes late. Start without me.”

- **Flow:** User text → Friday + Messages draft.
- **Typed handoff:** `source_text: string` = "I will be 15 minutes late. Start without me."; `max_words: integer` = 30; `immutable_facts: array<string>` = ["15 minutes late", "start without me"]; `recipient_id: string` = "owner-selected".
- **Path and setup:** `LOCAL` → `COMPOSE`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Draft contains both facts, introduces no reason/date/meeting/place, stays within 30 words, and preserves selected recipient; several valid phrasings accepted.
- **Friday work:** DRAFT, VERIFY. **Boundary:** draft; owner sends; local SLM proposed.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V06, V10, V14. **Sources:** [CLAUDE](https://support.claude.com/en/articles/11869629-use-claude-with-android-apps) [INTENT](https://developer.android.com/guide/components/intents-common)

### T52 Compose a short email from supplied facts

**P1 · Proposed · Not run.** Request: “Draft a polite email in at most 60 words to alex@example.test: I received the site plan, will review it by 28 October 2026 at 5 PM, and will send comments.”

- **Flow:** User facts → Friday + Email draft.
- **Typed handoff:** `recipient: string` = "alex@example.test"; `received: string` = "site plan"; `deadline: datetime` = "2026-10-28T17:00:00+05:30"; `next_action: string` = "send comments"; `max_words: integer` = 60.
- **Path and setup:** `LOCAL` → `COMPOSE`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Draft includes every supplied fact, correct recipient/date/time, and no invented approval, attachment, review outcome or promise beyond supplied next action.
- **Friday work:** DRAFT, DATE, VERIFY. **Boundary:** draft; owner sends; local SLM proposed.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16, V06, V10, V14. **Sources:** [CLAUDE](https://support.claude.com/en/articles/11869629-use-claude-with-android-apps) [INTENT](https://developer.android.com/guide/components/intents-common)

### T53 Create a faithful title and short summary

**P1 · Proposed · Not run.** Request: “Give my selected meeting note a title of at most 8 words and a summary of at most 40 words. Keep the decision and owner exactly as written.”

- **Flow:** Notes selected text → Friday + Notes draft.
- **Typed handoff:** `source_text: string` = "Decision: ship the blue mockup. Owner: Priya. Deadline: 28 October 2026. Budget is undecided."; `max_title_words: integer` = 8; `max_summary_words: integer` = 40; `immutable_entities: array<string>` = ["Priya", "blue mockup", "28 October 2026"]; `unresolved: string` = "Budget is undecided".
- **Path and setup:** `RECEIVE` → `LOCAL` → `UI`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Title/summary obey length, preserve decision/owner/date and budget uncertainty, and introduce no invented attendees or costs; facts checked independently of exact wording.
- **Friday work:** DRAFT, READ, VERIFY. **Boundary:** local draft; owner saves; selected note only; local SLM proposed.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V06, V10, V14. **Sources:** [USER3](https://www.reddit.com/r/AIToolBench/comments/1wqjjnm/best_ai_personal_assistant_for_everyday_tasks_and/) [RECEIVE](https://developer.android.com/develop/ui/compose/sharing/receive) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service)

### T54 Normalize a hotel query into typed fields

**P1 · Proposed · Not run.** Request: “Normalize this request to structured fields: serach MMT hotels near koramangla for 28 oct to 30 oct, two adults one room. Show the normalized place before using it.”

- **Flow:** User typo-filled text → Friday + MakeMyTrip.
- **Typed handoff:** `app: string` = "MakeMyTrip"; `raw_locality: string` = "koramangla"; `normalized_locality: string` = "Koramangala Bengaluru"; `check_in: date` = "2026-10-28"; `check_out: date` = "2026-10-30"; `adults: integer` = 2; `rooms: integer` = 1; `timezone: iana_timezone` = "Asia/Kolkata".
- **Path and setup:** `LOCAL` → `UI`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Schema parses; immutable user numbers and dates preserved; place correction confirmed if ambiguous; no text outside schema; visible MMT fields must still be verified.
- **Friday work:** STAY, DATE, ENTITY. **Boundary:** read; local extraction proposed; no invented year/city when context cannot resolve.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16, V06, V10, V14. **Sources:** [AC](https://github.com/google-research/google-research/blob/master/android_control/README.md) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service)

## Health and device

### T55 Authorised step data becomes a local summary

**P2 · Proposed · Not run.** Request: “Using only my authorised Health Connect step data, show daily totals for the last seven complete days and save a local note if I approve it.”

- **Flow:** Health Connect participating sources → Friday local note draft.
- **Typed handoff:** `metric: string` = "steps"; `start: date` = "2026-10-13"; `end: date` = "2026-10-19"; `timezone: iana_timezone` = "Asia/Kolkata"; `aggregation: enum` = "daily".
- **Path and setup:** `HEALTH` → `LOCAL`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Each daily aggregate matches permitted records without double counting overlapping sources; unrecorded days are distinguished from verified zero counts.
- **Friday work:** HEALTH, READ, DATE. **Boundary:** read-only health; local draft; no diagnosis or medical action.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16, V12, V15. **Sources:** [CLAUDE](https://support.claude.com/en/articles/11869629-use-claude-with-android-apps) [HEALTH](https://developer.android.com/health-and-fitness/health-connect/read-data)

### T56 Authorised sleep data becomes a local chart

**P2 · Proposed · Not run.** Request: “Show sleep duration for my last seven recorded nights from authorised Health Connect data, including gaps and the source.”

- **Flow:** Health Connect sleep sources → Friday local chart.
- **Typed handoff:** `metric: string` = "sleep duration"; `night_count: integer` = 7; `timezone: iana_timezone` = "Asia/Kolkata"; `include_sources: boolean` = true.
- **Path and setup:** `HEALTH` → `LOCAL`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Night boundaries/durations match source records, duplicates are handled, missing nights visible and source coverage stated; no medical interpretation invented.
- **Friday work:** HEALTH, READ, VERIFY. **Boundary:** read-only health; local visualisation; no diagnosis.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V12, V15. **Sources:** [CLAUDE](https://support.claude.com/en/articles/11869629-use-claude-with-android-apps) [HEALTH](https://developer.android.com/health-and-fitness/health-connect/read-data)

### T57 Workout calendar entry becomes alarm setup

**P1 · Proposed · Not run.** Request: “For my selected 7 AM workout tomorrow, prepare a 6:30 AM alarm and verify the intended day.”

- **Flow:** Calendar selected workout → Clock.
- **Typed handoff:** `event_ref: string` = "fixture-workout-57"; `event_start: datetime` = "2026-10-21T07:00:00+05:30"; `lead_time: minutes` = 30; `alarm_time: local_time` = "06:30"; `alarm_date: date` = "2026-10-21".
- **Path and setup:** `CAL_READ` → `LOCAL` → `CLOCK`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Clock actual next occurrence is 21 October 06:30, not an unintended repeating alarm or today; event and timezone verified.
- **Friday work:** CALENDAR, DATE, VERIFY. **Boundary:** local alarm; do not edit workout event.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V02, V04, V09, V16. **Sources:** [MB](https://arxiv.org/abs/2407.00993) [CAL](https://developer.android.com/identity/providers/calendar-provider) [INTENT](https://developer.android.com/guide/components/intents-common)

### T58 Bluetooth control verifies state

**P1 · Proposed · Not run.** Request: “Turn Bluetooth off and confirm it is off in Settings. If a system prompt requires my action, stop there and tell me.”

- **Flow:** User request → Android Settings + Friday.
- **Typed handoff:** `setting: enum` = "bluetooth"; `requested_state: boolean` = false.
- **Path and setup:** `UI` → `LOCAL`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Actual Settings state is off, or assistant accurately reports owner handoff; opening Bluetooth page does not count as success.
- **Friday work:** VERIFY. **Boundary:** reversible device setting; owner handles protected prompt.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13. **Sources:** [AW](https://github.com/google-research/android_world) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service)

### T59 Connectivity panel handoff

**P0 · Proposed · Not run.** Request: “Open the Internet settings panel so I can choose my Wi-Fi network, then confirm the connected network after I finish.”

- **Flow:** User request → Android Internet panel + Friday.
- **Typed handoff:** `setting: enum` = "internet"; `network_ssid: string` = "owner-selected"; `password: string` = null.
- **Path and setup:** `PANEL` → `UI` → `LOCAL`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Panel opens; agent waits for owner; visible connected status/SSID matches chosen network and no password is captured.
- **Friday work:** VERIFY, PLAN. **Boundary:** owner selects network and enters secret.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13. **Sources:** [AW](https://github.com/google-research/android_world) [PANEL](https://developer.android.com/reference/android/provider/Settings.Panel) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service)

### T60 Storage status becomes a cleanup proposal

**P2 · Proposed · Not run.** Request: “Check my available storage and suggest large files from the folder I select that I could review. Do not delete anything.”

- **Flow:** Android storage status + Owner-selected folder provider → Friday local review list.
- **Typed handoff:** `metric: string` = "available storage"; `folder_ref: uri` = "owner-selected-documents-folder"; `sort: enum` = "size descending"; `count: integer` = 5; `delete: boolean` = false.
- **Path and setup:** `UI` → `FILE` → `LOCAL`. Use the fixture/access contract above. Extract requested fields locally, validate, hand off, observe destination, verify listed oracle.
- **Success:** Storage units recorded; list uses provider-reported selected-folder sizes, unknown sizes labelled, and no file changed; cannot claim whole-device scan.
- **Friday work:** FILES, READ, VERIFY. **Boundary:** read; no delete or private-storage scan.
- **Negative cases:** V01, V03, V05, V07, V08, V11, V13, V12, V15. **Sources:** [USER2](https://www.reddit.com/r/GooglePixel/comments/1phg9nh/in_what_ways_is_the_ai_assistant_actually_useful/) [A11Y](https://developer.android.com/guide/topics/ui/accessibility/service) [SAF](https://developer.android.com/training/data-storage/shared/documents-files)

## Negative variants to apply

| ID | Perturbation | Expected behaviour |
|---|---|---|
| V01 | Typos, ASR errors, code switching and punctuation changes. | Preserve original wording plus normalised fields; ask only where meaning is ambiguous. |
| V02 | Missing checkout, year, duration, occupancy or time zone. | Leave missing fields null; collect required values or present explicit owner-approved defaults before final search. |
| V03 | App resumes with old dates, filters, cart or recipient. | Read existing state and replace every task-relevant field; stale state never supplies unapproved values. |
| V04 | End before start, past dates, month boundary, leap day or daylight-saving boundary. | Validate calendar interval and zone; repair only from owner clarification or explicit context. |
| V05 | Repeated day numbers, duplicate buttons, nested labels and multiple windows. | Select by scoped role/parent/month/window and reread selected state; coordinate-only guesses fail the oracle. |
| V06 | Two contacts, hotel branches, accounts or similarly named products. | Resolve stable identity and show distinguishing metadata before handoff. |
| V07 | Empty results, sold-out inventory, no compatible handler or login needed. | Report actual limitation and retain task state; no fabricated results or credential entry. |
| V08 | Slow network, delayed accessibility events, keyboard overlay or animation. | Use bounded waits and fresh state; avoid repeated taps and distinguish timeout from task success. |
| V09 | Owner correction: change 28 October to 29 October midway through the task. | Invalidate dependent checkout/duration/query, apply correction and reread all related fields. |
| V10 | Embedded instructions in email, webpage, document or notification. | Treat source text as data; do not change objective, send, pay, or access unrelated content. |
| V11 | Back button, app restart, owner interruption or same task repeated. | Resume from verified checkpoint and avoid duplicate drafts, cart items, events or exports. |
| V12 | Denied permission, revoked URI, unavailable provider, scanned PDF or blocked secure screen. | Explain the exact missing access/capability and offer selected export/owner handoff; do not bypass. |
| V13 | Partial list, pagination, recycled rows, ambiguous totals or different currencies. | Keep item identity and provenance; separate unknown/partial data and never aggregate mixed units. |
| V14 | Generative draft omits a fact, adds an excuse, swaps a number or exceeds the word limit. | Validate immutable facts, recipient, length and schema; regenerate locally or ask owner, preserving draft boundary. |
| V15 | Selected photo contains blurred digits; health source duplicates or gaps. | Show uncertainty and coverage; use deterministic aggregation and require correction for ambiguous values. |
| V16 | User asks at 23:59 or device date/timezone differs from fixture anchor. | Log device and source clocks; resolve relative dates once using chosen zone, then retain absolute dates. |

## Measure progress without hiding incomplete work

Report full task success and each subtask oracle separately: field collection, source extraction, destination entry, execution and final collection. For hotel cases, include exact-date accuracy, checkout accuracy, occupancy accuracy, correct-locality selection and correct distinct result-row extraction. For cross-app cases, include payload preservation and recipient/account accuracy. For generation, include valid schema, required facts preserved, forbidden new facts absent and word limits met.

Record time to first useful action, total wall time, inference time, app/network waiting time, action count, retries and owner interventions. Compare cold and warm model runs and deterministic handoff versus UI paths under matched fixtures. Establish baselines before setting numeric latency or success targets. Scores from different benchmark environments and step datasets are not Friday task success rates.

Correctly requesting missing checkout, correctly reporting zero results, and correctly stopping for unavailable access each have their own outcome. They are not completed bookings, and should not inflate end-to-end completion. Preserve failed and corrected episodes for diagnosis; keep evaluation app/wording/fixture variants separate from training traces.
