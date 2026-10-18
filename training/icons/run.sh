#!/bin/bash
# Train, export and evaluate the icon classifier (research/25). Refuses to start without 4 GB of free VRAM, so the
# owner's other jobs on this box keep theirs. Output: out/icons.onnx + out/labels.json + out/metrics.json.
set -euo pipefail
cd /root/friday-laya/icons
. .venv/bin/activate
FREE=$(nvidia-smi --query-gpu=memory.free --format=csv,noheader,nounits | sort -n | tail -1)
[ "${FREE:-0}" -ge 4096 ] || { echo "only ${FREE} MB of VRAM free: not starting"; exit 1; }
python train.py 2>&1 | tee out_train.log
