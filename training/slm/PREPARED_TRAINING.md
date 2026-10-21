# Larger prepared-data SLM training

_21 October 2026 · Offline research run on the RTX 4090_

This state/context stage starts from the pinned **SmolLM2-360M-Instruct base**.
It uses full dialogue-state annotations and a small set of independently reviewed
writing references. The earlier 543-row pilot, frozen suites, prompts and installed
Android models remain separate artifacts.

## Inputs and quality checks

- Dialogue source: `out/dialogue-v2-full/`, originally 234,811 train and 25,751
  validation rows. Every row passed independent source, schema, prior-context,
  prompt and split audits. Targets use the separate `dialogue_state_v2` contract.
- Writing source: `out/writing-external/prepared/`, originally 20,617 train and
  1,151 validation rows. Publisher targets and inputs were independently
  reconstructed from pinned TRAIN files.
- The [80-pair semantic review](WRITING_SAMPLE_REVIEW.md) rejected 27 writing
  references. The first blind teacher quality filter failed its fixed calibration
  rules, so no bulk approval or target rewriting was attempted.
- The loader excludes rejected and unreviewed writing references, checks exact
  input/response hashes, and records the retained counts. Prepared totals are not
  automatically training totals.

The reference judge's go/no-go rules are fixed before its outputs: approval
precision at least 0.90 against the 80 AI judgments, rejection recall at least
0.70, and at least 30 of the 53 AI-approved references retained. These compare two
AI judgments; they do not establish human approval or corpus-wide accuracy. The
stratified 80-pair sample deliberately overrepresents summaries and validation.

### Measured first reference-judge calibration

The pinned Qwen3-4B teacher approved 63 of the 80 targets: 46 agreed with the
independent AI review and 17 disagreed. It rejected 10 of the 27 targets rejected
by that review and rejected seven of the 53 approved targets. Approval precision
against the review was **73.02%**, and rejection recall was **37.04%**. The fixed
bulk go/no-go result is **false**. Calibration was scored before any overrides;
the rubric, model, decoding and thresholds were not revised using these outputs.

Consequently this first state/context stage uses only the sample's **23 approved
TRAIN writing references** and **30 approved validation references**. The 27
rejected samples and all 21,688 unreviewed writing references are excluded. The
larger writing experiment needs better screening or cleaner references. This run
uses a **98% state / 2% writing response-token objective**, not the trainer's
general 70/30 default; the small writing set is rehearsal, not broad writing
training.

The tokenized run retains **234,834 training rows and 25,781 validation rows**.
One epoch is one pass through the training rows: **14,678 optimizer updates**
with the final partial group included.

## Training and validation

The run uses response-only full SFT, FP32 weights/optimizer, BF16 autocast and
SDPA. Complete examples stay separate. System/user tokens and padding are masked;
assistant response and EOS tokens are supervised. Token-length checks happen
before optimization, with overlong rows excluded and counted rather than cut.

Hardware calibration at a full 2,048-token sequence passed batch size 4 with peak
allocated memory **23,465,776,640 bytes**. Batch size 8 ran out of memory. The
real weighted-loss run later exhausted memory at step 30, so that calibration
did not establish enough headroom for variable-length training batches.

Training now caps each padded microbatch at **6,000 tokens**. Short examples can
still use four rows; four 2,048-token examples split into two pairs. Nominal
batch size 4, accumulation 4, example order, optimizer groups and their loss
denominators remain unchanged: normally 16 examples per optimizer update.
`PYTORCH_ALLOC_CONF=expandable_segments:True` also reduces fragmentation from
changing allocation sizes; the token limit addresses capacity. See the
[PyTorch CUDA memory documentation](https://docs.pytorch.org/docs/2.14/notes/cuda.html).

From a checkout preserving the repository layout:

```sh
cd training/slm
python3 train_prepared.py --self-check
PYTORCH_ALLOC_CONF=expandable_segments:True bash job.sh train_prepared.py \
  --writing-review out/writing-external/prepared/semantic-sample-review.json \
  --frozen ../../tests/slm-regression.json ../../tests/slm-domain-regression.json \
  --out out/prepared-state-stage1-throughput \
  --batch-size 4 --accumulate 4 --max-sequence 2048 --max-batch-tokens 6000 \
  --learning-rate 2e-5 --writing-share 0.02 \
  --validation-loss-limit 192 --validation-generation-limit 0 \
  --max-steps 2
```

Keep the hash-bound review JSON from the sampled-review report; it is an input
artifact, not something regenerated automatically by the trainer. The raw and
prepared corpora must first be reproduced as described in [DATASETS.md](DATASETS.md)
and [WRITING_DATA.md](WRITING_DATA.md). A flat remote copy must also receive both
frozen manifests and use their actual paths.

The first two optimization steps check real data, GPU memory and recoverability.
Then run the **same command and output directory** with `--resume --max-steps 0`
to finish the epoch. Invocation step limits are excluded from the immutable
objective configuration; data, source, review, batching and loss settings must
match. Preserve every other flag, including validation settings.

Checkpoint selection uses separate validation data. The original 84-case and
24-case Friday suites are reserved for reports after checkpoint selection, and
cannot tune this run. Public validation is held out from this SFT; unknown base
pretraining exposure means it is not guaranteed unseen by the base model.
Dialogue validation supplies human previous states and does not measure errors
accumulating through predicted-state rollouts.
The initial base checkpoint remains eligible if no trained checkpoint improves
the fixed validation objective.

Progress reports include optimizer steps, response tokens, throughput, elapsed
time and ETA. Periodic recovery checkpoints preserve model, optimizer, scheduler,
random state and training position. Keep the exact scripts, source manifests,
review decisions and run configuration to resume the same experiment.

### Preparation speed and launch

The first preparation attempt tokenized about 223 rows/second individually. It
was stopped before any optimizer step, with its directory retained. The new
trainer tokenizes 128 complete chats per call with 16 tokenizer threads. A pinned
512-row probe measured 469 rows/second and exactly matched the single-row token
IDs, masks and validation prefixes in all 16 equivalence checks. The full scan
subsequently exceeded 520 rows/second. Tokenization results are cached and bound
to source, review, template, configuration and code hashes.

The first two-step check then exposed a second bottleneck: serial generated
responses held GPU use near 14% for minutes. Generated diagnostics were moved
after final checkpoint selection. Validation-loss checks still run every 500
steps with a cap of 192 rows: this run has 128 state and 30 approved writing
rows, so each check uses **158 rows**. This changes diagnostic timing, not the validation-loss
objective or the data. The at-most-two warmup updates were replayed from the same
pinned base; both original run directories remain as audit artifacts.

The managed `friday-slm-prepared-state` pipeline uses
`out/prepared-state-stage1-throughput/`. It reuses the verified prepared cache
through hard links, with a new declared configuration and no imported model or
optimizer state from the superseded attempts. Its two-step recovery check passed
before the full-epoch continuation. Following the memory failure, the wrapper
resumes the saved checkpoint directly. The original 84-case and 24-case frozen
reports and a separate prepared-validation generation report run only after completion. The last report
uses the same deterministic selection policy, up to 96 rows, without selection
eligibility. The pipeline survives the local SSH session ending.

The active pipeline SHA256 is
`33fd7163ad85af6d994e9099e333eec8d15d223d2b1347f44f957a50e517ca4c`.
The larger epoch remains on the RTX 4090; the proposed machine migration was
cancelled before any destination installs, data transfer or job changes.

The reference-judge audit and hardware probes are backed up locally as ignored
`out/prepared-quality-audit.zip` (73,447 bytes), SHA256
`009fe67da5aec4f7ce7d98d5980d0a3b7d600538b7b66ea581b827b152eec396`.
The active trainer SHA256 is
`b271afcb5d64cceb362cd1c2bc77f2e60ebd00da41088932dbc7c64c76aa2967`.
No teacher bulk screening was run after the failed calibration.

### Preserving the checkpoint through the memory fix

[migrate_prepared.py](migrate_prepared.py) accepts only the recorded failed OOM
configuration and the reviewed trainer version. Run it with training stopped:

```sh
bash job.sh migrate_prepared.py --run out/prepared-state-stage1-throughput
```

It backs up the original checkpoint and small metadata files, changes only the
trainer identity and `max_batch_tokens=6000`, and checks exact equality of every
other checkpoint field after serialization: weights, optimizer, scheduler,
random state, progress and sampler. Prepared feature file hashes stay unchanged.
The receipt and originals live in `compatibility-token-budget/`; the best model
keeps its original configuration fingerprint in its metadata. Recovery starts
from saved step 2, replaying updates that were lost before the next checkpoint.
Changing kernel shapes and dropout grouping can change subsequent numerical
results; this is a documented execution-policy continuation, not a claim of
bitwise equivalence with the failed run.

## Optuna tracking

The earlier synthetic pilot compared three learning rates and selected `2e-5`.
That does not establish the best rate for this larger corpus. The current full
run records a **single fixed-parameter baseline** in Optuna 4.5.0, so later
experiments can compare against its validation curve and exact source identity.
There is no new parameter search, pruning or automatic adjustment during this
epoch. The trainer itself selects the best checkpoint using held-out loss.

[watch_prepared.py](watch_prepared.py) runs alongside training without loading a
model or using the GPU:

```sh
bash job.sh watch_prepared.py \
  --run out/prepared-state-stage1-throughput \
  --config-sha256 cfd6d194c94dda3996d962deb499b0fdfe60ef351ff446f1be779bf6f9ece2db
```

Launch it under the existing supervisor as `friday-slm-optuna`. Its configuration
pin must match the actual post-migration report; reproducing with another
environment requires that run's own fingerprint. It saves
`optuna/study.sqlite3` and `optuna/latest.json` inside the run directory, binds
source/configuration hashes, and records each weighted validation loss and its
file hash. The retained step-2 validation belongs to the verified checkpoint
lineage. Initial base loss remains in `training.json`.

Stopping the observer leaves training untouched and its trial resumable; restarting
it reuses that trial. A completed epoch records the best validation loss as the
objective. A training failure closes the trial as `FAIL`; a later attempt needs a
separate study/run rather than reopening a finished trial. A second observer is
blocked by a local lock. The fixed 84-case and 24-case Friday reports never feed
Optuna or checkpoint selection.

The installed-library smoke check passed SQLite creation, signal-driven pause,
same-trial resume, intermediate losses, final objective, completed restart,
configuration mismatch rejection and training failure. Both managed processes
were then observed running; the memory-safe trainer passed step 520 and saved a
new recovery checkpoint at step 500. Optuna recorded both step-2 and step-500
validation losses. At step 500 the weighted loss was **0.0669005**, compared with
the initial base's **0.4028262**. State loss was 0.0413922 and writing loss was
1.3168048. These are fixed held-out token losses, not generated-answer accuracy
or Android task success.

Recovery and observer metadata are backed up locally in ignored
`out/prepared-state-recovery-audit.zip` (71,914 bytes), SHA256
`4d6b1d7da94a09d7e05dd939671e914d0a9d67844f8bf76ba779a30ed439b201`.
Its SQLite snapshot passed an independent integrity check. This small archive
contains scripts, configuration, reports and the migration receipt; it does not
contain the active model weights or prepared feature cache.

### Queued reports and their reproduction

The recovery archive preserves the exact managed wrapper as
`out/prepared-state-stage1-throughput-pipeline.sh` and the generated diagnostic
source as `out/prepared-final-diagnostics.py`. Both use the recorded flat job
layout; adjust the archived diagnostic's `root` if moving to a different job
directory. Restore it alongside the run artifacts for the final command below.
`job.sh` places the training directory on Python's module path so scripts
under `out/` can import the shared helpers; its import and incomplete-training
guards were checked while the epoch continued.

After the epoch is complete and its `selected_checkpoint` is verified as `best`,
the queued pipeline performs the following reports (fixture paths shown for a
normal checkout):

```sh
bash job.sh regression.py --cases ../../tests/slm-regression.json \
  --model out/prepared-state-stage1-throughput/best \
  --output out/prepared-state-stage1-throughput/frozen-main.json
bash job.sh regression.py --cases ../../tests/slm-domain-regression.json \
  --model out/prepared-state-stage1-throughput/best \
  --output out/prepared-state-stage1-throughput/frozen-domains.json
bash job.sh out/prepared-final-diagnostics.py
```

The diagnostic verifies selected-weight and cache hashes before reading our own
validation cache. It uses the fixed validation selection policy with a 96-row
cap and writes `final-prepared-validation.json` with `selection_eligible=false`.

### Comparing generated responses with the pinned base

After the queued pipeline releases the GPU, the separate
[evaluate_prepared.py](evaluate_prepared.py) can run the pinned base on the same
validation IDs, cached prompts, 96-row selection policy, BF16/SDPA and 256-token
generation limit:

```sh
bash job.sh evaluate_prepared.py \
  --run out/prepared-state-stage1-throughput --checkpoint base \
  --output out/prepared-state-stage1-throughput/base-prepared-validation.json
```

The helper requires a completed epoch and checks configuration, frozen helper
code, cache hashes and tokenizer IDs before generation. It records model and
artifact hashes, selection IDs, raw responses and scores. Public source messages
are included for writing outputs so semantic review can check the request and
source facts. It refuses to overwrite a report. `--checkpoint selected` can
reproduce the selected-model diagnostic with the same settings into a new file.

This comparison is for reporting after checkpoint selection. Keep generated
state accuracy, writing judgments, old-contract frozen regression and Android
actions separate. The same-subset initial base token loss is comparable with
this run's loss curve; the earlier synthetic pilot's loss uses a different
corpus and objective.

## Instance and artifacts

The [continuous dashboard](MONITORING.md) refreshes training graphs and health
checks every 60 seconds in a separate CPU environment. It reads this run's
configuration-bound reports and filters earlier OOM attempts from the shared
log. The trainer, sampler and optimization configuration remain unchanged.

Run the finite job through the instance's existing supervisor. Keep the instance
running while training. Stopping it interrupts the job; recovery requires a
completed checkpoint and unchanged data/configuration. Destroying the instance
deletes its disk. Preserve the selected weights and audit artifacts elsewhere
before destroying it.

Raw datasets, caches, checkpoints and response logs stay outside Git. Source,
commands, measured configuration and summaries belong in this repo under the
existing contributor identity, without coauthor trailers.

This training run does not deploy a model to Pixel. Generated prose and state
outputs still need evaluation; no language score verifies an Android action.
