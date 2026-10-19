# Training on a rented GPU (Vast.ai)

All training happens here, never on the Mac (D-042). Each job lives in its own folder under `/root/friday-laya/` with its
own venv: no global installs, drivers, cron or shell changes. Results come back to the Mac's git-ignored `models/`.

## What to rent
| | Icon classifier only | + Laya fine-tune pilots / wake word later |
|---|---|---|
| GPU | any NVIDIA with ≥ 8 GB (3090, 4090, A5000…) | 24 GB (RTX 4090 or 3090) |
| CPU / RAM | ≥ 8 vCPU / ≥ 32 GB | same |
| Disk | 40 GB | **120 GB** (AndroidControl alone is ~50 GB, research/14) |
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

## Later jobs (same machine if it has 120 GB)
- **Laya fine-tune pilots** (research/14 data + research/15 method): AndroidControl download and processing (~50 GB,
  20–60 min CPU), baselines, pilots A/B (~3–7 min each, 8–12 GB VRAM). The run-pack is in the session scratchpad
  (`gpu_runpack/`); it moves into `training/laya/` when it runs.
- **Custom "Hey Friday" model** (research/16 §4.5): synthetic speech plus openWakeWord training, ~1–2 h.
