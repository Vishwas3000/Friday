# 39 — Teachers and a visual policy for Friday

_Checked 2026-10-20. Primary sources only. Research and proposed experiments; no models downloaded, training started, GPU used or Pixel actions performed._

## Recommendation

**Benchmark GUI-Owl-1.5-8B-Instruct and MAI-UI-8B as remote teachers, with GUI-Owl-1.5-4B-Instruct as the first memory smoke test. Compare their existing 2B checkpoints before training a new visual student.** Keep Friday's persistent coordinator and native capabilities. Introduce a screenshot-aware execution option behind the same action and verification contract.

This addresses two different problems. A better policy can choose controls and recover on unfamiliar screens. It cannot make an unsaved event a saved event, restore missing task inputs, or replace outcome verification. The calendar failure demonstrated a missing capability and false completion; those require coordinator and executor fixes regardless of teacher choice.

The repository's [component evaluation](27-laya-at-scale.md) and [student experiments](28-laya-student.md) report roughly 60% joint step accuracy and substantial degradation on unseen apps. These are offline component metrics, not end-to-end task success. The present scorer consumes accessibility candidates; an icon classifier does not supply general screenshot reasoning. Distilling another text scorer alone cannot recover controls or relationships absent from that input.

## 1. What is actually available

Scores below are author-reported under their own evaluation setups, not reproduced by Friday. They are useful for shortlisting, not a shared leaderboard. AndroidWorld is an interactive Android benchmark; ScreenSpot-Pro and OSWorld scores alone do not establish mobile task completion.

| Candidate / official weights | Declared weight license | Actual mobile evidence and limits | Friday role |
|---|---|---|---|
| [GUI-Owl-1.5 4B / 8B Instruct](https://huggingface.co/mPLUG/GUI-Owl-1.5-8B-Instruct), [4B](https://huggingface.co/mPLUG/GUI-Owl-1.5-4B-Instruct), [2B](https://huggingface.co/mPLUG/GUI-Owl-1.5-2B-Instruct) | MIT on these cards; Qwen3-VL foundation | Model-specific AndroidWorld: 4B **69.8**, 8B **69.0**, 2B **67.9**. MobileWorld: 4B **32.3**, 8B **41.8**. Published mobile runner and AndroidWorld code. [Official release and result table](https://github.com/X-PLUG/MobileAgent/tree/main/Mobile-Agent-v3.5). | First teacher pair; 4B is a useful budget baseline, not automatically inferior to 8B. |
| [GUI-Owl-1.5-8B-Think](https://huggingface.co/mPLUG/GUI-Owl-1.5-8B-Think) | MIT; the paper calls this variant “Thinking” | AndroidWorld **71.6**, MobileWorld **33.3**: thinking improves one benchmark while losing to 8B Instruct on another. [Paper](https://arxiv.org/abs/2602.16855). | Optional second pass on difficult teacher labels; measure token and latency costs. |
| [MAI-UI-8B](https://huggingface.co/Tongyi-MAI/MAI-UI-8B), [2B](https://huggingface.co/Tongyi-MAI/MAI-UI-2B) | Apache-2.0 | AndroidWorld **70.7 / 49.1** for 8B / 2B; 8B MobileWorld overall **24.9**. The often-quoted **76.7** and **41.7** belong to **235B**, not the released 8B. [Original paper, Tables 6 and 8](https://arxiv.org/html/2512.22047v1). | Independent mobile teacher and student baseline; explicit ask-user and tool actions are useful. |
| [UI-TARS-1.5-7B](https://huggingface.co/ByteDance-Seed/UI-TARS-1.5-7B) | Apache-2.0 | Screenshot policy with a [mobile action prompt](https://github.com/bytedance/UI-TARS). Its card separates released 7B from the stronger unspecified-size 1.5 model. **64.2 AndroidWorld is not a verified 7B result**. Released 7B OSWorld is 27.5, versus larger 1.5's 42.5. | Established grounding baseline; compare on Friday screens instead of assuming its family headline applies. |
| [AutoGLM-Phone-9B-Multilingual](https://huggingface.co/zai-org/AutoGLM-Phone-9B-Multilingual) | MIT on weight card; [runner code](https://github.com/zai-org/Open-AutoGLM) declares Apache-2.0 and also contains research-use wording | Official [English runner documentation](https://github.com/zai-org/Open-AutoGLM/blob/main/README_en.md) explicitly distinguishes English-capable multilingual weights from the Chinese-focused model and supplies Android execution. No comparable checkpoint-specific AndroidWorld result was verified on its card. | Useful English-app challenger. Resolve the runner's usage wording before adopting it as product code; do not attribute MobileRL paper scores to these weights. |
| [Qwen3-VL-8B-Instruct](https://huggingface.co/Qwen/Qwen3-VL-8B-Instruct) | Apache-2.0 | General VLM with an official [mobile-agent cookbook](https://github.com/QwenLM/Qwen3-VL/blob/main/cookbooks/mobile_agent.ipynb). It is the foundation for several specialized policies, not evidence that an untuned general model matches them. | General perception / independent verifier baseline. |
| [Qwen3.5-9B](https://huggingface.co/Qwen/Qwen3.5-9B) | Apache-2.0 | Current general multimodal alternative: its card reports AndroidWorld **57.8**, not the adjacent **58.6** belonging to 4B. Different [hybrid linear/full-attention architecture](https://huggingface.co/Qwen/Qwen3.5-9B/raw/main/config.json). | Useful challenger for general reasoning; not the first export target for Friday's existing ONNX scorer. |
| [InternVL3.5-8B](https://huggingface.co/OpenGVLab/InternVL3_5-8B) | Apache-2.0, including its Qwen3 component | Card lists **8.5B total** parameters and GUI capability. A directly comparable 8B AndroidWorld result was not verified in this review. | Secondary perception / extraction baseline, behind candidates with measured mobile navigation. |
| [Gemma 3n](https://ai.google.dev/gemma/docs/gemma-3n) | [Gemma terms](https://ai.google.dev/gemma/terms), not Apache/MIT | Designed for device-efficient multimodal inference; effective E2B/E4B labels differ from total parameters. The [model card](https://ai.google.dev/gemma/docs/gemma-3n/model_card) does not establish a mobile GUI action policy. | Potential later device-runtime experiment; not a ready navigation teacher. |

**Version trap:** the UI-TARS repository announces [UI-TARS-2](https://arxiv.org/abs/2509.02544), whose paper reports 73.3 on AndroidWorld. This review found public 1.5-7B weights linked from the official repository, not an equivalent downloadable UI-TARS-2 checkpoint. Likewise, July 2026's [Qwen-UI-Agent paper](https://arxiv.org/abs/2607.28227) and [official directory](https://github.com/Tongyi-MAI/MAI-UI/tree/main/Qwen-UI-Agent) provide valuable architecture research; no matching downloadable weight release was verified. Do not rename old checkpoints or budget a single GPU around those paper results.

## 2. What one 24 GB RTX 4090 can do

The following are **engineering estimates**, not measured compatibility or throughput. Nominal dense parameter counts give these weight-only lower approximations; actual vision modules, unquantized tensors and allocator overhead add memory.

| Nominal size | BF16 weights: parameters × 2 bytes | Ideal 4-bit weights: parameters ÷ 2 | Practical decision |
|---|---:|---:|---|
| 2B | 3.7 GiB | 0.9 GiB | Strong starting point for LoRA / QLoRA student experiments. |
| 4B | 7.5 GiB | 1.9 GiB | First teacher inference smoke test; LoRA candidate with bounded images/context. |
| 8B | 14.9 GiB | 3.7 GiB | Plausible single-model teacher inference at batch 1; QLoRA is an experiment, not a guaranteed fit. |
| 9B | 16.8 GiB | 4.2 GiB | Less BF16 headroom; bound resolution, history and output length. |
| 32B | 59.6 GiB | 14.9 GiB | BF16 does not fit. Quantized inference may fit some configurations, but is not the first teacher plan. |

Weights are only part of the budget: image encoding, activations, KV caches, generation, optimizer state and framework reservations also matter. “Active parameters” in a MoE do not mean only those parameters must be stored. Do not keep a teacher and student resident together on this card; label and train sequentially.

The [AutoGLM runner](https://github.com/zai-org/Open-AutoGLM/blob/main/README_en.md) recommends 24 GB+ for local inference, without proving every supplied ten-image / long-context configuration fits a 4090. UI-TARS's [official endpoint guide](https://github.com/bytedance/UI-TARS/blob/main/README_deploy.md) recommends a 48 GB L40S setup. GUI-Owl's [published serving recipe](https://huggingface.co/mPLUG/GUI-Owl-1.5-8B-Think) allows five images and 32K context and labels its validation hardware as 96 GB; it is not a 24 GB measurement. Smaller-context BF16/quantized 4090 deployment remains something to measure.

Proposed smoke test: batch 1, one current screenshot, short bounded history, capped visual resolution, and short structured action output. Record peak allocated/reserved VRAM, prefill, first-token and total latency, output validity and grounding accuracy. Preserve aspect ratio and original coordinates. Increase image detail only after checking small controls remain legible. Quantized and BF16 models must be compared on the same held-out screens.

For training, use [PEFT quantized adapter training](https://huggingface.co/docs/peft/main/en/developer_guides/quantization) or the model family's [official LoRA path](https://github.com/QwenLM/Qwen3-VL/tree/main/qwen-vl-finetune). A 2B/4B visual student with frozen base/vision weights, low-rank language adapters, microbatch 1 and gradient checkpointing is the first experiment. Full VLM fine-tuning and large online-RL reproduction are not the proposed single-card job. [TRL's VLM guidance](https://huggingface.co/docs/trl/main/training_vlm_sft) also means image tokens must survive preprocessing: filter oversized examples and bound resolution/history, rather than blindly truncating image-containing sequences.

## 3. Architecture decision for Friday

| Approach | Benefit | Remaining limitation | Decision |
|---|---|---|---|
| Continue only the current accessibility scorer | Small, cheap and compatible with existing export/runtime | Cannot see controls, images or spatial relationships missing from its input | Keep as the fast path for covered screens. |
| Accessibility policy + visual teacher offline + visual fallback at execution | Retains cheap native/A11y actions and adds missing perception | Needs an explicit fallback contract, data and real device latency measurements | **Recommended staged approach.** |
| Replace execution with a small multimodal policy | Can jointly select actions and coordinates from screenshots/history | More RAM and latency; still needs coordinator, typed values, policy checks and verification | Compare existing 2B policies first; promote only if task results justify it. |
| Put a large remote policy in every step | Faster quality baseline to evaluate; avoids fitting large weights on Pixel | Changes offline behavior and adds network/privacy/availability dependencies | Optional explicit research baseline, not an automatic production switch. |

Suggested common step contract:

```text
Input: owner goal + current subgoal + typed facts + recent verified actions
       + foreground package + screenshot + accessibility candidates
Output: action + candidate ID or coordinates + text/fact reference
        + expected observable change + uncertainty / needs-user
Execution: validate snapshot and authority → act once → observe → verify
```

This is a proposed interface, not a new implemented feature. Preserve native Calendar/Maps/intents when available. Prefer a matching live accessibility target; map visual coordinates only against the exact captured screenshot geometry and foreground app. Reject stale frames, out-of-bounds coordinates and protected interactions. A policy's `done` output is a suggestion for verification, never success evidence.

**Teacher-to-student boundary:** if the teacher can see a button that has no accessibility candidate, that row cannot teach a text-only student to click the missing candidate. Keep it as a `requires_vision` example for a visual student/fallback. For observable rows, map teacher actions to candidate IDs and verify the target/action against the next screen before adding a positive training example. Retain failures and corrections with their original task/screen state. Use concise observable action annotations; reproducing lengthy teacher thoughts is not required.

## 4. Papers worth applying

| Paper and official artifact | Concrete contribution for Friday | Compute / transfer caveat |
|---|---|---|
| [Mobile-Agent-v3.5 / GUI-Owl-1.5](https://arxiv.org/abs/2602.16855), [code and weights](https://github.com/X-PLUG/MobileAgent/tree/main/Mobile-Agent-v3.5) | Real mobile action vocabulary, screenshot grounding, progress/memory supervision; benchmark Instruct versus Thinking rather than assuming longer reasoning helps. | Released small checkpoints can be evaluated now. Reproducing its multi-platform RL is a different budget. |
| [MAI-UI](https://arxiv.org/abs/2512.22047), [archived original implementation](https://github.com/Tongyi-MAI/MAI-UI/tree/main/MAI-UI), [8B weights](https://huggingface.co/Tongyi-MAI/MAI-UI-8B) | Train clarification and tool invocation alongside navigation, instead of forcing every task through screen taps. | Start with existing weights; hundreds of parallel RL environments are not a one-4090 recipe. |
| [UI-TARS-2](https://arxiv.org/abs/2509.02544), [official project](https://github.com/bytedance/UI-TARS) | Verify generated trajectories, collect corrections while the agent runs, and preserve stateful history. | Adopt the data loop. The report's large training infrastructure and unverified public checkpoint availability prevent a direct single-card reproduction. |
| [Qwen-UI-Agent](https://arxiv.org/abs/2607.28227), [official artifacts](https://github.com/Tongyi-MAI/MAI-UI/tree/main/Qwen-UI-Agent) | Real-phone collection, cross-platform context, tools plus GUI, and a stateful harness closely match Friday's goal. | CLI/ADB actions do not all exist inside an ordinary Android app. Its 10,000-environment RL scale and reported results are not a deployable 8B release claim. |
| [MobileRL](https://arxiv.org/abs/2509.18119), [official code linked by authors](https://github.com/THUDM/MobileRL), [related released phone model](https://huggingface.co/zai-org/AutoGLM-Phone-9B-Multilingual) | Difficulty-aware replay, failure curricula and shorter successful paths offer useful later training objectives. | Paper's MobileRL-9B and downloadable AutoGLM-Phone-Multilingual are distinct artifacts; do not transfer its 80.2 AndroidWorld result to the latter. Begin with supervised corrections, not full online RL. |
| [Agent S2](https://arxiv.org/abs/2504.00906) / [Agent S3](https://arxiv.org/abs/2510.02250), [Apache-2.0 framework](https://github.com/simular-ai/Agent-S) | Separating action reasoning from grounding is viable; S3's original paper has actual AndroidWorld evidence, not just desktop results. | S3's 68.1 → 71.6 result uses GPT-5 and three rollouts, not one small downloadable model. Its [paper limitation](https://arxiv.org/html/2510.02250v1) requires independent resettable states; parallel complete rollouts must not share the owner's live phone/account. |
| [AnyAppBench](https://arxiv.org/abs/2609.34139), [code/data](https://github.com/AnyAppBench/AnyAppBench-Code), [project](https://anyappbench.github.io/) | Keep the goal and verifier fixed while switching apps. This directly tests whether Friday learns a capability or one app layout. | Benchmark contribution, not a new teacher model. Programmatic verifiers decide success; a VLM labels failures. Useful before spending training compute. |

## 5. First experiment and release decision

1. Freeze a task/app-held-out suite before labels or fine-tuning. Include calendar block, contact/message composition, search, date/time pickers, scrolling, keyboard obstruction, unreadable controls, interrupted execution and false `done` predictions. Separate one-step grounding from full task success.
2. Evaluate GUI-Owl 4B/8B and MAI-UI 8B on the same screenshots and resettable task states; include their 2B students as zero-training baselines. Keep exactly the same coordinator, permissions, verifiers and step limits.
3. Select a teacher using verified action correctness, task completion, recovery, latency and invalid-output rate. Inspect disagreement cases; neither teacher confidence nor model consensus is ground truth.
4. Create a small reviewed correction set first. Distill observable candidate decisions into Laya; keep visual-only examples for a 2B/4B VLM adapter. Mix prior general navigation/routing data so new tasks do not erase earlier abilities.
5. Measure quantization/export parity and Pixel peak memory, thermal behavior and p50/p95 step/task time. The existing multi-app RAM failures mean a nominally small VLM cannot be assumed to fit beside other apps. Load one model at a time.
6. Ship only after the new policy improves held-out end-to-end completion without increasing false success, duplicate actions or unrequested mutations. If a visual model fits the server but fails the phone budget, keep it as a teacher or optional fallback while the local policy remains available.

This document chooses candidates and an experiment order. It does not establish a training duration, a 4090 throughput number, successful Android export, or a production-ready visual policy.
