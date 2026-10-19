"""A smaller Laya-Android (research/28): fewer encoder layers, distilled from the teacher, same input contract.

    python student.py make  --layers 0,2,4,6,8,10,12,14,16,18,20 --out ckpt/s11-init   # T2: student from the teacher
    python student.py cache                                     # T4: teacher logits on train, once
    python student.py train --init ckpt/s11-init --out ckpt/s11 # T4: distil, keep the best val Policy Joint
    python student.py train --init ckpt/s11 --out ckpt/s11f --train-file friday_train.jsonl --val-file friday_val.jsonl \
        --replay 0.4 --epochs 1 --encoder-lr 1e-5 --freeze-emb                # E5: S11 on Friday's input format
    python student.py eval  --model ckpt/s11 --name s11         # T5: test metrics (also --model teacher)
    python student.py export --model ckpt/s11 --name s11        # T6: ONNX -> nb8-h-e16 (the shipped settings) + parity
    python student.py parity --model teacher --onnx shipped/laya-android.nb8-h-e16.onnx --name shipped  # baseline
    python student.py vocab                                     # T7: token ids used (analysis only)
    python student.py vocab-eval --model teacher --name teacher-en   # T7: what the English-only cut costs [estimate]
    python student.py trim --model ckpt/s11 --onnx out/laya-android-s11.nb8-h-e16.onnx --name s11  # T7: cut file + map
    python student.py fixture --onnx out/laya-android-s11.nb8-h-e16.onnx --name s11   # phone-bench fixture logits
    python student.py assistant --model ckpt/s11 --name s11     # skill routing + slot picks (eval_assistant's cases)
    python student.py onnx-eval --model ckpt/s11 --onnx out/laya-android-s11.nb8-h-e16.onnx --name s11-nb8 --torch-name s11
    python student.py smoke                                     # synthetic rows for a dry run (STUDENT_DATA=smoke)

Runs on the GPU box only (D-042). Reuses laya's own training loop and laya-android's own evaluation harness; the
export imports the repo's scripts/export_onnx.py and scripts/speed_eval.py (copied into repo-scripts/).
"""
import argparse
import gzip
import hashlib
import json
import os
import random
import re
import shutil
import sys
import time
from collections import Counter
from pathlib import Path

import numpy as np
import torch

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE / "laya-android-src" / "src"), str(HERE / "laya-android-src" / "scripts"),
                str(HERE / "repo-scripts")]
os.environ.setdefault("HF_HOME", "/root/friday-laya/hf")

import laya.train as LT  # noqa: E402
import serialization as S  # noqa: E402
import eval_laya_android as E  # noqa: E402  (laya-android's harness: evaluate_split, encode, logits_for, BUCKETS)

_row = S.row
# Hand-off lines carry the laya row their builder made (FORMAT.md): use it, so Friday's format needs no builder here.
# For the trained format it equals serialization.row(item) (checked when the hand-off was written).
S.row = lambda it, low_level=False: (_row(it, low_level) if low_level or "state" not in it else
                                     {k: it[k] for k in ("state", "questions", "expected")})

TEACHER, REV = "ByunByun/laya-android", "5a03b2bc4220ec786a00a87eacdaa75cb844bd6b"
SHA = {"model.safetensors": "4ffca5d9a75f4888dc029b1de1dc2d81b77944b69f6b22c93db5285a66262993"}
DATA = Path(os.environ.get("STUDENT_DATA", "/root/friday-laya/laya/data"))
CACHE = HERE / "cache" / ("%s_teacher_train_logits.pt" % DATA.name)
OUT = HERE / "out"
BUCKETS = [("0", 0, 0)] + E.BUCKETS  # all steps by candidate count (screens with no candidates too)
MAX_LEN, HEAD_MAX_LEN = E.MAX_LEN, E.HEAD_MAX_LEN  # 3072 / 1536, the teacher's


def teacher_dir():
    from huggingface_hub import snapshot_download
    from laya.revisions import verify_digests
    d = snapshot_download(TEACHER, revision=REV, allow_patterns=["rl_agent_config.json", "model.safetensors",
                                                                  "tokenizer/*", "encoder/*"])
    verify_digests(d, SHA)
    return Path(d)


def resolve(model):
    return teacher_dir() if model == "teacher" else Path(model)


def data_path(split):
    """ac_<split>.jsonl in DATA, or a file name there (friday_train.jsonl)."""
    return DATA / (split if split.endswith(".jsonl") else "ac_%s.jsonl" % split)


def steps(split):
    """Processed AndroidControl steps (laya-android's item format: goal, history, candidates, operation_gold, ...)."""
    with open(data_path(split), encoding="utf8") as f:
        return [json.loads(line) for line in f]


# ---------------------------------------------------------------------------------------------------- T2: make
def make(layers, out):
    from safetensors.torch import load_file, save_file
    src, out = teacher_dir(), Path(out)
    keep = [int(x) for x in layers.split(",")]
    ecfg = json.loads((src / "encoder" / "config.json").read_text())
    n, g = ecfg["num_hidden_layers"], ecfg["global_attn_every_n_layers"]
    # layer 0 has no attention pre-norm in ModernBERT, so it must stay in slot 0
    assert keep[0] == 0 and keep == sorted(set(keep)) and keep[-1] < n, keep
    types = [ecfg["layer_types"][t] for t in keep]
    # transformers 4.x picks global layers by i % g, transformers 5 reads layer_types: both must agree
    assert types == ["full_attention" if i % g == 0 else "sliding_attention" for i in range(len(keep))], types
    ecfg.update(num_hidden_layers=len(keep), layer_types=types)
    sd, new = load_file(str(src / "model.safetensors")), {}
    for k, v in sd.items():
        m = re.match(r"encoder\.layers\.(\d+)\.(.*)", k)
        if not m:
            new[k] = v
        elif int(m[1]) in keep:
            new["encoder.layers.%d.%s" % (keep.index(int(m[1])), m[2])] = v
    out.mkdir(parents=True)
    save_file(new, str(out / "model.safetensors"))
    shutil.copytree(src / "tokenizer", out / "tokenizer")
    (out / "encoder").mkdir()
    (out / "encoder" / "config.json").write_text(json.dumps(ecfg, indent=2))
    cfg = json.loads((src / "rl_agent_config.json").read_text())
    cfg["student"] = {"teacher": "%s@%s" % (TEACHER, REV), "layers": keep}
    (out / "rl_agent_config.json").write_text(json.dumps(cfg, indent=2))
    check_loads(out)


def check_loads(path):
    """Loads through laya's own Agent (strict state dict) and answers a real-shaped question."""
    import laya
    agent = laya.load(str(path), device="cuda")
    enc = agent.model.encoder
    print("loaded %s: %d layers, %.1fM parameters, global layers %s" % (
        path, enc.config.num_hidden_layers, sum(p.numel() for p in agent.model.parameters()) / 1e6,
        [i for i, layer in enumerate(enc.layers) if layer.attn.local_attention == (-1, -1)]))
    labels = ["Search (EditText) edit", "Clock (TextView)", "Settings (TextView)", "Timer (TextView) selected"]
    state = {"goal": "Open the Clock app and go to the Timer tab", "history": ["OPEN_APP Clock"],
             "ui": ["[%d] %s" % (i, lab) for i, lab in enumerate(labels)]}
    qs = {"operation": {"type": "choice", "instructions": S.Q_OP, "criteria": dict(S.OP_DESC)},
          "target": {"type": "choice", "instructions": S.Q_TARGET, "criteria": {str(i): lab for i, lab in enumerate(labels)}}}
    a = agent.system_one(state, qs, max_len=MAX_LEN, head_max_len=HEAD_MAX_LEN)["answers"]
    print("  answers: operation %s %.3f, target %s %.3f" % (
        a["operation"]["choice"], a["operation"]["answer_confidence"], a["target"]["choice"],
        a["target"]["answer_confidence"]))


# ---------------------------------------------------------------------------------------------------- T4: cache
def flat_items(tok, rows):
    """[(step index, question id, encoded)] in canonical option order, as the harness encodes them."""
    out = []
    for i, it in enumerate(rows):
        out += [(i, qid, enc) for qid, enc in E.encode(tok, it, MAX_LEN, HEAD_MAX_LEN, False) if enc is not None]
    return out


@torch.no_grad()
def cache(batch_tokens=24576):
    model, tok, _ = E.load(str(teacher_dir()))
    rows = steps("train")
    flat = flat_items(tok, rows)
    order = sorted(range(len(flat)), key=lambda j: len(flat[j][2]["ids"]))
    logits, t0, start = {}, time.time(), 0
    while start < len(order):  # length-sorted batches under a token budget (bf16, as the harness runs)
        end = start + 1
        while end < len(order) and (end - start + 1) * len(flat[order[end]][2]["ids"]) <= batch_tokens:
            end += 1
        chunk = [flat[j] for j in order[start:end]]
        for (i, qid, enc), row in zip(chunk, E.logits_for(model, tok, [c[2] for c in chunk])):
            logits[(i, qid)] = row[:len(enc["markers"])].astype(np.float32)
        start = end
    CACHE.parent.mkdir(exist_ok=True)
    torch.save({"steps": len(rows), "items": len(flat), "logits": logits}, CACHE)
    print("cached %d teacher distributions over %d train steps in %.0f s -> %s" % (
        len(logits), len(rows), time.time() - t0, CACHE))


ASSIST_CACHE = HERE / "cache" / "assist_teacher.pt"


@torch.no_grad()
def cache_assist(n_instructions=13600, batch=64):
    """Teacher answers to Friday's own routing and slot questions (Skills.kt) over AndroidControl train requests: every
    unique goal, plus a sample of step instructions for routing; the goals' trimmed spans for the slot. S11 lost
    routing (E6), so S11-F learns the teacher's answers here, as it replays the trained format (research/28 E5)."""
    import eval_assistant as A
    model, tok, _ = E.load(str(teacher_dir()))
    ac = steps("train")
    goals = sorted({it["goal"] for it in ac})
    ins = sorted({it["step_instruction"] for it in ac if it.get("step_instruction")} - set(goals))
    ins = random.Random(0).sample(ins, min(n_instructions, len(ins)))
    rows = [{"state": {"goal": g}, "questions": {"skill": {"type": "choice", "instructions": ROUTE_Q,
                                                           "criteria": A.SKILLS}}} for g in goals + ins]
    for g in goals:
        sp = A.spans(g, True)
        if len(sp) >= 2:
            rows.append({"state": {"goal": g}, "questions": {"slot": {
                "type": "choice", "instructions": SLOT_Q, "criteria": {str(i): x for i, x in enumerate(sp[:64])}}}})
    flat = []
    for i, r in enumerate(rows):
        state_ids = LT.encode_state(tok, r["state"], MAX_LEN)
        for qid, q in r["questions"].items():
            qi = LT.to_internal(qid, q)
            item, _ = LT.make_item(tok, qi, [1.0] + [0.0] * (len(q["criteria"]) - 1), state_ids, HEAD_MAX_LEN, MAX_LEN)
            if item:
                flat.append((i, qid, LT.encode_item(tok, item, MAX_LEN, HEAD_MAX_LEN)))
    logits = {}
    for s0 in range(0, len(flat), batch):
        chunk = flat[s0:s0 + batch]
        for (i, qid, enc), row in zip(chunk, E.logits_for(model, tok, [c[2] for c in chunk])):
            logits[(i, qid)] = row[:len(enc["markers"])].astype(np.float32)
    for i, r in enumerate(rows):  # the teacher's pick stands in for the gold (alpha = 1 uses only the distribution)
        r["questions"] = {q: v for q, v in r["questions"].items() if (i, q) in logits}
        r["expected"] = {q: list(v["criteria"])[int(np.argmax(logits[(i, q)]))] for q, v in r["questions"].items()}
    torch.save({"rows": rows, "logits": logits}, ASSIST_CACHE)
    print("assist cache: %d rows (%d routing, %d slot), %d teacher answers -> %s" % (
        len(rows), len(goals) + len(ins), len(rows) - len(goals) - len(ins), len(logits), ASSIST_CACHE))


# ---------------------------------------------------------------------------------------------------- T4: train
def _forward_bf16(model, batch, device, amp, detach_encoder):
    """laya-android's patch: bf16 autocast instead of laya's hard-coded fp16 (train_laya_android.py)."""
    args = [batch[k].to(device) for k in ("input_ids", "attention_mask", "marker_pos", "marker_mask", "qtype")]
    with torch.autocast(device.type, dtype=torch.bfloat16, enabled=amp):
        logits, _ = model(*args, detach_encoder=detach_encoder)
    return logits.float()


def mix(gold, z, alpha, tau):
    """alpha x the teacher's softmax(z / tau) + (1 - alpha) x the gold one-hot (research/28 §3.3)."""
    p = np.exp((z - z.max()) / tau)
    return list(alpha * p / p.sum() + (1 - alpha) * np.asarray(gold))


def distil_items(tok, rows, teacher, alpha, tau, idx=None):
    """Items with target = mix(gold, teacher). idx: the rows' indices in ac_train (the teacher cache's keys)."""
    items, skipped = [], Counter()
    for k, it in enumerate(rows):
        i = idx[k] if idx else k
        r = S.row(it)
        state_ids = LT.encode_state(tok, r["state"], MAX_LEN)
        for qid, q in r["questions"].items():
            qi = LT.to_internal(qid, q)
            gold = LT.target_from_expected(qi, r["expected"][qid])
            z = teacher.get((i, qid))
            target = gold if z is None or alpha == 0 else mix(gold, z, alpha, tau)
            item, reason = LT.make_item(tok, qi, target, state_ids, HEAD_MAX_LEN, MAX_LEN)
            if item is None:
                skipped[reason] += 1
            else:
                items.append(item)
                skipped["teacher_missing"] += z is None
    return items, dict(skipped)


def train(init, out, alpha, tau, epochs, limit, train_file="train", val_file="val", replay=0.0, encoder_lr=2.5e-5,
          freeze_emb=False, wait_for=None, assist=False):
    """Distil (trained format, the defaults), or fine-tune on another format's rows with hard labels plus a `replay`
    share of each epoch drawn from trained-format train steps with the teacher mix (S11-F, research/28 E5)."""
    from laya.calibrate import fit_temperature_map
    from safetensors.torch import load_file
    LT._forward = _forward_bf16
    out = Path(out)
    model, tok, cfg = LT.load_checkpoint(str(init))
    teacher_cfg = json.loads((teacher_dir() / "rl_agent_config.json").read_text())
    ac = steps("train")
    teacher = torch.load(CACHE, weights_only=False)
    assert teacher["steps"] == len(ac), "teacher cache is for another train file"
    if train_file == "train":
        rows = ac[:limit] if limit else ac
        items, skipped = distil_items(tok, rows, teacher["logits"], alpha, tau)
    else:
        rows = steps(train_file)[:limit] if limit else steps(train_file)
        items, skipped = distil_items(tok, rows, {}, 0.0, tau)  # hard labels
        want = int(round(len(items) * replay / (1 - replay)))
        order = list(range(len(ac)))
        random.Random(0).shuffle(order)
        pick, n = [], 0
        for i in order:  # whole steps until the replay share is reached
            if n >= want:
                break
            pick.append(i)
            n += 1 + bool(ac[i]["target_groundable"])
        replayed, _ = distil_items(tok, [ac[i] for i in pick], teacher["logits"], alpha, tau, idx=pick)
        skipped["replay_items"] = len(replayed)
        items += replayed
    if assist:  # Friday's routing and slot questions, the teacher's answers as targets (cache_assist)
        ast = torch.load(ASSIST_CACHE, weights_only=False)
        extra, _ = distil_items(tok, ast["rows"], ast["logits"], 1.0, 1.0)
        skipped["assist_items"] = len(extra)
        items += extra
    if freeze_emb:  # research/15: a fine-tune of a trained model keeps the embedding table frozen
        model.encoder.embeddings.tok_embeddings.weight.requires_grad_(False)
    print("train steps %d, items %d, skipped %s" % (len(rows), len(items), skipped), flush=True)
    while wait_for and not any(Path(w).exists() for w in wait_for.split(",")):  # items built while the GPU is busy
        time.sleep(30)
    tc = LT.TrainConfig(epochs=epochs, micro_batch=8, grad_accum=4, encoder_lr=encoder_lr, head_lr=1e-4,
                        loss="soft-ce", shuffle_options=("choice",), seed=0, gradient_checkpointing=True,
                        max_len=MAX_LEN, head_max_len=HEAD_MAX_LEN)
    val_path = str(data_path(val_file))
    log = {"init": str(init), "alpha": alpha, "tau": tau, "train_file": train_file, "val_file": val_file,
           "replay": replay, "encoder_lr": encoder_lr, "freeze_emb": freeze_emb, "assist": assist, "train_steps": len(rows),
           "train_items": len(items), "skipped": skipped, "epochs": []}
    best = {"joint": -1.0}
    out.mkdir(parents=True, exist_ok=True)

    def on_epoch_end(epoch, loss):
        model.eval()
        v = E.evaluate_split(model, tok, cfg, val_path, latency_n=0)
        model.train()
        log["epochs"].append({"epoch": epoch + 1, "train_loss": loss, "val": v,
                              "minutes": round((time.time() - t0) / 60, 1)})
        print("epoch %d loss %.4f val op %.4f target %.4f joint %.4f (%.0f min)" % (
            epoch + 1, loss, v["operation_accuracy"], v["target_top1_accuracy"], v["joint_action_accuracy"],
            (time.time() - t0) / 60), flush=True)
        if v["joint_action_accuracy"] > best["joint"]:  # selection: val Policy Joint only; keep one copy (disk)
            best.update(joint=v["joint_action_accuracy"], epoch=epoch + 1)
            LT.save_checkpoint(model, tok, cfg, str(out / "best"))
        (out / "train_log.json").write_text(json.dumps(log, indent=1))

    t0 = time.time()
    LT.train_model(model, tok, items, tc, torch.device("cuda"), MAX_LEN, HEAD_MAX_LEN, on_epoch_end=on_epoch_end)
    log["train_hours"], log["best"] = round((time.time() - t0) / 3600, 3), best
    model.load_state_dict(load_file(str(out / "best" / "model.safetensors")))
    model.float()

    # temperature on val only (the teacher's recipe), then built into the scorer for the phone's fixed T (§3.5)
    val_items, _ = LT.items_from_rows(tok, (S.row(it) for it in steps(val_file)), MAX_LEN, HEAD_MAX_LEN)
    fitted = fit_temperature_map(LT.calibration_records(model, tok, val_items, torch.device("cuda"), MAX_LEN,
                                                        HEAD_MAX_LEN))
    assert not fitted["temperature_by_options"], fitted  # the phone applies one temperature per type
    t_phone, t_fit = teacher_cfg["temperature"][0], fitted["temperature"][0]
    scale = t_phone / t_fit
    with torch.no_grad():
        model.scorer[3].weight.mul_(scale)
        model.scorer[3].bias.mul_(scale)
    final = dict(cfg, temperature=teacher_cfg["temperature"], temperature_by_options={})
    final["student"] = dict(cfg.get("student", {}), alpha=alpha, tau=tau, best_epoch=best["epoch"],
                            fitted_temperature=t_fit, scorer_scale=scale, train_hours=log["train_hours"])
    LT.save_checkpoint(model, tok, final, str(out))
    shutil.rmtree(out / "best")
    log["fitted_temperature"], log["scorer_scale"] = t_fit, scale
    model.eval()
    log["val_final"] = E.evaluate_split(model, tok, final, val_path, latency_n=0)  # ~the best epoch's (fp16 save)
    (out / "train_log.json").write_text(json.dumps(log, indent=1))
    print("saved %s: best epoch %d, val joint %.4f, T fitted %.4f -> scorer x %.4f" % (
        out, best["epoch"], best["joint"], t_fit, scale), flush=True)


# ---------------------------------------------------------------------------------------------------- T5: eval
def score(rows, preds, keys=None):
    """Operation accuracy, target top-1 and Policy Joint exactly as evaluate_split counts them, over `keys`."""
    ops = list(S.OP_DESC)
    m = Counter()
    for i in (range(len(rows)) if keys is None else keys):
        it, (p_op, p_t) = rows[i], preds[i]
        op_ok = p_op is not None and ops[int(np.argmax(p_op))] == it["operation_gold"]
        m["steps"] += 1
        m["op"] += op_ok
        if it["operation_gold"] in ("CLICK", "LONG_PRESS"):
            if not it["target_groundable"]:
                continue
            tgt_ok = p_t is not None and int(np.argmax(p_t)) == it["target_gold"]
            m["groundable"] += 1
            m["target"] += tgt_ok
            op_ok = op_ok and tgt_ok
        m["joint_n"] += 1
        m["joint"] += op_ok
    r = lambda a, b: round(a / b, 4) if b else None  # noqa: E731
    return {"steps": m["steps"], "operation": r(m["op"], m["steps"]), "target_top1": r(m["target"], m["groundable"]),
            "policy_joint": r(m["joint"], m["joint_n"]), "groundable": m["groundable"]}


def evaluate(model, name, split="test"):
    """Test rows once each: internal metrics (harness), Type/Grounding (InfiGUI-R1 judge), all by candidate bucket."""
    mdl, tok, cfg = E.load(str(resolve(model)))
    path, rows = data_path(split), steps(split)
    OUT.mkdir(exist_ok=True)
    split = split.replace(".jsonl", "")
    preds_path = OUT / ("%s.%s.preds.jsonl.gz" % (name, split))
    harness = E.evaluate_split(mdl, tok, cfg, str(path), latency_n=0, preds_path=str(preds_path))
    with gzip.open(preds_path, "rt", encoding="utf8") as f:
        pl = [json.loads(line) for line in f]
    preds = [(None if p["operation_probs"] is None else np.array(list(p["operation_probs"].values())),
              None if p["target_probs"] is None else np.array(p["target_probs"])) for p in pl]
    if "row" in rows[0]:  # Friday's format: a step can be two lines; the harness's own numbers count lines
        rows, preds = merge_lines(rows, preds)
        harness = {k: v for k, v in harness.items() if k in ("ece_operation", "ece_target", "unfit_items")}
    overall = score(rows, preds)
    if "joint_action_accuracy" in harness:  # same counting as the harness (5-dp probabilities: allow a tie or two)
        assert abs(overall["policy_joint"] - harness["joint_action_accuracy"]) <= 1e-3, (overall, harness)
    report = {"model": str(model), "split": split, "harness": harness, "overall": overall,
              "op_recall": op_recall(rows, preds), "by_bucket": {}, "by_subsplit": {}}
    bucket = lambda c: next(b for b, lo, hi in BUCKETS if lo <= c <= hi)  # noqa: E731
    for b, _, _ in BUCKETS:
        report["by_bucket"][b] = score(rows, preds, [i for i, it in enumerate(rows) if bucket(it["candidate_count"]) == b])
    for sub in sorted({s for it in rows for s in it.get("subsplits", [])}):
        report["by_subsplit"][sub] = score(rows, preds, [i for i, it in enumerate(rows) if sub in it["subsplits"]])
    ref = reference_scores(rows, preds)
    if ref:
        report["reference"] = ref
    (OUT / ("%s.%s.metrics.json" % (name, split))).write_text(json.dumps(report, indent=1))
    print(json.dumps({k: report[k] for k in ("overall", "by_bucket")}, indent=1))
    if ref:
        print(json.dumps(ref, indent=1))


def merge_lines(rows, preds):
    """Friday-format lines -> one record per step: the operation from the line that asks it, the target from the line
    that asks it (scored on that line's shortlist), and a groundable tap whose gold the shortlist cut counted wrong,
    as research/27 L3 counts it."""
    steps_, order = {}, []
    for it, (p_op, p_t) in zip(rows, preds):
        key = (it["episode_id"], it["step"])
        if key not in steps_:
            steps_[key] = {"op": None, "p_op": None, "t": None, "p_t": None}
            order.append(key)
        if p_op is not None:
            steps_[key].update(op=it, p_op=p_op)
        if p_t is not None:
            steps_[key].update(t=it, p_t=p_t)
    out_rows, out_preds = [], []
    for key in order:
        m = steps_[key]
        it = m["op"]
        gold = m["t"]["target_gold"] if m["t"] else (-1 if it["target_gold"] is not None else None)
        out_rows.append({**it, "target_gold": gold, "candidates": (m["t"] or it)["candidates"]})
        out_preds.append((m["p_op"], m["p_t"]))
    return out_rows, out_preds


def op_recall(rows, preds):
    """Per gold operation: share predicted right, and how many steps (DONE and WAIT are the weak ones)."""
    ops, hit, n = list(S.OP_DESC), Counter(), Counter()
    for it, (p_op, _) in zip(rows, preds):
        n[it["operation_gold"]] += 1
        hit[it["operation_gold"]] += p_op is not None and ops[int(np.argmax(p_op))] == it["operation_gold"]
    return {op: [round(hit[op] / n[op], 4), n[op]] for op in ops if n[op]}


def reference_scores(rows, preds):
    """Type and Grounding with the unmodified InfiGUI-R1 judge, as laya-android's evaluate_androidcontrol.py does."""
    ref_path = Path(os.environ.get("STUDENT_REFERENCE", "/root/friday-laya/laya/ac/raw/infigui/android_control_test.json"))
    if not ref_path.exists() or "candidates" not in rows[0]:
        return None
    from evaluate_androidcontrol import evaluate_android_control_action, to_reference
    ref = {}
    for line in json.loads(ref_path.read_text()):
        for k, pam in enumerate(line["step_check_pams"]):
            ref[(line["episode"]["episode_id"], k)] = (pam, line["width"], line["height"])
    ops = list(S.OP_DESC)
    per = {}
    for it, (p_op, p_t) in zip(rows, preds):
        key = (it["episode_id"], it["step"])
        if it["operation_gold"] == "DONE" or key not in ref:
            continue
        pam, w, h = ref[key]
        op = ops[int(np.argmax(p_op))]
        box = it["candidates"][int(np.argmax(p_t))]["bounds"] if op in ("CLICK", "LONG_PRESS") and p_t is not None else None
        pred = to_reference(op, box, w, h)
        type_ok, exact = evaluate_android_control_action(pred, pam, w, h, w, h)
        per[key] = (bool(type_ok), bool(exact) and pred["action"] == "click", pam["action"] == "click",
                    it["candidate_count"], it.get("subsplits", []))
    if len(per) != len(ref):
        print("warning: %d of %d reference steps matched" % (len(per), len(ref)))
    if not per:
        return None

    def agg(vals):
        n, clicks = len(vals), sum(v[2] for v in vals)
        return {"steps": n, "type": round(100 * sum(v[0] for v in vals) / n, 2) if n else None,
                "grounding": round(100 * sum(v[1] for v in vals) / clicks, 2) if clicks else None, "gold_clicks": clicks}
    out = {"all": agg(list(per.values())), "by_bucket": {}, "by_subsplit": {}}
    for b, lo, hi in BUCKETS:
        out["by_bucket"][b] = agg([v for v in per.values() if lo <= v[3] <= hi])
    for sub in sorted({s for v in per.values() for s in v[4]}):
        out["by_subsplit"][sub] = agg([v for v in per.values() if sub in v[4]])
    return out


# ---------------------------------------------------------------------------------------------------- T6: export
SHIPPED = HERE / "shipped" / "laya-android.nb8-h-e16.onnx"  # the phone's file, for the contract check (optional)


def export(model, name, n_parity, keep_fp32=False):
    import onnx
    import export_onnx as X
    import speed_eval as Q
    model = resolve(model)
    OUT.mkdir(exist_ok=True)
    fp32 = OUT / ("laya-android-%s.onnx" % name)
    # the shipped exporter and quantizer, pointed at the student (same opset, exporter, MatMulNBits settings)
    X.MODEL, X.PIN, X.OUT = str(model), {"revision": None, "expected_sha256": None}, fp32
    X.main()
    Q.FP32, Q.MODELS = fp32, OUT
    Q.quantize(["nb8-h-e16"])
    nb8 = OUT / ("laya-android-%s.nb8-h-e16.onnx" % name)
    os.replace(OUT / "laya-android.nb8-h-e16.onnx", nb8)
    if not keep_fp32:
        fp32.unlink()  # 1 GB; the nb8 file is what ships
    report = {"file": nb8.name, "mb": round(nb8.stat().st_size / 1e6, 1)}
    m = onnx.load(str(nb8), load_external_data=False)
    report["io"] = io_contract(m)
    report["ops"] = dict(Counter(n.op_type for n in m.graph.node))
    if SHIPPED.exists():
        s = onnx.load(str(SHIPPED), load_external_data=False)
        report["io_matches_shipped"] = io_contract(s) == report["io"]
        report["shipped_ops"] = dict(Counter(n.op_type for n in s.graph.node))
        report["op_types_not_in_shipped"] = sorted(set(report["ops"]) - set(report["shipped_ops"]))
        del s
    del m
    report["parity"] = parity(model, nb8, n_parity)
    (OUT / ("%s.export.json" % name)).write_text(json.dumps(report, indent=1))
    print(json.dumps({k: v for k, v in report.items() if k not in ("ops", "shipped_ops")}, indent=1))


def io_contract(m):
    def t(v):
        tt = v.type.tensor_type
        return [v.name, tt.elem_type, [d.dim_param or d.dim_value for d in tt.shape.dim]]
    return {"inputs": [t(v) for v in m.graph.input], "outputs": [t(v) for v in m.graph.output]}


def parity(model, nb8, n):
    """PyTorch fp32 (laya's Agent, CPU) vs the nb8 file (laya's ONNXAgent, ORT CPU) on n random test steps."""
    import laya
    pt = laya.load(str(model), device="cpu")
    ox = laya.load(str(model), backend="onnx", onnx_path=str(nb8))
    rows = steps("test")
    pick = random.Random(0).sample(range(len(rows)), min(n, len(rows)))
    res, flips = Counter(), []
    worst = {"operation": 0.0, "target": 0.0}
    for i in pick:
        r = S.row(rows[i])
        a = pt.system_one(r["state"], r["questions"], max_len=MAX_LEN, head_max_len=HEAD_MAX_LEN)["answers"]
        b = ox.system_one(r["state"], r["questions"], max_len=MAX_LEN, head_max_len=HEAD_MAX_LEN)["answers"]
        for q in a:
            res[q + "_n"] += 1
            res[q + "_same"] += a[q]["choice"] == b[q]["choice"]
            worst[q] = max(worst[q], max(abs(a[q]["probabilities"][k] - b[q]["probabilities"][k]) for k in a[q]["probabilities"]))
            if a[q]["choice"] != b[q]["choice"]:  # how close was PyTorch's own call?
                p1, p2 = sorted(a[q]["probabilities"].values())[-2:][::-1]
                flips.append({"step": i, "question": q, "torch": [a[q]["choice"], p1], "nb8": b[q]["choice"],
                              "torch_margin": round(p1 - p2, 4)})
    return {"steps": len(pick), "operation_same": "%d/%d" % (res["operation_same"], res["operation_n"]),
            "target_same": "%d/%d" % (res["target_same"], res["target_n"]), "flips": flips,
            "max_abs_dp": {q: round(v, 4) for q, v in worst.items()}, "runtime": "onnxruntime %s, x86 CPU" % ort_version()}


def ort_version():
    import onnxruntime
    return onnxruntime.__version__


def onnx_eval(model, onnx_path, name, torch_name, sample=None, threads=8):
    """The phone's file itself on test: laya's ONNXAgent on an nb8 file (x86 CPU, phone-like kernels), scored like
    `evaluate`, and paired with the PyTorch run `torch_name` on the same steps (review: the gate numbers are bf16)."""
    import laya
    import onnxruntime as ort
    agent = laya.load(str(resolve(model)), backend="onnx", onnx_path=str(onnx_path))
    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    so.intra_op_num_threads = threads
    so.add_session_config_entry("mlas.disable_kleidiai", "1")
    agent.session = ort.InferenceSession(str(onnx_path), sess_options=so, providers=["CPUExecutionProvider"])
    rows = steps("test")
    idx = sorted(random.Random(0).sample(range(len(rows)), sample)) if sample else list(range(len(rows)))
    ops, preds, t0 = list(S.OP_DESC), [], time.time()
    for n, i in enumerate(idx):
        r = S.row(rows[i])
        a = agent.system_one(r["state"], r["questions"], max_len=MAX_LEN, head_max_len=HEAD_MAX_LEN)["answers"]
        preds.append((np.array([a["operation"]["probabilities"][o] for o in ops]),
                      np.array(list(a["target"]["probabilities"].values())) if "target" in a else None))
        if n % 500 == 0:
            print("%d/%d steps, %.0f s" % (n, len(idx), time.time() - t0), flush=True)
    sub = [rows[i] for i in idx]
    with gzip.open(OUT / ("%s.test.preds.jsonl.gz" % torch_name), "rt", encoding="utf8") as f:
        tp = [json.loads(line) for line in f]
    torch_preds = [(np.array(list(tp[i]["operation_probs"].values())),
                    None if tp[i]["target_probs"] is None else np.array(tp[i]["target_probs"])) for i in idx]
    same_op = sum(int(np.argmax(a[0])) == int(np.argmax(b[0])) for a, b in zip(preds, torch_preds))
    tq = [(a[1], b[1]) for a, b in zip(preds, torch_preds) if a[1] is not None and b[1] is not None]
    same_t = sum(int(np.argmax(a)) == int(np.argmax(b)) for a, b in tq)
    rep = {"model": str(model), "onnx": str(onnx_path), "steps": len(idx), "sample": sample, "threads": threads,
           "seconds": round(time.time() - t0), "nb8": score(sub, preds), "torch_same_steps": score(sub, torch_preds),
           "agreement_with_torch": {"operation": "%d/%d" % (same_op, len(idx)), "target": "%d/%d" % (same_t, len(tq))},
           "nb8_op_recall": op_recall(sub, preds)}
    ref = reference_scores(sub, preds)
    if ref:
        rep["nb8_reference"] = ref["all"]
        rep["torch_reference_same_steps"] = reference_scores(sub, torch_preds)["all"]
    (OUT / ("%s.onnx-eval.json" % name)).write_text(json.dumps(rep, indent=1))
    print(json.dumps(rep, indent=1))


# ---------------------------------------------------------------------------------------------------- T7: vocab
SLOT_Q = "Which words in the request name the song, artist, show, person, place or item?"  # Skills.kt
ROUTE_Q = "What does the user want Friday to do?"  # Skills.kt route()


def app_template_ids(tok):
    """Token ids of Friday's own fixed questions on this model (Skills.kt): routing over the 16 skills, the slot
    question, LayaEngine's warm-up. Their text must never be re-spelt."""
    from eval_assistant import SKILLS  # the same 16 (key, text) pairs as Skills.kt SKILLS
    rows = [{"state": {"goal": ""}, "questions": {"q": {"type": "choice", "instructions": ins, "criteria": crit}},
             "expected": {"q": next(iter(crit))}}
            for ins, crit in ((ROUTE_Q, SKILLS), (SLOT_Q, {"0": "x"}), ("Pick one.", {"a": "yes", "b": "no"}))]
    items, _ = LT.items_from_rows(tok, rows, MAX_LEN, HEAD_MAX_LEN)
    return {i for it in items for i in LT.encode_item(tok, it, MAX_LEN, HEAD_MAX_LEN)["ids"]}


def vocab():
    """Token ids in the sequences the model reads (question, options, state), train vs test (research/28 E4)."""
    from transformers import AutoTokenizer
    tok = AutoTokenizer.from_pretrained(str(teacher_dir() / "tokenizer"))
    flat = {s: flat_items(tok, steps(s)) for s in ("train", "val", "test")}
    seqs = {s: [enc["ids"] for _, _, enc in v] for s, v in flat.items()}
    counts = {s: Counter(i for ids in v for i in ids) for s, v in seqs.items()}
    train_ids = set(counts["train"])
    special = set(tok.all_special_ids)
    byte_ids = {i for t, i in tok.get_vocab().items() if re.fullmatch(r"<0x[0-9A-F]{2}>", t)}
    # the fixed templates (question texts, the 12 operation options, markers, option numbers up to 299)
    one = {"episode_id": 0, "step": 0, "goal": "", "history": [], "operation_gold": "CLICK", "target_gold": 0,
           "target_groundable": True, "candidate_count": 300,
           "candidates": [{"label": "", "class": "View", "editable": False, "long_clickable": False, "clickable": True,
                           "scrollable": False, "checked": False, "selected": False, "enabled": True}] * 300}
    templates = {i for _, _, enc in flat_items(tok, [one]) for i in enc["ids"]}
    tabs = {i for t, i in tok.get_vocab().items() if "\t" in t}  # Gemma has no <0x09> byte token (review)
    app = app_template_ids(tok)
    unseen = Counter({i: n for i, n in counts["test"].items() if i not in train_ids})
    keep = train_ids | special | byte_ids | templates | tabs | app
    differ = {(i, q) for i, q, enc in flat["test"] if any(t not in keep for t in enc["ids"])}
    emb_mb = lambda rows: round(rows * 768 * 2 / 1e6, 1)  # noqa: E731  fp16 table, as in nb8-h-e16
    report = {
        "vocab_size": len(tok), "unique_ids": {s: len(c) for s, c in counts.items()},
        "tokens": {s: sum(c.values()) for s, c in counts.items()},
        "test_token_coverage_by_train": round(1 - sum(unseen.values()) / sum(counts["test"].values()), 6),
        "test_unique_ids_unseen_in_train": len(unseen),
        "test_rows_with_an_unseen_token": len(differ), "test_rows": len(seqs["test"]),
        "test_steps_with_an_unseen_token": len({i for i, _ in differ}), "test_steps": len(steps("test")),
        "templates_outside_train": len(templates - train_ids), "tab_ids": len(tabs),
        "app_templates_outside_train": len(app - train_ids),
        "kept_rows_train_special_bytes": len(keep), "special_ids": sorted(special), "byte_fallback_ids": len(byte_ids),
        "embedding_mb_fp16": {"full": emb_mb(len(tok)), "kept": emb_mb(len(keep))},
        "most_common_unseen": [[tok.convert_ids_to_tokens(i), n] for i, n in unseen.most_common(25)],
    }
    OUT.mkdir(exist_ok=True)
    (OUT / "vocab.json").write_text(json.dumps(report, indent=1, ensure_ascii=False))
    np.save(OUT / "vocab_train_ids.npy", np.array(sorted(keep), dtype=np.int32))  # ids only, no text
    print(json.dumps({k: v for k, v in report.items() if k != "most_common_unseen"}, indent=1))


def make_respell(tok, keep):
    """old id -> old ids inside `keep`: kept ids stay; any other token is re-spelt from kept pieces (greedy longest
    match, else its UTF-8 byte tokens, which are all kept).
    shortcut: greedy re-spelling, not a rebuilt BPE tokenizer; rebuild tokenizer.json if the cut ever ships."""
    pieces = {p: i for p, i in tok.get_vocab().items() if i in keep}
    memo = {}

    def respell(i):
        if i in keep:
            return [i]
        if i not in memo:
            s, out, j = tok.convert_ids_to_tokens(i), [], 0
            while j < len(s):
                k = next((k for k in range(len(s), j, -1) if s[j:k] in pieces), None)
                if k is None:
                    out += [pieces.get("<0x%02X>" % b, tok.unk_token_id) for b in s[j].encode("utf8")]
                    j += 1
                else:
                    out.append(pieces[s[j:k]])
                    j = k
            memo[i] = out
        return memo[i]
    return respell


def patch_tokenizer(respell, to_new=None):
    """Every tokenizer call laya makes goes through encode_text: map its ids (then cap options at 48, as laya does)."""
    import laya.common as C
    real = C.encode_text

    def encode_text(t, text, **kw):
        enc = real(t, text, **kw)
        ids = [r for i in enc["input_ids"] for r in respell(i)]
        if to_new is not None:
            ids = [to_new[i] for i in ids]
        enc["input_ids"] = ids[:kw["max_length"]] if kw.get("truncation") else ids
        return enc
    import laya.agent
    import laya.onnx_agent
    # laya.train, laya.agent and laya.onnx_agent imported it by name
    C.encode_text = LT.encode_text = laya.agent.encode_text = laya.onnx_agent.encode_text = encode_text


def vocab_eval(model, name):
    """[estimate] What the English-only vocabulary costs: tokens outside it are re-spelt (make_respell) before the
    unchanged model reads them; then the usual test metrics."""
    from transformers import AutoTokenizer
    tok = AutoTokenizer.from_pretrained(str(teacher_dir() / "tokenizer"))
    patch_tokenizer(make_respell(tok, set(np.load(OUT / "vocab_train_ids.npy").tolist())))
    evaluate(model, name)


def trim(model, onnx_path, name, n_check=50):
    """The English-only cut of an nb8 file: embedding rows of the kept ids only (new id = rank of the old id), plus the
    old->new id map the phone applies right after its tokenizer. Checked: same logits as the uncut file fed the same
    re-spelt text."""
    import onnx
    import onnxruntime as ort
    from onnx import numpy_helper
    from transformers import AutoTokenizer
    from speed_eval import EMB_W
    keep = np.load(OUT / "vocab_train_ids.npy")
    assert list(keep[:5]) == [0, 1, 2, 3, 4], keep[:5]  # pad/eos/bos/unk/mask keep their ids (hard-coded in Kotlin)
    kept_hash = hashlib.sha256(np.ascontiguousarray(keep, dtype=np.int32).tobytes()).hexdigest()[:16]
    m = onnx.load(str(onnx_path))
    w = next(t for t in m.graph.initializer if t.name == EMB_W)
    w.CopyFrom(numpy_helper.from_array(numpy_helper.to_array(w)[keep], EMB_W))
    # the phone can refuse a map that doesn't match the file (review)
    onnx.helper.set_model_props(m, {"friday.vocab_kept": str(len(keep)), "friday.vocab_kept_sha256": kept_hash})
    out = OUT / ("laya-android-%s-en.nb8-h-e16.onnx" % name)
    onnx.save(m, str(out))
    del m
    tok = AutoTokenizer.from_pretrained(str(teacher_dir() / "tokenizer"))
    kept = set(keep.tolist())
    respell = make_respell(tok, kept)
    to_new = {int(o): n for n, o in enumerate(keep)}
    vmap = {"about": "research/28 E4. new id = position in `kept`; any other old id becomes the new ids in `respell`. "
                     "Apply right after the tokenizer, before laya's 48-token option cap and max_len clamp.",
            "old_vocab": len(tok), "new_vocab": len(keep), "kept_sha256": kept_hash,
            "kept_sha256_of": "the kept ids as little-endian int32, first 16 hex chars; equals the ONNX metadata",
            "kept": keep.tolist(),
            "respell": {str(i): [to_new[j] for j in respell(i)] for i in range(len(tok)) if i not in kept}}
    map_path = OUT / ("laya-android-%s-en.vocab-map.json" % name)
    map_path.write_text(json.dumps(vmap, separators=(",", ":")))
    # check: the cut file with new ids == the uncut file with the same re-spelt old ids
    import laya
    full = laya.load(str(resolve(model)), backend="onnx", onnx_path=str(onnx_path))
    rows = steps("test")
    pick = random.Random(1).sample(range(len(rows)), n_check)
    patch_tokenizer(respell)
    ref = [full.system_one(S.row(rows[i])["state"], S.row(rows[i])["questions"], max_len=MAX_LEN,
                           head_max_len=HEAD_MAX_LEN)["answers"] for i in pick]
    cut = laya.load(str(resolve(model)), backend="onnx", onnx_path=str(out))
    patch_tokenizer(lambda i: [i], to_new)  # on top of the re-spelling: old ids -> new ids, as the phone would
    worst, same = 0.0, 0
    for i, a in zip(pick, ref):
        b = cut.system_one(S.row(rows[i])["state"], S.row(rows[i])["questions"], max_len=MAX_LEN,
                           head_max_len=HEAD_MAX_LEN)["answers"]
        same += all(a[q]["choice"] == b[q]["choice"] for q in a)
        worst = max(worst, max(abs(a[q]["probabilities"][k] - b[q]["probabilities"][k]) for q in a for k in a[q]["probabilities"]))
    r = {"file": out.name, "mb": round(out.stat().st_size / 1e6, 1), "map": map_path.name,
         "map_mb": round(map_path.stat().st_size / 1e6, 1), "check_steps": n_check, "same_decisions": same,
         "max_abs_dp_vs_uncut_respelt": worst}
    (OUT / ("%s-en.trim.json" % name)).write_text(json.dumps(r, indent=1))
    print(json.dumps(r, indent=1))


def fixture(onnx_path, name, vmap=None):
    """models/fixture.json with this file's own logits (and new ids for a cut file), for the phone bench's parity line:
    the shipped fixture holds the teacher's logits, so a student would show a large max_logit_diff_vs_mac there."""
    import onnxruntime as ort
    fx = json.loads((HERE / "fixture.json").read_text())
    if vmap:
        new_of = {o: n for n, o in enumerate(json.loads(Path(vmap).read_text())["kept"])}
        fx["input_ids"] = [[new_of[i] for i in row] for row in fx["input_ids"]]  # KeyError = a re-spelt token
    feed = {k: np.array(fx[k], dtype=bool if k == "marker_mask" else np.int64)
            for k in ("input_ids", "attention_mask", "marker_pos", "marker_mask", "qtype")}
    so = ort.SessionOptions()
    so.add_session_config_entry("mlas.disable_kleidiai", "1")
    logits = ort.InferenceSession(str(onnx_path), so, providers=["CPUExecutionProvider"]).run(["logits"], feed)[0]
    old = np.array(json.loads((HERE / "fixture.json").read_text())["expected_logits"])
    fx["expected_logits"] = logits.tolist()
    fx["note"] = "expected_logits from %s on x86 ORT %s (research/28)" % (Path(onnx_path).name, ort.__version__)
    (OUT / ("fixture-%s.json" % name)).write_text(json.dumps(fx))
    print("fixture-%s.json: op argmax %d, target argmax %d; teacher's fixture logits differ by up to %.3f" % (
        name, logits[0][:12].argmax(), logits[1][:int(feed["marker_mask"][1].sum())].argmax(),
        np.abs(logits - old).max()))


def assistant(model, name, onnx_path=None, respell=False):
    """Friday's other questions on the same file (scripts/eval_assistant.py's 50 cases, research/09): skill routing over
    the 16 skills and the slot pick, with coverage and accuracy at the app's confidence floor (Skills.kt CONFIDENT).
    The app-choice part needs the owner's app list, which stays on the Mac."""
    import laya
    import eval_assistant as A
    if respell:  # the English-only cut, re-spelt as the -en file would see it
        from transformers import AutoTokenizer
        tok = AutoTokenizer.from_pretrained(str(teacher_dir() / "tokenizer"))
        patch_tokenizer(make_respell(tok, set(np.load(OUT / "vocab_train_ids.npy").tolist())))
    agent = (laya.load(str(resolve(model)), backend="onnx", onnx_path=str(onnx_path)) if onnx_path else
             laya.load(str(resolve(model)), device="cpu"))
    rows = []
    for req, skill, _, slot, split in A.CASES:
        a = agent.system_one({"goal": req}, {"skill": {"type": "choice", "criteria": A.SKILLS,
                                                       "instructions": ROUTE_Q}}, **A.KW)["answers"]["skill"]
        r = {"split": split, "skill": (a["choice"] == skill, A.conf_of(a))}
        for key, trim_ in (("slot", False), ("slot_trim", True)):
            if slot:
                sp = A.spans(req, trim_)
                b = laya.predict_tournament(agent, {"goal": req}, {"slot": {
                    "type": "choice", "criteria": {str(i): x for i, x in enumerate(sp)}, "instructions": SLOT_Q}},
                    **A.KW)["answers"]["slot"]
                got, want = sp[int(b["choice"])].lower(), slot.lower()
                r[key] = (got == want, got in want or want in got, A.conf_of(b))
        rows.append(r)

    def summary(pairs):  # pairs: (right, confidence)
        sure = [ok for ok, c in pairs if c >= 0.7]
        return {"n": len(pairs), "accuracy": round(sum(ok for ok, _ in pairs) / len(pairs), 3),
                "coverage_at_0.7": round(len(sure) / len(pairs), 3),
                "accuracy_at_0.7": round(sum(sure) / len(sure), 3) if sure else None}
    rep = {"model": str(model), "onnx": str(onnx_path) if onnx_path else None, "respell": respell,
           "skill": {sp: summary([r["skill"] for r in rows if sp in ("all", r["split"])]) for sp in ("all", "seen", "para")}}
    for key in ("slot", "slot_trim"):
        have = [r[key] for r in rows if key in r]
        rep[key] = {"exact": summary([(e, c) for e, _, c in have]),
                    "overlap": round(sum(o for _, o, _ in have) / len(have), 3)}
    (OUT / ("%s.assistant.json" % name)).write_text(json.dumps(rep, indent=1))
    print(json.dumps(rep))


# ---------------------------------------------------------------------------------------------------- smoke
def smoke(n=40):
    """A few synthetic steps in laya-android's item format, to dry-run every stage before the real data lands."""
    rng = random.Random(0)
    words = ["Search", "Settings", "Clock", "Timer", "Alarm", "Add", "Back", "More options", "Wi-Fi", "Bluetooth",
             "Display", "Battery", "Storage", "Sound", "Apps", "Notifications", "Home", "Profile", "Cart", "Menu"]
    classes = ["TextView", "ImageView", "Button", "EditText", "LinearLayout", "FrameLayout"]
    ops = list(S.OP_DESC)
    out = HERE / "smoke"
    out.mkdir(exist_ok=True)
    for split in ("train", "val", "test"):
        with open(out / ("ac_%s.jsonl" % split), "w") as f:
            for i in range(n):
                k = rng.randint(3, 40)
                cands = [{"label": " ".join(rng.sample(words, rng.randint(1, 3))), "class": rng.choice(classes),
                          "editable": False, "long_clickable": False, "clickable": True, "scrollable": False,
                          "checked": False, "selected": False, "enabled": True,
                          "bounds": [0, 60 * j, 1080, 60 * j + 50]} for j in range(k)]
                op = rng.choice(["CLICK"] * 6 + ops)
                gold = rng.randrange(k) if op in ("CLICK", "LONG_PRESS") else None
                f.write(json.dumps({"episode_id": i // 5, "step": i % 5, "goal": "Open %s" % rng.choice(words),
                                    "history": [], "candidates": cands, "operation_gold": op, "target_gold": gold,
                                    "target_groundable": gold is not None if op in ("CLICK", "LONG_PRESS") else None,
                                    "candidate_count": k, "subsplits": ["IDD"]}) + "\n")
    print("wrote", out)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("stage", choices=["make", "cache", "cache-assist", "train", "eval", "export", "parity", "vocab",
                                      "vocab-eval", "trim", "fixture", "assistant", "onnx-eval", "smoke"])
    ap.add_argument("--layers", default="0,2,4,6,8,10,12,14,16,18,20")
    ap.add_argument("--init")
    ap.add_argument("--out")
    ap.add_argument("--model", default="teacher")
    ap.add_argument("--name", default="teacher")
    ap.add_argument("--alpha", type=float, default=0.5)
    ap.add_argument("--tau", type=float, default=2.0)
    ap.add_argument("--epochs", type=int, default=3)
    ap.add_argument("--limit", type=int)
    ap.add_argument("--split", default="test")
    ap.add_argument("--parity", type=int, default=1000)
    ap.add_argument("--onnx")
    ap.add_argument("--vmap")
    ap.add_argument("--train-file", default="train")
    ap.add_argument("--val-file", default="val")
    ap.add_argument("--replay", type=float, default=0.0)
    ap.add_argument("--encoder-lr", type=float, default=2.5e-5)
    ap.add_argument("--freeze-emb", action="store_true")
    ap.add_argument("--wait-for", help="build the items, then wait for any of these files (comma-separated)")
    ap.add_argument("--respell", action="store_true", help="assistant: tokens outside the English-only cut re-spelt")
    ap.add_argument("--assist", action="store_true", help="train: add the teacher's routing/slot answers (cache-assist)")
    ap.add_argument("--torch-name", help="onnx-eval: the PyTorch run to pair with (out/<name>.test.preds.jsonl.gz)")
    ap.add_argument("--sample", type=int, help="onnx-eval: this many random test steps (seed 0) instead of all")
    ap.add_argument("--threads", type=int, default=8)
    a = ap.parse_args()
    if a.stage == "make":
        make(a.layers, a.out)
    elif a.stage == "cache":
        cache()
    elif a.stage == "cache-assist":
        cache_assist()
    elif a.stage == "train":
        train(a.init, a.out, a.alpha, a.tau, a.epochs, a.limit, a.train_file, a.val_file, a.replay, a.encoder_lr,
              a.freeze_emb, a.wait_for, a.assist)
    elif a.stage == "eval":
        evaluate(a.model, a.name, a.split)
    elif a.stage == "export":
        export(a.model, a.name, a.parity)
    elif a.stage == "parity":
        OUT.mkdir(exist_ok=True)
        r = parity(resolve(a.model), a.onnx, a.parity)
        (OUT / ("%s.parity.json" % a.name)).write_text(json.dumps(r, indent=1))
        print(json.dumps(r, indent=1))
    elif a.stage == "vocab":
        vocab()
    elif a.stage == "vocab-eval":
        vocab_eval(a.model, a.name)
    elif a.stage == "trim":
        trim(a.model, a.onnx, a.name)
    elif a.stage == "fixture":
        fixture(a.onnx, a.name, a.vmap)
    elif a.stage == "assistant":
        assistant(a.model, a.name, a.onnx, a.respell)
    elif a.stage == "onnx-eval":
        onnx_eval(a.model, a.onnx, a.name, a.torch_name, a.sample, a.threads)
    else:
        smoke()
