"""Friday's task suite (D-049): real tasks, each with an automatic check, run on the phone before and after a change.
Prints pass/fail, total time, Laya time and steps per task; saves each run to captures/suite/ (git-ignored).

    uv run python scripts/task_suite.py                      # every task, memory off: Laya and the skills do the work
    uv run python scripts/task_suite.py --memory             # with Friday's memory, read only (the suite never teaches it)
    uv run python scripts/task_suite.py --shortlist 16       # Laya picks targets from 16 elements (D-049); 0 = all
    uv run python scripts/task_suite.py --only clock         # tasks whose id contains "clock"
    uv run python scripts/task_suite.py --owner              # also tasks that need you (fingerprint): stay by the phone
    uv run python scripts/task_suite.py --compare captures/suite/A.json captures/suite/B.json

Tasks: scripts/suite_tasks.json (generic, public) plus captures/suite_tasks.json (yours, private), in the same format:
    {"id": "...", "request": "...", "owner": false,
     "check": {"pkg": "<foreground app>", "screen": ["<text on screen>", ...], "selected": "<selected tab's text>",
               "answer": "<regex>", "media": "<package playing>", "shell": ["<adb shell cmd>", "<regex>"]},
     "restore": ["<adb shell cmd to read>", "<regex, one group>", "<adb shell cmd to write {}>"]}
Every check given must pass. Requests run unattended: a step that would wait for you is skipped instead (the app
logs it). Music is muted while the suite runs and media is paused after each media task.
"""
import argparse
import json
import re
import socket
import statistics
import subprocess
import time
from datetime import datetime
from pathlib import Path

from requests_log import MEMORY, STEP  # sibling script: how a trace line reports a Laya step

ROOT = Path(__file__).resolve().parent.parent
TASKS = (ROOT / "scripts/suite_tasks.json", ROOT / "captures/suite_tasks.json")
RUNS = ROOT / "captures/suite"
PLAYING = re.compile(r"state=PlaybackState \{state=(?:PLAYING\()?3\b")
assert PLAYING.search("state=PlaybackState {state=PLAYING(3), position=0") and not PLAYING.search("{state=PAUSED(2)")


def sh(cmd):
    return subprocess.run(["adb", "shell", cmd], capture_output=True, text=True).stdout


def call(**cmd):
    with socket.create_connection(("127.0.0.1", 8765), timeout=900).makefile("rw") as f:
        f.write(json.dumps(cmd) + "\n")
        f.flush()
        return json.loads(f.readline())


def texts(state):
    """(text, selected) for every node in every window of the bridge's "state"."""
    out, todo = [], [w["root"] for w in state.get("windows", []) if w.get("root")]
    while todo:
        n = todo.pop()
        out.append((" ".join(filter(None, (n.get("text"), n.get("content_desc"), n.get("hint")))), n.get("selected", False)))
        todo += n.get("children", [])
    return out


def playing(pkg):
    """A media session of [pkg] is playing: dumpsys lists each session's package= line, then its state."""
    return any(b.startswith(pkg) and PLAYING.search(b) for b in sh("dumpsys media_session").split("package=")[1:])


def check(c, answer):
    fails = []
    if "pkg" in c and call(cmd="foreground").get("package") != c["pkg"]:
        fails.append("app")
    for _ in range(6):  # a page that is still loading gets up to 6 s (Chrome's results, 2026-10-18)
        if "screen" not in c and "selected" not in c:
            break
        t = texts(call(cmd="state"))
        miss = ["screen: " + w for w in c.get("screen", []) if not any(w.lower() in s.lower() for s, _ in t)]
        if "selected" in c and not any(sel and c["selected"].lower() in s.lower() for s, sel in t):
            miss.append("selected: " + c["selected"])
        if not miss:
            break
        time.sleep(1)
    else:
        fails += miss
    if "answer" in c and not re.search(c["answer"], answer or ""):
        fails.append("answer")
    if "media" in c and not any(playing(c["media"]) or time.sleep(1) for _ in range(6)):
        fails.append("not playing")
    if "shell" in c and not re.search(c["shell"][1], sh(c["shell"][0])):
        fails.append("shell: " + c["shell"][1])
    return fails


def run(task, a):
    sh("input keyevent KEYCODE_HOME")
    time.sleep(1.5)
    saved = re.search(task["restore"][1], sh(task["restore"][0])) if "restore" in task else None
    opts = {"shortlist": a.shortlist} if a.shortlist is not None else {}
    t0 = time.time()
    r = call(cmd="ask", goal=task["request"], memory="read" if a.memory else "off", unattended=True, **opts)
    ms = (time.time() - t0) * 1000
    time.sleep(1)
    fails = ["error: " + r["error"]] if "error" in r else check(task["check"], r.get("answer"))
    if "media" in task["check"]:
        sh("input keyevent KEYCODE_MEDIA_PAUSE")
    if saved:
        sh(task["restore"][2].format(saved[1]))
    trace = r.get("trace", [])
    steps = [m for m in map(STEP.search, trace) if m]
    return {"id": task["id"], "request": task["request"], "ok": not fails, "fails": fails, "answer": r.get("answer"),
            "ms": round(ms), "laya_ms": sum(int(m[3]) for m in steps), "laya_steps": len(steps),
            "memory_steps": sum(1 for t in trace if MEMORY.search(t)), "trace": trace}


def line(r):
    return "%-24s %s %6.1fs  laya %5.1fs in %d  %s" % (r["id"], "PASS" if r["ok"] else "FAIL", r["ms"] / 1000,
                                                     r["laya_ms"] / 1000, r["laya_steps"],
                                                     ", ".join(r["fails"]) or (r["answer"] or "")[:50].replace("\n", " "))


def compare(pa, pb):
    a, b = ({r["id"]: r for r in json.loads(Path(p).read_text())["results"]} for p in (pa, pb))
    both = [i for i in a if i in b]
    print("%-24s %-11s %-17s %s" % ("task", "pass", "total s", "laya s"))
    for i in both:
        x, y = a[i], b[i]
        print("%-24s %s -> %s  %6.1f -> %6.1f  %6.1f -> %6.1f" % (i, "ok" if x["ok"] else "--", "ok" if y["ok"] else "--",
              x["ms"] / 1000, y["ms"] / 1000, x["laya_ms"] / 1000, y["laya_ms"] / 1000))
    for name, d in (("A", a), ("B", b)):
        print("%s: %d/%d pass, median %.1f s, Laya %.1f s in all" % (name, sum(d[i]["ok"] for i in both), len(both),
              statistics.median(d[i]["ms"] for i in both) / 1000, sum(d[i]["laya_ms"] for i in both) / 1000))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--memory", action="store_true", help="use Friday's memory (read only)")
    ap.add_argument("--shortlist", type=int, help="target row size (default: the app's own)")
    ap.add_argument("--only", default="", help="run tasks whose id contains this")
    ap.add_argument("--owner", action="store_true", help="include tasks that need you at the phone")
    ap.add_argument("--compare", nargs=2, metavar="RUN", help="compare two saved runs")
    a = ap.parse_args()
    if a.compare:
        return compare(*a.compare)
    tasks = [t for f in TASKS if f.exists() for t in json.loads(f.read_text())]
    tasks = [t for t in tasks if a.only in t["id"] and (a.owner or not t.get("owner"))]
    subprocess.run(["adb", "forward", "tcp:8765", "localabstract:layatest_agent"], check=True, capture_output=True)
    vol = re.search(r"volume is (\d+)", sh("cmd media_session volume --stream 3 --get"))
    sh("cmd media_session volume --stream 3 --set 0")
    results = []
    try:
        for t in tasks:
            try:
                results.append(run(t, a))
            except (OSError, ValueError) as e:  # the bridge went away (app restarted?): note it, go on
                results.append({"id": t["id"], "request": t["request"], "ok": False, "fails": ["bridge: %s" % e],
                                "answer": None, "ms": 0, "laya_ms": 0, "laya_steps": 0, "memory_steps": 0, "trace": []})
                subprocess.run(["adb", "forward", "tcp:8765", "localabstract:layatest_agent"], capture_output=True)
            print(line(results[-1]), flush=True)
    finally:
        if vol:
            sh("cmd media_session volume --stream 3 --set " + vol[1])
        sh("input keyevent KEYCODE_HOME")
    RUNS.mkdir(parents=True, exist_ok=True)
    out = RUNS / (datetime.now().strftime("%Y%m%d-%H%M%S") + ".json")
    out.write_text(json.dumps({"options": vars(a), "results": results}, indent=1, ensure_ascii=False))
    print("\n%d/%d pass, median %.1f s, Laya %.1f s in all -> %s" % (
        sum(r["ok"] for r in results), len(results), statistics.median(r["ms"] for r in results) / 1000,
        sum(r["laya_ms"] for r in results) / 1000, out.relative_to(ROOT)))


if __name__ == "__main__":
    main()
