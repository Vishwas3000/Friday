"""Score WAV files with Friday's on-phone wake word (D-035, research/16): push them into the app and run the
bridge "wake_test" on each. The WAVs are deleted from the phone afterwards (they may be the owner's voice).

    uv run python scripts/wake_test.py WAV_DIR       # every *.wav in WAV_DIR: 16 kHz, mono, 16-bit PCM

Name files PHRASE__SPEAKER.wav (e.g. hey_friday__samantha.wav) to get a per-phrase summary. On a Mac:
    say -v Samantha -o hey_friday__samantha.wav --data-format=LEI16@16000 "hey friday"
Needs the app installed with the bridge forwarded (scripts/setup_phone.py).
"""
import argparse
import json
import shutil
import socket
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

PKG = "dev.layatest.agent"
TMP = "/data/local/tmp/friday_wake"
adb = shutil.which("adb") or sys.exit("adb not found on PATH")


def call(**cmd):
    f = socket.create_connection(("127.0.0.1", 8765), timeout=60).makefile("rw")
    f.write(json.dumps(cmd) + "\n")
    f.flush()
    return json.loads(f.readline())


def shell(script):
    r = subprocess.run([adb, "shell", script], capture_output=True, text=True)
    if r.returncode:
        sys.exit(f"adb shell failed: {r.stderr}{r.stdout}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("wav_dir", type=Path)
    wavs = sorted(ap.parse_args().wav_dir.glob("*.wav")) or sys.exit("no .wav files there")
    shell(f"rm -rf {TMP} && mkdir -p {TMP}")
    if subprocess.run([adb, "push", *map(str, wavs), TMP], capture_output=True).returncode:
        sys.exit("adb push failed")
    # adb (shell uid) can read /data/local/tmp; run-as (app uid) can write filesDir: pipe one into the other
    shell(f"run-as {PKG} mkdir -p files/wake && cd {TMP} && "
          f"for f in *.wav; do run-as {PKG} sh -c \"cat > 'files/wake/$f'\" < \"$f\"; done; rm -rf {TMP}")
    by_phrase = defaultdict(list)
    try:
        for w in wavs:
            r = call(cmd="wake_test", wav=f"wake/{w.name}")
            if "error" in r:
                sys.exit(f"{w.name}: {r['error']}")
            print(f"{w.stem:40s} max {r['score']:.3f}  held {r['held']:.3f}  {'FIRES' if r['fires'] else '-'}")
            by_phrase[w.stem.split("__")[0]].append(r)
    finally:
        shell(f"run-as {PKG} rm -rf files/wake")
    print("\nphrase            fires   max score (median)   ms/frame")
    for p, rs in sorted(by_phrase.items()):
        s = sorted(r["score"] for r in rs)
        ms = sum(r["ms_per_frame"] for r in rs) / len(rs)
        print(f"{p:16s} {sum(r['fires'] for r in rs):3d}/{len(rs):<3d}  {s[-1]:.3f} ({s[len(s) // 2]:.3f})        {ms:.1f}")


if __name__ == "__main__":
    main()
