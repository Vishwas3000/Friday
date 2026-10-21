#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
# Inherit the rented image's PyTorch/CUDA; all new packages stay in this job.
/venv/main/bin/python -m venv --system-site-packages .venv
.venv/bin/python -m pip install --disable-pip-version-check \
  'transformers==4.57.6' 'safetensors==0.8.0' 'optuna==4.5.0'
.venv/bin/python -c 'import json, torch, transformers, safetensors; print(json.dumps({"torch": torch.__version__, "cuda": torch.version.cuda, "cuda_available": torch.cuda.is_available(), "transformers": transformers.__version__, "safetensors": safetensors.__version__}))'
