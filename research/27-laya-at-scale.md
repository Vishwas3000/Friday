# 27 — Laya-Android at scale: Friday's input changes and the shortlist on AndroidControl

_2026-10-19 · Runs on the rented RTX 4090 (Vast), never on the Mac (D-042) · Code: `training/laya-scale/` · Trust tags:
[primary] read at source · [measured] run here · [derived] computed from measured numbers · [UNCERTAIN] not yet known_

## TL;DR
- **The baseline reproduces.** On AndroidControl's test split, Laya-Android here scores AndroidControl-High Type
  **76.75** / Grounding **61.84**, against the published 76.7 / 61.7. Step by step, 99.7% of operations and 99.4% of
  targets match the repo's saved predictions [measured].
- **Friday's input format is not the problem; it is a small net gain** (9,987 held-out steps, paired) [measured]:
  - all four changes together: target **+1.2** points (65.4 → 66.6, p 0.0004) and Grounding **+0.8** (61.9 → 62.7),
    but operation **−0.4** (74.7 → 74.3, p 0.02: more taps where Laya should stop or wait); joint 61.0 → 61.2
    (197 worse, 212 better, p 0.49: no change);
  - **idLabel** is the change that pays: +1.1 target, +0.9 Grounding (both p < 0.001);
  - **icon names** help alone (+0.7 target, p 0.006) but add nothing measurable on top of idLabel here;
  - **system windows out** is neutral alone (it loses 14 gold taps on notifications), but it is what keeps loose text
    safe: loose text without it pulls in the status bar's notification icons and costs 1.8 points of target and 1.6
    of joint;
  - **loose text** fires on 1.2% of steps as shipped; its effect there is too small to call. AndroidControl's apps
    rarely need it [UNCERTAIN on the owner's Compose / React Native apps].
- **The target shortlist costs accuracy at every k** [measured]:
  - at k = 16 (shipped) it hides the gold element on 17% of crowded screens (recall 83%), and target accuracy there
    falls **57.6 → 52.6** (166 worse, 67 better). Over all taps that is −2.1 points, more than the format gains:
    **Friday as shipped (format + shortlist) has joint 60.35, against 61.02 for the trained format with every
    element** (321 worse, 256 better, p 0.008);
  - bigger k costs less: −3.1 points on the 17% of taps it touches at k = 24 (p 0.005), −2.2 (p 0.11, not
    significant) on 8% at k = 32;
  - it saves 22% of a tap step's tokens on those screens at k = 16, 19% at 24, 16% at 32;
  - **fewer options don't make Laya choose better:** when the gold is shown, it is right 63.5% of the time with every
    element and 63.4% with the shortlist (k = 16). All the loss is golds left out, so the shortlist buys speed only;
  - **no obviously better cheap rule:** the best tried (prefix word matches, tappable elements first) adds 1 point of
    recall and no accuracy; dropping words already tapped makes it worse.
- **Cutting the operation row to the shortlist also costs accuracy:** at k = 16 the operation is right on 75.1 → 74.0%
  of the 38% of steps it touches (145 worse, 101 better, p 0.006), mostly taps turned into scrolls or DONE. It saves
  24% of that row's tokens [measured].
- **Recommendations (§6):** keep idLabel, icon names and system windows out; keep loose text only together with
  system windows out; raise the target shortlist's k from 16 to 32 (24 if speed on crowded screens matters more);
  don't cut the operation row. A fine-tune on Friday's format (§7) should win back the stopping loss.

## 1. The goal
The owner says Friday "doesn't function properly". Everything we know about Laya on Friday's input comes from 52
recorded (screen, goal) pairs (research/18). This note measures two things on thousands of held-out AndroidControl
steps instead:
1. **Do Friday's own changes to Laya's input help or hurt?** Friday renders the candidate list differently from the
   format Laya-Android was trained on (system windows dropped, resource-id words, loose text, icon names).
2. **How good is Friday's element shortlist?** On screens with more than 16 elements, the target question shows only
   16 (D-049, research/18 §9).

## 2. Tasks
| # | Task | Where | Status |
|---|---|---|---|
| T0 | This plan, before any long run | Mac | done |
| T1 | Box setup: laya-android @ `ab216d4`, laya @ `4ac226d` (read), `laya==0.3.28` + transformers 4.57.6 + torch 2.14.1 cu126 in `/root/friday-laya/laya/.venv` (Python 3.11); model `ByunByun/laya-android` @ `5a03b2b` (sha256 checked) | box | done |
| T2 | L0 data: stream the 20 AndroidControl shards, run the repo's builder, keep test forests + icon names; hand-off files for the student agent | box | done (4.5 min; READY 08:46) |
| T3 | L1 baseline: the repo's own eval on test; must give AC-High Type 76.7 / Grounding 61.7 within ~1 point | box | done: 76.75 / 61.84 |
| T4 | L2 Friday's format: (a) system windows, (b) idLabel, (c) loose text, (d) icon names; one at a time, then all (and each left out of all) | box | done |
| T5 | L3 the shortlist: exact Python port with its self-check; recall@k, Laya's target accuracy, tokens; cheaper rules; the operation row cut to it (added by the coordinator) | box | done |
| T6 | Results and recommendations (this note) | Mac | done |

## 3. Decisions and why
### 3.1 Dataset and splits
- **AndroidControl** (Apache-2.0), the official `splits.json` and `test_subsplits.json` from `gs://gresearch/android_control`
  [primary]. Test: 1,543 episodes, 8,444 actions + one synthetic DONE per episode = 9,987 steps. Train 13,594 episodes /
  88,308 steps (9 empty episodes skipped, as the repo does). Validation 137 / 827.
- **Why the test split:** Laya-Android never saw it (it trained on train, picked its epoch and temperature on
  validation). The four test subsplits (IDD, app-, task-, category-unseen) overlap; every number here counts each
  test step once (the repo's "all"), except where L1 compares per-subsplit numbers with the published ones.
- **Streaming:** the shards (~50 GB) do not fit the disk. Each is read over HTTPS straight into the parser; nothing
  raw is stored. A dropped stream raises and the shard restarts.
- **What is kept:** for every split, the repo builder's step items (goal, history, candidates, gold op and target).
  For test steps also the whole accessibility forest (to build every Friday variant later without the shards) and,
  for every unnamed icon-shaped candidate, the icon classifier's top class and confidence.
- **Hand-off for the student agent:** `data/ac_{train,val,test}.jsonl`, each line the repo's step item plus the
  `{state, questions, expected}` row its training fed to `laya.train` (`serialization.row`), then `data/READY`.

### 3.2 Metrics (the repo's own definitions, `scripts/eval_laya_android.py`, `docs/eval_protocol.md`)
- **Operation accuracy:** argmax of the operation question = gold op, over all steps including DONE.
- **Target accuracy:** argmax of the target question = the gold candidate, over groundable CLICK / LONG_PRESS steps.
  The target question is asked whatever op the model picks (teacher-forced).
- **Joint:** op right and, on a click step, target right. Click steps whose gold tap hits no candidate are excluded
  (reported as coverage).
- **Buckets:** by the step's candidate count in the trained format (1–5, 6–10, 11–20, 21–50, 51+), so a bucket holds
  the same steps in every variant.
- **AndroidControl-High Type / Grounding:** the InfiGUI-R1 judge, unmodified, as `scripts/evaluate_androidcontrol.py`
  calls it (tap = centre of the chosen candidate's box; hit inside the gold box ×1.2 or within 4% of the screen).
  This one needs no candidate index, so it compares variants whose lists differ.
- **Comparisons are paired:** the same steps, with the steps that turn right → wrong and wrong → right counted, and a
  two-sided sign test on them.
- **Inference:** the repo's batching (length-sorted, batch 32, bf16 autocast, the checkpoint's temperature 1.161).

### 3.3 The variants: exactly what each one changes
All are built by Friday's Python builder (`scripts/laya_android.py build_candidates`, the reference the phone's
`Candidates.kt` matches), with switches added for this test (defaults = the phone's behaviour).
| Variant | Change, and only this | Where on the phone |
|---|---|---|
| L1 | none: the repo's `src/candidates.py`, the format Laya-Android was trained on | — |
| F0 | Friday's builder with every change off. **Must equal L1 on every step**; any difference is a hidden change | — |
| (a) system | candidates in TYPE_SYSTEM windows (status bar, nav bar, shade) are dropped, and their loose text | `buildCandidates` |
| (b) idLabel | an element with no text of its own or its children's gets words from its resource id (`tab_menu_stopwatch` → "tab menu stopwatch"); ids and order don't move | `idLabel` |
| (c) loose text | when non-actionable texts (3+ letters, outside every actionable box) are at least twice the actionable elements, the first 30 join, top to bottom, as `Label (TextView)` | `buildCandidates` |
| (d) icons | an unnamed candidate shaped like an icon (both sides ≤ ¼ of the screen's short side, aspect ≤ 2) gets the shipped classifier's word when its top class isn't "other" and p ≥ 0.90 | `IconNamer` (crop as `IconNamer.crop`) |
| all | a + b + c, then d on what is still unnamed: Friday as shipped | — |

- **History** is rebuilt from each variant's own labels (`CLICK <label>`), as Friday writes it.
- **The gold target** is found in each variant's list by the repo's grounding rule: the smallest candidate box
  containing the gold tap, then the deepest, then the lowest index.
- **How often each change fires** is counted per step and per gold target.
- Not tested: Meanings (learned on the phone), hints and remembered paths (no memory offline).

### 3.4 The shortlist (L3)
- **The port:** `shortlist()` and `Routes.contentWords` / `aboutWords`, line for line, with `shortlistSelfCheck()`'s
  cases as asserts.
- **Its words offline:** `aboutWords(goal, app)`, where app is the episode's OPEN_APP name when it has one. Friday also
  adds a remembered hint's labels and the words it will type; neither exists offline, so this is the shortlist
  without memory.
- **On Friday's shipped format** (variant "all"), steps with more than k candidates, k = 8, 12, 16, 24, 32:
  - gold recall@k (is the gold candidate in the shortlist?);
  - Laya's target accuracy with the shortlist vs the full list, by bucket. The target row then lists only the
    shortlisted elements, re-indexed, as `LayaEngine.step` sends them; a gold outside the shortlist counts as wrong;
  - tokens of the target row, full vs shortlist.
- **Cheaper alternatives** are picked on 5,000 train clicks (the rule learns nothing from the model, so train is fair
  for choosing it) and then reported on test. Report only: the app is not changed.
- **The operation row cut to the same shortlist** (added by the coordinator, 2026-10-19): `LayaEngine.step` can show
  the operation question only the shortlisted elements (`opShown`). On every test step with more than k elements,
  k = 16, 24, 32: operation accuracy and joint against the full list, by bucket, and the row's tokens.

## 4. Experiments
| Exp | What | Gate / output |
|---|---|---|
| E1 (L1) | the repo's eval scripts, unchanged, on the four test subsplits; then its AC-High scorer | Type 76.7 / Grounding 61.7 within ~1 point, and step-level agreement with the repo's saved test predictions (`results/test/raw`). If not: stop and find out why |
| E2 (L2) | F0 parity, then a, b, c, d, all on the 9,987 test steps | op, target, joint (all and by bucket), Type / Grounding, flips, how often each change fires |
| E3 (L3) | recall@k, Laya with the shortlist vs the full list, tokens; alternative rules; the operation row cut to the shortlist (k = 16, 24, 32) | the best k; whether a better cheap rule exists; whether the op row can be cut |

## 5. Results log
### L0 — data [measured, 2026-10-19]
- **Streaming worked:** 20 shards, 10 at a time, ~230 MB/s; the whole pass took 4.5 minutes and left nothing raw
  on disk. Every shard read to the end of its gzip stream.
- **Counts match the repo exactly**, for every split: episodes, steps, action types, click steps, groundable clicks,
  unlabelled and scroll-only gold targets, mean and maximum candidates, maximum nodes
  (`results/data/androidcontrol_stats.json`). Train 13,594 episodes / 88,308 steps; validation 137 / 827; test
  1,543 / 9,987 (8,444 actions + 1,543 DONE); subsplits 4,618 / 4,106 / 5,267 / 4,591 steps.
- **Hand-off:** `/root/friday-laya/laya/data/ac_{train,val,test}.jsonl` + `FORMAT.md`; `READY` touched at 08:46 (box
  time). Every line's `{state, questions, expected}` equals `serialization.row()` of its item (checked on val + test).
- **Disk:** 2.1 GB of processed data (test forests 1 GB of it), plus 0.7 GB of hand-off files.

### L1 — the published numbers reproduce [measured, RTX 4090, laya 0.3.28, torch 2.14.1 cu126]
The repo's `eval_laya_android.py` and `evaluate_androidcontrol.py`, unchanged, on our data:

| AndroidControl-High | Type, published → ours | Grounding, published → ours | Policy joint, published → ours |
|---|---|---|---|
| all (8,444 steps) | 76.66 → **76.75** | 61.71 → **61.84** | — |
| IDD | 81.40 → 81.47 | 67.69 → 67.77 | 0.659 → 0.660 |
| app-unseen | 70.76 → 70.88 | 53.16 → 53.31 | 0.540 → 0.541 |
| task-unseen | 72.76 → 72.87 | 56.48 → 56.68 | 0.567 → 0.568 |
| category-unseen | 71.45 → 71.58 | 54.93 → 55.15 | 0.545 → 0.547 |

- **Step by step** against the repo's saved test predictions: the same operation on 99.7% of 18,582 subsplit steps,
  the same target on 99.4% of 8,710 target rows; the largest probability difference is 0.06. What remains is bf16 on a
  different CUDA build (cu126 here, cu130 there).
- **On the 9,987 test steps counted once** (the baseline for everything below): operation 74.72%, target 65.41% (4,756
  groundable clicks), joint 60.99%, click coverage 93.57%.

### L2 — Friday's format [measured]
**Parity first.** Friday's Python builder with every change off (F0) gives the trained format on **all 9,987 steps**:
the same option strings, history and gold. So nothing hidden differs; only the four changes do.

**Paired comparison method.** A step whose rendered input is unchanged keeps the L1 prediction; only changed steps
are run. So "worse / better" counts only steps where the input moved. Batching alone barely matters: the same model
run over the subsplit files and over the merged file disagrees on 6 of 18,582 operations and 5 of 8,710 targets
(max |Δp| 0.04), so reusing predictions mainly saves GPU time.

**Each change alone, against the trained format** (9,987 steps; target and Grounding on gold clicks):
| Variant | Steps it changes | Operation | Target (paired, n ≈ 4,750) | Joint | Type | Grounding (paired) |
|---|---|---|---|---|---|---|
| L1 trained format | — | 74.72 | 65.41 | 60.99 | 76.75 | 61.86 |
| (a) system windows out | 178 (1.8%) | 74.64 (13 worse / 5 better) | 65.54 (2 / 7) | 61.05 | 76.68 | 61.69 (13 / 4, p 0.05) |
| **(b) idLabel** | 4,070 (40.8%) | 74.52 (121 / 101, p 0.20) | **66.48 (74 / 125, p 0.0004)** | 61.23 | 76.61 | **62.79 (66 / 113, p 0.0005)** |
| (c) loose text | 1,663 (16.6%) | **73.97 (177 / 102, p < 1e-5)** | **63.62 (103 / 18)** | **59.37** | 75.86 | **60.54 (100 / 33)** |
| (d) icon names | 3,458 (34.6%) | 74.81 (80 / 89, p 0.54) | **66.13 (55 / 89, p 0.006)** | 61.47 | 76.72 | 62.04 (58 / 67, p 0.47) |
| **all (as shipped)** | 5,102 (51.1%) | **74.30 (165 / 123, p 0.016)** | **66.60 (89 / 144, p 0.0004)** | 61.14 | 76.50 | **62.69 (91 / 133, p 0.006)** |

**Each change on top of the other three** (leave one out, then add it back = "all"):
| Adding | Steps it changes | Operation worse / better | Target worse / better | Target where it fired | Grounding worse / better |
|---|---|---|---|---|---|
| (a) system windows out | 1,790 (17.9%) | 101 / **163** (p 0.0002) | 23 / **106** (p < 1e-6) | 61.7 → **72.4** (n 772) | 36 / **98** |
| (b) idLabel | 3,952 (39.6%) | **117** / 73 (p 0.002) | 79 / 100 (p 0.14) | 60.4 → 61.2 (n 1,955) | 58 / **98** (p 0.002) |
| (c) loose text | 125 (1.2%) | 18 / 12 (p 0.36) | 7 / 1 (p 0.07) | 92 → 64 (n 25) | 4 / 10 (p 0.18) |
| (d) icon names | 1,197 (12.0%) | 21 / 17 (p 0.63) | 13 / 18 (p 0.47) | 59.2 → 60.0 (n 500) | 11 / 15 (p 0.56) |

**How often each change fires, and on what** (alone, against F0):
- **(a)** removes 416 candidates on 178 steps, such as the nav bar's Back / Home and the open notification shade's
  toggles. **14 gold taps were on system-window elements** and become impossible.
- **(b)** relabels 15,204 unlabelled candidates on 4,070 steps; the gold target's own text changes on 362 steps.
  Examples: `- (RecyclerView) scroll` → `books (RecyclerView) scroll`, `- (ImageView) long` → `title (ImageView) long`,
  but also `peoplekit autocomplete chip (ViewGroup)` and `composearea tap trap bottom (View)`.
- **(c) alone adds 35,287 candidates on 1,663 steps; 97% of them come from system windows.** The status bar's
  notification icons carry content descriptions ("JioMart notification:", "Clock notification: Missed alarm"), so
  they count as loose text, and on a sparse screen they reach twice the tappable elements. **On the phone (a) removes
  them first**: with (a) on, (c) fires on only 125 steps (1.2%) and adds 1,046 candidates. There it makes 19 gold taps
  reachable that weren't.
- **(d)** the classifier sees 16,073 unnamed, icon-shaped candidates (counted per step) and names 52.8% of them at
  0.90 (13% are "other"); most often back, close, favorite, more options, share, bookmark, add, check, search. On top of
  idLabel it still names 2,186 on 1,197 steps; the gold changes on 205 steps alone.

**By candidate count** (trained-format count; operation / target):
| Variant | 0 (97 steps) | 1–5 (1,344 / 547 clicks) | 6–10 (1,943 / 860) | 11–20 (4,095 / 2,025) | 21–50 (2,385 / 1,228) | 51+ (123 / 96) |
|---|---|---|---|---|---|---|
| L1 | 77.3 / — | 69.7 / 84.1 | 70.5 / 67.6 | 77.3 / 66.5 | 75.9 / 54.6 | 83.7 / 54.2 |
| (a) | 77.3 / — | 69.6 / 84.5 | 70.4 / 67.6 | 77.3 / 66.8 | 75.9 / 54.6 | 83.7 / 54.2 |
| (b) | 77.3 / — | 69.2 / 84.1 | 70.5 / 69.3 | 77.1 / 68.0 | 75.9 / 55.5 | 80.5 / 50.0 |
| (c) | **50.5** / — | 67.5 / **75.5** | 69.7 / 65.4 | 77.2 / 65.6 | 76.1 / 54.5 | 83.7 / 54.2 |
| (d) | 77.3 / — | 70.0 / 84.1 | 71.0 / 69.2 | 77.5 / 67.2 | 75.6 / 55.3 | 82.9 / 53.1 |
| all | 75.3 / — | 68.7 / 84.2 | 70.5 / 69.4 | 76.9 / 68.2 | 75.7 / 55.5 | 80.5 / 49.0 |

- The target gains sit in 6–50 elements (+1–2 points). The 51+ bucket loses 5 points, but that is 5 of its 96
  clicks.
- (c) alone wrecks the sparse screens: on the 97 screens with no tappable element, operation accuracy falls from 77%
  to 51% once the status bar's texts join.

**What the operation loses** ("all" vs L1, 165 worse / 123 better): mostly stopping and waiting. DONE → CLICK 39
worse vs 23 better, WAIT → CLICK 19 vs 6, CLICK → DONE 17 vs 13. With more elements named, Laya taps more where it
should stop: the weak DONE of research/01 §2.6, made a little worse. idLabel alone shows the same pattern
(DONE → CLICK 30 vs 22).

### L3 — the shortlist [measured]
**The port.** `scripts/laya_android.py` now has `content_words`, `about_words` and `shortlist`, line for line from
Routes.kt and Candidates.kt, with `shortlistSelfCheck()`'s cases as asserts (they pass). Its words offline:
`aboutWords(goal, app)`, app = the episode's OPEN_APP name; no hint, no typed words (§3.4).

**Gold recall@k** on taps whose screen has more than k elements (train: 5,000 clicks in the trained format; test:
Friday's shipped format):
| Rule | @8 train / test | @12 | @16 | @24 | @32 |
|---|---|---|---|---|---|
| **shipped** | 68.2 / **71.8** | 77.2 / **80.5** | 80.8 / **83.0** | 85.1 / **87.6** | 89.9 / **90.8** |
| fuzzy (shared 4+ letter prefix) | 68.9 / 71.8 | 77.2 / 80.6 | 81.1 / 83.2 | 84.8 / 87.6 | 89.7 / 90.8 |
| tappable first (the op is a tap) | 69.4 / 72.5 | 77.6 / 81.3 | 81.6 / 83.8 | 85.8 / 88.3 | 90.5 / 91.3 |
| minus words already tapped (history) | 69.3 / 72.6 | 77.0 / 80.0 | 80.7 / 82.2 | 85.0 / 87.5 | 89.6 / 90.5 |
| fuzzy + tappable first | 69.8 / 72.4 | 77.5 / 81.3 | 81.8 / 84.0 | 85.4 / 88.3 | 90.1 / 91.3 |
| screens with more than k (train / test) | 3,904 / 3,710 | 3,016 / 2,804 | 2,191 / 1,969 | 1,118 / 807 | 546 / 401 |

- **Why recall stalls:** on crowded train screens only 51% of gold targets share a word with the goal, although 91%
  of screens have some element that does (6.3 on average). The other half must come from the fill, and gold
  targets sit evenly down the screen (26.7 / 23.0 / 22.8 / 27.4% by quarter), so top-and-bottom catches only the
  edges. 96.7% of gold targets are tappable, 3.3% editable, 10.6% unlabelled [measured, ad hoc on train].

**Laya's target accuracy, shortlist vs every element** (Friday's shipped format; a gold outside the shortlist counts
wrong):
| k | taps on screens > k | recall | target, all elements → shortlist | worse / better (p) | target row tokens | tap-step tokens saved |
|---|---|---|---|---|---|---|
| 8 | 3,710 (78% of taps) | 71.8 | 63.1 → **51.1** | 602 / 157 (< 1e-9) | 538 → 256 | 30.2% |
| 12 | 2,804 (59%) | 80.5 | 60.9 → **54.5** | 287 / 108 (< 1e-9) | 613 → 348 | 25.3% |
| **16 (shipped)** | 1,969 (41%) | 83.0 | 57.6 → **52.6** | 166 / 67 (< 1e-9) | 702 → 444 | 21.9% |
| 24 | 807 (17%) | 87.6 | 53.9 → 50.8 | 49 / 24 (0.005) | 946 → 650 | 19.2% |
| 32 | 401 (8%) | 90.8 | 56.6 → 54.4 | 17 / 8 (0.11) | 1,156 → 853 | 16.3% |
| 16, fuzzy + tappable first | 1,969 | 84.0 | 57.6 → 52.1 | 177 / 67 | 702 → 436 | 22.6% |

By the screen's element count (Friday's format), all elements → shortlist (recall):
| k | 6–10 | 11–20 | 21–50 | 51+ |
|---|---|---|---|---|
| 8 | 65.2 → 62.1 (92%) | 68.2 → 55.7 (76%) | 55.1 → 42.2 (61%) | 49.0 → 26.0 (43%) |
| 12 | — | 66.5 → 62.2 (89%) | 55.1 → 46.8 (72%) | 49.0 → 35.4 (56%) |
| 16 | — | 63.9 → 61.6 (93%) | 55.1 → 49.3 (79%) | 49.0 → 36.5 (65%) |
| 24 | — | — | 54.6 → 52.3 (90%) | 49.0 → 39.6 (71%) |
| 32 | — | — | 59.0 → 57.0 (94%) | 49.0 → 45.8 (79%) |

- **Fewer options don't help Laya choose.** On the taps whose gold is in the shortlist, Laya is right as often with
  the shortlist as with every element: 70.7 → 71.1% at k = 8, 68.0 → 67.7 at 12, **63.5 → 63.4 at 16**, 58.1 → 58.0
  at 24, 59.6 → 59.9 at 32 [measured, cached predictions]. **The whole loss is golds left out.** research/01 §2.6's
  "0.84 from 1–5 elements, 0.54 from 21–50" describes harder screens, not too many options; so the shortlist buys
  speed only, never accuracy (the premise of D-049 doesn't hold).
- **Over all 4,761 taps** the target falls 66.6 → 57.2 / 62.8 / **64.5** / 66.1 / 66.4 at k = 8 / 12 / **16** / 24 /
  32 [derived]. At 16 that is below the trained format's 65.4: the shortlist takes back more than the format gave.
- **Friday as shipped against the trained format, joint on the same 9,646 steps:** 61.02 → **60.35** (321 worse,
  256 better, p 0.008). The format alone: 61.02 → 61.18 (197 / 212, p 0.49) [measured].
- **The 52-screen phone test (research/18 §9) could not see this:** 34 taps can't show a 5-point loss on 41% of taps.
- **A rule with better recall is not better for Laya:** fuzzy + tappable first lifts recall@16 by 1 point but lowers
  accuracy by 0.6 (52.1 vs 52.6): the golds it adds are ones Laya gets wrong anyway. Nothing cheap here beats raising k.

**The operation row cut to the same shortlist** (the coordinator's question; every step with more than k elements):
| k | steps cut | operation on them, all → shortlist | worse / better (p) | op row tokens | joint over all steps: full / op cut / target cut (as shipped at this k) / both cut |
|---|---|---|---|---|---|
| 16 | 3,756 (38%) | 75.1 → **74.0** | 145 / 101 (0.006) | 470 → 355 (−24%) | 61.14 / 60.94 / **60.32** / 60.24 |
| 24 | 1,493 (15%) | 78.2 → 76.4 | 53 / 27 (0.005) | 577 → 454 (−21%) | 61.14 / 61.01 / 60.89 / 60.73 |
| 32 | 657 (7%) | 81.7 → 79.5 | 22 / 7 (0.008) | 676 → 551 (−19%) | 61.14 / 61.07 / 61.06 / 60.98 |

- **What breaks:** gold taps turned into SCROLL_DOWN (30 at k = 16) or DONE (21), and DONE / SCROLL_DOWN turned into taps
  (17, 14): with elements hidden, Laya more often decides the target isn't on screen.
- **By bucket at k = 16:** 17–20 elements no change (22 worse / 23 better); 21–50 elements 76.0 → 74.2 (116 / 73,
  p 0.002); 51+ 80.5 → 78.9 (7 / 5).
- **Tokens:** AndroidControl's crowded screens are lighter than the owner's: the op row averages 470 tokens on its
  17+-element screens, against the 1,300–1,600 the coordinator reports on the phone. The cut's share (19–24%) should
  carry over; the seconds saved will be larger on the phone [UNCERTAIN].

## 6. Recommendations
| # | What | Keep / change | Evidence |
|---|---|---|---|
| 1 | (b) idLabel | **keep** | +1.1 target, +0.9 Grounding, p < 0.001; costs 0.2–0.45 of operation (stopping) [measured] |
| 2 | (d) icon names | **keep** (cheap, harmless) | +0.7 target alone (p 0.006); nothing measurable on top of idLabel, where AndroidControl's ids already name most icons [measured]. Whether it matters more on the owner's apps is open; research/26 E4 found few unnamed icons there [UNCERTAIN] |
| 3 | (a) system windows out | **keep** | neutral alone, but required by (c): without it, loose text costs 1.8 target / 1.6 joint [measured]. Cost: a task that must tap a system-window element (the shade, the nav bar) can't: 14 of 5,074 gold taps here |
| 4 | (c) loose text | **keep only with (a)**; judge it on the owner's apps | fires on 1.2% of AndroidControl steps as shipped; 7 worse / 1 better targets, 4 worse / 10 better Grounding: too few to call [measured]. Built for Compose / React Native apps that AndroidControl hardly has [UNCERTAIN] |
| 5 | Target shortlist k | **16 → 32** (24 if speed on 25–32-element screens matters more) | it never raises accuracy (same when the gold is shown); k = 16 costs 5.0 target points on 41% of taps (−2.1 overall); k = 32 costs 2.2 on 8% (−0.2 overall, not significant) and still trims the heaviest steps by 16% [measured]. Speed on the phone at 32 is not measured |
| 6 | Shortlist rule | **leave as is** | none of four cheap variants helps Laya [measured] |
| 7 | Operation row | **don't cut it** to the shortlist | −1.2 to −2.3 operation points on the steps it touches, all significant [measured]. Make the op pass cheaper with a smaller model (research/28) instead |
| 8 | The DONE / WAIT loss | fine-tune on Friday's format (§7) | the format makes Laya tap where it should stop (DONE → CLICK 39 vs 23) [measured] |

**What this says about "doesn't function properly":** the format and the shortlist move single-step accuracy by
about ±1–2 points. Laya-Android itself gets about 61% of held-out AndroidControl steps fully right (joint), and
over a 5-step task that compounds (research/01 §4). The input pipeline is not where most of the failures come from.

## 7. What a fine-tune on Friday's format would need
1. **Friday-format rows for train and validation:** re-run L0 with the Friday builder switched on (a + b + c + d).
   The stream takes ~5 minutes. Icon names need the train screenshots' crops: about 150k crops [derived from 16k
   per 10k test steps], roughly 10–15 more minutes on 10 workers [estimate]. `ac_data.py` keeps forests only for
   test today; it needs a flag to keep them (or the rendered rows) for every split (~9 GB of forests for train
   [derived]).
2. **The same recipe as Laya-Android** (`train_laya_android.py`: 3 epochs, soft-CE, shuffled options, bf16; 2.7 h on
   a free 4090) starting from Laya-Android, or research/22's lighter one. Mixing in trained-format rows keeps the
   old format working.
3. **Refit the temperature on validation in the new format** (the shipped 1.161 is fitted on the old one).
4. **Gates:** on test in Friday's format, beat "all" here (joint 61.14, operation 74.30, target 66.58), especially
   DONE and WAIT recall; on test in the trained format, stay within 1 point of L1. Then the phone's task suite.
5. **If the target shortlist stays,** train target rows on shortlists too, so Laya learns the shortlist's shape. It
   can't fix recall: a missing gold stays missing.

## 8. Dead ends and caveats
- **Full reruns for every variant.** The first L2 pass reran all 9,987 steps per variant (~3 min each on the shared
  GPU). Batching noise turned out tiny (above), so this only cost time; unchanged steps now reuse L1's predictions.
- **(c) loose text measured alone** mostly measures the status bar: 97% of the text it adds comes from system windows,
  which the phone drops first. Its leave-one-out row is the meaningful one.
- **The repo's eval crashes on a groundable click with 0 candidates** (its bucket table starts at 1): the loose-text
  variant creates such steps. Variant items carry their own candidate count; buckets come from the trained format.
- **Rules picked for recall** (fuzzy, tappable first) don't carry over to accuracy (§L3).
- **"Fewer options help Laya":** a first reading compared shortlist accuracy on the golds it keeps (63%) with
  full-list accuracy on every crowded tap (58%). On the same taps there is no difference (63.5 vs 63.4).
- **Caveats:**
  - The shortlist's words here lack the hint labels and typed words Friday adds live, so live recall may be a little
    higher [UNCERTAIN].
  - Crops are resized with PIL's bilinear (as the classifier's training data); the phone's `createScaledBitmap`
    aliases more on big downscales, so names on the phone may be slightly worse [UNCERTAIN].
  - Meanings (names learned on the phone) and memory hints don't exist offline.
  - AndroidControl is 2023-era apps on an emulator; the owner's 2026 apps differ (research/26 E4 found few unnamed
    icons on their first screens).
  - The GPU was shared with the student agent; that affects speed only, not results.

## 9. Reproducing, and what is on the box
- **Code** (`training/laya-scale/`): `ac_data.py` (L0), `ac_eval.py l1 | l2 | l3 | l3op`; the switches and the
  shortlist port in `scripts/laya_android.py`. The repo's own L1 commands:
  `eval_laya_android.py --model <snapshot> --splits test_idd,test_app_unseen,test_task_unseen,test_category_unseen
  --out results/test/internal_laya_android.json --preds-dir results/test/raw/laya_android`, then
  `evaluate_androidcontrol.py --model laya_android --out results/test/final_metrics.json --preds-out
  results/test/final_predictions.jsonl.gz`, with `LAYA_ANDROID_DATA=/root/friday-laya/laya/ac`.
- **On the box** (`/root/friday-laya/laya`, 8.7 GB): the venv (6.5 GB), `ac/` (repo-format items 0.6 GB, test
  forests + icon classes 1 GB, cached predictions 8 MB, splits and the InfiGUI reference), `data/` (hand-off, 0.7 GB),
  `results/l2.json`, `l3.json`, `l3op.json`, logs; the model in the shared `/root/friday-laya/hf` (0.65 GB). No raw
  shards. Nothing of mine is running. The box's laya-android clone has our L1 run in `results/test/` (its published
  files are in the clone's git HEAD, which `ac_eval.py l1` compares against).

## Sources
- [primary] github.com/Byun11/laya-android @ `ab216d4`: `src/candidates.py`, `src/serialization.py`,
  `src/data/android_control.py`, `scripts/build_androidcontrol_dataset.py`, `scripts/eval_laya_android.py`,
  `scripts/evaluate_androidcontrol.py`, `docs/eval_protocol.md`, `docs/training.md`,
  `results/data/androidcontrol_stats.json`, `results/test/final_metrics.json`, `results/test/raw/laya_android/`.
- [primary] `laya==0.3.28` (`laya.train.items_from_rows`, `load_checkpoint`); github.com/NandhaKishorM/laya @ `4ac226d`.
- [primary] HF `ByunByun/laya-android` @ `5a03b2bc4220ec786a00a87eacdaa75cb844bd6b` (model.safetensors sha256
  `4ffca5d9…62993`, checked).
- [primary] AndroidControl, `gs://gresearch/android_control` (20 shards, `splits.json`, `test_subsplits.json`), Apache-2.0.
- [primary] InfiGUI-R1's judge (`third_party/infigui_r1`) and its test file `InfiX-ai/android_control_test`
  (`android_control_test.json`, 1,543 episodes, 8,444 steps).
- [primary] Friday: `Candidates.kt` (`buildCandidates`, `idLabel`, `shortlist`), `Routes.kt` (`contentWords`,
  `aboutWords`), `IconNamer.kt` (`isIcon`, `crop`), `LayaEngine.step`; `models/icons/` (threshold 0.90).
- [measured] `/root/friday-laya/laya/results/{l2,l3,l3op}.json` and the logs next to them.
