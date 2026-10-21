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
