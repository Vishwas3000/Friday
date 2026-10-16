"""Export Laya-Android to ONNX, verify parity with the PyTorch model, time it on the Mac.

    uv run python scripts/export_onnx.py          # -> models/laya-android.onnx (fp32)

Phase-2 gate (docs/app.md): prove the model exports cleanly and that laya's own ONNXAgent
reproduces the PyTorch decision, before any on-device work. The fp32 graph is ~1.3 GB and
git-ignored; 2b quantizes it to fit the phone. Pure Python (runs on macOS and Windows).

Graph contract (from laya/onnx_agent.py `_infer_batch`): 5 inputs ->
  input_ids[r,s] attention_mask[r,s] marker_pos[r,m] marker_mask[r,m](bool) qtype[r]
  -> logits[r,m], act_logits[r,2]
The torch DecisionModel.forward (laya/common.py) already takes exactly these 5 positionally.
"""
import sys
import time
from pathlib import Path

import onnx
import torch

import laya

sys.path.insert(0, str(Path(__file__).resolve().parent))  # sibling laya_android.py
from laya_android import MODEL, OP_DESC, PIN, Q_OP, Q_TARGET  # noqa: E402

OUT = Path(__file__).resolve().parent.parent / "models" / "laya-android.onnx"
NAMES = ["input_ids", "attention_mask", "marker_pos", "marker_mask", "qtype"]


def example_inputs(vocab, rows=2, seq=160, markers=12):
    """Shape-representative inputs. seq>local_attention(128) so the sliding-window path traces and
    generalises under dynamic seq; two rows with different marker counts exercise the mask."""
    g = torch.Generator().manual_seed(0)
    input_ids = torch.randint(0, vocab, (rows, seq), generator=g, dtype=torch.long)
    attention_mask = torch.ones(rows, seq, dtype=torch.long)
    marker_pos = (torch.arange(markers) % (seq - 1) + 1).repeat(rows, 1)
    marker_mask = torch.zeros(rows, markers, dtype=torch.bool)
    marker_mask[0, :] = True       # operation row: 12 options
    marker_mask[1, :6] = True      # target row: 6 options, 6 padded
    qtype = torch.zeros(rows, dtype=torch.long)  # 0 = choice
    return input_ids, attention_mask, marker_pos, marker_mask, qtype


def parity_state():
    """A real decide() state (same shape as scripts/laya_android.decide): goal + ui rows + the two
    choice questions. Parity encodes at its own seq length, so it also tests dynamic-seq export."""
    labels = ["Clock (app)", "Calculator (app)", "Settings (app)", "Phone (app)"]
    state = {"goal": "Open the Clock app and go to the Timer tab", "history": [],
             "ui": ["[%d] %s" % (i, l) for i, l in enumerate(labels)]}
    questions = {"operation": {"type": "choice", "instructions": Q_OP, "criteria": dict(OP_DESC)},
                 "target": {"type": "choice", "instructions": Q_TARGET,
                            "criteria": {str(i): l for i, l in enumerate(labels)}}}
    return state, questions


def main():
    OUT.parent.mkdir(exist_ok=True)
    print("loading PyTorch agent (CPU, fp32)...")
    agent = laya.load(MODEL, device="cpu", revision=PIN["revision"], expected_sha256=PIN["expected_sha256"])
    model = agent.model.eval()
    vocab = model.encoder.config.vocab_size

    # The head's nn.TransformerEncoderLayer takes a fused kernel (aten::_transformer_encoder_layer_fwd)
    # in eval+no_grad that the ONNX exporter can't lower and that ignores laya's exportable
    # _DynamicMultiheadAttention. Force the modular path; same maths, so parity is unaffected.
    if hasattr(torch.backends, "mha"):
        torch.backends.mha.set_fastpath_enabled(False)

    print(f"exporting -> {OUT}")
    with torch.no_grad():
        torch.onnx.export(
            model, example_inputs(vocab), str(OUT),
            input_names=NAMES, output_names=["logits", "act_logits"],
            dynamic_axes={"input_ids": {0: "rows", 1: "seq"}, "attention_mask": {0: "rows", 1: "seq"},
                          "marker_pos": {0: "rows", 1: "markers"}, "marker_mask": {0: "rows", 1: "markers"},
                          "qtype": {0: "rows"}, "logits": {0: "rows", 1: "markers"}, "act_logits": {0: "rows"}},
            opset_version=17, dynamo=False)
    onnx.checker.check_model(str(OUT))
    print("ok: %s (%.0f MB), onnx.checker passed" % (OUT.name, OUT.stat().st_size / 1e6))

    # Parity: laya's own ONNXAgent must reproduce the PyTorch decision on a real state.
    state, questions = parity_state()
    kw = dict(max_len=3072, head_max_len=1536)
    torch_ans = agent.system_one(state, questions, **kw)["answers"]
    onnx_agent = laya.load(MODEL, backend="onnx", onnx_path=str(OUT), revision=PIN["revision"])
    onnx_ans = onnx_agent.system_one(state, questions, **kw)["answers"]

    worst = 0.0
    for q in ("operation", "target"):
        tp, op = torch_ans[q]["probabilities"], onnx_ans[q]["probabilities"]
        worst = max(worst, max(abs(tp[k] - op[k]) for k in tp))
        assert torch_ans[q]["choice"] == onnx_ans[q]["choice"], \
            "%s choice differs: torch=%s onnx=%s" % (q, torch_ans[q]["choice"], onnx_ans[q]["choice"])
    print("parity: op=%s target=%s (both backends agree) | max |Δprob| = %.2e"
          % (onnx_ans["operation"]["choice"], onnx_ans["target"]["choice"], worst))
    assert worst < 1e-2, "probability parity too loose (%.2e) -- export likely wrong" % worst

    t = []
    for _ in range(6):
        t0 = time.perf_counter()
        onnx_agent.system_one(state, questions, **kw)
        t.append(time.perf_counter() - t0)
    t = sorted(t[1:])  # drop warm-up
    print("onnx CPU latency (4 candidates, 2 questions): median %.0f ms" % (1000 * t[len(t) // 2]))
    print("\nnext (2b): quantize to <=0.8 GB with >=99%% parity, then measure on-device via ORT Android.")


if __name__ == "__main__":
    main()
