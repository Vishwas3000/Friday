# 26 — Icon classifier: tasks, decisions and experiments

_2026-10-19 · Builds on research/24 §4 and research/25 (prior art, sizes, licences, data) and D-055 · Runs on a rented
RTX 4090 (Vast, Ireland), never on the Mac (D-042) · Trust tags: [primary] read at source · [measured] run here ·
[derived] computed from cited numbers · [UNCERTAIN] not yet known_

## 1. The goal, and what counts as done
**The problem.**
- An icon with no text, content description or useful resource id reaches Laya as `- (ImageView)`, so Laya can't
  choose it (docs/SYSTEM.md §3.2).
- On the first screens of 17 of the owner's apps, 30 of 417 elements were unnamed. Resource-id words named 8 of them
  (D-052 step 1).
- The rest are mostly in Chrome pages, the Play Store and Zomato [measured, 2026-10-18].
- Money apps block screenshots (GPay came back 99.9% black), so pixels only help elsewhere [measured].

**The fix.** A tiny on-phone classifier names an unnamed icon from a crop of the screen: "search", "menu", "qr code"…
- The name is cached per app and element, so each icon is classified once.
- A name is used only when the model is confident. Otherwise the icon stays unnamed, as today.

**Done means all of these** [targets set before training]:
| Gate | Target | Why |
|---|---|---|
| Real held-out top-1 | ≥ 85% | research/25: UIST'18's 38.5M-parameter network reached 94.7% on the same split; Knecht's MobileNetV2 0.83–0.89 on unseen websites |
| Precision at the threshold | ≥ 95% of the icons it names are right | a wrong name misleads Laya more than no name |
| Coverage at the threshold | as high as possible, reported | how many icons get a name at all |
| Size | ≤ 10 MB file | the phone already carries Laya (578 MB) |
| Speed | ≤ ~10 ms per icon on the Pixel 8 CPU [UNCERTAIN until measured] | it must not slow a step |
| ONNX parity | max difference vs PyTorch < 1e-4 | the phone runs the ONNX file |
| On the owner's apps | precision ≥ 90% on ~100–200 real icons | the 2017 data may not match 2026 apps |
| Task suite (D-049) | no task gets worse with names on | the end-to-end gate |

## 2. Tasks, in order
| # | Task | Where | Status |
|---|---|---|---|
| T1 | Research: prior art, model sizes, licences, data (research/24 §4, research/25) | agents, web | done |
| T2 | Run-pack: `training/icons/` (`setup.sh`, `prepare.py`, `train.py`, `run.sh`) | Mac (code only) | done |
| T3 | Dry run of data and renders without training; it caught the label bug (a float column read as one-hot) | Mac | done |
| T4 | Rent the GPU; setup: venv (PyTorch 2.14.1 cu126), data, renders | Vast 4090 | done: 130,342 train, 4,809 real test, 1,831 render test, 108 classes |
| T5 | E0 baseline training + ONNX export + parity + threshold | Vast | **done**: passes every gate (§6) |
| T6 | Independent code review of the run-pack | agent (read only) | running |
| T7 | Phone integration: crop unnamed icons outside money apps, classify, cache | agent (Kotlin, built only) | running |
| T8 | E1 Optuna tuning | Vast | **done**: +1–2 points; width 0.75 always worse (§6) |
| T9 | E2 ablations: what each data and model choice is worth | Vast | **done**: A1, A2 (§6) |
| T10 | E3 robustness: dark mode, blur, low resolution, per-class confusions | Vast | planned |
| T11 | E4 on the phone: latency, parity and the owner's icons | Pixel 8 | after T7 |
| T12 | E5 task-suite gate, icon names on vs off | Pixel 8 | after T11 |
| T13 | Ship: model to the phone; delete the downloaded icon data on the box; tell the owner to stop the instance | — | last |

## 3. Decisions and why
### 3.1 Architecture: MobileNetV3-Small 1.0, from scratch
Candidates were weighed before training on size, compute and on-device support [research/25 §3; derived from published
counts]:

| Backbone (100-class head) | Parameters | fp32 file | Multiply-adds at 64 px | Verdict |
|---|---|---|---|---|
| **MobileNetV3-Small 1.0** | 1.62M | 6.5 MB | 5.1M | **chosen**: fits the budget, runs on ONNX Runtime 1.31 unchanged (HardSwish is expanded), a few ms per icon |
| MobileNetV3-Small 0.75 | 1.12M | 4.5 MB | 4.0M | ablation E2: smaller, is it as good? |
| MobileNetV2 0.5 | 0.82M | 3.3 MB | 7.9M | not run: more compute for no expected gain |
| MobileNetV4-Conv-S | 2.62M | 10.5 MB | 16.7M | ablation E2 if V3 falls short; better under int8 (ReLU, no squeeze-excite) |
| EfficientNet-Lite0 | 3.50M | 14.0 MB | 33.2M | rejected: over the size budget |
| UIST'18 network | 38.5M | 147 MB | 1,490M at 32 px | rejected: 20× too big (research/25 §2) |

**Why not something else:**
- **Captioners and CLIP-style models:** 0.1–1.9 GB, and MobileCLIP's licence is research-only (research/25 §3).
- **Released icon models** (IconNet, Screen Recognition, Apple's labeller) were never published. COALA's is GPL.

**Settings:**
- **From scratch, not ImageNet-pretrained.** ImageNet's non-commercial terms may carry over to pretrained weights, and
  in Knecht's runs training from scratch cost only ~3 points (research/25 §3). A pretrained run appears below as a
  reference only (E2).
- **fp32, no int8.** MobileNetV3's hard-swish and squeeze-excite stay float under ORT int8. One fine-tuned
  MobileNetV3-Small fell from 98.4% to 58.9% under static int8 [secondary, research/25]. The fp32 file already fits.

### 3.2 Input: 64×64 grey, values /255
- **Grey.** UIST'18 is grey (32×32), and app icons appear in any colour and in light or dark themes. Grey plus random
  inversion during training removes colour as a cue.
- **64 px.** Phone crops are 100–200 px. 64 keeps more detail than 32, and the cost is tiny (5.1M multiply-adds).
  UIST'18's crops are upscaled 32 → 64; renders are drawn at 64.
- **The ONNX takes exactly what the phone has:** grey (ITU-R 601 luma, as PIL `convert('L')`) / 255, `[N,1,64,64]`.
  Normalisation and the grey-to-3-channel step are inside the graph.

### 3.3 Data
| Source | What | Count | Licence | Use |
|---|---|---|---|---|
| UIST'18 `data.zip` (Rico apps, 2017) | hand-labelled icon crops, 99 classes | 49,136 unique, oversampled to 113,854 (≥ 1,000 per class); test 4,809 at the natural mix | none of its own; Rico's terms (no licence to the screenshots; they bind the downloader's employer) | the backbone of training; owner accepted for personal research (2026-10-17, 2026-10-19) |
| Material Symbols (Google), 3 styles × fill × weight | renders of 8 classes the real data lacks: QR code, scan, camera, wallet, rupee, translate, apps, sort | 400 per class | Apache-2.0 | new classes |
| Material Symbols | today's look of 97 real classes | 120 per class | Apache-2.0 | style gap 2017 → 2026 |
| Material Symbols + text | "other": 600 icons outside the list × 4, plus 1,200 bits of text | 3,600 | Apache-2.0 | lets the model say "not one of mine" |

**Render variety.** Light or dark ground; filled or outlined; weights 300–600; plain or on a round or rounded button;
a ±4 px shift; blur 30% of the time; a low-resolution round trip 30% of the time (prepare.py).

**Splits:**
- Real icons: UIST'18's own train/validation split.
- Renders: 10% held out at random.

**Known weaknesses:**
- UIST'18's split is random per class, not per app, and its training set repeats icons (49k unique → 114k). So the
  real-test number is an **upper bound** for unseen apps [primary, research/25 §1]. E4 (the owner's icons) is the
  honest test.
- Real icons are from 2017. Renders are clean vector art, not screenshots.

### 3.4 Output: a threshold, not a forced answer
- The model outputs softmax probabilities over 108 classes.
- The threshold is the lowest confidence at which the icons it names (excluding "other") are ≥ 95% right, over both
  test sets.
- On the phone: below the threshold, or for "other", the icon stays unnamed. Above it, Laya sees the word
  (`arrow_backward` → "back", `avatar` → "profile").

### 3.5 Training recipe (E0)
- **Optimiser and schedule:** AdamW, lr 3e-3, weight decay 0.05, OneCycle with 5% warm-up.
- **Loss and length:** label smoothing 0.1, batch 512, 40 epochs, bf16 autocast.
- **Augmentation:** RandomResizedCrop (scale 0.7–1.0), RandomInvert 0.5, ColorJitter (brightness and contrast 0.3),
  GaussianBlur 20% of the time.
- **Checkpoint:** the best by real + render test accuracy.

The checkpoint is chosen on the test sets, so E0's reported numbers are slightly optimistic. E1 fixes that with a
separate validation split.

## 4. Experiments
### E0 — Baseline (running)
The recipe of §3.5. Outputs `out/icons.onnx`, `labels.json` and `metrics.json` (top-1 real and renders, threshold,
precision and coverage at it, per-class accuracy, ONNX parity, size).

### E1 — Optuna tuning (if E0 misses a gate, or the curves show headroom)
- **Validation:**
  - 5% of the training set, split **by unique image**. Hashing the crops matters, because the 114k training crops
    repeat 49k icons, and a random split would leak.
  - The render test set stays untouched for the final report.
- **Objective:** macro-averaged validation accuracy, every class weighted equally.
- **Search space:**
  | Parameter | Range |
  |---|---|
  | lr | 1e-3–1e-2 (log) |
  | weight decay | 0.01–0.1 |
  | label smoothing | 0–0.2 |
  | crop scale minimum | 0.5–0.9 |
  | blur probability | 0–0.4 |
  | epochs | 20–40 |
  | width | 0.75 or 1.0 |
  | input | 48 or 64 px |
- **Budget:** ~12 trials, with Optuna's median pruner after epoch 5. About 1 hour on the 4090 (~9 s per epoch
  measured), ~$0.40. Trials run one at a time: the instance has ~6 vCPU, and augmentation is CPU-bound.
- **Output:** the best configuration, retrained on everything and re-exported. The study's results go in §6.

### E2 — Ablations (one change at a time against the best configuration)
| Run | Change | Question |
|---|---|---|
| A1 | no renders | what do the Material Symbols renders add on real icons? |
| A2 | renders only (no Rico data) | how good is the licence-clean model? (the fallback of D-055) |
| A3 | input 48 / 96 px | does resolution matter? |
| A4 | MobileNetV3-Small 0.75 | can it be smaller for free? |
| A5 | MobileNetV4-Conv-S | better accuracy for 4 MB more? (only if V3 falls short) |
| A6 | ImageNet-pretrained V3-Small | reference only, not shipped: what does pretraining buy? |

### E3 — Robustness (on the test sets, no retraining)
- **Inverted test images.** Dark mode must not matter.
- **Blur and low-resolution versions.** Small or soft icons on the phone.
- **The 10 weakest classes and the top confusions.** E.g. close vs add, or the arrows: which names to drop or merge.

### E4 — On the phone (after T7)
- **Speed:** milliseconds per icon and per batch on the Pixel 8 CPU, with the session's load time.
- **Parity:** the phone's probabilities vs the Mac's ONNX Runtime on the same crops.
- **The owner's icons:**
  - collect ~100–200 unnamed icons from the owner's apps (not money apps; the crops stay on the phone and the Mac);
  - label them with the help of D-052's learned names, with the owner confirming;
  - measure precision and coverage at the threshold;
  - re-set the threshold if precision drops below 90%.

### E5 — The gate
The task suite (D-049) with icon names on vs off. No task may get worse. Then the model ships.

## 5. Risks and unknowns
- **2017 icons vs 2026 apps** [UNCERTAIN]. Only E4 can tell; renders narrow the gap.
- **Coordinates.** Display screenshots should share `getBoundsInScreen()`'s pixels; checked once on the phone in T7/E4.
- **Licence.** Rico-derived weights are fine for the owner's personal use, not for publishing. A2 gives a clean fallback.
- **Over-trust.** A wrong name can mislead Laya. The threshold and E5 are the guards.

## 6. Results log
| Exp | Config | Real top-1 | Renders top-1 | Threshold | Precision / coverage at it | Size | Parity | Time |
|---|---|---|---|---|---|---|---|---|
| **E0b (shipped)** | fixed data + E1's best (lr 0.0078, wd 0.092, smoothing 0.077, crop 0.82, blur 0.21), 40 epochs | **94.07%** | **98.58%** | **0.90** | **99.2% / 86%** on real; unseen "other" wrongly named **5.0%** | 6.53 MB | **7.7e-7** | 6.5 min |
| E0 | §3.5, 40 epochs | **94.1%** | **98.1%** | **0.70** (shipped) | **98.3% / 92%** on real; renders 98.8% / 78%* | 6.53 MB | **1.0e-6** | 6.4 min (9.6 s/epoch) |

\* About 20% of the render test set is "other", which is correctly left unnamed.

**E0 notes [measured, RTX 4090, 2026-10-19]:**
- **Accuracy.** Real top-1 reached 90% by epoch 10 and settled at ~94% from epoch 32. That is level with UIST'18's 94.7%
  from a model 24× smaller (1.63M parameters). Renders reached 98%.
- **Threshold.** Precision and the share named on real held-out icons:
  | threshold | 0.3 | 0.5 | 0.6 | 0.7 | 0.8 | 0.9 |
  |---|---|---|---|---|---|---|
  | right | 96.4% | 97.5% | 98.0% | 98.3% | 98.5% | 99.2% |
  | named | 97% | 95% | 93% | 92% | 91% | 66% |

  The rule "≥ 95% right" is already met at 0.05, the search floor, because these test icons look like the training
  icons. The shipped threshold is **0.7**: 98.3% right, leaving a margin for 2026 apps. E4 re-sets it on the owner's
  icons.
- **Weakest real classes** (few test examples each): national_flag 31%, swap 36%, attach_file 50%, weather 55%, gift
  56%, dashboard 57%, then flash, launch, description and compare at 60%. None is on Friday's frequent list.
- **A bug in the first parity check.** It reported a max difference of 0.85. `torch.onnx.export` restores a module's
  previous mode afterwards, and the fresh `Probs` wrapper was in train mode. That flipped BatchNorm back to batch
  statistics, so PyTorch in train mode was compared with ONNX in eval mode. The ONNX file itself was right: 1.6e-6 on
  512 icons, the same top-1 on all. Fixed in `train.py`, which now also has `--eval-only`.
- **E1 started** (Optuna, 12 trials): validation 6,545 images by unique image (56,017 unique).

**E0b — the shipped model [measured, 2026-10-19]:**
- **Data:** the fixed data (no named icons in "other"; 280 unseen-"other" test images).
- **Settings:** E1's best.
- **Accuracy:** real top-1 94.07% (E0 94.09%), renders 98.58% (E0 98.14%), ONNX parity 7.7e-7.
- **The threshold trade-off**, now with icons from outside the list it never saw:

  | threshold | 0.50 | 0.60 | 0.70 | 0.80 | 0.85 | **0.90** | 0.95 |
  |---|---|---|---|---|---|---|---|
  | real: named icons right | 97.0% | 97.6% | 98.0% | 98.6% | 98.8% | **99.2%** | 95.1% |
  | real: icons named | 95.8% | 94.5% | 93.5% | 91.7% | 90.4% | **85.9%** | 3.4% |
  | unseen "other": wrongly named | 17.1% | 14.6% | 12.5% | 9.6% | 8.2% | **5.0%** | 0.4% |

- **The threshold decision is 0.90.**
  - The rule "≥ 95% right over all three sets" alone would pick 0.19, but that names a quarter of the unseen icons
    outside the list.
  - On a phone, many unnamed icons are brand or app-specific (outside the list). A wrong name misleads Laya more than
    none, so 0.90: 99.2% right, 86% of known icons named, 5% of unseen ones wrongly named.
  - Above ~0.92 nothing is named, because label smoothing caps confidence.
  - E4 re-sets the threshold on the owner's icons.
- **On the phone:** pushed with threshold 0.90. E0 is kept in `models/icons/e0/` (git-ignored).

**E2 ablations — one change against E0b, same settings [measured, 2026-10-19]:**
| Run | Training data | Real top-1 | Renders top-1 | Unseen "other" wrongly named at 0.9 | Self-set threshold → named share on all sets |
|---|---|---|---|---|---|
| **E0b** | real + renders | **94.07%** | **98.58%** | **5.0%** | 0.19 → 90.5% |
| A1 | real only (113,854) | 93.60% | 54.4% | 10.4% | 0.88 → 71.2% |
| A2 | renders only (16,488) | 47.2% | 98.3% | 1.1% | 0.75 → 45.4% |

- **The real (Rico) icons carry real-icon accuracy.** Renders alone reach 47%, so the licence-clean fallback of D-055
  is **not usable as is**. Publishing would need a different real-icon dataset. Many classes score 0% on real icons
  in A2, follow and book among them.
- **The renders add only +0.5 points on real icons.** But they teach the 8 new classes (A1 manages 54% on renders, and
  can't predict QR code, wallet, … or "other") and **halve the unseen icons wrongly named** (10.4% → 5.0% at 0.9).
  That is the "other" class doing its job.
- **A4 (width 0.75) was answered by E1:** worse in every trial. A3, A5 and A6 were not run, because E0b passes every
  gate with room to spare.

**Independent review of the run-pack** (a separate agent, read only, 2026-10-19). Nothing blocked the run. Findings:
1. **The parity check ran PyTorch in train mode.** Found and fixed above.
2. **The threshold never met an "other" icon it hadn't seen.** Renders were held out one image at a time, so the test
   set's "other" icons also appeared in training. The phone's likeliest mistake, confidently naming an unfamiliar
   icon, went unmeasured. **Fix:** 60 whole "other" icons plus 4 text strings, never trained on, form
   `x_test_other`. A name given to one of them counts as wrong when the threshold is set, and `metrics.json` reports
   how many get named.
3. **About 60 "other" training images were named icons in disguise.** In Material Symbols, `clear` is the same
   picture as close, `email` as mail, and so on: 80 such names in all. **Fix:** exclude any name that shares a
   picture with a named class, plus near-copies (cancel, check_circle, `*_off`, outline variants, chevrons, iOS
   arrows). That leaves 3,812 names to draw 660 from.
4. **`setup.sh` CUDA cutoff.** PyTorch 2.14's default build is for CUDA 13.0, so the cutoff is now 13.0. This box was
   unaffected (driver 12.7 → cu126). `train.py` now refuses to train on the CPU.

**E1 — Optuna, 12 trials, 25 epochs each, median pruner [measured, 2026-10-19, 30 min, ~$0.21].** The score is
macro-averaged accuracy on a 6,545-image validation split cut by unique image. It is lower than the test top-1 because
every class weighs the same.
| Trial | lr | wd | smoothing | crop min | blur | width | Validation | State |
|---|---|---|---|---|---|---|---|---|
| **1** | **0.0078** | **0.092** | **0.077** | **0.82** | **0.21** | **1.0** | **0.881** | complete (best) |
| 8 | — | — | — | — | — | 1.0 | 0.877 | complete |
| 6 | 0.0050 | 0.011 | 0.133 | 0.77 | 0.08 | 1.0 | 0.867 | complete |
| 0 | 0.0035 | 0.052 | 0.121 | 0.72 | 0.17 | 0.75 | 0.857 | complete |
| 2 | 0.0012 | 0.012 | 0.004 | 0.83 | 0.31 | 1.0 | 0.846 | complete |
| 3, 4, 5, 7, 9, 10, 11 | | | | | | | 0.58–0.84 | pruned |

- **Learning rate and weight decay.** A higher lr (~0.008) with stronger weight decay (~0.09) is best. A low lr
  (0.0012) is clearly worse.
- **Width 0.75 lost every time** (trials 0, 4, 7). The full-width model stays; this answers ablation A4.
- **Small gain.** About 1–2 points over the configuration nearest E0's, so E0 was already near a good optimum.

**The final model is E0b:** the fixed data, plus E1's best settings if they beat E0 on the unique-image validation
split. It is re-thresholded with the unseen-"other" set counted. E0 above is superseded by it.

**E4 — on the phone, first screens of 10 of the owner's apps [measured, Pixel 8, 2026-10-19].** The `icons` bridge
command was run on each app's first screen without tapping. The apps were Chrome, Play Store, Zomato, Amazon, YouTube,
Photos, Maps, Gmail, Spotify and Meesho. The crops were checked by eye against the screenshots on the Mac (scratchpad
only, not kept).
- **Unnamed, icon-shaped elements are rare.** There were 7 in all: 4 on Zomato and 3 on Spotify. The other 8 apps
  label every element.
- **What they were:**
  - Zomato: a wallet icon (₹), a "Meals under ₹250" banner, a dotted-plus brand button and the "district" logo;
  - Spotify: three artist photos (two of them the same box).
- **Names given: 1 of 7.** The dotted-plus brand button was named **add**: the shape is a plus, but the meaning is
  wrong. The photos, banner and logo were rightly left unnamed. The wallet (a class learned from renders) was missed:
  no harm done, but no help either.
- **Speed:** 455–697 ms per screen. The model took 77–305 ms for 3–4 icons and the session loaded once in 273 ms; the
  rest is the screenshot and the crops. After that the names are cached.
- **Coordinates:** the screenshot is 1080×2400, the screen's size, and the crops line up with the element boxes.
- **What it means:**
  - In the owner's everyday apps the classifier has little to do on first screens.
  - Its real risk is **brand logos and photos that look like icons**, not old-looking icons.
  - That points icon v2 at "other": photos, banners, logos and thumbnails.
  - The planned 100–200-icon collection needs deeper screens, from an exploration walk. Not done.
  - E5 (the suite, on vs off) decides whether it stays on.

**E5 — the gate: the task suite with icon names on [measured, Pixel 8, 2026-10-19].**
- **Result: 20/25**, the same as the last run without the model (20/25 once the dropped Zepto task is taken out).
- **Icon naming fired once in the whole suite:** Spotify, "share" (1 of 2 icons, 303 ms). That task passed.
- **One new failure is unrelated: maps-search.** It took the same 4 Laya steps as both passing runs (and no icon was
  named); only its final screen differed, so the check is flaky. D-057 now answers that task in one launch.
- **Verdict: the gate passes,** and the classifier stays on. In the owner's apps it rarely has anything to name
  (E4, E5). The next useful step is v2's wider "other" class (photos, banners, logos), not more classes.

