"""Friday Teach recordings (filesDir/teach/<time>.jsonl, D-033) -> laya-train rows, deduped and split by task (research/14 §5).

python teach_rows.py <layaTest repo> <out dir> <teach.jsonl ...> [--profiles app_profiles_auto.json --apps apps.json]
                     [--holdout 0.2] [--test-after <epoch ms>] [--repeat 3] [--done-repeat 5] [--seed 0]

Per recording: one operation row per step (+ target for CLICK/LONG_PRESS), the final DONE row, a yes/no "done" row per
screen, and (with --profiles/--apps) one "which app" row over the owner's phone profiles. States are rebuilt with the
repo's scripts/laya_android.py, the same code the Mac runner uses, so rows match inference byte for byte.
Writes teach_train.jsonl (oversampled) and teach_test.jsonl (held-out task families + the time slice, never oversampled).
Personal data: run it only on the Mac or the GPU box; the outputs are deleted with the recordings (D-033).
"""
import argparse
import hashlib
import json
import os
import random
import re
import sys

Q_APP = "Which app should be opened to do this request?"
Q_DONE = "Is the goal already complete on this screen?"


def family(goal):
    """Task family: the goal without quoted text, digits or punctuation."""
    g = re.sub(r"[\"'“”‘’].*?[\"'“”‘’]", " ", goal.lower())
    return " ".join(re.sub(r"[^a-z ]", " ", re.sub(r"\d+", " ", g)).split())


def norm(s):
    return " ".join(re.sub(r"\d", "#", s.lower()).split())


def strip_app(goal, label):
    """Drop "open the X app (and)", "in X" style mentions of the gold app's label."""
    w = re.escape(label.split()[0]) if label else None
    if not w:
        return goal
    g = re.sub(r"(?i)\b(?:open|launch|go to|in|on|using|use|via|from|with)\s+(?:the\s+)?%s[\w.&' -]{0,25}?(?:\s+app(?:lication)?)?\b[\s,.&]*(?:and\s+(?:then\s+)?)?" % w, "", goal)
    return " ".join(g.split()).strip(" ,.")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("repo")
    ap.add_argument("out")
    ap.add_argument("teach", nargs="+")
    ap.add_argument("--profiles")
    ap.add_argument("--apps")
    ap.add_argument("--holdout", type=float, default=0.2)
    ap.add_argument("--test-after", type=int, default=None)
    ap.add_argument("--repeat", type=int, default=3)
    ap.add_argument("--done-repeat", type=int, default=5)
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args()
    sys.path.insert(0, os.path.join(a.repo, "scripts"))
    from laya_android import OP_DESC, Q_OP, Q_TARGET, build_candidates, option_label, parse_forest

    profiles = json.load(open(a.profiles)) if a.profiles else {}
    label_pkg = {x["label"]: x["package"] for x in json.load(open(a.apps))} if a.apps else {}
    rng = random.Random(a.seed)
    stats = {"recordings": 0, "steps": 0, "target_reground": 0, "target_dropped": 0, "dupes": 0, "app_rows": 0}
    train, test, seen = [], [], {}

    for path in a.teach:
        recs = [json.loads(l) for l in open(path, encoding="utf8") if l.strip()]
        if not recs:
            continue
        stats["recordings"] += 1
        goal, fam = recs[0]["goal"], family(recs[0]["goal"])
        held = (int(hashlib.sha1(fam.encode()).hexdigest(), 16) % 1000) < a.holdout * 1000 or (
            a.test_after is not None and recs[0]["time"] >= a.test_after)
        out = []
        for r in recs:
            st = r["state"]
            cands = build_candidates(parse_forest(st), tuple(st["screen"]))
            labels = [option_label(c) for c in cands]
            state = {"goal": r["goal"], "history": r["history"][-3:],
                     "ui": ["[%d] %s" % (i, lab) for i, lab in enumerate(labels)]}
            q = {"operation": {"type": "choice", "instructions": Q_OP, "criteria": dict(OP_DESC)}}
            exp = {"operation": r["op"]}
            if r["op"] in ("CLICK", "LONG_PRESS") and r.get("target") is not None:
                t, box = r["target"], tuple(r.get("target_bounds") or ())
                if not (t < len(cands) and cands[t]["label"] == r.get("target_label") and tuple(cands[t]["bounds"]) == box):
                    hit = [i for i, c in enumerate(cands) if tuple(c["bounds"]) == box]  # re-ground like Teach.match()
                    t = hit[0] if hit else None
                    stats["target_reground" if t is not None else "target_dropped"] += 1
                if t is not None:
                    q["target"] = {"type": "choice", "instructions": Q_TARGET,
                                   "criteria": {str(i): lab for i, lab in enumerate(labels)}}
                    exp["target"] = str(t)
            key = (fam, tuple(norm(x) for x in state["ui"]), tuple(state["history"]), r["op"], exp.get("target"))
            if key in seen:  # the same screen and answer for the same task: keep one
                stats["dupes"] += 1
                continue
            seen[key] = True
            stats["steps"] += 1
            out.append(({"state": state, "questions": q, "expected": exp}, r["op"] == "DONE"))
            out.append(({"state": state, "questions": {"done": {"type": "noul", "instructions": Q_DONE}},
                         "expected": {"done": r["op"] == "DONE"}}, False))
        first = recs[0]
        app_label = first.get("payload") if first["op"] == "OPEN_APP" else None
        pkg = label_pkg.get(app_label) if app_label else first.get("package")
        if profiles and pkg in profiles and len(profiles) >= 16:
            others = rng.sample([p for p in profiles if p != pkg], 15)
            opts = [pkg] + others
            rng.shuffle(opts)
            # a named minority in train keeps "open X" working; test is always de-named
            req = strip_app(goal, app_label or "") if (held or rng.random() < 0.85) else goal
            out.append(({"state": {"goal": req}, "questions": {"app": {"type": "choice", "instructions": Q_APP,
                         "criteria": {str(i): profiles[p] for i, p in enumerate(opts)}}},
                         "expected": {"app": str(opts.index(pkg))}}, False))
            stats["app_rows"] += 1
        for row, is_done in out:
            if held:
                test.append(row)
            else:
                train += [row] * (a.done_repeat if is_done else a.repeat)
    rng.shuffle(train)
    os.makedirs(a.out, exist_ok=True)
    for name, rows in (("teach_train", train), ("teach_test", test)):
        with open(os.path.join(a.out, name + ".jsonl"), "w", encoding="utf8") as f:
            f.writelines(json.dumps(r, ensure_ascii=False) + "\n" for r in rows)
    print(json.dumps(dict(stats, train_rows=len(train), test_rows=len(test))))


if __name__ == "__main__":
    main()
