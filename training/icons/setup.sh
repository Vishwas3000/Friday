#!/bin/bash
# Icon classifier run-pack (research/25, D-055), for the GPU box only (D-042: no training on the Mac).
# Everything lives in /root/friday-laya/icons with its own venv: no global pip/apt, drivers, cron or shell changes,
# and nothing outside this directory is touched (the box hosts the owner's other projects).
#
#   bash setup.sh                                   # recon + venv + data
#   tmux new -d -s friday-icons "bash run.sh"       # train, export, evaluate (minutes on one GPU)
set -euo pipefail
W=/root/friday-laya/icons
mkdir -p "$W"
cd "$W"

echo "== recon (read only)"
nvidia-smi --query-gpu=index,name,memory.used,memory.total --format=csv || true
nvidia-smi --query-compute-apps=pid,process_name,used_memory --format=csv || true
df -h "$W" | tail -1
free -g | head -2

echo "== venv"
if command -v uv >/dev/null; then
  [ -d .venv ] || uv venv .venv --python 3.11
  . .venv/bin/activate
  uv pip install -q torch torchvision onnx onnxruntime pillow numpy
else
  [ -d .venv ] || python3 -m venv .venv
  . .venv/bin/activate
  pip install -q --upgrade pip
  pip install -q torch torchvision onnx onnxruntime pillow numpy
fi
python -c "import torch; print('torch', torch.__version__, '| cuda', torch.cuda.is_available(), torch.cuda.get_device_name(0) if torch.cuda.is_available() else '')"

echo "== data"
python prepare.py
