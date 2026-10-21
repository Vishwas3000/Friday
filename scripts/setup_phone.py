"""One-command phone setup: install the agent APK, open permission setup, forward the adb bridge.

uv run python scripts/setup_phone.py            # install + prompt for access + forward
uv run python scripts/setup_phone.py --build     # also build the debug APK first (needs the Android SDK + JDK 17)

Works on macOS and Windows. Permissions are approved by the owner on the phone.
"""
import argparse
import platform
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APK = ROOT / "android/app/build/outputs/apk/debug/app-debug.apk"
PORT = 8765
adb = shutil.which("adb") or sys.exit("adb not found on PATH (install Android platform-tools)")


def sh(*args, check=True):
    r = subprocess.run([adb, *args], capture_output=True, text=True)
    if check and r.returncode:
        sys.exit(f"adb {' '.join(args)} failed:\n{r.stdout}{r.stderr}")
    return r.stdout.strip()


def shell(*args):
    return sh("shell", *args)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--build", action="store_true")
    ap.add_argument("--serial")
    a = ap.parse_args()
    dev = ["-s", a.serial] if a.serial else []

    devices = [l.split()[0] for l in sh("devices").splitlines()[1:] if l.endswith("device")]
    if not devices:
        sys.exit("no authorized device. Plug in the phone, enable USB debugging, and accept the prompt.")

    if a.build or not APK.exists():
        gradlew = "gradlew.bat" if platform.system() == "Windows" else "./gradlew"
        print("building the debug APK...")
        if subprocess.run([gradlew, ":app:assembleDebug"], cwd=ROOT / "android").returncode:
            sys.exit("gradle build failed (need the Android SDK and JDK 17)")
    print(f"installing {APK.name}...")
    print(sh(*dev, "install", "-r", str(APK)))
    # Uninstalling wipes the app's storage: put Laya back if it's missing (a fresh install needs it).
    have = sh(*dev, "shell", "run-as", "dev.layatest.agent", "ls", "files", check=False)
    from bench_device import MODELS, push_into_files  # sibling script; shares the shell->run-as copy
    for name in ("laya-android.nb8-h-e16.onnx", "tokenizer.json"):
        if name not in have:
            src = MODELS / name
            if not src.exists():
                sys.exit(f"{name} isn't on the phone or in models/. Build it: scripts/export_onnx.py, then speed_eval.py quantize nb8-h-e16.")
            push_into_files(src, name)

    sh(*dev, "forward", f"tcp:{PORT}", "localabstract:layatest_agent")
    sh(*dev, "shell", "am", "start", "-n", "dev.layatest.agent/.MainActivity")
    print(f"\nInstalled with Laya on the phone; bridge forwarded on tcp:{PORT}.")
    print("Approve Friday's access on the phone: Tools > Permissions. The bridge connects after Accessibility is enabled.")
    print('run a task:  uv run python scripts/live_adb.py --app "Open the Clock app and go to the Timer tab"')


if __name__ == "__main__":
    main()
