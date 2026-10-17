# Fine-tuning data: teaching Laya which app does what, and how to operate it

_Last updated: 2026-10-17 · Trust tags: [primary] read at source · [secondary] search/blog/forum · [measured] run here · [UNCERTAIN] unconfirmed or conflicting_

**The question (D-031).** Laya-Android should learn the basic use of every app — which app serves a request, and how to operate it — through fine-tuning, not hand-written per-app rules. This note covers the **data**: what can teach that, how it becomes `laya-train` rows, how big it is, and under which license. The training *method* (questions, freezing, learning rates, calibration, gates) is research/15. The two share one row format.

**How it was done:**
- **Read:**
  - a clone of Byun11/laya-android at `ab216d4`;
  - laya 0.3.28's `train.py`, `calibrate.py` and `shortlist.py`;
  - Friday's `Teach.kt`, `AppProfiler.kt` and `LayaEngine.kt`.
- **Downloaded** into `models/data/`, git-ignored, ~35 MB kept:
  - AndroidControl's `splits.json` and `test_subsplits.json`;
  - two third-party JSON mirrors of its goals and actions;
  - the public Wikidata list of items with a Play Store ID.
- **Decoded, then deleted:** the first 245 MB of one AndroidControl shard (65 episodes). The Mac's disk is 97% full. Big downloads go to the GPU box from now on.
- **Run on the Mac:**
  - rows built through laya's own `items_from_rows`;
  - one short MPS training timing, before the "no training on the Mac" rule;
  - two zero-shot app-choice probes.
- **Research agents:**
  - Literature: synthetic data, forgetting, routing.
  - Metadata and utterance corpora.
  - Facts from these two keep the tags the agents gave them.
  - A third agent, surveying datasets, had not reported when this was written. §2.2 is built from the other two plus two direct checks.
- **Personal data:** the owner's app list was only *counted*. Nothing personal is in this file.

## Answer in one screen
1. **No public dataset teaches "which app" cleanly. AndroidControl comes closest, but its labels leak the answer.**
   - It has 5,024 train goals that open an app, over 692 apps.
   - **93.4% of those goals name the app** [measured].
   - Zero-shot Laya-Android, 16 options: **98.5% right with the goal as written, 32.0% with the name removed** (chance 6.3%) [measured].
   - Use it after stripping the name. That is how Alexa built its routing training set [primary].
   - Label with soft targets: the rater's app is one right answer among several.
2. **The owner's money, ride, rail and food-delivery apps are missing from public data.**
   - AndroidControl has Indian shopping, grocery and travel apps: Flipkart 240 goals, JioMart 42, Blinkit 40, bigbasket 40, MakeMyTrip 37.
   - It has **zero goals** for Paytm, PhonePe, GPay, CRED, IRCTC, Swiggy, Ola or Rapido.
   - **49 of the owner's 207 apps** appear in it by name: money apps 1 of 30, ride-hailing 0 of 7 [measured].
   - Those categories have to come from synthetic requests and Teach mode.
3. **Operating apps: AndroidControl remains the base. Friday's own Teach recordings are the best new data.**
   - Of the sets checked, only AndroidControl, FingerTip 20K and Mobile3M ship accessibility trees. The last two are Chinese-app sets with no clear license [primary]. Whether AndroidLab's released data includes XML is unconfirmed.
   - Teach rows convert to exactly Laya-Android's format with the repo's own parser (§5).
4. **Option text must be the phone's profile text.**
   - On the phone, `AppProfiler` writes the profiles (D-031), so training options must be rendered the same way.
   - Public metadata is **input for a teacher LLM** that writes requests, never option text:
     - AppGoblin: 3.4M apps, CC BY 4.0 per its publisher, all 18 Indian apps checked;
     - Wikidata (CC0): thin coverage, 4 of 18 Indian apps have a Play ID;
     - Wikipedia (CC BY-SA).
   - AndroZoo is closed to us: it needs an institutional email and is non-commercial [primary].
5. **Request phrasing without per-app rules.**
   - Corpora:
     - **MASSIVE 1.1:** CC BY 4.0; 5 Indian locales; 60 intents.
     - **SGD:** CC BY-SA 4.0; every service has a description.
     - **Hinglish-TOP:** Apache-2.0.
     - De-named AndroidControl goals.
     - Teacher-written requests generated from app descriptions.
   - Map each intent to an app *kind* once, through a small table that never names an app. No dataset covers UPI, grocery or shopping phrasing, so those come from the teacher.
6. **Reusing laya-android's pipeline costs almost nothing.**
   - Its builder and `serialization.row()` are reused unchanged.
   - The new questions (`app`, `done`) are plain extra rows. laya's own item builder accepted §6's kinds A–D with 0 skipped [measured].
   - Its checkpoint loads and trains under `laya.train` [measured].
   - Everything is Apache-2.0 (the encoder is MIT) [primary].
7. **Friday's own data** (§5):
   - **Teach recordings:**
     - each step becomes an operation row, plus a target row when it is a tap;
     - each demo also gives one DONE row and one "which app" row;
     - **Routes** give "which app" rows, and step rows only if a screen is saved with each step.
   - **Hold out by task**, never by step.
   - **Weighting:** oversample personal rows 3–5× and always replay at least as much AndroidControl. Small models forget most [primary].
8. **Compute is the GPU box.**
   - The Mac trained at 1.29 items/s with 11.5 GB of GPU memory [measured]. With two training jobs at once, the 16 GB Mac went deep into swap, so training on the Mac is off.
   - A data run-pack is ready: AndroidControl download and processing, row building, zero-shot baselines, and two pilots (§7).

## 1. How Laya-Android turned AndroidControl into rows, and what we can reuse

### 1.1 Their pipeline [primary]
| Stage | File | What it does |
|---|---|---|
| Read | `src/data/android_control.py` | A hand-rolled protobuf decoder for the 20 GZIP TFRecord shards; no TensorFlow, and screenshots are skipped. It keeps each window's `window_type` and, per node, `text, content_desc, hint, class, package, resource_id`, 12 flags, bounds, children and depth. |
| Steps | `scripts/build_androidcontrol_dataset.py` | An episode with T actions has T+1 trees, so it gives T+1 step items. The op comes from `canonical_op`, with the scroll direction folded in. The target comes from `ground()`: the smallest candidate box holding the tap point, else the step is ungroundable. History is the last 3 `history_str` entries. **A synthetic `DONE` is added on the final observation.** |
| Candidates | `src/candidates.py`, `src/accessibility.py` | The rules we already ported (`scripts/laya_android.py`, D-013). |
| Rows | `src/serialization.py` `row()` | A step item becomes `{state, questions, expected}`. `target` is asked only for a groundable CLICK or LONG_PRESS. |
| Train | `scripts/train_laya_android.py` | `laya.train.items_from_rows`, then `train_model`, with two patches: bf16 autocast (laya 0.3.28 hard-codes fp16), and a validation pass after each epoch that keeps the best epoch. Temperatures are fitted on validation only. |

- **Counts:**
  - 13,594 train episodes with 74,714 actions.
  - Adding one DONE per episode gives 88,308 steps.
  - Those make 132,448 items: 88,308 operation and 44,140 target questions.
  - Click grounding coverage is 94.8%.
- **Config** (= the `training` block of the checkpoint's `rl_agent_config.json`):
  - 3 epochs, effective batch 32 (8 × 4);
  - encoder lr 2.5e-5, head lr 1e-4, cosine schedule;
  - soft-CE, shuffled choice options, gradient checkpointing;
  - `max_len 3072` / `head_max_len 1536`;
  - best epoch 2, 2.73 h on one RTX 4090.
- **No oversampling, class weights or RL.** That is why HOME (29 train examples) and LONG_PRESS (157) stay weak.

### 1.2 Reuse
- **(a) An app-selection question:** add rows of a new kind to the same JSONL. `items_from_rows` takes any mix of questions per row [primary].
  - Shape the app rows like the phone's generic choose call, which today serves skills and slots and will serve the app question (D-031). `LayaEngine.choose` serializes `{"goal": request}` and renders options as `"i: text"`, in groups of ≤ 16 (`Assistant.GROUP = 16`, laya's `DEFAULT_TOURNAMENT_GROUP`) [primary].
  - Don't attach the app question to step rows.
  - Validated [measured]:
    - Hand-made rows of kinds A–D in §6 gave 9 items, 0 skipped; `laya-train --dry-run` agrees.
    - 65 real AndroidControl episodes through laya-android's own builder, plus the new rows, gave 1,145 items, 0 skipped: 454 operation, 207 target, 454 `done`, 30 `app`.
- **(b) Continuing from their checkpoint:** the published layout is laya's own, so `laya.train.load_checkpoint(<dir>)` loads it, and we trained on it [measured].
  - Point `laya-train --base` at it, and use their bf16 patch on CUDA (run-pack, `train_bf16.py`).
  - `finetune()` holds out min(400, 10%) of the items for calibration and drops the inherited `temperature_by_options` [primary].
  - One `choice` temperature covers the operation, target and app questions alike. Check each one separately (research/15 §3.4).
- **Licenses** [primary]:

  | Component | License |
  |---|---|
  | Laya-Android code and weights | Apache-2.0; its NOTICE credits Laya (Apache-2.0), mmBERT-base (MIT) and AndroidControl (Apache-2.0) |
  | laya | Apache-2.0 |
  | InfiGUI-R1 evaluator | Apache-2.0 |

  A private fine-tune only has to keep the notices.

## 2. Public datasets: (goal → app) and (goal, screen, action)

### 2.1 AndroidControl up close [measured unless tagged]
- **Size:**
  - 15,283 episodes: train 13,603 / validation 137 / test 1,543.
  - Test subsplits (they overlap): IDD 721, category-unseen 700, app-unseen 631, task-unseen 803.
  - 14,548 unique tasks across 833 apps in 40 categories, which is about 18 episodes per app [primary, agent].
  - Each shard is ~2.5 GB, almost all screenshots; ~50 GB in total.
- **The app is recoverable per step.**
  - The tree's application window carries `package` on every node, and its window title is the app's label. In our 65 episodes, 452 of 454 observations had one.
  - 56 of 65 episodes stay in a single app.
  - 32 start on the previous app or the launcher, so take the app from the observation *after* OPEN_APP.
  - 57 packages appeared, including `in.amazon.mShop.android.shopping`, the Indian Amazon build.
- **Goal → app pairs leak the answer:**
  - 5,024 of 13,594 train episodes (37.0%) contain an `open_app` action; 4,978 of them start with it.
  - They cover 692 app names: mean 7.3 episodes per app, median 3.
  - **93.4% of these goals name the app**, and 72.7% of all 15,283 goals contain the word "app".
- **So as written they teach copying.** Zero-shot Laya-Android on 200 train goals, 16 names-only options:

  | Goal | Right |
  |---|---|
  | as written | **98.5%** |
  | app name removed | **32.0%** |
  | chance | 6.3% |

  On our own seen/unseen split it scored 31.4% (n = 274) and 46.7% (n = 60).
- **A regex strip plus a leak check keeps 3,643 of the goals over 599 apps.** An LLM rewrite would keep more.
- **Stripped pairs are inherently multi-answer:**
  - "find a flight from Bangalore to Mumbai" → Agoda;
  - "search for new Nike shoes" → Temu;
  - "search the train schedule to Amsterdam" → Maps.

  Train them with `gold` spread over every app that could do it.
- **Indian apps.** Goals mentioning each app:

  | App | Goals |
  |---|---|
  | Amazon | 447 |
  | Flipkart | 240 |
  | Myntra | 67 |
  | JioMart | 42 |
  | Blinkit | 40 |
  | bigbasket | 40 |
  | MakeMyTrip | 37 |
  | Nykaa | 34 |
  | AJIO | 25 |
  | Meesho | 14 |
  | ixigo | 14 |
  | JioSaavn | 10 |
  | Goibibo | 9 |
  | Zomato | 8 |
  | Zepto | 5 |

  RailYatri and ixigo trains appear as `open_app` targets. **None** mention Paytm, PhonePe, GPay, BHIM, CRED, IRCTC, Swiggy, Ola, Rapido, BookMyShow, Hotstar, Truecaller, MyJio or Airtel (word-boundary regex over all goals).
- **Against the owner's phone:**
  - 49 of 207 installed apps (24%) match an AndroidControl app name exactly. Counting only `open_app` episodes, so this is a lower bound.
  - Money apps (bank, card, UPI, invest, trackers): 1 of 30.
  - Ride-hailing: 0 of 7.
  - Messaging and calls: 2 of 8.
  - Shopping, grocery and food: 14 of 24.
- **Label quality.** No terminal action exists, so DONE is synthetic [primary]. A 2025 audit (AndroidControl-Curated) flags a large share of test labels as ambiguous or wrong; it gives both 30% and 70% [UNCERTAIN].

### 2.2 The rest, for Laya's purposes (tree-based, many apps, Indian coverage)
| Dataset | Size | Apps | App per episode? | Accessibility tree? | License | Indian apps | Use |
|---|---|---|---|---|---|---|---|
| **AndroidControl** (2024) | 15,283 episodes | 833 | yes (§2.1) | **yes**, the full forest | Apache-2.0 per the README header and laya-android's NOTICE; there is no separate data-license file [primary; UNCERTAIN as a data license] | shopping, grocery and travel only | **the base**: operation/target replay, de-named app pairs, DONE |
| Android in the Wild (2023) | 715,142 episodes, 30,378 unique prompts | mostly Google apps and web shopping | [UNCERTAIN] | **no**: screenshots plus OCR/icon boxes | not in the README; HF mirrors tag CC BY 4.0 [secondary] | not checked | little. Its labels are noisy too: only ~72% of 7,180 sampled episodes succeed (AitZ) [primary] |
| GUI-Odyssey (2024) | 7,735 cross-app episodes | 201 | yes, each lists its apps [primary] | screenshots [UNCERTAIN] | CC BY 4.0, "mainly research" [primary] | not checked | multi-app goal → apps pairs |
| AndroidLab / Android Instruct (2024) | 10.5k traces, 94.3k steps [primary] | benchmark: 9 apps (Bluecoins, Calendar, Cantook, Clock, Contacts, Maps.me, PiMusic, Settings, Zoom) | yes | XML mode exists; whether the released data carries XML [UNCERTAIN] | MIT (repo); no data license [primary] | none | extra step data for system-like apps |
| FingerTip 20K (2025) | 21,437 episodes from 91 real users' own phones | 506 | yes, the activity name | **yes**: XML plus screenshots, actions and intent text | **none stated** (GitHub; data on Kaggle) [primary] | likely none ("most Chinese apps can only run on physical phones") [UNCERTAIN] | the closest public analogue of Teach data; not reusable until licensed |
| Mobile3M (2024) | 3.1M pages, 20.1M actions from graph exploration | 49 Chinese apps | yes | yes, XML | not stated [primary] | none | method reference for exploration graphs |
| AndroidWorld (2024) | environment with 116 parameterized tasks | 20 open-source and system apps | yes | live trees | Apache-2.0 [UNCERTAIN] | none | closed-loop evaluation, an exploration sandbox |
| ISTAS | 6,877 in-situ queries → target app, 255 users | — | yes | n/a | none stated [primary] | not checked | the only real query → app data found; research only |
| Leyzer | ~20 "domains" that *are* apps (Spotify, YouTube, Slack…) | ~20 | yes | n/a | CC BY-NC-ND 4.0 on GitHub vs CC BY 4.0 on the HF card [UNCERTAIN] | none | Western request → app pairs |

- **2025–2026 successors seen** (methods more than data for us) [primary unless marked]:
  - OS-Genesis: 1K trajectories, reverse synthesis.
  - UI-Genie: 16k trajectories, screenshots.
  - TongUI: 143K tutorial trajectories over 200+ apps [primary, abstract only].
  - MobileA3gent: auto-labels a user's own phone use.
  - BlueLM-GUI: trained on real phones [abstract only].
- **Not checked** (the survey agent's slice): AMEX, MONDAY, MobileViews, CAGUI, A3, LlamaTouch. **No India-focused mobile-agent dataset turned up** in the searches made.
- **Verdict:**
  - For *operating*: AndroidControl plus the owner's own Teach data, with AndroidLab as an optional extra.
  - For *choosing*: de-named AndroidControl, then synthetic and real-utterance data (§3–4).

## 3. App metadata and request phrasing

### 3.1 What profiles to train on
**The rule:** the option text in training must be what the phone renders. That is `AppProfiler.profileOf`, e.g. `Fit — fitness; does: start exercise; shortcuts: Start workout, Track activity` (D-031; format in research/15 §2).

**What public metadata is for:**
- telling the *teacher* LLM what an app does, so it can write requests and judge which options fit;
- giving a port of `AppProfiler` the fields it can reproduce without the APK: label, package words, category.

Capabilities and shortcuts need the real manifest, which only the owner's phone gives us.

| Source | Apps | Text | Category | Package | License / terms | Indian coverage | Tag |
|---|---|---|---|---|---|---|---|
| **AppGoblin** app descriptions (HF; snapshot 2026-03-19) | 3,403,247 rows, Android + iOS | short + full description | yes | `store_id` | publisher: CC BY 4.0, commercial use allowed, credit required; the HF tag says cc-by-2.0; the listing text remains the developers' copyright | **all 18 checked apps present** (en-US listing: GPay's row is the US "unavailable" notice) | [primary] |
| Wikidata (P3418, Play Store ID) | 6,803 items / 6,968 IDs; 3,361 are video games | one-line descriptions | P31 only; P136 is game genres | P3418 | **CC0** | 17 of 18 have an item; **only 4 carry a Play ID**; the owner's phone: 66 of 207 by package [measured] | [primary] |
| Wikipedia lead sections | per article | prose, 126–712 chars | — | via sitelink | CC BY-SA 4.0 (share-alike on derived text) | 16 of 18 | [primary] |
| Jl-wei/gp-app-description | 589,143 apps | full text | `genreId` | yes | **academic use only** | 15 of 18 | [primary] |
| AndroZoo (APKs + Play metadata) | Play-wide | `descriptionHtml` | ? | yes | no redistribution, **non-commercial**, a faculty sponsor plus an institutional email (Gmail refused), keys expire | — | [primary] — **not available to us** |
| Kaggle lava18 / gauthamp10 | 10.8k (2019) / 2.3M (2021) | no description | yes | gauthamp10 only | CC BY-SA 4.0 / ODbL vs MIT (conflict) | low / unmeasured | [primary] |
| Rico `app_details.csv` | ~9.3k (2017) | none | 27 categories | yes | research agreement, not an open license | low | [primary] |
| F-Droid metadata | 4,702 | summary + description | yes | yes | AGPL-3.0 (repo) | none | [primary] |
| Play category taxonomy | 32 + 17 categories | scope notes | — | — | Google site terms [UNCERTAIN] | — | [primary] |

- **Play categories are noisy for routing.** AppGoblin files Ola and Rapido under `maps_and_navigation`, and Zepto and Blinkit under `food_and_drink` [primary]. Descriptions carry the meaning, so profiles should not lean on the category.
- **Play's robots.txt allows detail pages** (`/store/apps/details`) and disallows search, reviews and the internal API [primary]. Fetching listings for the owner's short app list is compliant. Use the text as teacher input only, never verbatim.
- **Example descriptions** (AppGoblin, short) [primary]:
  - PhonePe: "Secure UPI, Money Transfer, Recharge, Bill Payment…"
  - Namma Yatri: "Auto, Cab & Metro Booking App…"
  - Swiggy: "Online Food Delivery | Exciting Dineout Deals | Grocery Delivery"

### 3.2 Request phrasing (real utterances)
| Corpus | Size | Languages | License | Maps to app kinds via | Tag |
|---|---|---|---|---|---|
| **MASSIVE 1.1** | 16,521 utterances per locale (11,514 train), 52 locales | incl. **hi-IN, kn-IN, ml-IN, ta-IN, te-IN** (native script) | **CC BY 4.0** | 60 intents, e.g. `takeaway_order` → food delivery, `transport_taxi` → ride, `transport_ticket` → train/flight/bus, `play_music` → music, `recommendation_events` → tickets, `qa_stock` → trading | [primary] |
| **SGD** (+ SGD-X) | 20k+ dialogues, 20 domains, 45 services | en | **CC BY-SA 4.0** | each service has a description, e.g. RideSharing "App to book a cab…", Payment_1, Trains_1, Banks_1, Movies, Restaurants | [primary] |
| **Hinglish-TOP** | ~10K human + ~170K synthetic | romanized Hinglish ("do ghante ke liye alarm set kardo") | **Apache-2.0** | alarm, event, messaging, music, navigation, reminder, timer, weather | [primary] |
| MTOP | ~100k, 11 domains | incl. **hi** | CC BY-SA 4.0 (mirror card) [secondary] | calling, messaging, music, news, recipes, reminders | mixed |
| Taskmaster 1/2/3 | 13k / 17k / 24k dialogues | en | CC BY 4.0 | pizza, ride, movie tickets, food ordering, flights | [primary] |
| CLINC150 | 23,700 queries, 150 intents + 1,200 out-of-scope | en | CC BY 3.0 | banking, credit cards, travel; **out-of-scope = "no app" negatives** | [primary] |
| Banking77 · SNIPS · SLURP/HWU64 | 13k · ~14.5k · 16.5k | en | CC BY 4.0 · CC0 · CC BY 4.0 | bank/UPI · music, restaurants, movies · same as MASSIVE (dedupe: MASSIVE en is SLURP) | [primary] |

- **Gaps:** nothing public covers UPI or payments, grocery or shopping phrasing, and no AI4Bharat command set exists [primary]. Those come from the teacher LLM.
- **One small table, never per app.** The ~60 MASSIVE intents (plus SGD's services and CLINC's domains) map once to *app kinds* (food delivery, ride, train booking, music…). Options are then drawn from apps whose profile text shows that kind; the teacher judges which ones qualify.

## 4. Synthetic data without per-app rules

### 4.1 Recipes
1. **Strip the app's name from real goals (cheapest and most realistic).**
   - Use AndroidControl episodes that open an app. Add the in-app episodes too: their package is in the tree, and the package → label table can be read off the `open_app` episodes.
   - Remove the naming clause ("Open the X app and", "in the X app"). Drop the pair if any 4+ letter token of the name, or a stray "… app", survives.
   - A regex keeps 3,643 goals [measured]; an LLM rewrite keeps more.
   - Alexa built 5.34M routing examples the same way, stripping the skill name from "Ask {Uber} to {get me a ride}" [primary, 1804.08065].
   - **Keep 10–20% named rows** so "open spotify" keeps working.
2. **A teacher LLM writes requests per app**, from its description (§3.1). Do this for the owner's 207 apps, plus popular Indian apps of the missing kinds.
   - Volume: ~50–100 requests per app. Octopus v2 reached 98% at ~100 per API (research/15) [primary].
   - Prompt rule from ToolLLM: "try to avoid explicitly specifying which API to employ" [primary, 2307.16789].
   - Add terse, Hinglish and indirect phrasing ("bahut bhook lagi hai").
   - A judge pass marks every option that could also do it; that sets the soft `gold`.
   - **Paraphrase the profiles too.** SGD-X lost 12–18% joint accuracy on reworded service descriptions, and back-translated descriptions recovered 8–11% [primary, 2110.06800].
   - **Run the teacher on the box**, so the owner's app list stays on machines the owner controls (research/15).
3. **Real utterances, mapped to kinds once:** MASSIVE, SGD, Hinglish-TOP and Taskmaster (§3.2). This adds human phrasing and Indian languages that synthetic text lacks.
4. **Explore, then label afterwards (for operating, not choosing).**
   - Walk safe screens of the owner's apps:
     - no typing;
     - no risky words (D-025);
     - never inside money flows (D-026).
   - Record the screen graph, then let a teacher write a goal for a screen that was reached ("show my past orders"). Every step of the path becomes a §6 B row.
   - Labelling after exploring beats writing tasks first: OS-Genesis 17.4 vs 6.3 on AndroidWorld; NNetNav 7.2 vs 4.2 [primary].
   - MobileA3gent's auto-labels of people's own phone use reached 58.1 vs 61.7 for human labels on AndroidControl, at ~1% of the cost [primary].

### 4.2 Pitfalls
| Pitfall | Evidence | Do this |
|---|---|---|
| The goal names the app | 93.4% of AndroidControl's app-opening goals; 98.5% vs 32.0% accuracy [measured] | strip names, check for leaks, keep a named minority |
| One rater's app isn't the only right one | "Nike shoes" → Temu; "flight Bangalore–Mumbai" → Agoda [measured] | soft `gold`; score "an acceptable app" |
| Training option text ≠ phone option text | Laya-Android: "other label formats are out of distribution" [primary]; SGD-X wording loss [primary] | render with an `AppProfiler` port; drop fields at random (research/15) |
| Templated or LLM-flavoured requests | Alexa: 95.19 top-1 on weak labels vs 89.65 on human paraphrases [primary]; query/tool word overlap ROUGE-L 0.06 [primary, 2503.01763] | mix real goals, real corpora and the owner's own requests; evaluate on human-written ones |
| Eval leakage | the 50 requests in `eval_apps.py` / `eval_assistant.py` drove the designs | never in prompts or rows; dedupe by n-gram overlap (research/15 dropped 12 near-copies) |
| Judge and labeller noise | best LLM judge precision on "success" 69.8% [primary, 2504.08942]; GPT-4o step labels 86–90% vs human 95% [primary, 2509.23738] | weight rows by judge score; keep a human-checked dev set |
| Category gaps | no money, ride or rail goals [measured] | teacher requests + Teach mode; **never synthesize payment actions** (D-026) |
| Position bias | reversing options changed 65–76 of 100 zero-shot picks [secondary, research/01] | `--shuffle-options` |
| Rare operations | HOME 29, LONG_PRESS 157 train examples [primary] | oversample; collect via Teach |
| Synthetic DONE | AndroidControl has no terminal action [primary] | also cut demos short and continue past the end for "not done" negatives (UI-Genie, 2505.21496) [primary] |

## 5. Friday's own data: Teach recordings and Routes

### 5.1 Teach recordings → rows
**Source:** the phone's `filesDir/teach/<time>.jsonl`, one JSON row per step (`Teach.kt`, D-033). Each row has:
- `goal`, `history` (Laya history strings), `op`, `package`, `time`;
- `state`: the raw forest from `readState()`, i.e. `{screen, package, windows[{type, root}]}`;
- `target` (an index into `buildCandidates(state)`), `target_label`, `target_bounds`;
- `payload`: typed text, or the launcher label for OPEN_APP.

`scripts/teach_touches.py` adds real touches, so Compose apps and edge-swipe BACK/HOME are captured too.

**Conversion** (Python, the repo's own `scripts/laya_android.py`: `parse_forest` → `build_candidates` → `option_label`):
1. **Build the state exactly as at inference:**
   `{"goal": goal, "history": history[-3:], "ui": ["[i] " + option_label(c) for i, c in enumerate(cands)]}`.
2. **Operation question** on every row, `expected.operation = op`. Ops seen: CLICK / LONG_PRESS / SCROLL_* / INPUT_TEXT / OPEN_APP / BACK / HOME / DONE.
3. **Target question** only for CLICK and LONG_PRESS:
   - first check that `cands[target]` has the recorded `target_label` and `target_bounds`;
   - if not, re-ground with the same box (else the smallest box containing its centre, as `Teach.match()` does), or drop the target and keep the operation;
   - count these mismatches: they measure Kotlin ↔ Python candidate parity (D-013).
4. **DONE row:** the recorder's Done step is the final screen, so it gives an operation-only row with `expected.operation = "DONE"`. Also emit a `done` yes/no row on every screen (§6 D): true on the final one, false before.
5. **App row:** one per recording.
   - The request is `goal` with the app's name stripped (§4.1), keeping the named form in a minority.
   - The gold app is the first `OPEN_APP` payload, else the first state's `package`.
   - The options are the owner's phone profiles (`captures/app_profiles_auto.json`, from the bridge's `profiles` command): gold + 15 lookalikes / random.
6. **INPUT_TEXT and OPEN_APP payloads are not training targets.** Laya never generates text; their history strings stay in later steps' `history`.

`teach_rows.py` in the run-pack (§7) does steps 1–5, plus dedupe (§5.4), the split (§5.5) and the oversampling (§5.6). Its app-row negatives are random for now; BM25 lookalikes are still to add. Self-checked on synthetic recordings [measured]:
- 2 recordings give 23 train rows and 7 test rows;
- `laya-train --dry-run` accepts them (26 items, 0 skipped);
- the held-out app request is de-named ("open the clock app and show 3 alarms" → "show 3 alarms").

### 5.2 Routes (the UI memory being added) → rows
A route stores, per goal and app, the step sequence that worked, as element signatures `(package, resource_id, label, class)`.
- **App rows: yes, directly.** Each route's goal → its app, exactly like a Teach app row.
- **Step rows: only with screens.** A signature is not a `ui` list, so a route alone cannot make an operation or target row.
- **Recommendation:** when a route is replayed successfully, write the same Teach-format record per step, tagged `source: "route"`. A successful replay already reads the live screen and resolves the signature to a candidate. Matching rule:
  - `resource_id` + `class`, then `label`;
  - if several candidates match (list rows, repeated "Add" buttons), use stored bounds, or drop the target.
- **Label weight:** a route is gold if it came from a Teach demo. If it came from Laya's own run, it is a *weak* positive, confirmed only by success (§5.3).

### 5.3 Approvals, skips and stops (weak labels)
| Signal | Rows | Label | Use |
|---|---|---|---|
| Teach demo / Teach-derived route | B, C, A | gold | main personal signal, and DONE |
| The owner's answer to "which app?" (research/12) | A | gold | the owner's phrasing |
| **Stop**, then the owner teaches the rest | B for the corrected step | gold | the best negatives: they come with the right answer |
| A successful route replay of Laya's own choices | B | weak (confirms the model) | calibration and regression; low weight |
| **Run** on a risky step | B | weak | calibration only |
| **Skip** | E, "not this" (§6) | weak negative | optional; a skip can mean "not now" |

- **Approvals as pairs.** V-Droid trained a verifier on pairs (gold vs every other candidate). It beat an SFT selector 47.4 vs 35.8 at equal data, and 9K → 110K pairs took AndroidWorld from 15.0% to 59.5% [primary, 2503.15937]. Our Run/Skip/Stop log is that kind of data. A pairwise loss is a research/15 question, not a data one.
- **Human labels beat model labels.** A reward model reached 94.9% accuracy on human labels vs 86–90% on GPT-4o labels [primary, 2509.23738]. The owner's own clicks are the gold standard.

### 5.4 Dedupe
1. **Normalize before hashing:** lowercase, digits → `#`, collapse whitespace, in both the goal and the `ui` labels. Clocks, prices and counters otherwise make every screen unique.
2. **Exact duplicates:** the same `(goal, ui, history, op, target label)` collapses to one row.
3. **Cap repeats:** at most 2 copies per (route, step) and per (task family, screen). Otherwise a route replayed 50 times outweighs everything else.
4. **Route vs Teach:** if a route came from a Teach demo, keep the Teach copy only.
5. **Near-duplicate goals stay:** paraphrases are useful. They only matter for the split (§5.5).

### 5.5 The owner's test set
- **Split by task family, never by step.** A task family is the normalized goal without quoted text and numbers, or the route ID. Hold out ~20% of families, and at least 30 episodes.
- **Add a time-based slice:** the latest week's recordings are test, to mimic future use.
- **Report per question:**
  - operation accuracy, target accuracy and DONE recall/precision;
  - app top-1, split into seen and unseen apps.
- **Keep app evaluation honest:**
  - `eval_apps.py`'s 50 requests never enter training;
  - research/15 asks for a fresh owner-written set of ≥ 200 requests.
- **Privacy:** recordings and rows stay on the phone, the Mac and the box (D-033, D-034), are never committed (D-004), and are deleted after each run.

### 5.6 Weighting against AndroidControl replay
**What the literature says** [primary]:

| Replay of old data | Result | Source |
|---|---|---|
| 0.25–1% of each old task | keeps 98–99.8% of old performance (T0, 8 tasks) | 2205.12393 |
| 1% pretraining data mixed in | prevents forgetting; **the smallest models forget most** (41M–1.27B) | 2502.06042 |
| 5% (weak shift) / 25% (strong shift); 50% hurts the new task | continual LLM pretraining | 2403.08763 |
| 5% replay on AndroidControl category tasks | forgetting −5.73 → −3.11; a KL penalty → −1.01 | 2603.02951 |
| 1:1 old exemplars per new domain | Alexa: all domains 88.2%; naive fine-tuning sends the old ones to ~0% | 1905.00921 |

research/15's own pilot flipped 1 of 5 tap targets with no replay [measured, research/15].

**Recommendation** (Laya is 322M, the forgetting-prone end, and the box makes replay cheap):
- **v1 (public app knowledge):** joint training on all AndroidControl step rows plus all app rows, so there is nothing to forget.
- **Personal updates:**
  - Teach rows oversampled **3–5×**, DONE rows **5×**;
  - route-replay and approval rows **1×**;
  - plus a fixed random AndroidControl replay **≥ 1:1** with the (oversampled) personal items, and a sample of v1 app rows.
  - When personal data passes ~5k steps, lower the oversampling.
- **Gate every checkpoint:** AndroidControl validation within 1 point, plus research/15 §3.5's gates.
- **Learning rate:** laya-android's or lower (research/15: encoder 1e-5).

### 5.7 How much
- **Seen vs unseen apps:** inside AndroidControl, seen apps beat unseen ones by ~12 points of Policy Joint (0.659 vs 0.540, at ≈ 18 demos per app) [primary].
- **Public data alone won't get there:** the AndroidControl paper extrapolates 95% in-domain step accuracy at ~0.5–1M episodes, and 10–60M out of domain [primary; its sections disagree 2×, UNCERTAIN]. Teach demos make the owner's apps *in-domain*, the cheap end of that curve.
- **What a few demos buy:**
  - In context, one demonstration gives most of the gain (LearnAct 19.3 → 51.7; IFRAgent: 2–3 demos add nothing).
  - Fine-tuning on 1,000 real users' episodes moved personalized success 1.5 → 12.5 (FingerTip) [primary].
- **Start with:** 3–5 demos for each of the owner's ~20 most-used task families. That is ≈ 60–100 episodes, 300–600 steps and 60–100 DONE rows, plus every "which app?" answer: ~1–2k items, minutes on the box.

## 6. Row formats for `laya-train` (validated with laya's own `items_from_rows`) [measured]
- **Shapes:** `items_from_rows` takes `{state, questions, expected}` or `{state, questions, gold}`, where gold is `{"probabilities": {label: p}}`, normalized, and may give several right answers.
- Each question becomes an item sharing the row's tokenized state.
- Extra keys (e.g. `meta`) are ignored.
- **Rule 0: a training row must equal the inference call byte for byte.** Same state keys and order, same instruction string, same `"key: text"` options, same budgets (3072 / 1536).

**A. App choice** — matches `eval_apps.py` and `LayaEngine.choose`:
```json
{"state": {"goal": "order chicken biryani for dinner"},
 "questions": {"app": {"type": "choice", "instructions": "Which app should be opened to do this request?",
   "criteria": {"0": "<profile, exactly as AppProfiler.profileOf renders it>", "1": "…", "15": "…"}}},
 "gold": {"app": {"probabilities": {"0": 0.5, "1": 0.5}}}}
```
- **Options:** ≤ 16 (one tournament group or one shortlist), in two shapes:
  - gold + random apps, as in group rounds;
  - gold + lookalikes, as in finals and BM25 shortlists (research/15 §4).
- **Gold:** `gold` when several apps can do it, `expected` when one can.
- **Cost:** ~12 state + ~253 head tokens with 16 short (~10-word) profiles [measured], about half a step item. Full `AppProfiler` profiles are capped at 48 tokens each, so a row can reach ~800 head tokens [estimate].

**B. One step (operation + target)** — laya-android's `serialization.row()`, unchanged:
```json
{"state": {"goal": "Turn off Bluetooth", "history": ["CLICK Connected devices Bluetooth, pairing"],
           "ui": ["[0] Navigate up (ImageButton)", "[1] Use Bluetooth (Switch) on",
                  "[2] Pair new device (LinearLayout)", "[3] - (RecyclerView) scroll"]},
 "questions": {"operation": {"type": "choice", "instructions": "Which operation should be performed next?",
                             "criteria": {"CLICK": "tap a UI element", "…": "all 12 of OP_DESC"}},
               "target": {"type": "choice", "instructions": "Which UI element should be targeted?",
                          "criteria": {"0": "Navigate up (ImageButton)", "1": "Use Bluetooth (Switch) on",
                                       "2": "Pair new device (LinearLayout)", "3": "- (RecyclerView) scroll"}}},
 "expected": {"operation": "CLICK", "target": "1"}}
```
- `target` only for a groundable CLICK or LONG_PRESS. Other ops get operation-only rows.
- State p50 is ~200 tokens and the target head ~190 [primary stats; our 65 episodes: state p50 196–234, max 1,375].

**C. DONE.** B's shape with `"expected": {"operation": "DONE"}` and no target, on the screen *after* the last action. That is laya-android's synthetic DONE, and exactly what Teach's **Done** records.

**D. Completion check** (`done`, yes/no, on the same state as B):
```json
{"state": {"goal": "…", "history": ["…"], "ui": ["…"]},
 "questions": {"done": {"type": "noul", "instructions": "Is the goal already complete on this screen?"}},
 "expected": {"done": false}}
```
- Every demo gives it for free: true on the final screen, false before. AndroidControl train alone gives 13,594 true and 74,714 false.
- It only helps if the phone asks the same question, and the Kotlin side needs the noul decode (research/15 §3.4).

**E. A skipped step as "not this"** (optional):
```json
{"state": {"…": "…"}, "questions": {"target": {"…": "…"}},
 "gold": {"target": {"probabilities": {"0": 0.31, "1": 0.0, "2": 0.52, "3": 0.17}}}}
```
- Take the model's own probabilities at the skipped step, zero the skipped option and renormalize. This pushes mass off the rejected choice without claiming which one was right.
- [UNCERTAIN] untested. Target-level only, low weight.

## 7. Run-pack for the GPU box (data side)
- **Location:**
  - Built and self-checked in the session scratchpad: `gpu_runs/finetune-data/` holds `run.sh`, `build_rows.py`, `teach_rows.py`, `train_bf16.py` and `eval_rows.py`.
  - Goes to `/root/friday-laya/finetune-data/` on the box (D-034), with its own uv venv, `UV_CACHE_DIR`, `UV_PYTHON_INSTALL_DIR` and `HF_HOME` inside that folder.
  - Read-only recon comes first. Long jobs run in tmux, one per GPU.
- **Self-checks done on the Mac:**
  - `bash -n` and `py_compile` pass;
  - `build_rows.py` ran on a synthetic processed set, and `laya-train --dry-run` accepted its rows (20 and 11 items, 0 skipped);
  - `teach_rows.py` ran on synthetic recordings (26 items, 0 skipped).
- **Nothing has trained or run on the box yet.** The port is pending.
- **research/15's run-pack** (`finetune-method/`) covers the method ablations. This one prepares AndroidControl for both, and tests the AndroidControl-derived data.

| # | Step | Exact command (in `/root/friday-laya/finetune-data`) | Time / VRAM (est.) |
|---|---|---|---|
| 0 | Recon | `nvidia-smi; nvidia-smi --query-compute-apps=pid,process_name,used_memory --format=csv; df -h .; free -g` | — |
| 1 | Env | `uv venv --python 3.12 .venv && uv pip install "laya==0.3.28" "transformers>=4.48,<5" pyyaml huggingface_hub torch --torch-backend=auto`; `git clone https://github.com/Byun11/laya-android && git -C laya-android checkout ab216d4` | ~5 min |
| 2 | Checkpoint | `snapshot_download("ByunByun/laya-android", revision="5a03b2bc…", local_dir="ckpt/laya-android")`, then `sha256sum` = `4ffca5d9…` (in `run.sh`) | 644 MB |
| 3 | AndroidControl | `curl -fLO -C - …/gresearch/android_control/android_control-000NN-of-00020` for NN in 00–19, plus `splits.json` and `test_subsplits.json`; then `LAYA_ANDROID_DATA=$PWD/data python laya-android/scripts/build_androidcontrol_dataset.py --workers 8` | **~50 GB disk**; processing unmeasured (est. 20–60 min, CPU) |
| 4 | Rows | `python build_rows.py laya-android data/processed/androidcontrol rows --replay 3000`, which writes `app_train` (~3.6k), `app_idd` / `app_idd_named` / `app_unseen` (~200 each), `replay` (3k steps), `val_steps` and `val_done` (827 each) | ~1 min |
| 5 | Baselines | `python eval_rows.py ckpt/laya-android rows/app_idd_named.jsonl rows/app_idd.jsonl rows/app_unseen.jsonl rows/val_steps.jsonl rows/val_done.jsonl --json runs/base.json` | ~5 min, ~2 GB |
| 6 | Pilot A: de-named app rows only | `python train_bf16.py --data rows/app_train.jsonl --base ckpt/laya-android --out runs/pilotA --epochs 2 --micro-batch 8 --grad-accum 4 --loss soft-ce --shuffle-options --device cuda`, then `eval_rows.py` on `app_idd`, `app_unseen`, `val_steps` | ~3 min at ~40 items/s; 8–12 GB |
| 7 | Pilot B: same + replay | the same on `cat app_train.jsonl replay.jsonl` (~3.6k app + ~4.5k step items) | ~7 min; 8–12 GB |
| 8 | Personal rows (once Teach data exists) | copy `scripts/laya_android.py` into `repo/scripts/`, then `python teach_rows.py repo rows/teach teach/*.jsonl --profiles app_profiles_auto.json --apps apps.json --holdout 0.2 --repeat 3 --done-repeat 5`. Mix with `build_rows.py … --replay N`, N ≥ the personal train rows; `cat rows/teach/teach_train.jsonl rows/replay.jsonl > rows/mix_personal.jsonl` | seconds (CPU); training as step 6 |
| 9 | Personal eval | `python eval_rows.py runs/<ckpt> rows/teach/teach_test.jsonl rows/val_steps.jsonl` | ~1 min |

**What the runs answer:**
- **Step 5:**
  - Is the 98.5 / 32 gap the same on official test splits?
  - Does the untrained yes/no `done` question already separate final screens (`val_done` AUC)? research/15's small probe says no.
- **Pilots A and B:**
  - Do de-named AndroidControl goals teach app *use*: does `app_idd` rise?
  - Does that carry to unseen apps (`app_unseen`)? With names-only options, little is expected (research/15: names don't transfer).
  - What does forgetting cost on `val_steps`, and does replay hold it?
- **Bring back:** only `runs/*.json` and `*.log`. Checkpoints stay on the box and are deleted after use.

**Later downloads on the box** (none on the Mac):

| Item | Size |
|---|---|
| MASSIVE 1.1 | 40 MB |
| SGD | 50 MB |
| Hinglish-TOP | 10 MB |
| Taskmaster | 108 MB |
| CLINC150 | 2 MB |
| AppGoblin descriptions | 788 MB `.tsv.xz` (free account) or 2.1 GB parquet |
| AndroidLab Android Instruct | not checked |

## Recommendations
1. **Build "which app" data from four sources, all rendered with phone-format profiles and ≤ 16 options, with soft gold:**
   - de-named AndroidControl (~4–13k pairs);
   - teacher-written requests for the owner's 207 apps and the missing Indian kinds (~50–100 per app);
   - MASSIVE / SGD / Hinglish-TOP mapped to kinds;
   - the owner's Teach and route goals.
   - **Why:** the measured leak (98.5 vs 32.0%) and the category gaps; plus research/15's finding that the model learns to *read profiles*.
   - **Rejected:**
     - AndroidControl goals as written (they teach copying);
     - per-app answer tables (D-031);
     - training on description text the phone never shows.
2. **Keep laya-android's step pipeline unchanged and replay all of it in v1.**
   - **Why:** it is the trained format; joint training can't forget; a 4090 epoch is ~1 h.
   - **Rejected:** re-deriving candidates for AndroidControl from our Kotlin port (D-013 already proves parity).
3. **Turn Teach recordings into B, C, D and A rows with `scripts/laya_android.py`.**
   - Have routes write Teach-format records on successful replays.
   - Hold out by task family and by time; oversample personal 3–5× against ≥ 1:1 AndroidControl replay.
   - **Why:** gold labels in the owner's apps, and DONE examples where Laya is weakest.
4. **Use AppGoblin (CC BY 4.0) and Wikipedia/Wikidata only as teacher input.**
   - **Rejected:** AndroZoo (non-commercial, institutional only), Jl-wei (academic only), MobileRec (no license), verbatim Play text.
5. **Do all downloads and training on the box** (D-034). Run steps 0–7 of the run-pack first. AndroidControl processing is shared with research/15's Phase 1.

## Risks
1. **Label noise from one-rater apps and LLM judges.** Soft labels and judge-weighting help. A human-checked dev set is still required.
2. **Profile mismatch.**
   - A Python port of `AppProfiler` can only reproduce label, package words and category for public apps; capabilities and shortcuts need the manifest.
   - So public-app profiles are sparse unless field-dropout covers them (research/15).
   - The owner's own profiles are exact.
3. **Licenses.**
   - AndroidControl's data license rests on a README header [UNCERTAIN].
   - SGD and MTOP are share-alike.
   - AppGoblin's CC BY doesn't clear the developers' copyright on the text itself.
   - Fine for a private model ("not gonna publish", D-033); revisit before sharing anything.
4. **Forgetting** the screen skill when adding app rows: replay plus the research/15 gates.
5. **Privacy:** Teach and route data hold real screens. Phone, Mac and box only; wiped after runs; never committed.
6. **Self-confirmation:** training on Laya's own successful runs (routes, Run approvals) reinforces its existing habits. Keep those rows at 1× and favour Teach.
7. **Shared GPU box:** read-only recon first; work only inside our folder; leave VRAM for others.

## Open questions
1. The official-split numbers for step 5 and pilots A/B, from the box.
2. How many usable de-named pairs an LLM rewrite gives, vs the regex (3,643).
3. The Kotlin vs Python candidate parity on real Teach recordings (§5.1 step 3).
4. Whether the route format will keep bounds; that decides how ambiguous signatures resolve.
5. The dataset survey's unchecked rows (AMEX, MONDAY, MobileViews, CAGUI, A3), and whether any India-focused trajectory set exists.
6. Whether `ApplicationInfo.category` is set by Play installs on the Pixel 8 (it decides one profile field).

## Dead ends
- **Wikidata as the profile source:** 6,968 Play IDs, half of them games; 66 of the owner's 207 apps; Swiggy, Zomato, Paytm, PhonePe, IRCTC, CRED, Zepto and Blinkit all lack a Play ID [measured]. Its descriptions are company-level ("Indian online travel company").
- **AndroZoo for APKs or descriptions:** needs a faculty sponsor and an institutional email, and is non-commercial [primary].
- **App Actions built-in intents as a capability vocabulary:** 46 were deprecated in Feb 2024, including food order, taxi, money transfer and flights [primary].
- **AI4Bharat for Indic commands:** no such set exists; theirs are speech, NER, QA and MT [primary].
- **A public app-store query → app log:** none found. ISTAS (6,877 queries, no license) is the only real one.
- **Training on the Mac:** it works, but swapped the 16 GB Mac (1.29 items/s, 11.5 GB). Stopped by the coordinator, 2026-10-17.
- **A 300 MB slice of one AndroidControl shard over a slow link:** ~150 KB/s. Stopped at 245 MB, then deleted.

## Sources
**Code and data read:**
- https://github.com/Byun11/laya-android (`ab216d4`): README, MODEL_CARD.md, NOTICE, `src/*`, `scripts/build_androidcontrol_dataset.py`, `scripts/train_laya_android.py`, `configs/full.yaml`, `results/data/androidcontrol_stats.json`
- https://huggingface.co/ByunByun/laya-android `rl_agent_config.json`
- laya 0.3.28 `train.py`, `train_cli.py`, `calibrate.py`, `shortlist.py`, `evals.py`

**AndroidControl:**
- https://storage.googleapis.com/gresearch/android_control/ (splits, test_subsplits, shard 0 head)
- https://arxiv.org/abs/2406.03679
- https://github.com/google-research/google-research/tree/master/android_control
- Third-party mirrors: https://huggingface.co/datasets/OfficerChul/Android-Control-84k · https://huggingface.co/datasets/endlesswinds/AndroidControl-JSON-Splits

**Other datasets:**
- AITW https://github.com/google-research/google-research/tree/master/android_in_the_wild · AitZ https://arxiv.org/abs/2403.02713
- GUI-Odyssey https://huggingface.co/datasets/OpenGVLab/GUI-Odyssey · AndroidLab https://github.com/THUDM/Android-Lab
- FingerTip 20K https://arxiv.org/abs/2507.21071, https://github.com/tsinghua-fib-lab/FingerTip-20K · Mobile3M https://arxiv.org/abs/2409.14818
- ISTAS https://github.com/aliannejadi/istas · Leyzer https://github.com/cartesinus/leyzer
- AndroidControl-Curated https://arxiv.org/abs/2510.18488

**Metadata:**
- https://huggingface.co/datasets/appGoblin/app-store-app-descriptions · https://appgoblin.info/free-app-datasets
- https://www.wikidata.org/wiki/Property:P3418 (SPARQL, 2026-10-17)
- https://huggingface.co/datasets/Jl-wei/gp-app-description · https://androzoo.uni.lu/access
- https://www.kaggle.com/datasets/lava18/google-play-store-apps · https://www.kaggle.com/datasets/gauthamp10/google-playstore-apps
- https://www.interactionmining.org/rico · https://gitlab.com/fdroid/fdroiddata
- https://developer.android.com/reference/app-actions/built-in-intents · https://play.google.com/robots.txt

**Utterances:**
- https://github.com/alexa/massive · https://github.com/google-research-datasets/dstc8-schema-guided-dialogue · SGD-X https://arxiv.org/abs/2110.06800
- https://github.com/google-research-datasets/Hinglish-TOP-Dataset · https://github.com/google-research-datasets/Taskmaster
- https://github.com/clinc/oos-eval · https://huggingface.co/datasets/tasksource/mtop

**Synthesis and forgetting:**
- ToolLLM 2307.16789 · Alexa 1804.08064 / 1804.08065 · OS-Genesis 2412.19723 · NNetNav 2410.02907
- MobileA3gent 2502.02982 · UI-Genie 2505.21496 · ToolRet 2503.01763 · AgentRewardBench 2504.08942 · GUI-Shepherd 2509.23738
- V-Droid 2503.15937 · LearnAct 2504.13805 · IFRAgent 2508.08645
- 2205.12393 · 2502.06042 · 2403.08763 · CGL 2603.02951 · CoNDA 1905.00921
