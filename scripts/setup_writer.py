"""Fetch the pinned Writer runtime/model; optionally copy verified weights into Friday's private files.

python3 scripts/setup_writer.py --runtime-only
python3 scripts/setup_writer.py --install --serial <adb serial>
python3 scripts/setup_writer.py --qwen --install --serial <adb serial>  # comparison model too
"""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tarfile
import urllib.request
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
MODELS = ROOT / "models/writer"
RUNTIME = ROOT / "android/third_party/llama.cpp"
COMMIT = "01fc3aae846caa644f0cee360f108d4c48e4aaa1"  # b11701
ARCHIVE_SHA256 = "2d4f2e49956220503e76af51e301629be3ad93cd398eb1643d911cb96dbae63d"
MODEL_FILES = {
    "writer.gguf": {
        "repo": "LiquidAI/LFM2.5-1.2B-Instruct-GGUF",
        "revision": "8ed288026e23958ad9dfa92d53ed773a8eee7125",
        "file": "LFM2.5-1.2B-Instruct-QAD-Q4_0.gguf",
        "bytes": 695755488,
        "sha256": "bb741ebb106d543e9de114b843a3d3d73d51c74b5801e69da2abde821a0cb3e1",
    },
    "writer-qwen.gguf": {
        "repo": "unsloth/Qwen3.5-0.8B-GGUF",
        "revision": "6ab461498e2023f6e3c1baea90a8f0fe38ab64d0",
        "file": "Qwen3.5-0.8B-Q8_0.gguf",
        "bytes": 811843840,
        "sha256": "0ad885ffd4bb022fc4f0d33a3308fa108ef8613159d3b3a67e23abca056b7a6c",
    },
}


def sha256(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        while block := f.read(4 << 20):
            h.update(block)
    return h.hexdigest()


def fetch(url, path, expected_hash):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        if sha256(path) != expected_hash:
            raise RuntimeError(f"Checksum mismatch: {path}. Move the file aside before retrying.")
        print(f"Verified {path.name}", flush=True)
        return
    part = path.with_suffix(path.suffix + ".part")
    if part.exists() and sha256(part) == expected_hash:
        part.replace(path)
        print(f"Verified {path.name}", flush=True)
        return
    # Resume only when the host explicitly honors Range; otherwise restart the incomplete file.
    offset = part.stat().st_size if part.exists() else 0
    req = urllib.request.Request(url, headers={"Range": f"bytes={offset}-"} if offset else {})
    print(f"Downloading {path.name}", flush=True)
    with urllib.request.urlopen(req, timeout=120) as response:
        append = offset and response.status == 206
        with part.open("ab" if append else "wb") as output:
            shutil.copyfileobj(response, output, length=1 << 20)
    if sha256(part) != expected_hash:
        raise RuntimeError(f"Checksum mismatch: {part}; no model/runtime was installed.")
    part.replace(path)
    print(f"Verified {path.name}", flush=True)


def setup_runtime():
    archive = MODELS / "llama-b11701.tar.gz"
    fetch(f"https://codeload.github.com/ggml-org/llama.cpp/tar.gz/{COMMIT}", archive, ARCHIVE_SHA256)
    marker = RUNTIME / ".friday-revision"
    if RUNTIME.exists():
        if marker.exists() and marker.read_text().strip() == COMMIT:
            return
        raise RuntimeError(f"Unverified runtime directory: {RUNTIME}. Move it aside before setup.")
    staged = RUNTIME.with_name("llama.cpp.download")
    if staged.exists():
        shutil.rmtree(staged)  # only this script's incomplete extraction, never another checkout
    staged.mkdir(parents=True)
    with tarfile.open(archive) as bundle:
        prefix = f"llama.cpp-{COMMIT}"
        for member in bundle.getmembers():
            rel = PurePosixPath(member.name).relative_to(prefix)
            if rel.is_absolute() or ".." in rel.parts:
                raise RuntimeError("Unsafe runtime archive path")
            if str(rel) == ".":
                continue
            dest = staged.joinpath(*rel.parts)
            if member.isdir():
                dest.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                dest.parent.mkdir(parents=True, exist_ok=True)
                with bundle.extractfile(member) as source, dest.open("wb") as output:
                    shutil.copyfileobj(source, output)
            # Symlinks/devices are not needed to build this CPU-only runtime.
    (staged / ".friday-revision").write_text(COMMIT + "\n")
    staged.replace(RUNTIME)


def install_model(path, name, expected, serial, adb_executable="adb"):
    adb = shutil.which(adb_executable)
    if not adb:
        raise RuntimeError("adb executable not found; set --adb or FRIDAY_ADB")
    base = [adb] + (["-s", serial] if serial else [])

    def run(*args):
        return subprocess.run(base + list(args), check=True, capture_output=True, text=True).stdout.strip()

    remote = f"/data/local/tmp/friday-{name}"
    current = run("shell", "run-as", "dev.layatest.agent", "sh", "-c", f"'sha256sum files/{name} 2>/dev/null || true'")
    if current.startswith(expected):
        print(f"Phone already has verified {name}", flush=True)
        return
    run("push", str(path), remote)
    try:
        run("shell", "run-as", "dev.layatest.agent", "mkdir", "-p", "files")
        # Fixed allowlisted filenames; copy atomically so a disconnected push never replaces working weights.
        # shell can read /data/local/tmp; app UID can write filesDir. Direct run-as cp is denied on Android.
        run("shell", f"run-as dev.layatest.agent sh -c 'cat > files/{name}.part' < {remote}")
        actual = run("shell", "run-as", "dev.layatest.agent", "sha256sum", f"files/{name}.part").split()[0]
        if actual != expected:
            raise RuntimeError(f"Phone checksum mismatch for {name}")
        run("shell", "run-as", "dev.layatest.agent", "mv", f"files/{name}.part", f"files/{name}")
    finally:
        run("shell", "rm", "-f", remote)
    print(f"Installed verified {name}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-only", action="store_true")
    parser.add_argument("--qwen", action="store_true", help="also fetch the Qwen comparison model")
    parser.add_argument("--install", action="store_true", help="copy models into the installed debug app")
    parser.add_argument("--adb", default=os.environ.get("FRIDAY_ADB") or "adb",
                        help="adb executable (default: FRIDAY_ADB or adb)")
    parser.add_argument("--serial", default=os.environ.get("FRIDAY_DEVICE_SERIAL") or None,
                        help="device serial (default: FRIDAY_DEVICE_SERIAL or adb selection)")
    args = parser.parse_args()
    setup_runtime()
    if args.runtime_only:
        return
    selected = ["writer.gguf"] + (["writer-qwen.gguf"] if args.qwen else [])
    for name in selected:
        model = MODEL_FILES[name]
        path = MODELS / name
        url = f"https://huggingface.co/{model['repo']}/resolve/{model['revision']}/{model['file']}"
        fetch(url, path, model["sha256"])
        if path.stat().st_size != model["bytes"]:
            raise RuntimeError(f"Wrong model size: {path}")
        (MODELS / (name + ".json")).write_text(json.dumps(model, indent=2) + "\n")
        if args.install:
            install_model(path, name, model["sha256"], args.serial, args.adb)
    print("LFM weights: custom LFM Open License v1.0; model files are separate from the APK.")


if __name__ == "__main__":
    main()
