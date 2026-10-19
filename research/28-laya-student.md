# 28 — A smaller Laya for the phone: distilling Laya-Android into fewer layers

_2026-10-19 · Builds on research/22 §5 item 5 (planned there), research/06 and research/18 (how the phone's file is
made) · Runs on the rented RTX 4090 (D-042), never on the Mac · Run-pack: `training/laya-student/` · Trust tags:
[primary] read at source · [measured] run here · [derived] computed from cited numbers · [estimate] · [UNCERTAIN]_

## TL;DR
- **S11 (11 of 22 layers, distilled) passes the gate** [measured, AndroidControl test, 9,987 steps]: Policy Joint
  0.6029 vs the teacher's 0.6099 (−0.70 points, 95% CI −1.46 to +0.05; the gate was −2). Type 75.47 vs 76.75,
  Grounding 60.43 vs 61.86. It loses most on crowded screens (−1.4 PJ at 11–50 candidates) and nothing up to 10.
- **About half the compute** [derived]: ~0.56× the multiply-adds per token, so ~1.0 s instead of 1.73–1.85 s per
  pass on the Pixel 8 if time follows compute [estimate; the owner's phone bench decides].
- **Same contract, same export:** identical ONNX inputs, outputs and op types; nb8 file 515.6 MB (−11%); nb8 keeps
  298/300 operations and 147/148 targets vs PyTorch on x86 (the shipped teacher file: 297/300, 146/148).
- **Memory needs the vocabulary, not the layers.** English-only (D-058): 49,736 of 256,000 token ids cover 99.37% of
  test tokens; accuracy cost −0.03 / +0.09 points (teacher / S11). **S11 English-only: 198.7 MB file, ~272 MB RAM
  [estimate]** vs 578 MB / 651 MB today. Needs a 5-line id map in `Tokenizer.encode` (§6 E4c).
- **S11 lost routing** (E6): Friday's skill question is right 40% of the time (the teacher: 82%), and half of its
  *confident* routings are wrong. Distilling on AndroidControl's two questions alone drops the general choice skill.
- **S8 (8 layers) missed the gate** (E2): Policy Joint 0.5754, −3.5 points; it keeps operations and loses targets.
- **S11-F** (E5: S11 fine-tuned on Friday's format, with trained-format replay and the teacher's routing/slot answers)
  **restores routing to the teacher's 82%, with every confident routing right**, but the format rows bought nothing:
  Friday-format Policy Joint level with S11 (−0.27, CI −0.81 to +0.28) and −0.9 on the trained format.
- **S11-A** (E7: S11 plus the routing/slot answers, no format rows) is the run that should keep S11's accuracy and the
  fixed routing [running]. The swap into the phone waits for its numbers and the owner's phone bench.

## 1. The goal, and what counts as done
**The problem.**
- One Laya pass takes 1.73–1.85 s for 636 tokens on the Pixel 8 (`nb8-h-e16`, 578 MB file, 651 MB RAM), and a tap
  step on a crowded screen needs two passes (research/18 §6b, §9).
- The owner wants Friday "as light as possible in both compute and memory".

**The fix.** A student with about half of Laya-Android's 22 encoder layers, distilled from it, with the **same input
contract**: the same tokenizer, prompt and options, the same 5 ONNX inputs and 2 outputs. On the phone only the model
file changes.

**Done means** [targets set before training]:
| Gate | Target | Why |
|---|---|---|
| Policy Joint, AndroidControl test | **within 2 points of the teacher**, both run here with the same harness | the owner's gate |
| Type, Grounding, target top-1 by candidate count | reported next to the teacher's | where a smaller model loses first |
| ONNX contract | same input/output names, dtypes and dynamic axes as `laya-android.nb8-h-e16.onnx` | Kotlin unchanged |
| Probabilities on the phone | the app's hard-coded T = 1.161 gives the student's own calibrated probabilities | `LayaInput.kt:32` hard-codes T; `Skills.kt` routes at confidence ≥ 0.7 |
| Parity, nb8 vs PyTorch | decisions agree on the test rows checked; max \|Δp\| reported | research/18 kept 52/52 at max \|Δp\| 0.056 |
| File, RAM, speed | reported; speed on the phone is the owner's bench | |

## 2. Tasks
| # | Task | Where | Status |
|---|---|---|---|
| T0 | This plan | Mac | done |
| T1 | Box setup: venv (Python 3.11, torch 2.14.1+cu126, transformers 4.57.6, onnxruntime 1.31.0); laya `4ac226d` (0.4.8), laya-android `ab216d4`, HF `ByunByun/laya-android@5a03b2b` (sha256 checked) | box | done |
| T2 | Build S11 from the teacher; load it through laya's own code; check the ONNX contract | box | done: 266.7M parameters, global layers 0, 3, 6, 9; same contract (E3) |
| T3 | Distillation data from the other agent (`/root/friday-laya/laya/data/READY`); scripts and a smoke test while waiting | box | done: data READY 08:46, smoke test of every stage on synthetic rows |
| T4 | Teacher distributions on train (cached once); distil S11; select by validation Policy Joint | box | done: epoch 3 kept (E1) |
| T5 | S11 vs the teacher on test (Type, Grounding, Policy Joint, by candidate count); a deeper cut if it passes | box | S11 passes (−0.70 PJ); S8 (E2) |
| T6 | Export like the shipped model (ONNX → nb8-h-e16); parity; size; copy to `models/laya-android-s11.nb8-h-e16.onnx` | box → Mac | done (E3) |
| T7 | Vocabulary: ids used, test coverage, size and RAM saving, what the tokenizer would need. Narrowed by the owner to English only (2026-10-19), plus a cut S11 file and id map for a RAM bench | box | done (E4) |
| T8 | Results and a recommendation (§6, §8) | Mac | done |

## 3. Decisions and why
### 3.1 Which layers: S11 = the teacher's even layers 0, 2, …, 20
- **The attention pattern carries over exactly** [primary, transformers 4.57.6 `modeling_modernbert.py:464`]:
  ModernBERT makes layer *i* global when `i % global_attn_every_n_layers == 0` (3 here); every other layer attends
  inside a 128-token window.
  - The student keeps `global_attn_every_n_layers = 3`, so its global layers are 0, 3, 6, 9 = the teacher's 0, 6, 12,
    18 (all global there). Its local layers are the teacher's 2, 4, 8, 10, 14, 16, 20 (all local there).
  - The checkpoint's `encoder/config.json` also carries a 22-entry `layer_types` list, which transformers 5 reads
    instead [primary]. The student gets the same list cut to the kept layers. It equals the `i % 3` pattern, so both
    transformers versions build the same model; `make` asserts it.
- **Layer 0 stays layer 0.** ModernBERT's layer 0 has no attention pre-norm (`attn_norm` is an `Identity` for
  `layer_id == 0`) [primary, `modeling_modernbert.py:521`]. Any choice that moves another layer into slot 0 would lose
  that layer's norm or invent one.
- **Rejected:**
  - *The bottom 11 layers (0–10):* the pattern carries over too, but the decision head was trained on top-layer
    features, and dropping the top half moves the student furthest from them.
  - *Odd layers 1–21:* slot 0 would get teacher layer 1, which is local and has a pre-norm.
  - *A narrower model* (fewer heads or a smaller width): every weight changes shape, so nothing is copied and training
    starts much further away.

### 3.2 What the student copies from the teacher
Everything except the dropped layers: the embedding table and its norm, the 11 kept layers (renumbered 0–10), the final
norm, the 2-layer decision head, the question-type embedding, the scorer and the act head. The tokenizer,
`max_len` 3072 and `head_max_len` 1536 are the teacher's. Only `num_hidden_layers` and `layer_types` change in
`encoder/config.json`.
- **Note for later:** the decision head is 2 transformer layers of width 768 with a 3,072-wide MLP that run over every
  token. Per token that is ~2.8 encoder layers of compute (§4). It stays as it is here (the owner asked for it), but
  `head_layers` is a config field that laya's own loader reads, so a 1-layer head is a later lever.

### 3.3 The loss: soft cross-entropy to a mix of the teacher and the gold answer
Target for each question = **0.5 × the teacher's probabilities at temperature τ = 2 + 0.5 × the gold one-hot**. The
student is trained with laya's own `soft_ce_loss` on its plain logits, so laya's training loop runs unchanged; only the
targets differ.
- **Why a 50/50 mix:** with the teacher's weight at most 0.5, the gold answer always keeps the largest share of the
  target, so a confident teacher mistake can't flip what the student is taught. The other half carries the teacher's
  ranking of the wrong options: which element is the plausible second pick, and which operations it confuses. Gold
  labels matter because the gate is scored against them.
- **Why τ = 2:** the teacher trained on these same rows for 2 epochs, so on train it is more confident than on unseen
  screens. At its own fitted T = 1.161 most of the mass sits on one option and the mix would be close to plain
  one-hot training. τ = 2 spreads more mass onto the alternatives, which is the knowledge distillation is for. 2 is the
  common default (e.g. DistilBERT) [secondary].
- **Calibration is not the loss's job:** the student is refitted on validation afterwards (§3.5).
- **Rejected:**
  - *Teacher only (weight 1):* copies the teacher's train-set confusions, and with only 3 epochs there is little
    room for pure function matching.
  - *Hinton's form* (the student also at τ, plus a separate gold term): a custom loss and training loop for the same
    idea, and the owner asked for one soft cross-entropy to a mix.
  - *Hidden-state distillation* (TinyBERT-style layer matching): runs the teacher every step (about twice the cost)
    and needs new code. It is the next step if the gate fails.
  - *A sweep over the mix and τ:* no time on this box (§3.7).

### 3.4 Otherwise the teacher's recipe
`laya.train.train_model` exactly as `train_laya_android.py` used it: AdamW (weight decay 0.01), encoder lr 2.5e-5,
head lr 1e-4, cosine to 1e-6, micro-batch 8 × accumulation 4, gradient clipping 1.0, bf16 autocast (the same
`_forward` patch), gradient checkpointing, option order shuffled for choice questions, seed 0, **3 epochs, the epoch
with the best validation Policy Joint kept** (laya-android's own `evaluate_split`). The questions are the teacher's
training rows: the operation on every step, the target on groundable CLICK/LONG_PRESS steps.
- **Rejected:** a higher learning rate or more epochs for the smaller model. No evidence for either, and keeping the
  recipe isolates the effect of depth.

### 3.5 Temperature: refit on validation, then built into the scorer
- The phone decodes with a hard-coded T = 1.1609 (`LayaInput.kt:32`), and routing trusts confidence ≥ 0.7
  (`Skills.kt:113`). A student whose logits have a different scale would change those confidences.
- So, as in the teacher's recipe, a temperature T_s is fitted on validation (`laya.calibrate.fit_temperature_map`;
  validation is too small for per-bucket temperatures, as it was for the teacher). Then the scorer's last linear layer
  is multiplied by 1.1609 / T_s and the config keeps T = 1.1609. `softmax(z · 1.1609/T_s / 1.1609) = softmax(z / T_s)`
  exactly, so the phone and `laya.load` both give the calibrated probabilities with no code change.
- Side effect: the act head reads the softmax of those logits, so its output moves too. Nothing in the app decides on
  it (`actProbability` is only logged) [primary, grep of `android/`].

### 3.6 Evaluation
- **Harness:** laya-android's own `evaluate_split` (operation accuracy, target top-1 on groundable clicks, Policy
  Joint, ECE) and the unmodified InfiGUI-R1 judge for Type and Grounding over the 8,444 reference test steps, as in
  laya-android's `evaluate_androidcontrol.py`.
- **The teacher is re-run here,** in the same environment and on the same rows, and checked against its published
  numbers (Type 76.66, Grounding 61.71; Policy Joint 0.659 IDD) before any comparison.
- **The gate number:** Policy Joint over the union of the four test subsplits (each step once, synthetic DONE steps
  included, as the harness counts it). Per-subsplit and per-bucket numbers are reported too.
- **Buckets:** candidate count 1–5, 6–10, 11–20, 21–50, > 50 (the harness's own).

### 3.7 Time on the box, and the deeper cut
- The teacher's 3 epochs took 2.7 h on a 4090 [primary]. S11 has ~0.56× the compute per token (§4), so about 1.5 h
  [estimate]. The owner's budget for the whole job is 4–5 h.
- **S8, the deeper cut:** teacher layers 0, 2, 5, 9, 11, 14, 18, 20. Its global slots 0, 3, 6 are the teacher's
  global 0, 9, 18, and the other five are local in the teacher too.
- **Decided: S8 runs after S11, not alongside it.** S11 alone drove the GPU to ~80%. The other agent's evaluations
  on the same GPU peaked at 19.7 GB, and one of my side runs did hit an out-of-memory error with three processes on
  the card, so two trainings at once risked killing a run. `chain.sh` starts S8 automatically once S11 has passed
  the gate and been exported.

### 3.8 Export: the shipped model's own code
`scripts/export_onnx.py` (opset 17, the TorchScript exporter, the MHA fast path off) and `scripts/speed_eval.py`'s
`nb8-h-e16` quantizer (MatMulNBits 8-bit, blocks of 32, accuracy_level 4, the head fp32, the embedding fp16) are
imported and pointed at the student, so the settings are the shipped ones by construction.
- **Environment difference:** the shipped file was exported on the Mac with transformers 5.20 and laya 0.3.28; this box
  has transformers 4.57.6 (laya-android's tested version) and laya 0.4.8 (the owner's pin). The contract and the op
  types are compared against the shipped file (§5, E3).
- **Parity on the box is x86:** ORT's MatMulNBits kernels differ between x86 and the Pixel's Arm cores, so on-phone
  logits can differ slightly [UNCERTAIN until the owner's bench].

## 4. What to expect [derived]
Per token, from the weight shapes in the checkpoint (encoder layer: QKV 768×2304, out 768×768, MLP 768×2304 and
1152×768; head layer: 768×2304, 768×768, 768×3072 and 3072×768) plus attention at the fixture's 318 tokens per row:

| | encoder layers | multiply-adds per token | vs teacher | nb8 file | phone pass at 636 tokens (if time ∝ compute) |
|---|---|---|---|---|---|
| Teacher | 22 | ~136M | 1.00 | 578 MB (393 MB of it is the fp16 embedding) | 1.73–1.85 s [measured] |
| S11 | 11 | ~76M | 0.56 | ~515 MB | ~1.0 s |
| S8 | 8 | ~59M | 0.43 | ~498 MB | ~0.8 s |

- **Compute roughly halves; memory barely moves.** The embedding table (393 MB as fp16) is two thirds of the file and
  the layers are only ~5.6 MB each at 8 bits. Memory needs the vocabulary cut (T7), not fewer layers.
- RAM ≈ the file + ~70 MB of runtime and activations (651 MB for the 578 MB teacher), so S11 ≈ 585 MB [estimate].

## 5. Experiments
### E0 — The teacher, re-evaluated here
Same harness and rows as the students; checks the data and the environment against the published numbers.

### E1 — S11
`make` (T2) → `cache` the teacher's train distributions → `train` (§3.3–3.5) → `eval` on test.

### E2 — S8 (run after S11 passed, §3.7)

### E3 — Export and parity (T6)
ONNX contract and op types vs the shipped file; nb8 vs PyTorch on test rows (decision agreement, max |Δp|); size.

### E4 — Vocabulary (T7, analysis only)
Token ids used by AndroidControl train and test rows (question, options and state, as the model sees them); the share
of test tokens a train-built vocabulary covers; the size and RAM saving; what the phone's tokenizer would need.

### E5 — S11-F: S11 fine-tuned on Friday's input format (asked 2026-10-19, after S11 passed)
research/27 found Friday's format (system windows out, id labels, loose text, icon names; target rows cut to the k = 32
shortlist) costs 0.4 operation points, mostly DONE → CLICK and WAIT → CLICK, while gaining 1.2 target points.
- **Data:** the research/27 agent's `friday_{train,val,test}.jsonl`: one line per step, or an operation line on every
  element plus a target line on the shortlist (no target line when the shortlist misses the gold).
- **Recipe (my call):**
  - **Start from S11** (the ship candidate), not from the teacher: the phone runs the student.
  - **Friday-format train lines with hard labels, plus trained-format replay at 40% of the epoch** (research/22 §3,
    research/15 §3.4: 20–50% replay kept the old task; without it a pilot flipped 1 of 5 targets). Replay steps are
    drawn at random from AndroidControl train and keep S11's own targets (the teacher mix, from the cache).
  - **1 epoch, encoder lr 1e-5, embedding table frozen,** head lr 1e-4, otherwise the teacher's recipe. research/15
    chose the lower lr and frozen embeddings for fine-tuning a trained model, against forgetting. One epoch because the
    format shift is small (research/27: the teacher's joint is about the same in both formats) and the box time is
    bounded; a second epoch is the first thing to try if it falls short.
  - **Temperature refitted on `friday_val`** and built into the scorer, as for S11.
- **Scoring Friday-format test lines:** the harness counts lines, so they are merged per step: the operation from the
  line that asks it, the target from the line that asks it (scored on its shortlist), and a groundable tap whose gold
  the shortlist cut counts as wrong (research/27 L3's rule). DONE and WAIT recall are reported per operation.
- **Gate (the coordinator's):** S11-F beats S11 on Friday-format test Policy Joint, and stays within 1 point of S11 on
  the trained-format test.

## 6. Results log
### E0 — the teacher, re-run here [measured, RTX 4090, 2026-10-19]
Laya-Android at the pinned revision, laya 0.4.8, transformers 4.57.6, bf16, on the other agent's hand-off rows
(`ac_test.jsonl`: the 9,987 test steps, each once). It reproduces the published numbers within 0.2 points, so the
rows, the harness and this environment are sound:

| | here | published (laya-android `results/`) |
|---|---|---|
| Type (8,444 reference steps, InfiGUI-R1 judge) | 76.75 | 76.66 |
| Grounding | 61.86 | 61.71 |
| Policy Joint, IDD / app-unseen / task-unseen / category-unseen | 0.6601 / 0.5408 / 0.5686 / 0.5474 | 0.659 / 0.540 / 0.567 / 0.545 |
| **Policy Joint, all 9,987 test steps (the gate's reference)** | **0.6099** | — |

So the gate is **S11 ≥ 0.5899** Policy Joint on the same rows.

### E1 — S11: 11 layers, distilled [measured, 2026-10-19]
**Training.** 132,448 items (88,308 operation + 44,140 target questions; teacher distributions cached for all of
them in 331 s), 3 epochs, 1.70 h on the shared 4090. Validation (827 steps) after each epoch:

| | operation | target top-1 | Policy Joint |
|---|---|---|---|
| epoch 1 | 0.7690 | 0.6484 | 0.6295 |
| epoch 2 | 0.8017 | 0.6584 | 0.6605 |
| **epoch 3 (kept)** | **0.8089** | **0.6658** | **0.6667** |
| teacher | 0.7993 | 0.7032 | 0.6716 |

- **Temperature:** fitted T_s = 0.837 on validation (the student is under-confident at T = 1, as expected from τ = 2
  targets). The scorer's last layer was multiplied by 1.1609 / 0.837 = **1.387**, so the app's fixed T = 1.1609 gives
  the student's calibrated probabilities (§3.5).

**Test, all 9,987 steps — the gate passes** (Type and Grounding over the 8,444 reference steps):

| | Policy Joint | Operation | Target top-1 | Type | Grounding | ECE op / target | Op macro-F1 |
|---|---|---|---|---|---|---|---|
| Teacher (22 layers) | 0.6099 | 0.7472 | 0.6541 | 76.75 | 61.86 | 0.026 / 0.038 | 0.562 |
| **S11** | **0.6029** | 0.7363 | 0.6476 | 75.47 | 60.43 | 0.043 / 0.056 | 0.559 |
| difference | **−0.70** | −1.09 | −0.65 | −1.28 | −1.43 | | |

- **Paired on the same 9,660 scored steps:** −0.70 points, 95% CI [−1.46, +0.05]. The 2-point gate is well outside it.
  The teacher alone is right on 727 steps and S11 alone on 659: the student is not a copy. It picks the teacher's
  operation on 85.5% of steps and its target on 75.5% of target questions.
- **Calibration: after the fold S11 is more over-confident than the teacher on test** (the temperature was fitted on
  827 validation steps; it is never fitted on test):

  | | mean confidence − accuracy | at confidence ≥ 0.7: share of steps / right | ECE |
  |---|---|---|---|
  | operation, teacher | +0.021 | 63.9% / **87.4%** | 0.026 |
  | operation, S11 | +0.042 | 69.4% / **85.0%** | 0.043 |
  | target, teacher | +0.038 | 54.7% / 87.9% | 0.038 |
  | target, S11 | +0.055 | 58.2% / 86.4% | 0.056 |

  Friday acts without asking at confidence ≥ 0.7 (`Skills.kt` CONFIDENT), so on AndroidControl-style steps S11's
  "sure" answers are ~2.4 points less often right than the teacher's. Routing itself is measured in E6.

**By candidate count** (Policy Joint and target top-1 over all steps in the bucket; Type/Grounding over reference
steps):

| candidates | steps | PJ teacher → S11 | target top-1 teacher → S11 | Type teacher → S11 | Grounding teacher → S11 |
|---|---|---|---|---|---|
| 0 | 97 | 0.854 → 0.842 | — | 80.9 → 79.8 | — |
| 1–5 | 1,344 | 0.638 → 0.633 | 0.841 → 0.839 | 73.2 → 73.7 | 58.0 → 57.8 |
| 6–10 | 1,943 | 0.591 → **0.609** | 0.676 → **0.697** | 72.6 → 73.7 | 62.1 → 63.7 |
| 11–20 | 4,095 | 0.637 → 0.623 | 0.665 → 0.657 | 79.1 → 77.2 | 63.0 → 61.2 |
| 21–50 | 2,385 | 0.560 → 0.546 | 0.546 → 0.528 | 77.5 → 74.5 | 62.4 → 59.0 |
| > 50 | 123 | 0.512 → 0.463 | 0.542 → 0.458 (n = 96) | 85.5 → 80.0 | 62.5 → 56.3 |

- **The student loses on crowded screens:** −1.4 points of Policy Joint at 11–50 candidates and −5 above 50 (only
  123 steps). Up to 10 candidates it is level or better.
- **What the phone's target question sees** (target shortlist k = 32, D-060, so a target question has at most 32
  options) [measured, target top-1 on the test target questions]:

  | candidates on the screen | n | teacher | S11 | gap |
  |---|---|---|---|---|
  | 1–16 | 2,791 | 0.714 | 0.711 | −0.4 |
  | 17–32 | 1,566 | 0.569 | 0.557 | −1.2 |
  | 33 or more (on the phone: cut to 32) | 399 | 0.566 | 0.561 | −0.5 |

**By test subsplit** (Policy Joint; Type / Grounding):

| | IDD | app-unseen | task-unseen | category-unseen |
|---|---|---|---|---|
| Teacher | 0.6601; 81.47 / 67.81 | 0.5408; 70.88 / 53.31 | 0.5686; 72.87 / 56.68 | 0.5474; 71.58 / 55.15 |
| S11 | 0.6516; 79.65 / 67.16 | 0.5350; 70.13 / 50.54 | 0.5629; 72.02 / 54.74 | 0.5436; 70.88 / 52.79 |

### E2 — S8: 8 layers, distilled [measured, 2026-10-19]
The teacher's layers 0, 2, 5, 9, 11, 14, 18, 20 (§3.7), the same recipe and data as S11: 3 epochs, 1.19 h on the
4090. Best epoch 2 (validation joint 0.6295; epoch 3: 0.6270); temperature 0.835, folded as scorer × 1.390.

| AndroidControl test (9,987 steps) | Policy Joint | operation | target top-1 | Type | Grounding |
|---|---|---|---|---|---|
| teacher | 0.6099 | 0.7472 | 0.6541 | 76.75 | 61.86 |
| S11 | 0.6029 | 0.7363 | 0.6476 | 75.47 | 60.43 |
| **S8** | **0.5754** | 0.7419 | **0.5929** | 76.69 | **58.65** |

- **Missed the gate by a wide margin:** −3.45 points of Policy Joint against the teacher (the gate was −2). The loss
  is all grounding: operations stay within 0.5 points of the teacher, target top-1 falls 6.1 points. By subsplit:
  IDD 0.6307, app_unseen 0.4982, category_unseen 0.5074, task_unseen 0.5293.
- Half the depth cost 0.7 points (S11); a third of it costs 3.5. **Not exported;** its checkpoint stayed on the box.

### E3 — S11 exported like the shipped file [measured, box: x86, onnxruntime 1.31.0]
- **fp32 ONNX:** 1,067 MB, `onnx.checker` passes, and laya's own `ONNXAgent` reproduces PyTorch exactly
  (max |Δp| = 0), as with the teacher (research/06).
- **`laya-android-s11.nb8-h-e16.onnx`: 515.6 MB** (shipped teacher: 578.0 MB, −11%). IR 8, opset 17 + `com.microsoft`
  1, the only contrib op is MatMulNBits: inside the phone's limits (`speed_eval.quantize`'s own asserts).
- **Contract:** the 5 inputs and 2 outputs have exactly the shipped file's names, element types and dynamic axes.
  **No op type the shipped graph doesn't already use.** 1,841 nodes vs 3,466: MatMulNBits 44 vs 88 (4 per layer), and
  the head's MatMuls stay fp32 as in the shipped file. The rest of the difference is small mask and shape ops, because
  transformers 4.57.6 (here) builds ModernBERT's masks differently from 5.20 (the Mac's export).
- **Parity, nb8 vs PyTorch fp32,** on 300 random test steps (seed 0), both questions, decoded with the app's T:

  | file | operation same | target same | max \|Δp\| operation / target |
  |---|---|---|---|
  | **S11** `laya-android-s11.nb8-h-e16.onnx` | **298/300** | **147/148** | 0.069 / 0.058 |
  | shipped teacher `laya-android.nb8-h-e16.onnx`, same rows | 297/300 | 146/148 | 0.095 / 0.067 |

  S11's 8-bit file keeps its decisions at least as well as the shipped one does on this runtime. Both flip a few
  decisions on x86 (margins not recorded in this run; `parity` now logs them), where research/18 saw 52/52 for the
  teacher with the Mac's phone-like kernels. The phone's numbers are the owner's bench.
- Copied to the Mac as `models/laya-android-s11.nb8-h-e16.onnx` (git-ignored).

### E4 — the English-only vocabulary (T7) [measured]
The owner narrowed Friday to English on 2026-10-19, so the cut keeps: every token id in AndroidControl train rows (as
the model reads them: question, options and state), the fixed templates (question texts, the 12 operation options,
markers, option numbers up to 299), **Friday's own fixed questions on this model** (the routing question and the 16
skill options of `Skills.kt`, `SLOT_Q`, LayaEngine's warm-up), the special tokens, all 255 byte-fallback tokens, and
**the 31 tokens that contain a tab** (Gemma has no `<0x09>` byte token, so a tab would otherwise become UNK).
- **v1 → v2 (after the independent review):** v1 (49,736 ids, the file the owner benched) missed the tabs and the app's
  questions: `spend_query`, `device_setting` and `device_status` were re-spelt on every routing call. v2 adds 35 ids.
  The test rows that tokenize differently stay 6,698, so the accuracy numbers below (measured with v1) carry over.

| | count |
|---|---|
| Vocabulary | 256,000 |
| Ids used by train / val / test rows | 49,547 / 5,960 / 25,243 |
| **Kept, v2** (train + templates + app questions + specials + bytes + tabs) | **49,771** (19.4%) |
| Test tokens covered by the kept set | **99.37%** (5.59M tokens) |
| Test ids never seen in train | 4,037 |
| Test question rows that tokenize differently after the cut | 6,698 of 14,743 (45%) |
| Test steps that tokenize differently | 4,565 of 9,987 (46%) |

- **What falls out:** ordinary words that AndroidControl train never used ("phases", "anime", "Realism", "flipped",
  "Remix", "Nebula", "hostages"…) and symbols (♎ ♏ ♍…). After the cut they are spelt from smaller kept pieces
  (`▁phases` → `▁phase` + `s`), so nothing is lost outright, but almost half the test steps carry at least one such
  word. Their accuracy cost is measured below (E4b), not assumed.
- **Size** [derived from the shapes]: the fp16 embedding table goes from 393.2 MB to **76.4 MB** (−317 MB).

**E4b — what the cut costs in accuracy** [measured, the teacher, all 9,987 test steps]. Every token outside the kept
set was re-spelt from kept pieces before the unchanged model read it (greedy longest match, else its UTF-8 byte
tokens), which is what a cut tokenizer would do [estimate: a rebuilt BPE splits a little differently]:

| | Policy Joint | Operation | Target top-1 | Type | Grounding |
|---|---|---|---|---|---|
| Teacher, full vocabulary | 0.6099 | 0.7472 | 0.6541 | 76.75 | 61.86 |
| Teacher, English-only cut | 0.6096 | 0.7458 | 0.6529 | 76.54 | 61.75 |
| S11, full vocabulary | 0.6029 | 0.7363 | 0.6476 | 75.47 | 60.43 |
| **S11, English-only cut** | **0.6038** | 0.7369 | 0.6474 | 75.34 | 60.45 |

The cut moves Policy Joint by −0.03 (teacher) and +0.09 points (S11), and Type and Grounding by 0.2 at most:
noise-level. 4,035 distinct tokens were
re-spelt 33,468 times, e.g. `reasons` → `reas` + `ons`, `▁flipped` → `▁flip` + `ped`, `♎` → its three UTF-8 bytes.

**File size and RAM with the cut.** RAM = the file + 73 MB, the gap measured for the shipped file (651 MB PSS for
578 MB, research/18 §6b) [estimate: assumes ORT keeps one copy of the weights, as it does today]:

| | nb8 file today | with the English-only cut | RAM today | RAM with the cut |
|---|---|---|---|---|
| Teacher, 22 layers | 578.0 MB [measured] | ~261 MB [derived] | 651 MB [measured] | ~334 MB [estimate] |
| S11, 11 layers | 515.6 MB [measured] | **198.7 MB** [measured] | ~589 MB [estimate] | **~272 MB** [estimate] |

**E4c — the cut S11 file, for the owner's RAM bench** (`trim`):
- `laya-android-s11-en.nb8-h-e16.onnx` (198.7 MB): the nb8 file with the embedding initializer cut to the 49,736 kept
  rows, in old-id order. Nothing else in the graph changes.
- `laya-android-s11-en.vocab-map.json` (5.7 MB): `kept` (new id = position; value = the old id) and `respell` (for each
  of the 206,264 other old ids, the new ids it is spelt with).
- **How the phone applies it** (one place, `Tokenizer.encode`):
  1. Load the map once; build `newId = IntArray(256000) { -1 }` with `newId[kept[n]] = n`.
  2. After DJL returns the old ids, replace each id by `newId[id]` when it is ≥ 0, else by the list `respell[id]`.
  3. Return the mapped ids. Everything downstream stays as it is: LayaInput's 48-token option cap and the
     `max_len` clamp then run on mapped ids, exactly as the Python check did. The special ids are unchanged (pad 0,
     eos/SEP 1, bos/CLS 2, unk 3, mask 4 are the first five kept ids), so `LayaInput.kt`'s constants stay valid.
  4. The Python reference builder (`laya_android.decide`, `speed_eval`) needs the same mapping for parity checks;
     `student.py patch_tokenizer` shows it.
- **Until that is wired, the cut file only works with pre-tokenized input** (`bench_device.py` with
  `--fixture fixture-s11-en.json`). `--decide`, or any real run, would hand it old ids above 49,735 and ORT's Gather
  fails, as the first check run here did.
- **Better for shipping:** rebuild `tokenizer.json` itself with the kept vocabulary (keeping every merge intermediate
  the kept tokens need). Then DJL emits new ids directly, no map is needed, and the tokenizer's own load time (~8 s
  today, docs/SYSTEM.md §3.3) and memory shrink too. Not done here.

### E5 — S11-F: S11 fine-tuned on Friday's format [measured, 2026-10-19]
**Data, one epoch:** 92,394 Friday-format train steps → 131,755 items with hard labels; 87,837 trained-format replay
items (40% of the epoch, random steps, the teacher mix from the cache); 39,495 items of Friday's routing and slot
questions with the teacher's answers as the only target (E6's fix). 259,087 items, 0.68 h at ~13 micro-batches/s.
Validation (`friday_val`) joint 0.6277; temperature 1.112, folded as scorer × 1.044. Started from `ckpt/s11`,
encoder lr 1e-5, embeddings frozen.

| Friday-format test (9,987 steps, lines merged per step) | Policy Joint | operation | target top-1 | Type | Grounding |
|---|---|---|---|---|---|
| teacher | 0.6104 | 0.7433 | 0.6641 | 76.55 | 62.40 |
| S11 | 0.6023 | 0.7356 | 0.6511 | 75.60 | 60.72 |
| **S11-F** | **0.5996** | 0.7306 | 0.6529 | 74.53 | 59.87 |

**Paired on the same steps** (10,000-resample bootstrap, 95% CI; 9,664 joint-scored steps):

| | Policy Joint | operation | target top-1 |
|---|---|---|---|
| S11 → S11-F, Friday format | −0.27 [−0.81, +0.28]; 374 worse, 348 better | −0.49 [−0.96, −0.02] | +0.19 [−0.53, +0.90] |
| teacher → S11-F, Friday format | −1.08 [−1.89, −0.26] | −1.26 [−1.99, −0.54] | −1.11 [−2.21, +0.00] |
| teacher → S11, Friday format | −0.81 [−1.57, −0.04] | −0.77 [−1.45, −0.10] | −1.30 [−2.31, −0.25] |
| S11 → S11-F, trained format | **−0.88 [−1.41, −0.36]** | −0.85 [−1.31, −0.39] | −0.69 [−1.39, −0.02] |
| teacher → S11-F, trained format | −1.58 [−2.37, −0.79] | −1.94 [−2.66, −1.21] | −1.35 [−2.44, −0.25] |

- **The gate's first clause failed:** S11-F does not beat S11 on Friday's format; it is level within noise. The second
  clause (within 1 point of S11 on the trained format) holds at −0.88, but that loss is real (CI excludes 0).
- Where it moved: DONE recall 0.649 → 0.677 (the format's known DONE → CLICK confusion eases), WAIT 0.353 → 0.360;
  but SCROLL_DOWN 0.604 → 0.547 and CLICK 0.837 → 0.829. By screen size (Policy Joint, S11 → S11-F): 1–5
  candidates 0.646 → 0.634, 6–10 0.603 → 0.596, 11–20 0.625 → 0.630, 21–50 0.544 → 0.537, > 50 0.398 → 0.415.
- **What it did buy is routing** (E6): 0.40 → 0.82, the teacher's level, with every confident routing right. The
  format rows were not what fixed it; the 39,495 teacher-answered routing/slot items were. Hence E7.
- **Export** (as E3): nb8 file 515.6 MB, same contract and op types as the shipped file; parity on 100 test steps
  99/100 operations (the one flip at a PyTorch margin of 0.047) and 48/48 targets, max |Δp| 0.086 / 0.091.
  English-only cut (v2 vocabulary, E4): 198.8 MB plus a 5.7 MB map; 50/50 decisions unchanged, max |Δp| 0.0 against
  the uncut file on re-spelt input. Fixtures `fixture-s11f.json` and `fixture-s11f-en.json` written for the phone
  bench.
- **The nb8 file itself, scored like PyTorch** (`onnx-eval`: laya's ONNX agent on 1,000 random test steps, x86 CPU
  kernels, 811 s): Policy Joint 0.5868 against 0.5868 for the bf16 PyTorch run on the same steps; operation 0.716 vs
  0.715, target top-1 0.657 vs 0.659; the two agree on 987/1,000 operations and 462/475 targets; Type 72.87 both,
  Grounding 59.68 vs 60.47. The 8-bit file loses nothing measurable, so the gate numbers above stand for the phone's
  file too.

### E6 — Friday's other questions: skill routing and slot picks [measured, PyTorch fp32 on the box's CPU]
The same model file also routes requests (`Skills.kt route()`: 16 skills, act at confidence ≥ 0.7, else ask the owner)
and picks slots (`SLOT_Q` over the request's own word spans). `scripts/eval_assistant.py`'s 50 cases (34 phrasings
the rules were written for, 16 paraphrases), through laya on the box (`student.py assistant`); the app-choice part
needs the owner's app list and stayed on the Mac.

| | routing accuracy (seen / paraphrases) | routing at confidence ≥ 0.7: share / right | slot, trimmed spans: exact / overlap |
|---|---|---|---|
| Teacher | **0.82** (0.82 / 0.81) | 60% / **93.3%** | 0.77 / 0.96 |
| **S11** | **0.40** (0.59 / **0.00**) | 52% / **46.2%** | 0.73 / 0.92 |
| **S11-F** (E5) | **0.82** (0.85 / 0.75) | 38% / **100%** | 0.73 / 0.92 |
| S11-A (E7) | [running] | | |

The same on the **English-only cut files, with the request re-spelt as the phone would see it** (`--respell`):

| | routing accuracy (seen / paraphrases) | at confidence ≥ 0.7: share / right | slot: exact / overlap |
|---|---|---|---|
| teacher, cut | 0.80 (0.79 / 0.81) | 58% / 93.1% | 0.77 / 0.96 |
| S11, cut | 0.40 (0.56 / 0.06) | 58% / 37.9% | 0.62 / 0.89 |
| S11-F, cut | **0.78** (0.82 / 0.69) | 32% / **100%** | 0.73 / 0.92 |

- **S11 lost routing.** It was distilled on AndroidControl's two questions only, and the general choice skill the
  teacher kept from base Laya (research/09: 93% right at ≥ 0.7) went with the dropped layers. Worse than the accuracy:
  half of its *confident* routings are wrong, and Friday acts on those without asking. **S11 must not replace the
  teacher for routing as it is.**
- Slots held up (−4 points exact on 26 cases).
- **The fix works:** the teacher's answers to the routing and slot questions over AndroidControl's train requests
  (every unique goal, plus as many step instructions; 26,550 routing and 12,945 slot rows, `cache-assist`), learnt as
  soft targets in one epoch, bring S11-F to the teacher's accuracy. The 50 cases above are not among them. S11-F is
  **less often confident** (38% of requests at ≥ 0.7 against the teacher's 60%), so Friday would ask the owner to
  confirm the skill more often; what it is confident about is right.
- **The English cut costs routing:** the teacher 0.82 → 0.80, S11-F 0.82 → 0.78 with its confident share 38% → 32%;
  slots unchanged. The 50 requests are the owner's phrasing, and words outside AndroidControl's vocabulary are
  re-spelt into pieces. Before a cut file ships, the kept list should also cover the words of the owner's own request
  log (E4's recipe takes any extra ids).

### E7 — S11-A: S11 plus the routing/slot answers, no format rows [running, 2026-10-19]
E5 showed the Friday-format rows bought nothing on the step questions and cost 0.9 points on the trained format,
while the routing/slot items fixed routing. S11-A keeps only the fix: from `ckpt/s11`, one epoch over a random
30,000 trained-format steps (the teacher mix, as a low-lr replay) plus the 39,495 routing/slot items; encoder lr
1e-5, embeddings frozen; temperature refitted on `val`. **Gate:** test Policy Joint within 0.5 of S11's, routing
≥ 0.80 on the 50 cases. Exported and cut only if it passes (`chain_a.sh`). Results: [pending].

## 7. Dead ends and lessons
- **Fine-tuning the student on Friday's format gave nothing** (E5): S11 already handled Friday's format as well as the
  trained one, as the teacher did (research/27), so there was nothing for the format rows to teach; they cost 0.9
  points on the trained format instead. Fine-tune for what is measured missing (routing), not for a format that
  measures level.
- **A student distilled on the task's two questions loses the general choice skill** (E6: routing 0.82 → 0.40). Any
  future student trains with the teacher's answers to Friday's own questions (`cache-assist`); 40k items in one epoch
  restore the skill.
- **The English cut re-spells the owner's words** (E6): −4 routing points on the cut S11-F. Add the request log's
  words to the kept list before shipping a cut file.
- **CPU jobs next to GPU training cost 3–4×.** A CPU parity run (torch + ONNX Runtime threads) on the same box cut
  S11's training from ~10–12 to ~2.6 micro-batches/s, even when pinned to other cores (pinning the trainer itself
  also cost ~30%). laya's training loop is one Python thread that feeds small kernels, so it is sensitive to memory
  and scheduler contention. CPU-heavy work now runs only between trainings, or briefly [measured].
- **A bucket bug** (screens with 0 candidates fell outside the harness's buckets) crashed the first teacher test run
  after its metrics were computed; fixed with a "0" bucket.
