#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
export TOKENIZERS_PARALLELISM=false
export OMP_NUM_THREADS=8
exec .venv/bin/python -u "$@"
