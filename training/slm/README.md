# Small text model GPU smoke probe

Replays four synthetic cases from the first Friday SLM comparison: duration,
combined calendar fields, a short message and a negated message with a reference
code. This is **BF16, unconstrained GPU generation using each model's chat
template**. It does not reproduce Friday's production prompts, grammar, GGUF
runtime, quantization, phone memory or end-to-end task execution.

Run in a job folder on the rented PyTorch image:

```sh
bash setup.sh
.venv/bin/python probe.py
```

Setup inherits PyTorch/CUDA from `/venv/main` through a job-local venv and pins
Transformers 4.57.6 and safetensors 0.8.0. It changes no drivers or global packages.
The probe records actual package/CUDA versions and pinned model revisions. It
requires a CUDA GPU supporting BF16; there is no CPU fallback. Model files go into
the job's ignored `.hf/` cache and reports into ignored `out/`.

SmolLM2-360M and LFM2-350M run by default; select one with `--models smollm2` or
`--models lfm2`. `--models qwen3` runs the pinned Qwen3-4B-Instruct-2507 comparison.
Use `--output PATH` to select a report destination. Reports retain
raw decoded outputs, templates, exact inputs, timings and peak allocated GPU
memory. Generation time excludes loading and tokenization; it is not phone latency.
GPU memory is PyTorch peak allocated memory, not total process/device memory.

JSON validation parses the entire output, rejects fences, duplicate keys and
nonfinite values, and checks the exact required string fields. Duration and
calendar extraction have exact expected fields. Message cases have **no automatic
semantic pass**: review the recorded criteria and raw text for preserved facts,
negation and reference codes. Four cases are a smoke check, not a benchmark or
evidence that a model is ready to ship or distill.

The separate [task-update pilot](../../tests/slm-task-updates.json) has 24 synthetic
evaluation-only cases for Friday's saved-context schema. This four-case probe does
not execute those cases. Keep evaluation cases out of training/model selection;
the fixture metadata distinguishes native routing, native rejection and possible
SLM calls so they are not mislabeled as model failures.

The focused parser/report check needs only Python's standard library:

```sh
python3 probe.py --self-check
```
