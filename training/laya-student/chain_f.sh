#!/bin/bash
# research/28 E5 (S11-F): fine-tune S11 on Friday's input format (data/READY_FRIDAY from the research/27 agent), with
# trained-format replay and the teacher's routing/slot answers (cache-assist). Items are built on the CPU while S8
# still trains; training starts only after S8's last GPU work (its test eval) and with 8 GB of VRAM free (review).
# The CPU-only work (exports, cuts, fixtures) follows in chain_x.sh once the GPU is idle: beside a training it costs
# the training 3–4× (§7).
#   tmux new -d -s friday-student-f "bash chain_f.sh"
cd /root/friday-laya/laya-student
. .venv/bin/activate
export HF_HOME=/root/friday-laya/hf
cpu() { env CUDA_VISIBLE_DEVICES= OMP_NUM_THREADS=8 python student.py "$@"; }
(
  until grep -q "S8_EXIT\|no S8" logs/run_status; do sleep 20; done
  until [ "$(nvidia-smi --query-gpu=memory.free --format=csv,noheader,nounits | head -1)" -ge 8192 ]; do sleep 20; done
  touch logs/s11f_go
) &
until [ -e /root/friday-laya/laya/data/READY_FRIDAY ] && grep -q "ASSIST_CACHE_EXIT 0" logs/run_status; do sleep 20; done
PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True python student.py train --init ckpt/s11 --out ckpt/s11f \
  --train-file friday_train.jsonl --val-file friday_val.jsonl --replay 0.4 --epochs 1 --encoder-lr 1e-5 --freeze-emb \
  --assist --wait-for logs/s11f_go > logs/train_s11f.log 2>&1
echo "S11F_TRAIN_EXIT $?" >> logs/run_status
for job in "s11f friday_test.jsonl" "s11 friday_test.jsonl" "s11f test" "teacher friday_test.jsonl"; do
  set -- $job
  model=$1; [ "$1" = teacher ] || model=ckpt/$1
  python student.py eval --model "$model" --name "$1" --split "$2" > "logs/eval_$1_${2%.jsonl}.log" 2>&1
  echo "EVAL_EXIT $1 $2 $?" >> logs/run_status
done
cpu assistant --model ckpt/s11f --name s11f > logs/assistant_s11f.log 2>&1
echo "ASSIST_S11F_EXIT $?" >> logs/run_status
wait
