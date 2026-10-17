"""Teach mode with the phone plugged in (D-033): forward the owner's real touches to Friday's recorder.

    uv run python scripts/teach_touches.py --goal "Open the Clock app and reset the stopwatch"
    uv run python scripts/teach_touches.py --check      # parser + classifier self-check, no phone

Teach mode on its own records taps from click events. Compose apps and buttons that hide themselves send
none (measured: Play Store tabs, Clock's Reset), so those steps were lost. This script reads the
touchscreen with `adb shell getevent` (the adb shell user is in the `input` group) and sends every tap,
long-press and edge swipe (BACK / HOME) to the phone. The phone matches each point against the screen it
was showing, the same way it handles click events, and ignores click events for this session so nothing
is recorded twice. Scrolls and typing still come from the phone's own events.
Finish with Done or Cancel on the phone's red banner; the script exits at the next touch after that.
Ctrl-C only stops forwarding.
"""
import argparse
import json
import math
import re
import socket
import subprocess

PORT = 8765
SLOP = 40          # px a finger may drift and still be a tap
LONG_S = 0.5       # held this long: a long-press
EDGE = 60          # px: gesture-nav back swipes start this close to the left/right edge
BOTTOM = 80        # px: the home swipe starts this close to the bottom edge
LINE = re.compile(r"\[\s*([\d.]+)\]\s+(EV_\w+)\s+(\w+)\s+(\w+)")


def call(**cmd):
    f = socket.create_connection(("127.0.0.1", PORT), timeout=30).makefile("rw")
    f.write(json.dumps(cmd) + "\n"); f.flush()
    return json.loads(f.readline())


def touchscreen():
    """(device, max x, max y) of the input device that reports multi-touch positions."""
    out = subprocess.run(["adb", "shell", "getevent", "-pl"], capture_output=True, text=True, check=True).stdout
    for block in out.split("add device")[1:]:
        mx = re.search(r"ABS_MT_POSITION_X\s*:.*?max (\d+)", block)
        my = re.search(r"ABS_MT_POSITION_Y\s*:.*?max (\d+)", block)
        if mx and my:
            return block.split(":", 1)[1].split()[0], int(mx[1]), int(my[1])
    raise SystemExit("no touchscreen found in `adb shell getevent -pl`")


def gestures(lines):
    """getevent -lt lines -> (start, end, multi) per gesture; start/end are (seconds, raw x, raw y)."""
    slot, first, active, multi = 0, None, set(), False
    x = y = start = None
    for line in lines:
        m = LINE.match(line)
        if not m:
            continue
        t, code, val = float(m[1]), m[3], m[4]
        if code == "ABS_MT_SLOT":
            slot = int(val, 16)
        elif code == "ABS_MT_TRACKING_ID":
            if val == "ffffffff":
                active.discard(slot)
            else:
                active.add(slot)
                first = slot if first is None else first
                multi |= len(active) > 1
        elif code == "ABS_MT_POSITION_X" and slot == first:
            x = int(val, 16)
        elif code == "ABS_MT_POSITION_Y" and slot == first:
            y = int(val, 16)
        elif code == "SYN_REPORT":
            if start is None and active and x is not None and y is not None:
                start = (t, x, y)
            elif start is not None and not active:
                yield start, (t, x, y), multi
                first, multi, start = None, False, None


def classify(start, end, w, h):
    """A gesture in screen px -> teach_input fields, or None for scrolls (the phone records those itself)."""
    (t0, x0, y0), (t1, x1, y1) = start, end
    dx, dy = x1 - x0, y1 - y0
    if math.hypot(dx, dy) < SLOP:
        return {"x": x0, "y": y0, "long": t1 - t0 >= LONG_S}
    if (x0 < EDGE and dx > 150) or (x0 > w - EDGE and dx < -150):
        return {"op": "BACK"}
    if y0 > h - BOTTOM and dy < -200:
        return {"op": "HOME"}
    return None


def check():
    # a tap at (540, 900), then a swipe in from the left edge; real lines are "[time] TYPE CODE VALUE"
    lines = """[ 10.000] EV_ABS ABS_MT_TRACKING_ID 0000002a
[ 10.000] EV_ABS ABS_MT_POSITION_X 0000021c
[ 10.000] EV_ABS ABS_MT_POSITION_Y 00000384
[ 10.000] EV_SYN SYN_REPORT 00000000
[ 10.080] EV_ABS ABS_MT_TRACKING_ID ffffffff
[ 10.080] EV_SYN SYN_REPORT 00000000
[ 11.000] EV_ABS ABS_MT_TRACKING_ID 0000002b
[ 11.000] EV_ABS ABS_MT_POSITION_X 00000005
[ 11.000] EV_ABS ABS_MT_POSITION_Y 000004b0
[ 11.000] EV_SYN SYN_REPORT 00000000
[ 11.100] EV_ABS ABS_MT_POSITION_X 00000190
[ 11.100] EV_SYN SYN_REPORT 00000000
[ 11.150] EV_ABS ABS_MT_TRACKING_ID ffffffff
[ 11.150] EV_SYN SYN_REPORT 00000000"""
    got = [classify(s, e, 1080, 2400) for s, e, multi in gestures(lines.splitlines()) if not multi]
    assert got == [{"x": 540, "y": 900, "long": False}, {"op": "BACK"}], got
    assert classify((0, 500, 1000), (0.7, 510, 1005), 1080, 2400) == {"x": 500, "y": 1000, "long": True}
    assert classify((0, 540, 2380), (0.2, 540, 1500), 1080, 2400) == {"op": "HOME"}
    assert classify((0, 540, 1800), (0.3, 540, 600), 1080, 2400) is None  # a scroll
    print("teach_touches self-check: ok")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--goal", help="what you're about to do on the phone")
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    if args.check:
        return check()
    if not args.goal:
        ap.error("--goal is required")
    subprocess.run(["adb", "forward", f"tcp:{PORT}", "localabstract:layatest_agent"], check=True, capture_output=True)
    dev, mx, my = touchscreen()
    w, h = map(int, re.search(r"(\d+)x(\d+)", subprocess.run(
        ["adb", "shell", "wm", "size"], capture_output=True, text=True).stdout).groups())
    # shortcut: portrait only (raw axes map straight to the screen); rotate x/y here if teaching in landscape
    if not call(cmd="teach", goal=args.goal, raw=True).get("started"):
        raise SystemExit("the phone didn't start teaching (Friday busy, or already teaching?)")
    print("Teaching on the phone -- do the task; finish with Done or Cancel on the red banner.")
    # -tt: a terminal, or getevent buffers its output and touches arrive seconds late
    p = subprocess.Popen(["adb", "shell", "-tt", "getevent", "-lt", dev], stdout=subprocess.PIPE, text=True, bufsize=1)
    try:
        for start, end, multi in gestures(p.stdout):
            scale = lambda e: (e[0], e[1] * w // (mx + 1), e[2] * h // (my + 1))  # noqa: E731
            step = None if multi else classify(scale(start), scale(end), w, h)
            if step is None:
                continue
            r = call(cmd="teach_input", **step)
            print("  ->", step, "" if r.get("teaching", True) else "(teaching ended)")
            if r.get("teaching") is False:
                break
    except KeyboardInterrupt:
        print("stopped forwarding; finish with Done or Cancel on the phone")
    finally:
        p.terminate()


if __name__ == "__main__":
    main()
