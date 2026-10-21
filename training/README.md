# Training on a rented GPU (Vast.ai)

All training happens here, never on the Mac (D-042). Each job lives in its own folder under `/root/friday-laya/` with its
own venv: no global installs, drivers, cron or shell changes. Results come back to the Mac's git-ignored `models/`.

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

## Small SLM baseline before distillation

`training/slm/` contains a pinned, four-case GPU smoke probe for SmolLM2-360M,
LFM2-350M and the optional Qwen3-4B-Instruct-2507 teacher. Copy its files into
`/root/friday-laya/slm/`, then run:

```sh
cd /root/friday-laya/slm
bash setup.sh
.venv/bin/python probe.py --models smollm2 lfm2 qwen3
```

Setup reuses the PyTorch image's `/venv/main` environment through a job-local venv.
It installs no drivers or global packages. Results retain exact prompts, raw outputs,
model revisions, framework versions and GPU timing/allocation measurements in `out/`.
This is unconstrained BF16 inference, separate from Android grammar/GGUF evaluation.

The [24 context cases](../tests/slm-task-updates.json) are a separate evaluation-only
pilot for the current `task_update` interface. They are not loaded by this four-case
probe and have not had model/native-runtime execution. Keep them out of training and
checkpoint selection; three native guard cases are marked separately.

Sync useful reports off the rented instance. `/workspace` is only persistent across
recycle/destroy when a host volume is actually mounted; inspect the instance's guide
and `vast-capabilities` rather than assuming persistence. The first SLM instance had
no such volume; its synthetic outputs were backed up to ignored local captures.

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
