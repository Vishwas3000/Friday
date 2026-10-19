#!/bin/bash
# Laya student run-pack (research/28), for the GPU box only (D-042). Everything lives in /root/friday-laya/laya-student
# with its own venv: no global pip/apt, drivers, cron or shell changes.
#
#   scp training/laya-student/* scripts/{export_onnx,speed_eval,laya_android}.py → the box (repo-scripts/ for the three)
#   bash setup.sh
#   STUDENT_DATA=... python student.py make|cache|train|eval|export|vocab   (in tmux, sessions named friday-*)
set -euo pipefail
W=/root/friday-laya/laya-student
cd "$W"
# pins (owner, 2026-10-19): laya 0.4.8, laya-android v0.3.1, HF ByunByun/laya-android@5a03b2b (sha256 checked by student.py)
[ -d laya-src ] || { git clone -q https://github.com/NandhaKishorM/laya.git laya-src && git -C laya-src checkout -q 4ac226d; }
[ -d laya-android-src ] || { git clone -q https://github.com/Byun11/laya-android.git laya-android-src && git -C laya-android-src checkout -q ab216d4; }
[ -d .venv ] || uv venv .venv --python 3.11
. .venv/bin/activate
uv pip install torch --index-url https://download.pytorch.org/whl/cu126   # driver CUDA 12.7
uv pip install "transformers==4.57.6" "safetensors>=0.4" "huggingface_hub>=0.20,<1" numpy pyyaml onnx "onnxruntime==1.31.0" onnxscript
uv pip install --no-deps -e laya-src
python -c "import torch, transformers, laya, onnxruntime; print(torch.__version__, torch.cuda.is_available(), transformers.__version__, laya.__version__, onnxruntime.__version__)"
