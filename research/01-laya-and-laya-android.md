# Laya and Laya-Android: technical deep dive

_Last updated: 2026-10-16 · Trust tags: [primary] read at source · [secondary] search/blog/forum · [measured] run here · [UNCERTAIN] unconfirmed or conflicting_

Scope: what the `laya` package and the Laya-Android checkpoint can and cannot do for a local, adb-driven Pixel 8 agent run from the Mac. Nothing was executed or installed for this note. Code facts come from reading the laya 0.3.28 and 0.4.8 sdists, shallow clones of both GitHub repos (2026-10-16), and Hugging Face / PyPI metadata. "Self-reported" marks a third party's own measurement that I could not check.

## TL;DR

- **What it is.** Laya-Android (`ByunByun/laya-android`, HF revision `5a03b2b`, 2026-10-07) is a Laya checkpoint built on mmBERT-base: 321.9M parameters stored as fp16 safetensors (644 MB). Each step it answers two `choice` questions in one call: `operation` (12 ops) and `target` (an index into the accessibility candidates). It never outputs text or app names. It ships no device runner, no adb code and no live evaluation [primary].
- **How good it is.** Every number is offline and step-level, scored against the gold history:
  - AndroidControl-High Type 76.7 and Grounding 61.7 [primary].
  - Its internal "Policy Joint" metric is 0.66 on IDD and 0.54–0.57 on unseen apps, tasks and categories [primary].
  - It is weak on BACK, WAIT, horizontal scroll and LONG_PRESS. DONE recall is only 0.55–0.75 [primary].
  - Closed-loop task success has never been measured [UNCERTAIN].
- **Input format.** The input must match training byte for byte: `{"goal", "history": last 3 actions (oldest first), "ui": ["[i] <label≤40> (<Class>) <flags>"]}`. The repo's `src/candidates.py` and `src/serialization.py` build it from an AndroidControl-style accessibility forest. We only need an adapter from `uiautomator dump` XML to that node schema. Note that the XML has `hint` but no `editable` attribute [primary].
- **Runtime.** Run it in-process on the Mac with `uv venv --python 3.12`, `laya==0.3.28`, `transformers==4.57.6` and torch 2.14.1. That is the exact stack it was evaluated on.
  - System Python 3.15 is out: torch 2.14.1 ships wheels for cp310–cp314 only [primary].
  - Device order is CUDA→MPS→XPU→CPU. A 2-question call runs fp32 on MPS [primary].
  - I estimate tens of ms per step on Apple GPUs. That is extrapolated from BENCHMARKS.md, not measured on the M5 [UNCERTAIN].
- **Payloads are our job.** OPEN_APP needs an app name and INPUT_TEXT needs text, and both have to come from somewhere else: the task spec, goal parsing, or a small local LLM. The closest GUI fine-tune (laya-browser) uses a local Qwen3-8B to write typed text [primary].
- **On-phone inference.** It is possible in principle, but nothing does it for this checkpoint yet.
  - The official exporter `scripts/export_onnx.py --model <any checkpoint>` writes one fused fp32 graph, about 1.29 GB for mmBERT. Tokenization stays outside the graph (HF `tokenizers` in Python; pure TS, Go and Java BPE ports elsewhere) [primary].
  - laya-java says Android is "not implemented yet" [primary].
  - Community LiteRT ports of the *base* multilingual model run at 36–51 ms per question on a Snapdragon S26, but only at static 256/512-token windows. Laya-Android rows need up to about 3k tokens [primary, self-reported].
- **Community.** Laya-Android has 1 GitHub star, 0 issues and 0 HF discussions (2026-10-16) [primary]. The only adb runner I found, `surapat-codediva/laya-android`, uses *base* Laya. It measured about 2.2 s per `uiautomator dump` on an emulator and found base Laya near chance at picking elements [primary, self-reported].

## 1. The `laya` package

### 1.1 Versions

| | 0.3.28 (Laya-Android's pin) | 0.4.8 (latest) |
|---|---|---|
| PyPI upload | 2026-10-05 | 2026-10-15 |
| wheel / sdist | 360 KB / 995 KB | 405 KB / 1.2 MB |
| `requires-python` | >=3.10 | >=3.10 |
| classifiers | 3.10–3.13 | 3.10–3.13 |
| core deps | torch>=2.0, transformers>=4.48, safetensors>=0.4, huggingface_hub>=0.20, numpy>=1.20 | same |

- **Release history.** There are 46 releases from 0.1.0 (2026-09-18) to 0.4.8. `requires-python` was >=3.8 until 0.3.4 and >=3.10 from 0.3.5 (2026-09-21). No files are yanked [primary, PyPI JSON].
- **What changed from 0.3.28 to 0.4.8.** I compared the two sdists' syntax trees, ignoring docstrings [primary].
  - Unchanged: `serialize_state`, `render_options`, `build_head`, `build_sequence`, `collate_items`, `temp_bucket`, `clamp_temperature`, `DecisionModel`, `build_model` and `Agent._decode_answers`.
  - Changed: `Agent.__init__` (temperature warnings), `_encode_state` (an equivalent option count), `predict_long` and `load` (a type annotation).
  - So 0.4.8 should give Laya-Android the same answers. I did not run it to confirm [UNCERTAIN].
- **Extras.** `[serve]` (fastapi, uvicorn), `[onnx]` (onnx, onnxruntime, onnxscript), `[fast]` (TileLang, CUDA only), `[mcp]`, `[structured]` and integrations for LangChain, LlamaIndex and CrewAI [primary].
- **CLIs.** `laya`, `laya-serve`, `laya-evals`, `laya-mcp-server`, `laya-train` [primary].

### 1.2 Python, wheels and the uv pin (as of 2026-10-16)

- **torch** 2.14.1 (2026-09-30) has wheels for cp310–cp314 and cp314t on macOS arm64 and win_amd64. It has **no cp315 wheels** [primary, PyPI JSON].
- **onnxruntime** 1.31.0 needs Python >=3.11 and has cp311–cp314 wheels only [primary].
- **numpy** 2.5.4 needs Python >=3.12 [primary].
- **tokenizers** 0.23.3 and **safetensors** 0.8.0 ship abi3 (cp310+) wheels [primary].
- **transformers** 4.57.6 (2026-01-16) is the last 4.x release [primary]. Laya-Android's `requirements.txt` pins `transformers>=4.48,<5` and records "Tested: Python 3.10, torch 2.14.1+cu130, transformers 4.57.6" [primary].
- **Docs.** The laya README itself shows `uv venv --python 3.12` and then `uv pip install laya`. For specific torch builds it adds `--torch-backend=auto|cpu` [primary].
- **Pin: Python 3.12.** Every dependency has 3.12 wheels on macOS arm64 and on Windows. The laya-go parity lane pins 3.12, and the M5 Pro benchmark ran on it [primary].

### 1.3 Install footprint

- **Wheels.** For macOS arm64 cp312: torch 127 MB, transformers 12 MB, numpy 5.5 MB, tokenizers 3.1 MB, safetensors 0.5 MB, laya 0.36 MB. The PyPI win_amd64 torch wheel is 124 MB and CPU-only; CUDA builds come from the PyTorch index [primary, PyPI JSON]. I did not measure installed size [UNCERTAIN].
- **Model download.** `laya.load()` fetches only `rl_agent_config.json`, `model.safetensors`, `tokenizer/*` and `encoder/*`. For Laya-Android that is 678 MB, of which 644 MB is weights and 34 MB is the 256k-vocab `tokenizer.json`. The HF repo also holds 2 MB of figures and evals that are not fetched [primary, HF API].
- **Memory.**
  - The weights are 170 fp16 tensors. The embedding table alone is 256,000 × 768 (196.6M parameters). The head is 15.0M parameters [primary, safetensors header].
  - `build_model` creates fp32 modules and loads the fp16 weights into them, so expect about 1.3 GB resident for the parameters [primary code; RSS not measured].
  - Peak VRAM at batch 1 was 1.66 GB on an RTX 4090 [primary].

### 1.4 Device selection on Apple Silicon, and latency

- **Device order.** Automatic order is CUDA → MPS → XPU → CPU. Override it with `device=` (`"mps"`, `"cpu"`, …). A requested but unavailable device falls back to CPU with a warning [primary, `agent.py`].
- **Precision on MPS.**
  - MPS autocasts to fp16 only when a call has at least `LAYA_MPS_AMP_MIN_ROWS` question rows (default 5). Laya-Android's 2-question call therefore runs fp32 [primary].
  - CPU stays fp32 unless `LAYA_CPU_AMP=bf16`. CUDA uses the checkpoint's `amp_dtype` (bf16), or `LAYA_CUDA_AMP` [primary].
- **One row per question.** Each question is its own sequence that carries the full state, so a step costs 2 rows [primary, `_encode_state`].

Measured MPS and CPU numbers from the official BENCHMARKS.md, all on the **base multilingual** checkpoint (same encoder as Laya-Android), median ms per call [primary]:

| hardware (laya, torch) | rows / total input tokens | fp32 | fp16 |
|---|---|---|---|
| Apple M5 Pro 24 GB (0.4.6, 2.14.1) | 1 / 88 | 12.1 | 13.4 |
| Apple M5 Pro | 2 / 572 | 36.4 | 25.0 |
| Apple M5 Pro | 4 / 1,137 | 69.0 | 40.4 |
| Apple M5 Pro | 8 / 2,258 | 123.3 | 65.2 |
| Apple M1 Pro 16 GB (0.3.26, 2.14.0) | 2 / 572 | 78.5 | 86.0 |
| AMD EPYC 9R14, 4 cores, fp32 (0.3.20) | 1 question, short state | 193 | – |

- **fp16 on MPS.** On the M5 Pro it changed 0 of 1,248 decisions on the multilingual model and 1 on the English model. The largest probability shifts were 0.0050 and 0.0126. On the M1 Pro the multilingual shift reached 0.0206, and fp16 was *slower* there [primary].
- **First call.** It pays Metal kernel compilation, about 13 s on an AMD-GPU Mac Pro, so warm up before use [primary, README]. Another adb runner on an Apple Mac measured the first decision at 0.3–1.8 s and warm decisions at 0.2–0.26 s, on a busy 8-core Mac host [primary, self-reported].
- **No concurrency on MPS.** "Concurrent torch forwards abort the process" on MPS. Keep one inference at a time [primary, README].
- **CPU threads.** Set intra-op threads to about the physical core count and inter-op threads to 1. On a Ryzen laptop under WSL2 that took a 3-question call from 9,396 ms to 783 ms [primary]. This matters for the Windows box.
- **Laya-Android estimate.** A median step is about 2 rows totalling ~730 tokens (state p50 218 tokens, target head p50 193 tokens, my estimate of ~100 tokens for the operation head). Interpolating the M5 Pro rows gives roughly 45 ms fp32 per step. A base M5 has fewer GPU cores than an M5 Pro, so expect more. Measure it [UNCERTAIN].

### 1.5 The API we will use (0.3.28; signatures unchanged in 0.4.8)

```python
laya.load(model_id_or_path="convaiinnovations/laya", device=None, token=None, subfolder=None,
          fast=False, compile=False, revision=None, expected_sha256=None, lang_temperatures=None,
          hooks=None, ..., calibration=None, backend=None, onnx_path=None, ...)  # -> Agent | ONNXAgent
Agent.system_one(state, questions, lang=None, hooks=None, ..., max_len=None, head_max_len=None,
                 min_confidence=None) -> dict
Agent.predict = system_one          # alias in both versions
Agent.predict_batch(states, questions, batch_size=None, lang=None, ..., max_len=None,
                    head_max_len=None, sort_by_length=False, min_confidence=None) -> list[dict]
```

- **`system_one(state, …)`** is literally `predict_batch([state], …)[0]` [primary].
- **`predict_batch`** packs the rows of many states into shared forward passes, and returns results in input order [primary]. That is useful only for offline evaluation here.
- **`predict_long`** windows a long state. It does not suit Laya-Android: the options sit in the head, and windowing would split the `ui` list [primary + inference].
- **`decide`** turns a JSON schema or pydantic model into questions [primary].
- **`load` with a Hub id** calls `snapshot_download` with an allow-list. A string containing `/` is treated as a path or Hub id, not as a registry alias [primary].
  - `revision=` pins the Hub commit, and `LAYA_REVISION` does the same from the environment [primary].
  - `expected_sha256={"model.safetensors": "<hex>"}` checks files before parsing them [primary].
  - Only the checkpoint's own `encoder/config.json` is read, so mmBERT-base itself is never downloaded [primary, `build_model(pretrained=False)`].
- **Sequence layout** for each question row [primary, `common.build_sequence`]:

  `[CLS] "<type> question: <instructions>" [SEP] [MASK] opt0 [MASK] opt1 … [SEP] <state> [SEP]`

  - The state is `json.dumps(state, ensure_ascii=False)` in dict insertion order.
  - A dict or string state is cut **from the right** when too long. Only list states keep their tail.
  - Each option is capped at 48 tokens. If all the options together overflow `head_max_len`, each one is re-capped to `max(4, (head_max_len-16)//n)` tokens.
  - If the option markers do not all fit in `max_len`, the call raises `ValueError`.

### 1.6 Question schema

- `choice`:
  - Format: `{"type": "choice", "instructions": str|dict|list, "criteria": {label: description|null} | [labels]}`.
  - Options render as `"label: description"`, or just `"label"` when there is no description.
  - Labels must be unique scalars; `1`, `1.0` and `True` count as the same key. Null labels are refused [primary, `_check_question`].
- `score`: `{"type": "score", "instructions": …, "criteria": [level0, level1, …]}`. Levels render as `"level i: <text>"` [primary].
- `noul` (yes/no):
  - Format: `{"type": "noul", "instructions": …, "criteria"?: {"true": …, "false": …}, "labels"?: {"false": "B", "true": "A"}}`.
  - The answer is always P(true). `labels` is accepted only for `noul` [primary].
- `option_order` (any type) must be a permutation of `range(n)`: slot s shows option `order[s]`. Probabilities come back in label order [primary].

### 1.7 Output schema

```json
{"model": "laya-rl-agent",
 "answers": {"operation": {"type": "choice", "choice": "CLICK",
               "probabilities": {"CLICK": 0.9347, "...": 0.0},
               "confidence": 0.71, "answer_confidence": 0.9347,
               "action": {"act_probability": 1.0}}},
 "usage": {"input_tokens": 0, "output_tokens": 0, "state_tokens": 0,
           "state_tokens_dropped": 0, "truncated": false, "truncated_questions": []}}
```

- **Two confidence fields** [primary].
  - `answer_confidence` = max(p). This is the quantity that temperature scaling fits.
  - `confidence` = 1 − H(p)/log k (normalised entropy). It is *not* calibrated.
  - Never compare the two against the same threshold.
- **Scores and probabilities.** `score` answers add `score` (the expected level index) and `legend`. `noul` answers carry `noul` = P(true). Probabilities are rounded to 4 decimals [primary].
- **`usage.options`** appears only when options collapsed to the same token span [primary]. That is unlikely for Laya-Android: each target option starts with its own index (`"4: …"`), and even with 246 candidates every option keeps at least 4 tokens [inference].
- **Gating.** With `min_confidence=` (a number, or a map like `{"choice:11+": 0.6, "default": 0.4}`) every answer gains `abstention` (`passed`, `abstained` or `unevaluated`) and `abstention_threshold`. Abstained answers also get `low_confidence: true`. The answer itself is kept [primary].
- **`act_probability`** comes from the act head. A converter reports it saturated at 1.0 on every one of 201 rows for the base model. Do not use it as an escalation signal [primary, self-reported, LiteRT card].

### 1.8 Calibration and temperatures

- **Where temperatures come from.** Each checkpoint's `rl_agent_config.json` carries `temperature`, a list for [choice, score, noul], and `temperature_by_options`, keyed `choice:2`, `choice:3-5`, `choice:6-10`, `choice:11+`, `score:…` and `noul:2`.
  - The bucket value wins, with the per-type value as fallback.
  - Every value is clamped to [0.5, 5.0]. The clamp landed 2026-09-21 (#42).
  - Logits are divided by T before the softmax [primary].
- **Overrides and fitting.** `load(calibration="cal.json")` overrides the shipped temperatures. `lang_temperatures=` sets them per language. `agent.fit_temperatures(records)`, `fit_binning`, `save_calibration` and `load_calibration` refit and store them [primary].
- **Laya-Android ships** `temperature = [1.1609, 1.0, 1.0]` and `temperature_by_options = {}`. Both of its questions therefore use T = 1.161, whatever the option count. It was fitted on AndroidControl validation [primary].
- **Base checkpoints.** The English checkpoint ships `choice:11+ = 0.1006`. After the clamp that becomes 0.5 and is still over-confident (laya #394, HF discussion #2). The base multilingual checkpoint ships no fitted temperatures [primary + secondary]. Neither applies to Laya-Android.

### 1.9 `laya-serve`

- **Install and run.** `uv pip install "laya[serve]"`, then `laya-serve` listens on `0.0.0.0:8000`. It speaks TypeSafe Jev's `/v1/systemone` wire protocol [primary, `docs/http-api.md`].
- **Endpoints** [primary]:
  - `GET /health` reports `status`, `loaded`, `revisions`, `device`, `checkpoint_devices` and `cpu_fallbacks`. The detail fields need the bearer token when `LAYA_API_KEY` is set.
  - `POST /v1/systemone` takes `{"state", "questions", "model"?, "task"?, "lang"?, "lang_guess"?, "max_len"?, "head_max_len"?, "min_confidence"?}`. It returns the §1.7 output plus `x_jev_confidence` on choice and score answers, and a root `routing` block.
  - `POST /v1/systemone/batch` takes `{"states": [... up to 64], "questions": {...}}` and returns `{"results": [...], "total_usage": {...}}`.
- **Serving a fine-tune.** Register it with `LAYA_EXTRA_MODELS='{"android": "<dir or ByunByun/laya-android>"}'` and send `"model": "android"` [primary]. Without `model` the Router picks the english or multilingual base by language, which is wrong for us [primary]. Whether `LAYA_MODELS=android` preloads an extra name I inferred from the code, not tested [UNCERTAIN].
- **Limits** [primary]:
  - body 2 MiB; state 50,000 characters; 64 questions per request; 512 options across all questions.
  - **100 options per `choice`.** `LAYA_TOURNAMENT_GROUP` raises it to 255, but it answers by elimination in groups, a different decision procedure from the one trained.
  - `LAYA_MAX_CONCURRENT` (16) requests admitted at once, and a single inference worker.
- **Verdict.** For Laya-Android the server adds a hop and the 100-candidate cap. In-process is simpler.

### 1.10 ONNX path, tokenization, and what it means for an Android app

- **How `laya.onnx` is made** [primary, `scripts/export_onnx.py`].
  - The script lives in the GitHub repo, not the sdist, and needs `laya[onnx]`.
  - Command: `python scripts/export_onnx.py --model <hub id or local ckpt> --output laya.onnx [--quantize]`.
  - It loads `Agent(..., device="cpu")` and runs `torch.onnx.export` at opset 18.
  - Inputs are `input_ids, attention_mask, marker_pos, marker_mask, qtype`; outputs are `logits, act_logits`. Batch, sequence and marker dimensions are dynamic.
  - Weights stay fp32 in a `.data` sidecar. "Fused" means one graph containing encoder and head, as opposed to the older split `encoder.onnx + head.onnx`. The fused multilingual graph is 1.29 GB: a 2.8 MB graph plus external weights [primary, `laya-java/MODELS.md`].
  - `--quantize` writes an INT8 dynamic-quantized copy that runs on CPU only. The docstring warns it "trades real accuracy": on held-out choice decisions the multilingual model agreed with eager inference only 83% of the time (#790) [primary].
- **Can a fine-tune be exported?** The script accepts any checkpoint id or path, so `--model ByunByun/laya-android` should export [primary code]. Not tried. The repo's ONNX long-sequence tests use stub sessions, so there is no real-graph parity check at 1–3k tokens [primary; result UNCERTAIN].
- **The Python ONNX path.** `laya.load(ckpt, backend="onnx", onnx_path=...)` returns an `ONNXAgent` with the same `system_one`, `predict` and `predict_batch`. It downloads only the config, tokenizer and encoder config. Three caveats [primary, `onnx_agent.py`, `common.py`]:
  - it tokenizes with `transformers.AutoTokenizer`, which wraps HF `tokenizers` (Rust);
  - it still imports torch through `laya.common`;
  - its execution providers are `CUDAExecutionProvider` or `CPUExecutionProvider` only.

  So on the Mac it is not lighter than the torch path. It is mainly a parity reference.
- **The tokenizer** is a Gemma-style BPE: normalizer `Replace(" " → "▁")`, a `Metaspace` pre-tokenizer, byte_fallback, and ids `<pad>`=0, `<eos>`=1, `<bos>`=2, `<mask>`=4. Laya adds `<bos>` and `<eos>` itself (`add_special_tokens=False`) [primary, tokenizer.json].
- **Other runtimes in the laya repo** [primary]:

| runtime | graph | tokenizer | Android status |
|---|---|---|---|
| laya-ts (TypeScript; npm publishing is an open issue, #288) | split encoder.onnx + head.onnx, from `laya-ts/scripts/export_onnx.py`, verified to 1e-4 at 512 tokens | pure TypeScript BPE reading tokenizer.json (Metaspace and ByteLevel) | onnxruntime-node or onnxruntime-web (WebGPU, then WASM) |
| laya-go | fused `laya.onnx` + `.data`, through a nested cgo module on ONNX Runtime 1.29+ (CPU provider) | pure Go tokenizer package, tested against Python goldens | – |
| laya-java (0.1.0) | fused or split | pure Java BPE; the only dependency is `com.microsoft.onnxruntime:onnxruntime:1.20.0`; JDK 17+ | "Not implemented yet: … Android"; not on Maven Central |

- **What this means for an Android app** [inference]. Tokenization is already a portable pure function of `tokenizer.json`. The hard parts are a mobile graph runtime, 644 MB to 1.29 GB of weights, and sequences up to 3,072 tokens per row.

## 2. Laya-Android (Byun11/laya-android, ByunByun/laya-android)

### 2.1 Repo and model facts (as of 2026-10-16)

- **Author and license.** By Jaeyeon Byun, not affiliated with Convai. Apache-2.0 for code and weights. The NOTICE credits mmBERT-base (MIT) and AndroidControl (Apache-2.0) [primary].
- **GitHub.** Created 2026-10-07; last commit `ab216d4` (2026-10-07 19:26 +0900, "v0.3.1: Jev-centred results…"); 1 star, 0 forks, 0 open issues [primary, gh API].
- **Hugging Face.** Created and last modified 2026-10-07, sha `5a03b2bc4220ec786a00a87eacdaa75cb844bd6b`. It has 1 like, 0 downloads in the last 30 days and 0 discussions. The HF model card is identical to the repo's `MODEL_CARD.md` apart from line endings [primary].
- **Version labels disagree:** CITATION.cff says 0.2.1, the README's BibTeX says 0.3.0, and the last commit says v0.3.1 [primary].
- **Base model.** `convaiinnovations/laya`, subfolder `multilingual`, revision `7b928d828b7b0e022f929d9bd2e44165aa270148`. The encoder is mmBERT-base: ModernBERT architecture, 22 layers, hidden size 768, 12 heads, vocab 256,000, local attention window 128 with a global layer every 3rd, RoPE θ 160,000 [primary, `encoder/config.json`].
- **Checkpoint config.** `rl_agent_config.json` stores `max_len 3072` and `head_max_len 1536`, so the card's explicit arguments repeat the defaults [primary].
- **Training.**
  - Supervised fine-tuning with `laya.train`: soft cross-entropy over options, bf16, option order shuffled for `choice`.
  - 3 epochs on one RTX 4090 in 2.7 h; epoch 2 kept, chosen by validation Policy Joint.
  - Data: AndroidControl train only, 88,308 steps including one synthetic DONE per episode [primary].
  - Rare operations: only 29 HOME, 157 LONG_PRESS and 208 SCROLL_LEFT examples in training [primary, `androidcontrol_stats.json`].
- **No live runner.** The repo has no adb, device, live-run or on-device code. Its eval and benchmark scripts hard-code `.cuda()` and expect `LAYA_ANDROID_DATA` (default `D:/laya-android`). On the Mac use `laya.load` + `system_one` instead [primary]. The model card warns: "Do not let it operate real devices unsupervised; gate irreversible actions" [primary].

### 2.2 The state schema (exactly as in training)

```json
{"goal": "<AndroidControl high-level goal>",
 "history": ["<oldest of last 3>", "...", "<newest>"],
 "ui": ["[0] <label> (<Class>) <flags>", "[1] ...", "..."]}
```

- **Key order** is `goal`, `history`, `ui`. The state is JSON-serialised in that order, and a too-long state loses the **end** of `ui` first [primary, `serialization.row`, `common.build_sequence`].
- **`history`** is the last 3 entries, oldest first, and `[]` on the first step. Entries are produced by `history_str` [primary, `scripts/build_androidcontrol_dataset.py`]:

  ```python
  if op in ("CLICK", "LONG_PRESS"):
      return "%s %s" % (op, cands[gold]["label"] or cands[gold]["class"] if gold is not None else "(unlabeled)")
  if op == "INPUT_TEXT": return 'INPUT_TEXT "%s"' % action.get("text", "")
  if op == "OPEN_APP":   return "OPEN_APP %s" % action.get("app_name", "")
  return op   # SCROLL_UP/DOWN/LEFT/RIGHT, BACK, HOME, WAIT
  ```

  - A CLICK entry uses the raw candidate label (up to 60 characters) from the screen *before* the click, not the decorated option text.
  - The runner must write the payload it actually used into `INPUT_TEXT "…"` and `OPEN_APP <name>`.
- **`step_instruction`** (AndroidControl-Low) exists only behind a diagnostic flag and was never trained. Leave it out [primary].

### 2.3 Candidates: filter, label, deduplicate, order, cap

From `src/candidates.py`, `src/accessibility.py` and `src/serialization.py` [primary]:

1. **Input.** A flat node list from the AndroidControl accessibility *forest*, which has every window, each node carrying `window_type`.
   - Each node has `window, window_type, id, depth, children, bounds (x1,y1,x2,y2), text, content_desc, hint, class, resource_id`.
   - Each node has the flags `clickable, long_clickable, editable, scrollable, checkable, enabled, checked, selected, visible`.
2. **Keep** nodes that are visible, not in the IME window (`window_type == 2`), have a non-empty box after clipping to the screen, and have at least one of `clickable, long_clickable, editable, scrollable, checkable`. Disabled nodes are kept and flagged.
3. **Label** = the node's own `text`, else `content_desc`, else `hint`, with whitespace collapsed and capped at 60 characters. Failing those, it is the descendants' own texts in depth-first order up to 60 characters.
4. **Deduplicate** on (clipped box, label): keep the deepest node and OR the action flags together.
5. **Order** by `(y1, x1, y2, x2, label, class)`, i.e. top to bottom, then left to right. Ids run 0..n-1.
6. **No count cap.** Training screens had a median of 15 candidates, p99 60 and a maximum of 246. The token budgets (3072/1536) were chosen so that no AndroidControl candidate is truncated.
7. **Rendering:**

   ```python
   def option_label(c):
       flags = [f for f, on in (("edit", c["editable"]), ("long", c["long_clickable"] and not c["clickable"]),
                                ("scroll", c["scrollable"] and not c["clickable"]), ("on", c["checked"]),
                                ("selected", c["selected"]), ("disabled", not c["enabled"])) if on]
       return " ".join([c["label"][:40] or "-", "(%s)" % c["class"], *flags])
   ```

   - `class` is the last segment of the class name, e.g. `android.widget.Button` becomes `Button`.
   - `resource_id` is stored but **not** shown, so an unlabeled icon renders as `- (ImageView)`.
   - Real examples: `Brand (ImageView)`, `FILTER Clear All Brand Size And Type Rel (View) scroll`, `Home Tab 1 of 5 (ImageView) selected`.

Adapter notes for a live Pixel, from the AOSP `uiautomator` dumper source on the main branch [primary]:

- **Attributes it writes:** `index, text, resource-id, class, package, content-desc, checkable, checked, clickable, enabled, focusable, focused, scrollable, long-clickable, password, selected, bounds` (already clipped to the screen), `drawing-order, hint`.
  - There is **no `editable`**. It has to be inferred, for example from `EditText` subclasses [UNCERTAIN quality].
  - Only visible-to-user children are dumped.
  - The command calls `waitForIdle(1000, 10000)`, which is part of why a dump is slow.
- **Multi-window mode.** `uiautomator dump --windows` exists on AOSP main but is not in the help text. It dumps all windows with their `type`, which is closer to AndroidControl's forest and lets us drop `TYPE_INPUT_METHOD`. Whether the Pixel 8's Android 16 build has it is unknown [UNCERTAIN].

### 2.4 The `questions` dict (verbatim constants from `src/serialization.py`)

```python
OP_DESC = {"CLICK": "tap a UI element", "LONG_PRESS": "long-press a UI element", "SCROLL_UP": "scroll up",
           "SCROLL_DOWN": "scroll down", "SCROLL_LEFT": "scroll left", "SCROLL_RIGHT": "scroll right",
           "OPEN_APP": "launch an app by name", "INPUT_TEXT": "type text into the focused field",
           "BACK": "press the system back button", "HOME": "go to the home screen",
           "WAIT": "wait for the screen to update", "DONE": "the goal is complete; stop"}
Q_OP = "Which operation should be performed next?"
Q_TARGET = "Which UI element should be targeted?"
questions = {"operation": {"type": "choice", "instructions": Q_OP, "criteria": dict(OP_DESC)},
             "target": {"type": "choice", "instructions": Q_TARGET,
                        "criteria": {str(i): option_label(c) for i, c in enumerate(candidates)}}}
```

- **When the target was trained.** The target question was asked only on steps whose gold action was a CLICK or LONG_PRESS that could be grounded to a candidate [primary]. At inference the card asks both questions every step. Read `target` only when the chosen op is CLICK or LONG_PRESS [primary + inference].
- **Option rendering.** The model reads target options as `"4: Brand (ImageView)"` and the matching state line as `"[4] Brand (ImageView)"` [primary, `render_options`].

### 2.5 Mapping each decision to a device action

| op | what the repo implies | live mapping I recommend |
|---|---|---|
| CLICK | evaluator taps the **centre of the chosen candidate's clipped box** (`evaluate_androidcontrol.to_reference`) [primary] | `input tap cx cy` |
| LONG_PRESS | same point [primary] | `input swipe x y x y 1000` [inference] |
| SCROLL_* | **no target**; the direction lives in the op. The evaluator swipes from screen centre by a quarter screen, but ignores the direction when scoring, so that mapping is not a physical spec [primary] | swipe within the largest `scroll` candidate (else the screen centre), using AndroidWorld semantics: `scroll down` = from centre to the **top** edge, so content moves up; `up` = to the bottom edge; `right` = to the left edge; `left` = to the right edge [primary, AndroidWorld `actuation.py`]. AndroidControl's README defines only "scroll in a specific direction", so check this on the device [UNCERTAIN] |
| OPEN_APP | "app name is not generated" [primary]. AndroidControl: `app_name` "should map to an activity name which can be used to open the app using adb" [primary] | resolve the name from the goal (fuzzy match against installed launcher labels), then `monkey -p <pkg> -c android.intent.category.LAUNCHER 1` [inference] |
| INPUT_TEXT | "text is not generated" [primary]. AndroidControl `input_text` "types the text… It does not perform a click or press enter" [primary] | the field must already be focused (usually a preceding CLICK); text comes from the task, goal parsing or a local LLM; `input text` (needs escaping) or an IME broadcast [inference] |
| BACK / HOME | – | `input keyevent 4` / `3` |
| WAIT | – | sleep about 1 s (AndroidWorld uses 1.0 s) and observe again [primary, AndroidWorld] |
| DONE | synthetic end-of-episode label [primary] | stop and verify |

### 2.6 Accuracy details that matter live (offline, gold history) [primary, `docs/results.md`]

- **Per split.** Type / Grounding / Policy Joint: IDD 81.4 / 67.7 / 0.659; App-Unseen 70.8 / 53.2 / 0.540; Task-Unseen 72.8 / 56.5 / 0.567; Category-Unseen 71.5 / 54.9 / 0.545.
- **Calibration.** Operation ECE is 0.012 on IDD and 0.045–0.055 on the unseen splits. On a 1,000-step subset, target ECE is 0.061.
- **Grounding detail.** The reference evaluator counts a click as correct anywhere inside the gold box enlarged 1.2×, or within 4% of the screen from the gold point. Counting only clicks inside the smallest gold box gives 55.2. 6.4% of test targets are not among the candidates at all.
- **Target accuracy falls with candidate count:** top-1 is 0.84 with 1–5 candidates, 0.68 with 6–10, 0.67 with 11–20, 0.54 with 21–50 and 0.54 above 50.
- **Operation recall by op (test splits):**
  - strong: CLICK 0.79–0.89, OPEN_APP 0.85–0.94, INPUT_TEXT 0.87–0.91;
  - middling: SCROLL_DOWN 0.57–0.64, DONE 0.55–0.75;
  - weak: BACK 0.31–0.56, WAIT 0.30–0.41, SCROLL_UP 0.00–0.55, SCROLL_LEFT 0.04–0.13, SCROLL_RIGHT 0.28–0.68, LONG_PRESS 0.22 (n = 9).
- **Failure modes the author lists:** unlabeled icons that cannot be told apart without the image, and predicting another CLICK when the step should be DONE.
- **Zero-shot base Laya** on the same harness scores only Type 29.0 / Grounding 11.8. The fine-tune is what makes it usable.
- **Latency on an RTX 4090:** 39.7 ms median forward pass and 44.5 ms including tokenization (bf16 autocast). It stays flat up to about 1,024 tokens per row and 50 candidates [primary, `results/latency/rtx4090.json`].
- **Smoke test.** `examples/quickstart.py` should print about `CLICK 0.9347` and `4 Brand (ImageView) 0.9313` on the 4090 [primary]. MPS fp32 results may differ slightly [UNCERTAIN].

### 2.7 Minimal verbatim inference example (README "Quick start")

```python
import laya
from serialization import OP_DESC, Q_OP, Q_TARGET   # src/serialization.py

agent = laya.load("ByunByun/laya-android")
state = {"goal": goal, "history": last_3_actions, "ui": ["[%d] %s" % (i, c) for i, c in enumerate(candidates)]}
questions = {
    "operation": {"type": "choice", "instructions": Q_OP, "criteria": OP_DESC},
    "target": {"type": "choice", "instructions": Q_TARGET, "criteria": {str(i): c for i, c in enumerate(candidates)}},
}
out = agent.system_one(state, questions, max_len=3072, head_max_len=1536)
out["answers"]["operation"]["choice"], out["answers"]["target"]["choice"]   # ('CLICK', '4')
```

## 3. Community and related work

- **Laya-Android itself.** 0 GitHub issues and 0 HF discussions. Search turned up no Reddit or Hacker News threads (2026-10-16) [primary + secondary].
- **`surapat-codediva/laya-android`** (MIT, 0 stars, last pushed 2026-10-02). It is an agent skill driving a phone over `adb` and `uiautomator dump`, with a Laya daemon on a Unix socket, adaptive settling (`screencap | md5sum` probes), a policy gate and trajectories [primary].
  - It uses the **base** `ml` and `en` checkpoints with its own element format, not Laya-Android.
  - Self-reported, emulator only (Pixel 8 AVD, API 36, MPS host): a full observation takes about 2.2 s (≈0.7 s JVM start, 1 s `waitForIdle`, 0.3 s traversal); a probe 0.15 s; a warm Laya decision 0.2–0.26 s; an average step 4.7 s.
  - Its notes say zero-shot Laya was "near chance" picking elements (confidence 0.06–0.09 over 11 elements) and confidently wrong on yes/no questions [primary, self-reported].
  - Open issues plan a companion AccessibilityService (#2) and synthetic training data (#3) [primary].
- **`edwardmonteiro/laya-android`** (Apache-2.0). An Asteroids game whose enemy pilot is base multilingual Laya running **on the phone**. It uses int8 weight-only ONNX (615 MB), ONNX Runtime Android on CPU and HF tokenizers through DJL, and reports about 260 ms per decision on a 2-core CI JVM. It also notes that zero-shot Laya ignores negations. It proves base Laya runs on-device; it does not do GUI control [primary, self-reported].
- **`arnabwithab/laya-android-control`** (HF, 2026-10-10). English 421M; picks the operation only (6 ops, no target). Test op accuracy 0.665, with calibration measured in-sample only. It is weaker than Laya-Android [primary, self-reported].
- **On-device ports of base Laya.** None are of Laya-Android [primary, self-reported].
  - `litert-community/Laya-Multilingual-LiteRT` ships an Android Kotlin sample with LiteRT 2.2.0 graphs at static 256/512 windows. On a Galaxy S26 (Snapdragon): GPU fp32 51.9 ms, Hexagon NPU 36.5 ms, CPU 163 ms per question at 256 tokens. 680 MB of files. Validated on that one device only.
  - ExecuTorch builds (`ksanjiv05/laya-for-rn-executorch`, the English ModernBERT-large `typed-decisions` checkpoint): about 45 ms per decision on an iPhone 15 through Core ML, but **about 7.7 s** on an unnamed Android phone's CPU (XNNPACK int8), and 1.6 s on a desktop CPU. CPU-only phone builds look slow.
  - int4 ONNX (`androidli/laya-multilingual-onnx-int4`, about 200 MB).
  - GGUF (`ggml-org/Laya-GGUF`). llama.cpp PR #29818, merged 2026-10-02, adds a `/v1/systemone` server API and decision heads for Laya and other decision models, with up to 255 options for Laya [primary].
- **Other GUI fine-tunes of Laya** [primary, self-reported]:
  - `cklxx/laya-browser` (mmBERT-base, browser agent for browser-use/jev-ultrafast) scores 11–14 of 54 on held-out multi-step real-site tasks. Its main failure is stopping too early (about 60%). A local Qwen3-8B writes the typed text. Its write-up (`docs/finetune_browser_agent.md` in the laya repo) found that moving elements from the state into the option list mattered more than any data change.
  - Also seen, not read: `ShaunSpark/laya-mind2web-browser-agent`, `knpatil/laya-browser-agent`, and `ChenneyZhuang/laya-computer-use` (macOS accessibility tree).
- **Laya HF discussions with transferable lessons** [secondary]:
  - #15: dict or string states lose their *tail* when truncated, and zero-shot Laya is a poor agent step guard.
  - #9: reversing the options changed 65–76 of 100 zero-shot selections. Laya-Android trained with shuffled option order, which should help, but the evaluation used the fixed screen order.
  - #12: multilingual on an i9 CPU took 307 ms for 1 question over a 227-token state.
  - #5: an MLX port leaked Metal memory over hours.
- **Papers** [primary, arXiv abstracts]:
  - *Jev-Mobile* (2609.30186): an infrequent VLM sets local goals and Jev picks actions inside the accessibility-tree action space. On AndroidWorld it reaches 79% against 84% for a step-wise VLM, with 32.7% less time and 73.4% lower cost. This is the planner-plus-executor pattern that suits Laya-Android.
  - *Action Rebinding* (2601.12349): a malicious app swaps the foreground app while the agent is deciding. 100% hijack rate on 6 agents.
  - *Not an A11y* (2608.08939): prompt injection through accessibility metadata.
- **Jev, for context.** Jev is TypeSafe AI's hosted "System One" typed-decision model, called at `https://api.typesafe.ai/v1/systemone` [primary, `bench_jev.py`]. Laya copies its wire format and its RLCD training framing, and Laya-Android benchmarks against `jev-1.13.0` [primary]. typesafe.ai links a "Jev 1.14" post as of 2026-10-15 [secondary].

## 4. Limitations and risks for driving a real phone

- **Offline numbers do not transfer directly.** Every metric uses the gold history; live, the history is the model's own and errors compound. As a back-of-envelope figure, 0.66 per step over a 5-step task is about 13%, if failures were independent. They are not, so measure [UNCERTAIN]. laya-browser's live real-site success was 20–26% [primary, self-reported].
- **Distribution shift** [primary + inference]:
  - Training data is AndroidControl: 2023–24 apps, an android_env accessibility forest with all windows.
  - We feed `uiautomator` XML from Android 16 apps, with a different window set and `editable` inferred.
  - Even inside AndroidControl, Policy Joint drops 9–12 points on unseen apps.
- **No vision.** Unlabeled icons, custom-drawn UI, WebViews, Flutter, games, canvases and empty accessibility trees are invisible or ambiguous to it [primary]. FLAG_SECURE screens block screenshots but not the accessibility tree [inference].
- **No payloads.** Without a separate source for text and app names, the agent cannot finish any task that needs typing or an app launch [primary].
- **Weak operations.** BACK, WAIT, horizontal scroll, LONG_PRESS and early or late DONE are weak; only 29 HOME examples were trained [primary]. Use step caps, repeated-action detection and a verifier.
- **Truncation.** A dict state loses the end of `ui` first. More than about 100 candidates squeezes the per-option token budget, and option markers that do not fit `max_len` raise an error [primary].
- **Confidence.** The temperature was fitted on AndroidControl validation. On our screens `answer_confidence` may be over-confident, so fit thresholds on logged live steps [primary + inference]. `act_probability` is likely meaningless [UNCERTAIN].
- **Latency.** Each step spends roughly 2 s on the dump, plus settle time and the model call (tens of ms on MPS, 0.3–2 s on a CPU) [primary, self-reported + UNCERTAIN]. A companion AccessibilityService could cut the dump cost.
- **Security and safety.** A gap between observing and acting allows foreground swaps (Action Rebinding). App-controlled text can steer a classifier too. The card says not to run unsupervised. So: gate irreversible actions, and re-check the foreground package and activity just before acting [primary].
- **Small, new projects** (2026-10-16): Laya-Android is a 9-day-old single-author project with 1 star. laya itself ships several releases a week; pin versions and revisions [primary].

## Recommendations

1. **Run Laya-Android in-process on the Mac** with `uv venv --python 3.12`, then `uv pip install "laya==0.3.28" "transformers==4.57.6" torch` (torch 2.14.1).
   - **Why:** this is the stack Laya-Android was evaluated with, every package has cp312 wheels on macOS and Windows, and MPS is picked automatically.
   - **Rejected:** system Python 3.15, which has no torch or onnxruntime wheels; laya 0.4.8 as the first target, since the code diff says its answers should match but that is unmeasured, so compare it second; `laya-serve`, which adds a hop and caps choices at 100.
2. **Download the model once and load it from a local path.** Pin HF revision `5a03b2bc4220ec786a00a87eacdaa75cb844bd6b` and pass `expected_sha256={"model.safetensors": "4ffca5d9a75f4888dc029b1de1dc2d81b77944b69f6b22c93db5285a66262993"}`. Run with `HF_HUB_OFFLINE=1`.
   - **Why:** no network at run time, and the result is reproducible.
   - **Rejected:** `laya.load("ByunByun/laya-android")` by Hub id at run time, which contacts the Hub on every load.
3. **Copy the repo's input pipeline unchanged.** Vendor `src/accessibility.py`, `src/candidates.py` and `src/serialization.py` as they are (Apache-2.0; keep the NOTICE). Write only a uiautomator-XML to AndroidControl node-dict adapter; try `dump --windows` and drop IME windows.
   - **Why:** the card says "other label formats are out of distribution", and the state must match training byte for byte.
   - **Rejected:** surapat's role/context element strings, or any prompt of our own.
4. **Ask both questions every step, exactly as `serialization.py` defines them, and act per §2.5.**
   - Use `target` only for CLICK and LONG_PRESS.
   - Tap the centre of the clipped bounds.
   - Scroll inside the largest `scroll` candidate using AndroidWorld's direction semantics, and verify the direction on the device.
   - **Why:** this matches training and the evaluator's tap rule.
   - **Rejected:** using the target distribution to choose scroll containers (it was never trained for that), and the evaluator's centre swipe (a scoring artefact).
5. **Supply OPEN_APP and INPUT_TEXT payloads outside the model.** Fuzzy-match the goal against installed launcher labels for app names. Take text from task slots or quoted strings in the goal, and hand off otherwise. Add a small *local* LLM only if goals need free-form text.
   - **Why:** the model emits no payloads, and the one GUI fine-tune with live results (laya-browser) used a local LLM for exactly this.
   - **Rejected:** zero-shot base Laya for app or text choice (near chance), and any cloud LLM (breaks the local-only goal).
6. **Gate every action.**
   - Act only when `answer_confidence` clears a threshold, for the op and also for the target on clicks; fit the thresholds on our own logged steps.
   - Keep a deterministic block list for irreversible actions.
   - Re-dump and compare the foreground package just before acting.
   - Use max-steps and repeated-action stops, and record every step to JSONL.
   - **Why:** the card's own warning, offline-only metrics, and Action Rebinding.
   - **Rejected:** unsupervised autonomy, and `act_probability` as the escalation signal.
7. **Defer on-phone inference and keep the Mac + adb loop as v0.** If we later need it on the phone, re-export Laya-Android to a LiteRT static window of at least 1,024 tokens, following the litert-community recipe, or try llama.cpp GGUF with `/v1/systemone` on Android.
   - **Why:** those are the only routes with phone-side evidence or an Android-capable runtime, and the CPU-only phone builds report seconds per decision.
   - **Rejected:** laya-java (Android not implemented), INT8 dynamic-quantized ONNX (83% agreement on multilingual choices), and the Python ONNXAgent (desktop-only execution providers, still needs torch).

## Open questions

- Does `uiautomator dump --windows` work on the Pixel 8 (Android 16)? How do its candidate lists compare with AndroidControl's, for example nav-bar buttons and dialogs that live in separate windows?
- How good is the `editable` inference (`EditText` subclasses, Compose text fields) compared with AndroidControl's `editable` flag? The `edit` flag drives INPUT_TEXT.
- On the device, does SCROLL_DOWN mean "finger up" (AndroidWorld) for this checkpoint? A quick check: whether SCROLL_DOWN steps in AndroidControl precede newly revealed lower content.
- What are the real MPS latency and memory per step on this M5 (16 GB), fp32 against `LAYA_MPS_AMP_MIN_ROWS=1`, including the first-call warm-up?
- Do laya 0.3.28 and 0.4.8 give identical probabilities on the quickstart and on a few dozen real screens?
- How well does an ONNX, LiteRT or GGUF export of Laya-Android match the torch model at 1–3k tokens, and how long do inputs on the phone's CPU or GPU take?
- What closed-loop success rate do we get on 10–20 scripted tasks across Settings, Clock, Contacts and Chrome, and how well calibrated is `answer_confidence` on them?
- Is `act_probability` saturated for Laya-Android too?

## Dead ends

- **No discussion anywhere.** No Reddit or Hacker News threads about Laya-Android or Laya as a GUI or phone agent turned up (two searches, 2026-10-16). Laya-Android also has no GitHub issues and no HF discussions.
- **Fine-tune listing failed.** The HF API filter `other=base_model:finetune:convaiinnovations/laya` returned unrelated trending models, so it cannot be used to list Laya fine-tunes. I used name searches instead.
- **Wrong "device".** `laya/mcp/device.py` and `LAYA_DEVICE` mean the compute device, not an Android device.
- **Wrong "phone".** `research/benchmarks/es_phone_turns` in the laya repo is telephone voice-agent routing, not phone GUI control.
- **Not a gesture spec.** Laya-Android's `to_reference` swipe coordinates are not a physical scroll definition, because the InfiGUI evaluator ignores swipe direction.
- **Harness won't run here.** Laya-Android's eval and benchmark scripts are CUDA-only (`.cuda()`, `torch.autocast("cuda")`) and Windows-path defaults.
- **Same name, different things.** The other repos called "laya-android" are a base-Laya adb skill (surapat) and a game (edwardmonteiro), not Laya-Android runners. `arnabwithab/laya-android-control` predicts the operation only.
- **Line endings only.** The first diff of the HF card against the repo card showed every line changed; it was only CRLF line endings.
- **Unconfirmed framing.** WebSearch summaries claimed Jev-Mobile uses "System-One" framing; the abstract does not say so. Vendor latency claims for Jev and Laya are unverified.

## Sources

- https://pypi.org/pypi/laya/json · https://pypi.org/pypi/torch/json · https://pypi.org/pypi/onnxruntime/json · https://pypi.org/pypi/numpy/json · https://pypi.org/pypi/transformers/4.57.6/json
- laya sdists: https://files.pythonhosted.org/packages/ed/52/108b9fda4e5bcd56d64db83e2c173981b64793c1f20b7e97f6cfc7542d5e/laya-0.3.28.tar.gz · https://files.pythonhosted.org/packages/4e/2d/a0d23f625a04f8962aa44dfb0a6748243233504510b87f84796cb9b8c7e2/laya-0.4.8.tar.gz
- https://github.com/NandhaKishorM/laya (clone at `4ac226d`, 2026-10-15): README.md, BENCHMARKS.md, docs/http-api.md, docs/finetune_browser_agent.md, scripts/export_onnx.py, laya-ts/, laya-go/, laya-java/README.md, laya-java/MODELS.md
- https://github.com/Byun11/laya-android (clone at `ab216d4`, 2026-10-07): README.md, MODEL_CARD.md, NOTICE, src/*.py, scripts/*.py, docs/*.md, configs/, results/
- https://huggingface.co/ByunByun/laya-android (rl_agent_config.json, encoder/config.json, tokenizer/, model.safetensors header) · https://huggingface.co/api/models/ByunByun/laya-android
- https://huggingface.co/convaiinnovations/laya (configs) · https://huggingface.co/convaiinnovations/laya/discussions (#2, #5, #9, #12, #15, #32)
- https://github.com/surapat-codediva/laya-android (README.md, BENCHMARKS.md, skills/laya-android/SKILL.md, issues #2, #3)
- https://github.com/edwardmonteiro/laya-android · https://huggingface.co/arnabwithab/laya-android-control
- https://huggingface.co/litert-community/Laya-Multilingual-LiteRT · https://huggingface.co/litert-community/laya-LiteRT · https://huggingface.co/ksanjiv05/laya-for-rn-executorch · https://huggingface.co/androidli/laya-multilingual-onnx-int4 · https://huggingface.co/ggml-org/Laya-GGUF · https://github.com/ggml-org/llama.cpp/pull/29818
- https://huggingface.co/cklxx/laya-browser · https://github.com/browser-use/jev-ultrafast · https://github.com/ChenneyZhuang/laya-computer-use
- https://raw.githubusercontent.com/google-research/android_world/main/android_world/env/actuation.py (HEAD `30583e2`) · https://raw.githubusercontent.com/google-research/google-research/master/android_control/README.md
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/cmds/uiautomator/library/core-src/com/android/uiautomator/core/AccessibilityNodeInfoDumper.java · …/cmds/uiautomator/cmds/uiautomator/src/com/android/commands/uiautomator/DumpCommand.java
- https://arxiv.org/abs/2609.30186 (Jev-Mobile) · https://arxiv.org/abs/2601.12349 (Action Rebinding) · https://arxiv.org/abs/2608.08939 (Not an A11y)
- https://typesafe.ai
