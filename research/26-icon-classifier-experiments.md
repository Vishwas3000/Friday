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
| T8 | E1 Optuna tuning: E0 passes, so this checks for easy headroom on a cheap rented GPU | Vast | running |
| T9 | E2 ablations: what each data and model choice is worth | Vast | planned |
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
