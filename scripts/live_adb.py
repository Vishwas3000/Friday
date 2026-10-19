"""Live test: Laya-Android drives the USB-connected phone, one step at a time.

uv run python scripts/live_adb.py --app "Open the Clock app and go to the Timer tab"   # via the Friday app
uv run python scripts/live_adb.py "Open the Clock app and go to the Timer tab"         # plain adb, no app (~3 s/step)

Every step is shown before it runs: on the phone with --app (Run / Skip / Stop), otherwise in this terminal.
--yes runs steps without asking, but risky-looking ones (send, pay, delete, ...) still ask.
Each run is logged to captures/ (git-ignored, because it contains screen text).
"""
import argparse
import json
import re
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path

import laya

from laya_android import (MODEL, PIN, build_candidates, decide, history_entry, option_label, parse_forest,
                          parse_uiautomator)

RISKY = re.compile(r"\b(send|pay|buy|purchase|order|delete|remove|uninstall|erase|reset|call|post|share|"
                   r"confirm|transfer|subscribe|install|accept|allow)\b", re.I)
ADB = shutil.which("adb") or sys.exit("adb not found on PATH")


class AppPhone:
    """The Laya agent app's accessibility service, over `adb forward` (one JSON object per line)."""

    def __init__(self, serial, port=8765):
        subprocess.run([ADB, "-s", serial, "forward", f"tcp:{port}", "localabstract:layatest_agent"],
                       check=True, capture_output=True)
        self.f = socket.create_connection(("127.0.0.1", port), timeout=900).makefile("rw")
        self.screen = tuple(self.call(cmd="state", quiet_ms=0)["screen"])

    def call(self, **cmd):
        self.f.write(json.dumps(cmd) + "\n")
        self.f.flush()
        line = self.f.readline()
        if not line:
            sys.exit("the phone app closed the connection: is 'Friday' on in Accessibility settings?")
        reply = json.loads(line)
        if "error" in reply:
            raise RuntimeError(reply["error"])
        return reply

    def observe(self):
        st = self.call(cmd="state")
        return parse_forest(st), st["package"]

    def apps(self):
        return [(a["label"], a["package"]) for a in self.call(cmd="apps")["apps"]]

    def foreground(self):
        return self.call(cmd="foreground")["package"]

    def confirm(self, text, bounds):
        return self.call(cmd="confirm", text=text, bounds=bounds)["choice"]

    def tap(self, x, y, ms=60):
        self.call(cmd="tap", x=x, y=y, ms=ms)

    def long_press(self, x, y):
        self.tap(x, y, ms=1000)

    def swipe(self, x1, y1, x2, y2, ms):
        self.call(cmd="swipe", x=x1, y=y1, x2=x2, y2=y2, ms=ms)

    def type_text(self, text):
        return self.call(cmd="text", text=text)["ok"]

    def open_app(self, pkg):
        self.call(cmd="open_app", package=pkg)

    def key(self, name):
        self.call(cmd="global", action=name)


# shortcut: adb can't read app labels, so the adb backend names apps with this table + package names;
# the phone app (--app) uses the real labels from PackageManager
APP_ALIASES = {"settings": "com.android.settings", "clock": "com.google.android.deskclock",
               "calculator": "com.google.android.calculator", "calendar": "com.google.android.calendar",
               "contacts": "com.google.android.contacts", "chrome": "com.android.chrome",
               "files": "com.google.android.apps.nbu.files", "keep": "com.google.android.keep",
               "maps": "com.google.android.apps.maps", "photos": "com.google.android.apps.photos",
               "play store": "com.android.vending", "youtube": "com.google.android.youtube"}


class AdbPhone:
    """Plain adb: `uiautomator dump` to read (slow; pauses accessibility services), `input` to act."""

    def __init__(self, serial):
        self.serial = serial
        w, h = re.search(r"(\d+)x(\d+)", self.sh("wm", "size")).groups()
        self.screen = (int(w), int(h))
        self.ime = self.sh("settings", "get", "secure", "default_input_method").split("/")[0]

    def sh(self, *args):
        r = subprocess.run([ADB, "-s", self.serial, "shell", *args], capture_output=True, text=True, timeout=60)
        return r.stdout.strip()

    def observe(self):
        for _ in range(3):  # "could not get idle state" happens while a screen animates; retrying usually works
            r = subprocess.run([ADB, "-s", self.serial, "exec-out", "uiautomator", "dump", "/dev/tty"],
                               capture_output=True, text=True, timeout=60)
            if "</hierarchy>" in r.stdout:
                return parse_uiautomator(r.stdout, self.ime), None
            time.sleep(0.5)
        raise RuntimeError("uiautomator dump failed: " + (r.stdout + r.stderr)[-300:])

    def apps(self):
        out = self.sh("cmd", "package", "query-activities", "--brief", "-a", "android.intent.action.MAIN",
                      "-c", "android.intent.category.LAUNCHER")
        pkgs = sorted({line.split("/")[0].strip() for line in out.splitlines() if "/" in line})
        names = {pkg: name for name, pkg in APP_ALIASES.items()}
        return [(names.get(p, p.rsplit(".", 1)[-1]), p) for p in pkgs]

    def foreground(self):
        return None

    def confirm(self, text, bounds):
        print("  " + text.replace("\n", "\n  "))
        if not sys.stdin.isatty():
            print("  stop: this step needs a human at the keyboard")
            return "stop"
        a = input("  [Enter = run, s = skip, n = stop] ").strip().lower()
        return "stop" if a.startswith("n") else "skip" if a.startswith("s") else "run"

    def tap(self, x, y):
        self.sh("input", "tap", str(int(x)), str(int(y)))

    def long_press(self, x, y):
        self.swipe(x, y, x, y, 1000)

    def swipe(self, x1, y1, x2, y2, ms):
        self.sh("input", "swipe", *map(str, (int(x1), int(y1), int(x2), int(y2), ms)))

    def type_text(self, text):
        if not text.isascii():
            print("  ! `input text` cannot type non-ASCII; use --app")
        self.sh("input", "text", "'%s'" % text.replace(" ", "%s").replace("'", "'\\''"))
        return True

    def open_app(self, pkg):
        self.sh("monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")

    def key(self, name):
        self.sh("input", "keyevent", {"back": "4", "home": "3"}[name])


def pick_app(goal, apps):
    """OPEN_APP payload: the installed app whose label appears earliest in the goal (longer label wins ties)."""
    g = goal.lower()
    hits = [(lab, pkg) for lab, pkg in apps if lab and re.search(r"\b%s\b" % re.escape(lab.lower()), g)]
    return min(hits, key=lambda h: (g.find(h[0].lower()), -len(h[0]))) if hits else None


def pick_text(goal, typed):
    """INPUT_TEXT payload: the first quoted span in the goal not typed yet (Laya cannot generate text)."""
    for span in re.findall(r"[\"“]([^\"”]+)[\"”]", goal):
        if span not in typed:
            return span
    return None


def act(phone, op, target, payload, cands):
    if op in ("CLICK", "LONG_PRESS"):
        x1, y1, x2, y2 = target["bounds"]
        x, y = (x1 + x2) // 2, (y1 + y2) // 2
        phone.tap(x, y) if op == "CLICK" else phone.long_press(x, y)
    elif op.startswith("SCROLL_"):
        boxes = [c["bounds"] for c in cands if c["scrollable"]]
        x1, y1, x2, y2 = (max(boxes, key=lambda b: (b[2] - b[0]) * (b[3] - b[1])) if boxes
                          else (0, 300, phone.screen[0], phone.screen[1] - 300))
        cx, cy, dx, dy = (x1 + x2) / 2, (y1 + y2) / 2, (x2 - x1) * 0.3, (y2 - y1) * 0.3
        # SCROLL_DOWN reveals content below, so the finger moves up (AndroidControl/AndroidWorld convention)
        sx, sy = {"SCROLL_DOWN": (0, 1), "SCROLL_UP": (0, -1), "SCROLL_RIGHT": (1, 0), "SCROLL_LEFT": (-1, 0)}[op]
        phone.swipe(cx + sx * dx, cy + sy * dy, cx - sx * dx, cy - sy * dy, 400)
    elif op == "OPEN_APP":
        phone.open_app(payload[1])
    elif op == "INPUT_TEXT":
        if not phone.type_text(payload):
            print("  ! no focused text field to type into")
    elif op in ("BACK", "HOME"):
        phone.key(op.lower())
    elif op == "WAIT":
        time.sleep(2)


def top(probs, k=3):
    return sorted(probs.items(), key=lambda kv: -kv[1])[:k]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("goal")
    ap.add_argument("--app", action="store_true", help="drive the phone through the Friday app (fast)")
    ap.add_argument("--yes", action="store_true", help="run steps without asking (risky ones still ask)")
    ap.add_argument("--steps", type=int, default=10)
    ap.add_argument("--serial", help="adb serial; default: the only connected device")
    ap.add_argument("--settle", type=float, default=0.3, help="seconds to wait after each action")
    a = ap.parse_args()

    serial = a.serial or [l.split()[0] for l in subprocess.run([ADB, "devices"], capture_output=True, text=True)
                          .stdout.splitlines()[1:] if l.endswith("device")][0]
    phone = AppPhone(serial) if a.app else AdbPhone(serial)
    t = time.perf_counter()
    agent = laya.load(MODEL, **PIN)
    print(f"model loaded in {time.perf_counter() - t:.1f}s on {getattr(agent, 'device', '?')}; screen {phone.screen}")
    apps = phone.apps()
    log = Path("captures") / time.strftime("run-%Y%m%d-%H%M%S.jsonl")
    log.parent.mkdir(exist_ok=True)
    history, typed, blocked, skips = [], [], set(), 0

    for step in range(1, a.steps + 1):
        t0 = time.perf_counter()
        nodes, fg = phone.observe()
        cands = [c for c in build_candidates(nodes, phone.screen) if (c["label"], c["bounds"]) not in blocked]
        t1 = time.perf_counter()
        answers, usage = decide(agent, a.goal, history, cands)
        t2 = time.perf_counter()
        op = answers["operation"]["choice"]
        p_op = answers["operation"]["probabilities"][op]
        tprobs = answers.get("target", {}).get("probabilities", {})
        target = cands[int(answers["target"]["choice"])] if tprobs else None
        payload = pick_app(a.goal, apps) if op == "OPEN_APP" else pick_text(a.goal, typed) if op == "INPUT_TEXT" else None
        targeted = op in ("CLICK", "LONG_PRESS") and target is not None

        shown = option_label(target) if targeted else payload[0] if op == "OPEN_APP" and payload else payload or ""
        print(f"[{step}] {op} {shown}   op={top(answers['operation']['probabilities'])} "
              f"target_top={[(option_label(cands[int(i)])[:30], p) for i, p in top(tprobs)]}  "
              f"observe={1000 * (t1 - t0):.0f}ms decide={1000 * (t2 - t1):.0f}ms "
              f"cands={len(cands)} tokens={usage.get('input_tokens')} truncated={usage.get('truncated')}")
        record = {"step": step, "goal": a.goal, "history": history, "op": op, "foreground": fg,
                  "op_probs": answers["operation"]["probabilities"], "target": shown,
                  "target_probs": top(tprobs, 5), "payload": payload, "cands": [option_label(c) for c in cands],
                  "observe_ms": 1000 * (t1 - t0), "decide_ms": 1000 * (t2 - t1), "usage": usage}

        if op == "DONE":
            print("model says DONE")
            break
        if op in ("OPEN_APP", "INPUT_TEXT") and payload is None:
            print(f"stop: {op} needs an app name / text the model cannot produce (no match in the goal)")
            break
        if not a.yes or (targeted and RISKY.search(target["label"])):
            sure = f" ({p_op:.0%}" + (f", target {tprobs[str(cands.index(target))]:.0%})" if targeted else ")")
            choice = phone.confirm(f"Step {step}: {op} {shown}{sure}\nGoal: {a.goal}",
                                   list(target["bounds"]) if targeted else None)
            record["choice"] = choice
            if choice == "stop":
                print("stopped by the owner")
                break
            if choice == "skip":
                skips += 1
                if targeted:
                    blocked.add((target["label"], target["bounds"]))
                with log.open("a") as f:
                    f.write(json.dumps(record) + "\n")
                if skips >= 3:
                    print("stop: three skips in a row")
                    break
                continue
            if fg and phone.foreground() != fg:  # the screen changed while the owner was deciding
                print("  screen changed while waiting; looking again")
                continue
        skips = 0
        with log.open("a") as f:
            f.write(json.dumps(record) + "\n")
        act(phone, op, target, payload, cands)
        if op == "INPUT_TEXT":
            typed.append(payload)
        history.append(history_entry(op, target, payload[0] if op == "OPEN_APP" else payload))
        if len(history) >= 3 and len(set(history[-3:])) == 1:
            print("stop: same action three times in a row")
            break
        time.sleep(a.settle)
    print(f"log: {log}")


if __name__ == "__main__":
    main()
