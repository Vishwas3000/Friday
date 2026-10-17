# How fast can Laya run on a Pixel 8? Encoder speed on Android

_Last updated: 2026-10-17 · Trust tags: [primary] read at source · [secondary] search/blog/forum · [measured] run here · [UNCERTAIN] unconfirmed or conflicting_

**Question (owner):** "how fast can Laya process on Android phones like mine, using the ONNX model?"

**Method.**
- Web research only. Nothing was run on the phone for this note.
- **[measured]** rows are today's live steps on the Pixel 8 and the research/06 bench. Two Mac-side checks ran without any inference:
  - a static read of our ONNX graphs (op census, no weights loaded);
  - tokenizer-only row lengths for synthetic screens.
- Sub-agents read the ONNX Runtime source, the published benchmarks and the GPU material. Their tags are kept as they reported them.
- The estimator script stayed in the session scratchpad, as in research/04.

## TL;DR
- **Today: 7–13 ms per token in live use** [measured].
  - 390 tokens took 2.9 s and 1,868 tokens took 24.4 s. A "token" here counts both rows (§1).
  - That is 21–35 GFLOPS effective.
  - The same model did 72 GFLOPS (3.8 ms/token) on a cool bench (research/06).
- **What the same chip does elsewhere** [primary]. No BERT-family text encoder has published numbers on a Tensor G3 (dead end), but nearby results do:
  - **Pixel 8a CPU (Tensor G3):** an 86M-parameter ViT-B image tower runs 196 tokens in **313 ms** (LiteRT/XNNPACK, 4 threads, fp16 weights, most likely fp32 math). That is ≈ 113 GFLOPS, or **~2.2 ms per Laya token**.
  - **Tensor G4 CPU, int8:** ONNX Runtime reaches ≈ 340 GOPS on the same ViT, and ExecuTorch ≈ 440.
- **Three causes stack up:**
  1. **ORT 1.20 has no fp16 CPU kernels for MatMul, Gemm, Softmax, Add, Mul or Gelu** [primary, ORT source]. As a result it:
     - casts ~250 MB of fp16 weights to fp32 on **every run**, on one thread;
     - does the math in fp32;
     - skips its fusions;
     - takes a slow fp16 LayerNorm path.

     The maintainers say so directly: "fp32 model shall be faster than fp16 model in CPU" [primary].
  2. **Live conditions cost 2–3× compared with the cool bench.**
     - **Heat:** the Tensor G3 CPU throttles to ~57–66% within minutes [secondary].
     - **Probably the wrong cores:** while Friday operates another app, its process sits in the `foreground` cpuset, which is CPUs 0–7 with no X3 core [primary Pixel source; UNCERTAIN until checked].
  3. **Wasted tokens.** The operation row is padded to the target row, and the target row runs even when the step isn't a tap. At 50 candidates a step costs 1,930 tokens; it needs 585 for a non-tap step or 1,550 for a tap [measured, tokenizer only].
- **Estimates for a cool phone** (today's padded 2-row step):

  | Config | ~400 tokens | ~900 | ~1,300 | ~1,900 |
  |---|---:|---:|---:|---:|
  | Today's config | 1.4 s | 3.7 s | 5.8 s | 9.0 s |
  | int8 | 0.4 s | 1.2 s | 2.1 s | 3.7 s |
  | ORT 1.31 native fp16 | 0.6 s | 1.7 s | 2.8 s | 4.6 s |
  | LiteRT GPU, FP32 [UNCERTAIN] | 0.3–0.6 s | 0.6–1.3 s | 1.4–2.9 s | 1.4–2.9 s |

  Asking the operation first cuts the mean by another ~1.5–2× (§9).
- **Realistic target per step:** **≈ 1 s on typical screens** (~900 tokens today) and **~3 s on the heaviest** (~1,900), sustained, on the CPU with op-first plus int8. With native fp16 instead of int8, expect ~1.5× that. Either way it is 5–10× faster than today. A working Mali GPU path could bring heavy screens to ~1 s, but that is unproven.
- **Top 3 levers, by gain per effort:**
  1. **Ask `operation` first. Run `target` only for CLICK/LONG_PRESS, and never pad.**
     - Gain: ~1.5–2× on the mean, ~3–4× on non-tap steps at 30–50 candidates.
     - Effort: hours, with no accuracy risk.
  2. **Leave the fp16-cast path.**
     - **First choice:** int8, if its accuracy gate passes (3.5× measured).
     - **Otherwise:** ORT 1.31 native fp16 (~2×), or fp32 math on ORT ≥ 1.23 (~1.3×).
     - **Effort:** a dependency bump or re-quantization, plus a parity run.
     - **Caution:** true fp16 can overflow. Laya's own LiteRT port found 1.4e4 activations.
  3. **Fix the run conditions.**
     - Check which cores the step runs on (one adb line).
     - Pin the threads to the A715s if they wander.
     - Pace steps by thermal headroom.
     - Gain: up to ~2× on live steps [UNCERTAIN].

## 1. What one step costs [measured inputs; FLOP arithmetic]

**Token counts cover both rows.** `LayaEngine.step` reports `inputIds.sumOf { it.size }`, and `collate` pads the `operation` and `target` rows to the longer one. So a "1,868-token" step is 2 rows × 934 tokens, and the padding in the shorter row is computed too [primary, code read].

**FLOPs:**
- **Linear layers:** 2 × 124.5M non-embedding params = **249 MFLOP per token**. That is the encoder's 110.3M plus the head's 14.2M; the head's 2 layers run over every token [primary, config/code, research/04].
- **Attention:** 4·L²·768 per layer per row, in **all 24 attention layers** (22 encoder + 2 head). The export does full attention with a mask (§6).

| Step tokens (2 × L) | L per row | Linear GFLOP | Attention GFLOP | Attention share | Saved if the local layers were truly windowed | Time | Effective GFLOPS |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 636 (research/06 bench, cool) | 318 | 158 | 15 | 9% | 3% | 2.4 s | **72** |
| 390 | 195 | 97 | 6 | 5% | 1% | 2.9 s | 35 |
| 924 | 462 | 230 | 32 | 12% | 5% | 12.3 s | 21 |
| 1,320 | 660 | 329 | 64 | 16% | 8% | 17.3 s | 23 |
| 1,868 | 934 | 465 | 129 | 22% | 11% | 24.4 s | 24 |
| next step (thermal) | — | — | — | — | — | 38.8 s | — |

**What the CPU can do** [UNCERTAIN, derived from pipe counts]:
- The X3 has 4×128-bit FP/SIMD pipes [secondary]; the A715 is taken to have 2.
- **Four threads on X3 + 3×A715** peak at about **207 GFLOPS fp32, 414 fp16, ~830 GOPS int8 (SDOT) and ~1.6 TOPS (I8MM)**.
- **Four A715s alone** peak at 152 / 303 / 607 / 1,213.

**How to read it:**
- Live steps ran at **10–17% of the fp32 peak**, and the cool bench at 35%. The model didn't change between them, so **2–3× of today's time is conditions** (heat, cores, memory), not the model.
- The int8 bench (677 ms) works out to ≈ 256 GOPS. Laya's measured int8 is in line with:
  - published int8 on a Tensor G4 (§7);
  - llama.cpp prefill on Tensor G3 phones: Gemma 3 1B Q4_0 at 141 tok/s ≈ 200 GOPS, and Llama 3.2 1B Q8_0 at 64 tok/s ≈ 125 GOPS [secondary, research/13].

## 2. Where the tokens go [measured, tokenizer only, synthetic Settings-like labels]
The rows were rebuilt exactly as `LayaInput.kt` builds them: the head, then the state, then `[SEP]`. Labels look like `"Network & internet (LinearLayout)"`.

| Candidates | operation row | target row | Today: 2 × max | Unpadded op + target | op row only |
|---:|---:|---:|---:|---:|---:|
| 5 | 184 | 122 | 368 | 306 | 184 |
| 10 | 225 | 209 | 450 | 434 | 225 |
| 20 | 316 | 399 | 798 | 715 | 316 |
| 30 | 405 | 587 | 1,174 | 992 | 405 |
| 40 | 496 | 777 | 1,554 | 1,273 | 496 |
| 50 | 585 | 965 | 1,930 | 1,550 | 585 |
| 80 | 856 | 1,533 | 3,066 | 2,389 | 856 |

**Today's steps map to** about 6, 23, 34 and 48 candidates (390 / 924 / 1,320 / 1,868 tokens).

**Why the rows grow:**
- The target row carries every candidate twice, once in `state.ui` and once as an option.
- The operation row carries 12 fixed operation options instead.

**What asking the operation first does:**
- **Non-tap steps** need only the op row: 190–565 tokens, 30–49% of today's.
- **Tap steps** need both rows unpadded: 80–87% of today's.
- **At p(CLICK) = 0.5** that is **55–67% of today's tokens**. CLICK + LONG_PRESS are 53% of AndroidControl steps [primary, research/04].

The two rows are independent forward passes, so the operation answer doesn't change (research/04).

## 3. What ONNX Runtime 1.20 actually runs [primary, ORT source at each tag, read via sub-agent]

**On 1.20.0 the CPU EP has no fp16 kernel for MatMul, Gemm, Softmax, Add/Sub/Mul/Div, Erf, Gelu, Attention or MultiHeadAttention.**
- `OperatorKernels.md` at v1.20.0 gives MatMul as "T = tensor(double), tensor(float), tensor(int32), tensor(int64), …", and Gemm and Softmax as double/float only.
- `cpu_execution_provider.cc` registers only a few fp16 kernels:
  - a CNN set;
  - LayerNormalization, which computes in fp32 inside;
  - IsNaN, Expand and Gather;
  - the contrib RotaryEmbedding, GQA and MatMulNBits.

**What 1.20 does to our fp16 graph:**
- **It casts every run.**
  - `InsertCastTransformer` runs once, after partitioning, and nothing folds constants after it. Its rule: "If the node's input is float16 and currently the node is not assigned to any EP we need to insert a cast to float".
  - So ~250 MB of fp16 weights become ~500 MB of fp32 on **every `Run()`**. The conversion goes through `MlasConvertHalfToFloatBuffer` with no thread pool, so it is single-threaded.
  - With the arena off, those 500 MB are also freshly allocated each run [UNCERTAIN cost].
- **The math is fp32.** MatMuls run MLAS SGEMM in fp32. Isolated fp16 nodes are moved to fp32 to cut cast churn (`ForceSingleNodeCPUFloat16ToFloat32`).
- **The fusions are lost.** Unassigned fp16 nodes fail `IsSupportedProvider`, so the Gelu, LayerNorm, SkipLayerNorm, BiasGelu and MatMulScale fusions skip them.
- **LayerNorm is slow.** The fp16 LayerNorm converts each row to fp32 through two temporary allocations per row. The 1.24.1 rewrite (PR #25680) reports "performance improved from 22sec to 0.6sec".
- **This is known behaviour:**
  - "fp32 model shall be faster than fp16 model in CPU" (#19437).
  - "ORT computes fp16 with fp32 operators on CPU now" (#10919).
  - Android 14 with ORT 1.18: "FP16 model inference is slower compared to FP32" (#22242).
  - On a Raspberry Pi 5, casts took 54% of the time (#25824).

**Our graph in numbers** [measured, static read of `models/*.onnx`, no inference]:
- **fp16 graph:**
  - 3,809 nodes, including 589 `Cast` and 150 `MatMul` + 2 `Gemm`.
  - 96 MatMuls and both Gemms take an fp16 weight directly.
  - The other 54 MatMuls are the 48 attention products (QKᵀ and AV in 24 layers) plus the head's 6 in-projections, whose weights arrive through a `Split`.
  - All 25 `Softmax` nodes sit between Casts. These are the fp32 islands the converter kept (research/06).
- **int8 graph** (`quantize_dynamic` with its defaults):
  - 98 `MatMulInteger` + 98 `DynamicQuantizeLinear`. This includes the scorer and both `act_head` layers.
  - Still fp32: all 54 of the other MatMuls, because `MatMulConstBOnly` "Default is True for dynamic mode" [primary, ORT `quantize.py`]. That covers the 48 attention products and the head's 6 in-projections, whose weights arrive through a `Split`.
  - **The 256k embedding table is quantized too, to uint8 with a single scale.**

**What changed after 1.20** (all on ARM64, so Android):

| Version | Change for fp16 graphs |
|---|---|
| 1.23.0 | Level-4 `FuseInitializers` (PR #24726) widens fp16 weights to fp32 **once, at load**. RAM becomes fp32-sized, but there is no per-run cast |
| 1.24.1 | fp16 Add/Sub/Mul/Div/Erf kernels; a fast Eigen-fp16 LayerNorm |
| 1.26.0 | fp16 Gelu (opset 20); `yield` in the ARM64 spin loop, plus spin-duration knobs |
| 1.29.0 / 1.30.0 | **Native fp16 MatMul/Gemm** via MLAS HGEMM (#29709). 1.30 limits it to accelerated builds, which include Android ARM64 (#32301). It **accumulates in fp16** (`vfmaq_f16`, no `fmlal`), an accuracy risk at K = 768/1152. Softmax still has no fp16 kernel in 1.31 |
| open | PR #32562 would make native fp16 opt-in (`session.cpu_fp16_mode`, default off) |

**Version notes:**
- The latest `onnxruntime-android` is **1.31.0** (2026-10-09) [primary, Maven].
- ORT ≥ 1.29 adds the INTERNET permission and telemetry (research/13). Going past 1.28 needs a manifest `tools:node="remove"`.

**A trap in "fp16 parity"** [inference from primary sources; UNCERTAIN until measured]:
- research/06's fp16 parity (Δlogit 0.006) was measured on a runtime that did fp32 math between the casts.
- On ORT ≥ 1.24.1 the elementwise ops run in real fp16, and on ≥ 1.29 so do the MatMuls.
- Laya's LiteRT port of the same encoder found rows "up to 1.4e4, whose squares overflow fp16 inside LayerNorm" from layer 12. It needed a power of two moved out of the GeGLU product in layers 11–12 [primary, §8].
- So any ORT bump must re-run parity on real screens and watch for inf/NaN.

**The int8 paths:**
- **`DynamicQuantizeMatMul` / `MatMulInteger`** (what `quantize_dynamic` emits) have used SMMLA/UMMLA (i8mm) on ARM64 since 1.17: "up to 1.33x … compared to the optimized UDOT qgemm kernel". So the 677 ms run already used i8mm.
- **Why per-tensor int8 hurts Laya** [UNCERTAIN, inference]:
  - Rows carrying 1.4e4 values set the single activation scale for everything else.
  - Laya's own issue #790 puts the loss in "the encoder activations".
- **Finer-grained options:**
  - **`MatMulNBits` 8-bit at `accuracy_level=4`:** block-wise weights, with activations quantized to int8 per block. It has ARM64 kernels only from **1.23.0**; 1.20 accepts only 4-bit. On ARM64 it uses SDOT/USDOT, not SMMLA.
  - **llama.cpp Q8_0** (§7).
- **KleidiAI** helps a core like the X3 or A715, which has no SME, only for 4-bit `MatMulNBits` at level 4 (i8mm kernels, 1.22+).
- **bf16 for fp32 graphs:** `mlas.enable_gemm_fastmath_arm64_bfloat16=1` (since 1.17) runs fp32 MatMul on BFMMLA where the CPU has BF16.
  - The PR reports "1.2x-1.76x performance improvement compared to sgemm" on BERT-class models (Graviton3).
  - It is one `addConfigEntry`, but accuracy must be gated again.

**Threads:**
- `intra_op_num_threads` counts the calling thread. Android's default is `hardware_concurrency()/2`, which is 4 here.
- **Thread affinity is compiled out on Android.** `env.cc` guards it with `#if !defined(__APPLE__) && !defined(__ANDROID__) …`, still in 1.31. So `session.intra_op_thread_affinities` does nothing on the phone.
- MLAS SGEMM "is segmented as a 1D partition", one equal slice per thread, so the slowest core sets the pace. That is the likely reason 8 threads lost to 4 (1,201 vs 677 ms): some slices landed on A510s [inference].
- Java exposes `addConfigEntry`, so the spinning keys are reachable. There is no Java hook for custom thread creation.

**The XNNPACK EP:**
- On 1.20 its MatMul accepts only an A input of at most 2 dimensions with concrete sizes, and a constant 2-D B.
- Our 3-D dynamic MatMuls therefore stayed on MLAS, which explains 2,295 vs 2,400 ms.
- 1.23 lifts the rank limit (#24908).

**Fusion:**
- No ORT version fuses ModernBERT attention. The bias-free fused Wqkv, plus RoPE applied before SDPA, doesn't match the BERT patterns.
- The CPU `MultiHeadAttention` and `Attention` contrib ops are fp32-only.
- Once the nodes run in fp32, the LayerNorm and Gelu fusions can apply.

## 4. Which cores Laya gets [primary AOSP/Pixel source; effect UNCERTAIN until checked on the phone]

**Pixel 8 cpusets** (`device/google/zuma/conf/init.zuma.rc`, "Setup final cpuset"):
```
write /dev/cpuset/top-app/cpus 0-8
write /dev/cpuset/foreground/cpus 0-7
write /dev/cpuset/background/cpus 0-3
```
- CPUs 0–3 are the A510s, 4–7 the A715s, and 8 the X3.
- The same file caps the `bg` group at `uclamp_max 130`.
- It gives `background` 1,024 cpu.shares, against 20,480 for `foreground` and `top-app`.

**Where Friday's process sits while it operates another app:**
- The system binds an AccessibilityService with `BIND_AUTO_CREATE | BIND_FOREGROUND_SERVICE_WHILE_AWAKE | BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS | BIND_INCLUDE_CAPABILITIES` (`AccessibilityServiceConnection.java`). There is no `BIND_SCHEDULE_LIKE_TOP_APP`.
- So the process should get the default scheduling group, which joins the **`foreground` cpuset: CPUs 0–7, with no X3** (`task_profiles.json`: `CPUSET_SP_FOREGROUND` → cpuset `foreground`).
- Only when Friday's own activity is on top does it get `top-app` (0–8) [inference from AOSP; check on the phone].
- `setProcessGroup(…, THREAD_GROUP_DEFAULT)` "move[s] all non-background priority threads to the foreground scheduling group, but … leave[s] background priority threads alone" (`Process.java`).
  - So if a thread set to background priority ever builds the ORT session, ORT's pool would stay on the A510s at a low frequency cap.
  - Friday's threads use the default priority today [code read].

**What it means:**
- The research/06 bench may have had the X3, if Friday was on top; live steps don't.
- Four A715s peak ~27% below X3 + 3×A715.
- Four threads in a 0–7 cpuset can land on an A510 when the app being operated keeps an A715 busy. One slow slice then stalls the GEMM (§3).
- **Check during a live step (one adb line):** `adb shell 'p=$(pidof dev.layatest.agent); cat /proc/$p/cpuset; ps -T -p $p -o TID,PSR,PCY,NI,CMD'`.
  - The `PCY` column shows `fg` or `ta`.
  - A `PSR` of 0–3 on busy threads would confirm A510 placement.
- **Possible fixes, cheapest first:**
  - **3 vs 4 threads, measured live.**
  - **A few-line JNI `sched_setaffinity(0, {4–8})`** on the thread that creates the session. Linux threads inherit their creator's affinity mask, and the per-session pool is created there [UNCERTAIN].
  - **An ADPF hint session.** Pixel enables `qos_adpf_enable` for the `fg` group too [primary]. But it needs the pool's thread ids.

## 5. Memory: the arena switch may cost more at long rows [primary allocator config; effect UNCERTAIN]
- **What arena-off does.** With the arena off, ORT returns every intermediate to bionic's allocator.
- **Why that hurts big buffers.** Android's Scudo caches freed large blocks only up to **2 MB**: `AndroidNormalConfig` has `DefaultMaxEntriesCount = 32U` and `DefaultMaxEntrySize = 2UL << 20`. Bigger buffers are unmapped, then page-faulted back in on every run.
- **Which buffers are big:**
  - **Attention intermediates** (§6). At L = 934, one [2, 12, 934, 934] tensor is 42 MB in fp16 and 84 MB in fp32, and each layer makes about six. That adds up to roughly 9–12 GB of freshly faulted memory per pass, against ~1 GB at L = 318.
  - **The fp32 weight copies** on 1.20 (§3).
- **What to re-measure.** research/06 compared arena on and off only at L = 318, where the difference was small. Re-measure at L ≈ 900 before treating arena-off as free.

## 6. Sequence length, local attention, and what the ONNX graph really does

**ModernBERT's design** [primary, arXiv 2412.13663 §2.1.2]:
- **Layer pattern:** "every third layer employs global attention with a RoPE theta of 160,000", and "the remaining layers use a 128 token, local sliding window attention".
- **Where the speed comes from:** GPU kernels ("We use Flash Attention's variable length attention and RoPE implementations") and unpadding.
- The paper says nothing about CPU or edge inference.

**The export keeps nothing of the window:**
- **transformers v4.57.6, SDPA path** [primary, `modeling_modernbert.py`]: local layers call `F.scaled_dot_product_attention(..., attn_mask=sliding_window_mask)` over the whole sequence. The mask is a dense `|i − j| ≤ 64` band built with `torch.abs(rows - rows.T)`.
- **Our fp16 graph** [measured, static read]: every encoder layer, sliding or global, runs the same chain over the full L × L: `MatMul(QKᵀ) → Add(mask) → Cast → Softmax → Cast → IsNaN → Where → MatMul(AV)`.
- **ORT has no bidirectional sliding-window kernel to map it to.** `GroupQueryAttention.local_window_size` is "left_window_size for causal local attention (like Mistral). Must be -1 when causal is 0" [primary, ORT `ContribOperators.md`].

**How the cost scales** per row of L tokens:
- **Linear layers** grow with L, at 249 MFLOP per token.
- **Attention as exported** grows with L² in all 24 layers. It only matches the linear cost at L ≈ 3,400, so at our L ≤ ~950 it is 5–22% of the FLOPs (§1).
- **True windowing** would make 14 of those layers grow with L × 129. That saves ~11% of the FLOPs at L = 934 and ~1% at L = 195. **Not worth a custom kernel at our lengths.**
- **FLOPs understate attention here, though.** The unfused chain writes and re-reads about six full L × L tensors per layer, in two precisions. At L = 934 that is roughly 20 GB of memory traffic per pass. Once the linears are int8, attention becomes the larger half at long rows [UNCERTAIN until profiled].
- **What would fix it:** a fused kernel. Options are the ONNX `Attention`-23 op (CPU fp16 since 1.23, with a mask input), or LiteRT/GPU. Both are later levers (§11).

## 7. Published numbers (on-device, per sequence, batch 1)
No published latency exists for any BERT-family **text** encoder on a Tensor G3, and none for mmBERT-base or XLM-R on any phone [dead end]. The nearest anchors:

| Model (non-embedding params) | Tokens | Phone (SoC) | Runtime, precision, threads | Latency | ≈ GFLOPS | Tag |
|---|---:|---|---|---:|---:|---|
| SigLIP2 ViT-B/16 image tower (~86M) | 196 | **Pixel 8a (Tensor G3)** | LiteRT CPU XNNPACK, fp16 weights (math precision unstated; XNNPACK uses fp32 unless fp16 is forced), 4 | **313 ms** | ~113 | [primary] |
| same | 196 | Pixel 8a | LiteRT GPU delegate (only 44 of 809 ops on GPU) | 964 ms | — | [primary] |
| ViT-B/16 (~86M) | 197 | Pixel 9 Pro XL (Tensor G4) | CPU int8, 4 threads: ExecuTorch / **ORT** / LiteRT | 80 / **104** / 469 ms | 440 / **340** / 75 | [primary] |
| ViT-B/16 | 197 | Pixel 9 Pro XL, **Mali-G715** | ExecuTorch Vulkan fp16 | 392 ms (4.9× slower than CPU int8) | ~90 | [primary] |
| **Laya multilingual** (mmBERT-base + Laya heads) | 256 per question | Galaxy S26 (8 Elite Gen 5) | LiteRT GPU FP32 / NPU / CPU | 51.9 / 36.5 / 163 ms | ~1,300 / — / ~420 | [primary] |
| Gecko-110M (~85M) | 256 / 512 / 1,024 | Galaxy S23 Ultra (8 Gen 2) | LiteRT CPU dynamic int8, 4 · GPU | 148 / 353 / 954 · 76 / 173 / 397 ms | ~220 at 1,024 (CPU) | [primary] |
| EmbeddingGemma-300m (~100M) | 256 / 512 / 1,024 / 2,048 | Galaxy S25 Ultra (8 Elite) | LiteRT CPU int4/int8, 4 · GPU | 66 / 169 / 549 / 2,455 · 64 / 119 / 241 / 683 ms | ~510 at 1,024 (CPU) | [primary] |
| mmBERT-small (~45M, same layout as ours) | 256 / 384 / 512 | Galaxy S26 | LiteRT CPU, fp16 weights, 4 · GPU fp16 | 39 / 63 / 97 · 15 / 22 / 31 ms | — | [primary] |
| BERT-base (86M) | 128 | Pixel 4 (SD855) | TFLite CPU (precision unstated), 4 | 342 ms | ~65 | [primary] |
| MobileBERT text classifier (~20M) | 128 | Pixel 6 (Tensor G1) | MediaPipe CPU, dynamic int8 | 58 ms | — | [primary] |

**Prefill throughput on the same chips** [primary unless tagged]:
- **Llama 3.2 1B, Pixel 9 Pro XL CPU** (int4 weights + int8 activations, 4 threads, 256-token prompt):
  - ExecuTorch 271–297 tok/s and ORT 256–268 tok/s, ≈ 500–580 GOPS.
  - On the Mali-G715 with ExecuTorch Vulkan: 530–540 tok/s.
- **Qwen3-0.6B, LiteRT-LM over OpenCL on a Pixel 8a:** 554–572 tok/s prefill, ≈ 0.5 TFLOPS. "Replacing 916 out of 916 node(s) with delegate (LITERT_CL)".
- **ML Drift on a Pixel 9 (Mali-G715):** Gemma 2B q8 at 796 tok/s.
  - That is ~94% of the GPU's fp16 peak, so it probably relies on int8 dot products.
  - Don't scale it to an fp32 encoder [UNCERTAIN].

**Precision results:**
- **TFLite fp16 vs fp32** (Panopoulos et al. 2023): "FP16 models had the same latency as FP32" (0.97–1.06×) unless XNNPACK's fp16 mode is forced. Dynamic int8 was 2.27× on DistilBERT [primary].
- **XNNPACK native fp16:** ≈ 2× on ARMv8.2 CPUs, measured on vision models including a Pixel 7 [primary, TF blog 2023].
- **Gemma3-1B prefill on an S24 Ultra CPU:** fp32 49 → int8 177 tok/s, 3.6× [primary]. That matches our 3.5×.

## 8. GPU on the Mali-G715
**Verdict:** only one GPU path has credible transformer speed on Mali: **LiteRT (ML Drift over OpenCL)**.
- Apps on the Pixel 8 can use it, and a public LiteRT conversion of multilingual Laya already exists.
- It is untested on Mali, and Mali has open attention bugs.
- Every other GPU route is slower than the CPU or broken.

**OpenCL is open to apps** [primary]:
- Pixel 8 firmware (Android 14 and 15) lists `libOpenCL.so` and `libOpenCL-pixel.so` in `/vendor/etc/public.libraries.txt`.
- Apps must declare them with `<uses-native-library>`; LiteRT's AAR manifest does exactly this.
- LiteRT's GPU path runs fully on OpenCL on a Pixel 8a (same Tensor G3) on Android 16 (LiteRT #8619; LiteRT-LM #3986).
- One report said "the Tensor G3 chip does not expose OpenCL". The likeliest cause is the missing manifest entries [UNCERTAIN].

**Peak:** about **1.59 TFLOPS fp32 and 3.19 fp16**. That is Arm's "256 FP32 ops … per cycle per core" × 7 cores × 0.89 GHz [derived from primary].

**Laya on LiteRT** (`litert-community/Laya-Multilingual-LiteRT`, mmBERT-base + the two head layers + scorer, Galaxy S26) [primary]:
- **Speed:** **GPU FP32 51.9 ms per 256-token question**, NPU 36.5 ms, CPU 163 ms. The host embedding lookup adds 14.6 ms.
- **Parity:** 81 of 81 answers match the fp32 reference on GPU FP32.
- **GPU FP16 changed 1 of 81 answers** and is not recommended.
- **The recipe:**
  - fixed S256/S512 graphs;
  - the embedding lookup on the host, from a 393 MB fp16 table, because the GPU delegate rejects the in-graph lookup;
  - rotary tables baked per layer type;
  - attention kept at rank 4;
  - fp16-safe rewrites: LayerNorm inputs scaled by a power of two, a −1e4 mask, and GeGLU rescaled in layers 11–12.
- **The conversion script is not published.** Only the host code, an Android sample (LiteRT 2.2.0, GPU FP32) and fixtures are. "Other Android GPUs and NPUs have not been validated here."

**Estimate for the Pixel 8 GPU at FP32:** **~140–300 ms per 256-token row, ~0.55–1.2 ms per token** [UNCERTAIN, extrapolated from the S26 using ML Drift's Mali/Adreno ratio]. That is 3–7× today's cool bench rate per token, at fp32 accuracy.

**Mali pitfalls seen on a Pixel 8a** [primary, LiteRT issues]:

| Issue | Status |
|---|---|
| Masked-attention miscompute (#8593) | Fixed in 2.1.6 |
| Rank-3 attention miscompute (#8619) | Workaround: rank 4 |
| Rank-5 tensors rejected; ModernBERT's `qkv.view(bs, S, 3, H, D)` needs a rewrite (litert-torch #1079) | Needs a rewrite |
| `SUM` accumulates in fp16 (#9249) | Open |
| Fused SDPA "hard-crashes the Pixel 8a on its first GPU run" (#8620) | Keep attention decomposed |

GPU initialisation costs 1.2–1.7 s on an S25 Ultra (EmbeddingGemma card). It is paid once per load.

**Other routes:**
- **ExecuTorch Vulkan:** ViT is 4.9× slower than CPU int8 on the Mali-G715. Before May 2026 its fp16 shaders also ran at fp32 rate on Mali (#19287) [primary].
- **ncnn Vulkan:** a ViT is ~4× slower than the CPU on Mali-G720, and Pixel 8 drivers break ncnn's fp16 [primary].
- **MLC on Mali:** "both prefill and decode speeds are slower than on the CPUs" [primary].
- **llama.cpp GPU:** OpenCL is Adreno-only, and Vulkan hangs under sustained load on a Tensor G4 [primary].
- **ORT WebGPU EP (Dawn → Vulkan):** in the full AAR and listed on a Pixel 8a, but no Android/Mali performance data exists, and a QDQ model failed (#28423). Unproven.

## 9. Estimates for our token counts [UNCERTAIN: model calibrated on two measured points and published rates]

**Model:**
- Time per row = linear FLOPs / r_lin + attention FLOPs / r_att.
- Rates (GFLOPS):

  | Row | r_lin | r_att | Basis |
  |---|---:|---:|---|
  | A | 76 | 45 | Fitted to the research/06 bench: 2.4 s at 2 × 318 |
  | B | 110 | 50 | Pixel 8a ViT on XNNPACK, 113 (likely fp32 math) |
  | C | 190 | 60 | ~2× fp32 [UNCERTAIN] |
  | D | 350 | 55 | Fitted to research/06's 677 ms int8 |
  | E | 230–490 | 230–490 | §8, with rows padded up to 256/512/1,024 buckets |

- Cool phone, 4 big-core threads.

**Today's input (two rows padded to the longer one), seconds:**

| Config | ~400 | ~900 | ~1,300 | ~1,900 |
|---|---:|---:|---:|---:|
| Today, live [measured] | 2.9 | 12.3 | 17.3 | 24.4 |
| A. Today's fp16 graph, ORT 1.20, cool | 1.4 | 3.7 | 5.8 | 9.0 |
| B. fp32 math (fp32 file, or ORT ≥ 1.23 widening fp16 weights at load) | 1.0 | 2.7 | 4.3 | 6.8 |
| C. Native fp16, ORT 1.31 (fp16 accumulation; parity risk) | 0.6 | 1.7 | 2.8 | 4.6 |
| D. int8 linears (dynamic, i8mm; accuracy gate pending) | 0.4 | 1.2 | 2.1 | 3.7 |
| E. LiteRT GPU FP32 on the Mali-G715 | 0.3–0.6 | 0.6–1.3 | 1.4–2.9 | 1.4–2.9 |

**With the operation asked first, rows unpadded** (≈ 55–67% of today's tokens). Each cell is the mean at p(CLICK) = 0.5, followed by (non-tap step / tap step):

| Config | ~400 | ~900 | ~1,300 | ~1,900 |
|---|---|---|---|---|
| A | 0.9 (0.7 / 1.2) | 2.3 (1.3 / 3.2) | 3.2 (1.8 / 4.6) | 4.6 (2.4 / 6.9) |
| B | 0.7 (0.5 / 0.8) | 1.6 (1.0 / 2.3) | 2.3 (1.3 / 3.4) | 3.4 (1.7 / 5.2) |
| C | 0.4 (0.3 / 0.5) | 1.0 (0.6 / 1.5) | 1.5 (0.8 / 2.2) | 2.3 (1.1 / 3.4) |
| D | 0.2 (0.2 / 0.3) | 0.7 (0.4 / 1.0) | 1.1 (0.6 / 1.6) | 1.7 (0.8 / 2.7) |
| E | 0.2–0.4 | 0.4–1.0 | 0.6–1.4 | 1.0–2.2 |

**Reading it:**
- **Today's live steps are 2.1–3.3× slower than row A**, the same model on a cool phone. Heat alone explains ~1.5–1.9× (§10). The rest is probably cores (§4), allocation (§5), or both.
- **Threads:** keep 4 on the A715s. Use 5 (adding the X3) only when Friday is on top. 3 may beat 4 while the operated app keeps an A715 busy. These gains sit inside the live-vs-cool gap, not on top of it.
- **For sustained use, multiply CPU rows by ~1.5–1.9.**
- research/04's pre-measurement estimate (1.3–2.7 s with int8 linears for ~650-token rows) holds up against row D.
- **Realistic target per step: ≈ 1 s on typical screens and ~3 s on the heaviest, sustained.** That is row D plus op-first × 1.5–1.9: 1.0–1.3 s and 2.6–3.2 s. Row C gives 1.5–1.9 s and 3.5–4.4 s.

## 10. Heat and sustained use
**Measured here:**
- research/06: ~8 back-to-back runs took the same fp16 step from 2.4 s to 8.2 s.
- Today: 24.4 s, then 38.8 s on the next step.

**Published for the Tensor G3** [secondary]:
- **3DMark Wild Life Stress, 20 minutes:** the Pixel 8 keeps 52.5% of its first-loop score, the Pixel 8 Pro 58.7%. The base model has no vapor chamber.
- **CPU Throttling Test, Pixel 8 Pro:** the minimum is ≈ 57% of the maximum (Beebom); 66% average with throttling from "2-3 minutes" in (Mobilissimo).

**What drives it:**
- Today's fp16 path keeps four big cores busy for 3–25 s per step, so a multi-step task reaches the throttle point.
- Energy per step scales with tokens × cost per token. Levers 1 and 2 (§11) cut energy, not just time, and that is what keeps later steps fast.

**Pacing:**
- `PowerManager.getThermalHeadroom(forecastSeconds)` returns 1.0 at "throttled at THERMAL_STATUS_SEVERE". It tracks skin temperature, and calling it more than about once a second returns NaN [primary, AOSP].
- Use it to space steps, or drop to 3 threads, as headroom nears 1.0.

**Spinning:** before 1.26, ORT's ARM64 spin loop had no pause hint [primary]. `session.intra_op.allow_spinning=0` trades a little latency for less wasted power between ops.
- An x86 hybrid CPU measured 30.5 → 33.5 ms on DistilBERT at 4 threads [primary].
- The effect on the Pixel is UNCERTAIN.

**On the GPU:** a GPU path moves the heat to a more efficient unit. But a Tensor G4 user saw Mali Vulkan hang "under sustained compute pressure", worst when backgrounded [primary, single reporter]. The OpenCL path has not been tested for sustained load.

## 11. Levers, ranked by gain per effort

| # | Lever | Gain | Effort / risk | Tag |
|---:|---|---|---|---|
| 1 | **Ask `operation` first. Run `target` only for CLICK/LONG_PRESS, unpadded.** Also stop padding the op row in `collate` | ~1.5–2× mean; ~3–4× on non-tap steps at 30–50 candidates; the quadratic attention shrinks more | Hours in `LayaEngine.step`. No accuracy risk: the rows are independent passes | [measured tokens; estimated time] |
| 2 | **Leave the fp16-cast path.** int8 if its gate passes; else ORT 1.31 native fp16; else fp32 math (ORT ≥ 1.23, +~250 MB RAM) | 3.5× (int8, measured); ~2× (fp16); ~1.3× (fp32) | A dependency bump or re-quantization, plus a parity run on real screens. Watch for fp16 overflow (§3) and the INTERNET permission on ORT ≥ 1.29 | [measured / primary / UNCERTAIN] |
| 3 | **Fix run conditions.** Check cores; pin to the A715s (JNI `sched_setaffinity` before session creation) or try 3 threads; pace by thermal headroom; re-check arena-off at long rows | Up to ~2× on live steps | The check takes one adb line; a fix takes a day | [UNCERTAIN] |
| 4 | **LiteRT GPU (ML Drift/OpenCL) at FP32** | ~1.3–2.6× over CPU int8 and ~2.3–4.9× over CPU fp32 at long rows, with fp32 accuracy | Weeks: redo the unpublished conversion for Laya-Android, add buckets up to 1,024, a host embedding table, a second runtime, and Mali bugs. **First run the card's Android sample on the Pixel 8 as-is** to measure Mali speed before any conversion | [UNCERTAIN] |
| 5 | **Fused attention.** Rewrite the L × L chain to ONNX `Attention`-23 (fp16 CPU kernel since 1.23) | ~1.3–1.5× at long rows, after lever 2 | Graph surgery plus parity | [UNCERTAIN] |
| 6 | **Distil to mmBERT-small** (42M non-embedding) | ~2.6× fewer linear FLOPs | Retraining (research/15) | [primary sizes] |

**Alternative CPU runtime, worth a look:**
- llama.cpp merged Laya support on 2026-10-02 (PR #29818, "add /v1/systemone API (models: laya, …)", with a ModernBERT graph).
- `ggml-org/Laya-GGUF` ships Q8_0 (449 MB) and BF16 [primary, via sub-agent].
- Q8_0's block-wise int8 is the finer-grained quantization §3 recommends.
- The Writer plan already embeds llama.cpp (research/13).
- Untested on Laya-Android, which needs its own conversion, and there are no Tensor G3 encoder numbers [UNCERTAIN].

## To measure next on the Pixel 8 (cheap first)
1. **Where the time goes.** Run `SessionOptions.enableProfiling(path)` on one ~900-token step on a cool phone. The JSON splits time per node: `InsertedPrecisionFreeCast_*`, MatMul, the L × L chain, LayerNorm.
2. **Which cores.** Run the §4 adb line during a live step. Then run the same step with Friday's own activity on top, and with 3 threads.
3. **Arena on vs off at L ≈ 900**, on a cool phone (§5).
4. **ORT 1.31 with today's fp16 graph:** speed, plus parity on recorded screens. Watch for inf/NaN, and strip the INTERNET permission. **Done 2026-10-18 (research/18 §6b):** fp16 on 1.31 is erratic and further off. 8-bit MatMulNBits on 1.31 shipped instead: 1.73–1.85 s vs 3.83–3.90 s on the fixture.
5. **Sustained:** 10 steps back to back at ~900 tokens, logging step time and `getThermalHeadroom(10)`.
6. **`adb shell cat /vendor/etc/public.libraries.txt`.** This confirms the OpenCL libraries on Android 16. It also shows whether `libedgetpu_litert.so` is now public on the Pixel 8; it is on Pixel 9 firmware but not in the Pixel 8 Android 15 dump [UNCERTAIN].
7. **Only if going GPU:** run the Laya-Multilingual-LiteRT Android sample on the Pixel 8. It downloads the S256 graph plus the 393 MB embedding table. Measure S256 GPU FP32 latency and parity against the CPU.

## Dead ends
- **Published numbers:** no BERT-family text encoder has published latency on a Tensor G3, and none exists for mmBERT-base or XLM-R on any phone.
  - The ExecuTorch dashboard's MobileBERT input is ~8 tokens.
  - MLPerf Mobile has no Pixel rows.
  - AI Benchmark and Geekbench AI pages weren't readable.
- **Accelerators:**
  - NNAPI is deprecated, and the Pixel 8's only NNAPI driver is the TPU one.
  - The QNN EP is Qualcomm-only.
  - The Tensor TPU is listed for G5/G6 only in the Tensor SDK.
- **ORT settings:**
  - **XNNPACK EP on ORT 1.20 for this graph:** its MatMul is 2-D and static-shape only [primary + measured].
  - **Thread affinity through ORT on Android:** compiled out [primary].
  - **8 threads:** slower than 4 [measured].
- **Attention:**
  - **True sliding-window attention in ORT:** there is no bidirectional local kernel, and it would be ≤ 11% of FLOPs at our lengths anyway.
  - **ModernBERT attention fusion in ORT:** none, in any version.
- **GPU routes:**
  - **Slower than the CPU for encoders, or broken:** ExecuTorch Vulkan, ncnn Vulkan, MLC, llama.cpp OpenCL/Vulkan on Mali.
  - **LiteRT GPU FP16 for Laya:** changed 1 of 81 answers.
- **Thermal:** no sustained-ML thermal study exists for any Pixel.

## Sources

**ONNX Runtime** (read at the tagged source, mostly via sub-agent)
- https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/maven-metadata.xml
- https://github.com/microsoft/onnxruntime/blob/v1.20.0/docs/OperatorKernels.md
- https://github.com/microsoft/onnxruntime/blob/v1.20.0/onnxruntime/core/providers/cpu/cpu_execution_provider.cc and the same file at v1.31.0
- https://github.com/microsoft/onnxruntime/blob/v1.20.0/onnxruntime/core/optimizer/insert_cast_transformer.cc
- https://github.com/microsoft/onnxruntime/blob/v1.20.0/onnxruntime/core/session/inference_session.cc
- https://github.com/microsoft/onnxruntime/blob/v1.20.0/onnxruntime/core/platform/posix/env.cc
- https://github.com/microsoft/onnxruntime/blob/v1.20.0/include/onnxruntime/core/session/onnxruntime_session_options_config_keys.h
- https://github.com/microsoft/onnxruntime/blob/v1.31.0/onnxruntime/core/mlas/inc/mlas.h
- https://github.com/microsoft/onnxruntime/blob/v1.31.0/onnxruntime/contrib_ops/cpu/quantization/matmul_nbits.cc
- https://github.com/microsoft/onnxruntime/blob/main/docs/ContribOperators.md
- https://github.com/microsoft/onnxruntime/blob/main/onnxruntime/python/tools/quantization/quantize.py
- PRs:
  - https://github.com/microsoft/onnxruntime/pull/24726 (FuseInitializers, 1.23)
  - https://github.com/microsoft/onnxruntime/pull/25680 (fp16 elementwise + LayerNorm, 1.24.1)
  - https://github.com/microsoft/onnxruntime/pull/29709 (fp16 MatMul/Gemm, 1.29)
  - https://github.com/microsoft/onnxruntime/pull/32301 (gated to accelerated builds, 1.30)
  - https://github.com/microsoft/onnxruntime/pull/32562 (open: `session.cpu_fp16_mode`)
  - https://github.com/microsoft/onnxruntime/pull/17031 (bf16 fastmath)
  - https://github.com/microsoft/onnxruntime/pull/17160 (i8mm QGEMM)
  - https://github.com/microsoft/onnxruntime/pull/25110 (8-bit MatMulNBits on ARM64)
  - https://github.com/microsoft/onnxruntime/pull/24908 (XNNPACK N-D MatMul)
  - https://github.com/microsoft/onnxruntime/pull/23627 (KleidiAI MatMulNBits)
- Issues:
  - https://github.com/microsoft/onnxruntime/issues/19437
  - https://github.com/microsoft/onnxruntime/issues/10919
  - https://github.com/microsoft/onnxruntime/issues/22242
  - https://github.com/microsoft/onnxruntime/issues/25824
  - https://github.com/microsoft/onnxruntime/issues/29076
  - https://github.com/microsoft/onnxruntime/issues/28423
- https://onnxruntime.ai/docs/performance/tune-performance/threading.html
- https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html
- https://onnxruntime.ai/docs/execution-providers/WebGPU-ExecutionProvider.html

**Android and Pixel platform**
- https://android.googlesource.com/device/google/zuma/+/refs/heads/main/conf/init.zuma.rc
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/accessibility/java/com/android/server/accessibility/AccessibilityServiceConnection.java
- https://android.googlesource.com/platform/system/core/+/refs/heads/main/libprocessgroup/profiles/task_profiles.json
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/Process.java
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/PowerManager.java
- https://android.googlesource.com/platform/external/scudo/+/refs/heads/main/config/custom_scudo_config.h
- https://dumps.tadiphone.dev/dumps/google/shiba/-/blob/shiba-user-15-BP1A.250405.005.B1-13151136-release-keys/vendor/etc/public.libraries.txt (via sub-agent)

**Model architecture**
- https://arxiv.org/html/2412.13663 (ModernBERT)
- https://github.com/huggingface/transformers/blob/v4.57.6/src/transformers/models/modernbert/modeling_modernbert.py

**Published latency**
- https://huggingface.co/litert-community/SigLIP2-base-patch16-224
- https://arxiv.org/html/2605.08195v1 (ExecuTorch; Pixel 9 Pro XL tables 5–6)
- https://huggingface.co/litert-community/Laya-Multilingual-LiteRT
- https://huggingface.co/litert-community/Gecko-110m-en
- https://huggingface.co/litert-community/embeddinggemma-300m
- https://huggingface.co/litert-community/Phocinae-Largha-150M-LiteRT
- https://huggingface.co/litert-community/Gemma3-1B-IT
- https://arxiv.org/abs/2004.02984 (MobileBERT)
- https://developers.google.com/edge/mediapipe/solutions/text/text_classifier
- https://arxiv.org/abs/2306.11426 (Panopoulos et al.)
- https://blog.tensorflow.org/2023/11/half-precision-inference-doubles-on-device-inference-performance.html

**GPU**
- https://arxiv.org/html/2505.00232v1 (ML Drift)
- https://github.com/google-ai-edge/ml-drift
- https://github.com/google-ai-edge/LiteRT/blob/main/litert/kotlin/src/main/AndroidManifest.xml
- LiteRT issues:
  - https://github.com/google-ai-edge/LiteRT/issues/8619
  - https://github.com/google-ai-edge/LiteRT/issues/8593
  - https://github.com/google-ai-edge/LiteRT/issues/8620
  - https://github.com/google-ai-edge/LiteRT/issues/9249
- https://github.com/google-ai-edge/litert-torch/issues/1079
- LiteRT-LM issues:
  - https://github.com/google-ai-edge/LiteRT-LM/issues/3986
  - https://github.com/google-ai-edge/LiteRT-LM/issues/1860
- https://github.com/pytorch/executorch/pull/19287
- ncnn:
  - https://github.com/Tencent/ncnn/blob/master/benchmark/README.md
  - https://github.com/Tencent/ncnn/issues/7054
- llama.cpp:
  - https://github.com/ggml-org/llama.cpp/issues/23359
  - https://github.com/ggml-org/llama.cpp/pull/15800
  - https://github.com/ggml-org/llama.cpp/pull/29818 (Laya support, merged 2026-10-02) [primary, checked here]
- https://arxiv.org/html/2410.03613 (MLC on Mali)
- https://community.arm.com/support-forums/f/mobile-graphics-and-gaming-forum/55094/confusion-of-fp32-operations-per-cycle

**Thermal and device specs** [secondary]
- https://www.notebookcheck.com/Google-Pixel-8-und-Pixel-8-Pro-drosseln-Leistung-um-bis-zu-47-5-unter-Dauerlast-laut-Performance-Vergleich.757278.0.html
- https://beebom.com/google-tensor-g3-benchmarks/
- https://www.mobilissimo.ro/articole-telefoane/google-pixel-8-pro-benchmark
- https://www.hwcooling.net/en/cortex-x3-the-new-fastest-arm-core-architecture-analysis/
- https://www.gsmarena.com/here_are_the_qualcomm_snapdragon_8_gen_3_benchmarks-news-60373.php
