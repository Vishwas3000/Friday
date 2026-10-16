"""Time Laya-Android on this machine: the model-card example, then synthetic screens of growing size.

Run: uv run python scripts/bench_model.py [cpu|mps|cuda]
Synthetic labels only, so no personal phone data ends up in the output.
"""
import json
import statistics
import sys
import time

import laya

from laya_android import MODEL, OP_DESC, PIN, Q_OP, Q_TARGET

# Pixel 8 Settings rows as option_label() renders them: title + subtitle, cut to 40 chars
ROWS = ["Network & internet Mobile, Wi‑Fi, hotspot (LinearLayout)", "Connected devices Bluetooth, pairing (LinearLayout)",
        "Apps Assistant, recent apps, default app (LinearLayout)", "Notifications Notification history, conv (LinearLayout)",
        "Battery 48% - Charging rapidly (LinearLayout)", "Storage 51% used - 126 GB free (LinearLayout)",
        "Sound & vibration Volume, haptics, Do Not (LinearLayout)", "Display & touch Dark theme, font size, to (LinearLayout)",
        "Wallpaper & style Colors, themed icons, a (LinearLayout)", "Accessibility Display, interaction, audi (LinearLayout)",
        "Security & privacy App security, device l (LinearLayout)", "Location On - 11 apps have access to loc (LinearLayout)",
        "Search Settings (LinearLayout)", "About phone Pixel 8 (LinearLayout)"]


def ask(agent, goal, history, candidates):
    state = {"goal": goal, "history": history, "ui": ["[%d] %s" % (i, c) for i, c in enumerate(candidates)]}
    questions = {
        "operation": {"type": "choice", "instructions": Q_OP, "criteria": OP_DESC},
        "target": {"type": "choice", "instructions": Q_TARGET, "criteria": {str(i): c for i, c in enumerate(candidates)}},
    }
    return agent.system_one(state, questions, max_len=3072, head_max_len=1536)


def main(device=None):
    t = time.perf_counter()
    agent = laya.load(MODEL, device=device, **PIN)
    print(f"load: {time.perf_counter() - t:.1f}s  device={getattr(agent, 'device', '?')}")

    out = ask(agent, "Turn off Bluetooth", ["OPEN_APP Settings"], ROWS[:3])
    print("model-card example (expect CLICK on '1' = Connected devices):")
    print(json.dumps(out, indent=1, default=str)[:2500])

    for n in (3, 14, 50, 100):
        # real rows first; beyond 14, numbered copies only to grow the input
        cands = [r if i < len(ROWS) else r.replace(" (", f" {i} (", 1)
                 for i, r in ((i, ROWS[i % len(ROWS)]) for i in range(n))]
        ask(agent, "Turn off Bluetooth", ["OPEN_APP Settings"], cands)  # warm-up
        times = []
        for _ in range(10):
            t = time.perf_counter()
            out = ask(agent, "Turn off Bluetooth", ["OPEN_APP Settings"], cands)
            times.append((time.perf_counter() - t) * 1000)
        u = out.get("usage", {})
        print(f"candidates={n:3d}  median={statistics.median(times):7.1f} ms  max={max(times):7.1f} ms  "
              f"state_tokens={u.get('state_tokens')} truncated={u.get('truncated')} "
              f"op={out['answers']['operation']['choice']} target={out['answers']['target']['choice']}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else None)
