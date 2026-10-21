# Training on a rented GPU (Vast.ai)

All training happens here, never on the Mac (D-042). Each job lives in its own folder under `/root/friday-laya/` with its
own venv: no global installs, drivers, cron or shell changes. Weights come back to the Mac's git-ignored `models/`;
raw reports and job audit files go into ignored local `captures/`.

## What to rent
| | Icon classifier only | + Laya fine-tune pilots / wake word later |
|---|---|---|
| GPU | any NVIDIA with ≥ 8 GB (3090, 4090, A5000…) | 24 GB (RTX 4090 or 3090) |
| CPU / RAM | ≥ 8 vCPU / ≥ 32 GB | same |
| Disk | 40 GB | 40 GB is enough when AndroidControl is streamed (research/27 does); 120 GB to keep the raw shards |
| Image | Vast's PyTorch template (CUDA 12.x), launch mode **SSH** | same |
| Type | interruptible is fine (~30 min job) | on-demand (multi-hour jobs) |

## Before renting (once)
1. Vast console → **Account → Keys**: add the Mac's public key (`cat ~/.ssh/id_ed25519.pub`).
2. Rent the instance, wait until it says Running, click **Connect** and send the `ssh -p PORT root@IP` line.

## Job 1: the icon classifier (research/25, D-055), `training/icons/`
| Step | What runs | Time (est.) |
|---|---|---|
| 1. Recon | `nvidia-smi`, disk, RAM, other processes, read only | 1 min |
| 2. Copy | `scp training/icons/* → /root/friday-laya/icons/` | 1 min |
| 3. Setup + data | `bash setup.sh`: venv (torch, torchvision, onnx, onnxruntime, pillow); downloads UIST'18 icons (135 MB) and Material Symbols (3 fonts); renders ~20k icons; writes `data/icons.npz` | 5–10 min |
| 4. Train | `tmux new -d -s friday-icons "bash run.sh"`: MobileNetV3-Small from scratch, 40 epochs; exports `out/icons.onnx`; ONNX parity check; 95%-precision threshold | 10–20 min |
| 5. Bring back | `scp out/{icons.onnx,labels.json,metrics.json}` → `models/icons/` (~7 MB) | 1 min |
| 6. Clean up | delete `data/` on the box (Rico-derived icons); keep scripts | 1 min |

**Passes if:** real held-out top-1 ≥ 85%, and named icons ≥ 95% right at the threshold. Its parity with PyTorch must hold
(max diff < 1e-4), and the file must be ≤ 10 MB. Then it gets wired into Friday (crop unnamed icons outside money apps,
classify, cache) and checked on the owner's apps and the task suite before Laya sees its names.

## Job 2: Laya at scale on AndroidControl (research/27, D-056), `training/laya-scale/`
| Step | What runs | Time |
|---|---|---|
| 1. Copy | `training/laya-scale/*.py` and `scripts/laya_android.py` → `/root/friday-laya/laya/`; `models/icons/{icons.onnx,labels.json}` → `/root/friday-laya/laya/icons/` | 1 min |
| 2. Setup | `bash setup.sh`: clones laya-android at `ab216d4`; venv with torch 2.14.1 cu126, laya 0.3.28, transformers 4.57.6, onnxruntime 1.31.0 | 5 min |
| 3. Data (L0) | `python ac_data.py --workers 10`: streams AndroidControl's 20 shards over HTTPS (no raw shard on disk). Writes the repo-format items, test forests with icon names, `data/ac_{train,val,test}.jsonl` and `data/READY` | ~5 min |
| 4. Evaluate | `python ac_eval.py l1` (must reproduce Type 76.66 / Grounding 61.71 within ~1 point), then `l2`, `l3`, `l3op` → `results/*.json` (copies in `training/laya-scale/results/`) | ~1.5 h on a shared GPU |
| 5. Friday-format rows | `CUDA_VISIBLE_DEVICES= python friday_data.py --workers 20`, then `--check` → `data/friday_{train,val,test}.jsonl` + `READY_FRIDAY` | ~30 min CPU |

## Job 3: the student, a smaller Laya (research/28, D-056/D-058), `training/laya-student/`
Needs Job 2's `data/READY` (it reads `/root/friday-laya/laya/data/`). The two jobs can share one GPU.

| Step | What runs | Time |
|---|---|---|
| 1. Copy | `training/laya-student/*` → `/root/friday-laya/laya-student/`; `scripts/{export_onnx,speed_eval,laya_android}.py` → its `repo-scripts/`; optionally the phone's `models/laya-android.nb8-h-e16.onnx` → its `shipped/` (the contract check) and `models/fixture.json` → its folder | 2 min |
| 2. Setup | `bash setup.sh`: clones laya at `4ac226d` (0.4.8, editable) and laya-android at `ab216d4`; venv with torch cu126, transformers 4.57.6, onnxruntime 1.31.0 | 5 min |
| 3. Build the students | `python student.py make --layers 0,2,4,6,8,10,12,14,16,18,20 --out ckpt/s11-init`, and `--layers 0,2,5,9,11,14,18,20 --out ckpt/s8-init` | 2 min |
| 4. Teacher's answers | `tmux new -d -s friday-student "bash run.sh teacher"`: caches the teacher's distributions on train once, plus its own test metrics | ~20 min |
| 5. Train S11 | `bash run.sh s11` (in tmux): distils 3 epochs, keeps the best validation epoch, refits the temperature, evaluates on test | ~1.7 h |
| 6. Export and the rest | `tmux new -d -s friday-student-chain "bash chain.sh"`: exports S11 as the phone's 8-bit file, checks parity, builds the English-only cut (`-en`) with its id map and fixture, then trains S8 if S11 passed the gate | ~2.5 h |
| 7. Fine-tune on Friday's format | `bash chain_f.sh` after S8, once Job 2 step 5's `READY_FRIDAY` exists: S11-F (research/27 §7), its evals on both formats and the routing/slot check | ~1.5 h |
| 8. CPU work, GPU idle | `bash chain_x.sh` (start it any time; it waits for step 7): exports and English-only cuts of S8 and S11-F, S11's re-cut, fixtures, the cut files' routing check, S11-F's nb8 file on 1,000 test steps | ~35 min |
| 9. Bring back | `out/laya-android-s*.onnx`, `*.vocab-map.json`, `fixture-s*.json`, `*.json` metrics → the Mac's `models/student/` | 5 min |

Shared Hugging Face cache for both jobs: `export HF_HOME=/root/friday-laya/hf` (the model is ~0.65 GB).

## Job 4: Friday text distillation, `training/slm/`

Use the [complete reproduction guide](slm/README.md),
[experiment contract/results](slm/EXPERIMENT.md) and
[teacher review](slm/TEACHER_REVIEW.md). This is an offline language pilot on a 24 GB
RTX 4090, separate from Laya navigation training and Android app acceptance.
The [expanded app scenarios](../research/21-friday-use-case-catalogue.md) include
follow-up context, media, spending, holdings, activity, notes and task modifications.
The [dataset research](slm/DATASETS.md) pins external dialogue/tool/UI sources and
records 24,579 downloaded SGD/MultiWOZ train dialogues, outside this pilot.
`training/slm/fetch_data.py` reproduces the verified train-only download with a
1 GB cap. The separate `dialogue_data.py` adapter has prepared **234,811 train /
25,751 validation rows** remotely for `dialogue_state_v2`; the corrected independent
audit passed all **260,562 rows**, including source/context/schema checks and exact
prompt/hash bindings. A local 50,000-row subset also passed independent audits.
Preparation and standard-library `verify_dialogue_data.py` commands, source/schema
gates and exact hashes are documented there. The new
[prepared-data trainer](slm/PREPARED_TRAINING.md) implements offline training for
this separate protocol; Android integration remains separate.
The separate [writing dataset guide](slm/WRITING_DATA.md) records 81,531 downloaded
publisher TRAIN records and **21,768 filtered writing pairs** (19,697 grammar
corrections / 2,071 summaries), split into **20,617 future train / 1,151 validation**.
Those references were not used in the completed pilot. An
[80-pair semantic review](slm/WRITING_SAMPLE_REVIEW.md) rejected 27 targets, and the
blind teacher screening failed its fixed calibration gates. The larger state/context
stage uses the audited dialogue data plus only 23 approved writing training
references and 30 approved writing validation references. Other writing targets
are excluded; a larger writing experiment needs better screening.

| Step | What runs |
|---|---|
| 1. Checkout/setup | Keep `training/slm/` and `tests/` in repo layout. `bash setup.sh` inherits `/venv/main` PyTorch/CUDA through a local venv; pins Transformers 4.57.6, safetensors 0.8.0 and Optuna 4.5.0. |
| 2. Checks/data | Standard-library self-checks, then `make_data.py`: 1,200 synthetic train and 240 validation candidates, hashes and split/fixture overlap checks. |
| 3. Baselines | Run pinned Qwen3-4B teacher and SmolLM2-360M base student on the same frozen [language regression](../tests/slm-regression.json). Never use it for tuning. |
| 4. Teacher labels | Batched greedy generation; strict schema/source spans and independent gold gate structured labels. Keep rejected raw answers and atomic batch checkpoints. |
| 5. Writing review | Independently AI-review every accepted prose target, bound to its exact response SHA. Unreviewed/rejected writing is excluded from train/validation loss; AI review is not semantic certification. |
| 6. Student/Optuna | Response-only full SFT. Three fixed LR trials score generated answers on original synthetic validation candidates, including teacher-rejected ones. Validation loss selects an epoch; frozen evaluation does not select anything. |
| 7. Final comparison | Evaluate the selected checkpoint against the same frozen cases; report per-family exact/provenance, prose and truncation failures. Keep full weights, tokenizer, manifests, study SQLite and hashes. |
| 8. Backup | Sync reports to ignored local captures and selected weights to ignored models before recycling/destroying the instance. |

Submit long `job.sh` commands to the instance's existing supervisor and retain its
logs/PID metadata. Run teacher and student sequentially to release teacher GPU memory.
The four-case `probe.py` remains a separate development smoke; it does not run the
full regression. The [original 24 context cases](../tests/slm-task-updates.json) are
included in frozen evaluation, with native guard expectations labeled as metadata.

For a flat remote job directory, generate candidates from the local full checkout,
upload all `out/data/` and its manifest, and pass explicit frozen file paths through
`--cases`/`--regression`. Do not copy `make_data.py` alone without its repo `tests/`.

These results do not establish app-task success or production prompt/grammar parity.
The student still needs quantization, quantized regression and Pixel memory/latency
checks before replacing Friday's Writer. `/workspace` only survives recycle/destroy
when a persistent host volume is actually mounted; inspect the instance guide and
capabilities before relying on it.

## If the box is lost
Everything needed to rebuild is in this folder. Rent again (the table above), add the Mac's key, then redo the jobs'
Copy and Setup steps. Data is re-streamed from AndroidControl's public bucket, the models come from Hugging Face at
their pinned revisions, and the trained results to keep (models, metrics) are on the Mac in git-ignored `models/`.
Nothing on the box is the only copy of anything.

## Later jobs
- **Laya fine-tune on the owner's own rows** (Teach demos, verified runs, research/22): the same recipe as Job 3
  step 5, with AndroidControl replay.
- **Custom "Hey Friday" model** (research/16 §4.5): synthetic speech plus openWakeWord training, ~1–2 h. Parked: the
  floating bubble (D-053) replaced the always-on wake word.
