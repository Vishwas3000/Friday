#!/bin/bash
# research/28 E7 (S11-A): S11 plus the teacher's routing/slot answers only, no Friday-format rows. S11-F (E5) fixed
# routing but gained nothing on Friday's format and lost 0.9 points on the trained one, so the assist items are kept
# and the format rows dropped: one epoch over a random 30,000 trained-format steps (teacher mix, a low-lr replay) plus
# the assist items. Exported only if it keeps S11's test Policy Joint (within 0.5) and routes at least 80% right.
# Runs after chain_x (GPU idle, CPU free).
#   tmux new -d -s friday-student-a "bash chain_a.sh"
cd /root/friday-laya/laya-student
. .venv/bin/activate
export HF_HOME=/root/friday-laya/hf
until grep -q "ALL_DONE" logs/run_status; do sleep 30; done
PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True python student.py train --init ckpt/s11 --out ckpt/s11a \
  --limit 30000 --epochs 1 --encoder-lr 1e-5 --freeze-emb --assist > logs/train_s11a.log 2>&1
echo "S11A_TRAIN_EXIT $?" >> logs/run_status
for split in test friday_test.jsonl; do
  python student.py eval --model ckpt/s11a --name s11a --split "$split" > "logs/eval_s11a_${split%.jsonl}.log" 2>&1
  echo "EVAL_EXIT s11a $split $?" >> logs/run_status
done
export CUDA_VISIBLE_DEVICES= OMP_NUM_THREADS=16
step() { local tag=$1; shift; python student.py "$@" > "logs/$tag.log" 2>&1; echo "$tag EXIT $?" >> logs/run_status; }
step assistant_s11a assistant --model ckpt/s11a --name s11a
python - >> logs/run_status 2>&1 <<'EOF' && gate=0 || gate=1
import json
pj = lambda n: json.load(open("out/%s.test.metrics.json" % n))["overall"]["policy_joint"]
route = json.load(open("out/s11a.assistant.json"))["skill"]["all"]["accuracy"]
print("gate: s11 %.4f, s11a %.4f, routing %.2f" % (pj("s11"), pj("s11a"), route))
raise SystemExit(0 if pj("s11a") >= pj("s11") - 0.005 and route >= 0.8 else 1)
EOF
if [ "$gate" = 0 ]; then
  step export_s11a export --model ckpt/s11a --name s11a --parity 100
  step trim_s11a trim --model ckpt/s11a --onnx out/laya-android-s11a.nb8-h-e16.onnx --name s11a
  step fixture_s11a fixture --onnx out/laya-android-s11a.nb8-h-e16.onnx --name s11a
  step fixture_s11a_en fixture --onnx out/laya-android-s11a-en.nb8-h-e16.onnx --name s11a-en --vmap out/laya-android-s11a-en.vocab-map.json
  step assistant_s11a_en assistant --model ckpt/s11a --name s11a-en --respell
else
  echo "S11A missed the gate: not exported" >> logs/run_status
fi
echo "ALL_DONE_A" >> logs/run_status
