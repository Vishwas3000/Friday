# Friday text model regression and distillation

This folder compares a pinned teacher and small student, generates separate
synthetic training data, and trains a student on accepted teacher responses.
[EXPERIMENT.md](EXPERIMENT.md) holds the experiment contract and measured results;
[TEACHER_REVIEW.md](TEACHER_REVIEW.md) records interpretation and prose review limits.
The [expanded app scenario catalogue](../../research/21-friday-use-case-catalogue.md)
defines separate proposed device acceptance work; it is not another model score.
[DATASETS.md](DATASETS.md) records primary online sources, complete pinned
SGD/MultiWOZ train downloads and the separate human-annotated `dialogue_state_v2`
adapter. The full remote preparation contains **234,811 train / 25,751 validation
rows**; the corrected independent audit passed all **260,562 rows**, including
source/context/schema and exact SYSTEM-prompt/hash bindings. A 50,000-row local
subset also passed independent audits. These data remain outside the original
synthetic pilot; [train_prepared.py](train_prepared.py) implements their separate
trainer. [PREPARED_TRAINING.md](PREPARED_TRAINING.md) records the larger state/context
stage, recovery and measured hardware checks.
[WRITING_DATA.md](WRITING_DATA.md) documents another **21,768 publisher-reference
writing pairs**: 19,697 grammar corrections and 2,071 summaries, split into 20,617
future train / 1,151 validation rows. Its 81,531 raw TRAIN records and filtered
outputs remain separate. The [sampled reference review](WRITING_SAMPLE_REVIEW.md)
found semantic errors, and the blind Qwen filter failed calibration. The larger
state/context stage therefore includes only 23 independently approved training
writing references and 30 validation references; the other writing targets are
excluded. A larger writing experiment needs better reference screening.

These are **offline language experiments**. They do not change the installed
Writer or test Android app navigation, sending, calendar writes, research collection
or booking completion. Shared [prompts.py](prompts.py) is a research interface, not
Friday's production prompt or constrained grammar.

## Files and split rules

| File | Purpose |
|---|---|
| [tests/slm-regression.json](../../tests/slm-regression.json) | Frozen evaluation across task updates, writing, intent and goal interpretation |
| [tests/slm-task-updates.json](../../tests/slm-task-updates.json) | Original 24 context cases, included in the frozen regression |
| [make_data.py](make_data.py) | Seeded synthetic train/validation candidates with independent structured gold |
| [regression.py](regression.py) | Strict JSON, schema, source spans, normalized values, gold and writing canaries |
| [distill.py](distill.py) | Teacher labeling, writing review gate, response-only full SFT and bounded Optuna study |
| [probe.py](probe.py) | Separate four-case development smoke probe |
| [fetch_data.py](fetch_data.py) | Pinned, verified SGD/MultiWOZ official TRAIN download, eight workers and a 1 GB cap |
| [dialogue_data.py](dialogue_data.py) | Separate human-annotated `dialogue_state_v2` preparation, grouped splits and target-schema gates |
| [verify_dialogue_data.py](verify_dialogue_data.py) | Independent standard-library reconstruction audit against the original TRAIN sources |
| [writing_data.py](writing_data.py) | Pinned CoEdIT/DialogSum TRAIN references for a future writing loader; lexical filtering and grouped splits |
| [review_references.py](review_references.py) | Blind teacher reference screening with fixed calibration gates and hash-bound decisions |
| [train_prepared.py](train_prepared.py) | Larger prepared-data SFT, batched tokenization, reviewed writing, live progress and recoverable checkpoints |
| [migrate_prepared.py](migrate_prepared.py) | Verified upgrade of the recorded OOM checkpoint to token-budget batching, with original state and a receipt preserved |
| [watch_prepared.py](watch_prepared.py) | CPU-only Optuna record of the fixed prepared run, persistent validation losses and completion/failure state |
| [monitor_prepared.py](monitor_prepared.py) | Read-only health checks, validation curves and graph refresh alongside training |
| [plot_prepared.py](plot_prepared.py) | Matplotlib PNG/SVG and a self-contained HTML dashboard from recorded metrics |
| [sync_prepared.py](sync_prepared.py) | Verified SSH transfer of the live dashboard to local storage |
| [evaluate_prepared.py](evaluate_prepared.py) | Post-selection base/selected comparison on identical prepared validation IDs, with public writing inputs for semantic review |

[MONITORING.md](MONITORING.md) explains the six graphs, continuous checks and
isolated plotting setup. Graphs use recorded losses and runtime metrics; generated
answers and Android actions still require their separate evaluations.
Versioned graph snapshots and their numeric data are in
[reports/prepared-state-stage1](reports/prepared-state-stage1/README.md).

Frozen evaluation never supplies labels, tunes prompts, selects a checkpoint or
drives Optuna. Candidate train and validation use separate template/value pools;
the generator checks exact prompt overlap with each other and frozen evaluation.
Those checks do not prove semantic independence or broad generalization.

Structured responses must match independently defined gold and pass schema/span
checks. Query and intent cases distinguish a search target from app names, command
words and library scopes; cited text remains data, without authority to operate an
app. Writing checks cover literal facts, numbers, IDs and lexical negation, with
independent AI review before a writing target can enter training loss. A mechanical
pass or AI approval is not human semantic certification or app-task success.

## 1. Prepare the checkout and environment

Keep the repository layout, including `tests/`. Commands below start at the repo
root, then stay in `training/slm/`:

```sh
cd training/slm
python3 probe.py --self-check
python3 regression.py --self-check
python3 make_data.py --self-check
python3 distill.py self-check
```

These focused checks and candidate generation need only Python's standard library;
they can run locally. GPU inference and training run on the rented machine. On its
PyTorch image:

```sh
bash setup.sh
```

Setup creates `.venv` with `--system-site-packages`, inheriting PyTorch/CUDA from
`/venv/main`, and locally installs Transformers **4.57.6**, safetensors **0.8.0** and
Optuna **4.5.0**. No global packages or drivers change. Reports record actual
PyTorch/CUDA versions. The pilot uses a 24 GB RTX 4090; CUDA BF16 support is required.
Teacher inference is BF16; full student SFT uses FP32 weights/optimizer with BF16
autocast. There is no CPU training fallback.

For long runs, submit the following `bash job.sh ...` commands to the instance's
existing supervisor, following its guide. Retain its job/PID/log metadata and watch
the emitted JSON progress. `job.sh` selects the job venv, unbuffered output and
bounded CPU threads; it is not itself a supervisor. Run teacher and student jobs
sequentially so the teacher exits and releases GPU memory before training.

**Flat remote copies:** `make_data.py` resolves fixtures from the repo's `tests/`
directory; copying only that script into a flat job directory will break it.
Preserve the checkout, or generate data from the local checkout and upload the
entire `out/data/` with its manifest. For flat inference/training jobs, upload the
frozen manifest too and pass its actual path explicitly via `--cases` and
`--regression`; the relative paths below assume the preserved layout.

## 2. Freeze data, then measure teacher and base student

Generate 1,200 train and 240 validation candidates once. Existing output files
are deliberately not overwritten:

```sh
python3 make_data.py --output-dir out/data --rows 1440 --seed 20261021
```

Keep `out/data/manifest.json`: it records counts, hashes, split templates and frozen
fixture hash. Smaller `--rows` picks prefixes of the same seeded split streams for
throughput pilots; it does not create a new distribution from evaluation feedback.

Run both models against the same frozen manifest, prompts and generation settings:

```sh
bash job.sh regression.py --cases ../../tests/slm-regression.json \
  --model Qwen/Qwen3-4B-Instruct-2507 \
  --revision cdbee75f17c01a7cc42f958dc650907174af0554 \
  --output out/teacher-regression.json
bash job.sh regression.py --cases ../../tests/slm-regression.json \
  --model HuggingFaceTB/SmolLM2-360M-Instruct \
  --revision a10cc1512eabd3dde888204e902eca88bddb4951 \
  --output out/base-regression.json
```

Outputs retain raw responses, rendered prompts, revisions, timing, truncation,
per-family checks and pending review. Do not repair fenced/partial JSON or change
the frozen cases in response to these results. Native guard expectations in the
fixtures are unmeasured metadata until exercised in Kotlin.

## 3. Label and independently review writing targets

```sh
bash job.sh distill.py label \
  --train out/data/train.jsonl --validation out/data/validation.jsonl \
  --regression ../../tests/slm-regression.json --out out/labels --batch-size 8
```

Structured labels need exact independent gold, schema, provenance and value checks;
bad or truncated responses are rejected without repair. Writing labels first pass
mechanical checks. Raw accepted/rejected responses remain in atomic
`out/labels/label_batches/*.json`; accepted rows become `train.labels.jsonl` and
`validation.labels.jsonl`. The label manifest records source/code/model hashes.

`--resume` reuses atomic batches only with identical inputs/configuration. Keep the
exact labeling source version: the recorded whole-script hash is audit metadata,
not part of the automatic resume configuration check. Resume with that original
source, rather than mixing responses produced by changed labeling code.

Independently review **every accepted writing response** against its source,
instruction and rubric, including names, actors, deadlines, uncertainty, negation,
new commitments and claims that actions already happened. Save decisions outside
Git, for example `out/writing-review.json`, using this exact shape with real IDs and
computed hashes in place of the placeholders:

```json
{
  "version": 1,
  "reviewer": "Codex independent agent (AI, not human)",
  "reviews": {
    "<accepted-writing-row-id>": {
      "response_sha256": "<sha256-of-exact-response-UTF8>",
      "approved": true,
      "reason": "Preserves each source fact, actor, qualifier and requested tone."
    }
  }
}
```

Hash the exact `response` string from the accepted JSONL row, including whitespace;
do not hash its parsed/reformatted JSON. `approved` must be a JSON boolean. Unknown
or non-writing IDs, missing reasons and stale response hashes fail the gate.
Rejected and unreviewed writing targets are excluded from **both train and
validation loss**. Without `--writing-review`, all writing targets are excluded;
structured labels are unchanged. Reports identify the AI reviewer, review-file hash
and inclusion/exclusion counts. AI review does not certify the model's semantics.

## 4. Train and select with synthetic validation responses

```sh
bash job.sh distill.py train --data out/labels --out out/study \
  --trials 3 --epochs 1 --batch-size 4 --accumulate 4 \
  --validation-generation-limit 48 \
  --validation-candidates out/data/validation.jsonl \
  --writing-review out/writing-review.json
```

The bounded Optuna grid tests learning rates `2e-5`, `5e-5` and `1e-4`, with the same
seed and data. Validation response loss selects an epoch inside each trial. A fixed
family-stratified subset of the **original validation candidates**, including
teacher-rejected cases, supplies generated-answer checks for trial selection;
its SHA must match the label job. The objective maximizes accepted mechanical/exact
responses. Ties use lower validation loss, then lower trial number. Frozen regression
never chooses a trial or checkpoint. Writing scores still leave meaning for review.

`out/study/study.sqlite3` persists Optuna history; `study.json` records the selected
checkpoint, objective, hashes and trials. Each trial retains its best full weights,
tokenizer, `training.json` and raw validation responses. Assistant response and EOS
tokens are supervised; system/user and padding tokens are masked. Sequences are
bounded without silent truncation. Best inference weights are retained rather than
duplicate epoch/optimizer copies; mid-epoch training resume is unsupported. Repeating
a study reuses completed trials, but stops on an unfinished `RUNNING` trial for
inspection. Keep source, data and review files unchanged throughout the study.

## 5. Evaluate the selected checkpoint once on frozen cases

Read `selected_checkpoint` in `out/study/study.json`. Use that path, rather than
choosing a different trial using frozen evaluation results:

```sh
SELECTED_CHECKPOINT=$(.venv/bin/python -c 'import json; print(json.load(open("out/study/study.json"))["selected_checkpoint"])')
bash job.sh regression.py --cases ../../tests/slm-regression.json \
  --model "$SELECTED_CHECKPOINT" --output out/student-regression.json
```

Compare teacher, base and selected student by family, exact/provenance checks,
writing errors, truncations and raw responses. A gain on these synthetic cases is
not a general reliability claim. This checkpoint is unquantized; GGUF conversion,
quantized regression, production template/grammar integration and Pixel
memory/latency/battery measurements are separate acceptance steps. GPU generation
time excludes loading/tokenization and is not phone or end-to-end app latency.
PyTorch peak allocated bytes are not total process/device memory.

## Separate four-case development smoke

```sh
bash job.sh probe.py --models smollm2 lfm2 qwen3
```

`probe.py` uses the models' chat templates with unconstrained BF16 generation on
duration, combined fields, a short message and negation. Its four cases do not run
the full regression, use Android prompts or establish a release threshold. It
defaults to pinned SmolLM2-360M and LFM2-350M; Qwen3 is optional.

## Next stage: human-annotated dialogue state

This preparation step uses the standard library and original human annotations.
Run from the repo root, keeping the complete source download and prepared data
under ignored `out/`:

```sh
python3 training/slm/fetch_data.py --self-check
python3 -u training/slm/fetch_data.py \
  --output-dir training/slm/out/external-full --workers 8
python3 training/slm/dialogue_data.py --self-check
python3 -u training/slm/dialogue_data.py \
  --sources training/slm/out/external-full \
  --out training/slm/out/dialogue-v2-full --max-rows 300000
python3 training/slm/verify_dialogue_data.py --self-check
python3 -u training/slm/verify_dialogue_data.py \
  --sources training/slm/out/external-full \
  --prepared training/slm/out/dialogue-v2-full
```

Use a fresh output directory; existing prepared data is never overwritten. The
300,000 cap selects available compatible rows rather than inventing more examples.
For the audited smaller subset, use `--max-rows 50000` with
`--out training/slm/out/dialogue-v2-final`. Every current USER frame receives the
same before-turn state snapshot; dialogue groups stay within one split. Duplicate
and conflicting inputs, undeclared target names and invalid categorical values
are dropped without rewriting annotations.

The full remote preparation has **260,562 rows**, totaling **1,377,559,875 JSONL
bytes**. Its corrected independent audit passed with zero schema/enum, annotation,
context or overlap violations, with source-manifest/adapter hashes and every
SYSTEM prompt bound to the recorded adapter. The full files remain remote;
verified metadata is backed up under ignored `out/dialogue-v2-full-metadata/`.
Retain the data on storage with enough space before recycling the remote disk.
The verifier uses normal Python without `-O` and writes `independent-check.json`.

The output predicts full service state from a schema, prior annotated states and
the latest SYSTEM/USER utterances. It is not accepted by `distill.py` or the current
Android Writer. No v2 student has been trained or installed by this preparation
step; the separate [prepared trainer](PREPARED_TRAINING.md) now runs the first
state/context epoch. [DATASETS.md](DATASETS.md#human-annotated-dialogue-state-next-stage-data)
records measured counts, full manifest/report hashes, exclusions and rollout/token limits.

## Writing references

The separate [writing data guide](WRITING_DATA.md) records sources, exact hashes and
filters for **21,768 publisher-reference pairs**. It contains 19,697 grammatical
corrections and 2,071 summaries, rather than teacher-generated targets. From the
repo root:

```sh
python3 training/slm/writing_data.py --self-check
python3 training/slm/writing_data.py --fetch
```

The prepared directory must be empty; the default is
`training/slm/out/writing-external/prepared/`. Its 20,617/1,151 source-grouped
train/validation split supplies candidates for the prepared trainer. The active
state/context stage retains only 23 approved training targets and 30 approved
validation targets after sampled review; the other writing references remain
excluded. Lexical filters and publisher provenance do not establish semantic
accuracy. These rows use a separate manifest from `dialogue_state_v2` and the
original pilot's teacher labels.

## Artifacts and backup

Commit source, original synthetic fixtures, commands and sanitized measured
summaries. Keep weights, `.hf/`, generated data, raw responses, review decisions and
SQLite files in ignored `out/`, local `captures/` or `models/`. Never commit device
transcripts, contacts, credentials or instance connection details.

Before destroying/recycling the instance, copy source/prepared data manifests and
notices, raw label batches, reviews, study files, training/regression reports and
the selected full checkpoint off the rented disk. Put reports in local ignored
captures and weights in ignored models; verify file hashes against the job manifests. Cache can be
downloaded again from pinned revisions; trained weights and audit artifacts need
backup. `/workspace` is persistent only when an actual persistent host volume is
mounted—check the instance guide and capabilities rather than assuming it survives.
