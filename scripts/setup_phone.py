"""One-command phone setup: install the agent APK, enable its accessibility service, open the adb bridge.

uv run python scripts/setup_phone.py            # install + enable + forward
uv run python scripts/setup_phone.py --build     # also build the debug APK first (needs the Android SDK + JDK 17)

Works on macOS and Windows. Safe to re-run; it appends to the accessibility list, never overwrites it.
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
SERVICE = "dev.layatest.agent/dev.layatest.agent.AgentService"
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

    enabled = shell(*(dev[1:] and dev) or (), "settings", "get", "secure", "enabled_accessibility_services")
    # `dev` already consumed by sh(); re-pass via shell helper below
    enabled = sh("shell", *dev, "settings", "get", "secure", "enabled_accessibility_services")
    current = [s for s in (enabled or "").split(":") if s and s != "null"]
    if SERVICE not in current:
        current.append(SERVICE)
        sh("shell", *dev, "settings", "put", "secure", "enabled_accessibility_services", ":".join(current))
    sh("shell", *dev, "settings", "put", "secure", "accessibility_enabled", "1")

    now = sh("shell", *dev, "settings", "get", "secure", "enabled_accessibility_services")
    if SERVICE not in now:
        sys.exit("could not enable the service. Turn on 'Laya agent' by hand: Settings > Accessibility.")

    sh(*dev, "forward", f"tcp:{PORT}", "localabstract:layatest_agent")
    print(f"\nready. service enabled, bridge on tcp:{PORT}.")
    print('run a task:  uv run python scripts/live_adb.py --app "Open the Clock app and go to the Timer tab"')


if __name__ == "__main__":
    main()
