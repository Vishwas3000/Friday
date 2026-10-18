# Friday: the system as it is today

_Snapshot 2026-10-18. One page to bring someone (or another AI) up to speed. Details live in `research/` (01–23) and
`docs/DECISIONS.md` (D-001–D-051). Numbers are measured on the owner's phone unless marked "estimate"._

## 1. What Friday is
- **An assistant that operates the owner's Android phone by itself, fully on the phone.** You type a request ("open
  clock and show the stopwatch", "show me my last 10 transactions in GPay"), and Friday opens the right app, reads the
  screen, taps and scrolls until the task is done, then answers.
- **No cloud.** The app has no INTERNET permission. A personal research project for one owner.
- **Built around Laya-Android**, a small *non-generative* decision model. It picks one option from a list (which
  action, which on-screen element); it never writes text.
- **Phone:** Pixel 8 (Tensor G3, 8 GB RAM), Android 16, not rooted.
- **Other machines:**
  - a Mac (16 GB) for development; at most one model job at a time;
  - a rented GPU box for all training (currently unreachable: its SSH port changed).

## 2. How a request flows
```
typed request (or "Hey Friday" while charging)
  │
  ├─ 1. ROUTE      rules (regex) → else Laya picks one of 16 skills (≥ 0.7 confidence) → else ask the owner
  │
  ├─ 2. SKILL      Android intents where possible (alarm, timer, maps, web search, dial, email, media search);
  │                otherwise open the app fresh and run the step loop for the request (or a fixed goal)
  │
  ├─ 3. STEP LOOP  (Assistant.runLaya), up to N steps:
  │     a. memory: an exact remembered path for this goal → replay it, no Laya (~2 s)
  │     b. hint: a similar past success → "goal (last time: tap X)" goes into Laya's goal text
  │     c. read the screen (accessibility tree → candidate list), wait out loading and fingerprint screens
  │     d. done checks: the goal's words already on a selected tab, or the screen looks like the hint's finished screen
  │     e. skim: if nothing on screen matches the goal's words, scroll down up to 4 pages looking for it
  │     f. Laya step: OPERATION first (12 options, the whole screen), then TARGET only for tap / long-press / type,
  │        shown at most 16 elements (shortlist)
  │     g. safety verdict: AUTO / ASK the owner / BLOCK
  │     h. act: click the element (accessibility), scroll, type, back
  │     i. done-on-tap: the tapped element names everything the goal is about → stop
  │     j. stuck: the same action 3 times, or 3 skipped steps → stop
  │
  ├─ 4. ANSWER     "Done", or what was read (money questions: dated amounts, up to the count asked)
  │
  └─ 5. LEARN      answer card: ✓ Right / ✗ Wrong / Show me. Kept runs become remembered paths + training rows;
                   Wrong forgets the path and saves negative rows; "Show me" starts Teach mode
```

## 3. Components

### 3.1 The app (`android/app/src/main/java/dev/layatest/agent/`)
| Part | What it does |
|---|---|
| `AgentService` (AccessibilityService) | Friday's eyes and hands: reads every window's accessibility tree, taps, scrolls, types (`ACTION_SET_TEXT`), presses back. It also hosts a local socket "bridge" (adb-forwarded to the Mac) for tests and tools |
| `MainActivity` | The text box and Run button. Typing starts loading Laya. Also: Teach, the wake-word switch, and "Delete all learned data" |
| `Overlay` | The status banner ("Thinking… step 2"), approval prompts (Run / Skip / Stop) and the answer card |
| `Assistant` | One request end to end: routing, skills, the step loop, memory, the request log |
| `Skills.kt`, `PhoneSkills`, `MediaSkills`, `PeopleSkills` | The 16 skills: open_app, play_music, play_video, spend_query, balance_query, bill_query, device_setting, device_status, alarm_timer, call, message, navigate, web_search, ride, shopping, photos. Rules and slot parsing live here and in `Slots.kt` |
| `Candidates.kt` | Turns the tree into Laya's candidate list (below), plus the shortlist |
| `LayaEngine`, `LayaInput`, `Tokenizer` | Builds Laya's input on the phone, byte-identical to the Python reference, runs ONNX Runtime and decodes |
| `Routes.kt` | UI memory: remembered paths, similar-goal hints, word helpers |
| `Teach.kt` | The owner demonstrates a task; it is saved as a path and as training rows |
| `Safety.kt` | Per-action verdicts |
| `WakeWord.kt` | "Hey Friday" / "Hi Friday" (openWakeWord models on ONNX Runtime) |
| `AppResolver`, `AppProfiler` | Installed apps, which app does what, aliases, money apps |

### 3.2 What Laya sees: the candidate list
- **Which nodes:** visible, not in the keyboard window, with an action flag (clickable, long-clickable, editable,
  scrollable, checkable).
- **Label:** its own text, else its content description, else its hint, else its children's text (60 characters).
- **Order and duplicates:** de-duplicated by (box, label); ordered top to bottom, then left to right.
- **Rendering:** `[i] <label, first 40 chars> (<Class>) <flags>`, e.g. `[3] See transaction history (Button)`.
- **Unlabelled icons** show as `- (ImageView)`. Resource ids are kept (for memory and the shortlist) but never shown to
  Laya.

### 3.3 Laya-Android (the model)
- **What it is:** `ByunByun/laya-android`, a fine-tune of Convai's Laya on AndroidControl: 88k steps from 15,283
  recorded tasks across 833 apps. Apache-2.0.
- **Size:** 322M parameters, of which 196M is the 256k-word vocabulary table.
- **Architecture:**
  - an mmBERT encoder (ModernBERT: 22 layers, 768 wide; local attention, a global layer every 3rd);
  - a 2-layer decision head;
  - a scorer that reads one score per option marker.
- **Input:** a question, its options, then the state `{"goal", "history": last 3 actions, "ui": [candidates]}`. Up to
  3,072 tokens per question.
- **Two questions per step:**
  - operation: CLICK, LONG_PRESS, SCROLL_UP/DOWN/LEFT/RIGHT, OPEN_APP, INPUT_TEXT, BACK, HOME, WAIT, DONE;
  - target: which candidate.
  - Calibrated probabilities (T = 1.161).
- **Published quality** (offline, step-level, AndroidControl):
  - type 76.7%, grounding 61.7%;
  - 0.54–0.57 "policy joint" on unseen apps;
  - DONE recall only 0.55–0.75;
  - target accuracy falls with more elements: 0.84 from 1–5 elements, 0.54 from 21–50.
- **Can't:** write text (app names, search words, messages), see pixels, remember more than the last 3 actions.
- **On the phone:**
  - 8-bit weights (MatMulNBits, 578 MB file) on onnxruntime-android 1.31, CPU, 4 threads, arena off.
  - Loaded on demand; kept warm for 3 minutes after a request.
  - Cold load: model session about 2.3 s; the tokenizer takes about 8 s, in parallel.

### 3.4 Memory and learning (loop 1, on the phone)
- **Remembered paths ("routes"):** each step's element by package, resource id, class and label, keyed on the goal's
  content words. An exact match replays without Laya. They come from Teach demos, runs the owner confirms (✓), and
  runs whose result was checked automatically ("verified").
- **Hints:** a past success whose goal shares ≥ 50% of its about-words → "(last time: tap X)" goes into Laya's goal,
  and its finished screen counts as "done".
- **Training rows:** every kept run and every Teach demo is saved in the training format (state, goal, history,
  action) for fine-tuning on the GPU box (loop 2, not run yet).
- **Teach mode:** the owner does the task by hand. The app records it from accessibility events; for apps that don't
  report clicks, raw touches come over adb from the Mac.

### 3.5 Safety (every action, including remembered ones)
- **Money apps** (GPay, PhonePe, Paytm, BHIM, banks, cards) are **read-only**: no typing, and no tapping Pay, Send,
  Transfer and the like. Unlabelled buttons there ask the owner.
- **Elsewhere:** taps on words like send, pay, buy, order, book, delete, call, post, share, confirm, transfer… **ask
  the owner** (Run / Skip / Stop). Typing runs on its own.
- **Fingerprint and screen-lock prompts:** Friday waits up to 90 s for the owner and never acts on them.

### 3.6 Wake word
- "Hey Friday" / "Hi Friday", **off by default**.
- Opt-in "Listen while charging" only: there is no always-on microphone.
- 3.7 ms per audio frame. A spoken "hey friday" scores 0.87; a control phrase 0.001.

### 3.7 Logs and test tools (`scripts/`)
| Tool | Purpose |
|---|---|
| Request log (`files/requests.jsonl` on the phone; `scripts/requests_log.py`) | Every request: answer, total time, timeline ("+3.2s step 2 …, laya 3130 ms: 20 cands, 733 tokens") |
| `task_suite.py` + `suite_tasks.json` | 26 real tasks with automatic checks (app in front, screen words, selected tab, answer, media playing, a setting). Unattended, muted, memory off; runs saved and comparable. **Built, no baseline run yet** |
| `shortlist_eval.py`, `speed_eval.py`, `bench_device.py` | Decision-agreement and speed checks on 52 recorded screens; on-phone benchmark |
| Bridge commands | `ask`, `state`, `routes`, `requests`, `selfcheck`, `bench`, `teach`… for testing from the Mac |

## 4. Measured performance
| What | Number |
|---|---|
| One Laya pass, 636 tokens, 8-bit (was fp16) | 1.73–1.85 s (was 3.83–3.90 s); 651 MB RAM (was 753) |
| Screen read | 0.2–1.0 s (mostly waiting 300 ms for the screen to settle) |
| A step on a crowded screen (35 elements), before and after the shortlist | about 19 s → about 8 s |
| Live steps, cost per token | slower than the bench: the app being driven competes for the same CPU cores, and heat adds to it |
| Remembered task (no Laya) | about 2 s |
| "open my orders on meesho" (3 steps) | 25.2 s (was 52.0 s on fp16) |
| GPay history | 13.9 s (was 54 s), but the final tap landed on the wrong button (§5) |
| Reworded Clock task with a hint | 13 s (35 s without) |

## 5. What works and what doesn't (owner's real requests, 2026-10-17/18)
**Works:**
- opening apps;
- intent-backed skills: alarms, timers, maps, web search, dial, YouTube search and play, media keys;
- tab navigation in simple apps (Clock);
- remembered tasks;
- reading amounts once on the right page.

**Fails or is unreliable:**
| Request | What happened | Root cause |
|---|---|---|
| GPay "last 10 transactions" | tapped Money tab, then You tab; later opened Scan QR | the wording didn't match the remembered path; Laya guesses in unfamiliar layouts; the tap went to the centre of the element's box, where GPay's floating Scan QR button sits (fixes built, untested) |
| "Open bistro and order a dessert" | opened Swiggy; tapped one button 3 times | the named app was ignored when not on the skill's list (fixed); Laya stuck on a 4-element screen |
| Amazon "check my last order" | searched instead of going to orders | Laya doesn't know where features live in an app |
| Slack "check messages from X" | stopped | as above |

**Root causes, in general:**
1. **Laya decides each step from scratch** with no knowledge of the app's layout. Multi-step tasks in unfamiliar apps
   compound its ~55–60% step accuracy.
2. **Understanding of the request is scattered** across a dozen parsers that disagree (research/23 lists ten bugs).
3. **Weak "am I done?" sense:** it once overshot and started a real timer.
4. **Unlabelled icons are invisible** to a text-only model.
5. **Slow on crowded screens and on a hot phone.**

## 6. Built but not yet verified on the phone (uncommitted at the time of writing)
- Taps click the element through accessibility (`ACTION_CLICK` on the live node, re-found just before acting), with a
  finger tap as fallback. Meant to stop taps landing on overlays such as GPay's Scan QR.
- Money reads wait up to 5 s for the list to load before reading.

## 7. Planned (in order)
1. **Baseline the task suite**, then fix failures one at a time until the owner's top tasks pass. The phone must be
   connected over USB.
2. **Task card (D-051, research/23):** read each request once into one structured card (skill, app, about-words,
   query and slots, read or act, risk, done-check), used by every stage. Rules fill it; a small on-phone text LLM
   (the "Writer", LFM2.5-1.2B in llama.cpp, D-041, not built) fills it when rules can't.
3. **App map + element ranker (ideation; prior work in research/24):**
   - **The map:** learn each app's screens and what every element leads to, from the owner's own use, Friday's runs
     and safe exploration while charging. Plan paths on it with no model.
   - **The ranker:** a tiny learned model picks the likely elements on screens the map doesn't know.
   - The aim is to spend storage, not compute.
4. **Fine-tune Laya on the GPU box (research/22):**
   - training data: corrections, Teach demos and verified runs, plus public AndroidControl data replayed;
   - a smaller student: the vocabulary cut to the words UI text uses, and fewer layers;
   - ship only if it beats the current model on the task suite.

## 8. Rules and constraints
- **Everything runs on the phone.** No network permission. Training happens only on the GPU box, never the Mac; the
  Mac runs one model job at a time.
- **Money apps stay read-only.** Risky taps ask the owner. Friday never handles passwords, PINs or fingerprints.
- **The repo is public:** no personal data (names, amounts, the request log) is committed. `captures/` is git-ignored.
  "Delete all learned data" on the phone wipes the routes, Teach recordings and the request log.
- **Every decision is logged** with its reasons in `docs/DECISIONS.md`. Research notes carry trust tags.

## 9. Where things are
- **Code:** `android/app/src/main/java/dev/layatest/agent/` (app), `scripts/` (Python tools).
- **Models:** `models/` (git-ignored): `laya-android.nb8-h-e16.onnx` (shipping), `.fp16.onnx` (rollback), `tokenizer.json`.
- **Research notes:**
  - 01 Laya and Laya-Android
  - 06 ONNX export
  - 13 text LLM (Writer)
  - 14–15 fine-tune data and method
  - 16 wake word
  - 18–19 speed
  - 20 UI memory
  - 21 working prototypes
  - 22 training plan
  - 23 task card
  - 24 app maps and exploration (prior art)
- **Decisions:** D-016 load on demand, D-025/026 safety, D-033 Teach, D-040 memory, D-041 Writer, D-042 compute rule,
  D-046 no always-on mic, D-047 faster steps and the 8-bit model, D-048 learning loops, D-049 task suite and shortlist,
  D-050 fixes from failed requests, D-051 task card.
