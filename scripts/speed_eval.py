"""Speed levers for one Laya pass, checked on REAL screens (research/18-speed.md).

    uv run python scripts/speed_eval.py quantize int8-pc-h-e16 ...  # variants -> models/laya-android.<tag>.onnx
    uv run python scripts/speed_eval.py ref                         # fp32 reference + token trims + split rows
    <py with onnxruntime==1.20.0> scripts/speed_eval.py eval [--split] fp16 int8-pc-h-e16 ...  # each model vs fp32

Run `eval` under the phone's ORT (1.20.0, needs only numpy + onnxruntime): newer ORT on Apple silicon runs
dynamic-int8 MatMuls through KleidiAI/SME kernels that quantize activations per row, far more accurately than
the phone's plain MLAS path, so it overstates int8 agreement (research/18).

Screens: the git-ignored captures/ (run-*.jsonl option lists + playstore_state.json forest); goals below
are generic. Inputs are built by the reference builder (laya_android.decide -> laya's ONNXAgent), the
same bytes the phone feeds ORT. One model process at a time: each stage loads one session.
"""
import json
import os
import pickle
import re
import statistics
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).resolve().parent))  # sibling laya_android.py
from laya_android import (MODEL, PIN, build_candidates, decide, option_label,  # noqa: E402
                          parse_forest)

ROOT = Path(__file__).resolve().parent.parent
MODELS, CAPTURES = ROOT / "models", ROOT / "captures"
FP32 = MODELS / "laya-android.onnx"
REF = MODELS / "speed_eval_ref.pkl"  # holds screen text -> stays in git-ignored models/
EMB_W, EMB_GATHER = "encoder.embeddings.tok_embeddings.weight", "/encoder/embeddings/tok_embeddings/Gather"
HEAD = ("/layers.", "/scorer/", "/act_head/", "/type_emb/")  # 2-layer decision head + scorer, over every token
TAP = ("CLICK", "LONG_PRESS")  # the only ops whose target the app acts on (AgentService.act / Safety)
# SPEED_EVAL_KLEIDIAI=1 keeps ORT>=1.2x KleidiAI kernels on (its 4-bit MatMulNBits kernels also exist for NEON i8mm)
KLEIDIAI = os.environ.get("SPEED_EVAL_KLEIDIAI") == "1"

# tag -> (per_channel, name prefixes kept fp32, embedding table fp16 instead of int8, MatMulNBits bits or None).
# None = ORT dynamic int8 (one activation scale per tensor). 4/8 = MatMulNBits: weights in blocks of 32,
# accuracy_level 4 (int8 activations per row and 32-block where the runtime has that kernel). ORT 1.20 runs only 4.
VARIANTS = {
    "int8-pc": (True, (), False, None),
    "int8-pc-e16": (True, (), True, None),
    "int8-pc-h": (True, HEAD, False, None),
    "int8-pc-h-e16": (True, HEAD, True, None),
    "int8-pc-h-e16-l1": (True, HEAD + ("/encoder/layers.0/", "/encoder/layers.21/"), True, None),
    "int8-pc-h-e16-l2": (True, HEAD + tuple("/encoder/layers.%d/" % i for i in (0, 1, 20, 21)), True, None),
    "nb4-h-e16": (None, HEAD, True, 4),
    "nb8-h-e16": (None, HEAD, True, 8),
    "nb8-e16": (None, (), True, 8),
}

# (screen, goal, history). Screens: "<run file stamp>#<step>" in captures/, or "playstore" (forest).
PAIRS = [
    # launcher home, 31 candidates
    ("152851#1", "Open Settings and show the Battery page", []),
    ("152957#2", 'Search Settings for "dark theme"', ["BACK"]),
    ("152851#1", "Open the Camera", []),
    ("152851#1", "Create a new task in my task list", []),
    ("152851#1", "Do a voice search", []),
    ("162842#1", "Open the Clock app and go to the Timer tab", []),
    ("162842#1", "Open Messages", []),
    ("162842#1", "Search the web for today's weather", []),
    # Settings > Display > Screen timeout, 21
    ("152851#2", "Open Settings and show the Battery page", ["OPEN_APP Settings"]),
    ("152851#2", "Set the screen timeout to 5 minutes", ["OPEN_APP Settings", "CLICK Display & touch", "CLICK Screen timeout"]),
    ("152851#2", "Turn off adaptive timeout", ["OPEN_APP Settings", "CLICK Display & touch", "CLICK Screen timeout"]),
    ("152851#2", "Go back to the display settings", ["OPEN_APP Settings", "CLICK Display & touch", "CLICK Screen timeout"]),
    # Settings > Display & touch, 16
    ("152851#3", "Open Settings and show the Battery page", ["OPEN_APP Settings", "BACK"]),
    ("152851#3", "Turn off dark theme", ["OPEN_APP Settings", "CLICK Display & touch"]),
    ("152851#3", "Make the font bigger", ["OPEN_APP Settings", "CLICK Display & touch"]),
    ("152851#3", "Turn off the always-on display", ["OPEN_APP Settings", "CLICK Display & touch"]),
    # Settings home, 13 / 12 / 12
    ("152851#4", "Open Settings and show the Battery page", ["OPEN_APP Settings", "BACK", "BACK"]),
    ("152851#4", "Turn on Bluetooth", ["OPEN_APP Settings"]),
    ("152851#4", 'Search Settings for "dark theme"', ["OPEN_APP Settings"]),
    ("152851#4", "Check how much storage is free", ["OPEN_APP Settings"]),
    ("153015#3", "Open About phone in Settings to see the Android version", ["OPEN_APP Settings", "BACK"]),
    ("153015#3", "Open notification settings", ["OPEN_APP Settings"]),
    ("153015#3", "Change the wallpaper", ["OPEN_APP Settings"]),
    ("153015#4", "Open About phone in Settings to see the Android version", ["OPEN_APP Settings", "BACK", "SCROLL_DOWN"]),
    ("153015#4", "Open accessibility settings", ["OPEN_APP Settings", "SCROLL_DOWN"]),
    ("153015#4", "Turn off location", ["OPEN_APP Settings", "SCROLL_DOWN"]),
    ("153252#3", "Open About phone in Settings to see the Android version", ["OPEN_APP Settings", "BACK"]),
    ("153252#4", "Open About phone in Settings to see the Android version",
     ["OPEN_APP Settings", "BACK", "CLICK About phone Pixel 8"]),
    ("153252#3", "Open the security settings", ["OPEN_APP Settings"]),
    ("153252#3", "Turn on Do Not Disturb", ["OPEN_APP Settings"]),
    # Settings > Battery, 11
    ("152851#5", "Open Settings and show the Battery page",
     ["OPEN_APP Settings", "BACK", "BACK", "CLICK Battery 48% - Charging rapidly"]),
    ("152851#5", "Turn on Battery Saver", ["OPEN_APP Settings", "CLICK Battery 48% - Charging rapidly"]),
    ("152851#5", "Show the battery percentage in the status bar", ["OPEN_APP Settings", "CLICK Battery 48% - Charging rapidly"]),
    ("152851#5", "Go back to the main Settings page", ["OPEN_APP Settings", "CLICK Battery 48% - Charging rapidly"]),
    ("153015#2", "Open About phone in Settings to see the Android version", ["OPEN_APP Settings"]),
    ("153015#2", "Run the battery diagnostics", ["OPEN_APP Settings", "CLICK Battery 49% - Charging rapidly"]),
    # Settings > Storage, 12
    ("153252#2", "Open About phone in Settings to see the Android version", ["OPEN_APP Settings"]),
    ("153252#2", "Free up space on the phone", ["OPEN_APP Settings", "CLICK Storage"]),
    ("153252#2", "See how much space videos use", ["OPEN_APP Settings", "CLICK Storage"]),
    # Clock: alarms tab 19, timer tab 22
    ("152941#2", "Open the Clock app and go to the Timer tab", ["OPEN_APP Clock"]),
    ("152941#2", "Turn off the first alarm", ["OPEN_APP Clock"]),
    ("152941#2", "Add a new alarm", ["OPEN_APP Clock"]),
    ("152941#2", "Open the stopwatch", ["OPEN_APP Clock"]),
    ("152941#3", "Open the Clock app and go to the Timer tab", ["OPEN_APP Clock", "CLICK Timers"]),
    ("162842#2", "Set a 5 minute timer", ["OPEN_APP Clock"]),
    ("152941#3", "Show my alarms", ["OPEN_APP Clock", "CLICK Timers"]),
    ("152941#3", "Open the world clock", ["OPEN_APP Clock", "CLICK Timers"]),
    # app store home, 35
    ("playstore", "Search the Play Store for a weather app", ["OPEN_APP Play Store"]),
    ("playstore", "Show the top selling books", ["OPEN_APP Play Store"]),
    ("playstore", "Open the Games section", ["OPEN_APP Play Store"]),
    ("playstore", "Browse audiobooks", ["OPEN_APP Play Store"]),
    ("playstore", "Show more of the recently reduced ebooks", ["OPEN_APP Play Store"]),
]

OPT = re.compile(r"^(.*) \(([^()]*)\)((?: (?:edit|long|scroll|on|selected|disabled))*)$")


def cand_from_option(s):
    """A captured option string back to the dict option_label renders it from (round trip asserted)."""
    label, cls, flags = OPT.match(s).groups()
    f = flags.split()
    c = {"label": "" if label == "-" else label, "class": cls, "editable": "edit" in f,
         "long_clickable": "long" in f, "scrollable": "scroll" in f, "checked": "on" in f,
         "selected": "selected" in f, "enabled": "disabled" not in f,
         "clickable": not ("long" in f or "scroll" in f)}
    assert option_label(c) == s, (option_label(c), s)
    return c


def screen(key):
    if key == "playstore":
        st = json.loads((CAPTURES / "playstore_state.json").read_text())
        return build_candidates(parse_forest(st), tuple(st["screen"]))
    stamp, step = key.split("#")
    for line in (CAPTURES / ("run-20261016-%s.jsonl" % stamp)).read_text().splitlines():
        r = json.loads(line)
        if r["step"] == int(step):
            return [cand_from_option(s) for s in r["cands"]]
    raise KeyError(key)


# ---- token trims (no model change). Proposed builder changes; NOT applied to laya_android.py / Kotlin. ----
def cap_labels(cands, n):
    """option_label shows label[:40]; show label[:n] instead."""
    return [dict(c, label=c["label"][:n]) for c in cands]


def keep_non_dup_containers(cands):
    """Indices kept after dropping scroll-only containers (scrollable, not clickable) whose shown label starts
    with another candidate's shown label: the container's label is its first rows' text, already listed."""
    shown = [c["label"][:40] for c in cands]
    return [i for i, c in enumerate(cands)
            if not (c["scrollable"] and not c["clickable"] and shown[i]
                    and any(j != i and shown[j] and shown[i].startswith(shown[j]) for j in range(len(cands))))]


TRIMS = {  # name -> cands -> (cands shown to Laya, their indices in the original list)
    "base": lambda cs: (cs, list(range(len(cs)))),
    "cap32": lambda cs: (cap_labels(cs, 32), list(range(len(cs)))),
    "cap24": lambda cs: (cap_labels(cs, 24), list(range(len(cs)))),
    "dropdup": lambda cs: ([cs[i] for i in keep_non_dup_containers(cs)], keep_non_dup_containers(cs)),
    "cap32+dropdup": lambda cs: (cap_labels([cs[i] for i in keep_non_dup_containers(cs)], 32),
                                 keep_non_dup_containers(cs)),
}


# ---- shared helpers ----
def session(path, threads=4):
    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    so.intra_op_num_threads = threads  # the phone runs 4 (research/06)
    if not KLEIDIAI:  # phone-like kernels on newer ORT (the Pixel 8 has no SME); 1.20 ignores the key
        so.add_session_config_entry("mlas.disable_kleidiai", "1")
    return ort.InferenceSession(str(path), sess_options=so, providers=["CPUExecutionProvider"])


def probs(logits, k, temp):
    """laya _decode_answers for a choice row: softmax(logits[:k] / T)."""
    z = logits[:k] / temp
    p = np.exp(z - z.max())
    return p / p.sum()


def row(feed, r):
    """Row r alone, without the padding the batch gave it."""
    n, k = int(feed["attention_mask"][r].sum()), int(feed["marker_mask"][r].sum())
    return {"input_ids": feed["input_ids"][r:r + 1, :n], "attention_mask": feed["attention_mask"][r:r + 1, :n],
            "marker_pos": feed["marker_pos"][r:r + 1, :k], "marker_mask": feed["marker_mask"][r:r + 1, :k],
            "qtype": feed["qtype"][r:r + 1]}


def timed(sess, feed, reps=3):
    ts = []
    for _ in range(reps):
        t0 = time.perf_counter()
        out = sess.run(None, feed)
        ts.append(time.perf_counter() - t0)
    return out, statistics.median(ts)


# ---- stages ----
def quantize(tags):
    import onnx
    from onnx import TensorProto, helper, numpy_helper
    from onnxruntime.quantization import QuantType, quantize_dynamic
    names = [n.name for n in onnx.load(str(FP32), load_external_data=False).graph.node]
    for tag in tags:
        per_channel, keep, emb16, bits = VARIANTS[tag]
        out = MODELS / ("laya-android.%s.onnx" % tag)
        exclude = [n for n in names if n.startswith(keep)] + ([EMB_GATHER] if emb16 else [])
        print("%s: per_channel=%s, bits=%s, %d nodes kept fp32, embedding %s ..." % (
            tag, per_channel, bits, len(exclude), "fp16" if emb16 else "int8"))
        if bits:  # MatMulNBits built here with ORT's own packer (its Python quantizer needs onnx_ir, not installed)
            from onnxruntime.capi._pybind_state import quantize_matmul_4bits, quantize_matmul_8bits
            m = onnx.load(str(FP32))
            inits = {t.name: t for t in m.graph.initializer}
            new, dropped = [], set()
            for i, n in enumerate(m.graph.node):
                if n.op_type != "MatMul" or n.name in exclude or n.input[1] not in inits:
                    continue
                w = numpy_helper.to_array(inits[n.input[1]])
                k, cols = w.shape
                kb, kpack = k // 32, 8 // bits
                assert k % 32 == 0, (n.name, k)
                packed, scales = np.zeros((cols, kb, 32 // kpack), np.uint8), np.zeros(cols * kb, np.float32)
                zp = np.zeros((cols, (kb + kpack - 1) // kpack), np.uint8)  # unused: symmetric
                (quantize_matmul_4bits if bits == 4 else quantize_matmul_8bits)(packed, w, scales, zp, 32, cols, k, True)
                dropped.add(n.input[1])
                new += [numpy_helper.from_array(packed, n.input[1] + "_Q"),
                        numpy_helper.from_array(scales, n.input[1] + "_scales")]
                m.graph.node[i].CopyFrom(helper.make_node(
                    "MatMulNBits", [n.input[0], n.input[1] + "_Q", n.input[1] + "_scales"], list(n.output),
                    name=n.name + "_Q%d" % bits, domain="com.microsoft", K=k, N=cols, bits=bits, block_size=32,
                    accuracy_level=4))
            keep = [t for t in m.graph.initializer if t.name not in dropped]
            del m.graph.initializer[:]
            m.graph.initializer.extend(keep + new)
            m.opset_import.append(helper.make_opsetid("com.microsoft", 1))
            onnx.save(m, str(out))
            del m
        else:
            quantize_dynamic(str(FP32), str(out), per_channel=per_channel, weight_type=QuantType.QInt8,
                             nodes_to_exclude=exclude)
        m = onnx.load(str(out))
        if emb16:  # fp16 table + Cast: half the fp32 table's bytes, ~exact (int8 per-tensor was the alternative)
            w = next(i for i in m.graph.initializer if i.name == EMB_W)
            w.CopyFrom(numpy_helper.from_array(numpy_helper.to_array(w).astype(np.float16), EMB_W))
            g = next(n for n in m.graph.node if n.name == EMB_GATHER)
            y = g.output[0]
            g.output[0] = y + "_fp16"
            pos = list(m.graph.node).index(g) + 1
            m.graph.node.insert(pos, helper.make_node("Cast", [y + "_fp16"], [y], to=TensorProto.FLOAT,
                                                      name=EMB_GATHER + "_to_fp32"))
            onnx.save(m, str(out))
        # The phone runs ORT 1.20 (full onnxruntime-android): IR <= 10, default-domain opset <= 21, and the only
        # contrib op is MatMulNBits (whether 1.20 runs a given `bits` is checked by `eval` under ORT 1.20).
        domains = {(o.domain or "ai.onnx"): o.version for o in m.opset_import}
        contrib = {n.op_type for n in m.graph.node if n.domain == "com.microsoft"}
        assert m.ir_version <= 10 and domains.get("ai.onnx", 0) <= 21 and contrib <= {"MatMulNBits"}, \
            (m.ir_version, domains, contrib)
        print("  -> %s %.0f MB (IR %d, opset %s)" % (out.name, out.stat().st_size / 1e6, m.ir_version, domains))
        del m


def ref():
    import laya
    agent = laya.load(MODEL, backend="onnx", onnx_path=str(FP32), revision=PIN["revision"])
    assert not agent.temperature_by_options, "decode below assumes one temperature per qtype"
    agent.session = session(FP32)  # 4 threads, as on the phone (laya's own uses every core)
    cap = []
    real = agent.session.run
    agent.session.run = lambda outs, feed: cap.append((feed, real(outs, feed))) or cap[-1][1]

    screens = {s: screen(s) for s, _, _ in PAIRS}
    recs = []
    for i, (s, goal, hist) in enumerate(PAIRS):
        for trim, fn in TRIMS.items():
            shown, idx = fn(screens[s])
            cap.clear()
            ans, _ = decide(agent, goal, hist, shown)
            (feed, (logits, _act)), = cap
            rec = {"pair": i, "screen": s, "trim": trim, "ncands": len(shown), "idx": idx, "feed": feed,
                   "op": ans["operation"]["choice"], "op_p": ans["operation"]["probabilities"],
                   "target": idx[int(ans["target"]["choice"])], "target_p": ans["target"]["probabilities"],
                   "tokens": int(feed["input_ids"].size),
                   "row_len": [int(x) for x in feed["attention_mask"].sum(1)], "logits": logits}
            # our decode == laya's (4-dp rounded) on every row, so `eval` can decode other models the same way
            for r, q in enumerate(("operation", "target")):
                p = probs(logits[r], int(feed["marker_mask"][r].sum()), agent.temperature[0])
                assert np.abs(p - np.array(list(ans[q]["probabilities"].values()))).max() < 1e-4
            recs.append(rec)
        b = recs[-len(TRIMS)]
        print("%2d %-9s %2d cands %4d tok (rows %s) -> %-11s %.2f | target %s" % (
            i, s, b["ncands"], b["tokens"], b["row_len"], b["op"], max(b["op_p"].values()), b["target"]))

    # Split rows: run the operation row alone (and the target row only for a tap). Rows are independent
    # sequences, so the logits must match the batched run; the saving is the op row's padding + the target row.
    sess = agent.session
    sess.run = real
    split = []
    for rec in (r for r in recs if r["trim"] == "base"):
        f = rec["feed"]
        (lb, _), tb = timed(sess, f)
        (l0, _), t0 = timed(sess, row(f, 0))
        (l1, _), t1 = timed(sess, row(f, 1))
        d = max(np.abs(lb[0, :l0.shape[1]] - l0[0]).max(), np.abs(lb[1, :l1.shape[1]] - l1[0]).max())
        split.append({"pair": rec["pair"], "max_dlogit": float(d), "t_batched": tb, "t_op": t0, "t_target": t1,
                      "tap": rec["op"] in TAP})
    with REF.open("wb") as fh:
        pickle.dump({"temp": agent.temperature[0], "recs": recs, "split": split}, fh)
    report_ref(recs, split)


def agree(base, other):
    """op/target agreement + probability deltas of `other` decisions vs `base` (same pair)."""
    op_ok = base["op"] == other["op"]
    tg_ok = base["target"] == other["target"]
    dp_op = max(abs(base["op_p"][k] - other["op_p"][k]) for k in base["op_p"])
    return op_ok, tg_ok, dp_op


def report_ref(recs, split):
    base = {r["pair"]: r for r in recs if r["trim"] == "base"}
    tok = sum(r["tokens"] for r in base.values())
    print("\n== token trims vs base (fp32 decisions), %d pairs, %d tokens (rows x L) ==" % (len(base), tok))
    for trim in TRIMS:
        rs = [r for r in recs if r["trim"] == trim]
        ok = [agree(base[r["pair"]], r) for r in rs]
        tap = [o for o, r in zip(ok, rs) if base[r["pair"]]["op"] in TAP]
        print("%-14s tokens %5d (%+5.1f%%) | op %d/%d | target %d/%d (taps %d/%d) | max|dp_op| %.3f | cands %d" % (
            trim, sum(r["tokens"] for r in rs), 100.0 * (sum(r["tokens"] for r in rs) / tok - 1),
            sum(o for o, _, _ in ok), len(ok), sum(t for _, t, _ in ok), len(ok), sum(t for _, t, _ in tap), len(tap),
            max(d for _, _, d in ok), sum(r["ncands"] for r in rs)))
        for (o, t, d), r in zip(ok, rs):
            if not o or (not t and base[r["pair"]]["op"] in TAP):
                b = base[r["pair"]]
                print("    pair %d: op %s %.2f -> %s %.2f, target %s -> %s" % (
                    r["pair"], b["op"], b["op_p"][b["op"]], r["op"], r["op_p"][r["op"]], b["target"], r["target"]))
    rl = [base[s["pair"]]["row_len"] for s in split]
    batched = sum(2 * max(x) for x in rl)
    both = sum(sum(x) for x in rl)
    cond = sum(x[0] + (x[1] if s["tap"] else 0) for x, s in zip(rl, split))
    print("\n== split rows (fp32, Mac, 4 threads) ==")
    print("max |dlogit| split vs batched: %.2e" % max(s["max_dlogit"] for s in split))
    print("tokens: batched %d | op+target rows unpadded %d (%+.1f%%) | op row, target row only on a tap %d (%+.1f%%)"
          % (batched, both, 100.0 * (both / batched - 1), cond, 100.0 * (cond / batched - 1)))
    tb = sum(s["t_batched"] for s in split)
    tboth = sum(s["t_op"] + s["t_target"] for s in split)
    tcond = sum(s["t_op"] + (s["t_target"] if s["tap"] else 0) for s in split)
    print("Mac time: batched %.1f s | both rows %.1f s (%+.1f%%) | conditional %.1f s (%+.1f%%) | taps %d/%d"
          % (tb, tboth, 100 * (tboth / tb - 1), tcond, 100 * (tcond / tb - 1), sum(s["tap"] for s in split), len(split)))


def evaluate(names, split=False):
    data = pickle.loads(REF.read_bytes())
    temp, base = data["temp"], [r for r in data["recs"] if r["trim"] == "base"]
    fx = json.loads((MODELS / "fixture.json").read_text())
    fx_feed = {k: np.array(fx[k], dtype=np.int64) for k in ("input_ids", "attention_mask", "marker_pos", "qtype")}
    fx_feed["marker_mask"] = np.array(fx["marker_mask"], dtype=bool)
    results = {}
    for name in names:
        path = {"fp32": FP32, "fp16": MODELS / "laya-android.fp16.onnx",
                "int8": MODELS / "laya-android.int8.onnx"}.get(name, MODELS / ("laya-android.%s.onnx" % name))
        sess = session(path)
        sess.run(None, base[0]["feed"])  # warm-up
        rows, t_total = [], 0.0
        for b in base:
            if split:  # each row alone, as lever 1 runs them: own activation ranges, no padding
                (l0, _), t0 = timed(sess, row(b["feed"], 0))
                (l1, _), t1 = timed(sess, row(b["feed"], 1))
                logits, t = (l0[0], l1[0]), t0 + t1
            else:
                (logits, _), t = timed(sess, b["feed"])
            t_total += t
            ks = [int(b["feed"]["marker_mask"][r].sum()) for r in (0, 1)]
            p_op, p_tg = probs(logits[0], ks[0], temp), probs(logits[1], ks[1], temp)
            keys = list(b["op_p"])
            o = {"op": keys[int(p_op.argmax())], "op_p": dict(zip(keys, p_op.tolist())),
                 "target": b["idx"][int(p_tg.argmax())]}
            op_ok, tg_ok, dp = agree(b, o)
            rows.append({"pair": b["pair"], "op_ok": op_ok, "tg_ok": tg_ok, "dp_op": dp,
                         "dp_tg": float(np.abs(p_tg - np.array(list(b["target_p"].values()))).max()),
                         "tap": b["op"] in TAP, "base": (b["op"], b["op_p"][b["op"]]), "got": (o["op"], o["op_p"][o["op"]])})
        fl = sess.run(None, fx_feed)[0]
        fx_d = float(max(np.abs(fl[r, :k] - np.array(fx["expected_logits"][r][:k])).max()
                         for r, k in enumerate(np.array(fx["marker_mask"]).sum(1))))
        n, taps = len(rows), [r for r in rows if r["tap"]]
        res = {"ort": ort.__version__, "size_mb": path.stat().st_size / 1e6, "op": sum(r["op_ok"] for r in rows), "n": n,
               "target": sum(r["tg_ok"] for r in rows), "target_taps": sum(r["tg_ok"] for r in taps), "taps": len(taps),
               "mean_dp_op": float(np.mean([r["dp_op"] for r in rows])), "max_dp_op": max(r["dp_op"] for r in rows),
               "mean_dp_tg": float(np.mean([r["dp_tg"] for r in rows])), "max_dp_tg": max(r["dp_tg"] for r in rows),
               "mac_s": t_total, "fixture_op": int(fl[0].argmax()), "fixture_target": int(fl[1, :14].argmax()),
               "fixture_dlogit_vs_fp16": fx_d,
               "flips": [(r["pair"], r["base"], r["got"]) for r in rows if not r["op_ok"]],
               "tap_target_misses": [r["pair"] for r in taps if not r["tg_ok"]]}
        results["%s@%s%s%s" % (name, ort.__version__, "+kleidiai" if KLEIDIAI else "", "+split" if split else "")] = res
        print("%-12s %4.0f MB | op %d/%d | target %d/%d (taps %d/%d) | |dp_op| mean %.3f max %.3f | "
              "|dp_tg| mean %.3f max %.3f | Mac %.1f s | fixture op=%d tgt=%d d=%.3f" % (
                  name, res["size_mb"], res["op"], n, res["target"], n, res["target_taps"], res["taps"],
                  res["mean_dp_op"], res["max_dp_op"], res["mean_dp_tg"], res["max_dp_tg"], t_total,
                  res["fixture_op"], res["fixture_target"], fx_d))
        for p, b, g in res["flips"]:
            print("    op flip pair %d: %s %.2f -> %s %.2f" % (p, b[0], b[1], g[0], g[1]))
        del sess
    out = MODELS / "speed_eval_results.json"
    old = json.loads(out.read_text()) if out.exists() else {}
    out.write_text(json.dumps({**old, **results}, indent=1))


if __name__ == "__main__":
    stage, args = sys.argv[1], sys.argv[2:]
    {"quantize": lambda: quantize(args), "ref": ref,
     "eval": lambda: evaluate([a for a in args if a != "--split"], "--split" in args),
     "report": lambda: report_ref(*(lambda d: (d["recs"], d["split"]))(pickle.loads(REF.read_bytes())))}[stage]()
