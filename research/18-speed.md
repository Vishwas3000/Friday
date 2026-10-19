# 18 — Making each Laya pass faster without changing its decisions

_2026-10-17, phone results 2026-10-18 (§6b) · Mac (Apple M5, 4 intra-op threads) on 52 (screen, goal) pairs from real Pixel 8 screens; phone numbers
from research/06 and 20 · **ORT versions:** agreement on the phone's runtime is measured with **onnxruntime 1.20.0**
(the version in `android/app/build.gradle.kts`, installed in an isolated venv). The fp32 reference, token trims, split
rows and the 8-bit / fp16 checks use the project venv's **onnxruntime 1.31.0**; each table states whether KleidiAI was
on · Trust tags: [primary] read at source · [measured] run here · [derived] computed from measured numbers ·
[UNCERTAIN]_

## TL;DR
- **Shipped (2026-10-18, §6b).** On the Pixel 8, `nb8-h-e16` on onnxruntime-android 1.31.0 runs the fixture in
  **1.73–1.85 s vs 3.83–3.90 s** for today's fp16 on 1.20 (**2.1×**), at 651 MB instead of 753 MB. Its logits match the
  Mac's phone-like run to 3 decimals, so §4's 52/52 carries over. fp32 on 1.20 was only 1.3× faster, for +600 MB.
- **Target shortlist (D-049, §9).** On crowded screens Laya picks the target from the 16 elements most related to the
  goal. On the phone, over the 52 pairs, nothing got worse: 30/34 same targets, 1 better, 2 equivalent, 1 debatable.
  Tap steps on crowded screens took 0.63× the time; the 35-element store screen went from about 19 s to about 8 s.
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

## 6. Phone benchmark (prepared 2026-10-17, run 2026-10-18: results in §6b)
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

## 6b. Phone results [measured, Pixel 8, 2026-10-18]
Fixture batched (2 rows × 318 tokens), 4 threads, arena off (`--lowmem`), 6 timed passes after one warm-up. Battery
32.7–34.2 °C, screen awake, Friday's process in the `foreground` cpuset. Runs alternate between models with 45–60 s
rests.

| ORT | model | median s | min s | PSS MB | load s | op / target | max \|Δlogit\| vs fixture |
|---|---|---|---|---|---|---|---|
| 1.20.0 | fp16 (shipped until today) | 3.90, 3.83 | 3.50, 3.30 | 753–756 | 2.5–2.9 | BACK / 0 | 0.006 |
| 1.20.0 | fp32 | 3.08, 2.82 | 2.83, 2.60 | 1,351–1,359 | 4.2–4.8 | BACK / 0 | 0.039 |
| 1.31.0 | fp16 | 11.88 † | 3.07 | 686 | 11.5 † | BACK / 0 | 0.082 |
| 1.31.0 | fp32 | 3.32 † | 2.53 | 1,300 | 5.4 | BACK / 0 | 0.039 |
| 1.31.0 | **nb8-h-e16** | **1.73, 1.85** | **1.59, 1.69** | **651–653** | 2.3 | BACK / 0 | **0.1816** (Mac: 0.182) |

- **nb8 is 2.1× faster than what shipped, with less memory.** `--decide` (on-phone tokenizer → ORT → decode) matched
  `input_ids` and marker positions and answered BACK 0.41 / target 0 0.76.
- **Parity carries over from §4.** The phone's nb8 logits equal the Mac's KleidiAI-off run to 3 decimals. The phone
  runs the same block-int8 path, so §4's 52/52 operations and targets apply.
- **fp16 on 1.31 is a dead end on the phone too:** erratic (3.1–12.4 s within one run) and further from the reference
  (0.082 vs 0.006).
- **fp32 isn't worth it:** 1.3× for +600 MB.
- † **Don't time within ~10 minutes of `adb install`.** The first nb8 run after the install (6.49 s, load 9.0 s), the
  fp16 run after it and probably the fp32 run were slowed by background work. The same nb8 file gave 2.33 s a few
  minutes later and 1.73–1.85 s settled.
- **research/06's 2.4 s for fp16 on this fixture** didn't reproduce: it was 3.8–3.9 s today. Conditions differ, so
  compare only within one session.
- **Live steps are slower per token than the bench.** fp16 in real tasks cost 8–27 ms per token against about 6 ms
  here (request log, 2026-10-17). Examples: GPay 709 tokens 5.5 s; Meesho 2,210 tokens 36.7 s; Amazon 975 tokens
  26.5 s. The app being driven competes for the same cores, and heat adds to it [UNCERTAIN: not separated].
- **Shipping changes:**
  - `onnxruntime-android` 1.20.0 → 1.31.0.
  - The AAR merges INTERNET and ACCESS_NETWORK_STATE; both are removed with `tools:node="remove"`. `aapt2 dump
    permissions` matches the 1.20 build.
  - The APK grows 63.6 → 80.3 MB (`libonnxruntime.so` 34 MB).
  - `LayaEngine.MODEL` = `laya-android.nb8-h-e16.onnx`; `setup_phone.py` pushes it.
  - Rollback: set `MODEL` back and the ORT version to 1.20.0. The fp16 file stays on the phone.

## 7. What speed to expect on the phone [derived before §6b; §6b measured nb8 at 2.1×, inside this range]
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

## 9. Target shortlist (D-049) [measured, Pixel 8, nb8 on ORT 1.31, 2026-10-18]
**The lever.**
- The target row lists every element twice: once as an option, once in the screen list. On a crowded screen it is most
  of a tap step's tokens (§2).
- Laya also picks worse from many elements: 0.84 top-1 with 1–5 candidates, 0.54 with 21–50 (research/01 §2.6).
- So on screens with more than k elements, the target row shows only k of them; the operation row still sees all.
  Operations can't change, and the chosen index maps back to the full list.

**Which k elements** (`shortlist()` in `Candidates.kt`):
1. Elements whose words (label, or resource-id words) meet the goal's about-words, plus the labels of a "last time"
   hint; the most overlap first.
2. Text fields.
3. The screen's top and bottom, alternately (toolbars, tab bars).
The result keeps screen order.

**Test.**
- The "shortlist_eval" bridge command runs the app's own Kotlin on the phone (`scripts/shortlist_eval.py 12 16 20`):
  each pair once with all elements, then with each k.
- The 52 pairs are §1's. Only the store screen has resource ids; the run-file screens are option strings, so matching
  there was by label only.
- The eval's about-words keep the app's name. In live use the hint labels are added too.
- The owner used the phone during part of the run, so absolute times are inflated. Each ratio compares back-to-back
  calls on the same screen.

| k | same target (of 34) | full pick inside the shortlist | changed picks | tap steps on screens with more than k elements |
|---|---|---|---|---|
| 12 | 31 | 33 | 2 equivalent, **1 worse** | 24 steps: 252.7 → 139.4 s (median ratio 0.59) |
| **16** | 30 | 33 | 1 better, 2 equivalent, 1 debatable | 15 steps: 192.4 → 108.9 s (median 0.63) |
| 20 | 31 | 34 | 1 better, 1 equivalent, 1 debatable | 11 steps: 148.5 → 89.9 s (median 0.75) |

**Changed picks:**
- "Set the screen timeout to 5 minutes": the full list picked the unlabeled radio button, the shortlist its "5 minutes"
  row. Equivalent; this is the full pick that isn't shown at k = 12 and 16.
- "Turn off adaptive timeout": the switch became its row. Equivalent, as in §5.
- "Browse audiobooks": "Search" became "Audiobooks". Better; §5's container trim fixed the same pick.
- "Search the Play Store for a weather app": "Search Google Play" became the search bar's container at k = 16 and 20
  (debatable), and the Apps tab at k = 12 (worse).

**Speed.** On the 35-element store screen a tap step took 18.8–19.7 s with all elements and 7.8–9.2 s with 16.

**Chosen: k = 16** (`Assistant.SHORTLIST`), the smallest k with no worse pick.

**Not covered:**
- The operation row still reads every element, so non-tap steps on crowded screens keep their cost: 2.4–6.6 s on these
  21–31-element screens here. Shortening it risks DONE and SCROLL decisions and needs its own test.
- 34 taps can't show a 1-in-30 regression; the task suite (D-049) checks whole tasks.

## 10. Run conditions: threads, pinning, background load, heat [measured, Pixel 8, nb8 on ORT 1.31, 2026-10-18]
**Setup.**
- The fixture: 2 rows × 318 tokens, 6 timed passes, arena off.
- Launched 9 minutes after an install. Runs 20 s apart, from 23:15 to 23:26.
- Battery 37.4–38.5 °C, charging. Android's thermal status was 1 (light) throughout, headroom 0.79–0.85 (1.0 = severe).
- `scripts/bench_device.py --threads N [--pin]`; "busy" = 2 `yes` busy-loops started over adb.

| threads | pinned to the fast cores | background | median s | min s |
|---|---|---|---|---|
| 4 (Friday's setting), first run | no | idle | **2.68** | 2.36 |
| 1 / 2 / 3 | no | idle | 9.94 / 6.24 / 4.27 | 9.88 / 6.06 / 3.53 |
| 6 | no | idle | 3.66 | 3.44 |
| 3 / 4 | yes | idle | 4.35 / 3.99 | 4.26 / 2.95 |
| 4 | no | 2 busy loops | 4.36 | 4.10 |
| 4 | yes | 2 busy loops | 5.57 | 5.48 |
| 2 | no | 2 busy loops | 5.76 | 5.68 |
| 4, last run | no | idle | **3.17** | 2.88 |

- **4 threads, unpinned, stays best.** Fewer threads are slower. 6 spill onto the little cores.
- **Pinning is slower every time** (+25–50%). That closes research/19 lever 3, matching the slow pinned live run of D-047.
- **Where the threads run.** Mid-run `ps -T` put ORT's 3 pool threads on the mid cores (CPU 5, 6, 7, ~30% each), cpuset `/foreground`. The X3 is kept for the app on top. research/19's "3 of 4 on little cores" didn't reproduce here.
- **Heat:**
  - the same pass took 1.73–1.85 s cool (34 °C, §6b) and 2.7–3.2 s here: **~1.5–1.8×**;
  - 10 minutes of back-to-back benching added 18% (2.68 → 3.17 s).
- **Background load:** two busy loops cost **~1.4–1.6×**. In real use that is the app being driven, which loads and draws while Laya runs.
- **Live steps also carry 800–1,500 tokens** against the fixture's 636. Heat × load × tokens explains the 4–10 s live steps seen in the suite.
- **What follows:**
  - no setting makes a step faster; only less work does: a shorter operation row, skipping Laya via memory/Teach/the map, a smaller student;
  - every Laya step's trace now logs `heat status S, headroom H` from `PowerManager`.

## 11. Why live steps are slower than the bench: not swap [measured, Pixel 8, 2026-10-19]
- **Swap was the suspect.** With Photos in front, Friday's process had **530 MB in swap** (zram) and only 330 MB
  resident (`/proc/<pid>/status`).
- **Swap is not the cause.** Across a Photos request with a 20.8 s Laya step (1,571 tokens), the process took only
  **3,002 major page faults (~12 MB)**, and VmSwap stayed at ~530 MB. The swapped memory is cold: most likely the
  256k-row embedding table, whose unused rows are never read [inferred]. The encoder weights stay resident.
  - The vocabulary trim (D-058, research/28) shrinks that cold memory. It saves RAM, not time.
- **The process gets cores 0–7** in the `foreground` cpuset (oom adj 100, procState 5). Only the prime core (8) is
  reserved for the app in front, so a restricted cpuset is ruled out too.
- **Where the time does go [inferred]:**
  - **Heat while charging:** thermal headroom was 0.86–0.91 (1.0 = throttling) at status 1.
  - **The app in front competing for the cores.**
  - **Longer inputs.** The step ran at ~9 ms per token over its two passes, against 2.8 ms on the bench, which runs
    passes back to back on a fresh session.
- **The levers left:** fewer layers (research/28), fewer tokens per pass, and running unplugged or cool.
- **Shortlist 32 vs 16 on the phone** (suite, memory on, 2026-10-19):
  - Laya time was **+19%** (231 s vs 193 s), nearly all on two crowded screens: Photos 39 → 65 s, Spotify 63 → 103 s.
  - Pass rate was 22/25 vs 23/25. Spotify's failure came from the safety rule (its suggestion rows contain "Add
    suggestion" and were skipped), not from k.
  - Accuracy at scale is research/27 L3.

## 12. The half-depth student on the phone [measured, Pixel 8, ORT 1.31, 2026-10-19]
Bench: `bench_device.py`, 636-token fixture, 4 threads, 10 passes. The phone was charging at thermal status 1
(headroom 0.85). The models are research/28's.

| Model | Median pass | p90 | Session load | Process PSS in the bench | Model's share (PSS minus ~130 MB idle app) |
|---|---|---|---|---|---|
| Shipped teacher, 22 layers (`nb8-h-e16`) | **2,271 ms** | 2,291 | 4.8 s | 785 MB | ≈ 655 MB |
| S11, 11 layers | **1,249 ms** | 1,266 | 3.0 s | 705 MB | ≈ 575 MB |
| **S11 English** (49,736-token vocabulary) | **1,256 ms** | 1,264 | **2.1 s** | 408 MB | **≈ 278 MB** |

- **Faster: 1.82× per pass,** close to the compute ratio research/28 §4 predicted (0.56×).
  - The teacher ran 2.27 s here, against 1.73–1.85 s on a cool phone (§6b): this run was warm and charging. The
    ratio is the result, not the absolute numbers.
- **Lighter: the English cut takes the model from ≈ 655 MB to ≈ 278 MB** (−58%), and loading drops from 4.8 s to 2.1 s.
  Halving the layers alone saved only ~80 MB.
- **Parity on the phone:** the students' logits are within 0.059 of the box's PyTorch references, the expected x86 vs
  ARM MatMulNBits difference.
  - On this synthetic fixture the students pick a different operation from the teacher (OPEN_APP vs BACK).
  - So routing and slot answers must be measured on the student before it ships (review, 2026-10-19).
- **How memory was measured:** each figure is the process PSS minus the app's idle PSS (~130 MB). Friday's warm Laya
  copy, when loaded, adds ~650 MB to the process (the first runs read 1.43 GB).

## Open
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
