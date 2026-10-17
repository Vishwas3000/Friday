"""Copy Friday's Teach recordings and confirmed runs off the phone, turn them into laya-train rows, delete them (D-033).

    uv run python scripts/pull_teach.py               # pull to captures/teach/, rows to captures/teach_rows/ (git-ignored)
    uv run python scripts/pull_teach.py --wipe-phone  # ...and then delete them from the phone
    uv run python scripts/pull_teach.py --wipe        # delete the Mac copies (after training)

Rows come from scripts/teach_rows.py (research/14 §5): an operation row per step (+ target), the DONE row, a yes/no
"done" row per screen, and a "which app" row when captures/app_profiles_auto.json and captures/apps.json exist.
It also re-checks every recorded target against the Python candidate builder ("target_reground"/"target_dropped"
should be 0, or the phone's Kotlin port has drifted). Personal data: it stays on this Mac or the GPU box.
Remembered paths (files/routes.json) are Friday's memory, not training data, and stay on the phone.
"""
import argparse
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CAP = ROOT / "captures"
PKG = "dev.layatest.agent"


def run_as(*args, check=True):
    return subprocess.run(["adb", "exec-out", "run-as", PKG, *args], capture_output=True, check=check).stdout


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--wipe", action="store_true", help="delete the Mac copies and their rows")
    ap.add_argument("--wipe-phone", action="store_true", help="after pulling, delete the recordings on the phone")
    a = ap.parse_args()
    if a.wipe:
        for d in (CAP / "teach", CAP / "teach_rows"):
            shutil.rmtree(d, ignore_errors=True)
        return print("deleted the Mac copies (captures/teach, captures/teach_rows)")

    names = run_as("ls", "files/teach", check=False).decode().split()
    out = CAP / "teach"
    out.mkdir(parents=True, exist_ok=True)
    for n in names:
        (out / n).write_bytes(run_as("cat", "files/teach/" + n))
    print("pulled %d recordings into %s" % (len(names), out.relative_to(ROOT)))

    files = sorted(str(p) for p in out.glob("*.jsonl"))
    if files:
        extra = []
        if (CAP / "app_profiles_auto.json").exists() and (CAP / "apps.json").exists():
            extra = ["--profiles", str(CAP / "app_profiles_auto.json"), "--apps", str(CAP / "apps.json")]
        subprocess.run([sys.executable, str(ROOT / "scripts" / "teach_rows.py"), str(ROOT), str(CAP / "teach_rows"),
                        *files, *extra], check=True)
    if a.wipe_phone and names:
        run_as("rm", "-rf", "files/teach")
        print("deleted %d recordings from the phone" % len(names))


if __name__ == "__main__":
    main()
