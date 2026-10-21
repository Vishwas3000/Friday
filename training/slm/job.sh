#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
# Generated diagnostics under out/ also import the shared training helpers.
export PYTHONPATH="$PWD${PYTHONPATH:+:$PYTHONPATH}"
export TOKENIZERS_PARALLELISM=false
export OMP_NUM_THREADS=8
exec .venv/bin/python -u "$@"
