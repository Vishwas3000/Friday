#!/bin/bash
# research/28 after S11's run (bash run.sh s11): export S11 like the shipped file, the shipped file's own parity as a
# baseline, the English-only cut of S11 (T7), then S8 only if S11 passed the gate (test Policy Joint within 2 points
# of the teacher's). CPU-heavy steps run here, between trainings (research/28 §7).
#   tmux new -d -s friday-student-chain "bash chain.sh"
cd /root/friday-laya/laya-student
. .venv/bin/activate
export HF_HOME=/root/friday-laya/hf
until grep -q "RUN_EXIT" logs/run_status; do sleep 30; done
grep -q "RUN_EXIT 0" logs/run_status || { echo "S11 run failed: chain stops" >> logs/run_status; exit 1; }
CUDA_VISIBLE_DEVICES= python student.py export --model ckpt/s11 --name s11 --parity 300 > logs/export_s11.log 2>&1
echo "EXPORT_EXIT $?" >> logs/run_status
CUDA_VISIBLE_DEVICES= python student.py parity --model teacher --onnx shipped/laya-android.nb8-h-e16.onnx --name shipped --parity 300 > logs/parity_shipped.log 2>&1
echo "PARITY_SHIPPED_EXIT $?" >> logs/run_status
CUDA_VISIBLE_DEVICES= python student.py trim --model ckpt/s11 --onnx out/laya-android-s11.nb8-h-e16.onnx --name s11 > logs/trim_s11.log 2>&1
echo "TRIM_EXIT $?" >> logs/run_status
python - >> logs/run_status 2>&1 <<'EOF' || { echo "S11 missed the gate: no S8" >> logs/run_status; exit 0; }
import json, sys
t = json.load(open("out/teacher.test.metrics.json"))["overall"]["policy_joint"]
s = json.load(open("out/s11.test.metrics.json"))["overall"]["policy_joint"]
print("gate: teacher %.4f, s11 %.4f" % (t, s))
sys.exit(0 if s >= t - 0.02 else 1)
EOF
until [ "$(nvidia-smi --query-gpu=memory.free --format=csv,noheader,nounits | head -1)" -ge 9000 ]; do sleep 20; done
PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True bash run.sh s8
echo "S8_EXIT $?" >> logs/run_status
