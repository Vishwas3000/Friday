#!/usr/bin/env python3
"""Copy and verify a remote progress archive; endpoints and credentials are runtime arguments."""
import argparse
import fcntl
import json
import os
import re
import signal
import stat
import subprocess
import tempfile
import threading
import warnings
import zipfile
from datetime import datetime, timezone
from pathlib import Path

from plot_prepared import validate

LIMIT = 20 * 1024 * 1024
REQUIRED = {"metrics.json", "health.json", "overview.png", "overview.svg", "overview.html"}
ALLOWED = REQUIRED | {"alerts.jsonl"}


def reject(value):
    raise ValueError("Invalid JSON constant: " + value)


def unique(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("Duplicate JSON key: " + key)
        value[key] = item
    return value


def parsed(raw):
    return json.loads(raw, object_pairs_hook=unique, parse_constant=reject)


def checked_archive(path, expected):
    if path.stat().st_size > LIMIT:
        raise ValueError("Compressed archive exceeds 20 MiB")
    with zipfile.ZipFile(path) as archive:
        members = archive.infolist()
        names = [member.filename for member in members]
        if len(set(names)) != len(names) or not REQUIRED <= set(names) or not set(names) <= ALLOWED:
            raise ValueError("Missing, duplicate, unsafe or unexpected archive members")
        if sum(member.file_size for member in members) > LIMIT:
            raise ValueError("Uncompressed archive exceeds 20 MiB")
        payload = {}
        for member in members:
            mode = member.external_attr >> 16
            if member.is_dir() or stat.S_ISLNK(mode) or stat.S_ISDIR(mode) or member.flag_bits & 1:
                raise ValueError("Directories, symlinks and encrypted members are unsupported")
            with archive.open(member) as stream:
                data = stream.read(member.file_size + 1)
            if len(data) != member.file_size:
                raise ValueError("Archive member length mismatch")
            payload[member.filename] = data
    metrics = validate(parsed(payload["metrics.json"]))
    if metrics.get("config_sha256") != expected or metrics.get("monitor_status") not in {"running", "complete", "failed"}:
        raise ValueError("Archive configuration or monitor status does not match")
    health = {"captured_utc": metrics["captured_utc"], "monitor_status": metrics["monitor_status"], **metrics["health"]}
    if parsed(payload["health.json"]) != health:
        raise ValueError("Health file disagrees with metrics")
    return metrics, payload


def publish(out, payload):
    # Stage every byte before replacing any published file. HTML embeds its own SVG.
    with tempfile.TemporaryDirectory(dir=out, prefix=".publish-") as temporary:
        stage = Path(temporary)
        for name, data in payload.items():
            with (stage / name).open("wb") as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
        for name in sorted(set(payload) - {"overview.html"}) + ["overview.html"]:
            (stage / name).replace(out / name)


def fetch(args):
    with tempfile.TemporaryDirectory(dir=args.out, prefix=".download-") as temporary:
        archive = Path(temporary) / "bundle.zip"
        command = ["scp", "-q", "-P", str(args.port), "-i", str(args.identity),
            "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes", "-o", "ConnectTimeout=15",
            "-o", "StrictHostKeyChecking=yes", "-o", "UserKnownHostsFile=" + str(args.known_hosts),
            "--", args.host + ":" + args.remote_bundle, str(archive)]
        subprocess.run(command, capture_output=True, text=True, check=True, timeout=45)
        metrics, payload = checked_archive(archive, args.config_sha256)
        publish(args.out, payload)
        return metrics


def log(event, **fields):
    print(json.dumps({"captured_utc": datetime.now(timezone.utc).isoformat(), "event": event, **fields}, allow_nan=False), flush=True)


def sync(args):
    args.out.mkdir(parents=True, exist_ok=True)
    stop = threading.Event()
    for kind in (signal.SIGTERM, signal.SIGINT):
        signal.signal(kind, lambda number, frame: stop.set())
    with (args.out / ".sync.lock").open("a") as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        while not stop.is_set():
            try:
                metrics = fetch(args)
                status = metrics["monitor_status"]
                log("synced", monitor_status=status, step=metrics["progress"].get("step"))
                if args.once or status in {"complete", "failed"}:
                    return 0
            except Exception as error:
                if isinstance(error, subprocess.CalledProcessError):
                    message = "SCP exited with status " + str(error.returncode)
                elif isinstance(error, subprocess.TimeoutExpired):
                    message = "SCP exceeded its 45-second timeout"
                else:
                    message = type(error).__name__ + ": " + str(error)
                log("sync_error", error=message[:500])
                if args.once:
                    return 1
            stop.wait(args.interval)
    log("sync_stopped")
    return 0


def self_check():
    from unittest.mock import patch
    snapshot = {"captured_utc": "2026-10-21T12:00:00+00:00", "config_sha256": "a" * 64,
        "status": "running", "monitor_status": "running", "health": {"level": "ok", "messages": []}, "progress": {"step": 1}}
    health = {"captured_utc": snapshot["captured_utc"], "monitor_status": "running", **snapshot["health"]}
    payload = {"metrics.json": json.dumps(snapshot).encode(), "health.json": json.dumps(health).encode(),
               "overview.html": b"<html><svg></svg></html>", "overview.png": b"png", "overview.svg": b"<svg></svg>"}
    with tempfile.TemporaryDirectory() as temporary:
        out = Path(temporary)
        archive = out / "bundle.zip"
        def bundle(entries):
            with warnings.catch_warnings(), zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as target:
                warnings.simplefilter("ignore", UserWarning)
                for name, data in entries:
                    target.writestr(name, data)
        bundle(payload.items())
        assert checked_archive(archive, "a" * 64)[0] == snapshot
        order, replace = [], Path.replace
        def recorded(path, destination):
            order.append(destination.name)
            return replace(path, destination)
        with patch.object(Path, "replace", recorded):
            publish(out, payload)
        assert order[-1] == "overview.html" and all((out / name).read_bytes() == raw for name, raw in payload.items())
        for extra, expected in (([], "b" * 64), ([("../escape", b"x")], "a" * 64),
                ([("unexpected.txt", b"x")], "a" * 64), ([("metrics.json", b"{}")], "a" * 64),
                ([("alerts.jsonl", b"x" * (LIMIT + 1))], "a" * 64)):
            bundle(list(payload.items()) + extra)
            try:
                checked_archive(archive, expected)
            except (ValueError, zipfile.BadZipFile):
                pass
            else:
                raise AssertionError("Unsafe archive accepted")
            assert (out / "overview.html").read_bytes() == payload["overview.html"]
    print("Sync self-check passed: allowlist, duplicate/path/size/config rejection, staged atomic files and HTML-last publication")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-check", action="store_true")
    for name in ("host", "remote-bundle", "config-sha256"):
        parser.add_argument("--" + name)
    for name in ("identity", "known-hosts", "out"):
        parser.add_argument("--" + name, type=Path)
    parser.add_argument("--port", type=int)
    parser.add_argument("--interval", type=int, default=60)
    parser.add_argument("--once", action="store_true")
    args = parser.parse_args()
    if args.self_check:
        self_check()
        return 0
    if (not all((args.host, args.port, args.identity, args.known_hosts, args.remote_bundle, args.config_sha256, args.out))
            or not 0 < args.port < 65536 or args.interval < 1 or not re.fullmatch("[0-9a-f]{64}", args.config_sha256)):
        parser.error("All connection/output arguments, a valid port and configuration SHA256 are required")
    return sync(args)


if __name__ == "__main__":
    raise SystemExit(main())
