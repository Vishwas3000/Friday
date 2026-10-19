#!/bin/bash
# research/28 E5 (S11-F): fine-tune S11 on Friday's input format once the research/27 agent's rows exist
# (data/READY_FRIDAY). Items are built on the CPU while S8 still trains; training starts when S8's has finished.
# Meanwhile S8 is exported (nb8, English-only cut, fixture). Then S11 and S11-F are evaluated on both formats.
#   tmux new -d -s friday-student-f "bash chain_f.sh"
cd /root/friday-laya/laya-student
. .venv/bin/activate
export HF_HOME=/root/friday-laya/hf
(until grep -q "S8_EXIT\|no S8" logs/run_status; do sleep 30; done; touch logs/s8_done) &
until [ -e /root/friday-laya/laya/data/READY_FRIDAY ]; do sleep 30; done
(
  until [ -e logs/s8_done ]; do sleep 30; done
  if grep -q "S8_EXIT 0" logs/run_status; then  # S8's CPU-side export overlaps S11-F's training
    nice -n 19 env CUDA_VISIBLE_DEVICES= python student.py export --model ckpt/s8 --name s8 --parity 100 > logs/export_s8.log 2>&1
    nice -n 19 env CUDA_VISIBLE_DEVICES= python student.py trim --model ckpt/s8 --onnx out/laya-android-s8.nb8-h-e16.onnx --name s8 > logs/trim_s8.log 2>&1
    nice -n 19 env CUDA_VISIBLE_DEVICES= python student.py fixture --onnx out/laya-android-s8-en.nb8-h-e16.onnx --name s8-en --vmap out/laya-android-s8-en.vocab-map.json > logs/fixture_s8.log 2>&1
    echo "S8_EXPORT_EXIT $?" >> logs/run_status
  fi
) &
PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True python student.py train --init ckpt/s11 --out ckpt/s11f \
  --train-file friday_train.jsonl --val-file friday_val.jsonl --replay 0.4 --epochs 1 --encoder-lr 1e-5 --freeze-emb \
  --wait-for ckpt/s8/model.safetensors,logs/s8_done > logs/train_s11f.log 2>&1
echo "S11F_TRAIN_EXIT $?" >> logs/run_status
for job in "s11f friday_test.jsonl" "s11 friday_test.jsonl" "s11f test" "teacher friday_test.jsonl"; do
  set -- $job
  model=$1; [ "$1" = teacher ] || model=ckpt/$1
  python student.py eval --model "$model" --name "$1" --split "$2" > "logs/eval_$1_${2%.jsonl}.log" 2>&1
  echo "EVAL_EXIT $1 $2 $?" >> logs/run_status
done
wait
