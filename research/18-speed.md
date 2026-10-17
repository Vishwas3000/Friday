# 18 — Making each Laya pass faster without changing its decisions

_2026-10-17 · Mac (Apple M5, 4 intra-op threads) on 52 (screen, goal) pairs from real Pixel 8 screens; phone numbers
from research/06 and 20 · **ORT versions:** agreement on the phone's runtime is measured with **onnxruntime 1.20.0**
(the version in `android/app/build.gradle.kts`, installed in an isolated venv). The fp32 reference, token trims, split
rows and the 8-bit / fp16 checks use the project venv's **onnxruntime 1.31.0**; each table states whether KleidiAI was
on · Trust tags: [primary] read at source · [measured] run here · [derived] computed from measured numbers ·
[UNCERTAIN]_

## TL;DR
- **The int8 recipe we planned does not keep decisions on the phone's runtime.** Dynamic int8 with per-channel weights,
  with the decision head, the embedding table or the first/last encoder layers kept un-quantized, flips **7–11 of 52
  operations and 7–10 of 34 tap targets** under ORT 1.20.0 [measured].
  - Flipped decisions had fp32 margins up to 0.82, so they are not near-ties.
  - Running each row alone doesn't rescue it either.
  - The damage is the **single per-tensor activation scale** that dynamic int8 uses for every encoder MatMul, so
    keeping parts in float doesn't help.
- **8-bit block weights with block-wise int8 activations keep decisions:** `MatMulNBits` bits=8, blocks of 32,
  accuracy_level 4, head fp32, embedding fp16 (`models/laya-android.nb8-h-e16.onnx`, 578 MB).
  - It matches fp32 on **52/52 operations and 52/52 targets**, max |Δp| 0.056 (fp16 today: 0.019).
  - It runs **1.56–1.64× faster than fp32 math** on the Mac's CPU kernels [measured, ORT 1.31.0, KleidiAI off].
  - **It needs a newer ORT on the phone.** ORT 1.20.0 refuses it ("Only 4b quantization is supported for
    MatMulNBits") [measured]. research/19 puts 8-bit support at ORT ≥ 1.23.
- **Don't bump ORT and keep the fp16 file without measuring it.** With phone-like kernels, ORT 1.31 ran today's fp16
  file **2.5× slower** than fp32 math (120.9 s vs 47.7 s); under 1.20 it was 1.17× slower [measured, Mac].
  "Native fp16 kernels" are not fast kernels here.
- 4-bit `MatMulNBits` runs on ORT 1.20 and keeps operations (51–52/52), but loses 3 of 34 tap targets. Not good enough.
- **Mac int8 numbers depend on the ORT version and the chip.** On Apple silicon, ORT ≥ 1.2x runs dynamic int8 through
  KleidiAI SME kernels that quantize activations per row. That gives max |Δlogit| 0.48 vs fp32, against 3.7 on plain
  MLAS kernels (the phone's path). The Pixel 8 has no SME, and in ORT 1.31 the KleidiAI 8-bit and fp16 kernels are
  SME-only. Every agreement number here uses phone-like kernels.
- **Token trims don't pay.**
  - A label cap of 40 → 32 characters saves 4% of tokens but changes 5/52 decisions.
  - Dropping scroll containers that repeat an item's label saves 4.8% and changes 3/52 decisions: 1 better,
    1 equivalent, 1 debatable.
- **Asking the operation first and the target only for a tap** (main is implementing it) is exact (max |Δlogit|
  1.7e-5). Non-tap steps take **24–38%** of today's time, tap steps 74–88% [measured, Mac].

**Recommendation:**
1. Op-first (main, in progress).
2. Ship **nb8-h-e16 together with an ORT bump to 1.31.0** (strip the INTERNET permission, research/19), after §6's
   phone bench confirms speed and parity.
3. Bench today's fp16 file on the new ORT too, in case nb8 has to wait.
4. Leave the candidate builder alone.

**Estimate:** op-first plus nb8 bring an average step to roughly **0.12–0.35×** today's compute [derived, §7].

## 1. Test set and method [measured]
- **Screens:** 13 distinct screens recorded on the Pixel 8 on 2026-10-16 (`captures/run-*.jsonl`), plus one app-store
  home forest (`captures/playstore_state.json`). They cover the launcher home (31 candidates), system Settings pages
  (11–21), two Clock tabs (19, 22) and the store home (35).
- **Goals:** 2–8 generic goals per screen, with histories as Friday would send them (52 pairs). fp32 decides 34 CLICK,
  6 OPEN_APP, 5 BACK, 4 DONE and 3 SCROLL_DOWN.
- **Inputs:** built by the reference builder (`laya_android.decide` → laya's `ONNXAgent`), the same bytes the phone
  feeds ORT. Token counts match the phone's traces: the store screen is 1852–1864 tokens here and 1868–1884 on the
  phone (research/20).
- **Agreement:** same operation and same target as the fp32 ONNX (bit-exact with PyTorch, research/06). Δp is the
  largest |p_variant − p_fp32| over the options. Decoding is laya's (softmax(logits/T), T = 1.161); the script asserts
  it equals laya's own answers on fp32.
- **Not usable:** `captures/spotify_state.json` holds an HTTP 400 error body, not a state.
- **Reproduce:** `scripts/speed_eval.py` (`quantize` / `ref` / `eval [--split]`; `SPEED_EVAL_KLEIDIAI=1` keeps
  KleidiAI on). Its outputs are `models/speed_eval_ref.pkl` and `models/speed_eval_results.json` (git-ignored: they
  contain screen text).
- **Caveat:** 52 pairs is a smoke-scale gate, not a benchmark. 52/52 bounds the true flip rate below about 6% (95%).

## 2. Where a pass's tokens go [measured]
Each step asks two questions, one sequence (row) each: the question, its options, then the whole screen state.
- The **target** row lists every candidate twice: once as an option, once in the state.
- The **operation** row has 12 fixed options plus the state.
- Today both rows run as one batch, so the operation row is **padded up to the target row's length**. The phone's
  `tokens` is rows × padded length.

| candidates | pairs | operation row | target row | today (2 × longest) | rows unpadded | operation row only |
|---|---|---|---|---|---|---|
| 11–13 | 23 | 291–329 | 332–420 | 664–839 | 623–748 | 291–329 |
| 16–22 | 16 | 344–392 | 456–554 | 913–1109 | 801–947 | 344–392 |
| 31 | 8 | 473 | 728 | 1456 | 1201 | 473 |
| 35 | 5 | 574 | 929 | 1858 | 1503 | 574 |

The model is 22 ModernBERT layers (hidden 768, MLP 1152, global attention every 3rd layer, local window 128), plus a
**2-layer transformer decision head that also runs over every token**, then a scorer at the option markers [primary,
`laya/common.py` `DecisionModel`, encoder `config.json`]. The cost is linear layers per token plus attention per row².

## 3. Operation first, target only for a tap (main is implementing it) [measured]
- **Exact:** running each row alone, unpadded, gives the same logits as the batch (max |Δlogit| 1.7e-5, fp32).
- **Nothing reads a non-tap step's target.** `AgentService.act`, `Safety.verdict` and `describe` use it only for
  CLICK/LONG_PRESS [primary].
- **Mac time** (fp32, ORT 1.31.0 with KleidiAI on, measured before the off switch was added; 4 threads, 52 pairs;
  today's batch = 100%):

| | all | 31–35 candidates | 11–16 candidates |
|---|---|---|---|
| tap step: both rows, unpadded | 80% | 74% | 88% |
| non-tap step: operation row only | 31% | 24% | 38% |

- Time falls more than tokens (−13.8% tokens for both rows), because attention grows with each row's length squared.
- **Expected average:** recorded episodes were 20% taps (5 of 25 steps). That gives 0.2 × 0.80 + 0.8 × 0.31 ≈ **0.41×
  today's compute per step**; at 50% taps it is about 0.56× [derived].
- **Side effects for main to know [primary, `Assistant.kt`]:** non-tap steps would carry `target = null`.
  - Training rows (`row()`) and UI-memory routes (`RouteStep.targeted`) stop recording an unused target for
    BACK/SCROLL/OPEN_APP/INPUT_TEXT. Route replays of those steps then no longer need to find that element on screen.
  - A skipped non-tap step no longer adds an unrelated element to `blocked`.

## 4. Quantized variants [measured]
Every row compares the 52 pairs against fp32. "Taps" is target agreement on the 34 pairs where fp32 taps. "Mac s" is
the summed median time per pair with 4 threads and phone-like kernels. A model named in back-ticks is
`models/laya-android.<name>.onnx`; † marks files deleted to save disk (rebuild with `speed_eval.py quantize <name>`).

**Batched, as the app runs today. ORT 1.20.0 (the phone's runtime):**

| model | MB | ops | targets | taps | max \|Δp\| op | Mac s |
|---|---|---|---|---|---|---|
| fp32 `laya-android.onnx` | 1288 | 52 | 52 | 34 | 0 | 49.1 |
| fp16 `laya-android.fp16.onnx` (on the phone today) | 645 | 52 | 52 | 34 | 0.019 | 57.5 |
| dynamic int8 as in research/06 (`int8`) | 334 | 45 | 41 | 27 | 0.62 | 39.1 |
| + per-channel weights (`int8-pc`†) | 334 | 45 | 37 | 24 | 0.65 | 39.7 |
| + embedding fp16 (`int8-pc-e16`†) | 531 | 45 | 39 | 25 | 0.73 | 39.5 |
| + head fp32 (`int8-pc-h`†) | 368 | 45 | 37 | 24 | 0.65 | 42.0 |
| + head fp32 + embedding fp16 (`int8-pc-h-e16`) | 565 | 45 | 39 | 25 | 0.73 | 39.9 |
| + encoder layers 0, 21 fp32 (`int8-pc-h-e16-l1`†) | 595 | 41 | 38 | 24 | 0.55 | 41.1 |
| + layers 0, 1, 20, 21 fp32 (`int8-pc-h-e16-l2`†) | 625 | 42 | 40 | 25 | 0.58 | 42.2 |
| 4-bit MatMulNBits, head fp32, emb fp16 (`nb4-h-e16`) | 523 | 51 | 44 | 31 | 0.29 | 49.2 |
| 8-bit MatMulNBits (`nb8-h-e16`) | 578 | — | — | — | refused | — |

**Batched. ORT 1.31.0 with KleidiAI off** (`mlas.disable_kleidiai=1`; this matches the phone, because the KleidiAI
8-bit and fp16 kernels in 1.31 are SME-only):

| model | MB | ops | targets | taps | max \|Δp\| op | Mac s |
|---|---|---|---|---|---|---|
| fp32 | 1288 | 52 | 52 | 34 | 0 | 47.7 |
| fp16 (today's file) | 645 | 52 | 52 | 34 | 0.021 | **120.9** |
| **8-bit MatMulNBits, head fp32, emb fp16 (`nb8-h-e16`)** | 578 | **52** | **52** | **34** | **0.056** | **30.5** |
| 8-bit, head 8-bit too (`nb8-e16`) | 546 | 52 | 51 | 33 | 0.057 | 28.7 |
| 4-bit (`nb4-h-e16`) | 523 | 52 | 44 | 31 | 0.29 | 40.2 |

**Each row alone, as op-first will run them** (`eval --split`; both rows timed):

| runtime | model | ops | targets | taps | max \|Δp\| op | Mac s |
|---|---|---|---|---|---|---|
| 1.20.0 | fp32 | 52 | 52 | 34 | 0 | 39.7 |
| 1.20.0 | dynamic int8 (`int8`) | 44 | 38 | 25 | 0.65 | 31.5 |
| 1.20.0 | `int8-pc-h-e16` | 42 | 36 | 24 | 0.61 | 32.4 |
| 1.20.0 | `nb4-h-e16` | 51 | 44 | 31 | 0.29 | 42.0 |
| 1.31.0, KleidiAI off | fp32 | 52 | 52 | 34 | 0 | 39.6 |
| 1.31.0, KleidiAI off | fp16 | 52 | 52 | 34 | 0.020 | 105.0 |
| 1.31.0, KleidiAI off | **`nb8-h-e16`** | **52** | **52** | **34** | 0.073 | **24.2** |
| 1.31.0, KleidiAI off | `nb8-e16` | 52 | 51 | 33 | 0.073 | 23.0 |

- **Why dynamic int8 fails.** ORT's dynamic quantization gives each MatMul input one uint8 scale for the whole
  [rows × tokens × channels] tensor [primary, `onnxruntime/quantization`: `DynamicQuantizeLinear` + `MatMulInteger`].
  A few channels reach very large values (research/19 cites up to ~1.4e4 in Laya's own LiteRT port), so a typical
  channel keeps only a handful of levels.
  - This happens in all 22 layers, so keeping the head, the embedding or the edge layers in float changes nothing.
    The l1/l2 variants are slightly *worse*.
  - Giving each row its own scale (split) doesn't help either.
  - The research/06 file also stored the 256k × 768 embedding as uint8 with one scale.
- **Why MatMulNBits works.** With accuracy_level 4, activations are quantized to int8 per row in blocks along K, so an
  outlier only costs its own block. The kernel names say so: KleidiAI's `qsi8d32p` (int8 per 32-block) and `qai8dxp`
  (int8 per row) [primary, ORT 1.31 binary]; MLAS's own `SQNBitGemm` CompInt8 path does the same [secondary].
  - At 8 bits, the weight error (blocks of 32) is negligible.
  - At 4 bits, the weights are the problem: the same 4-bit weights with fp32 activations (accuracy_level 1) gave
    identical results under 1.20.
  - Under ORT 1.20 on the Mac, both accuracy levels ran at fp32 speed (49.2 vs 49.9 s, fixture Δ identical), so 1.20
    computed in fp32 here. Whether 1.20 takes its int8 path on the phone is [UNCERTAIN].
- **The head stays fp32.** Quantizing it too (`nb8-e16`) saves 5% of time and 32 MB but loses a tap target.
- **KleidiAI check** (the per-channel file `int8-pc`, 4 pairs, ORT 1.31.0). Max |Δlogit| vs fp32:
  - KleidiAI on (Mac default): **0.48**.
  - KleidiAI off, fused: **3.72**.
  - Graph optimizations off (pure ONNX semantics): **4.16**.

  With KleidiAI on, nb8 also loses one tap target (33/34) and fp32 runs in 20.6 s instead of 47.7 s. Mac numbers taken
  with default settings would mislead in both accuracy and speed.

## 5. Token trims (no model change) [measured, fp32]
| trim | tokens | ops | targets | taps |
|---|---|---|---|---|
| none | 54,452 | 52 | 52 | 34 |
| option label 40 → 32 chars | −4.0% | 50 | 48 | 31 |
| option label 40 → 24 chars | −9.1% | 51 | 42 | 27 |
| drop duplicate scroll containers | −4.8% | 51 | 48 | 32 |
| both | −8.6% | 50 | 47 | 31 |

- **Label cap:** `_label`'s 60-character limit only reaches history entries and dedup keys. The model already sees
  `label[:40]` (`option_label`, the same cap upstream laya-android trained with [primary, `src/serialization.py`]). So
  "60 → 40" is a no-op for the model's input. The real knob, 40 → 32, changes decisions:
  - 1 equivalent target (the row instead of its switch);
  - 2 store taps moved to the search bar;
  - BACK became CLICK on a sub-page;
  - a low-confidence DONE became SCROLL_DOWN.
- **Duplicate containers:** a scroll-only container (scrollable, not clickable) whose shown label starts with another
  candidate's shown label. Its text is just its first rows' text. It hit 2 containers on the launcher, 1 on Screen
  timeout and 3 on the store home (9% of that screen's tokens). Changes:
  - "Open the Camera": OPEN_APP became CLICK on the camera icon (debatable);
  - "Turn off adaptive timeout": the switch became its row (equivalent);
  - "Browse audiobooks": a wrong "Search" tab became the correct "Audiobooks" (better).
- **If ever adopted,** filter only the list Laya sees, so scroll geometry keeps the containers:
  - **Kotlin:** `Assistant.kt:192` `val cands = scr.candidates.filter { … }` → wrap it in a `dropDupContainers(…)` added
    to `Candidates.kt`. `svc.act(…, scr.candidates)` at `:282` stays unfiltered.
  - **Python:** the same function in `laya_android.py`, applied before `decide()` (`live_adb.py:223`); reference
    implementation in `speed_eval.keep_non_dup_containers`.
  - **Label cap:** `laya_android.py:129` `[:40]` and `LayaInput.kt:79` `take(40)`.
  - Not recommended now: small saving, decisions move, and the gain shrinks further once op-first lands.

## 6. Phone benchmark to run (prepared, not run)
Prerequisites (main's call):
1. Bump `com.microsoft.onnxruntime:onnxruntime-android` from 1.20.0 to **1.31.0** (the version measured here) in
   `android/app/build.gradle.kts`. If that AAR isn't published, use the newest ≥ 1.23 (not checked: no network here).
2. Strip the INTERNET permission it adds (research/19).
3. Rebuild and install. Let the phone rest before each run.

```
uv run python scripts/bench_device.py --model laya-android.fp16.onnx --lowmem                  # today's file on the new ORT
uv run python scripts/bench_device.py --model laya-android.nb8-h-e16.onnx --lowmem             # pushes 578 MB + fixture
uv run python scripts/bench_device.py --model laya-android.fp16.onnx --lowmem --no-push        # bracket thermal drift
uv run python scripts/bench_device.py --model laya-android.nb8-h-e16.onnx --decide --no-push   # tokenizer→ORT→decode
```
- **Config:** `--lowmem` is the production config (arena off, D-023); 4 threads is the default. Bench runs the fixture
  batched (2 rows), so its parity matches the batched tables in §4.
- **Speed:** read `median_ms`, `p90_ms`, `peak_pss_mb` (budget 0.8 GB) and `load_ms`. Compare with today's fp16 on
  1.20: ~2.4 s cool on this fixture, 688 MB PSS (research/06).
  - If fp16 on 1.31 is much slower than 2.4 s, that is the §4 regression: don't ship the bump without nb8.
- **Parity:** the fixture is the synthetic 14-button state, whose operation row is a near-tie (fp16: BACK 0.40,
  OPEN_APP 0.37). That makes it a sensitive canary.

  | model | `op_argmax` | `target_argmax` | `max_logit_diff_vs_mac` (vs the fp16 logits in fixture.json) |
  |---|---|---|---|
  | nb8-h-e16 | 8 (BACK) | 0 | about **0.18** (Mac, ORT 1.31.0, KleidiAI off: 0.182) |
  | fp16 | 8 (BACK) | 0 | about 0.01 (Mac: 0.006 on 1.20, 0.010 on 1.31) |

  If nb8's diff is well above ~0.3, the phone's kernels differ from the Mac's: re-run §4 with phone logits before
  shipping. `--decide` must show `input_ids_match` and `marker_pos_match` true, with operation BACK and target 0.
- **To use a variant in real runs,** change `LayaEngine.MODEL` (the app loads `laya-android.fp16.onnx` by name).
- **If the upgrade is blocked:** `laya-android.nb4-h-e16.onnx` is the only quantized file that both runs on 1.20 and
  keeps operations. Bench it the same way (Mac fixture diff 1.25, BACK/0); it still costs 3/34 tap targets.

## 7. What speed to expect on the phone [derived, UNCERTAIN until §6 runs]
- **Today's baseline is fp32 math.** ORT 1.20 has no fp16 CPU kernels for MatMul/Gemm/Softmax/Add/Mul/Gelu, so the
  fp16 file is cast to fp32 inside every run (research/19). On the Mac under 1.20, fp16 took 57.5 s vs 49.1 s for fp32.
- **On the Mac,** nb8 took 0.61–0.64× the time of fp32 math (24.2 / 39.6 s split, 30.5 / 47.7 s batched; MLAS
  kernels).
- **On the phone, int8 gains more than on the Mac.** Dynamic int8 was 3.5× faster than fp16 on the fixture (677 ms vs
  2.4 s, research/06), while on the Mac under 1.20 the same file was only 1.26× faster than fp32 math (1.47× faster
  than the fp16 file). So expect nb8 at **1.6× to ~3.5×** faster than today on the phone.
- **Combined with op-first (§3: 0.41–0.56×):** an average step would cost roughly **0.12–0.35× today's compute**. For
  example, a 24 s non-tap step on the store screen would take ~2–4 s [derived].
- **Less heat:** less compute per step also means less heat, and research/20's 24 s → 39 s second pass was thermal.

## 8. Dead ends (kept so they aren't repeated)
- **Dynamic int8 with float islands** (head, embedding, first/last 1–2 layers, per-channel), batched or split: all
  variants flip decisions under ORT 1.20 (§4).
- **Judging int8 with the Mac's default ORT:** KleidiAI SME kernels make it look far better than the phone (§4).
- **8-bit MatMulNBits on ORT 1.20:** refused at session creation.
- **4-bit weights:** activations aren't the issue there; the 4-bit weights cost tap targets.
- **An 8-bit head (`nb8-e16`):** 5% faster, one tap target lost.
- **Today's fp16 file on ORT 1.31 with phone-like kernels:** 2.5× slower than fp32 math on the Mac (needs the phone
  check, §6).
- **Label caps below 40 and the `_label` 60 → 40 change** (§5).
- **`captures/spotify_state.json`:** an error body, not a screen.

## Open
- Run §6 on the phone (needs the ORT bump): nb8 speed, PSS and parity, and whether fp16 regresses on 1.31.
- Re-check nb8 on more screens once Teach/run rows exist (research/14). 52 pairs can't show a 1% flip rate.
- The embedding is now the largest part of nb8 (393 MB as fp16). An 8-bit `GatherBlockQuantized` in a newer ORT could
  bring the file to ~380 MB [UNCERTAIN, untested].

## Sources
- [measured] `scripts/speed_eval.py`; results in `models/speed_eval_results.json`. Runtimes: onnxruntime 1.20.0
  (isolated venv) and 1.31.0 (project venv), Python 3.12, macOS on an M5, 4 intra-op threads.
- [primary] `laya/common.py` (`DecisionModel`: 2-layer head over all tokens) and `laya/onnx_agent.py` (encode/decode),
  laya 0.3.28; the mmBERT-base `config.json` in the pinned checkpoint.
- [primary] Upstream laya-android `src/serialization.py` (`label[:40]`).
- [primary] `onnxruntime/quantization` 1.31.0 (`quantize_dynamic`: per-tensor by default, `MatMulConstBOnly`).
  MatMulNBits files are packed with ORT's own `quantize_matmul_4bits/8bits`.
- [primary] The ORT 1.31.0 binary's KleidiAI kernel list (`strings`): `qsi8cx` and `f16` kernels are SME-only;
  `qsi4c32` has NEON dotprod/i8mm.
- [primary] ORT 1.20.0's `MatMulNBits` error ("Only 4b quantization is supported").
- research/06 (ONNX export, int8 on the phone), 19 (fp16 kernels, ORT versions, activation outliers), 20 (step times).
