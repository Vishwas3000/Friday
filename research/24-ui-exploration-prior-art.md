# 24 — Exploring apps: app maps, screen identity, datasets and small UI models (prior art)

_2026-10-18 · Trust tags: [primary] read at source · [secondary] blog, forum or survey · [measured] run here · [derived] computed from cited numbers · [UNCERTAIN] couldn't confirm_

The owner wants Friday to "understand the UI so it can explore apps properly", with the whole system as light as possible. This file checks the design below against published work. It extends research/15 §1 (app knowledge for choosing apps), research/21 §4 (replay memory) and research/14 §2 (datasets), and doesn't repeat them.

**The design under review:**
- **An app map per app:** a UI transition graph. Abstract screens are nodes; elements are edges, each annotated with what it leads to. Built by (a) watching the owner's own use through accessibility events, (b) safe exploration while charging, (c) Friday's own runs.
- **At request time:** plan a path on the map to the screen that matches the goal, with no model. On unknown screens, a small learned element ranker makes a shortlist and Laya picks.
- **Training on the GPU box:** the ranker and a smaller Laya student, from public data plus the owner's map. A teacher LLM writes goals for screens ("reverse task synthesis").
- **Testing:** offline navigation over recorded maps (no phone), plus the on-phone task suite (D-049).

**How we looked:** six parallel web-research passes (exploration memory 2017–2024 and 2024–2026; screen identity and safety; datasets; small models; learning by watching), plus our own reading of UINav, AppFlow, TVWorld, an app-update study and Android's source. Where a paper was vague, we read the released code or files (DroidBot, Fastbot, MobileGPT, AutoDroid's maps). Nothing was installed or run on the phone. One count was made on the Mac: the scripts in Laya's tokenizer [measured].

## Answer in one screen
1. **The design follows where the field went in 2025–2026, and maps help small models most** [primary].
   - AutoDroid's memory added 40.5 points of task completion to a 7B model, but 7.8 to GPT-4.
   - UI-KOBE's exploration graph took a 4B model from 58.6% to 70.7% on AndroidWorld.
   - GraphPilot checks one LLM-written plan against the graph: 74.1% vs 62.0% on DroidTask, with 1.03 LLM calls per task instead of 4.54.
2. **Maps are small, and deciding that two screens are "the same" needs no model** [primary].
   - AutoDroid's released maps hold 12–42 abstract screens and 31–106 transitions per app (our parse of its files). LLM-Explorer averages 128 states per app.
   - The key that works: package + activity + the *set* of (class, resource id, action flags), with no text, position or list length. LLM-Explorer found this rule beat asking an LLM.
   - Text in the key explodes the graph: Mobile3M kept ~1M "unique" pages for 49 apps.
3. **No paper navigates new tasks with no model** [primary]. Planning on the graph removes most calls. A model still has to say which screen is the goal, and what to do on screens the map lacks. That can be one Laya pass per request, not one per step.
4. **Watching the owner is the right first source, but click events miss a lot** [primary].
   - The closest study (ODIM, 2025, a stock phone) captured every screen but only 60.8% of gestures.
   - Jetpack Compose sends no click event for a finger tap. Android's pass-through touch mode for accessibility services is a hidden test API. So edges must be inferred from the trees before and after.
   - Watching maps only the owner's own paths: ERICA's coverage levelled off after 5–8 users. That suits repeat tasks.
5. **Nobody has explored a logged-in personal phone safely** [primary].
   - Every system used emulators, test accounts or nothing. DroidBot's simplest policy even prefers buttons saying "allow", "agree" and "ok".
   - In 144 hours of industrial test runs, logouts were 23% of the dead ends, and Monkey turned airplane mode on.
   - Risk classifiers are weak: AutoDroid's LLM check had precision 75.0% and recall 80.5%.
6. **Public data with trees and transitions exists; Indian-app data barely does** [primary].
   - MobileViews (MIT) has AccessibilityService trees and a from → to transition table for 19,431 apps. Flipkart, BigBasket, Myntra, GPay, PhonePe and Paytm have 4–101 states each.
   - AMEX (CC BY 4.0) has 296k element-function descriptions over 192 apps. DroidTask and GUI-Xplore have per-app graphs for offline tests.
7. **Small models are enough for ranking, and Laya's file can halve without retraining** [primary; derived].
   - UINav (Google) scores every element with a 320k-parameter model in 2 ms on a Pixel 6 Pro. Its 17.6 MB text encoder takes 263 ms.
   - Laya's 256k-token vocabulary is 393 MB of its 578 MB file. Trimming to the ~40–55k tokens Friday needs would give ~250–270 MB. Three trimming studies lost no accuracy.

## 1. Exploration-based app memory, 2017–2026
| System | What it stores | How it explores | Side effects | Run-time models, cost | Results | Tag |
|---|---|---|---|---|---|---|
| DroidBot (2017) | UI transition graph; nodes are screen hashes, edges events | greedy DFS, 1 s per event; restarts; random after 5 | none; its "naive" policy prefers "yes / ok / allow / agree" | no model | a testing tool | [primary, code] |
| AppAgent (2023) | a note per element: what a tap, swipe or text does | GPT-4V while doing set tasks (≤ 40 steps), or a human demo | BACK out of unrelated pages | GPT-4V | 45 tasks: no notes 48.9%, explored 73.3%, demo 84.4%, hand-written 95.6% | [primary] |
| AutoDroid (MobiCom'24) | graph + a "simulated task" per element (function, path) | random, 0.5–1 h per app, then 5–10 min of LLM | none while exploring; an LLM asks before risky steps | GPT-3.5/4 or Vicuna-7B; Instructor-XL retriever (5 GB); Vicuna 22.7–39.7 s per step on a Snapdragon 8 Gen 2 | DroidTask completion: Vicuna 0.6 → 41.1%, GPT-4 31.6 → 71.3%; shortcuts right 75% of the time, 38% fewer steps | [primary] |
| MobileGPT (MobiCom'24) | per-app graph: pages are sub-task lists, edges parameterized sub-tasks | random, ~50 pages per app in 10–15 min; proposes a background "user trace monitor" for pages such as payment (not evaluated) | none; the user undoes | GPT-4-turbo / 3.5 on a server; 213 MB phone client | 82.7% on 185 tasks (AutoDroid 74.7%, AppAgent 67.4%); warm memory −62.5% latency, −68.8% cost | [primary] |
| DroidAgent (ICST'24) | task memory + widget notes | a GPT-4 planner invents tasks, 2 h per app | none | GPT-3.5/4; $13–22 per app | activity coverage 60.7% vs 51.4% (Humanoid) | [primary] |
| AutoDroid-V2 (MobiSys'25) | per-app document: element descriptions, XPaths, effects, transitions | reuses traces from random/DFS testers or a "user trace monitor" | not addressed | **on the phone:** Llama-3.1-8B, 8-bit, one script per task | DroidTask 54.4% vs 43.9%; LLM time 46.3 s vs 669.2 s per task; documents $4.11 per app | [primary] |
| LLM-Explorer (MobiCom'25) | abstract graph, ~128 states per app | **no LLM per step:** a random unexplored action, reached by shortest path; LLM only for new states and text | none | gpt-3.5; $0.11 per app vs $16.31 for DroidAgent | activity coverage 64.6% (humans 66.7%) | [primary] |
| GUI-explorer (ACL'25) | element screenshot → effect text | DFS, branching 10, depth 5 | emulator restored after each branch | MLLM; 66.4 s, $0.064 per step | SPA-Bench 53.7% vs 42.0%; a "12.2% drop" without transition knowledge | [primary] |
| AppAgentX / Mobile-Agent-E (2025) | page–element–shortcut graph (vision) / tips + shortcuts | from task runs | none | GPT-4o + vision | AppAgentX 16.9% (no memory) → 71.4%, 9.1 → 5.7 steps; Agent-E satisfaction 75.1 → 86.9 | [primary] |
| KG-RAG (2025) | graph → index from intents to paths | DroidBot-based, ~1 h per app; gains flat after ~4 h | – | GPT-4; UI-TARS-7B | DroidTask 66.9 → 75.8%; HarmonyOS 61.4 → 70.9% | [primary] |
| GraphPilot (2026) | transition graph + rules | – | – | one GPT-4o call writes the plan; the graph validates it | DroidTask 74.1% vs 62.0%; 1.03 vs 4.54 LLM calls per task; 60.8% without the rules | [primary] |
| UI-KOBE (2026) | node: page description, embedding, screenshot; edge: action + instruction | 300 steps per app (54 nodes, 226 edges), 3.2–6.4 h, $6.2 | recommended only | a choice among one-hop options (done / stay / neighbour / free action), then a grounder taps | AndroidWorld 4B 58.6 → 70.7%, 9B 57.8 → 72.4%; A3 (4B) 26 → 61 | [primary] |
| MobileExplorer (2026) | – (probes during the task) | taps tree elements ranked by embedding similarity to the goal | BACK, then home + replay | 4B VLM; a llama.cpp case study on a Galaxy S24 | AndroidWorld 50.9% vs 46.6%; random probing 42.2%; no rollback 39.7% | [primary] |
| SkillDroid (2026) | successful runs as templates with weighted locators (resource id .40, text .20 …) | – | auto-taps "Allow / OK / Skip" | regex → MiniLM → LLM | 85.3% vs 62.0%; 35 replays with 0 LLM calls, all right | [primary] |
| MapAgent / PG-Agent / Agent-SAMA (2025) | page memory / page graph / per-app state machine | exploration / logged episodes / during tasks | none | MLLMs | SPA-Bench 53.1 → 62.7%; AITW 58.3 → 59.5%; AndroidWorld 63.7% | [primary] |
| AppFlow (FSE'18) | canonical screens and widgets per app category (shopping: 25 + 99) | flows from a test library | test accounts | screens: one hidden layer of 68 neurons; widgets: linear SVMs | unseen apps: screens 87.3%, widgets 87.8% | [primary] |
| ActionEngine (web, 2026) | crawled state machine | crawler | – | one planner call, then BFS with no model | WebArena-Reddit 95% vs 66%; 1.8 vs 10.2 LLM calls | [primary] |

**What transfers to a text-only, CPU-only, sub-1 GB phone** [derived from the rows above]:
- **The edges hold the value.** GraphPilot loses 13.3 points without its transition rules; GUI-explorer reports a 12.2% drop without its transition knowledge.
- **The model's job shrinks to two questions:** which screen is the goal, and what to tap on a screen the map lacks. UI-KOBE's one-hop options are already a multiple choice, the shape Laya answers.
- **Annotate the options.** AutoDroid marks the element on a remembered path with its destination ("go to complete <function>"); AppAgent's notes lifted success from 48.9% to 73.3%. In Friday, that is option text.
- **Exploring needs no model; labelling can wait for the box.** LLM-Explorer explores without one, and AutoDroid-V2 and OS-Genesis label the recorded transitions afterwards.
- **Doesn't transfer:** vision embeddings (AppAgentX, GUI-explorer, UI-KOBE), 5 GB retrievers, a cloud LLM per step. Only AutoDroid-V2 timed a model on a phone (8B, 46 s per task). No paper gives CPU-only or sub-1 GB numbers.

## 2. When are two screens "the same"?
| Screen key | Used by | What happened | Tag |
|---|---|---|---|
| activity + every element's class, id **and text** | DroidBot `state_str`; AutoDroid (a hash of its HTML); WCTester | any text change (a feed item, a clock) makes a new screen; WCTester looped on a countdown label | [primary] |
| activity + the set of (class, resource id, action flags); no text, position or index | DroidBot `structure_str`; Fastbot (text only from an allow-list file); LLM-Explorer | list rows collapse; LLM-Explorer: "the rule-based method is more effective" than LLM merging | [primary] |
| activity name only | DroidAgent | worst key in a 33-app, 1,980-hour study; element-property similarity ≥ 0.95 was best | [primary] |
| "the node's key elements are present" | MobileGPT (paper) | 3 false matches, 0 misses on 269 screens; text-based 38 / 14, vision 57 / 28 | [primary] |
| split a state when one action from it leads to different screens; undo splits into > 8 | APE | 131 / 347 / 725 states (min / median / max) per app in 1 h, vs 12 / 87 / 517 for Stoat's coarser key; "fine models are generally better" for testing | [primary] |
| resource-id sets differ by ≤ 1, or screenshots ≥ 99.8% equal | Rico | 6% false merges, 3% misses on 1,044 pairs | [primary] |
| element diff < 5 and pixel diff < 30%, after BM25 retrieval | Mobile3M | 998,334 "unique" pages of 3.1M, for 49 apps | [primary] |
| a semantic class ("cart", "sign-in") from tiny classifiers | AppFlow | 87.3% on unseen apps; layout features alone 85.6% (shopping) | [primary] |
| image hash or embeddings | MobileViews, GUI-explorer, UI-KOBE; MobileGPT's code (cosine > 0.95 on a text-stripped tree) | needs screenshots or an embedder; UI-KOBE keeps duplicate nodes when text changes | [primary] |

**For Friday** [derived]:
- **Node key:** package + activity (from `TYPE_WINDOW_STATE_CHANGED`) + the sorted set of (class, resource id, clickable / scrollable) over the candidates. No text, bounds, index or list length. Keep the text-bearing key in logs only.
- **Confirm** a node by its key elements being on screen, not by the hash alone (MobileGPT).
- **Let text into the key** only for labels seen to be stable, such as tab names (Fastbot's allow-list).
- **Split** a node only when one tap from it leads to different nodes (APE).
- **Health check:** tens to hundreds of nodes per app is normal. Thousands means text leaked into the key.
- Today's `Routes.screenWords` (a screen's words minus digits) suits "is this the finished screen", not map nodes.

## 3. Public datasets
Research/14 §2 covered AndroidControl, AITW, AndroidLab, FingerTip 20K and Mobile3M. Its open questions are now closed: GUI-Odyssey has no trees; AndroidLab's release has 6,208 steps in XML; AndroidWorld is Apache-2.0 and releases no trajectories. Two licences differ from research/14: Mobile3M's card text says CC BY-NC-SA 4.0 (its tag says apache-2.0), and FingerTip-20K is now tagged CC BY 4.0 [primary].

| Dataset | Size, apps | Trees | Transitions | Element functions | Goals | Licence, download | Indian apps | Use for Friday | Tag |
|---|---|---|---|---|---|---|---|---|---|
| **MobileViews** (2024–25) | 600k+ screens (v2: 1.2M, 30k+ apps); CompleteTraces: 19,431 apps, 628,655 states | AccessibilityService JSON + adb XML | **per-app from → to → action table** | – | – | MIT; ~0.5 TB on HF | states: BigBasket 101, Myntra 57, Snapdeal 55, Flipkart 53, Meesho 41, Zomato 11, Swiggy 9, GPay 6, PhonePe 5, Paytm 4 | offline navigation tests, "leads to" labels, reverse synthesis | [primary] |
| **AMEX** (2024) | 104,876 screens, 192 apps, 1.66M elements | partial: a flat element list (box, text, function) | – | **296k descriptions** | 3,046 | CC BY 4.0; ~94 GB on HF | none in the 76 instruction apps | ranker and option-text labels | [primary] |
| **DroidTask** (2024) | 158 tasks, 13 F-Droid apps | DroidBot JSON | **utg.yaml per app** (12–42 screens) | 2,045 element functions | yes | repo MIT; Google Drive | none | offline navigation with known answers | [primary] |
| GUI-Xplore (2025) | 310 apps, ~29k trees; 32,569 QA | DroidBot JSON | per-app from → to → element | – | QA | CC BY 4.0; 45 GB | none | offline navigation tests | [primary] |
| LlamaTouch (2024) | 495 tasks, 46 apps + AITW apps | XML + accessibility JSON + activity per state | per episode | – | yes + "essential states" | none stated; OneDrive | none | offline evaluation with state checks | [primary] |
| MoTIF (2022) | 4,707 demos, 125 apps | yes | per episode | – | 6.1k + "infeasible" labels | none stated | Swiggy | ranker data; "can't be done here" negatives | [primary] |
| OS-Genesis (2025) | ~1K trajectories (~110 MB JSONL) | flattened tree | per episode | – | reverse-synthesized | MIT | none (AndroidWorld apps) | the recipe and format for reverse synthesis | [primary] |
| Rico (2017) + derived | 66k+ screens released (paper: 72,219), 9.3k apps, 10,811 traces | JSON | from traces | Widget Captioning: 162,859 captions (CC BY 4.0); Rico Semantics: 78k icon meanings, 350k shapes (CC BY-SA 4.0) | – | Rico itself: a research-only notice that also binds employers | 2017 screens: Paytm 16, Zomato 16, Flipkart 12, PhonePe 6 | element and icon labels | [primary] |
| UGIF (2023) | 523 how-to tasks × 8 languages | XML per step | per episode | – | yes, with Hindi, Bengali, Marathi, Gujarati and Kannada UIs | CC BY 4.0; 4 GB | Google and system apps in Indic UIs | Hindi-UI screens | [primary] |
| Non-commercial: Mobile3M, MoGUI, MobileBench-v2, CAGUI | mostly Chinese apps; MoGUI has per-app graphs | some | some | – | some | CC BY-NC(-SA) | Meesho (MoGUI) | method references only | [primary] |
| Screenshots or video only: AITW, AitZ, GUI-Odyssey (8,334 episodes, 212 apps), GUI-World, MONDAY, AutoGUI-702k | – | no | – | AutoGUI: 702k functions | yes | various | GUI-Odyssey: Flipkart 103 episodes | goal text only | [primary] |

- **Indian-app coverage is thin everywhere** [primary]: at most ~100 states per app, often from 2017 or logged out. GPay, PhonePe, Paytm and Amazon India maps must come from the owner's phone.
- **No licence stated:** Screen2Words, CLAY, MoTIF, LlamaTouch, MUD and GUI-World [primary]. Our advice: use them for evaluation, not for shipped weights.

## 4. Small models: a ranker, icons, a smaller Laya

> **Correction (2026-10-18, research/25):** the UIST'18 semantic-icon-classifier below is not ≤ 50 MB. Its weights are a 294 MB Keras file, 38.5M parameters (~37 MB even in int8), so it is not usable on the phone. Rico's terms are not "research-only": they grant no licence to the screenshots and bind the downloader's employer. See research/25.
| Model | Size | Task | Result | Phone latency | Weights | Tag |
|---|---|---|---|---|---|---|
| **UINav** (Google, 2023) | 320k-parameter scorer (1.3 MB) + SmallBERT text encoder (17.6 MB) | scores every element of an accessibility tree | MoTIF unseen apps: task 59.6%, step 81.9% (Seq2Act 32.4% task); 90.6% on 43 in-house tasks; ~70% with 10 demos | Pixel 6 Pro: scorer 1.98 ms, encoder 263 ms; Pixel 3a: 437 ms in total | not released | [primary] |
| ReALM-80M / 250M (Apple) | FLAN-T5 | resolve on-screen entities, with the screen as text | 88.9 / 90.6 vs GPT-4 90.1 | – | not found | [primary] |
| Mind2Web ranker | DeBERTa-v3-base, 184M (738 MB fp32) | rank page elements for a task | recall@50 85.3–88.9%; top-1 only 26.8% | – | open, no licence | [primary] |
| all-MiniLM-L6-v2 (WebLINX) | 22.7M bi-encoder | retrieve elements | recall@10 74.3 vs 76.9 for a DeBERTa cross-encoder | – | Apache-2.0 | [primary] |
| ms-marco-MiniLM-L6-v2 | 22.7M cross-encoder; ships an int8 arm64 ONNX | rerank | MRR@10 39.0; 4 layers 37.7; TinyBERT-L2 (4.4M) 32.6 | none published | Apache-2.0; English | [primary] |
| mmBERT-small | 140M, of which 42M non-embedding; 22 layers × 384 | encoder with Laya's tokenizer | GLUE 84.7 vs 86.3 (base); XTREME 68.6 vs 72.8 | Galaxy S26 CPU: 39 / 97 ms at 256 / 512 tokens (research/19) | MIT | [primary] |
| Bekko a8m / a25m | mmBERT-small cut to 4 / 13 layers | retrieval | MMTEB 56.2 / 57.5; int8 vocabulary rows lossless | – | MIT | [primary] |
| EmbeddingGemma-300m; multilingual-e5-small | 308M (~200M of it embeddings); 118M | embedders | multilingual MTEB 61.2; MIRACL 60.8 | EmbeddingGemma 200 ms on an S26 Ultra CPU | Gemma licence; MIT | [primary] |
| SmolLM2-135M AndroidControl LoRA | 135M (538 MB fp32) | text element list → JSON action | click target 0.488 (its own split) | – | Apache-2.0 | [primary] |
| Icon classifiers: UIST'18 semantic-icon-classifier; IconNet; Screen Recognition | small CNN; 8.5 MB int8; 20 MB | 99 icon classes; 31 classes; element detection | 94.7%; 94.2% mAP; 71.3% mAP | –; 107 ms on a Pixel 3a; ~10 ms on an iPhone 11 | MIT (the only open ≤ 50 MB one); not released; not released | [primary] |
| COALA (FSE'21) | ResNet18 + context | icon labels | exact match 0.38 vs 0.14 for LabelDroid: context (resource id, nearby text) beats pixels | – | research code | [primary] |
| TinyClick; OmniParser v2 captioner; Pix2Struct widget captioning | 271–282M, vision | grounding; captions | ScreenSpot 73.8; CIDEr 133.1 | – | MIT / Apache-2.0 | [primary] |

- **Two stages beat one** [primary]. Rankers are good at recall and weak at top-1: Mind2Web's keeps the right element in its top 50 86–89% of the time, but ranks it first 26.8%. A Flan-T5-base choosing among 5 shortlisted options got 43.6%. That is Friday's shape: a ranker, then Laya.
- **Encode each element once, score cheaply.** UINav's 2 ms scorer sits on per-element text vectors, which can be cached across steps. The text encoder is the cost.
- **Distillation keeps most of a ranker:** on MS MARCO a 6-layer MiniLM matches the 12-layer one, and 4 layers keep 96.6%. No GUI-specific numbers exist [primary].

**Vocabulary trimming** [primary unless tagged]:
- Trimming kept accuracy in three studies. mBERT 178M → 141M, XNLI 65.8 → 65.8 (Load What You Need). XLM-R 278M → 124–137M, unchanged when trimmed after fine-tuning (Ushio et al. 2023). XLM-R 1,060 → 438 MB, XNLI English 85.7 → 85.8 (TextPruner).
- Laya's tokenizer is BPE with byte fallback. Of its 256,000 tokens, ~174k are ASCII-only, 1,516 contain Devanagari and ~80k use other scripts [measured]. Text outside the kept tokens still encodes, into more pieces.
- **For Friday** [derived]: keep the bytes, the specials, all 1,516 Devanagari tokens and the top ~35–50k ASCII tokens by frequency over English, romanized Hindi/Hinglish and Friday's own logged trees (K ≈ 40–55k).
  - The 393 MB table becomes 61–85 MB, and the 578 MB file ~250–270 MB. With 8-bit rows, ~220 MB: ORT has a `GatherBlockQuantized` kernel, but whether onnxruntime-android 1.31 has it is unchecked.
  - Speed doesn't change, since the lookup is under 1% of a pass. RAM and load time drop.
- A student on mmBERT-small with a 32–64k vocabulary would be ~70–90 MB at 8-bit, with ~2.6× fewer linear FLOPs (research/19) [derived].

## 5. Safe automatic exploration
**Techniques** [primary]:
- **Deny-lists.** Fastbot's `max.widget.black` blocks elements per activity; the documented example is the logout button. It also has activity allow/deny lists, and hides the status bar so taps can't reach Settings. Firebase Robo ignores elements such as "logout" and says to use "test accounts that are not associated with real users". Stoat never repeats an event that left the app.
- **Risk classifiers, all too weak to stand alone:**
  - AutoDroid's LLM check: precision 75.0%, recall 80.5%.
  - MobileSafetyBench: GPT-4o spots a risk 92% of the time when asked, but handles it 36% of the time while acting.
  - OS-Sentinel: at most 31.4/100 for finding the risky step; a text-only judge on the tree, 58.3% per trajectory.
  - WebGuard-3B (web, text-only): 76.5–86.0% recall on high-risk actions.
  - VeriSafe reaches 94–98%, but needs ~437 lines of hand-written predicates per app. A rule gate stepped in on 46% of *safe* tasks (CORA).
- **Resets.** AndroidWorld uses no emulator snapshots. It relies on per-task setup and apps "that do not require login", and its app snapshots need root. A stock phone has no reset at all.
- **Airplane mode is not a sandbox.** Network loss caused 11 of VET's 96 dead ends; offline apps show only error screens.

**What goes wrong** [primary]:
- VET (16 industrial apps, 144 one-hour runs): dead ends ("tarpits") took up to 98.6% of the time. Of 96: logout 22, with the session destroyed "both on-device and on-server"; loops 20; tool defects 17; UI frozen after an ad tap 13; network off 11; no way out 8; bad screen keys 5.
- On real accounts: three wrong UPI PINs in 24 h block GPay payments for 24 h. WhatsApp's terms ban "auto-messaging" and allow suspension.
- Android hides views flagged `accessibilityDataSensitive` (login, payment) from services that aren't accessibility tools, so a sparse tree can mean "sensitive", not "empty".
- No published method, and no count of harm, exists for exploring a personal, logged-in, unrooted phone.

**Rules for charging-time exploration** [derived from the above]:
- Money, messaging, mail, shopping-checkout and account apps (GPay, PhonePe, banks, WhatsApp, Gmail, Amazon): **watch only, never explore.**
- Elsewhere, tap only navigation: tabs, drawers, list rows, "see all". Never type, never touch a PIN pad, dialog button, toggle or system UI, never leave the app.
- Check a deny-list before every tap, on resource id, text and description: logout / sign out, pay, send, transfer, buy, order, subscribe, confirm, delete, block, report, call, post, share, allow, grant, install, update.
- After each tap, press BACK and check that the node key returns. If not, go home and stop that app. Stop after a few unexpected transitions or a dead end (VET).
- Log every exploration tap with its node key, so a side effect can be traced and denied next time. A learned risk guard may only add vetoes.

## 6. Learning by watching the owner
| Work | What it captured | Result | Lesson | Tag |
|---|---|---|---|---|
| SUGILITE (CHI'17) | click, long-click and text events; per target the text, label, id, bounds, parent/child and the whole tree | 85.5% of scripts ran (research/21) | its always-on tracker, which "records all the clickable elements", slowed the phone, offered wrong items ("Store Locator") and worried users (labels with balances) | [primary] |
| PINALITE (CSCW'20) | 15 recorded scripts | all 15 held personal data (42–193 strings each) | redact before storing or sharing | [primary] |
| ERICA (UIST'16) / Rico (UIST'17) | screenshots, trees, gestures (ERICA needed a modified OS) | 1,065 sessions from 28 users; only 6.5% of taps marked a flow; coverage flat after 5–8 users. Rico's crawler added 40% coverage over humans | watching covers the owner's paths, not the app | [primary] |
| **ODIM** (MobileHCI'25) | a stock-phone APK: a transparent overlay for touches, plus accessibility click and scroll events | every screen, but 60.8% of gestures (taps 57%); 23% with no source element; ~100 ms per tree; +4% battery per hour (Pixel 4); LLM redaction missed 43.3% | the closest precedent; infer edges from before/after trees | [primary] |
| KITE (MobiSys'18) | click and text events, plus a modified framework | intents ~100%, slots 95% precision / 88% recall, from ~5.8 traces per task | merged pages by class name; dropped WebView-heavy apps | [primary] |
| Humanoid (2019) | 12,278 Rico flows | which element a human taps: top-1 51.2%, top-10 85.2% (random 5.8 / 58.3) | a usage prior helps ranking; for coverage, random was "almost" as good | [primary] |
| GPTVoiceTasker (UIST'24) | accessibility tree; a page graph from the assistant's own runs | 92.5 s vs 162.2 s with Voice Access; replay 82.7% | shortest-path replay, each step re-checked | [primary] |
| MobileA3gent (2025) | "user" episodes auto-labelled by a local VLM | AndroidControl-High 58.1 vs 61.7 with human labels, at ~1.4% of the cost | simulated users only; never ran on phones | [primary] |
| Task segmentation (process mining) | UI logs | cutting at completion words (ok, submit, send, save): edit distance 0.04–0.17, vs 0.32–0.83 for a loop-based baseline, on concatenated test logs | cut by app session, completion words and idle gaps | [primary] |

**Android facts that shape it** [primary, docs and source]:
- `TYPE_VIEW_CLICKED` gives the source node, not the touch point. Classic Views send it from `performClick()`.
- **Compose sends it only for clicks made through accessibility, not for a finger tap.** Compose in-app navigation signals a window change only when a pane title changes. Flutter sends it only for widgets that call `Feedback.forTap` (InkWell yes, a bare GestureDetector no).
- An accessibility service can listen to touchscreen motion events, but listening consumes them. The pass-through "observe" mode is `@hide` and `@TestApi` in AOSP. So Friday can't see where a finger tapped without breaking the tap.
- Each event is an "expensive interprocess call" (`notificationTimeout`). Snapshot trees only on taps and window changes, for allowed packages.

## 7. Testing over recorded maps
- **A ready protocol (TVWorld, 2026)** [primary]. Graphs are crawled on real devices by BFS from the home screen, with sensitive entries (factory reset, language) masked. An episode samples a start and a goal node; success is ending on the goal within 50 steps. Generic 7B GUI agents scored 1.6–5.4%; an 8B model trained on the graphs reached 68.3%.
- **For Friday** [derived]: a simulator that shows a node's stored tree and moves along the edge of the element picked. It scores the planner, the ranker and Laya with no phone, on DroidTask's 13 graphs (known answers), MobileViews' Indian-app traces and the owner's own maps, with goals written by the teacher.
- **Maps go stale** [primary]. In 2014, 14% of the top 10,713 Play apps updated every two weeks or faster, and 45% of the frequently-updated apps said nothing about what changed (McIlroy 2015). Re-verify a node when its key elements go missing. AppFlow needed per-app fixes for only 15.9% of canonical screens.

## What this means for Friday (ranked)
1. **Start the map from watching and from Friday's own runs; explore last.** Log (node key, element key, next node key, time) on each owner tap and each Friday step. Infer the edge from the trees before and after when no click event arrives. Zero side effects, and it covers money and messaging apps that must never be explored (ODIM; MobileGPT proposes the same for payment pages but never measured it; SUGILITE and PINALITE for the privacy cost).
2. **Use a text-free structural node key, confirmed by key elements; no model** (LLM-Explorer, Fastbot; MobileGPT's 3 false matches in 269).
3. **Plan by shortest path; spend Laya once, on "which screen is the goal".** The teacher writes a one-line description and a few synthetic goals per node, offline. BM25 shortlists nodes and one Laya pass picks, as for app choice (D-043). Evidence: GraphPilot's 1.03 calls per task, UI-KOBE's one-hop choice, ActionEngine's BFS.
4. **Where Laya still decides, add each element's known destination to its option text,** e.g. "See all (→ transaction history)". AutoDroid's destination hint and AppAgent's notes (48.9 → 73.3%) show this works. It needs a hint-aware fine-tune (research/22 §5.3).
5. **Trim Laya's vocabulary before any distillation:** 578 → ~250–270 MB, with no retraining and no accuracy loss in three studies. Gate it with research/18's 52-pair parity set [derived].
6. **Build the offline map simulator before the ranker** (TVWorld's protocol; DroidTask, MobileViews and the owner's maps), so every later piece is scored without the phone.
7. **Then a small ranker for recall, with Laya picking:** a MiniLM-class cross-encoder (22.7M) or a UINav-style scorer over cached element vectors. Train it on AndroidControl, AMEX function labels, MobileViews transitions and the owner's map. Ship it only if it beats today's word-overlap `shortlist()` (D-049) offline.
8. **Exploration while charging comes last and stays narrow:** non-money apps, navigation taps only, a deny-list, BACK-and-check, stop at dead ends (VET, Fastbot, Robo). For icons, try resource-id words and nearby text first (COALA). Add a ≤ 50 MB icon classifier only if unlabelled icons still block tasks.

## Dead ends
- **Model-free navigation of new tasks:** no paper does it. The only model-free results are exact replays (SkillDroid) [primary].
- **CPU-only or sub-1 GB results for a memory-based phone agent:** none. Only AutoDroid-V2 timed a phone (8B) [primary].
- **Released explored graphs:** none from LLM-Explorer, AppAgentX, UI-KOBE, PG-Agent or MobileGPT. No code yet from GraphPilot, MapAgent, KG-RAG, SkillDroid or MobileExplorer. AutoDroid's shortcut, risk and privacy features are missing from its released code [primary].
- **Weights:** none public for ReALM, UIBert, ActionBert, UINav, Screen Recognition or IconNet. No phone latency for MiniLM, BGE-small or e5-small on any Tensor chip. No trimming results for Hindi/Hinglish or for a fine-tuned cross-encoder [primary].
- **Seeing finger taps:** Android's pass-through motion-event mode for accessibility services is hidden; listening consumes the touches [primary, AOSP].
- **GPTVoiceTasker as passive learning:** its graph grows from the assistant's runs; the paper never says manual use adds to it (research/21 implied it did). MobileA3gent never ran on real phones [primary].
- **Unreachable or not applicable:** DroidBot's paper (IEEE) and Baek & Bae's (ACM) could not be read. Explorer (2025) is web-only. GUI Exploration Lab's graphs are authored, not mined. MoGUI's report is a placeholder. A3 and SPA-Bench released no trajectories.
- **India:** no India-focused UI dataset was found.

## Not checked
- Nothing ran on the phone. Latencies are from the papers' own hardware, and sizes marked [derived] are arithmetic.
- Datasets were checked from cards, file lists and app lists, not downloaded. The Indian-app scans matched package names only.
- Whether onnxruntime-android 1.31 has `GatherBlockQuantized`, and whether a WebView finger tap sends a click event.
- How often the owner's own apps change their screens.

## Sources
**Exploration memory:**
- DroidBot https://github.com/honeynet/droidbot (`device_state.py`, `input_policy.py`, `utg.py`) · AppAgent https://arxiv.org/html/2312.13771 · https://github.com/TencentQQGYLab/AppAgent · AppAgent v2 https://arxiv.org/html/2408.11824v3
- AutoDroid https://arxiv.org/abs/2308.15272 · https://github.com/MobileLLM/AutoDroid (`memory/utgs/`) · MobileGPT https://arxiv.org/abs/2312.03003 · https://github.com/fclab-skku/MobileGPT · DroidAgent https://arxiv.org/html/2311.08649
- AutoDroid-V2 https://arxiv.org/html/2412.18116 · LLM-Explorer https://arxiv.org/abs/2505.10593 · https://github.com/MobileLLM/LLM-Explorer · AppAgentX https://arxiv.org/html/2503.02268 · Mobile-Agent-E https://arxiv.org/html/2501.11733 · GUI-explorer https://arxiv.org/html/2505.16827v1
- MobileVLM / Mobile3M https://arxiv.org/abs/2409.14818 · UI-KOBE https://arxiv.org/html/2605.29534 · GraphPilot https://arxiv.org/html/2601.17418 · KG-RAG https://arxiv.org/html/2509.00366v1 · MobileExplorer https://arxiv.org/html/2605.26546v1 · SkillDroid https://arxiv.org/html/2604.14872
- PG-Agent https://arxiv.org/html/2509.03536v1 · MapAgent https://arxiv.org/html/2507.21953v1 · Agent-SAMA https://arxiv.org/html/2505.23596v3 · ActionEngine https://arxiv.org/html/2602.20502v1 · AppFlow https://www.cs.columbia.edu/~junfeng/papers/appflow.pdf · OS-Genesis https://arxiv.org/html/2412.19723

**Screen identity:**
- APE https://cs.uwaterloo.ca/~cnsun/public/publication/icse19/icse19.pdf · Stoat https://tingsu.github.io/files/fse17-stoat.pdf · Jiang et al. 2019 https://www.cs.cityu.edu.hk/~wkchan/papers/trel2019-jaing+zhang+chan+zhang.pdf · Fastbot https://github.com/bytedance/Fastbot_Android · VET https://arxiv.org/pdf/2102.06377 · Rico paper https://ranjithakumar.net/resources/rico.pdf

**Datasets:**
- MobileViews https://arxiv.org/abs/2409.14337 · https://huggingface.co/datasets/mllmTeam/MobileViews · AMEX https://arxiv.org/html/2407.17490 · https://huggingface.co/datasets/Yuxiang007/AMEX · GUI-Xplore https://huggingface.co/datasets/9211sun/GUI-Xplore · LlamaTouch https://github.com/LlamaTouch/LlamaTouch
- AndroidLab https://github.com/THUDM/Android-Lab/blob/main/docs/instruction_tuning.md · MoTIF https://arxiv.org/html/2202.02312 · OS-Genesis data https://huggingface.co/datasets/OS-Copilot/OS-Genesis-mobile-data · Rico https://www.interactionmining.org/archive/rico (`copyright.txt`)
- Widget Captioning https://github.com/google-research-datasets/widget-caption · Rico Semantics https://github.com/google-research-datasets/rico_semantics · UGIF https://github.com/google-research/google-research/tree/master/ugif
- GUI-Odyssey https://huggingface.co/datasets/hflqf88888/GUIOdyssey · AndroidWorld https://github.com/google-research/android_world · Mobile3M https://huggingface.co/datasets/xwk123/Mobile3M · FingerTip-20K https://huggingface.co/datasets/AkadoAkiri/FingerTip-20K · MoGUI https://huggingface.co/datasets/OpenDFM/MoGUI · Explorer https://arxiv.org/abs/2502.11357

**Small models:**
- UINav https://arxiv.org/html/2312.10170 · ReALM https://arxiv.org/html/2403.20329 · Mind2Web https://arxiv.org/html/2306.06070 · WebLINX https://arxiv.org/html/2402.05930 · https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2
- mmBERT https://arxiv.org/html/2509.06888 · https://huggingface.co/jhu-clsp/mmBERT-small · Bekko https://arxiv.org/html/2607.25180 · EmbeddingGemma https://developers.googleblog.com/en/introducing-embeddinggemma/ · https://developers.google.com/edge/mediapipe/solutions/text/text_embedder · multilingual-e5 https://arxiv.org/html/2402.05672 · https://huggingface.co/thealper2/smollm2-135m-android-control
- Icons: https://github.com/datadrivendesign/semantic-icon-classifier · https://research.google/blog/improving-mobile-app-accessibility-with-icon-detection/ · https://arxiv.org/abs/2101.04893 · https://seal.ics.uci.edu/publications/2021_FSE_COALA.pdf · https://arxiv.org/html/2410.11871 · https://huggingface.co/microsoft/OmniParser-v2.0 · https://arxiv.org/html/2210.03347
- Trimming: https://arxiv.org/abs/2010.05609 · https://arxiv.org/html/2305.15020 · https://arxiv.org/abs/2203.15996 · https://github.com/microsoft/onnxruntime/blob/main/docs/OperatorKernels.md

**Safety:**
- MobileSafetyBench https://arxiv.org/pdf/2410.17520 · OS-Sentinel https://arxiv.org/pdf/2510.24411 · WebGuard https://arxiv.org/pdf/2507.14293 · VeriSafe https://arxiv.org/pdf/2503.18492 · CORA https://arxiv.org/pdf/2604.09155
- Firebase Robo https://firebase.google.com/docs/test-lab/android/command-line · https://firebase.google.com/docs/test-lab/android/robo-scripts-reference · GPay PIN https://support.google.com/pay/india/answer/7430541 · WhatsApp https://www.whatsapp.com/legal/terms-of-service · https://developer.android.com/blog/posts/enhancing-android-security-stop-malware-from-snooping-on-your-app-data

**Watching the owner:**
- SUGILITE https://toby.li/files/TobyLi-CHI2017-Sugilite.pdf · PINALITE https://toby.li/files/li-privacy-cscw2020.pdf · ERICA https://ranjithakumar.net/resources/deka-uist2016-erica.pdf · ODIM https://ranjithakumar.net/resources/odim.pdf · KITE https://toby.li/files/TobyLi-MobiSys18-KITE.pdf · Humanoid https://arxiv.org/html/1901.02633v2
- GPTVoiceTasker https://arxiv.org/html/2401.14268v3 · MobileA3gent https://arxiv.org/html/2502.02982v2 · segmentation http://eprints.cs.univie.ac.at/8135/ · https://arxiv.org/pdf/2008.05782
- Android: https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent · Compose https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/AndroidComposeViewAccessibilityDelegateCompat.android.kt · Flutter https://github.com/flutter/flutter/blob/master/engine/src/flutter/shell/platform/android/io/flutter/view/AccessibilityBridge.java · AOSP https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/accessibilityservice/AccessibilityServiceInfo.java

**Testing and staleness:**
- TVWorld https://arxiv.org/html/2601.13142 · GUI Exploration Lab https://papers.neurips.cc/paper_files/paper/2025/hash/103474f02fe16ba3e64f8ea74e18a285-Abstract-Conference.html · McIlroy et al. 2015 https://sailresearch.github.io/sail-website/data/pdfs/EMSE2015_FreshApps_AnEmpiricalStudyofFrequently-updated.pdf
