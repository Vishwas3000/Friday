"""Friday's request log (D-045): what you asked, what it answered, and where the time went.

    uv run python scripts/requests_log.py          # the last 20 requests: total time, Laya time, steps
    uv run python scripts/requests_log.py -v       # ...plus each request's timeline
    uv run python scripts/requests_log.py --wipe   # delete the log on the phone

Every request (Run on the phone, or the bridge's "ask") appends one line to files/requests.jsonl on the phone:
the request, the answer, total ms, and the trace, each line stamped "+X.Xs" from the start. Text only, no
screens; "Delete all learned data" on the phone deletes it too.
"""
import argparse
import json
import re
import socket
import subprocess

STEP = re.compile(r"\[read (\d+) ms \(settle (\d+)\), laya (\d+) ms: (\d+) cands, (\d+) tokens\]")
MEMORY = re.compile(r"\[read (\d+) ms, no Laya\]")
assert STEP.search("+9.8s step 1 on x: CLICK \"Timers\" (op 0.89) -> AUTO  [read 806 ms (settle 246), laya 8484 ms: "
                   "19 cands, 1118 tokens]")[3] == "8484"


def call(**cmd):
    subprocess.run(["adb", "forward", "tcp:8765", "localabstract:layatest_agent"], check=True, capture_output=True)
    f = socket.create_connection(("127.0.0.1", 8765), timeout=60).makefile("rw")
    f.write(json.dumps(cmd) + "\n")
    f.flush()
    return json.loads(f.readline())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-n", type=int, default=20, help="how many recent requests")
    ap.add_argument("-v", action="store_true", help="print each request's timeline")
    ap.add_argument("--wipe", action="store_true", help="delete the log on the phone")
    a = ap.parse_args()
    if a.wipe:
        subprocess.run(["adb", "exec-out", "run-as", "dev.layatest.agent", "rm", "-f", "files/requests.jsonl"], check=True)
        return print("deleted the request log on the phone")
    rows = call(cmd="requests", last=a.n)["requests"]
    print("%-44s %7s %7s %5s %4s  %s" % ("request", "total", "laya", "laya#", "mem", "answer"))
    for r in rows:
        steps = [m for m in map(STEP.search, r["trace"]) if m]
        print("%-44s %6.1fs %6.1fs %5d %4d  %s" % (
            r["request"][:44], r["ms"] / 1000, sum(int(m[3]) for m in steps) / 1000, len(steps),
            sum(1 for t in r["trace"] if MEMORY.search(t)), r["answer"].replace("\n", " ")[:50]))
        if a.v:
            for t in r["trace"]:
                print("      ", t)


if __name__ == "__main__":
    main()
