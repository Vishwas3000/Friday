"""Can Friday read this screen? A privacy-safe probe for payment apps (research/11 §8, D-029).

    uv run python scripts/probe_screen.py      # with the payment app's history open on the phone

Prints only the screen's STRUCTURE: how many texts look like amounts, dates or payment
directions, and each row's shape as placeholders ([NAME] [AMOUNT] [DATE]). It never prints or
saves a value -- no amounts, names or dates leave this process.
"""
import json
import re
import socket
import subprocess
import sys

AMOUNT = re.compile(r"(?:[+\-−]\s*)?(?:₹|rs\.?|inr)\s*[\d,]+(?:\.\d{1,2})?|[\d,]+(?:\.\d{1,2})?\s*(?:₹|rs\b)", re.I)
DATE = re.compile(
    r"\b(today|yesterday|\d{1,2}\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)\w*|"
    r"(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)\w*\s+\d{1,2}|\d{1,2}[/-]\d{1,2}[/-]\d{2,4}|"
    r"\d{1,2}:\d{2}\s*(am|pm)?)\b", re.I)
DIRECTION = re.compile(r"\b(paid|sent|received|debited|credited|refund\w*|failed|pending|cashback)\b", re.I)


def call(**cmd):
    f = socket.create_connection(("127.0.0.1", 8765), timeout=30).makefile("rw")
    f.write(json.dumps(cmd) + "\n")
    f.flush()
    return json.loads(f.readline())


def kind(text):
    if AMOUNT.search(text):
        return "AMOUNT"
    if DIRECTION.search(text):
        return "DIR"
    if DATE.search(text):
        return "DATE"
    return "NAME"


def main():
    subprocess.run(["adb", "forward", "tcp:8765", "localabstract:layatest_agent"], capture_output=True)
    st = call(cmd="state")
    texts, nodes, unlabeled_actionable = [], 0, 0

    def walk(n, ime):
        nonlocal nodes, unlabeled_actionable
        nodes += 1
        t = (n["text"] or n["content_desc"] or "").strip()
        if n["visible"] and not ime:
            if t:
                b = n["bounds"]
                texts.append(((b[1] + b[3]) // 2, b[0], kind(t)))
            elif n["clickable"]:
                unlabeled_actionable += 1
        for c in n["children"]:
            walk(c, ime)

    for w in st["windows"]:
        walk(w["root"], w["type"] == 2)

    counts = {k: sum(1 for *_, kk in texts if kk == k) for k in ("AMOUNT", "DATE", "DIR", "NAME")}
    print("foreground app :", st.get("package"))
    print("windows        :", len(st["windows"]), "| nodes:", nodes, "| visible texts:", len(texts),
          "| unlabelled tappables:", unlabeled_actionable)
    print("text kinds     :", counts)

    # group texts into rows by vertical position (a list row's parts sit within ~60 px of each other)
    rows, last_y = [], None
    for y, x, k in sorted(texts):
        if last_y is None or y - last_y > 60:
            rows.append([])
        rows[-1].append((x, k))
        last_y = y
    shapes = [" ".join("[%s]" % k for _, k in sorted(r)) for r in rows]
    with_amount = [s for s in shapes if "[AMOUNT]" in s]
    print("rows           :", len(rows), "| rows with an amount:", len(with_amount))
    print("row shapes (placeholders only, first 10 with an amount):")
    for s in with_amount[:10]:
        print("   ", s)
    verdict = ("READABLE: amounts are visible to Friday" if counts["AMOUNT"] >= 3 else
               "NOT READABLE here: few/no amounts visible (hidden, or not a history screen)")
    print("verdict        :", verdict)


if __name__ == "__main__":
    sys.exit(main())
