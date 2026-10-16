# On-device inference: compute, runtime and the System-2 text problem

_Last updated: 2026-10-16 · Trust tags: [primary] read at source · [secondary] search/blog/forum · [measured] run here · [UNCERTAIN] unconfirmed or conflicting_

**Scope.** This note asks two questions:
- Can Laya-Android (`ByunByun/laya-android`) run on the Mac host (Phase 1) and inside an app on the Pixel 8 (Phase 2), and at what cost?
- What fills the payload gap: the INPUT_TEXT strings and OPEN_APP app names that Laya cannot generate?

**Sources and method.**
- **[measured]** rows come from this repo's own runs, recorded in [05-live-tests.md](05-live-tests.md). Nothing in this note was run on the phone.
- **Code reads** were done without running the code, at:
  - laya `4ac226d` (2026-10-15)
  - laya-android `ab216d4` (2026-10-07)

## TL;DR
- **It can run inside an app on the Pixel 8, but only after shrinking it, and it will be slow.**
  - **Memory:** fp32 weights take 1.28 GB, and 61% of that is the 256k×768 embedding table.
  - **Live screens so far:** 600–1,200 input tokens per step, summed over the two rows [measured]. That puts the longest row at roughly 650 tokens or less (inferred). At that size the Tensor G3 CPU should take about **1.3–2.7 s per step with int8 linears, or 3–6.5 s in fp32**.
  - **Heavy screens** (50–100 candidates, rows of 1,100–2,300 tokens) take about 3–19 s with int8 linears, or 6–32 s in fp32.
  - These figures are estimated, not measured [UNCERTAIN, §2].
- **Phase 2 runtime: ONNX Runtime Android, CPU EP** (`onnxruntime-android` 1.31.0, published 2026-10-09).
  - Laya ships the exporter, and its fp32 export agrees with eager on 100% of decisions.
  - Laya also ships a pure-Java SDK, `laya-java`, with the tokenizer, sequence builder, ORT session and decoder. It just isn't built for Android yet [primary].
- **No accelerator shortcut on the Pixel 8:**
  - The Tensor TPU is open to third-party apps only on Tensor G5/G6 [primary].
  - NNAPI was deprecated in Android 15 [primary].
  - The GPU paths (LiteRT, ExecuTorch Vulkan, ORT WebGPU) are all untested for this model on a Mali GPU [UNCERTAIN].
- **Quantization costs accuracy on Laya.**
  - Laya's own test: dynamic int8 agrees with fp32 on only **83%** of mmBERT decisions, and per-channel collapses to 40% [primary].
  - To try next: weight-only block-wise int8 (`MatMulNBits`), plus keeping the embedding table out of RAM. Gate every variant with `laya-evals`.
- **Tokenizer:** mmBERT uses the **Gemma 2 tokenizer** [primary]. Laya's Java, TypeScript and Go SDKs each read `tokenizer.json` directly and are parity-tested. Reuse the Java one on Android. Sentencepiece is a dead end, because no `.model` file ships with the checkpoint.
- **Phase 1 runtime: torch MPS on the M5, Python 3.12.**
  - Laya takes 37–303 ms per step for 3–50 candidates and 793 ms at 100, measured on synthetic Settings rows in `bench_model.py`. CPU is 3–4× slower [measured].
  - The adb screen read takes 2.4–3.8 s and dominates each step [measured].
- **System 2: Gemini Nano is out.** The Pixel 8 is on no ML Kit GenAI device list. On top of that, inference only works while the calling app is the top foreground app [primary].
  - **Phase 1:** deterministic tricks, with Qwen3.5-4B on the Mac as the fallback.
  - **Phase 2:** deterministic tricks plus a Laya tournament, with a tiny LLM loaded only for payload steps.
- **"1.1 GB free" is not the ceiling.** `dumpsys meminfo` reports 4.5 GB "Free", of which 3.5 GB is cached apps [measured].
  - A compressed Laya at about 0.5–0.8 GB fits.
  - fp32 Laya plus an on-phone LLM does not.

## 1. What actually has to run

| Item | Value | Tag |
|---|---|---|
| Parameters | **321.9M**, of which: embedding 196.6M (256,000 × 768); encoder 110.3M (22 layers, d=768, GLU FFN 1152, no biases); head 15.0M (2 full-attention `nn.TransformerEncoderLayer`s with d=768 and FFN 3072, plus a scorer and an act head) | [primary] config + code; the sum matches the card's 321.9M [measured] |
| Attention layout | `layer_types` has 8 `full_attention` layers (every 3rd, starting at layer 0) and 14 `sliding_attention` layers. `local_attention` is 128, and both RoPE thetas are 160000. | [primary] `encoder/config.json` |
| Attention kernel | Laya forces `attn_implementation="sdpa"` (`laya/common.py`). Under SDPA, transformers (main and v4.57.6) builds a dense L×L band mask, so all 24 attention layers cost full L². Only FlashAttention-2 really windows. | [primary] (via sub-agent read of `modeling_modernbert.py`) |
| Budgets | `max_len` 3072, `head_max_len` 1536. No `option_layout` is set, so it defaults to `sequential`, which `ONNXAgent` requires. | [primary] `rl_agent_config.json`, `onnx_agent.py` |
| Rows per step | **2.** Each question becomes its own row, `[CLS] question [SEP] [MASK] opt0 … [SEP] state [SEP]`, padded to the longer of the two. The `target` row carries every candidate twice: once in `state.ui` and once as an option. | [primary] `build_sequence`, `serialization.py`; [measured] in 05 |
| Files | `model.safetensors` is 643.8 MB (fp16); `tokenizer.json` is 34.4 MB | [primary] HF API |
| Lengths (AndroidControl) | Train state tokens: p50 218, p90 450, p99 769. The longest row in a step is ≤512 tokens for 65% of test steps, ≤1024 for 94% and ≤1536 for 99%. | [primary] `androidcontrol_stats.json`, `rtx4090.json` |
| Lengths (live Pixel 8) | Settings pages: 600–950 input tokens per step (both rows). Home screen: ~1,200 (31 candidates). | [measured] 05 (`usage.input_tokens`) |
| Reference speed | RTX 4090: 39.7 ms median per step, peak VRAM 1.66 GB. Flat up to about 1,024 tokens, so the runtime there is overhead-bound. | [primary] |

## 2. Compute and memory estimates for the Pixel 8 [UNCERTAIN]

**Method.**
- **Linear layers:** FLOPs ≈ 2 × non-embedding params × tokens = 2 × 124.5M = **249 MFLOP per token**. The embedding is a lookup and costs no FLOPs.
- **Attention:** ≈ 4·L²·d = 3,072·L² FLOP per layer. Every one of the 24 layers pays the full L², because the export is masked full attention (§1).
- **The "window-aware" figure** is the floor a custom kernel could reach: 14 layers at 4·L·128·d.
- **Device:** the Tensor G3 CPU has 1× Cortex-X3 at 2.91 GHz, 4× A715 at 2.37 GHz and 4× A510 at 1.7 GHz [secondary].
  - The 4 useful threads (X3 + 3× A715) peak at about 0.2 TFLOPS fp32. Assuming **60–120 GFLOPS fp32 sustained**, and 200–400 GOPS for int8 linears via SDOT/I8MM.
  - The GPU is a Mali-G715 MP7 at 890 MHz, about 1.6 TFLOPS fp32 peak [secondary]. Assuming 0.3–0.6 TFLOPS effective on a cool phone.
- The calculator script was kept in the session scratchpad, not in the repo.

| Longest row L | GFLOP per step (2 rows; window-aware) | Attention share | Activation peak, 2 rows, fp32, unfused | CPU fp32 | CPU int8 linears | GPU fp32 (if a conversion runs) |
|---:|---:|---:|---:|---:|---:|---:|
| 300 | 163 | 8% | ~19 MB | 1.4–2.7 s | 0.5–1.0 s | 0.3–0.5 s |
| 650 (live home screen) | 386 | 16% | ~88 MB | 3.2–6.4 s | 1.3–2.7 s | 0.6–1.3 s |
| 1024 | 665 (586) | 23% | ~220 MB | 5.5–11 s | 2.6–5.1 s | 1.1–2.2 s |
| 1536 | 1,113 | 31% | ~490 MB | 9.3–18.5 s | 4.8–9.6 s | 1.9–3.7 s |
| 2048 | 1,638 (1,300) | 38% | ~870 MB | 14–27 s | 7.7–15 s | 2.7–5.5 s |
| 3072 | 2,922 (2,144) | 48% | **~1.96 GB** | 24–49 s | 15–31 s | 4.9–9.7 s |

**Cross-checks.**
- **EmbeddingGemma-300m on LiteRT** [primary]
  - Why it fits: same shape class, ~100M transformer + ~200M embedding parameters with a Gemma tokenizer.
  - Measured on a Galaxy S25 Ultra CPU (XNNPACK, 4 threads, int4/int8): 66 / 169 / 549 / 2,455 ms at 256 / 512 / 1,024 / 2,048 tokens.
  - Scaled to Laya on the Pixel 8 (×1.1–1.25 compute; ×2.0–2.5 device, Geekbench 6 multi-core about 4.2–4.4k vs about 10k [secondary]; ×2 rows): 0.7–1.1 / 2.4–3.4 / 10.8–15.3 s per step at 512 / 1,024 / 2,048.
  - This agrees with the int8 column.
- **M5 CPU in this repo** [measured]
  - The 100-candidate synthetic row (about 2,300 tokens per target row) took 3.2 s, about 0.6 TFLOPS effective on the M5 CPU.
  - A Pixel 8 with no matrix engine should land 5–10× below that.
- **ModernBERT-Ja-310M on LiteRT** [primary]
  - Setup: Galaxy S26 GPU, explicit FP32, 512-token window. The model costs about 1.8× Laya's FLOPs per token.
  - Result: **184 ms per pair on a cool phone, but a 416 ms median over a 17-minute warming run.** Sustained heat cost about 2.3×.
  - The Tensor G3 has a reputation for running hot [UNCERTAIN].

**Levers, cheapest first.**
1. **Ask `operation` first, and `target` only for CLICK or LONG_PRESS.**
   - The `operation` answer does not change, because the two rows are independent forward passes.
   - About half of all target rows go away: CLICK + LONG_PRESS are 46,581 of 88,308 AndroidControl steps [primary].
   - Running the two rows one after the other costs little on CPU. Laya: "batching questions saves little on CPU" [primary].
2. **Run the rows one at a time.** This halves activation memory.
3. **Cap `max_len` at about 1,536 on the phone.** That covers 99% of AndroidControl steps.
4. **Use int8 or fp16 weights** (§4).
5. **Distil onto mmBERT-small** (140M total, 42M non-embedding [primary]). That cuts linear FLOPs by about 2.6×, but needs retraining.

**Weights in memory.**

| Variant | Embedding | Linears | Total | Note |
|---|---:|---:|---:|---|
| fp32 (Laya `export_onnx.py`) | 786 MB | 498 MB | **1,284 MB** | The measured graph is 1.29 GB [primary laya-java MODELS.md] |
| fp16 | 393 | 249 | 642 | Same size as the safetensors file |
| int8 MatMul only (`--quantize`) | 786 | 125 | 911 | Laya: "1.4× smaller" on mmBERT [primary] |
| int8 MatMul + fp16 embedding | 393 | 125 | 518 | |
| int8 MatMul + int8 embedding | 197 | 125 | 321 | `onnx-community/mmBERT-base-ONNX` `model_int8.onnx` is 310 MB [primary] |
| int4 MatMul + int4 embedding | 98 | 62 | 161 | |
| Host-side fp16 table, memory-mapped, + int8 linears | a few MB resident | 125 | ~0.13 GB resident | Only the rows actually touched get paged in. ModernBERT-Ja-LiteRT ships exactly this, as `tok_embeddings_fp16.bin` [primary]. Untested with ORT [UNCERTAIN]. |

## 3. Android runtime options (Pixel 8, Android 16, arm64-v8a)

| Option | Status, 2026-10 | Fit for Laya-Android | Tag |
|---|---|---|---|
| **ORT Android, CPU EP (MLAS)** | `com.microsoft.onnxruntime:onnxruntime-android` 1.31.0. Java/Kotlin API, including `createSession(ByteBuffer)` on a direct buffer. CPU kernels include `MatMulNBits` (2/4/8-bit block-wise; `accuracy_level` 1 = fp32 activations, 4 = int8), `GatherBlockQuantized` (int4/uint8 → fp32/fp16) and `DynamicQuantizeMatMul`. | **Best.** Runs Laya's fused graph as is. | [primary] Maven, `OperatorKernels.md`, `OrtEnvironment.java` |
| ORT XNNPACK EP | Included in the AAR. MatMul and Gemm are "Only 2D Op", so a transformer's 3-D `[B,L,768]` MatMuls stay on the CPU EP. Use 1 ORT intra-op thread and turn spinning off. | Little gain expected [UNCERTAIN] | [primary] docs |
| ORT NNAPI EP | The EP page has no deprecation notice, but Android deprecated NNAPI in Android 15 and points to LiteRT instead. Needs constant inputs. | Dead end | [primary] |
| ORT WebGPU EP | Native, and lists "Android / Vulkan" through Dawn. Shipped as a plugin EP; not shown in the Android AAR. | Unproven | [primary] docs; packaging [UNCERTAIN] |
| ORT QNN EP | `onnxruntime-android-qnn`, Qualcomm only | N/A on Tensor | [primary] |
| **LiteRT** 2.3.0 (`com.google.ai.edge.litert`, 2026-10-06) + `litert-torch` 0.9.4 | A ModernBERT model has been converted: ModernBERT-Ja-310M, using fixed shapes (S256/S512), a hand-written graph module with the control flow written out, and the embedding lookup moved to the host. Verified on desktop and on a Galaxy S26 GPU/NPU, not on a Pixel. | Plan B for the GPU (ML Drift: OpenCL, falling back to OpenGL). Fixed shapes, so bucket inputs by length. | [primary] HF card, Google Maven |
| LiteRT NPU for Google Tensor | Tensor SDK beta (2026-05-19), **Tensor G5/G6 only**. The blog names the Pixel 10 family. AOT compilation, sign-up required. | **Not the Pixel 8** | [primary] |
| ExecuTorch (`executorch-android` 1.5.1) | optimum-executorch tests BERT, RoBERTa, EuroBERT and ALBERT, but not ModernBERT. XNNPACK backend for CPU. The Vulkan backend is "under active development" (fp32/fp16, quantized linears). The Python package requires `<3.15`. | Possible, but untested, and no gain over ORT | [primary] README, docs, PyPI |

**fp16 caveat for any GPU path.**
- In ModernBERT-Ja, LayerNorm inputs reach about 2,700 in the deep layers, and squaring them overflows fp16 [primary].
- The conversion needed two rewrites: a "SafeLayerNorm" and a −1e4 mask constant.
- With those in place, fp16 kernels still flipped 2 of 621 answers. Explicit FP32 matched the reference exactly [primary].
- mmBERT is probably similar [UNCERTAIN].

## 4. Export and quantization

**Exporters (laya repo).**
- **`scripts/export_onnx.py`** exports the whole `DecisionModel` to one fused `laya.onnx`.
  - It uses `torch.onnx.export` at opset 18, with dynamic batch, sequence and marker axes.
  - The trace dummies are batch 2 and 3 markers, per issue #695.
  - Weights go to external data, so the result is a 2.8 MB graph plus a `.data` file.
  - The same graph runs in `ONNXAgent`, `laya-java` (`LayaSession`) and `laya-go` (a cgo `onnx` module on ORT ≥ 1.29) [primary].
- **`laya-ts/scripts/export_onnx.py`** writes a split `encoder.onnx` + `head.onnx` using the dynamo exporter, with explicit `Dim`s and sequence length up to 8192.
  - Before writing, it verifies the output matches torch within 1e-4 at 16 and 512 tokens. The 512 check exercises the 128-token window [primary].
- **HF optimum-onnx** lists ModernBert, and `onnx-community/mmBERT-base-ONNX` ships fp32 (1.23 GB), fp16 (616 MB), int8 (310 MB) and q4/q4f16 [primary].
  - These cover the encoder only. Use Laya's exporter for the full model with its head.

**Measured quantization damage** (laya issue #790, fixed in 0.3.23; CPU, ORT 1.30) [primary]:

| Variant | English (ModernBERT-large), 96 decisions | Multilingual (mmBERT), 48 decisions |
|---|---|---|
| ONNX fp32 | 100% | 100% |
| Dynamic int8, per-tensor (now the default) | 67% | **83%** |
| Dynamic int8, per-channel (old default) | 32% | 40% |
| Static QDQ MinMax | 31–45% | — |

**What the issue adds** [primary]:
- int8 is "≈2× faster than eager / 1.8× faster than fp32" on CPU.
- Keeping the head in fp32 did not help, so the loss is in the encoder activations.
- Laya's own verdict: accuracy-safe int8 needs "QAT or SmoothQuant-style outlier handling".

**Next experiments** [UNCERTAIN until measured]:
1. Weight-only `MatMulNBits`, 8-bit, block 32, `accuracy_level=1` (fp32 activations). This should avoid the activation-outlier loss, though it saves memory more than time.
2. The same with `accuracy_level=4`, which quantizes activations to int8 per block. That is finer than per-tensor, and faster.
3. An fp16 or int8 embedding via `GatherBlockQuantized`, or the host-side memory-mapped table.

**Gate.** Score each variant with `laya-evals run --onnx …` on recorded Android steps and require ≥ 99% agreement with fp32 [primary for the tool].

## 5. Tokenizer on Android

**What mmBERT uses.**
- **The Gemma 2 tokenizer.** The paper says "employ the Gemma 2 tokenizer to handle multilingual input"; the HF card's table says "Gemma 2" [primary].
- The `tokenizer/tokenizer.json` that ships with Laya-Android [primary]:
  - BPE with `byte_fallback`: 256,000 vocab, 580,604 merges.
  - Normalizer `Replace " "→"▁"`, then a `Metaspace` pre-tokenizer (`prepend_scheme: always`, `split: true`).
  - 249 added tokens, using Gemma's ids: `<pad>`=0, `<eos>`=1 (also SEP), `<bos>`=2 (also CLS), `<unk>`=3, `<mask>`=4.
- **No `tokenizer.model` ships.** The pre-tokenizer also differs from stock Gemma, and the paper says mmBERT did not use prefix spaces [primary].

**Options.**

| Option | Evidence | Verdict |
|---|---|---|
| **laya-java tokenizer** (pure Java: normalizer, Metaspace/ByteLevel, BPE, byte fallback, added tokens) | Parity-tested against HF for both checkpoints; 44 files, 16k lines; ORT is its only dependency [primary]. Port risks: it uses records, sealed types and Java 11 APIs (`String.strip`, `Files`), which may need desugaring or a higher minSdk. It holds the merges as nested `HashMap`s, which may cost about 100–250 MB of ART heap [UNCERTAIN]. | **Use.** Precompile the merges to int arrays if heap is tight. |
| DJL HF tokenizers for Android (`ai.djl.android:tokenizer-native` 0.33.0, 2025-06) | Rust `tokenizers` over JNI, so exact by construction. The Android artifact lags DJL desktop (0.38) [primary Maven]. | Fallback |
| Rust `tokenizers` via cargo-ndk + JNI | Exact, but adds a build chain | Only if both of the above fail |
| onnxruntime-extensions tokenizer ops | `onnxruntime-extensions-android` is stuck at 0.13.0 (2024-10-31), while PyPI has 0.15.2 [primary] | Avoid (stale) |
| sentencepiece-android | No `.model` file, and the pre-tokenizer doesn't match | Dead end |

**How laya-ts tokenizes.** It has its own TypeScript BPE that reads `tokenizer.json`. It handles both pre-tokenizer kinds (Metaspace and ByteLevel), byte fallback and added tokens, and runs the split graphs on `onnxruntime-node` / `onnxruntime-web` [primary]. laya-go and laya-dotnet each carry their own `tokenizer.json` reader too [primary].

## 6. The Mac side (Phase 1)

| Topic | Finding | Tag |
|---|---|---|
| Device pick | Laya `Agent` picks MPS automatically and runs SDPA in eager mode (no `torch.compile`). | [primary] |
| MPS SDPA kernels | torch ≤ 2.12 builds the full [B,H,L,L] matrix with MPSGraph. 2.13.0 (2026-07-08) added a fused Metal prefill kernel (head_dim 64 qualifies). 2.14.x adds an MPP path for fp16/bf16 on macOS ≥ 26.2, which this Mac runs. | [primary] (sub-agent read of pytorch `Attention.mm` at each tag) |
| Mask dtype matters | transformers 5.x builds bool masks, which can take the fused path. 4.57.6, the version Laya-Android was validated with, builds fp32 additive masks. Whether those still reach the fused kernel is [UNCERTAIN]. | [primary] / [UNCERTAIN] |
| fp16 | Laya only autocasts on MPS at ≥ 5 rows (`LAYA_MPS_AMP_MIN_ROWS`). Laya-Android sends 2 rows, so it runs fp32. On a base M5 (PR #109), fp16 starts to win at about 4 rows. In one sweep, fp16 changed 1 of 1,248 decisions. | [primary] |
| Known MPS issues | The autocast crash is fixed (#273). The first call pays for Metal compilation. MPS `bmm` returns silently wrong results at ≥ 2³¹ elements; 2 rows × 3072 is about 0.23B, so this is safe. No ModernBERT-specific MPS bug was found. | [primary] / [secondary] |
| **Measured here** | Synthetic Settings rows, MPS median: 37 / 60 / 303 / 793 ms at 3 / 14 / 50 / 100 candidates. CPU median: 102 / 161 / 917 / 3,209 ms. Cached load: 3.4–5.2 s. Live decisions took 0.12–0.85 s. | [measured] 05 |
| Estimate (base M5, fp32, from the M5 Pro fit) | 81–120 ms at 512 tokens per row, 171–258 ms at 1,024, 0.41–0.62 s at 2,048, 0.72–1.10 s at 3,072. fp16 runs at about half that. | [UNCERTAIN] |
| Alternatives | **ORT CPU EP:** `ONNXAgent` works today with CPU or CUDA only; fp32 is 1.48× eager on x86. **CoreML EP:** its MLProgram op list lacks Gather, Where, Expand, Sin, Cos, Range and CumSum, so the graph would split up. **MLX:** mlx 0.32.4 has cp315 wheels and uses the M5 neural accelerators; mlx-embeddings lists ModernBERT, but the Laya head would need a port plus a parity check. | [primary] |
| Python, checked 2026-10-16 | 3.15.0 went final on 2026-10-09. Packages: **torch 2.14.1** has cp310–cp314 wheels on PyPI and cp315 only on download.pytorch.org. **onnxruntime 1.31.0** has only cp311–cp314 wheels and no sdist, so `laya[onnx]` can't install on 3.15. **executorch** requires `<3.15`. numpy 2.5.4, tokenizers and safetensors (abi3) and onnx 1.23.2 are fine on 3.15. Laya's CI covers 3.10–3.13. | [primary] PyPI JSON; torch/ORT tags rechecked [measured] |

**Unexplained gap: live decisions are slow.** Live decisions took up to 0.85 s, while the same ~600-token rows predict ~0.1–0.15 s.
- A likely cause is that every new sequence length rebuilds the MPS graph [UNCERTAIN].
- Test it by padding L up to a multiple of 64 or 128.

## 7. The "System 2" text problem

**How big the gap is.**
- **Share of steps:** INPUT_TEXT is 5,406 and OPEN_APP 5,044 of 74,714 non-DONE AndroidControl steps, so about 14% of steps need a payload [primary].
- **Payloads feed back into history:** the training history uses the formats `INPUT_TEXT "<text>"` and `OPEN_APP <app>` [primary].
- **The LLM baselines in the Laya-Android table say nothing about payloads:** they were only asked for `{operation, target}` [primary].

**(a) Deterministic tricks.** In the live episodes, an alias table plus quoted spans supplied every payload that was needed. The two failures were wrong operations, not wrong payloads [measured].
- **OPEN_APP**
  - Read the launcher labels with PackageManager. Android 11+ package visibility requires a `<queries>` element or `QUERY_ALL_PACKAGES`, and the latter is explicitly acceptable for accessibility apps [primary].
  - Fuzzy-match goal n-grams against the labels:
    - Python: RapidFuzz 3.14.6 (MIT, cp315 wheels).
    - Kotlin: Apache Commons Text `JaroWinklerSimilarity` / `LevenshteinDistance`.
    - Avoid `me.xdrop:fuzzywuzzy`, which is GPL-2.0 [primary].
  - A confident match launches directly via `getLaunchIntentForPackage` or `monkey -p`, skipping Laya.
  - The live failures were both of the type "an app was named on the launcher and Laya chose BACK" [measured].
- **Too many options**
  - Laya's option budget is shared: at the default 192/256 budgets, Banking77's 77 labels get about 3–4 tokens each, scoring 0.425 [primary].
  - Laya-Android's `head_max_len` of 1536 leaves room for about 150–200 short labels [UNCERTAIN].
  - `predict_tournament` (groups of 16, up to 256 labels) raised BANKING77 from 0.430 to 0.610 and CLINC150 from 0.625 to 0.876, at about 2× latency. laya-java has it too [primary].
  - `predict_shortlist` with the agent's own embedder was worse on every set [primary].
- **INPUT_TEXT**
  - Candidate spans: quoted strings; regex hits (email, URL, numbers, dates); text after "search for / named / saying / type"; short n-grams of the goal.
  - Pick among them with a Laya `choice` of ≤ 16 options, or with a tournament.
  - This fails when the text has to be composed rather than copied [UNCERTAIN how often].
- **Caveat:** base Laya is "near chance zero-shot" on new typed decisions [primary]. Measure span and app choice before trusting it, or fine-tune a span question on AndroidControl's ~10k payload steps.

**(b) Small local generative LLMs.**

| Where | Option | Memory | Speed | Quality / notes | Tag |
|---|---|---|---|---|---|
| Mac | **Qwen3.5-4B** (Ollama Q4 or mlx-lm) | 3.3–4.0 GB download | ~0.5–1 s per short payload call | Best small model in the Laya-Android same-input table: Type 67.3 / Grounding 52.8, zero-shot. Ollama `format` takes a JSON schema, so an app enum is possible. | [primary] table, Ollama; speed [UNCERTAIN] |
| Mac | Qwen3.5-0.8B / 2B; Gemma 4 E2B / E4B; Qwen3.5-9B | 1.2–9.5 GB | ~15–120 tok/s decode, estimated | Weaker (41–64 Type) or heavier | [primary] sizes; [UNCERTAIN] speed |
| Mac runtimes | MLX uses the M5 neural accelerators on macOS ≥ 26.2. Apple measured a 3.3–4.1× faster time to first token and 1.19–1.27× faster decode than the M4. Ollama's MLX engine (0.19, 2026-03) was gated to Macs with more than 32 GB at launch. | | | | [primary] |
| Phone | Gemma 4 E2B on **LiteRT-LM** (`litertlm-android` 0.18.0) | 2.58 GB file. Peak 1.36–1.73 GB on CPU, 0.68–0.79 GB on GPU | S26 Ultra CPU: 557 tok/s prefill, 47 tok/s decode. The Pixel 8 should be several times slower. | Does not fit beside Laya on CPU. The GPU backend needs `libOpenCL.so` [UNCERTAIN on Pixel]. | [primary] card; Pixel [UNCERTAIN] |
| Phone | Gemma 3n E2B (LiteRT-LM) | ~1.9B effective params | S24 Ultra CPU: 110 prefill / 16 decode tok/s | Heavier | [primary] |
| Phone | Qwen3.5-0.8B on llama.cpp Android | ~0.6–1.0 GB | ~1–2 s per short call, estimated | llama.cpp has a Kotlin sample and builds with the NDK | [primary] docs; speed [UNCERTAIN] |
| Phone | **FunctionGemma 270M**, fine-tuned for `type_text` / `open_app` tools | ~0.2–0.3 GB at int4/int8 | well under 1 s | Needs fine-tuning on AndroidControl payloads | [primary] model; rest [UNCERTAIN] |
| Phone | MediaPipe LLM Inference | — | — | "Maintenance-only"; Google says migrate to LiteRT-LM | [primary] |
| Phone | MLC LLM | — | — | Android APK from 2024-09; docs name Adreno only | [primary] |
| Phone | **Gemini Nano (ML Kit GenAI Prompt API, `genai-prompt` 1.0.0-beta4)** | system-managed | — | **The Pixel 8 / 8 Pro / 8a are not listed for any feature** (lists updated 2026-10-15; Prompt API starts at Pixel 9). Beta. "Permitted only when the app is the top foreground application", so a foreground service gets `BACKGROUND_USE_BLOCKED`. Per-app quotas apply. On the Pixel 8, Nano exists only behind a developer option, for Google's own features. | [primary]; history [secondary] |

## Recommendations

1. **Phase-1 runtime: on the host Mac, laya `Agent` on torch MPS in fp32, with torch 2.14.1 on Python 3.12 via uv.** The phone stays a thin adb client over USB, or over LAN via wireless debugging. ORT CPU with the exported `laya.onnx` runs on the Mac as a parity harness for Phase 2.
   - **Why:**
     - It already works: 37–303 ms per step for ≤ 50 candidates [measured].
     - The 2.4–3.8 s `uiautomator dump`, not the model, dominates each step [measured].
     - The parity harness measures int8 drift before any phone work.
   - **Rejected:**
     - ORT, CoreML or MLX as the primary runtime: slower or lossy, missing ops, or a port.
     - Python 3.15: no torch on PyPI, no onnxruntime wheels, and Homebrew's pyexpat is broken [measured]. D-003 already pins 3.12.
   - **Follow-ups:** an A/B on a replay set of transformers 4.57.6, Laya-Android's validated version, against the 5.20 in `uv.lock`; and length bucketing on MPS.
2. **Phase-2 runtime: ONNX Runtime Android (CPU EP, 1.31.0) running Laya's fused `laya.onnx`, driven by a port of `laya-java`, inside the AccessibilityService app.**
   - **Settings:**
     - Ask `operation` first, then `target` only when it is needed.
     - Cap `max_len` at about 1,536 and run the rows one at a time.
     - Use 4 intra-op threads (the X3 and A715 cores).
     - Precision: start in fp32, then move to weight-only int8 `MatMulNBits` with the embedding table kept out of RAM, gated by `laya-evals` (≥ 99% agreement with fp32).
   - **Why:**
     - It is the only runtime proven on this graph and available on the Pixel 8.
     - It means the least new code: Laya's own exporter, plus a Java SDK with a parity-tested tokenizer.
     - On the screens seen so far, expect about 3–6.5 s per step in fp32 and 1.3–2.7 s with int8 linears [UNCERTAIN].
   - **Rejected:**
     - LiteRT GPU as the first step: it needs fixed shapes and an unrolled graph, fp16 LayerNorm overflows, and OpenCL availability on Pixel is unclear. Keep it as Plan B if the CPU turns out too slow.
     - NNAPI and the TPU: NNAPI is deprecated, and the Tensor SDK supports G5+ only.
     - ExecuTorch: ModernBERT untested, and no Python 3.15 support.
     - The dynamic int8 default: 83% agreement.
3. **System 2, Phase 1: deterministic tricks first, Qwen3.5-4B (Ollama or mlx-lm, JSON-schema output with installed apps as an enum) as the fallback.** Log how often the fallback fires.
   - **Why:** the rules already supplied every payload the live episodes needed [measured]. About 4 GB fits beside Laya in 16 GB, and a call takes about 0.5–1 s.
   - **Rejected:** Qwen3.5-9B (RAM and speed); any cloud model, which breaks local-only.
4. **System 2, Phase 2: deterministic tricks plus a Laya tournament over app labels and spans, with an LLM loaded only on INPUT_TEXT or OPEN_APP steps.** Target model: FunctionGemma 270M fine-tuned on AndroidControl payloads, via LiteRT-LM tools. Interim zero-shot model: Qwen3.5-0.8B on llama.cpp.
   - **Why:** it fits the RAM budget, and both runtimes are maintained.
   - **Rejected:**
     - Gemini Nano: Pixel 8 unsupported, and foreground-only.
     - Gemma 4 E2B on CPU: 1.4–1.7 GB peak.
     - MediaPipe LLM Inference: maintenance mode.
     - MLC LLM: stale.
5. **1.1 GB of free RAM: no problem in Phase 1. In Phase 2, budget Laya at ≤ ~0.8 GB and never keep it resident alongside an LLM.**
   - **How to stay in budget:** int8 linears, an embedding table that is memory-mapped or quantized, rows of at most 1,536 tokens, and rows run one at a time.
   - **What to measure:** MemAvailable and the "Free" line in `dumpsys meminfo` (4.5 GB measured, of which 3.5 GB is cached apps), not MemFree.
   - **Why:** the low-memory killer reclaims cached apps to make room for a perceptible process.
   - **Rejected:** shipping the fp32 graph. That is 1.28 GB of weights, plus up to 2 GB of activations at 3,072 tokens.

## Open questions
- **What does Laya actually cost on the Pixel 8?** The single most important number: ORT latency and RSS for `laya.onnx`, fp32 against `MatMulNBits` int8, at L = 512 / 1,024 / 1,536. Measure it with `onnxruntime_perf_test` or a minimal app, and repeat over a 10-minute run to capture thermals.
- **Which quantized variant keeps ≥ 99% agreement with fp32 on recorded Android steps?** Candidates: weight-only int8/int4, an fp16 or int8 embedding, and `accuracy_level` 4.
- **Can ORT keep untouched embedding rows out of resident memory?** One route is the ORT format with `session.use_ort_model_bytes_directly`, `use_ort_model_bytes_for_initializers` and `disable_prepacking` on a memory-mapped buffer [primary options; effect UNCERTAIN]. Exporting the encoder with `inputs_embeds` and doing the lookup on the host is simpler.
- **Is the live MPS gap (0.4–0.85 s against ~0.1 s predicted) caused by per-shape graph rebuilds?**
- **Does transformers 5.20 agree with 4.57.6 on Laya-Android decisions?**
- **How does laya-java behave on ART?** Heap use for the 580k merges, desugaring needs, and the minSdk for `String.strip`.
- **Can apps use OpenCL on the Pixel 8?** This gates the GPU backends of both LiteRT and LiteRT-LM.
- **How accurate is Laya zero-shot on span and app-choice questions?** Also, what share of INPUT_TEXT payloads appear verbatim in the goal?

## Dead ends
- **Hardware acceleration on the Pixel 8:**
  - the Tensor TPU/NPU (the Tensor SDK supports G5/G6 only);
  - NNAPI (deprecated in Android 15);
  - Gemini Nano through ML Kit GenAI (Pixel 8 unsupported, and foreground-only).
- **Quantization schemes that wreck Laya:** dynamic int8 per-channel and static QDQ (31–45% agreement) [primary].
- **Tokenizer routes:** sentencepiece-android (no `.model` file, and a mismatched pre-tokenizer); the Android build of onnxruntime-extensions (stale).
- **Free compute from the sliding window:** under SDPA, ONNX or LiteRT exports, every layer pays full L² [primary].
- **Mac-side accelerations:** FlashAttention/unpadding on MPS; `torch.compile` for ModernBERT on MPS; the CoreML EP for this graph.
- **Phone-side LLM runtimes:** MLC LLM on Mali; MediaPipe LLM Inference.
- **Laya's `predict_shortlist`** with the agent's own embedder: worse than no shortlist [primary].
- **Python 3.15 for this stack** [primary + measured].

## Sources

**Laya, Laya-Android and the checkpoint**
- https://github.com/NandhaKishorM/laya at `4ac226d`: `scripts/export_onnx.py`, `laya/onnx_agent.py`, `laya/common.py`, `laya/agent.py`, `laya-java/README.md`, `laya-java/MODELS.md`, `laya-ts/src/tokenizer.ts`, `laya-ts/scripts/export_onnx.py`, `laya-go/README.md`, `BENCHMARKS.md`, `LOCAL_SETUP.md`, `docs/evals.md`
- https://github.com/NandhaKishorM/laya/issues/790
- https://github.com/NandhaKishorM/laya/pull/109
- https://huggingface.co/ByunByun/laya-android (`rl_agent_config.json`, `encoder/config.json`, `tokenizer/*`, `eval/rtx4090.json`)
- https://github.com/Byun11/laya-android at `ab216d4`: `src/serialization.py`, `results/data/androidcontrol_stats.json`, `docs/benchmark.md`
- https://huggingface.co/convaiinnovations/laya

**mmBERT and comparable on-device models**
- https://huggingface.co/jhu-clsp/mmBERT-base
- https://arxiv.org/html/2509.06888
- https://huggingface.co/onnx-community/mmBERT-base-ONNX
- https://huggingface.co/mlboydaisuke/ModernBERT-Ja-310M-Decision-LiteRT
- https://huggingface.co/litert-community/embeddinggemma-300m
- https://developers.googleblog.com/en/introducing-embeddinggemma/

**ONNX Runtime**
- https://onnxruntime.ai/docs/execution-providers/
- https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html
- https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html
- https://onnxruntime.ai/docs/execution-providers/WebGPU-ExecutionProvider.html
- https://onnxruntime.ai/docs/performance/model-optimizations/ort-format-models.html
- https://github.com/microsoft/onnxruntime: `docs/OperatorKernels.md`, `docs/ContribOperators.md`, `java/src/main/java/ai/onnxruntime/OrtEnvironment.java`
- https://onnxruntime.ai/docs/execution-providers/CoreML-ExecutionProvider.html

**Android platform, LiteRT and ExecuTorch**
- https://developer.android.com/ndk/guides/neuralnetworks/migration-guide
- https://developers.google.com/edge/litert/next/npu
- https://developers.google.com/edge/litert/next/tensor-sdk
- https://developers.googleblog.com/en/google-tensor-sdk-beta-with-litert/
- https://docs.pytorch.org/executorch/main/backends/vulkan/vulkan-overview.html
- https://github.com/huggingface/optimum-executorch
- https://huggingface.co/docs/optimum-onnx/onnx/overview

**Package registries**
- Maven Central: `com.microsoft.onnxruntime:*`, `ai.djl.android:tokenizer-native`, `org.pytorch:executorch-android`
- Google Maven: `com.google.ai.edge.litert:*`, `com.google.ai.edge.litertlm:litertlm-android`, `com.google.mlkit:genai-prompt`
- https://pypi.org/pypi/{torch,onnxruntime,executorch,litert-torch,optimum-onnx,onnxruntime-extensions}/json
- https://download.pytorch.org/whl/cpu/torch/

**Device specs**
- https://en.wikipedia.org/wiki/Google_Tensor (Tensor G3 CPU and GPU)
- https://www.phonearena.com/news/leak-indicates-performance-gap-between-pixel-8-and-android-flagships-is-going-to-widen_id147979 (Geekbench)

**Mac side** (via sub-agent)
- https://github.com/huggingface/transformers (`modeling_modernbert.py`, `masking_utils.py`, main and v4.57.6)
- https://github.com/pytorch/pytorch (`aten/src/ATen/native/mps/operations/Attention.mm` at v2.12.0, v2.13.0 and v2.14.1)
- https://www.apple.com/newsroom/2025/10/apple-unleashes-m5-the-next-big-leap-in-ai-performance-for-apple-silicon/
- https://machinelearning.apple.com/research/exploring-llms-mlx-m5
- https://arxiv.org/abs/2609.22991
- https://peps.python.org/pep-0790/
- https://docs.astral.sh/uv/guides/integration/pytorch/

**System 2** (via sub-agent)
- https://developers.google.com/ml-kit/genai
- https://developers.google.com/ml-kit/genai/prompt/android
- https://developers.google.com/edge/litert-lm/android
- https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
- https://huggingface.co/google/gemma-3n-E2B-it-litert-lm
- https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference
- https://ai.google.dev/gemma/docs/mobile-actions
- https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md
- https://llm.mlc.ai/docs/deploy/android.html
- https://ollama.com/library/qwen3.5
- https://docs.ollama.com/capabilities/structured-outputs
- https://ollama.com/blog/mlx
- https://developer.android.com/training/package-visibility/declaring
- https://pypi.org/project/RapidFuzz/
- https://commons.apache.org/proper/commons-text/apidocs/org/apache/commons/text/similarity/package-summary.html
- https://www.androidauthority.com/how-to-get-gemini-nano-on-pixel-8-8a-3450466/
