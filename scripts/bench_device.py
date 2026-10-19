"""Push a model + fixture into the app and run the on-device ORT benchmark (Phase-2 gate).

    uv run python scripts/bench_device.py                       # push + bench (4 threads, CPU)
    uv run python scripts/bench_device.py --xnnpack --threads 6 # try the XNNPACK EP
    uv run python scripts/bench_device.py --no-push             # re-bench without re-pushing

Copies into the app's private filesDir with a shell->run-as pipe (adb can't write there directly, but
the debug app can read its own stdin). Then sends {"cmd":"bench"} over the bridge and prints the result.
"""
import argparse
import json
import shutil
import socket
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODELS = ROOT / "models"
PKG = "dev.layatest.agent"
PORT = 8765
adb = shutil.which("adb") or sys.exit("adb not found on PATH")


def sh(*a):
    return subprocess.run([adb, *a], capture_output=True, text=True)


def push_into_files(local: Path, name: str):
    tmp = "/data/local/tmp/%s" % name
    print("push %s (%.0f MB) ..." % (local.name, local.stat().st_size / 1e6))
    r = sh("push", str(local), tmp)
    if r.returncode:
        sys.exit("adb push failed: " + r.stderr)
    sh("shell", "run-as", PKG, "mkdir", "-p", "files")
    # adb (shell uid) can read /data/local/tmp; run-as (app uid) can write filesDir. Pipe shell->run-as.
    r = sh("shell", "run-as %s sh -c 'cat > files/%s' < %s" % (PKG, name, tmp))
    if r.returncode:
        sys.exit("run-as copy failed: " + r.stderr + r.stdout)
    sh("shell", "rm", "-f", tmp)
    print("  " + sh("shell", "run-as", PKG, "ls", "-l", "files/%s" % name).stdout.strip())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--iters", type=int, default=10)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--xnnpack", action="store_true")
    ap.add_argument("--lowmem", action="store_true")
    ap.add_argument("--pin", action="store_true", help="pin ORT's threads to the fast cores")
    ap.add_argument("--model", default="laya-android.nb8-h-e16.onnx")  # what the app runs (LayaEngine.MODEL)
    ap.add_argument("--decide", action="store_true", help="full on-device tokenize+build+ORT+decode on the fixture")
    ap.add_argument("--no-push", action="store_true")
    # a student needs its own expected logits (research/28: models/fixture-s11.json); the app always reads fixture.json
    ap.add_argument("--fixture", default="fixture.json")
    a = ap.parse_args()

    model, fixture = MODELS / a.model, MODELS / a.fixture
    assert model.exists() and fixture.exists(), "run export_onnx.py, quantize_onnx.py, make_fixture.py first"
    if not a.no_push:
        push_into_files(model, model.name)
        push_into_files(fixture, "fixture.json")
        if a.decide:
            tok = MODELS / "tokenizer.json"
            assert tok.exists(), "copy tokenizer.json into models/ first"
            push_into_files(tok, tok.name)

    sh("forward", "tcp:%d" % PORT, "localabstract:layatest_agent")
    cmd = ({"cmd": "decide_test", "model": a.model} if a.decide else
           {"cmd": "bench", "iters": a.iters, "threads": a.threads, "xnnpack": a.xnnpack,
            "lowmem": a.lowmem, "model": a.model, "pin": a.pin})
    try:
        conn = socket.create_connection(("127.0.0.1", PORT), timeout=900)
    except OSError:
        sys.exit("can't reach the bridge — is 'Friday' enabled in Accessibility? (run scripts/setup_phone.py)")
    with conn.makefile("rw") as f:
        print("running bench (loading %s on the phone)..." % a.model)
        f.write(json.dumps(cmd) + "\n")
        f.flush()
        reply = json.loads(f.readline())
    if "error" in reply:
        sys.exit("bench error: " + reply["error"])
    print(json.dumps(reply, indent=2))


if __name__ == "__main__":
    main()
