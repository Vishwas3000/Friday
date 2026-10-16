"""Make a tokenized input fixture + expected fp16 logits for the on-device ORT benchmark.

    uv run python scripts/make_fixture.py        # -> models/fixture.json

The on-device bench (Bridge `bench`) feeds these exact arrays to ORT on the Pixel 8 and checks its
logits against expected_logits, so the device test is apples-to-apples with the Mac fp16 run. Inputs
are captured straight from laya's own ONNXAgent pipeline (real tokenization), not hand-built.
"""
import json
import sys
from pathlib import Path

import numpy as np

import laya

sys.path.insert(0, str(Path(__file__).resolve().parent))  # sibling laya_android.py
from laya_android import MODEL, OP_DESC, PIN, Q_OP, Q_TARGET  # noqa: E402

FP16 = Path(__file__).resolve().parent.parent / "models" / "laya-android.fp16.onnx"
OUT = FP16.parent / "fixture.json"


def main():
    assert FP16.exists(), "run scripts/quantize_onnx.py first (%s missing)" % FP16
    k = 14  # representative mid-size screen (our bench convention)
    labels = ["Item %d (Button)" % i for i in range(k)]
    state = {"goal": "Open settings and turn on Wi-Fi", "history": [],
             "ui": ["[%d] %s" % (i, l) for i, l in enumerate(labels)]}
    questions = {"operation": {"type": "choice", "instructions": Q_OP, "criteria": dict(OP_DESC)},
                 "target": {"type": "choice", "instructions": Q_TARGET,
                            "criteria": {str(i): l for i, l in enumerate(labels)}}}

    ag = laya.load(MODEL, backend="onnx", onnx_path=str(FP16), revision=PIN["revision"])
    cap = {}
    real_run = ag.session.run

    def spy(outs, feed):  # capture the exact 5 arrays laya feeds ORT, plus the logits it gets back
        cap["feed"], r = feed, real_run(outs, feed)
        cap["logits"] = r[0]
        return r

    ag.session.run = spy
    ag.system_one(state, questions, max_len=3072, head_max_len=1536)

    feed = cap["feed"]
    fx = {name: feed[name].astype(np.int64).tolist() for name in ("input_ids", "attention_mask", "marker_pos", "qtype")}
    fx["marker_mask"] = feed["marker_mask"].astype(np.int64).tolist()  # bool -> 0/1 for JSON/Kotlin
    fx["expected_logits"] = cap["logits"].astype(np.float32).tolist()
    fx["shapes"] = {"rows": len(fx["input_ids"]), "seq": len(fx["input_ids"][0]),
                    "markers": len(fx["marker_mask"][0])}
    OUT.write_text(json.dumps(fx))
    print("wrote %s  rows=%d seq=%d markers=%d  (%.0f KB)"
          % (OUT, fx["shapes"]["rows"], fx["shapes"]["seq"], fx["shapes"]["markers"], OUT.stat().st_size / 1e3))


if __name__ == "__main__":
    main()
