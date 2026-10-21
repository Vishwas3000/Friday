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

## Training and validation

The run uses response-only full SFT, FP32 weights/optimizer, BF16 autocast and
SDPA. Complete examples stay separate. System/user tokens and padding are masked;
assistant response and EOS tokens are supervised. Token-length checks happen
before optimization, with overlong rows excluded and counted rather than cut.

Hardware calibration at a full 2,048-token sequence passed batch size 4 with peak
allocated memory **23,465,776,640 bytes**. Batch size 8 ran out of memory. The
initial configuration is batch 4, accumulation 4, one epoch and learning rate
`2e-5`; the actual weighted-loss trainer must also pass its GPU smoke check.

From a checkout preserving the repository layout:

```sh
cd training/slm
python3 train_prepared.py --self-check
bash job.sh train_prepared.py \
  --writing-review out/writing-external/prepared/semantic-sample-review.json \
  --frozen ../../tests/slm-regression.json ../../tests/slm-domain-regression.json \
  --out out/prepared-state-stage1-batched \
  --batch-size 4 --accumulate 4 --max-sequence 2048 \
  --learning-rate 2e-5 --writing-share 0.02 \
  --validation-loss-limit 192 --validation-generation-limit 96 \
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

The managed `friday-slm-prepared-state` pipeline uses
`out/prepared-state-stage1-batched/`. It automatically runs two real optimization
steps, verifies the stopped status and recoverable checkpoint, resumes the same
configuration for the full epoch, and runs the original 84-case and 24-case frozen
reports only after completion. The pipeline survives the local SSH session ending;
no model evaluation result changes checkpoint selection.

The reference-judge audit and hardware probes are backed up locally as ignored
`out/prepared-quality-audit.zip` (73,447 bytes), SHA256
`009fe67da5aec4f7ce7d98d5980d0a3b7d600538b7b66ea581b827b152eec396`.
The active trainer SHA256 is
`83b7aea989532b30e6cd82b6730d3e5e2ad2cf0a22989f6438352d7bad39fff5`.
No teacher bulk screening was run after the failed calibration.

## Instance and artifacts

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
