#!/bin/bash
# research/28, after chain_f.sh, once the GPU is idle (§7: CPU jobs beside a training cost it 3–4×, so none overlap it):
# S8's export and English-only cut, S11's re-cut with the v2 vocabulary, S11-F's export and cut, fixtures for the
# phone bench, routing/slot checks of the cut files as the phone would see them, and S11-F's nb8 file itself on
# 1,000 test steps. Each step logs its exit in logs/run_status; a failed step doesn't stop the rest.
#   tmux new -d -s friday-student-x "bash chain_x.sh"
cd /root/friday-laya/laya-student
. .venv/bin/activate
export HF_HOME=/root/friday-laya/hf CUDA_VISIBLE_DEVICES= OMP_NUM_THREADS=16
until grep -q "ASSIST_S11F_EXIT" logs/run_status; do sleep 30; done
step() { local tag=$1; shift; python student.py "$@" > "logs/$tag.log" 2>&1; echo "$tag EXIT $?" >> logs/run_status; }
grep -q "S8_EXIT 0" logs/run_status && models="s8" || models=""
grep -q "S11F_TRAIN_EXIT 0" logs/run_status && models="$models s11f"
for s in $models; do
  step export_$s export --model ckpt/$s --name $s --parity 100
  step trim_$s trim --model ckpt/$s --onnx out/laya-android-$s.nb8-h-e16.onnx --name $s
  step fixture_$s fixture --onnx out/laya-android-$s.nb8-h-e16.onnx --name $s
  step fixture_${s}_en fixture --onnx out/laya-android-$s-en.nb8-h-e16.onnx --name $s-en --vmap out/laya-android-$s-en.vocab-map.json
done
step trim_s11_v2 trim --model ckpt/s11 --onnx out/laya-android-s11.nb8-h-e16.onnx --name s11
step fixture_s11_v2 fixture --onnx out/laya-android-s11.nb8-h-e16.onnx --name s11
step fixture_s11_en_v2 fixture --onnx out/laya-android-s11-en.nb8-h-e16.onnx --name s11-en --vmap out/laya-android-s11-en.vocab-map.json
step assistant_s11_en assistant --model ckpt/s11 --name s11-en --respell
[ -d ckpt/s8/best ] && step assistant_s8 assistant --model ckpt/s8 --name s8
if [ -d ckpt/s11f ]; then
  step assistant_s11f_en assistant --model ckpt/s11f --name s11f-en --respell
  step onnx_eval_s11f onnx-eval --model ckpt/s11f --onnx out/laya-android-s11f.nb8-h-e16.onnx --name s11f-nb8 --torch-name s11f --sample 1000 --threads 16
fi
echo "ALL_DONE" >> logs/run_status
