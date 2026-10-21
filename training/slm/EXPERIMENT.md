# Friday text distillation experiment

This experiment tests language interpretation and short text generation separately
from Android app execution. It does not change the installed Writer or vision model.

## Reproduction contract

- Teacher: `Qwen/Qwen3-4B-Instruct-2507`, revision
  `cdbee75f17c01a7cc42f958dc650907174af0554`.
- Student: `HuggingFaceTB/SmolLM2-360M-Instruct`, revision
  `a10cc1512eabd3dde888204e902eca88bddb4951`.
- Environment: the rented image's Python 3.12 / PyTorch 2.14.1+cu130,
  Transformers 4.57.6 and safetensors 0.8.0 in an isolated job venv.
- Tuning: Optuna 4.5.0, with three learning-rate trials and persistent local SQLite
  history. Actual generated answers on a fixed synthetic validation subset select
  the trial; validation loss selects its epoch. No frozen evaluation feedback is
  used for tuning. Mechanical validation scores still do not establish semantics.
- Frozen evaluation: `tests/slm-regression.json`, including the original 24
  `tests/slm-task-updates.json` cases. Evaluation prompts and expected answers are
  fixed before GPU evaluation. They never supply training labels or choose a checkpoint.
- Training: a separate seeded synthetic corpus. Structured teacher answers must
  agree with independently generated expected values. Rejected answers remain in
  the audit report. Writing checks are mechanical and still require semantic review.
- Distillation: supervised fine-tuning on teacher responses, with loss only on the
  assistant answer. Validation loss selects the checkpoint, then the frozen
  regression compares teacher, base student and trained student.

`prompts.py` defines an offline interface shared by training and evaluation.
It is not Android prompt parity. Goal interpretation cases are research inputs;
their outputs cannot select packages, authorize actions or claim task completion.

Optuna documents [SQLite-backed study history and resumption](https://optuna.readthedocs.io/en/v4.5.0/tutorial/20_recipes/001_rdb.html).
The experiment stores its own data, code and model hashes alongside that history;
storing a study alone does not capture a training environment or sampler state.

## Artifact policy

Commit source, original synthetic fixtures, commands and measured summaries.
Keep model weights, cache, generated datasets and raw run artifacts in ignored
`out/`, `.hf/`, `captures/` or `models/`. Never include device transcripts,
personal contacts, credentials or a rented instance's connection details.

The current GPU instance has no persistent host volume. Copy useful reports and
the trained checkpoint off the instance before destroying or recycling it.

## Acceptance

A JSON/schema pass does not establish semantic correctness. Exact extraction,
source-span validation and writing checks are reported separately. Pending human
review is never counted as a semantic pass. Native validator expectations in the
fixtures are metadata until executed in Kotlin.

An experimental student requires a separate GGUF conversion, quantized regression,
chat-template integration and Pixel memory/latency checks before it can replace
Friday's Writer. App task completion still requires evidence from the device.

## 21 October 2026 run

The reproduction code was pushed in checkpoints `e157a58`, `64b7f04`, `6f3292b`
and `bc9b3d1`. `dd99ac0` adds the full teacher review, expanded workflow catalogue
and a separate 24-case domain suite. Both test suites are evaluation only. The
domain suite was authored after the first teacher results were visible, so it is
additional coverage rather than a blinded benchmark.

The original 84-case teacher run generated every response. It took 181.290 seconds
including loading, with 168.352 seconds inside generation. There were 83 complete
JSON objects, 19/73 exact structured agreements and 8/11 writing mechanical passes.
The [case-by-case AI review](TEACHER_REVIEW.md) distinguishes conservative source
span disagreements from wrong dates, invented patches and lost dependencies.
These scores do not establish Android task completion.

### Candidate and label audit

The seeded corpus contained 1,200 train and 240 validation candidates:

| Candidate file | SHA-256 |
|---|---|
| Train | `fc5e2a3873800f42e71b9653721b8a245c9ce7670486cde0d480744238fe60e8` |
| Validation | `158f17dc2a0325cd50ff7dc317cb6ce6b16c24b8ef1c0a55078749494ae14db1` |

An initial batch-8 labeling pilot was stopped after 720 candidates when GPU
profiling showed spare capacity. Its atomic records/source remain separate and
none of those targets were mixed into the final run. The same candidates were
replayed at batch 64: 1,440 responses in 234.041 seconds including setup, with a
mean generation batch of 9.527 seconds. One live sample measured 93% GPU utilization
and about 14 GB device memory. Peak PyTorch allocated memory was 13,028,546,560 bytes.
Utilization samples and allocator peaks are different measurements.

| Family | Candidates | Accepted before independent writing review |
|---|---:|---:|
| Saved-task patches | 600 | 196 |
| Intent | 360 | 217 |
| Research goal labels | 240 | 19 |
| Writing | 240 | 234 |
| **Total** | **1,440** | **666** |

All 234 accepted writing targets were independently AI-reviewed against their
source and instruction. 193 were approved and 41 rejected. Rejected emails turned
an intention to inspect something into an unsupported scheduled inspection and
dropped the inspecting speaker. The gate excluded all 41 without rewriting them.
There were no unreviewed writing targets entering loss.

The final training split has **543 rows** (377 structured + 166 approved writing);
validation loss uses **82 rows** (55 structured + 27 approved writing). The original
unfiltered validation candidates remain available for generated-response scoring,
so teacher rejections are not removed from Optuna's response objective.

The exact review-file SHA-256 is
`a948154d1576e3a6c54b2da31780493969e9e582b0a218ebd5108f41dabc9b4f`.
The launched label script snapshot has SHA-256
`5103d0f48e16691866e23c1741e1c18249e14a2d51cee1712ee316cbec3b8a4c`;
training uses the committed script after validation/review gate additions. Shared
prompts and checker are unchanged. The label snapshot is retained with raw artifacts.

### Training configuration

The actual bounded Optuna run uses three learning rates (`2e-5`, `5e-5`, `1e-4`),
one epoch each, batch 16 and accumulation 1 (effective batch 16), SDPA and seed
20261021. It trains full FP32 weights with BF16 autocast and response-only loss.
The actual SmolLM2 fast-tokenizer check confirmed all prompt tokens were masked
and the assistant EOS token was supervised. The fixed 48-case generated-response
subset is selected from the original 240 validation candidates, not either test suite.

For the flat job layout used in this run, after uploading the separately generated
candidate files, frozen test files and writing review:

```sh
bash job.sh distill.py label --train out/data/train.jsonl \
  --validation out/data/validation.jsonl --regression tests/slm-regression.json \
  --out out/labels-b64 --batch-size 64
bash job.sh distill.py train --data out/labels-b64 --out out/study \
  --trials 3 --epochs 1 --batch-size 16 --accumulate 1 \
  --validation-generation-limit 48 --validation-candidates out/data/validation.jsonl \
  --writing-review out/writing-review.json
```

See the [README](README.md) for the full checkout workflow and artifact backup.
Online dialogue samples and repaired training labels are separate next-stage data;
they do not change the baseline candidates, frozen tests or this study.

### Completed Optuna and regression results

| Trial | Learning rate | Generated validation checks /48 | Validation loss | Training invocation seconds |
|---|---:|---:|---:|---:|
| 0 | `5e-5` | 21 | 0.079249 | 38.617 |
| **1, selected** | **`2e-5`** | **24** | **0.089246** | **34.329** |
| 2 | `1e-4` | 20 | 0.122656 | 28.921 |

The response objective selected trial 1 even though trial 0 had lower validation
loss. Selection was completed before the final student regression. Each trial
performed 34 optimizer steps. The training set contained 20,619 supervised
response tokens and a maximum sequence of 405 tokens; validation had 3,141
response tokens and a maximum sequence of 403. These are small-data pilot numbers.
Peak PyTorch allocation was approximately 20.2 GB, separate from cached/reserved
device memory. No trial ran out of memory.

**Mechanical acceptance, not semantic or device success:**

| Frozen family | Cases | Teacher | Base student | Selected student |
|---|---:|---:|---:|---:|
| Main: saved-task patches | 24 | 8 | 3 | 3 |
| Main: intent | 20 | 9 | 0 | 4 |
| Main: research goal interpretation | 29 | 2 | 0 | 0 |
| Main: writing | 11 | 8 | 0 | 9 |
| **Main total** | **84** | **27** | **3** | **16** |
| Additional domains: saved-task patches | 6 | 0 | 2 | 1 |
| Additional domains: intent | 5 | 0 | 0 | 1 |
| Additional domains: writing | 13 | 7 | 0 | 9 |
| **Additional total** | **24** | **7** | **2** | **11** |

The selected student still fails all 29 exact high-level plans and remains weak
on saved-task patches. Passing more JSON checks does not make it a production
planner. Source-span variants and writing meaning need the separate AI reviews;
financial/fitness omissions and invented facts must not be hidden by totals.
The installed Friday models were not changed.

The selected FP32 safetensors weight file has SHA-256
`fa5dfcafff5015901cde136d826c42f35b4fbe3ae2a93efe5cda48a56b8dd526`.
The lossless model archive has 1,345,093,652 bytes and SHA-256
`f201ddbabc201afe198bc2e1ee3cb135387bfb5b168195fb3f7b13ad0778c48a`.
The six raw regression reports, full label/review audit, study SQLite and scripts
were copied to ignored local captures. Model-backup completion must be verified
against this archive checksum before relying on the local copy.

### Rejected-training-response correction pilot

[repair_labels.py](repair_labels.py) performs one bounded correction attempt on
rejected **training** targets. The executed 64-case pilot sampled 16 from each
family; no evaluation or validation prompt entered its feedback. It retained
original responses, rejection reasons, teacher-only normalization hints, repair
prompts, raw answers and parent/source hashes in a separate directory.

Only **1/48 structured repairs** passed the unchanged independent gold/source
gate. All 16 prose repairs passed mechanical checks and were subsequently
individually AI-reviewed with fresh response hashes: their bodies preserved the
original first-person inspection intention and absence of approval. All 16 were
approved as next-stage targets. They were not promoted into this study. This result does
not support automatic teacher rewriting as a reliable repair method. Use the
retained rejection analysis, independently constructed gold and the separately
prepared human-annotated dialogue data for the next experiment.

```sh
bash job.sh repair_labels.py --train out/data/train.jsonl \
  --labels out/labels-b64 --writing-review out/writing-review.json \
  --out out/repairs-next --limit 64 --batch-size 64
```

The [dataset report](DATASETS.md) records the complete downloaded public TRAIN
corpus and its source pins. Raw dialogue counts are not automatically valid Writer
examples; the next state-tracking protocol remains separate from this pilot.
