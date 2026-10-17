# Teaching Laya what every app is for: the fine-tune method

_Last updated: 2026-10-17 · Trust tags: [primary] read at source · [secondary] search/blog/snippet · [measured] run here · [UNCERTAIN] unconfirmed_

The owner wants Laya to understand the basic use of every installed app without hand-written per-app rules
or catalogs (D-031). This note is the method: what to train, on what data, where it runs, and how we prove it.
Two pieces are in progress elsewhere: the data pipeline (research/14) and the on-device profile builder
(`AppProfiler.kt`).

**Test set:** the owner's 207 apps and the 50 requests of [`scripts/eval_apps.py`](../scripts/eval_apps.py)
(34 that a skill covers, 16 long tail). Only aggregates are reported; "Reproduce" lists the scratch harness.

## TL;DR
1. **Today, with no training** [measured, base laya-android]:
   - The phone's automatic profiles lift app choice from 36% (names) to **48%**. Hand-written purposes,
     the catalog D-031 rules out, would give 58%.
   - When Laya is sure it is usually right: at confidence ≥ 0.7 it is right 90% of the time, on 20% of
     requests. Below that, Friday should ask.
2. **Laya, not the profiles, is the bigger gap.** In 22 of the 26 misses on automatic profiles, at least one
   acceptable app *does* declare a capability or shortcut, and Laya still didn't pick it [measured]. That is
   what fine-tuning fixes.
3. **Fine-tuning teaches app choice that carries over to apps never seen in training** [measured, small pilot].
   - Trained on 141 public apps, none on the owner's phone: 741 rows, 4 min on the M5.
   - Results: tournament 58 → **66%**; shortlist-then-choose 66 → **74%**; right when a right app is in the
     shortlist 80 → **90%**. All on purpose-style text.
   - Names-only did not move (36 → 36%): it learns to *read profiles*, not unseen names.
   - n = 50, sign test p ≈ 0.3: a direction, not proof. Not yet measured on the automatic profiles; that is
     run on the GPU box.
4. **It forgets unless we replay the old task** [measured]. The same tiny run, with no replay:
   - Operation unchanged on 25 of 25 recorded live screens, but **1 of 5 tap targets flipped**.
   - Paraphrase routing 81 → 75%.
   - So the real run trains the new questions jointly with AndroidControl replay, at a lower learning rate,
     with the 256k-token embedding table frozen.
5. **The phone needs one pass, not a tournament, so the shortlist matters.**
   - A tournament over ~200 apps is 14 sequences in two calls (est. 5–15 s on the Pixel 8).
   - BM25 top-16, then one Laya pass, needs no rules or training. It beats the tournament on rich text
     (66 vs 58%) but not yet on the automatic profiles (44 vs 48%): BM25 finds a right app in the 16 only 70%
     of the time (50% on the long tail).
   - Laya's own encoder as an embedder is worse than BM25 (36–48% recall): a dead end.
6. **Compute.** laya's trainer runs on Apple MPS (fp32; 1.4 items/s, 9.2 GB for a full fine-tune), but the
   16 GB Mac swaps. Training runs on the owner's GPU box (D-034).
   - A run-pack is ready ("To run on the GPU box"): ~1 min per pilot ablation on a 5090, ~2–3 h for the full
     joint run [UNCERTAIN until run].

## 1. What phone agents do to learn app functions, and what transfers to Laya
Laya picks among options in one pass and can't generate text, so "memory" has to become **option text** (what
the app is for) or **training rows** (requests labelled with the right app). Numbers are from the papers'
tables [primary unless marked].

| System | What it learns about apps | How it is built | Measured effect | What transfers to Laya |
|---|---|---|---|---|
| AutoDroid (MobiCom'24) | ≤15-word function summary per UI element; simulated tasks | random exploration + LLM; fine-tunes Vicuna-7B | DroidTask action/completion: no FT 11.3/0.6 → FT 57.7/41.1; memory-only FT 51.9/31.6 (Table 3) | training on machine-written app knowledge works without rationales |
| AutoDroid-V2 (MobiSys'25) | per-app docs (element, effect) | GPT-4o over exploration traces (~$4/app); tasks verified by running | 54.4% vs 43.9% (Table 2) | docs → verified tasks → training rows |
| AppAgent / v2 / X | 1–2 sentence docs per element | exploration or watching demos | AppAgent success: no docs 48.9, explored 73.3, demo 84.4, hand-written 95.6 (Table 1) | **option-text quality is a first-order lever** |
| MobileGPT (MobiCom'24) | sub-task memory; "App Launch" embeds Play descriptions, top 3 | explorer + GPT-4-turbo | 82.7% task success; app choice not reported | descriptions for retrieval |
| Mobile-Agent-v2 / E / v3.5 | memory unit; tips and shortcuts; GUI knowledge turned into QA *training* data | LLM | v2 without memory 88.6 → 77.3 (Table 4); E 75.1 → 86.9 (Table 3); v3.5 unablated | v3.5's "knowledge → training data" is the closest recipe |
| LearnAct | retrieved demonstrations | MiniLM-L6 retrieval | UI-TARS-7B online 18.1 → 32.8 (Table 6) | the owner's Teach demos (D-033) as training rows |
| **MobileRAG** (2025) | app = Play "About" text + name + package | **BGE-small fine-tuned on LLM-written queries** (+ "none" queries), top 3 to GPT-4o | app selection 100% (80 tasks / 20 apps); 93.8 without the retrieval (Table 5) | **the nearest published version of this plan** |
| AppSelectBench (2025) | 100 desktop apps, names or descriptions | — | best 0.644 even for GPT-5; recurring lookalike confusions | hard negatives must be lookalikes |
| Alexa skill routing (2018) | 1,500+ skills; shortlist then rerank | trained shortlister + reranker with usage features | top-1 81.5 → 93.8 after reranking the top 5 with usage (Tables 3–4) | **owner usage is a strong prior**; shortlist-then-rerank is standard |
| Octopus v2 / DroidCall | one token per API / Android intents | LLM-written queries | 100 queries per API → 98.1% (Table 1); Gemma2-2B LoRA 59 → 85% | ~100 requests per class is enough; per-app tokens can't cover unseen apps |
| ToolLLM retriever, Tool2Vec, TinyAgent | trained retrievers or classifiers over tools | LLM-written queries | ToolLLM NDCG@1 78.0 vs BM25 18.5; Tool2Vec usage vectors 63.1 vs description vectors 20.6 (E5-small, Table A.3); TinyAgent recall 0.998 | a learned shortlister beats BM25 once there is data |

No paper measures a small encoder choosing among ~200 phone apps, so every row is an analogy [UNCERTAIN as a
transfer].

**What transfers, and what doesn't:**
1. **Option text is the lever.**
   - Literature: AppAgent docs 48.9 → 95.6.
   - Ours: names → automatic profiles → purposes gives 36 → 48 → 58% [measured].
   - The text must come from the phone (D-031), so the profile builder matters as much as the model.
2. **Train on rows where the app appears only as text in an option.** Never give an app its own token or
   class, which can't cover an app installed tomorrow. Laya's choice head already works this way.
3. **Exploration memory (AutoDroid, AppAgent) is for operating inside an app, not for choosing one.** Its
   counterpart here is Teach mode (D-033) plus Laya-Android's op/target training.
4. **Shortlist, then choose with the cross-encoder.**
   - The model that reads request and option together picks better: zero-shot entity linking 76.06
     cross-encoder vs 57.61 pooled (Table 4).
   - The shortlist must have high recall: Gorilla falls from 91 with oracle documents to 17 with BM25
     (Table 1).
5. **Owner usage and remembered picks go outside the model.** Alexa gained most from them (75.7 → 89.6 with
   the enabled-skill signal). They are facts about this phone, not something to learn.

## 2. App understanding from on-device signals: the profile Laya reads
The profile format comes from `AppProfiler.kt`: `Label — package words category; does: capabilities; shortcuts: labels`.

What a sideloaded app can read without root (Android 14–16):
- `QUERY_ALL_PACKAGES` is a *normal* permission, granted at install to a sideloaded app; a `<queries>` list also
  works [primary].
- Private-space apps (Android 15) stay invisible to Friday [primary].

| Signal | API | Coverage on the owner's phone | Reliability |
|---|---|---|---|
| Label | launcher `queryIntentActivities` / `LauncherApps.getActivityList` | all | brand only: 36% alone (research/12) |
| Package-name words | `packageName`, platform noise removed | most | weak alone (BM25 recall@16 60%) [measured] |
| Category | `ApplicationInfo.category` (API 26; 9 values incl. ACCESSIBILITY in 31) | sparse; no adoption data [UNCERTAIN] | precise when set; the installer may set a hint (`setApplicationCategoryHint`); whether Play does is unknown |
| **Capability probes** (fixed platform vocabulary) | `queryIntentActivities(probe, MATCH_ALL)` | **48 of 205** with today's 10 probes [measured] | precise for `upi://pay`, `MEDIA_PLAY_FROM_SEARCH`, `SET_ALARM`; noisy for SEND |
| **Static shortcuts + App Actions** | launcher activity's `android.app.shortcuts` → `loadXmlMetaData` / `getResourcesForApplication().getXml()` | **37 of 205** [measured] | readable for any visible app. Dynamic and pinned shortcuts need the default launcher **or the active assistant (VoiceInteractionService)** [primary, AOSP] |
| Web domains | `DomainVerificationManager.getDomainVerificationUserState(pkg)` (API 31) | most consumer apps [UNCERTAIN] | readable for **any visible app**: AOSP checks visibility only [primary] |
| Service capabilities | `queryIntentServices`: `MediaBrowserService` / media3 session, `InCallService`, `HOST_APDU_SERVICE` with a payment aid-group, `CarAppService` categories (NAVIGATION, POI, IOT, WEATHER, MESSAGING, CALLING, MEDIA…) | few apps each | very precise [primary] |
| Health Connect | requested `android.permission.health.READ_*` (`GET_PERMISSIONS`); `VIEW_PERMISSION_USAGE` + `HEALTH_PERMISSIONS` | the fitness / sleep apps | precise ("steps", "sleep"), exactly the long-tail requests |
| Widget / tile labels | `AppWidgetManager.getInstalledProvidersForPackage` + `loadLabel` | ~⅓ of apps [UNCERTAIN] | short verbs |
| Full manifest | `ZipFile(ApplicationInfo.sourceDir)` + a binary-XML parser (installed APKs are 0644) | all non-archived apps | `GET_INTENT_FILTERS` is unsupported since API 31, so this is the only way to read every filter. `openXmlResourceParser("AndroidManifest.xml")` breaks on split APKs [primary] |
| App Actions capabilities | `<capability android:name="actions.intent.*">` in the same XML | rare | docs live (21 built-in intents), but phone Assistant is being retired from 2026-09 [secondary], so expect these to rot |
| AppFunctions (Android 16+) | static XML through `getProperty("android.app.appfunctions")` + assets | rare (Samsung Gallery, some OEM calendar/notes/tasks) | executing needs `EXECUTE_APP_FUNCTIONS` plus an allowlist plus user approval [primary] |
| Usage prior | `UsageStatsManager` (`PACKAGE_USAGE_STATS`, the owner grants it) | apps actually used | strong prior; **a number, not words** |
| Opt-in: first-screen text | Friday's AccessibilityService, opening each app once | not measured | logins, balances and chats on screen. Mask digits, currency and emails; never in money apps (D-026) [UNCERTAIN] |

**Profile order** (best words first, most informative fields first):
1. label;
2. category title;
3. capability tags from the probes, extended with `CATEGORY_APP_*` (MUSIC, MAPS, FITNESS, WEATHER, CALENDAR,
   MESSAGING, …), Health Connect, media, telecom, tap-to-pay and car-app categories, `SET_TIMER`,
   `CREATE_NOTE`, `CREATE_REMINDER`, calendar `INSERT`;
4. the first 3 shortcut / built-in-intent / AppFunction names;
5. widget labels;
6. web domain, if it differs from the brand.

Permissions stay out of the text except `health.*` and ACTIVITY_RECOGNITION.

- **What the misses say** [measured, aggregate]:
  - 17 of 205 profiles are the label alone.
  - Of the 50 eval requests, 43 have at least one acceptable app that declares something.
  - Of the 26 base-model misses on automatic profiles, only 4 are cases where no acceptable app declares
    anything.
  - So richer probes help, but the chooser is the bigger gap.
- **Budget the profile in tokens, not words.**
  - Each option is capped at 48 tokens (laya `build_head`) [primary], and is rendered as `i: <profile>`.
  - Measured with laya-android's tokenizer: comma lists of capitalised labels cost about 1.7 tokens/word and
    hit 48 at about **27 words**. Prose costs about 1.15 tokens/word (35 words = 41 tokens) [measured].
  - So: cap at ~44 tokens with the on-device tokenizer, and keep 35 words only as a fallback.

## 3. Training design with laya's trainer
### 3.1 One model, four questions
The architecture needs no new parameters [primary, `laya/common.py`]:
- Each question is its own sequence: `[CLS] "<type> question: <instructions>" [SEP] [MASK] opt0 … [SEP] <state> [SEP]`.
- A 2-layer head and one scorer are shared. `type_emb` tells choice / score / noul apart; the instruction
  tells the tasks apart.

| Question | Type | State | Options | Data |
|---|---|---|---|---|
| `operation` (12 ops), `target` | choice | `{goal, history, ui}`, as laya-android | unchanged | **replay** (AndroidControl, as trained) + the owner's Teach demos (D-033) |
| **`app`**: "Which app should be opened to do this request?" | choice | `{goal: request}` | ≤ 16 profiles (the shortlist) | LLM-written requests over profiled apps (research/14) |
| **`done`**: "Is the goal complete on this screen?" | noul | `{goal, history, ui}` | yes / no | AndroidControl final vs earlier screens; Teach-mode Done marks |
| `skill` (16 skills) | choice | `{goal: request}` | `SKILLS` | a few teacher-labelled rows, only to hold routing steady |

**Row format** (laya's `{state, questions, gold}`; questions on one state share its tokenisation):
```json
{"state": {"goal": "how did I sleep last night"},
 "questions": {"app": {"type": "choice", "instructions": "Which app should be opened to do this request?",
                       "criteria": {"0": "<profile>", "…": "…", "15": "<profile>"}}},
 "gold": {"app": {"probabilities": {"4": 0.5, "11": 0.5}}}}
```
- **Profiles in training must be the phone's own format.** Train on text from a port of `AppProfiler`, and
  randomly drop fields so a label-only or sparse profile is never out of distribution. The pilot trained on
  purpose-style text, which is not what the phone sends.
- **Several right answers:** the gold spreads over every option that can do the request. The teacher labels
  which ones can; no rule decides it.
- **Hard negatives:** BM25-mined lookalikes (a game store for "play", a device finder for "ring") plus
  same-category apps that can't do this request.
- **"None of these":** add rows with an explicit "none of these apps can do it" option.
  - Abstaining is a separate skill from selecting: a 2026 on-device router reached AUC 0.697 with BM25
    features vs 0.806 with a neural gate [primary, arXiv 2609.18672].
  - [UNCERTAIN for Laya: to test]
- **Option order:** `--shuffle-options` is on, as in laya-android's own training [primary, `rl_agent_config.json`].

### 3.2 Data volume and mixing
| Source | Size (target) | Share of each epoch |
|---|---|---|
| `app` rows | ~50–100 requests per app over ~1–2k public apps, plus the owner's apps, 2–3 option sets per request | ~45% |
| AndroidControl op/target replay | drawn from laya-android's 88,308 training steps [primary, research/01] | ~40% |
| `done` | 1 positive : 2 negatives per episode | ~10% |
| `skill` | a few thousand | ~5% |

- **App rows:** Octopus v2 reached 98% with about 100 requests per API, which sets the per-app volume
  [primary].
- **Replay share:** decoder-LM continual training shows how much replay is needed [primary, arXiv 2501.13453]:
  - with 20–50% replay it kept 76.9–80.6 on the old task, vs 11.2 without;
  - most of the loss came in the first ~150 steps.
- **Encoder precedent:** CoNDA (encoder intent models) mixed old and new data 1:1 [primary].
- **Our own evidence:** the pilot, with no replay, already flipped 1 of 5 targets [measured].

### 3.3 Hyperparameters
**What laya-android used** [primary, its config]:
- soft-CE;
- 3 epochs, with epoch 2 kept;
- effective batch 32;
- `encoder_lr` 2.5e-5, `head_lr` 1e-4;
- gradient checkpointing and shuffled options;
- 2.7 h on a 4090.

**Second stage, starting from laya-android:**
- `encoder_lr` 1e-5 (sweep 1e-5 vs 2.5e-5).
- `head_lr` 5e-5–1e-4, cosine schedule to 1e-6.
- 2 epochs, keeping the best by held-out app accuracy that also passes the forgetting gates.
- Effective batch 32.

**Freezing:**
- **Freeze the 256k-token embedding table** (197M of 322M parameters; the pilot trained 125M). Rare and
  multilingual tokens can't be overwritten by a small English dataset, and optimizer memory drops
  (4.7 vs 9.2 GB on the Mac).
- **Head-only** (15M trainable) is the cheapest option, and untested: the Mac run was stopped. CoNDA shows a
  frozen encoder can add classes [primary], but app choice may need the encoder to read "wake" ≈ "alarm"
  [UNCERTAIN; run R2 on the box].
- **No LoRA or adapters.** laya's trainer has neither, and the ONNX path (research/06) wants one merged graph
  anyway.

### 3.4 Calibration after fine-tuning
1. **One temperature per question type.** `finetune()` fits one temperature per type on a held-out slice
   (≤ 400 items), so `operation`, `target`, `skill` and `app` share one choice scalar. A per-option-count
   bucket needs ≥ 2,000 items [primary, `laya/calibrate.py`]. In the pilot the app question wanted T = 1.51,
   while laya-android's choice T is 1.16 [measured].
2. **Calibrate per question in Friday.** `LayaInput.kt` already divides logits by a hard-coded `TEMPERATURE`
   (`[1.1609, 1, 1]`, by type) [primary].
   - Make that a per-question map fitted on held-out records.
   - Set "act or ask" thresholds per question with `laya.calibrate.fit_abstention_thresholds` (target error 10%).
   - Re-fit after every retrain. A new checkpoint must never ship with the old constants.
3. **DONE needs noul decoding in Kotlin.** `decide()` decodes only choice questions today [primary,
   `LayaInput.kt`]; the `done` question needs the noul path (2 markers, P(true)).

### 3.5 Forgetting checks (the gates for any new checkpoint)
1. **AndroidControl test sample:** Type and Grounding within 1 point of laya-android's 76.7 / 61.7 [primary baseline].
2. **Recorded live screens** (`captures/run-*.jsonl`, 25 steps today; more from Teach mode): operation and
   target agreement with laya-android ≥ 96%, and any flip reviewed by hand.
3. **Routing** (`eval_assistant.py`): seen and paraphrase not below 82 / 81%.
4. **`done`:** better than the zero-shot probe in §6.
5. **ONNX fp16 parity:** re-run research/06's export and parity before the phone sees it.

### 3.6 Compute: Mac vs the GPU box
| Setup (laya `train_model`, 16-option app items) | M5 (MPS, fp32), measured | 4090 / 5090 |
|---|---|---|
| Head only (15M trainable) | 6.8 items/s, 3.5 GB (610-token items) | — |
| Frozen embeddings (125M) | 1.9 items/s, 4.7 GB (610 tokens); **6.2 items/s, 4.3 GB (230 tokens, the pilot)** | — |
| Everything (322M) | 1.4 items/s, 9.2 GB (610 tokens) | laya-android: ~420k item-steps in 2.73 h ≈ 43/s on a 4090 [primary config; derived] |

**Does laya's trainer support MPS? Yes** [measured, primary code]:
- `resolve_device('auto')` picks MPS.
- Autocast and the gradient scaler are CUDA-only, so the Mac trains in fp32.
- Gradient checkpointing works when asked for.

**Why training is not on the Mac anyway:** the 16 GB Mac swaps when another model job runs (observed
2026-10-17), so training belongs on the GPU box (D-034).

**On the GPU box:**
- On CUDA, laya's trainer uses fp16 autocast with a GradScaler (hard-coded); laya-android itself trained in
  bf16. Either is fine.
- 5090 speed: estimated ~1.3–1.6× a 4090 [UNCERTAIN].
- A full joint run (~150–250k items × 2 epochs): ~2–3 h.

## 4. Retrieval, then choose: a shortlist without rules
Base model, before any training; 50 requests over the owner's ~206 apps. Figures are covered / long tail / all.

| Setup | recall@16 | Laya's accuracy | Confidence ≥ 0.7: coverage / accuracy |
|---|---|---|---|
| A names, tournament over all apps (research/12) | — | 32 / 44 / 36% | — |
| **D automatic profiles, tournament** (14 sequences) | — | 50 / 44 / **48%** | 20% / 90% |
| **SD automatic profiles, BM25 top-16 → one pass** | 79 / 50 / **70%** | 47 / 38 / **44%** (63% when a right app is in the 16) | 16% / 88% |
| B purposes (hand-written; reference only), tournament | — | 50 / 75 / 58% | 26% / 92% |
| S purposes, BM25 top-16 → one pass | 82 / 81 / 82% | 65 / 69 / **66%** (80% when a right app is in the 16) | 38% / 95% |
| Laya's own encoder (mean-pooled) top-16, purposes | 44 / 56 / 48% | 32 / 50 / 38% | 12% / 50% |
| Laya's own encoder top-16, names | 35 / 38 / 36% | 18 / 19 / 18% | 14% / 43% |
| BM25 top-16, label + package words | 74 / 31 / 60% | 29 / 25 / 28% | 28% / 64% |

1. **Don't use Laya's own embeddings for the shortlist.** `laya.shortlist.embed_fn_from_agent` mean-pools an
   encoder trained as a cross-encoder, and loses to BM25 everywhere. laya's own docstring says a dedicated
   bi-encoder "will usually shortlist better" [primary].
2. **On the phone, one pass is the target.**
   - The tournament is 14 sequences. On Pixel 8 CPU that is several seconds (research/06: ~2.4 s for a ~700-token
     step; est. 5–15 s per request).
   - SD costs one pass, but today it trails the tournament (44 vs 48%) for two reasons:
     - BM25 recall on sparse profiles (70%; 50% on the long tail), because requests share no words with what
       apps declare ("how did I sleep" vs a band app's package words);
     - the base chooser reads sparse profiles badly (63% right in the shortlist vs 80% on purpose text).
   - Fine-tuning targets the second. The pilot took the chooser from 80 to 90% on purpose text.
3. **Ways to close the recall gap, in order:**
   1. **Richer probes** (§2): Health Connect "steps/sleep" is exactly the long-tail vocabulary.
   2. **A bigger shortlist.** 32 options still fit one pass: (1536−16)/32 = 47 tokens per option. Test 16 / 24 / 32.
   3. **A small learned dual encoder** (MiniLM-L6 22M or a static-embedding model of tens of MB) trained on
      the same teacher pairs. Profiles are embedded once per install, so a request costs one tiny pass.
      MobileRAG did this with BGE-small; ToolLLM's retriever beats BM25 78 vs 18.5 NDCG@1 [primary].
   4. **Fuse BM25 and dense ranks** (RRF), and always add the owner's remembered pick.
   5. **A contrastive objective on Laya's own encoder** (one model, two uses) [UNCERTAIN; needs a custom loop
      and may interfere with the choice head].
4. **Dead end on the phone: scoring every app yes/no.** ~200 passes per request.

## 5. Evaluation
1. **Held-out apps.**
   - Train on a public universe with **every app on the owner's phone removed**: fuzzy label match, plus every
     app named as an eval answer. Score on the owner's phone, where all apps are unseen. This is the pilot's
     protocol.
   - When the owner's apps join training (personal fine-tune), report seen and unseen apps separately.
   - Expect a gap: entity linking scores 87.7 on seen vs 83.0 on unseen entities [primary].
2. **Held-out phrasings.**
   - No eval request is ever trained on. The pilot dropped 12 requests sharing ≥ 50% of their content words
     with an eval request.
   - **Write a fresh eval set with the owner (≥ 200 requests).** The current 50, the pilot's training data and
     the purpose texts were all written by Claude, so a shared style can flatter results.
   - n = 50 can't separate 8 points (pilot p ≈ 0.3); 200 cases give about ±6 points.
3. **Setups to report** (`eval_apps.py` A/B/D, plus S/SD):
   - Split by covered vs long tail, by seen vs unseen app, and by whether a profile declares anything.
   - Include the "act or ask" table (coverage and accuracy per threshold).
   - The headline is **SD** (one pass, automatic profiles).
4. **Forgetting:** the §3.5 gates, every time.

## 6. Pilot fine-tune [measured, 2026-10-17, M5]
**Data:**
- 187 public apps, written teacher-style. 46 were dropped because they matched the owner's list or an eval
  answer, leaving 141.
- Requests are grouped by function. 274 were written; 12 dropped as near-copies of eval requests and 40
  because the function's only apps were on the owner's phone. That leaves 247 training and 27 calibration
  requests.
- 3 rows per request, each with 16 purpose-style options: 1–2 correct, up to 4 BM25 lookalikes, the rest random.

**Training:**
- laya `train_model`, soft-CE, shuffled options, embeddings frozen.
- `encoder_lr` 1e-5, `head_lr` 5e-5; 2 epochs at effective batch 16.
- 238 s and 4.3 GB, with no replay.
- Calibrating on the held-out requests fitted T = 1.51, so the model is over-confident.

**Before → after**, on the owner's phone with every app unseen. Figures are covered / long tail / all:

| | base | pilot | fixed / broken |
|---|---|---|---|
| A names (tournament) | 32 / 44 / 36% | 38 / 31 / 36% | 3 / 3 |
| B purposes (tournament) | 50 / 75 / 58% | 59 / 81 / **66%** | 7 / 3 (sign test p ≈ 0.34) |
| S purposes, BM25 top-16 → one pass | 65 / 69 / 66% | 76 / 69 / **74%** | 6 / 2 (p ≈ 0.29) |
| S, right when a right app is in the 16 | 80% | **90%** | |
| S, confidence ≥ 0.7: coverage / accuracy | 38% / 95% | 56% / 86% (needs T = 1.51) | |
| Routing (16 skills), seen / paraphrase | 82 / 81% | 88 / 75% | 2 / 1 |
| Live screens (25 steps): op / target agreement | — | 25/25 op, **4/5** CLICK targets | mean op TV 0.063 |

**Reading:**
- The pilot learned to match requests to profile text, and that carries over to unseen apps.
- It learned nothing about names it never saw.
- With no replay, it moved the screen decisions a little.
- The run is small, Claude-written and trained on purpose-style text, so the size of the gain is not
  trustworthy. Its effect on the automatic profiles (D/SD) wasn't measured before the weights were cleared.
  The box runs measure it.

**The yes/no head, zero-shot** (for the `done` question) [measured]:
- laya-android's noul head, never trained in its fine-tune, got 8 of 12 obvious statements right.
- On live screens it said "not done" on all 7 first steps (P ≤ 0.10).
- On the 2 screens where the run ended with DONE it said "done" on only 1 (0.80 and 0.40).
- So a completion check needs training. research/17's overshoot is the same weakness.

## 7. Recommended method and phases
**What runs on the phone, per request:**
1. Profiles are rebuilt on install or update (`AppProfiler`, extended per §2) and cached.
2. BM25 shortlists 16–32 apps, and the owner's remembered pick for the routed skill is added.
3. **One Laya pass asks `skill` and `app` together** (`system_one` batches both).
4. A per-question threshold decides: open the app if sure, else ask with the top 3 and remember the answer.
5. Inside the app, Laya's `operation`/`target` loop runs (D-036), with `done` checked on low-confidence steps.

**Where things run:**
- **GPU box (D-034):** all training, and the teacher LLM that writes requests, so the owner's app list stays
  on machines the owner controls.
- **Mac:** evaluation only, one small model process at a time.

| Phase | What | Gate / expected |
|---|---|---|
| **0 (now, no training)** | Automatic profiles + Laya: the tournament where latency allows (48%), else SD (44%); ask below the threshold; remember picks. Extend `AppProfiler`'s probes (§2). Run R1–R5 on the box. | D/SD on the eval. "Act" at ≥ 0.7 is 88–90% right today |
| **1 (box: ~1 day of data, ~3 h of training)** | Joint fine-tune: `app` + `done` + `skill` + AndroidControl replay; frozen embeddings; `encoder_lr` 1e-5. Data (research/14): a public app universe profiled by a port of `AppProfiler`, LLM-written requests, Teach demos. | Held-out-app SD ≥ 75% [UNCERTAIN]; the §3.5 forgetting gates; then ONNX export and parity (research/06) |
| **2 (box: minutes per retrain)** | Personal fine-tune on the owner's apps, Friday's own logs (request → app actually used) and Teach data. Re-fit per-question calibration. | Fresh owner-written eval (≥ 200), seen vs unseen apps |
| **3 (only if recall limits accuracy)** | A learned dual encoder for the shortlist, trained on the same pairs, fused with BM25 | recall@16 ≥ 95% on held-out apps |

**Expected gains:**
- **Measured so far:** +8 points from a 741-row pilot on purpose-style text, and the chooser in a shortlist
  80 → 90%.
- **Where the room is:** today's misses are mostly the chooser (22 of 26), which is what training on
  phone-format profiles addresses.
- **How much:** unknown until Phase 1 runs on the owner's own eval [UNCERTAIN].

## 8. Risks
1. **Sparse profiles.** 17 of 205 apps declare nothing beyond the label, and long-tail recall is 50%. An app
   that declares nothing can't be chosen from text: ask the owner and remember.
2. **Profiles at training must match the phone's.** The public universe must be profiled by a port of
   `AppProfiler`, with parity tests, and with fields dropped at random.
3. **Forgetting** the screen skill (seen even in the pilot). Mitigations: replay, frozen embeddings, a lower
   learning rate, and the §3.5 gates on every checkpoint.
4. **Calibration drift.** T went 1.16 → 1.51. Fix: per-question temperature and thresholds, re-fit every
   retrain, and never ship new weights with the old `LayaInput.kt` constants.
5. **Same-author bias.** Training data, eval requests and purpose texts all came from the same LLM. Fix: an
   owner-written eval set and a teacher from a different model family.
6. **APK and data sources.** Profiling public apps needs their APKs. AndroZoo's academic access is the clean
   route; mirrors' terms of service are [UNCERTAIN]. A cloud teacher would see the owner's app list, so use a
   local teacher on the box.
7. **Signal decay.** App Actions capabilities may disappear as Assistant is retired [secondary].
   - Dynamic shortcuts need Friday to be the default assistant, which costs the owner Gemini.
   - Android 17's Advanced Protection revokes non-tool accessibility services [secondary].
8. **Shared GPU box.** It hosts other projects: one job at a time, a free-VRAM check, and only our directory
   (D-034).
9. **Abstaining** ("no installed app does this") is a different skill from choosing. Test a "none of these"
   option.

## To run on the GPU box
The run-pack is ready and uses public data only; the box's port is pending (D-034). Everything lives in
`/root/friday-laya/finetune-method/`, with its own venv and its own `HF_HOME`: no global changes, and nothing
outside that directory is touched.

**1. Setup.** Copy the run-pack (`setup.sh`, `run_ablations.sh`, `train_rows.py`, `data/pilot_rows_{train,calib}.jsonl`)
and run `bash setup.sh`. It does:
- read-only recon: `nvidia-smi` including other processes, `df`, `free`;
- a venv with `laya==0.3.28` + `transformers==5.20.0`;
- a CUDA fp16 sanity check (Blackwell needs a CUDA 12.8+ torch build; the script says how to fix it inside
  the venv);
- the pinned laya-android download, sha256-checked (D-006).

**2. Ablations.** `tmux new -d -s friday-ft "bash run_ablations.sh"`:
- It refuses to start with less than 12 GB of free VRAM.
- It runs one job at a time and skips finished runs.
- Each run is `train_rows.py` on the pilot rows and writes a laya-loadable checkpoint (644 MB), with the
  measured time and peak VRAM in `meta.json`.

| Run | Config | Question it answers | Expected (5090) |
|---|---|---|---|
| R1-emb-s0/s1/s2 | `--freeze embeddings --encoder-lr 1e-5 --head-lr 5e-5 --epochs 2`, seeds 0–2 | does the Mac pilot reproduce on CUDA, and how big is seed noise? | ~1 min, ≤ 5 GB each |
| R2-head | `--freeze encoder --head-lr 1e-4 --epochs 2` | is the head alone enough? (the stopped Mac run) | < 1 min, ≤ 3 GB |
| R3-emb-hilr | `--freeze embeddings --encoder-lr 2.5e-5 --head-lr 1e-4` | laya-android's learning rates: more gain, more drift? | ~1 min |
| R4-full | `--freeze none --encoder-lr 1e-5 --head-lr 5e-5` | does freezing the embeddings cost accuracy? | ~1–2 min, ≤ 8 GB |
| R5-emb-4ep | `--freeze embeddings … --epochs 4` | more epochs | ~2 min |

Expected times are estimates from the item length (median 230 tokens) and laya-android's 4090 throughput.

**3. Evaluation on the Mac** (one model process at a time, when `pgrep -fl "pilot_train|eval_apps|llama-"`
shows nothing):
1. Copy back one run directory.
2. `pilot_eval.py <run_dir> <name>`. It scores A/B/S and **D/SD on the automatic profiles**, routing, and drift
   on the live screens.
3. `pilot_eval.py compare base <name>`.
4. Delete the run's weights before copying the next one.

Alternative: the owner may allow evaluation on the box (D-034 permits personal data there). That needs
`captures/{apps,app_purposes,app_profiles_auto}.json` and `run-*.jsonl` copied there, with `LAYA_REPO` pointing
at a repo copy, and saves the 644 MB transfers.

**4. Phase 1 run** (after the research/14 data exists):
- Command: `train_rows.py --freeze embeddings --encoder-lr 1e-5 --head-lr 5e-5 --epochs 2 --micro-batch 8 --grad-accum 4`
- Input: the mixed JSONL (app + AndroidControl replay + done + skill), with calibration rows held out by
  request.
- AndroidControl rows: built with laya-android's `scripts/build_androidcontrol_dataset.py` [primary per
  research/01] from the public release, downloaded on the box.
- Expected: ~2–3 h, ≤ 12 GB [UNCERTAIN].

## Reproduce (scratch harness, not in the repo)
- The scripts and public pilot rows live in the research session's scratchpad:
  - `gpu_runpack/`;
  - `e1_shortlist.py` (shortlist recall);
  - `e3_mps_train_bench.py` (MPS training cost);
  - `e4_noul_probe.py` (yes/no head);
  - `e5_auto_eval.py` (automatic profiles);
  - `pilot_{data,train,eval}.py`.
- Move them into `scripts/` if we keep them.
- Owner data never left the Mac, and per-case outputs with app names stay in the scratchpad.

## Dead ends
- **Laya's own mean-pooled embeddings as the shortlist** (`embed_fn_from_agent`): recall@16 36–48%, below
  BM25 everywhere.
- **Fine-tuning to teach unseen app names:** names-only stayed at 36%. An unseen app needs profile text.
- **Training on the 16 GB Mac:** the code works, but concurrent model jobs pushed it into swap.
- **Scoring every app yes/no on the phone:** ~200 passes per request.
- **Adapters or LoRA:** not in laya's trainer, and the ONNX path wants one graph.
- **Reading another app's manifest with `openXmlResourceParser("AndroidManifest.xml")`:** unreliable on split
  APKs [primary]. Parse `sourceDir` instead.

## Open questions
1. **Box ablations (R1–R5):** head-only vs frozen embeddings vs full fine-tune, and seed noise.
2. **The pilot on the automatic profiles:** its effect on D/SD was not measured.
3. **Device coverage:** what each extra §2 signal adds on the Pixel 8 — category set, domains, Health Connect
   permissions, widgets, and whether reading `sourceDir` passes SELinux.
4. **Shortlist size:** k = 16 / 24 / 32 against Laya's accuracy at larger option counts.
5. **"None of these" and `done`:** whether they train well into the same head.

## Sources (beyond the repo's own code and notes)
**Apps and agents:**
- AutoDroid https://arxiv.org/abs/2308.15272 · AutoDroid-V2 https://arxiv.org/abs/2412.18116
- AppAgent https://arxiv.org/abs/2312.13771 · AppAgent v2 https://arxiv.org/abs/2408.11824 · AppAgentX https://arxiv.org/abs/2503.02268
- MobileGPT https://arxiv.org/abs/2312.03003 · DroidAgent https://arxiv.org/abs/2311.08649
- Mobile-Agent-v2 https://arxiv.org/abs/2406.01014 · Mobile-Agent-E https://arxiv.org/abs/2501.11733
- Mobile-Agent-v3 https://arxiv.org/abs/2508.15144 · v3.5 https://arxiv.org/abs/2602.16855
- LearnAct https://arxiv.org/abs/2504.13805

**App and tool selection:**
- MobileRAG https://arxiv.org/abs/2509.03891 · AppSelectBench https://arxiv.org/abs/2511.19957 · MobileSteward https://arxiv.org/abs/2502.16796
- Alexa shortlister https://arxiv.org/abs/1804.08064 and https://arxiv.org/abs/1804.08065
- Octopus v2 https://arxiv.org/abs/2404.01744 · DroidCall https://arxiv.org/abs/2412.00402 · TinyAgent https://arxiv.org/abs/2409.00608
- Tool2Vec https://arxiv.org/abs/2409.02141 · ToolLLM https://arxiv.org/abs/2307.16789 · Gorilla https://arxiv.org/abs/2305.15334
- On-device action routing (2026) https://arxiv.org/abs/2609.18672 · zero-shot entity linking https://arxiv.org/abs/1906.07348

**Forgetting:**
- CoNDA https://arxiv.org/abs/1905.00921 · sparse replay https://arxiv.org/abs/1906.01076
- replay ratios https://arxiv.org/abs/2403.08763 and https://arxiv.org/abs/2501.13453 · mmBERT https://arxiv.org/abs/2509.06888

**Android:**
- `ApplicationInfo`, `PackageItemInfo.loadXmlMetaData`, `LauncherApps`, `Intent`, `AppWidgetManager`,
  `UsageStatsManager`, `AppFunctionManager` reference pages (https://developer.android.com/reference/)
- Package visibility https://developer.android.com/training/package-visibility/declaring
- Common intents https://developer.android.com/guide/components/intents-common
- Health Connect https://developer.android.com/health-and-fitness/guides/health-connect/develop/get-started
- AOSP `DomainVerificationEnforcer.java`, `VoiceInteractionManagerService.java`, `AssetManager2.cpp`,
  `PackageManagerService.java` (frameworks/base, main)
- AppFunctions announcement https://android-developers.googleblog.com/2026/02/the-intelligent-os-making-ai-agents.html
- Assistant retirement [secondary] https://the-decoder.com/google-will-shut-down-google-assistant-starting-september-2026-as-gemini-takes-over-on-android-and-wear-os/
