#!/bin/bash
# research/28 runs, one per call, in tmux on the box (sessions named friday-*):
#   bash run.sh teacher   # E0: the teacher's train distributions (cached once) + its test metrics here
#   bash run.sh s11       # E1: distil ckpt/s11-init -> ckpt/s11, then its test metrics (s8 likewise)
# Refuses to start without 8 GB of free VRAM, so the other agent's evaluations on this GPU keep theirs.
set -euo pipefail
cd /root/friday-laya/laya-student
. .venv/bin/activate
export HF_HOME=/root/friday-laya/hf
mkdir -p logs
FREE=$(nvidia-smi --query-gpu=memory.free --format=csv,noheader,nounits | sort -n | tail -1)
[ "${FREE:-0}" -ge 8192 ] || { echo "only ${FREE} MB of VRAM free: not starting"; exit 1; }
if [ "$1" = teacher ]; then
  python student.py cache 2>&1 | tee logs/cache.log
  python student.py eval --model teacher --name teacher 2>&1 | tee logs/eval_teacher.log
else
  python student.py train --init "ckpt/$1-init" --out "ckpt/$1" 2>&1 | tee "logs/train_$1.log"
  python student.py eval --model "ckpt/$1" --name "$1" 2>&1 | tee "logs/eval_$1.log"
fi
