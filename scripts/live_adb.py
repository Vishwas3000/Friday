"""Live test: Laya-Android drives the USB-connected phone over adb, one step at a time.

uv run python scripts/live_adb.py "Open Settings and show the Battery page" [--yes] [--steps 10] [--serial ID]

Observe = `uiautomator dump`, decide = Laya-Android, act = `adb shell input`.
Without --yes every action waits for Enter. Risky-looking targets (send, pay, delete, ...) always need a human.
Each run is logged to captures/ (git-ignored, because it contains screen text).
"""
import argparse
import json
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

import laya

from laya_android import MODEL, PIN, build_candidates, decide, history_entry, option_label, parse_uiautomator

RISKY = re.compile(r"\b(send|pay|buy|purchase|order|delete|remove|uninstall|erase|reset|call|post|share|"
                   r"confirm|transfer|subscribe|install|accept|allow)\b", re.I)
# shortcut: adb can't read app labels, so OPEN_APP matches the goal against this table + package names;
# the on-phone app will use PackageManager labels instead
APP_ALIASES = {"settings": "com.android.settings", "clock": "com.google.android.deskclock",
               "calculator": "com.google.android.calculator", "calendar": "com.google.android.calendar",
               "contacts": "com.google.android.contacts", "chrome": "com.android.chrome",
               "files": "com.google.android.apps.nbu.files", "keep": "com.google.android.keep",
               "maps": "com.google.android.apps.maps", "photos": "com.google.android.apps.photos",
               "play store": "com.android.vending", "youtube": "com.google.android.youtube"}
ADB = shutil.which("adb") or sys.exit("adb not found on PATH")


class Phone:
    def __init__(self, serial):
        self.serial = serial
        w, h = re.search(r"(\d+)x(\d+)", self.sh("wm", "size")).groups()
        self.screen = (int(w), int(h))
        self.ime = self.sh("settings", "get", "secure", "default_input_method").split("/")[0]

    def sh(self, *args):
        r = subprocess.run([ADB, "-s", self.serial, "shell", *args], capture_output=True, text=True, timeout=60)
        return r.stdout.strip()

    def dump(self):
        for _ in range(3):  # "could not get idle state" happens while a screen animates; retrying usually works
            r = subprocess.run([ADB, "-s", self.serial, "exec-out", "uiautomator", "dump", "/dev/tty"],
                               capture_output=True, text=True, timeout=60)
            if "</hierarchy>" in r.stdout:
                return r.stdout
            time.sleep(0.5)
        raise RuntimeError("uiautomator dump failed: " + (r.stdout + r.stderr)[-300:])

    def launcher_packages(self):
        out = self.sh("cmd", "package", "query-activities", "--brief", "-a", "android.intent.action.MAIN",
                      "-c", "android.intent.category.LAUNCHER")
        return sorted({line.split("/")[0].strip() for line in out.splitlines() if "/" in line})

    def swipe(self, x1, y1, x2, y2, ms):
        self.sh("input", "swipe", *map(str, (int(x1), int(y1), int(x2), int(y2), ms)))

    def type_text(self, text):
        if not text.isascii():
            print("  ! `input text` cannot type non-ASCII; needs ADBKeyboard or the on-phone app")
        escaped = text.replace(" ", "%s").replace("'", "'\\''")
        self.sh("input", "text", "'%s'" % escaped)


def pick_app(goal, packages):
    """App for OPEN_APP from the goal text. Returns (name, package) or None."""
    g = goal.lower()
    hits = [(name, pkg) for name, pkg in APP_ALIASES.items() if re.search(r"\b%s\b" % name, g) and pkg in packages]
    if not hits:
        noise = {"com", "android", "google", "apps", "app", "mobile", "ui"}
        words = set(re.findall(r"[a-z]+", g))
        hits = [(pkg.rsplit(".", 1)[-1], pkg) for pkg in packages if words & (set(pkg.split(".")) - noise)]
    return hits[0] if len(hits) == 1 else None


def pick_text(goal, typed):
    """INPUT_TEXT payload: the first quoted span in the goal not typed yet (Laya cannot generate text)."""
    for span in re.findall(r"[\"“]([^\"”]+)[\"”]", goal):
        if span not in typed:
            return span
    return None


def scroll_box(cands, screen):
    boxes = [c["bounds"] for c in cands if c["scrollable"]]
    return max(boxes, key=lambda b: (b[2] - b[0]) * (b[3] - b[1])) if boxes else (0, 300, screen[0], screen[1] - 300)


def act(phone, op, target, payload, cands):
    if op in ("CLICK", "LONG_PRESS"):
        x1, y1, x2, y2 = target["bounds"]
        x, y = (x1 + x2) // 2, (y1 + y2) // 2
        phone.sh("input", "tap", str(x), str(y)) if op == "CLICK" else phone.swipe(x, y, x, y, 800)
    elif op.startswith("SCROLL_"):
        x1, y1, x2, y2 = scroll_box(cands, phone.screen)
        cx, cy, dx, dy = (x1 + x2) / 2, (y1 + y2) / 2, (x2 - x1) * 0.3, (y2 - y1) * 0.3
        # SCROLL_DOWN reveals content below, so the finger moves up (AndroidControl/AndroidWorld convention)
        sx, sy = {"SCROLL_DOWN": (0, 1), "SCROLL_UP": (0, -1), "SCROLL_RIGHT": (1, 0), "SCROLL_LEFT": (-1, 0)}[op]
        phone.swipe(cx + sx * dx, cy + sy * dy, cx - sx * dx, cy - sy * dy, 400)
    elif op == "OPEN_APP":
        phone.sh("monkey", "-p", payload[1], "-c", "android.intent.category.LAUNCHER", "1")
    elif op == "INPUT_TEXT":
        phone.type_text(payload)
    elif op == "BACK":
        phone.sh("input", "keyevent", "4")
    elif op == "HOME":
        phone.sh("input", "keyevent", "3")
    elif op == "WAIT":
        time.sleep(2)


def top(probs, k=3):
    return sorted(probs.items(), key=lambda kv: -kv[1])[:k]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("goal")
    ap.add_argument("--yes", action="store_true", help="run actions without asking (risky ones still stop)")
    ap.add_argument("--steps", type=int, default=10)
    ap.add_argument("--serial", help="adb serial; default: the only connected device")
    ap.add_argument("--settle", type=float, default=1.0, help="seconds to wait after each action")
    a = ap.parse_args()

    serial = a.serial or [l.split()[0] for l in subprocess.run([ADB, "devices"], capture_output=True, text=True)
                          .stdout.splitlines()[1:] if l.endswith("device")][0]
    phone = Phone(serial)
    t = time.perf_counter()
    agent = laya.load(MODEL, **PIN)
    print(f"model loaded in {time.perf_counter() - t:.1f}s on {getattr(agent, 'device', '?')}; screen {phone.screen}")
    packages = phone.launcher_packages()
    log = Path("captures") / time.strftime("run-%Y%m%d-%H%M%S.jsonl")
    log.parent.mkdir(exist_ok=True)
    history, typed = [], []

    for step in range(1, a.steps + 1):
        t0 = time.perf_counter()
        cands = build_candidates(parse_uiautomator(phone.dump()), phone.screen, {phone.ime})
        t1 = time.perf_counter()
        answers, usage = decide(agent, a.goal, history, cands)
        t2 = time.perf_counter()
        op = answers["operation"]["choice"]
        tprobs = answers.get("target", {}).get("probabilities", {})
        target = cands[int(answers["target"]["choice"])] if tprobs else None
        payload = pick_app(a.goal, packages) if op == "OPEN_APP" else pick_text(a.goal, typed) if op == "INPUT_TEXT" else None

        shown = option_label(target) if op in ("CLICK", "LONG_PRESS") and target else payload or ""
        print(f"[{step}] {op} {shown}   op={top(answers['operation']['probabilities'])} "
              f"target_top={[(option_label(cands[int(i)])[:30], p) for i, p in top(tprobs)]}  "
              f"observe={1000 * (t1 - t0):.0f}ms decide={1000 * (t2 - t1):.0f}ms "
              f"cands={len(cands)} tokens={usage.get('input_tokens')} truncated={usage.get('truncated')}")
        with log.open("a") as f:
            f.write(json.dumps({"step": step, "goal": a.goal, "history": history, "op": op,
                                "op_probs": answers["operation"]["probabilities"], "target": shown,
                                "target_probs": top(tprobs, 5), "payload": payload,
                                "cands": [option_label(c) for c in cands], "observe_ms": 1000 * (t1 - t0),
                                "decide_ms": 1000 * (t2 - t1), "usage": usage}) + "\n")

        if op == "DONE":
            print("model says DONE")
            break
        if op in ("OPEN_APP", "INPUT_TEXT") and payload is None:
            print(f"stop: {op} needs an app name / text the model cannot produce (no match in the goal)")
            break
        risky = op in ("CLICK", "LONG_PRESS") and target and RISKY.search(target["label"])
        if risky or not a.yes:
            if not sys.stdin.isatty():
                print("stop: risky action needs a human at the keyboard")
                break
            if input("  run it? [Enter = yes, n = stop] ").strip().lower().startswith("n"):
                break
        act(phone, op, target, payload, cands)
        if op == "INPUT_TEXT":
            typed.append(payload)
        history.append(history_entry(op, target, payload[0].title() if op == "OPEN_APP" else payload))
        if len(history) >= 3 and len(set(history[-3:])) == 1:
            print("stop: same action three times in a row")
            break
        time.sleep(a.settle)
    print(f"log: {log}")


if __name__ == "__main__":
    main()
