"""L1-L3 (research/27): Laya-Android on AndroidControl's test steps, in the trained format and in Friday's.

    python ac_eval.py l1      # the repo's own test predictions vs the ones it published (run its eval scripts first)
    python ac_eval.py l2      # Friday's input changes, one at a time and all     -> results/l2.json
    python ac_eval.py l3      # the target shortlist                              -> results/l3.json
    python ac_eval.py l3op    # the operation row cut to the shortlist (after l3) -> results/l3op.json

Needs ac_data.py's output, and the Mac repo's scripts/laya_android.py copied next to this file. Inference and per-step
predictions go through the repo's own `evaluate_split` (its batching, bf16, temperature); scoring follows its
definitions (scripts/eval_laya_android.py, docs/eval_protocol.md). Predictions are cached in
ac/preds/<name>.preds.jsonl.gz, so a rerun only scores.
"""
import collections
import gzip
import json
import math
import os
import sys

import numpy as np

ROOT = "/root/friday-laya/laya"
os.environ.setdefault("LAYA_ANDROID_DATA", ROOT + "/ac")
MODEL = "/root/friday-laya/hf/hub/models--ByunByun--laya-android/snapshots/5a03b2bc4220ec786a00a87eacdaa75cb844bd6b"
REPO = ROOT + "/laya-android"
sys.path[:0] = [ROOT, REPO + "/src", REPO + "/scripts", REPO + "/third_party/infigui_r1"]
import build_androidcontrol_dataset as B  # noqa: E402
import candidates as C  # noqa: E402
import eval_laya_android as E  # noqa: E402
import laya_android as F  # noqa: E402  (Friday's builder: a copy of the Mac repo's scripts/laya_android.py)
import serialization as S  # noqa: E402
from evaluate_android_control import evaluate_android_control_action  # noqa: E402  (InfiGUI-R1 judge, unmodified)
from evaluate_androidcontrol import to_reference  # noqa: E402
from ac_data import is_icon  # noqa: E402  (IconNamer.isIcon)

OUT = ROOT + "/results"
PREDS = ROOT + "/ac/preds"
CLICKS = ("CLICK", "LONG_PRESS")
BOOLS = ("clickable", "long_clickable", "editable", "scrollable", "checkable", "checked", "selected", "enabled", "visible")
VARIANTS = {  # Friday's builder switches (scripts/laya_android.py build_candidates) + icon names
    "F0": dict(system=False, ids=False, loose_text=False, icons=False),
    "a_system": dict(system=True, ids=False, loose_text=False, icons=False),
    "b_idlabel": dict(system=False, ids=True, loose_text=False, icons=False),
    "c_loose": dict(system=False, ids=False, loose_text=True, icons=False),
    "d_icons": dict(system=False, ids=False, loose_text=False, icons=True),
    "all": dict(system=True, ids=True, loose_text=True, icons=True),
    # leave one out of "all": each change's effect as shipped, alongside the others (alone, loose text also picks up
    # the status bar's notification icons, which (a) drops on the phone)
    "all-a": dict(system=False, ids=True, loose_text=True, icons=True),
    "all-b": dict(system=True, ids=False, loose_text=True, icons=True),
    "all-c": dict(system=True, ids=True, loose_text=False, icons=True),
    "all-d": dict(system=True, ids=True, loose_text=True, icons=False),
}
ICON_THRESHOLD = 0.90  # models/icons/labels.json, as shipped
_model = None


def bucket(n):
    return next((b for b, lo, hi in E.BUCKETS if lo <= n <= hi), "0")  # "0": a screen with no candidates


def load_test():
    """The repo's test items (union of the four subsplits, each step once) and each step's forest + icon classes."""
    items = [json.loads(line) for line in open(ROOT + "/data/ac_test.jsonl", encoding="utf8")]
    for it in items:
        for k in ("state", "questions", "expected"):
            it.pop(k)
    extra = {}
    for s in range(20):
        for line in open(ROOT + "/ac/processed/test_extra/%02d.jsonl" % s, encoding="utf8"):
            x = json.loads(line)
            extra[(x["episode_id"], x["step"])] = x
    assert len(extra) == len(items), (len(extra), len(items))
    return items, extra


def friday_nodes(nodes):
    """The repo's flat forest nodes -> scripts/laya_android.py's node dicts: same order, children linked, IME and
    TYPE_SYSTEM windows tagged."""
    out = [{"text": n.get("text", ""), "content_desc": n.get("content_desc", ""), "hint": n.get("hint", ""),
            **{f: n.get(f, False) for f in BOOLS}, "class": (n.get("class") or "").rsplit(".", 1)[-1],
            "resource_id": (n.get("resource_id") or "").rsplit("/", 1)[-1],
            "bounds": tuple(n.get("bounds", (0, 0, 0, 0))), "depth": n["depth"], "ime": n["window_type"] == 2,
            "sys": n["window_type"] == 3, "children": []} for n in nodes]
    by = {(n["window"], n.get("id")): f for n, f in zip(nodes, out)}
    for n, f in zip(nodes, out):
        f["children"] = [by[(n["window"], c)] for c in n["children"] if (n["window"], c) in by]
    return out


def icon_words():
    lab = json.load(open(ROOT + "/icons/labels.json"))
    assert lab["threshold"] == ICON_THRESHOLD
    return [None if c == "other" else w for c, w in zip(lab["classes"], lab["words"])]


def variant_items(items, extra, v, words):
    """Each test step rebuilt in one Friday variant: its candidates, its gold (the repo's grounding rule on the new
    list), and a history rebuilt from the new labels. Buckets stay the trained format's (l2 takes them from items)."""
    out, hist, ep = [], [], None
    for it in items:
        if it["episode_id"] != ep:
            ep, hist = it["episode_id"], []
        x = extra[(it["episode_id"], it["step"])]
        cands = F.build_candidates(friday_nodes(x["nodes"]), tuple(x["screen"]), v["system"], v["ids"], v["loose_text"])
        named = 0
        if v["icons"]:
            names = {tuple(i[:4]): words[i[4]] for i in x["icons"] if i[5] >= ICON_THRESHOLD and words[i[4]]}
            side = min(x["screen"]) // 4
            for c in cands:
                if is_icon(c, side) and names.get(tuple(c["bounds"])):
                    c["label"] = names[tuple(c["bounds"])]
                    named += 1
        for i, c in enumerate(cands):
            c["id"], c["bounds"] = i, list(c["bounds"])
        op = it["operation_gold"]
        gold = C.ground(cands, *it["click"]) if op in CLICKS else None
        out.append({**it, "history": hist[-B.HISTORY:], "candidates": cands, "target_gold": gold,
                    "target_groundable": gold is not None if op in CLICKS else None, "icons_named": named,
                    "candidate_count": len(cands)})
        pay = it["payload"]
        hist.append(B.history_str(op, {} if pay is None else {"text": pay, "app_name": pay}, cands, gold))
    return out


def predict(name, items, base=None):
    """{(episode, step): saved prediction} from the repo's evaluate_split, run once per name and cached.
    With base = (items, predictions) of the same steps, a step whose state and questions are unchanged reuses base's
    prediction: only the steps whose input moved are run, and a paired comparison moves only there."""
    global _model
    path = os.path.join(PREDS, name + ".preds.jsonl.gz")
    if not os.path.exists(path):
        os.makedirs(PREDS, exist_ok=True)
        kept = {}
        if base:
            rows = {(b["episode_id"], b["step"]): S.row(b) for b in base[0]}
            for it in items:
                key, r = (it["episode_id"], it["step"]), S.row(it)
                if r["state"] == rows[key]["state"] and r["questions"] == rows[key]["questions"]:
                    kept[key] = base[1][key]
        todo = [it for it in items if (it["episode_id"], it["step"]) not in kept]
        new = {}
        if todo:
            src = os.path.join(PREDS, name + ".items.jsonl")
            with open(src, "w", encoding="utf8") as f:
                for it in todo:
                    f.write(json.dumps(it, ensure_ascii=False) + "\n")
            if _model is None:
                _model = E.load(MODEL)
            m = E.evaluate_split(*_model, src, latency_n=0, preds_path=path + ".run")
            os.remove(src)
            with gzip.open(path + ".run", "rt", encoding="utf8") as f:
                new = {(p["episode_id"], p["step"]): p for p in map(json.loads, f)}
            os.remove(path + ".run")
            print(name, "ran %d of %d steps" % (len(todo), len(items)),
                  json.dumps({k: v for k, v in m.items() if not isinstance(v, dict)}), flush=True)
        with gzip.open(path + ".tmp", "wt", encoding="utf8") as f:
            for it in items:
                key = (it["episode_id"], it["step"])
                f.write(json.dumps({**kept.get(key, {}), **new.get(key, {})}) + "\n")
        os.replace(path + ".tmp", path)
    with gzip.open(path, "rt", encoding="utf8") as f:
        return {(p["episode_id"], p["step"]): p for p in map(json.loads, f)}


def reference():
    ref = {}
    for line in json.load(open(ROOT + "/ac/raw/infigui/android_control_test.json", encoding="utf8")):
        for k, pam in enumerate(line["step_check_pams"]):
            ref[(line["episode"]["episode_id"], k)] = (pam, line["width"], line["height"])
    return ref


def outcomes(items, preds, ref):
    """Per step: op right, target right (None unless a groundable click), and the AC-High judge's (type, ground hit)."""
    out = {}
    for it in items:
        key = (it["episode_id"], it["step"])
        p = preds[key]
        op = max(p["operation_probs"], key=p["operation_probs"].get)
        t = int(np.argmax(p["target_probs"])) if p["target_probs"] else None
        click = it["operation_gold"] in CLICKS
        tgt = (t == it["target_gold"]) if click and it["target_groundable"] else None
        hi = None
        if key in ref:  # not the synthetic DONE
            pam, w, h = ref[key]
            box = it["candidates"][t]["bounds"] if op in CLICKS and t is not None else None
            pred = to_reference(op, box, w, h)
            type_ok, exact = evaluate_android_control_action(pred, pam, w, h, w, h)
            hi = (bool(type_ok), bool(exact) and pred["action"] == "click" if pam["action"] == "click" else None)
        out[key] = {"op": op == it["operation_gold"], "tgt": tgt, "click": click, "hi": hi}
    return out


def pct(a, b):
    return round(100 * a / b, 2) if b else None


def metrics(oc, buckets):
    """The repo's op / target / joint, by bucket, and AC-High Type / Grounding."""
    tg = [k for k, o in oc.items() if o["tgt"] is not None]
    joint = [o["op"] and (o["tgt"] if o["click"] else True) for o in oc.values() if not (o["click"] and o["tgt"] is None)]
    hi = [o["hi"] for o in oc.values() if o["hi"] is not None]
    by = collections.defaultdict(lambda: [0, 0, 0, 0])  # op right, steps, target right, groundable
    for k, o in oc.items():
        b = by[buckets[k]]
        b[0] += o["op"]
        b[1] += 1
        if o["tgt"] is not None:
            b[2] += o["tgt"]
            b[3] += 1
    clicks = sum(o["click"] for o in oc.values())
    return {"steps": len(oc), "op": pct(sum(o["op"] for o in oc.values()), len(oc)),
            "target": pct(sum(oc[k]["tgt"] for k in tg), len(tg)), "groundable": len(tg),
            "coverage": pct(len(tg), clicks), "joint": pct(sum(joint), len(joint)),
            "type": pct(sum(h[0] for h in hi), len(hi)),
            "grounding": pct(sum(bool(h[1]) for h in hi), sum(h[1] is not None for h in hi)),
            "by_bucket": {b: {"op": pct(v[0], v[1]), "steps": v[1], "target": pct(v[2], v[3]), "clicks": v[3]}
                          for b, v in sorted(by.items(), key=lambda kv: ["0", *(x for x, *_ in E.BUCKETS)].index(kv[0]))}}


def sign_test(worse, better):
    """Two-sided exact sign test on the discordant steps."""
    n, m = worse + better, min(worse, better)
    return min(1.0, 2 * sum(math.comb(n, i) for i in range(m + 1)) / 2 ** n) if n else 1.0


def paired(base, oc, field, sub=None):
    keys = [k for k in base if base[k][field] is not None and oc[k][field] is not None and (sub is None or sub(k))]
    if field == "hi":
        keys = [k for k in keys if base[k]["hi"][1] is not None]
        get = lambda o: bool(o["hi"][1])  # noqa: E731
    else:
        get = lambda o: o[field]  # noqa: E731
    worse = sum(get(base[k]) and not get(oc[k]) for k in keys)
    better = sum(not get(base[k]) and get(oc[k]) for k in keys)
    return {"n": len(keys), "base": pct(sum(get(base[k]) for k in keys), len(keys)),
            "variant": pct(sum(get(oc[k]) for k in keys), len(keys)), "worse": worse, "better": better,
            "p": round(sign_test(worse, better), 4)}


def ui(it):
    return [S.option_label(c) for c in it["candidates"]]


def fired(f0, v):
    """How often a variant's list differs from F0's, and what happened to the gold target."""
    steps = added = removed = relabeled = gold_changed = gold_lost = gold_gained = 0
    for a, b in zip(f0, v):
        ua, ub = ui(a), ui(b)
        if ua == ub:
            continue
        steps += 1
        added += max(0, len(ub) - len(ua))
        removed += max(0, len(ua) - len(ub))
        if len(ua) == len(ub):
            relabeled += sum(x != y for x, y in zip(ua, ub))
        if a["operation_gold"] in CLICKS:
            ga, gb = a["target_gold"], b["target_gold"]
            gold_lost += ga is not None and gb is None
            gold_gained += ga is None and gb is not None
            gold_changed += ga is not None and gb is not None and ua[ga] != ub[gb]
    return {"steps": steps, "steps_pct": pct(steps, len(f0)), "cands_added": added, "cands_removed": removed,
            "cands_relabeled": relabeled, "gold_option_changed": gold_changed, "gold_lost": gold_lost,
            "gold_gained": gold_gained, "icons_named": sum(it.get("icons_named", 0) for it in v)}


def l1():
    """The repo's fresh test predictions (results/test/raw) vs the published ones (git HEAD), step by step."""
    import subprocess
    rep = {}
    for s in ("test_idd", "test_app_unseen", "test_task_unseen", "test_category_unseen"):
        rel = "results/test/raw/laya_android/%s.preds.jsonl.gz" % s
        pub = gzip.decompress(subprocess.check_output(["git", "-C", REPO, "show", "HEAD:" + rel])).decode()
        pub = {(p["episode_id"], p["step"]): p for p in map(json.loads, pub.splitlines())}
        with gzip.open(os.path.join(REPO, rel), "rt") as f:
            new = {(p["episode_id"], p["step"]): p for p in map(json.loads, f)}
        assert pub.keys() == new.keys(), s
        op_same = tgt_same = tgt_n = 0
        dp = 0.0
        for k, p in pub.items():
            q = new[k]
            op_same += max(p["operation_probs"], key=p["operation_probs"].get) == max(q["operation_probs"], key=q["operation_probs"].get)
            dp = max(dp, max(abs(p["operation_probs"][o] - q["operation_probs"][o]) for o in p["operation_probs"]))
            if p["target_probs"]:
                tgt_n += 1
                tgt_same += int(np.argmax(p["target_probs"])) == int(np.argmax(q["target_probs"]))
                dp = max(dp, float(np.max(np.abs(np.array(p["target_probs"]) - np.array(q["target_probs"])))))
        rep[s] = {"steps": len(pub), "op_same": op_same, "target_rows": tgt_n, "target_same": tgt_same, "max_abs_dp": round(dp, 4)}
    print(json.dumps(rep, indent=1))
    return rep


def l2():
    items, extra = load_test()
    ref, words = reference(), icon_words()
    buckets = {(it["episode_id"], it["step"]): bucket(it["candidate_count"]) for it in items}
    rows = {name: variant_items(items, extra, v, words) for name, v in VARIANTS.items()}
    # F0 = Friday's builder with every change off: must be the trained format, history included
    diff = [(a["episode_id"], a["step"]) for a, b in zip(items, rows["F0"])
            if ui(a) != ui(b) or a["history"] != b["history"] or a["target_gold"] != b["target_gold"]]
    print("F0 vs the repo's builder: %d of %d steps differ" % (len(diff), len(items)), diff[:10], flush=True)
    p1 = predict("L1", items)
    oc = {"L1": outcomes(items, p1, ref)}
    for name in VARIANTS:
        oc[name] = outcomes(rows[name], predict(name, rows[name], base=(items, p1)), ref)
    rep = {"F0_differs": len(diff), "F0_diff_steps": diff[:50], "variants": {}}
    for name in ["L1", *VARIANTS]:
        r = {"metrics": metrics(oc[name], buckets)}
        if name != "L1":
            r["fired"] = fired(rows["F0"], rows[name])
            r["vs_L1"] = {f: paired(oc["L1"], oc[name], f) for f in ("op", "tgt", "hi")}
            r["vs_L1_target_where_fired"] = paired(
                oc["L1"], oc[name], "tgt", sub=lambda k, n=name: k in changed(rows["F0"], rows[n]))
            r["vs_L1_target_by_bucket"] = {b: paired(oc["L1"], oc[name], "tgt", sub=lambda k, b=b: buckets[k] == b)
                                           for b, *_ in E.BUCKETS}
        if name.startswith("all-"):  # adding the left-out change back, on top of the others = "all"
            r["adding_it_back"] = {"fired": fired(rows[name], rows["all"]),
                                   **{f: paired(oc[name], oc["all"], f) for f in ("op", "tgt", "hi")},
                                   "tgt_where_fired": paired(oc[name], oc["all"], "tgt",
                                                             sub=lambda k, n=name: k in changed(rows[n], rows["all"]))}
        rep["variants"][name] = r
        m = r["metrics"]
        print("%-10s op %.2f target %.2f (n %d) joint %.2f type %.2f grounding %.2f" % (
            name, m["op"], m["target"], m["groundable"], m["joint"], m["type"], m["grounding"]), flush=True)
    os.makedirs(OUT, exist_ok=True)
    json.dump(rep, open(OUT + "/l2.json", "w"), indent=1)
    return rep


_changed = {}


def changed(f0, v):
    """Keys of the steps whose rendered list differs from F0's."""
    key = (id(f0), id(v))
    if key not in _changed:
        _changed[key] = {(a["episode_id"], a["step"]) for a, b in zip(f0, v) if ui(a) != ui(b)}
    return _changed[key]


KS = (8, 12, 16, 24, 32)


def tappable(c):
    return c["clickable"] or c["long_clickable"]


def ranked(cands, want, k, fuzzy=False, tap_first=False):
    """shortlist() with two knobs: goal words also match by a shared 4+ letter prefix, and the top/bottom fill takes
    tappable elements first (the target question is only asked for a tap). Both off == F.shortlist."""
    if k <= 0 or len(cands) <= k:
        return list(range(len(cands)))

    def hits(c):
        ws = F.content_words(c["label"]) | F.content_words(c["resource_id"])
        if not fuzzy:
            return len(ws & want)
        return sum(any(w == e or (min(len(w), len(e)) >= 4 and (e.startswith(w) or w.startswith(e))) for e in ws)
                   for w in want)
    h = [hits(c) for c in cands]
    keep = dict.fromkeys(sorted((i for i in range(len(cands)) if h[i] > 0), key=lambda i: -h[i])[:k])
    for i in range(len(cands)):
        if cands[i]["editable"] and len(keep) < k:
            keep[i] = None
    n = len(cands)
    order = [i for j in range((n + 1) // 2) for i in (j, n - 1 - j)]  # 0, n-1, 1, n-2, ...
    if tap_first:
        order = [i for i in order if tappable(cands[i])] + [i for i in order if not tappable(cands[i])]
    for i in order:
        if len(keep) >= k:
            break
        keep[i] = None
    return sorted(keep)


def history_words(it):
    """Words of the elements already tapped in this episode's last 3 steps."""
    return set().union(*(F.content_words(h.split(" ", 1)[1]) for h in it["history"]
                         if h.startswith(("CLICK ", "LONG_PRESS "))))


RULES = {
    "shipped": lambda it, want, k: F.shortlist(it["candidates"], want, k),
    "fuzzy": lambda it, want, k: ranked(it["candidates"], want, k, fuzzy=True),
    "tap_first": lambda it, want, k: ranked(it["candidates"], want, k, tap_first=True),
    "minus_history": lambda it, want, k: F.shortlist(it["candidates"], want - history_words(it), k),
    "fuzzy+tap_first": lambda it, want, k: ranked(it["candidates"], want, k, fuzzy=True, tap_first=True),
}


def app_names(items):
    """Each episode's app, from its OPEN_APP action (what Friday's aboutWords removes from the goal)."""
    out = {}
    for it in items:
        if it["operation_gold"] == "OPEN_APP" and it["payload"]:
            out.setdefault(it["episode_id"], it["payload"])
    return out


def recall(clicks, apps, rule, k):
    big = [it for it in clicks if len(it["candidates"]) > k]
    hit = sum(it["target_gold"] in RULES[rule](it, F.about_words(it["goal"], apps.get(it["episode_id"])), k)
              for it in big)
    return {"recall": pct(hit, len(big)), "n": len(big)}


def train_clicks(n=5000, seed=0):
    """n groundable train clicks from whole episodes in seeded random order, and the episodes' apps."""
    import random
    eps = collections.OrderedDict()
    for line in open(ROOT + "/data/ac_train.jsonl", encoding="utf8"):
        it = json.loads(line)
        eps.setdefault(it["episode_id"], []).append(it)
    keys = list(eps)
    random.Random(seed).shuffle(keys)
    out, apps = [], {}
    for e in keys:
        apps.update(app_names(eps[e]))
        out += [it for it in eps[e] if it["operation_gold"] in CLICKS and it["target_groundable"]]
        if len(out) >= n:
            break
    return out[:n], apps


def l3():
    items, extra = load_test()
    ref, words = reference(), icon_words()
    full = variant_items(items, extra, VARIANTS["all"], words)  # Friday as shipped
    ocf = outcomes(full, predict("all", full), ref)
    apps = app_names(items)
    clicks = [it for it in full if it["operation_gold"] in CLICKS and it["target_groundable"]]
    for it in clicks:  # the rule port must equal the shipped shortlist
        want = F.about_words(it["goal"], apps.get(it["episode_id"]))
        assert all(ranked(it["candidates"], want, k) == F.shortlist(it["candidates"], want, k) for k in KS)
    tr, tr_apps = train_clicks()
    rep = {"recall_train": {r: {k: recall(tr, tr_apps, r, k) for k in KS} for r in RULES},
           "recall_test": {r: {k: recall(clicks, apps, r, k) for k in KS} for r in RULES}}
    for where in ("recall_train", "recall_test"):
        for r, v in rep[where].items():
            print(where, "%-16s" % r, " ".join("@%d %.1f (n %d)" % (k, v[k]["recall"], v[k]["n"]) for k in KS), flush=True)
    best = max((r for r in RULES if r != "shipped"), key=lambda r: rep["recall_train"][r][16]["recall"])
    tok = _model[1] if _model else __import__("transformers").AutoTokenizer.from_pretrained(MODEL + "/tokenizer")

    def tokens(it):
        return {q: len(enc["ids"]) for q, enc in E.encode(tok, it, E.MAX_LEN, E.HEAD_MAX_LEN, False) if enc}
    full_tokens = {(it["episode_id"], it["step"]): tokens(it) for it in clicks if len(it["candidates"]) > min(KS)}
    rep["laya"] = {}
    for rule, k in [("shipped", k) for k in KS] + [(best, 16)]:
        big = [it for it in clicks if len(it["candidates"]) > k]
        short, inside = [], {}
        for it in big:
            sl = RULES[rule](it, F.about_words(it["goal"], apps.get(it["episode_id"])), k)
            key, g = (it["episode_id"], it["step"]), it["target_gold"]
            inside[key] = g in sl
            short.append({**it, "candidates": [it["candidates"][i] for i in sl], "target_gold": sl.index(g) if g in sl else 0})
        ps = predict("sl_%s_%d" % (rule, k), short)
        oc = {}
        for it in short:
            key = (it["episode_id"], it["step"])
            right = inside[key] and int(np.argmax(ps[key]["target_probs"])) == it["target_gold"]
            oc[key] = {"tgt": right}
        base = {key: {"tgt": ocf[key]["tgt"]} for key in oc}
        tf = [full_tokens[key] for key in oc]
        ts = [tokens(it) for it in short]
        by = {}
        for b, *_ in E.BUCKETS:
            sub = [(it["episode_id"], it["step"]) for it in big if bucket(len(it["candidates"])) == b]
            if sub:
                by[b] = {**paired(base, oc, "tgt", sub=lambda key, s=set(sub): key in s),
                         "recall": pct(sum(inside[key] for key in sub), len(sub))}
        rep["laya"]["%s@%d" % (rule, k)] = {
            "steps": len(big), "recall": pct(sum(inside.values()), len(big)), **paired(base, oc, "tgt"),
            "target_row_tokens_full": round(float(np.mean([t["target"] for t in tf])), 1),
            "target_row_tokens_short": round(float(np.mean([t["target"] for t in ts])), 1),
            "step_tokens_saved_pct": round(100 * (1 - sum(t["operation"] + s["target"] for t, s in zip(tf, ts))
                                                   / sum(t["operation"] + t["target"] for t in tf)), 1),
            "by_bucket": by}
        r = rep["laya"]["%s@%d" % (rule, k)]
        print("%s@%d: %d steps, recall %.1f, target full %.2f -> short %.2f (worse %d, better %d, p %.3g), "
              "target row %.0f -> %.0f tokens, step tokens -%.1f%%" % (
                  rule, k, r["steps"], r["recall"], r["base"], r["variant"], r["worse"], r["better"], r["p"],
                  r["target_row_tokens_full"], r["target_row_tokens_short"], r["step_tokens_saved_pct"]), flush=True)
    rep["best_alternative"] = best
    json.dump(rep, open(OUT + "/l3.json", "w"), indent=1)
    return rep


def l3op():
    """The operation row cut to the same shortlist (LayaEngine.step's opShown) vs every element, on all test steps;
    joint with only the op row cut, and with both rows cut (the target answers of l3's runs, so run l3 first)."""
    items, extra = load_test()
    ref, words = reference(), icon_words()
    full = variant_items(items, extra, VARIANTS["all"], words)
    ocf = outcomes(full, predict("all", full), ref)
    apps = app_names(items)
    tok = __import__("transformers").AutoTokenizer.from_pretrained(MODEL + "/tokenizer")

    def op_tokens(it):
        return len(dict(E.encode(tok, it, E.MAX_LEN, E.HEAD_MAX_LEN, False))["operation"]["ids"])

    def op_of(p):
        return max(p["operation_probs"], key=p["operation_probs"].get)
    rep = {}
    for k in (16, 24, 32):
        big = [it for it in full if len(it["candidates"]) > k]
        cut, sls = [], {}
        for it in big:
            sl = F.shortlist(it["candidates"], F.about_words(it["goal"], apps.get(it["episode_id"])), k)
            sls[(it["episode_id"], it["step"])] = sl
            cut.append({**it, "candidates": [it["candidates"][i] for i in sl], "target_gold": None,
                        "target_groundable": None})
        po, pt = predict("op_shipped_%d" % k, cut), predict("sl_shipped_%d" % k, None)
        oc, flips = {}, collections.Counter()
        for it in full:
            key = (it["episode_id"], it["step"])
            o = dict(ocf[key])
            if key in sls:
                o["op"] = op_of(po[key]) == it["operation_gold"]
                if o["op"] != ocf[key]["op"]:
                    flips[("better" if o["op"] else "worse", it["operation_gold"], op_of(po[key]))] += 1
                if o["tgt"] is not None:
                    sl, g = sls[key], it["target_gold"]
                    o["tgt"] = g in sl and int(np.argmax(pt[key]["target_probs"])) == sl.index(g)
            oc[key] = o

        def joint(keys, op_from, tgt_from):
            j = [op_from[x]["op"] and (tgt_from[x]["tgt"] if ocf[x]["click"] else True)
                 for x in keys if not (ocf[x]["click"] and ocf[x]["tgt"] is None)]
            return pct(sum(j), len(j))
        by = {}
        for b, *_ in E.BUCKETS:
            sub = [(it["episode_id"], it["step"]) for it in big if bucket(len(it["candidates"])) == b]
            if sub:
                by[b] = {"op": paired(ocf, oc, "op", sub=lambda x, s=set(sub): x in s),
                         "joint_full": joint(sub, ocf, ocf), "joint_op_cut": joint(sub, oc, ocf),
                         "joint_target_cut": joint(sub, ocf, oc), "joint_both_cut": joint(sub, oc, oc)}
        tf = [op_tokens(it) for it in big]
        tc = [op_tokens(it) for it in cut]
        everything = list(ocf)
        rep[k] = {"steps": len(big), "op_on_cut_steps": paired(ocf, oc, "op", sub=lambda x: x in sls),
                  "op_all_steps": {"full": pct(sum(o["op"] for o in ocf.values()), len(ocf)),
                                   "cut": pct(sum(o["op"] for o in oc.values()), len(oc))},
                  "joint_all_steps": {"full": joint(everything, ocf, ocf), "op_cut": joint(everything, oc, ocf),
                                      "target_cut": joint(everything, ocf, oc), "both_cut": joint(everything, oc, oc)},
                  "op_row_tokens_full": round(float(np.mean(tf)), 1), "op_row_tokens_cut": round(float(np.mean(tc)), 1),
                  "flips": {"%s %s->%s" % f: n for f, n in flips.most_common(12)}, "by_bucket": by}
        r = rep[k]
        print("op row @%d: %d steps cut; op on them %.2f -> %.2f (worse %d, better %d, p %.3g); all steps op %.2f -> "
              "%.2f, joint %.2f -> %.2f (op cut) / %.2f (target cut, as shipped) / %.2f (both cut); op row %.0f -> %.0f "
              "tokens" % (
                  k, r["steps"], r["op_on_cut_steps"]["base"], r["op_on_cut_steps"]["variant"],
                  r["op_on_cut_steps"]["worse"], r["op_on_cut_steps"]["better"], r["op_on_cut_steps"]["p"],
                  r["op_all_steps"]["full"], r["op_all_steps"]["cut"], r["joint_all_steps"]["full"],
                  r["joint_all_steps"]["op_cut"], r["joint_all_steps"]["target_cut"], r["joint_all_steps"]["both_cut"],
                  r["op_row_tokens_full"],
                  r["op_row_tokens_cut"]), flush=True)
    json.dump(rep, open(OUT + "/l3op.json", "w"), indent=1)
    return rep


if __name__ == "__main__":
    {"l1": l1, "l2": l2, "l3": l3, "l3op": l3op}[sys.argv[1]]()
