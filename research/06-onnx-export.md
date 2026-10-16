# ONNX export + on-device sizing (Phase 2a / 2b)

_Last updated: 2026-10-16 · Trust tags: [primary] read at source · [measured] run here · [UNCERTAIN] unconfirmed_

## TL;DR
- **Laya-Android exports to ONNX, and the graph is bit-exact.** The PyTorch decision and laya's own
  `ONNXAgent` agree with `max |Δprob| = 0.00e+00` on a real state [measured]. So the fp32 ONNX is a
  trustworthy baseline to quantize against.
- **fp16 fits the phone with the decision intact:** 1288 MB → **645 MB** (≤ 0.8 GB, D-016), operation
  and target top-1 unchanged on a 3/8/14/25-candidate smoke set, `max |Δprob| = 5.3e-3` [measured].
- **The gate is still unmeasured:** on-device latency + peak RSS. Mac numbers don't transfer (Mac CPU
  emulates fp16). Next step: run the fp16 graph on the Pixel 8 via ORT and measure.

## The graph contract (from `laya/onnx_agent.py` `_infer_batch`) [primary]
`DecisionModel.forward` (laya/common.py) already takes exactly these 5 inputs positionally and returns 2:

| input | dtype | shape | | output | dtype | shape |
|---|---|---|---|---|---|---|
| input_ids | int64 | [rows, seq] | | logits | float | [rows, markers] |
| attention_mask | int64 | [rows, seq] | | act_logits | float | [rows, 2] |
| marker_pos | int64 | [rows, markers] |
| marker_mask | bool | [rows, markers] |
| qtype | int64 | [rows] |

`rows` = questions × states. All of rows/seq/markers are dynamic axes. laya ships the *consumer*
(`ONNXAgent`, which reuses the same tokenize/encode/decode as the torch Agent) but not the
`export_onnx.py` it references — we wrote it: [`scripts/export_onnx.py`](../scripts/export_onnx.py).

## Export [measured] — `scripts/export_onnx.py`
- Load `laya.load(MODEL, device="cpu")` (defaults `fast=False, compile=False`, so `agent.model` is a
  plain `DecisionModel` with the stock forward), `opset=17`, legacy TorchScript exporter (`dynamo=False`).
- **Gotcha 1:** the head's `nn.TransformerEncoderLayer` takes a fused kernel
  `aten::_transformer_encoder_layer_fwd` in eval+no_grad that the exporter can't lower (and that
  ignores laya's export-built `_DynamicMultiheadAttention`). Fix: `torch.backends.mha.set_fastpath_enabled(False)`
  before export — forces the modular path, same maths.
- Result: `models/laya-android.onnx`, **1288 MB fp32**, `onnx.checker` passes, parity `max|Δprob|=0.00e+00`.
- Encoder is ModernBERT (mmBERT-base): 22 layers, hidden 768, **vocab 256,000** (embedding = 786 MB =
  61% of weights), local-attention window 128. Example exported at seq=160 (>128) so the sliding-window
  path traces and generalises under dynamic seq.

## fp16 sizing [measured] — `scripts/quantize_onnx.py`
- **Why fp16, not int8:** the 256k embedding is 61% of weights and ORT dynamic-int8 leaves it fp32
  (~0.9 GB, over budget) while also quantizing activations (→ ~83% parity, D-016). fp16 halves every
  weight uniformly to 0.64 GB with near-exact parity, and the Pixel 8 (ARMv9) has native fp16.
- **Gotcha 2:** `onnxconverter_common.float16.convert_float_to_float16` breaks ModernBERT — it flips the
  fp32 islands ModernBERT keeps on purpose (RoPE, attention Cast nodes, softmax), producing
  type-mismatched edges ORT refuses to load. Fix: `node_block_list` = every `Cast`/`Softmax` node and
  any node named `*rotary*`, plus `keep_io_types=True`. Then it loads and is accurate.
- **Result:** 645 MB, operation top-1 4/4, target top-1 4/4, `mean|Δprob|=1.7e-3 max=5.3e-3`.
- Latency on Mac CPU (ORT, 14 candidates): fp32 178 ms, fp16 389 ms — **fp16 is slower only because
  macOS CPU emulates fp16; this does not predict the phone.** [measured]

## On-device results — THE GATE [measured 2026-10-16, Pixel 8, ORT 1.20.0 arm64, 4 threads]
Harness: `scripts/bench_device.py` copies the model into the app's filesDir (shell→run-as pipe, since
adb can't write there) and sends `{"cmd":"bench"}` to `Bench.kt` (OrtSession over `models/fixture.json`:
rows=2, seq=318, markers=14, with Mac fp16 logits to check parity).

| config | file | step latency (cool) | peak PSS | parity vs Mac |
|---|---|---|---|---|
| fp16, arena on | 645 MB | ~2400 ms | 1354 MB | exact (Δlogit 0.006) |
| fp16, arena on, XNNPACK | 645 MB | ~2295 ms | 1230 MB | exact (0.008) |
| **fp16, arena OFF** | 645 MB | ~2.4 s* | **688 MB** | exact |
| int8 dynamic, arena on | 334 MB | **677 ms** | 1747 MB | **broken** |
| int8 dynamic, arena OFF | 334 MB | (throttled) | **389 MB** | broken |

- **It runs and it's correct in fp16** — the phone reproduces the Mac's logits to 0.006. Plausibly the
  first local on-device run of Laya on the Android-control task.
- **Disabling ORT's CPU memory arena roughly halves PSS** (fp16 1354→**688**, int8 1747→**389**): the RAM
  was the arena + intermediates, **not the weights**. So D-016's "model size ≈ RAM" was wrong, and
  quantization is not the memory lever — `setCPUArenaAllocator(false)` is. Small latency cost (no reuse).
- **Winning config: fp16 + arena off + 4 threads — 688 MB (under the 0.8 GB budget) with decisions intact.**
- **int8 dynamic is 3.5× faster (677 ms) and 389 MB but breaks the operation decision** (Mac op top-1
  2/4, max |Δprob| 0.11; device picks CLICK where fp16 picks BACK). Dynamic int8 quantizes *activations* —
  that's the hit. To use it: keep the operation head/scorer fp32 (`nodes_to_exclude`) or go weight-only
  int8, then re-check the ≥99% parity gate.
- **4 threads beats 8** (8t=1201 ms vs 677 ms): big.LITTLE — spilling onto A510 cores + sync costs more.
- **Thermal throttling is real**: after ~8 back-to-back runs the same fp16 step went 2400→8186 ms. Friday
  must not loop inference tightly; approve-every-step pacing helps. (* arena-off cool latency wasn't
  isolated from heat; expect ~2.4–2.7 s.)

## Open / next
- **[gate PASSED]** On-device Laya is viable: fp16 + arena-off + 4 threads. Phase 2 proceeds.
- **Cool, isolated latency** for the winning config (let the phone rest first), and whether arena-off adds
  much over arena-on at the same temperature.
- **Parity is still a smoke test** (4 synthetic states). Replay real `captures/` states for the ≥99% gate.
- **int8 accuracy recovery** (keep the op head fp32) is the lever if we later need the 3.5× speedup.
- **Phase 2c (standalone)**: tokenizer via DJL (D-022, done — `phase2-staging/Tokenizer.kt`) + the Kotlin
  input/decode port + wiring into `AgentService`, so the phone decides with no Mac.
