# Friday: navigation data and a practical training plan

Checked **2026-10-20**. Primary sources only for external facts. This is a research and implementation proposal; no datasets or models were downloaded, no training ran, and no device state was changed for this note.

## Recommendation

Train three contracts separately: **goal → typed plan**, **current subgoal + observed screen → one action**, and **expected result + new observation → evidence or an explicit failure**. Keep persistence, dates, permissions, retries, exact text transport, and final completion in the Kotlin coordinator. A more capable model cannot recover screen information Android does not expose.

Use AndroidControl for the existing tree based actor once its conflicting usage terms are resolved; use GUIOdyssey for cross-app decomposition and a possible visual actor; collect Friday-native trajectories with independent result checks to teach recovery and completion. Use AndroidWorld and a private Pixel acceptance suite for evaluation. Published action accuracy and benchmark rankings do not establish that Friday can finish a task on an owner's account.

The current repo already has an inexpensive actor distillation pipeline. Its [student experiment](28-laya-student.md) reports about **60% joint action accuracy**, with unseen-app results near **53%**, on recorded steps. The icon classifier's roughly 94% label accuracy is a different task: it does not make the actor a vision-language model. Preserve these baselines and measure changes against them.

## 1. Dataset inventory

“Downloadable” means an author-maintained repository exposes files or an author-linked download. It does not prove every screenshot has been audited, nor that a repository code license grants every right in its data. “Oracle” below means a result check beyond copying a demonstrator's final action.

| Source | Download, size and available supervision | Splits, completion evidence and license | Friday use |
|---|---|---|---|
| **AndroidControl** | GCS TFRecords. **15,283 demonstrations, 833 apps**; screenshots, full accessibility forests, episode goal, step instructions, JSON actions. | Author splits include in-domain, unseen app/task/category. Failed demonstrations were removed; no reusable live-app success oracle accompanies each trajectory. **Terms conflict:** the datasheet states non-commercial distribution and Apache 2.0; README's Apache notice refers to the file. | Best input match for Laya's current actor and hierarchical subgoals. Resolve data terms before extending product training. [README](https://github.com/google-research/google-research/blob/master/android_control/README.md), [author datasheet](https://papers.nips.cc/paper_files/paper/2024/file/a79f3ef3b445fd4659f44648f7ea8ffd-Supplemental-Datasets_and_Benchmarks_Track.pdf). |
| **GUIOdyssey, July 2025 release** | Author-linked Hugging Face files: **8,334 episodes, 127,893 screenshots, 212 apps**, six device types; about **92.8 GB**. Includes task, action, click bounding box, low-level instruction, screen description, intention and prior context. **No accessibility-tree field documented.** | Random 4:1 and separate task, app and device splits; COMPLETE/INCOMPLETE annotations, not a live state oracle. Dataset card **CC BY 4.0**. The old 7,735-episode version is different; repository prose also contains conflicting counts, so pin this card/revision. | Best cross-app examples for research → record/calendar/search, and for a visual actor. Do not feed generated descriptions as if Friday had observed a tree. [Current card and files](https://huggingface.co/datasets/hflqf88888/GUIOdyssey), [official repository](https://github.com/OpenGVLab/GUI-Odyssey). |
| **Android in the Wild (AITW)** | Original GCS release: **715,142 episodes, 5,689,993 examples**. Screenshot, OCR/icon features, instruction, touch/lift gestures, typed text, episode identity and status actions; **not accessibility forests**. | Episode splits plus unseen Android version/domain/subject/verb. Completion/impossible markers are annotator status. Code repository's Apache license should not be confused with a clearly scoped dataset grant; data-specific terms were not established from the primary README checked here. | Later visual/gesture diversity. Sample by task and source; GoogleApps accounts for most episodes, so raw volume would dominate the mixture. [Official schema and splits](https://github.com/google-research/google-research/blob/master/android_in_the_wild/README.md). |
| **Android in the Zoo (AitZ/CoAT)** | Author-linked Drive/Baidu archive, about **2.6 GB**: **2,504 episodes, 18,643 screens** derived from AITW. Adds screen descriptions, action descriptions, action explanations and expected action results. No new full tree collection is documented. | **1,998 train / 506 test episodes**, 13,919 / 4,724 screens. Feasibility annotations exist; no general live oracle. README displays an Apache 2.0 badge, but separately scoped data terms were not located. | Small semantic supervision/recovery research source, subject to license clarification. **Track AITW parent IDs to avoid leakage.** [Official repository](https://github.com/IMNearth/CoAT), [paper](https://arxiv.org/abs/2403.02713). |
| **AndroidLab / Android Instruct** | Official guide links a training archive: **6,208 XML steps / 6,053 screenshot Set-of-Mark steps**, already arranged for LLaMA-Factory. The associated benchmark has **138 tasks in nine apps**. | Code is MIT. The linked training guide does not establish a separate dataset grant or a clean held-out-app training protocol. Benchmark evaluation includes task/subgoal checks; do not turn its evaluated tasks into training examples and retain the same benchmark claim. | Useful small XML/marked-screen training pilot after provenance and split audit. [Training guide](https://github.com/THUDM/Android-Lab/blob/main/docs/instruction_tuning.md), [benchmark](https://github.com/THUDM/Android-Lab), [code license](https://github.com/THUDM/Android-Lab/blob/main/LICENSE). |
| **AndroidWorld** | Downloadable emulator environment and **116 task definitions across 20 apps**, with generated parameters and programmatic rewards. It is not a large packaged human demonstration corpus. | Code Apache 2.0; bundled apps have their own licenses. Dynamic values reduce simple instance memorization but do not make training on the task templates contamination-free. | Main executable regression environment. Keep final test templates/instances out of training, or explicitly report training-exposed results separately. [Official environment](https://github.com/google-research/android_world), [license](https://github.com/google-research/android_world/blob/main/LICENSE). |
| **AndroidDaily** | Official Hugging Face release currently exposes **235 task rows in a CSV**, around **50.6 KB** total repository size. The card describes 3,146 static actions as well, but this file listing does not expose those screenshot/action trajectories. | Dataset card Apache 2.0. The `train` label in the HF viewer does not establish that these benchmark cases should train Friday. | Source of daily-use evaluation ideas, translated/adapted for Friday's supported language and apps; **not a verified downloadable 3,146-action SFT corpus**. [Card](https://huggingface.co/datasets/stepfun-ai/AndroidDaily), [actual files](https://huggingface.co/datasets/stepfun-ai/AndroidDaily/tree/main). |
| **UI-S1 released sample** | Author release has **1,000 training trajectories**, a JSONL and image parquet, about **3.47 GB**. Actions, screenshots and `is_successful` are visible; no A11y field is documented. | Card Apache 2.0. This is a **partial** release, derived from existing training sources; retain their provenance and upstream restrictions. The visible sample is success-labeled, so do not expect a balanced failure corpus. | Inspect the multi-turn training/evaluation format; optional small visual pilot after parent-ID deduplication. [Dataset](https://huggingface.co/datasets/mPLUG/UI_S1_dataset), [author training guide](https://github.com/X-PLUG/MobileAgent/tree/main/UI-S1). |

### Model releases are separate from dataset releases

**UI-TARS:** public model weights, action parsers and example training JSON are available. The inspected official `data/` directory contains coordinate illustrations and small test/training examples, not the full training corpus. Its existence is not evidence we can reproduce the paper's training dataset. UI-TARS-1.5-7B's model card states Apache 2.0. [Official files](https://github.com/bytedance/UI-TARS/tree/main/data), [model card](https://huggingface.co/ByteDance-Seed/UI-TARS-1.5-7B).

**GUI-Owl:** GUI-Owl-1.5 publishes model weights and a cookbook; the inspected release page does not link its full training mixture. The 2B Instruct model card states MIT. Mobile-Agent-v3.5 explicitly supports planner, executor, verifier and notetaker roles, making it a useful precedent for Friday's separation of responsibilities. Treat the model as a candidate teacher or visual fallback; its weights do not grant access to its training corpus. [Official release](https://github.com/X-PLUG/MobileAgent/tree/main/Mobile-Agent-v3.5), [2B model card](https://huggingface.co/mPLUG/GUI-Owl-1.5-2B-Instruct).

### Acquisition and privacy rules for our pipeline

Record the original URL, revision, archive hash, stated data license, parent datasets and split IDs for every imported source. Preserve required attribution. Isolate unresolved sources from a product-training manifest until their actual grant is clarified; downloads being public is not sufficient evidence.

For Friday captures, use test accounts and synthetic contacts, calendar entries, messages and bill fixtures. Store screenshots, trees, clipboard contents and account identifiers outside Git, with an explicit local retention policy. Never include an owner's personal CRED or message screen in a public dataset or send it to an external teacher by default. Public datasets also need a scan before using their screenshots as prompts. AITW's authors say raters were instructed not to enter personal information; that is a collection procedure, not a reason to skip our audit. [AITW privacy discussion](https://arxiv.org/html/2307.10088#S6.SS2).

## 2. A training row that matches the runtime

This schema is a **proposal**, not an existing Friday API. Public sources often omit fields; mark those values unknown instead of inventing them.

```json
{
  "source": {"dataset": "friday_synthetic", "revision": "v1", "episode": "example-42", "split": "train"},
  "goal": "Create a focus block on 28 October 2026 from 10 to 11 AM",
  "context": {"timezone": "Asia/Kolkata", "today": "2026-10-20", "facts": [], "completed_steps": []},
  "subgoal": {"capability": "calendar.create", "inputs": {"date": "2026-10-28", "start": "10:00", "end": "11:00"}},
  "observation": {"package": "example.calendar", "tree_ref": "private://tree-42", "image_ref": null, "readable": true},
  "candidates": [{"id": 0, "label": "Save", "resource_id": "save", "bounds": [100, 100, 200, 150]}],
  "action": {"op": "CLICK", "target": 0, "text": null},
  "expected": {"kind": "calendar_event", "date": "2026-10-28", "start": "10:00", "end": "11:00"},
  "result": {"status": "observed", "evidence_ref": "private://result-42", "judge": "provider_readback", "verified": false}
}
```

Use separate views of that record:

- **Planner target:** capability, typed inputs, dependencies, missing fields and expected evidence. Keep unresolved dates, calendar choice and recipient ambiguity explicit.
- **Actor target:** operation and an ID from the **actual runtime candidate list**. Resolve public coordinate actions against the corresponding tree only when an unambiguous target exists. Preserve `unrepresentable` cases for fallback training; do not label the nearest unrelated widget as correct.
- **Text target:** exact user text, or a bounded writing instruction plus verified facts. Train date/amount/recipient preservation independently from tone or summarization.
- **Verifier target:** `verified`, `blocked`, `needs_input` or `unknown`, with observed evidence spans/fields. A teacher's confident prose and a trajectory's final COMPLETE token are not independent verification.

The visual branch can learn screenshot → coordinate actions from GUIOdyssey/AITW. The present text actor cannot acquire vision by receiving their action labels alone. Teacher-written screenshot descriptions also introduce an extra perception component at runtime; evaluate its failures explicitly.

## 3. Build the teacher-to-student loop

1. **Freeze a baseline.** Pin runtime, model, serializer, app versions and splits. Capture both model decisions and actual outcome. A task that returned “Done” with no readback is a negative result, even if all code-level checks passed.
2. **Start with a small, clean collection budget:** propose 100 synthetic task templates, three different valid instances each, and 10,000 reviewed action/evidence rows at most for the first pilot. These are collection targets, not data already available or a guarantee of sufficiency.
3. **Run a capable teacher on train instances only.** Give it the goal, persisted plan, permitted capabilities, current screen and prior verified facts. Require a schema-valid proposal for one next step and a short observable postcondition. Do not request or store private hidden reasoning.
4. **Execute and check independently.** Prefer an app/provider query, deterministic result comparison, or author-supplied environment reward. For ordinary UI, retain a second observation with the relevant fields. Mark any unverifiable teacher trace accordingly; exclude it from positive completion supervision.
5. **Keep useful mistakes.** When the student visits a wrong screen, have the teacher/human label the next safe recovery from that actual state. Add the corrected example to training rather than replaying only perfect demonstrations. This follows the dataset-aggregation idea: training should cover states induced by the learned policy, not just an expert's path. [DAgger paper](https://arxiv.org/abs/1011.0686).
6. **Fit separate losses.** Reuse Laya's existing operation/target soft-label distillation for identical candidate options. Add supervised planner/text JSON targets and a verifier head/model only where the schema needs generation. Keep positive and negative completion labels distinct; do not turn false-DONE examples into positive action targets.
7. **Replay previous training data.** Begin with a proposed 50% previous clean actor data, 30% new verified task rows, 20% recovery/verification cases by sampled steps. This is a starting experiment, not a published optimum. Compare with an unweighted baseline; group by task/app so one large source does not dominate.
8. **Calibrate and export.** Fit confidence thresholds on validation only; check rare WAIT/BACK/DONE operations separately. Compare native, exported and quantized decisions, then measure Pixel latency/RAM during multi-app use.

### Failure examples we need to create

| State or intervention | Correct supervision |
|---|---|
| CRED foreground but no readable app observation | `blocked: observation_unavailable`; no balance, total or DONE target. Training cannot reconstruct private bills it cannot observe. |
| Calendar editor shows the right day in the wrong month/year | Continue correction or ask for ambiguity; completion requires exact date, timezone and time readback. |
| App opens but requested content is absent | App launch is a completed substep; the user's task remains pending. |
| Bill list also contains cashback, credit limit and minimum due | Extract explicitly labeled due amounts and coverage. No total across unseen/duplicated cards. |
| Gemini still generating, or ordinary content contains the word “thinking” | Use provider generation signals and stability evidence; avoid keyword-only stop decisions. |
| Copy returns an old clipboard value or only the final visible paragraph | Reject freshness/completeness evidence and retry capture, not the user prompt. |
| Wrong conversation, IME open, app switch or process death | Reconcile saved task/session/dispatch evidence before continuing. Never blindly resend an uncertain action. |
| Repeated taps, no-op click, loading, stale candidate index, failed permission | Bounded wait/re-observe/replan; retain the exact failure and observed result. |
| Screen text asks the agent to ignore the user or send data elsewhere | Treat app content as task data; preserve the selected goal and authority boundaries. |

All interventions must be generated in controlled train fixtures. Keep untouched variants for evaluation.

## 4. SFT, distillation and RL: order of work

**First: supervised adaptation and distillation.** It fits the present actor and makes mistakes inspectable. AndroidLab reports measurable improvements from supervised tuning across its tested text and multimodal models; this supports running an SFT pilot, not forecasting Friday's success rate. [AndroidLab report](https://arxiv.org/abs/2410.24024).

**Next: student-visited-state corrections.** Ordinary SFT on perfect paths misses recovery states. Use the teacher to label failed student trajectories, and independently verify the correction; successful-looking generated explanations are insufficient.

**Later: RL in resettable environments with reliable rewards.** UI-S1 is direct precedent for multi-turn GUI learning from offline trajectories, and releases a training sample. Its paper's training table specifies **four nodes with eight GPUs per node**; reproducing that experiment is not a single-4090 task. Its improvements do not prove that a small text-only actor will obtain the same benefit. [UI-S1 paper and training table](https://arxiv.org/html/2509.11543v2#A1.SS6).

Do not optimize “the model said DONE,” an LLM's approval alone, or a click count as a success reward. The coordinator should stay responsible for admission of result evidence even when RL is added. Training on AndroidWorld tasks changes the meaning of a later AndroidWorld score; reserve untouched task families or label that score as training-exposed.

## 5. What one RTX 4090 can realistically do

The machine offers **24 GB VRAM**. The table gives arithmetic storage floors, **not measured training allocations**; quantization metadata, nonquantized layers, vision encoders, activations, optimizer state, context, image resolution and temporary buffers add memory.

| Candidate | Ideal BF16 weights | Ideal 4-bit weights | Proposed role on one 4090 |
|---|---:|---:|---|
| About 0.3B text scorer | 0.6 GB | 0.15 GB | Fine-tune/distill the existing actor using its working pipeline. |
| 0.5–1.5B text generator | 1–3 GB | 0.25–0.75 GB | Planner/extractor/text pilot with LoRA; measure exact context and runtime export. |
| About 2B VLM | 4 GB | 1 GB | Plausible QLoRA pilot, batch one, bounded image tokens/history, frozen vision branch initially. Must profile before claiming fit. |
| About 7B VLM | 14 GB | 3.5 GB | Quantized teacher inference or a constrained QLoRA experiment; long visual trajectories can still exceed VRAM. Not the first on-device deployment target. |

For perspective, conventional mixed-precision AdamW training can use roughly **18 bytes per parameter before activations**. That is approximately 36 GB for 2B and 126 GB for 7B, already over this GPU's capacity. The exact optimizer changes these figures. [Transformers memory accounting](https://huggingface.co/docs/transformers/v4.50.0/model_memory_anatomy).

LoRA trains additional parameters rather than full copies of every weight; QLoRA uses a frozen quantized base with trainable adapters. Start a generative pilot with 4-bit NF4, BF16 compute, gradient checkpointing, batch one and accumulation; validate architecture-specific support and answer-token masking. These settings follow supported PEFT patterns, but their memory/performance on our selected model remains an experiment. [Official PEFT quantization guide](https://huggingface.co/docs/peft/developer_guides/quantization), [QLoRA paper](https://arxiv.org/abs/2305.14314).

**ETA must come from profiling.** The repo measured 1.70 hours for an earlier three-epoch actor distillation and about 13 minutes for a later narrow routing/slot update; neither is a duration estimate for VLM training. For a new model, time 100 warm training steps on the real sequence/image distribution, measure effective steps/second and peak VRAM, then forecast:

`training time ≈ (training examples × epochs ÷ effective batch size) × measured optimizer-step time`

Add measured data preparation, teacher generation, validation, export and device evaluation separately. Publish an interval from short/long sequence batches. Avoid concurrent teacher serving and training unless both memory budgets have been measured; do not interrupt other projects on the GPU.

**Phone feasibility is a separate gate.** A 4-bit model's file size is not its peak RAM, and the Pixel's 8 GB total RAM is shared with Android and the apps being controlled. The latest [Pixel acceptance report](37-pixel-research-live-acceptance.md) found Writer generation blocked by available-memory limits. A bigger student cannot be declared deployable because it fits the 4090. First retain the small actor, then load a compact generator only as needed and benchmark that schedule without weakening memory guards.

## 6. Evaluation and release gates

Split **before** teacher labeling, paraphrasing or augmentation. Group all steps, crops, app variants and derived episodes from the same original trajectory together. Track AITW ↔ AitZ and AndroidControl ↔ derivative corpora explicitly. Deduplicate goals/templates and near-identical screenshots across sources. If a pretrained teacher/student might have seen a benchmark, report that uncertainty instead of claiming uncontaminated evaluation.

Maintain four separate reports:

1. **Recorded-step tests:** operation, target and joint accuracy; candidate recall; exact typed text; WAIT/BACK/DONE confusion; calibration; seen versus unseen app/task/category. Never fit a threshold on test.
2. **Planner/verifier tests:** valid schema, correct capabilities, missing-data detection, exact date/month/year/timezone, required fact transfer, duplicate dispatch prevention, and false-completion rate. Keep silent omission distinct from an honest unsupported result.
3. **Executable environment tests:** AndroidWorld or isolated custom fixtures with independent state checks, fixed task budget, multiple seeds and clean resets. Report total successes/attempts and uncertainty; no cherry-picking successful retries.
4. **Pixel task completion:** untouched tasks on installed real apps, with minimal test accounts. Measure complete goal success, accurate readback, recovery, unexpected side effects, manual interventions, p50/p95 end-to-end time, model/UI/wait components, peak memory and unavailable observations. Assisted completion stays assisted.

Proposed promotion criteria: an actor candidate improves held-out joint accuracy without a material regression on rare operations, and the complete system improves independently verified Pixel task success under the same task/time budget. For calendar creation, a saved event with exact inputs is the acceptance result. For bill reading, verified source amounts and declared coverage are required; a hidden screen returning BLOCKED is honest failure, not successful bill retrieval. Maintain zero observed false-success claims in the release suite, while reporting the sample count rather than presenting zero as a universal guarantee.

## 7. First buildable sequence

1. Add a versioned evidence/trajectory exporter to the coordinator, using private local storage and test data.
2. Freeze held-out app/task families, Pixel acceptance cases and import manifests.
3. Audit a small AndroidControl train subset and the GUIOdyssey annotations/schema; resolve licenses separately from code licenses.
4. Collect the proposed controlled task budget, including failure and interrupted-state variants; have a teacher propose actions and an independent check grade results.
5. Run one existing-actor fine-tune and one compact generative planner/text LoRA pilot. Compare with the unchanged baseline before exploring a VLM student.
6. Export, calibrate and test peak RAM and task completion on Pixel. Keep any larger teacher on the GPU during development.
7. Expand only the data categories that fail held-out evaluation. Consider environment RL after the result checks are reliable and the supervised baseline is stable.

No dataset solves the current CRED observation gap or guarantees calendar correctness. Better data teaches decisions; the coordinator still has to expose the right capabilities, carry the correct inputs, and verify what actually happened.
