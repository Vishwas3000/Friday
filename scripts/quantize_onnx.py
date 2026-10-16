"""Make an on-device-sized Laya-Android ONNX and measure decision parity vs the fp32 export.

    uv run python scripts/quantize_onnx.py      # models/laya-android.onnx -> .fp16.onnx

fp16, not int8: the 256k-row embedding is 61% of the weights, and ORT dynamic-int8 leaves it fp32
(~0.9 GB, over the <=0.8 GB budget of D-016) while also quantizing activations (~83% parity in our
notes). fp16 halves every weight uniformly to ~0.64 GB with near-exact parity, and the Pixel 8
(ARMv9, native fp16) runs it. int8-of-linears + mmap-embedding is a later lever, only if the
measured on-device latency needs it.
"""
import sys
import time
from pathlib import Path

import numpy as np
import onnx
from onnxconverter_common import float16
from onnxruntime.quantization import QuantType, quantize_dynamic

import laya

sys.path.insert(0, str(Path(__file__).resolve().parent))  # sibling laya_android.py
from laya_android import MODEL, OP_DESC, PIN, Q_OP, Q_TARGET  # noqa: E402

MODELS = Path(__file__).resolve().parent.parent / "models"
FP32 = MODELS / "laya-android.onnx"
FP16 = MODELS / "laya-android.fp16.onnx"
INT8 = MODELS / "laya-android.int8.onnx"
KW = dict(max_len=3072, head_max_len=1536)


def eval_states(counts=(3, 8, 14, 25)):
    """A varied smoke set: the same two questions over candidate lists of different sizes (the thing
    that stresses the marker head). Not a full accuracy eval -- enough to catch fp16 breaking a
    decision or shifting a probability."""
    states = []
    for k in counts:
        labels = ["Item %d (Button)" % i for i in range(k)]
        state = {"goal": "Open settings and turn on Wi-Fi", "history": [],
                 "ui": ["[%d] %s" % (i, l) for i, l in enumerate(labels)]}
        questions = {"operation": {"type": "choice", "instructions": Q_OP, "criteria": dict(OP_DESC)},
                     "target": {"type": "choice", "instructions": Q_TARGET,
                                "criteria": {str(i): l for i, l in enumerate(labels)}}}
        states.append((state, questions))
    return states


def main():
    assert FP32.exists(), "run scripts/export_onnx.py first (%s missing)" % FP32
    print("converting fp32 -> fp16 ...")
    m = onnx.load(str(FP32))
    # ModernBERT upcasts RoPE/attention to fp32 on purpose, marking those islands with Cast nodes.
    # Leave the Casts and RoPE alone so the converter keeps them fp32 and only inserts clean fp16/fp32
    # boundary casts; converting them produced type-mismatched edges ORT refused to load.
    keep = [n.name for n in m.graph.node
            if n.op_type in ("Cast", "Softmax") or "rotary" in n.name.lower()]
    m16 = float16.convert_float_to_float16(m, keep_io_types=True, node_block_list=keep)
    onnx.save(m16, str(FP16))
    print("fp32 %.0f MB -> fp16 %.0f MB (budget <=800)" % (FP32.stat().st_size / 1e6, FP16.stat().st_size / 1e6))

    # int8 (weight-only dynamic): quantizes MatMul/Gemm weights, leaves the 786 MB embedding fp32.
    print("quantizing fp32 -> int8 (dynamic) ...")
    quantize_dynamic(str(FP32), str(INT8), weight_type=QuantType.QInt8)

    a32 = laya.load(MODEL, backend="onnx", onnx_path=str(FP32), revision=PIN["revision"])

    def measure(path, name):
        """Decision parity vs fp32 over the smoke set, plus Mac-CPU latency (14 cands)."""
        aq = laya.load(MODEL, backend="onnx", onnx_path=str(path), revision=PIN["revision"])
        ops = tgt = n = 0
        diffs = []
        for state, q in eval_states():
            r32 = a32.system_one(state, q, **KW)["answers"]
            rq = aq.system_one(state, q, **KW)["answers"]
            n += 1
            ops += r32["operation"]["choice"] == rq["operation"]["choice"]
            tgt += r32["target"]["choice"] == rq["target"]["choice"]
            for qq in ("operation", "target"):
                p32, pq = r32[qq]["probabilities"], rq[qq]["probabilities"]
                diffs.append(max(abs(p32[k] - pq[k]) for k in p32))
        state, q = eval_states((14,))[0]
        ts = []
        for _ in range(6):
            t0 = time.perf_counter()
            aq.system_one(state, q, **KW)
            ts.append(time.perf_counter() - t0)
        ts = sorted(ts[1:])
        print("%-5s %4.0f MB | op top-1 %d/%d, target %d/%d | mean|Δprob|=%.2e max=%.2e | Mac CPU %.0f ms"
              % (name, path.stat().st_size / 1e6, ops, n, tgt, n, float(np.mean(diffs)), max(diffs),
                 1000 * ts[len(ts) // 2]))
        return ops == n and tgt == n

    fp16_ok = measure(FP16, "fp16")
    measure(INT8, "int8")
    assert fp16_ok, "fp16 changed a decision -- investigate before shipping it on-device"
    print("(Mac-CPU latency: fp16 is emulated/slow here; int8 shows the quantization's real parity. "
          "Device numbers come from scripts/bench_device.py.)")


if __name__ == "__main__":
    main()
