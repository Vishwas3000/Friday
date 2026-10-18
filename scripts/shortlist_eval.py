"""Shortlist check (D-049): on research/18's 52 recorded (screen, goal) pairs, does Laya pick the same target from a
shortlist of k elements as from all of them, and how much faster is the step? Runs on the phone (the "shortlist_eval"
bridge command: the app's own Kotlin shortlist and model), screens from the git-ignored captures/.

    uv run python scripts/shortlist_eval.py 12 16 20
    uv run python scripts/shortlist_eval.py 16 --op 16 24 32    # also the operation row on a shortlist (research/18 §11)
"""
import json
import socket
import statistics
import subprocess
import sys

from speed_eval import PAIRS, screen  # sibling script; importing it loads no model


def call(**cmd):
    with socket.create_connection(("127.0.0.1", 8765), timeout=600).makefile("rw") as f:
        f.write(json.dumps(cmd) + "\n")
        f.flush()
        r = json.loads(f.readline())
    if "error" in r:
        sys.exit("bridge error: " + r["error"])
    return r


def main():
    args = sys.argv[1:]
    op_ks = [int(k) for k in args[args.index("--op") + 1:]] if "--op" in args else []
    ks = [int(k) for k in (args[:args.index("--op")] if "--op" in args else args)] or [16]
    subprocess.run(["adb", "forward", "tcp:8765", "localabstract:layatest_agent"], check=True, capture_output=True)
    rows = []
    for i, (key, goal, hist) in enumerate(PAIRS):
        cands = [dict(c, rid=c.get("resource_id", "").split("/")[-1]) for c in screen(key)]
        cands = [{k: v for k, v in c.items() if k != "bounds"} for c in cands]
        r = call(cmd="shortlist_eval", cands=cands, goal=goal, history=hist, ks=ks, **({"op_ks": op_ks} if op_ks else {}))
        rows.append((key, goal, cands, r))
        f = r["full"]
        print("%2d %-10s %2d cands  %-10s full %3s %5d ms %4d tok  " % (i, key, len(cands), f["op"], f["target"], f["ms"], f["tokens"])
              + "  ".join("k%d %s%3s %5d ms" % (k, "=" if r["k%d" % k]["target"] == f["target"] else "≠", r["k%d" % k]["target"],
                                                 r["k%d" % k]["ms"]) for k in ks if "k%d" % k in r), flush=True)
    for k in op_ks:
        crowded = [(key, goal, c, r) for key, goal, c, r in rows if len(c) > k]
        same = sum(r["op%d" % k]["op"] == r["op_all"]["op"] for _, _, _, r in rows)
        print("operation row from %d: same operation %d/%d; on the %d screens with more than %d: %.1f -> %.1f s, tokens %d -> %d" % (
            k, same, len(rows), len(crowded), k, sum(r["op_all"]["ms"] for *_, r in crowded) / 1000,
            sum(r["op%d" % k]["ms"] for *_, r in crowded) / 1000, sum(r["op_all"]["tokens"] for *_, r in crowded),
            sum(r["op%d" % k]["tokens"] for *_, r in crowded)))
        for key, goal, c, r in rows:
            if r["op%d" % k]["op"] != r["op_all"]["op"]:
                print("   changed: %-10s %r: %s -> %s" % (key, goal, r["op_all"]["op"], r["op%d" % k]["op"]))
    taps = [(key, goal, c, r) for key, goal, c, r in rows if r["full"]["target"] >= 0]
    print("\n%d pairs, %d with a target (tap/type); the operation row is the same either way" % (len(rows), len(taps)))
    for k in ks:
        crowded = [(key, goal, c, r) for key, goal, c, r in taps if len(c) > k]
        same = sum(r["k%d" % k]["target"] == r["full"]["target"] for _, _, _, r in taps)
        inside = sum(r["full"]["target"] in r["k%d" % k]["shown"] for _, _, _, r in taps)
        ms = [(r["full"]["ms"], r["k%d" % k]["ms"]) for _, _, _, r in crowded]
        tok = [(r["full"]["tokens"], r["k%d" % k]["tokens"]) for _, _, _, r in crowded]
        print("k=%d: same target %d/%d; full pick inside the shortlist %d/%d; on the %d screens with more than %d elements:"
              " step %.1f -> %.1f s (median ratio %.2f), tokens %d -> %d" % (
                  k, same, len(taps), inside, len(taps), len(crowded), k,
                  sum(a for a, _ in ms) / 1000, sum(b for _, b in ms) / 1000,
                  statistics.median(b / a for a, b in ms) if ms else 1, sum(a for a, _ in tok), sum(b for _, b in tok)))
        for key, goal, c, r in taps:
            if r["k%d" % k]["target"] != r["full"]["target"]:
                print("   changed: %-10s %r: %r -> %r%s" % (key, goal, c[r["full"]["target"]]["label"], c[r["k%d" % k]["target"]]["label"],
                                                       "" if r["full"]["target"] in r["k%d" % k]["shown"] else " (full pick not shown)"))


if __name__ == "__main__":
    main()
