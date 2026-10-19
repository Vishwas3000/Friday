#!/bin/bash
# research/27 run-pack (Laya-Android at scale on AndroidControl), for the GPU box only (D-042). Everything lives in
# /root/friday-laya/laya with its own venv: no global pip/apt, drivers, cron or shell changes.
#
#   scp training/laya-scale/*.py scripts/laya_android.py → /root/friday-laya/laya/
#   scp models/icons/{icons.onnx,labels.json} → /root/friday-laya/laya/icons/      (the shipped icon classifier, D-055)
#   bash setup.sh
#   tmux new -d -s friday-data "python ac_data.py --workers 10"     # L0: ~5 min streaming, writes data/ + READY
#   python ac_eval.py l1 | l2 | l3 | l3op                           # research/27 §5; then friday_data.py (§7)
set -euo pipefail
W=/root/friday-laya/laya
mkdir -p "$W/icons"
cd "$W"
# pins as run on 2026-10-19: laya-android v0.3.1 (its AndroidControl reader, builder and eval), laya 0.3.28 from PyPI
# (the version the shipped phone model was exported with); the model is HF ByunByun/laya-android@5a03b2b
[ -d laya-android ] || { git clone -q https://github.com/Byun11/laya-android.git && git -C laya-android checkout -q ab216d4; }
[ -d .venv ] || uv venv .venv --python 3.11
. .venv/bin/activate
uv pip install "torch==2.14.1" --index-url https://download.pytorch.org/whl/cu126   # driver CUDA 12.7; >= 13.0 takes the default build
uv pip install "laya==0.3.28" "transformers==4.57.6" "tokenizers==0.22.2" "safetensors==0.8.0" "huggingface-hub==0.36.2" \
  "onnxruntime==1.31.0" "protobuf==7.36.2" "numpy==2.4.6" pillow requests tqdm pyyaml
python -c "import torch, transformers, laya, onnxruntime; print(torch.__version__, torch.cuda.is_available(), transformers.__version__, laya.__version__, onnxruntime.__version__)"
