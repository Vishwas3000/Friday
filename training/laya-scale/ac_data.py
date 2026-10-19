"""L0 (research/27): AndroidControl -> laya-android's own step rows, plus what the Friday-format tests need.

    python ac_data.py [--shards 0,1] [--workers 10]     # stream, build, merge; then the hand-off files + READY

- Streams each GZIP shard over HTTPS (nothing raw lands on disk) and runs the laya-android repo's builder
  (`episode_items`) on every episode, so `ac/processed/androidcontrol/*.jsonl` is what its
  scripts/build_androidcontrol_dataset.py writes.
- For test episodes it also keeps each step's accessibility forest (the repo's node list), and the shipped icon
  classifier's top class and confidence for every unnamed, icon-shaped candidate, cropped as IconNamer.crop does.
- Then writes data/ac_{train,val,test}.jsonl (the repo's step items + the laya rows its training consumed),
  checks the counts against the repo's results/data/androidcontrol_stats.json, and touches data/READY.
"""
import argparse
import io
import json
import os
import struct
import sys
import time
import urllib.request
import zlib
from multiprocessing import Pool

ROOT = "/root/friday-laya/laya"
os.environ.setdefault("LAYA_ANDROID_DATA", ROOT + "/ac")
sys.path[:0] = [ROOT + "/laya-android/src", ROOT + "/laya-android/scripts"]
import build_androidcontrol_dataset as B  # noqa: E402  (the repo's builder: candidates, grounding, history)
import serialization as S  # noqa: E402
from data import android_control as AC  # noqa: E402

URL = "https://storage.googleapis.com/gresearch/android_control/android_control-%05d-of-00020"
EXTRA = os.path.join(AC.DATA_ROOT, "processed", "test_extra")
DATA = ROOT + "/data"
_sess = None


def records(url):
    """AC.records, reading the shard from its URL instead of a file; a cut stream raises instead of ending early."""
    d, buf = zlib.decompressobj(31), b""
    with urllib.request.urlopen(url, timeout=120) as r:
        while chunk := r.read(1 << 24):
            buf += d.decompress(chunk)
            while len(buf) >= 12:
                (ln,) = struct.unpack("<Q", buf[:8])
                if len(buf) < 16 + ln:
                    break
                yield buf[12:12 + ln]
                buf = buf[16 + ln:]
    if buf or not d.eof:
        raise ValueError("%s: stream ended mid-record or mid-gzip (%d bytes left)" % (url, len(buf)))


def is_icon(c, side):
    """IconNamer.isIcon: unnamed, both sides at most a quarter of the screen's short side, roughly square."""
    x1, y1, x2, y2 = c["bounds"]
    w, h = x2 - x1, y2 - y1
    return not c["label"] and w <= side and h <= side and w <= 2 * h and h <= 2 * w


def crop(shot, box):
    """IconNamer.crop: box clamped to the shot, bilinear to 64x64, ITU-R 601 grey / 255; None when flat or empty.
    shortcut: PIL's bilinear filters like the classifier's training data, Android's createScaledBitmap aliases more
    on big downscales; names here may be slightly better than on the phone."""
    import numpy as np
    from PIL import Image
    l = min(max(box[0], 0), shot.width)
    t = min(max(box[1], 0), shot.height)
    r = min(max(box[2], l), shot.width)
    b = min(max(box[3], t), shot.height)
    if r == l or b == t:
        return None
    px = np.asarray(shot.crop((l, t, r, b)).resize((64, 64), Image.BILINEAR), dtype=np.float32)
    g = (0.299 * px[..., 0] + 0.587 * px[..., 1] + 0.114 * px[..., 2]) / 255
    return g if g.max() - g.min() > 0.02 else None


def icon_classes(png, boxes):
    """[(top class index, its probability) | None] per box, from one screenshot and one batch."""
    global _sess
    import numpy as np
    import onnxruntime as ort
    from PIL import Image
    if _sess is None:
        o = ort.SessionOptions()
        o.intra_op_num_threads = 1
        _sess = ort.InferenceSession(ROOT + "/icons/icons.onnx", o, providers=["CPUExecutionProvider"])
    shot = Image.open(io.BytesIO(png)).convert("RGB")
    crops = [crop(shot, b) for b in boxes]
    batch = [c for c in crops if c is not None]
    if not batch:
        return [None] * len(boxes), shot.size
    probs = iter(_sess.run(["probs"], {"image": np.stack(batch)[:, None]})[0])
    return [None if c is None else (lambda p: (int(p.argmax()), round(float(p.max()), 4)))(next(probs))
            for c in crops], shot.size


def episode(e):
    """AC.episodes' episode dict, from an example parsed with its screenshots kept."""
    return {"episode_id": e["episode_id"][0], "goal": e["goal"][0].decode("utf8"),
            "step_instructions": [s.decode("utf8") for s in e.get("step_instructions", [])],
            "actions": [json.loads(a) for a in e.get("actions", [])], "trees": e["accessibility_trees"],
            "screen": (e["screenshot_widths"][0], e["screenshot_heights"][0])}


BOOLS = ("clickable", "long_clickable", "editable", "scrollable", "checkable", "checked", "selected", "enabled", "visible")
ICON_THRESHOLD = 0.90  # models/icons/labels.json, as shipped


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
    """Each icon class's word, None for "other" (never a name)."""
    lab = json.load(open(ROOT + "/icons/labels.json"))
    assert lab["threshold"] == ICON_THRESHOLD
    return [None if c == "other" else w for c, w in zip(lab["classes"], lab["words"])]


def work(shard):
    out = os.path.join(B.OUT, "shards", "%02d.jsonl" % shard)
    extra = os.path.join(EXTRA, "%02d.jsonl" % shard)
    if os.path.exists(out) and os.path.exists(extra):
        return shard, "kept", 0, 0
    split_of, _ = AC.load_splits()
    for attempt in range(3):
        n = empty = 0
        try:
            with open(out + ".tmp", "w", encoding="utf8") as f, open(extra + ".tmp", "w", encoding="utf8") as fx:
                for rec in records(URL % shard):
                    e = AC._example(rec, skip=())
                    ep = episode(e)
                    split = split_of.get(ep["episode_id"])
                    if split is None:
                        continue
                    if not ep["actions"]:  # as B.work: no demonstration to learn from
                        empty += 1
                        continue
                    items = B.episode_items(ep, split)
                    for it in items:
                        f.write(json.dumps(it, ensure_ascii=False) + "\n")
                        n += 1
                    if split != "test":
                        continue
                    side = min(ep["screen"]) // 4
                    for k, it in enumerate(items):
                        boxes = [c["bounds"] for c in it["candidates"] if is_icon(c, side)]
                        named, size = icon_classes(e["screenshots"][k], boxes) if boxes else ([], None)
                        fx.write(json.dumps({"episode_id": it["episode_id"], "step": k, "screen": ep["screen"],
                                             "shot": size, "nodes": AC.parse_forest(ep["trees"][k]),
                                             "icons": [[*b, *nc] for b, nc in zip(boxes, named) if nc]},
                                            ensure_ascii=False) + "\n")
            os.replace(out + ".tmp", out)
            os.replace(extra + ".tmp", extra)
            return shard, "built", n, empty
        except Exception as ex:  # a dropped stream: start the shard again
            print("shard %02d attempt %d failed: %r" % (shard, attempt + 1, ex), flush=True)
            time.sleep(10)
    raise RuntimeError("shard %02d failed 3 times" % shard)


def handoff():
    """data/ac_{train,val,test}.jsonl: each line is the repo's step item plus the laya row S.row() makes of it."""
    os.makedirs(DATA, exist_ok=True)
    _, sub = AC.load_splits()
    want = json.load(open(ROOT + "/laya-android/results/data/androidcontrol_stats.json"))
    counts = {}
    for name, src in (("train", ["train.jsonl"]), ("val", ["val.jsonl"]),
                      ("test", ["shards/%02d.jsonl" % s for s in range(20)])):
        n = eps = 0
        seen = set()
        with open(os.path.join(DATA, "ac_%s.jsonl.tmp" % name), "w", encoding="utf8") as f:
            for path in src:
                for line in open(os.path.join(B.OUT, path), encoding="utf8"):
                    it = json.loads(line)
                    if name == "test":
                        if it["split"] != "test":
                            continue
                        it["subsplits"] = sorted(k for k, ids in sub.items() if it["episode_id"] in ids)
                    f.write(json.dumps({**it, **S.row(it)}, ensure_ascii=False) + "\n")
                    n += 1
                    eps += it["episode_id"] not in seen
                    seen.add(it["episode_id"])
        counts[name] = (eps, n)
    exp = {"train": (want["train"]["episodes"], want["train"]["steps"]), "val": (want["val"]["episodes"], want["val"]["steps"]),
           "test": (1543, 8444 + 1543)}  # docs/eval_protocol.md §2: 8,444 test actions + one DONE per episode
    print("handoff (episodes, steps):", counts, "expected:", exp, flush=True)
    assert counts == exp, "counts differ from the repo's"
    for name in counts:
        os.replace(os.path.join(DATA, "ac_%s.jsonl.tmp" % name), os.path.join(DATA, "ac_%s.jsonl" % name))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--shards", default=",".join(str(i) for i in range(20)))
    ap.add_argument("--workers", type=int, default=10)
    args = ap.parse_args()
    shards = [int(s) for s in args.shards.split(",")]
    os.makedirs(os.path.join(B.OUT, "shards"), exist_ok=True)
    os.makedirs(EXTRA, exist_ok=True)
    t0 = time.time()
    with Pool(min(args.workers, len(shards))) as pool:
        for s, how, n, empty in pool.imap_unordered(work, shards):
            print("shard %02d %s: %d steps, %d empty episodes skipped, %.0f s" % (s, how, n, empty, time.time() - t0),
                  flush=True)
    if len(shards) == 20:
        print("merged", B.merge(shards), flush=True)
        handoff()
