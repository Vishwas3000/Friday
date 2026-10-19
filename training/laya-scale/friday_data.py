"""research/27 §7 step 1: AndroidControl in Friday's format, every split, as fine-tune rows (S11, research/28).

    CUDA_VISIBLE_DEVICES= python friday_data.py [--workers 20]   # stream, render, merge -> data/friday_*.jsonl
    CUDA_VISIBLE_DEVICES= python friday_data.py --check          # friday_test vs L2's "all" variant + the cut

- Streams each shard again (nothing raw on disk) and renders every step with Friday's builder, all four changes on:
  system windows out, idLabel, loose text, icon names at 0.90. The icon classifier runs on the CPU as the shard
  streams; only names are kept.
- Golds: the repo's grounding rule on Friday's list, kept only when it lands on the trained gold's own element (the
  same box). A tap with no counterpart (mostly a system window) drops the step; history then says "(unlabeled)", as
  the repo writes an ungroundable tap.
- On screens with more than 32 elements the target row is cut to shortlist(k = 32), as the phone does (D-060): the
  step becomes an operation line on every element plus a target line on the shortlist, or no target line when the
  shortlist misses the gold. Its words: aboutWords(goal, the episode's OPEN_APP name), as research/27 L3.
"""
import argparse
import collections
import json
import os
import sys
import time
from multiprocessing import Pool

import ac_data as D
from ac_data import AC, B, S

sys.path.insert(0, D.ROOT)
import candidates as C  # noqa: E402  (the repo's grounding rule)
import laya_android as F  # noqa: E402  (a copy of the Mac repo's scripts/laya_android.py)

K = 32  # the phone's target shortlist (D-060)
CLICKS = ("CLICK", "LONG_PRESS")
SHARDS = D.ROOT + "/ac/processed/friday"
NAMES = {"train": "train", "validation": "val", "test": "test"}


def app_name(items):
    return next((it["payload"] for it in items if it["operation_gold"] == "OPEN_APP" and it["payload"]), None)


def lines(fi, app, n):
    """The training lines of one Friday step: its full row, or an operation line plus a shortlist target line."""
    r = S.row(fi)
    if fi["target_gold"] is None or len(fi["candidates"]) <= K:
        return [{**fi, "row": "full", "shortlist": None, **r}]
    sl = F.shortlist(fi["candidates"], F.about_words(fi["goal"], app), K)
    cut = {"k": K, "shown": sl}
    out = [{**fi, "row": "operation", "shortlist": cut, "state": r["state"],
            "questions": {"operation": r["questions"]["operation"]}, "expected": {"operation": r["expected"]["operation"]}}]
    n["cut"] += 1
    if fi["target_gold"] not in sl:
        n["shortlist_missed"] += 1
        return out
    short = {**fi, "candidates": [fi["candidates"][i] for i in sl], "target_gold": sl.index(fi["target_gold"]),
             "candidate_count": len(sl)}
    rs = S.row(short)
    out.append({**short, "row": "target", "shortlist": cut, "state": rs["state"],
                "questions": {"target": rs["questions"]["target"]}, "expected": {"target": rs["expected"]["target"]}})
    return out


def no_counterpart(it, cands, g):
    """The trained gold has no element of its own in Friday's list: the tap lands on nothing, or on another box."""
    t = it["target_gold"]
    return t is not None and (g is None or cands[g]["bounds"] != it["candidates"][t]["bounds"])


def episode_lines(ep, items, shots, words, n):
    app, side, hist, out = app_name(items), min(ep["screen"]) // 4, [], []
    for k, it in enumerate(items):
        cands = F.build_candidates(D.friday_nodes(AC.parse_forest(ep["trees"][k])), ep["screen"])
        boxes = [c["bounds"] for c in cands if D.is_icon(c, side)]
        if boxes:  # IconNamer: the still-unnamed icons, named when the classifier is sure and it isn't "other"
            named = dict(zip(boxes, D.icon_classes(shots[k], boxes)[0]))
            for c in cands:
                nc = named.get(c["bounds"]) if D.is_icon(c, side) else None
                if nc and nc[1] >= D.ICON_THRESHOLD and words[nc[0]]:
                    c["label"] = words[nc[0]]
                    n["icons_named"] += 1
        for i, c in enumerate(cands):
            c["id"], c["bounds"] = i, list(c["bounds"])
        op, gold, drop = it["operation_gold"], None, False
        if op in CLICKS:
            g = C.ground(cands, *it["click"])
            drop = no_counterpart(it, cands, g)
            gold = None if drop else g
            n["gained"] += it["target_gold"] is None and gold is not None
        fi = {**it, "history": hist[-B.HISTORY:], "candidates": cands, "target_gold": gold,
              "target_groundable": gold is not None if op in CLICKS else None, "candidate_count": len(cands)}
        pay = it["payload"]
        hist.append(B.history_str(op, {} if pay is None else {"text": pay, "app_name": pay}, cands, gold))
        n["steps"] += 1
        if drop:
            n["dropped"] += 1
            continue
        out += lines(fi, app, n)
    return out


def work(shard):
    path = os.path.join(SHARDS, "%02d.jsonl" % shard)
    if os.path.exists(path):
        return shard, None
    split_of, _ = AC.load_splits()
    words = D.icon_words()
    for attempt in range(3):
        n = collections.defaultdict(collections.Counter)
        try:
            with open(path + ".tmp", "w", encoding="utf8") as f:
                for rec in D.records(D.URL % shard):
                    e = AC._example(rec, skip=())
                    ep = D.episode(e)
                    split = split_of.get(ep["episode_id"])
                    if split is None or not ep["actions"]:  # as the repo: no demonstration, no rows
                        continue
                    for line in episode_lines(ep, B.episode_items(ep, split), e["screenshots"], words, n[split]):
                        f.write(json.dumps(line, ensure_ascii=False) + "\n")
            os.replace(path + ".tmp", path)
            return shard, n
        except Exception as ex:  # a dropped stream: start the shard again
            print("shard %02d attempt %d failed: %r" % (shard, attempt + 1, ex), flush=True)
            time.sleep(10)
    raise RuntimeError("shard %02d failed 3 times" % shard)


def merge():
    """data/friday_{train,val,test}.jsonl from the shards, episode by episode; test lines get their subsplits."""
    _, sub = AC.load_splits()
    outs = {s: open(os.path.join(D.DATA, "friday_%s.jsonl.tmp" % s), "w", encoding="utf8") for s in NAMES.values()}
    n = collections.Counter()
    for s in range(20):
        for line in open(os.path.join(SHARDS, "%02d.jsonl" % s), encoding="utf8"):
            x = json.loads(line)
            name = NAMES[x["split"]]
            if name == "test":
                x["subsplits"] = sorted(k for k, ids in sub.items() if x["episode_id"] in ids)
                line = json.dumps(x, ensure_ascii=False) + "\n"
            outs[name].write(line)
            n[name] += 1
    for name, f in outs.items():
        f.close()
        os.replace(f.name, f.name[:-len(".tmp")])
    return n


def check():
    """friday_test against research/27 L2's "all" variant (stored forests, stored icon classes, no streaming) with
    the same drop rule and the k = 32 cut: every line must match; differences are listed."""
    import ac_eval as A
    items, extra = A.load_test()
    v2 = A.variant_items(items, extra, A.VARIANTS["all"], D.icon_words())
    got = collections.defaultdict(list)
    for line in open(os.path.join(D.DATA, "friday_test.jsonl"), encoding="utf8"):
        x = json.loads(line)
        got[(x["episode_id"], x["step"])].append(x)
    keys = ("row", "shortlist", "state", "questions", "expected")
    n, same, diff, apps = collections.Counter(), 0, [], {}
    for it, v in zip(items, v2):
        key = (it["episode_id"], it["step"])
        if it["episode_id"] not in apps:
            apps[it["episode_id"]] = app_name([x for x in items if x["episode_id"] == it["episode_id"]])
        want = [] if v["operation_gold"] in CLICKS and no_counterpart(it, v["candidates"], v["target_gold"]) else \
            lines(v, apps[it["episode_id"]], n)
        have = got.pop(key, [])
        if [{k: x[k] for k in keys} for x in want] == [{k: x[k] for k in keys} for x in have]:
            same += 1
        else:
            diff.append(key)
    print("friday_test vs L2 all + cut: %d of %d steps identical; %d differ %s; %d unexpected lines" % (
        same, len(items), len(diff), diff[:20], sum(map(len, got.values()))), flush=True)
    return same, diff


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--workers", type=int, default=20)
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    if args.check:
        check()
        sys.exit()
    os.makedirs(SHARDS, exist_ok=True)
    total = collections.defaultdict(collections.Counter)
    t0 = time.time()
    with Pool(args.workers) as pool:
        for s, n in pool.imap_unordered(work, range(20)):
            for split, c in (n or {}).items():
                total[split].update(c)
            print("shard %02d %s, %.0f s" % (s, "built" if n else "kept", time.time() - t0), flush=True)
    print("counts:", {k: dict(v) for k, v in total.items()}, flush=True)
    print("lines:", dict(merge()), flush=True)
