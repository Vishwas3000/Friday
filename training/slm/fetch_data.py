#!/usr/bin/env python3
"""Fetch pinned public TRAIN dialogues for future adapters; no current-pilot import."""
import argparse
import concurrent.futures
import fcntl
import hashlib
import json
import re
import tempfile
import time
import urllib.error
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parent
MAX_BYTES = 1_000_000_000
SOURCES = [
    {"name": "sgd", "repo": "google-research-datasets/dstc8-schema-guided-dialogue",
     "revision": "e852981ae34990f4358979625854259302feaa78", "license": "CC-BY-SA-4.0",
     "prefix": "train/", "extras": ["train/schema.json", "LICENSE.txt", "README.md"]},
    {"name": "multiwoz22", "repo": "budzianowski/multiwoz",
     "revision": "fe0c8e65cfcd8462bd33c86e35f21addc84ca82b", "license": "MIT (repository)",
     "prefix": "data/MultiWOZ_2.2/train/",
     "extras": ["data/MultiWOZ_2.2/schema.json", "LICENSE", "data/MultiWOZ_2.2/README.md"]},
]


def sha256(raw):
    return hashlib.sha256(raw).hexdigest()


def git_blob(raw):
    return hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()


def atomic_json(path, value):
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n", encoding="utf-8")
    temporary.replace(path)


def get(url, maximum):
    for attempt in range(3):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "Friday-public-dataset-research"})
            with urllib.request.urlopen(request, timeout=30) as response:
                raw = response.read(maximum + 1)
            if len(raw) > maximum:
                raise ValueError("Response exceeded byte bound: " + url)
            return raw
        except (urllib.error.URLError, TimeoutError) as error:
            if isinstance(error, urllib.error.HTTPError) and error.code not in {429, 500, 502, 503, 504}:
                raise
            if attempt == 2:
                raise
            time.sleep(attempt + 1)


def inventory(source, document):
    if document.get("truncated") or not isinstance(document.get("tree"), list):
        raise ValueError("Incomplete GitHub tree inventory")
    selected = []
    pattern = re.compile(re.escape(source["prefix"]) + r"dialogues_[0-9]{3}\.json")
    for item in document["tree"]:
        path = item["path"]
        if not (pattern.fullmatch(path) or path in source["extras"]):
            continue
        if (item.get("type") != "blob" or item.get("mode") not in {"100644", "100755"}
                or PurePosixPath(path).is_absolute() or ".." in PurePosixPath(path).parts
                or type(item.get("size")) is not int or item["size"] < 1
                or not re.fullmatch(r"[a-f0-9]{40}", item.get("sha", ""))):
            raise ValueError("Invalid pinned source file: " + path)
        selected.append({"dataset": source["name"], "repo": source["repo"], "revision": source["revision"],
                         "license": source["license"], "source_path": path,
                         "path": source["name"] + "/" + path, "bytes": item["size"], "git_blob_sha1": item["sha"],
                         "url": "https://raw.githubusercontent.com/" + source["repo"] + "/" + source["revision"] + "/" + path,
                         "dialogue_shard": bool(pattern.fullmatch(path))})
    found = {item["source_path"] for item in selected}
    if not set(source["extras"]) <= found or not any(item["dialogue_shard"] for item in selected):
        raise ValueError("Pinned source lacks expected train data/schema/notices")
    return sorted(selected, key=lambda item: item["path"])


def counts(raw, dialogue_shard):
    if not dialogue_shard:
        return {}
    dialogues = json.loads(raw)
    if not isinstance(dialogues, list) or not dialogues:
        raise ValueError("Expected a nonempty dialogue array")
    services, speakers = Counter(), Counter()
    ids = []
    for row in dialogues:
        if not isinstance(row.get("dialogue_id"), str) or not isinstance(row.get("turns"), list):
            raise ValueError("Malformed dialogue identity/turns")
        ids.append(row["dialogue_id"])
        services.update(set(row.get("services", [])))
        speakers.update(turn["speaker"] for turn in row["turns"])
    if len(set(ids)) != len(ids):
        raise ValueError("Repeated dialogue ID within source shard")
    return {"split": "train", "dialogues": len(dialogues), "turns": sum(speakers.values()),
            "speakers": dict(sorted(speakers.items())), "services": dict(sorted(services.items()))}


def local_file(output, relative):
    target = output / relative
    if not target.resolve().is_relative_to(output.resolve()):
        raise ValueError("Download path escapes output directory")
    return target


def fetch_file(output, item, previous):
    target = local_file(output, item["path"])
    raw = target.read_bytes() if target.is_file() else None
    existed = raw is not None
    valid = (raw is not None and len(raw) == item["bytes"] and git_blob(raw) == item["git_blob_sha1"]
             and (previous is None or sha256(raw) == previous["sha256"]))
    if not valid:
        raw = get(item["url"], item["bytes"])
        if len(raw) != item["bytes"] or git_blob(raw) != item["git_blob_sha1"]:
            raise ValueError("Pinned source byte/blob verification failed: " + item["path"])
    detail = {**item, "sha256": sha256(raw), **counts(raw, item["dialogue_shard"]),
              "cache": "reused_verified" if valid else "replaced_mismatch" if existed else "downloaded"}
    if not valid:
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(target.name + ".part")
        temporary.write_bytes(raw)
        temporary.replace(target)
    return detail


def totals(files):
    groups = {}
    for item in files:
        group = groups.setdefault(item["dataset"], {"files": 0, "bytes": 0, "train_shards": 0,
            "dialogues": 0, "turns": 0, "speakers": Counter(), "services": Counter()})
        group["files"] += 1
        group["bytes"] += item["bytes"]
        group["train_shards"] += item["dialogue_shard"]
        group["dialogues"] += item.get("dialogues", 0)
        group["turns"] += item.get("turns", 0)
        group["speakers"].update(item.get("speakers", {}))
        group["services"].update(item.get("services", {}))
    return {name: {**value, "speakers": dict(sorted(value["speakers"].items())),
                   "services": dict(sorted(value["services"].items()))} for name, value in sorted(groups.items())}


def run(args):
    args.output_dir.mkdir(parents=True, exist_ok=True)
    with (args.output_dir / ".fetch.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        entries = []
        for source in SOURCES:
            document = json.loads(get("https://api.github.com/repos/" + source["repo"] + "/git/trees/"
                                      + source["revision"] + "?recursive=1", 4 * 1024 * 1024))
            entries.extend(inventory(source, document))
        expected_bytes = sum(item["bytes"] for item in entries)
        if expected_bytes > args.max_bytes:
            raise ValueError("Pinned train inventory exceeds total byte cap before download: " + str(expected_bytes))
        inventory_hash = sha256(json.dumps(entries, sort_keys=True, separators=(",", ":")).encode())
        path = args.output_dir / "manifest.json"
        if path.exists():
            manifest = json.loads(path.read_bytes())
            if manifest.get("inventory_sha256") != inventory_hash:
                raise ValueError("Existing manifest belongs to another pinned inventory")
        else:
            manifest = {"version": 1, "created_utc": datetime.now(timezone.utc).isoformat(),
                        "purpose": "Public TRAIN raw sources for a future grounded adapter; not current-pilot training data",
                        "sources": SOURCES, "inventory_sha256": inventory_hash, "files": {}}
        manifest.update({"status": "downloading", "expected_files": len(entries), "expected_bytes": expected_bytes,
                         "max_bytes": args.max_bytes, "workers": args.workers,
                         "fetcher_sha256": sha256(Path(__file__).read_bytes())})
        manifest.pop("error", None)
        atomic_json(path, manifest)
        print(json.dumps({"stage": "inventory", "files": len(entries), "bytes": expected_bytes}), flush=True)
        started = time.perf_counter()
        try:
            # Every existing file is rehashed against both the pinned Git blob and its recorded SHA256.
            with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
                pending = {pool.submit(fetch_file, args.output_dir, item, manifest["files"].get(item["path"])): item for item in entries}
                completed = 0
                for future in concurrent.futures.as_completed(pending):
                    detail = future.result()
                    manifest["files"][detail["path"]] = detail
                    manifest["completed_files"] = completed = completed + 1
                    atomic_json(path, manifest)
                    print(json.dumps({"stage": "download", "completed": completed, "total": len(entries),
                                      "path": detail["path"], "cache": detail["cache"]}), flush=True)
            manifest.update({"status": "complete", "completed_utc": datetime.now(timezone.utc).isoformat(),
                             "totals": totals(list(manifest["files"].values())), "last_invocation_s": time.perf_counter() - started})
        except BaseException as error:
            manifest.update({"status": "failed", "error": type(error).__name__ + ": " + str(error)})
            raise
        finally:
            atomic_json(path, manifest)
        print(json.dumps({"manifest": str(path), "totals": manifest["totals"]}), flush=True)


def self_check():
    source = SOURCES[0]
    names = ["train/dialogues_001.json", "train/schema.json", "LICENSE.txt", "README.md",
             "dev/dialogues_001.json", "test/dialogues_001.json", "train/not-dialogues.json"]
    document = {"truncated": False, "tree": [{"path": name, "type": "blob", "mode": "100644", "size": 7, "sha": "a" * 40} for name in names]}
    selected = inventory(source, document)
    assert len(selected) == 4 and sum(item["dialogue_shard"] for item in selected) == 1
    assert all(not re.search(r"/(dev|test)/", item["path"]) for item in selected)
    assert git_blob(b"hello") == "b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0"
    raw = json.dumps([{"dialogue_id": "train-1", "services": ["Hotels_1"],
                      "turns": [{"speaker": "USER"}, {"speaker": "SYSTEM"}]}]).encode()
    measured = counts(raw, True)
    assert measured["dialogues"] == 1 and measured["turns"] == 2 and measured["services"] == {"Hotels_1": 1}
    with tempfile.TemporaryDirectory(prefix="friday-source-check-") as directory:
        output = Path(directory)
        relative = "sgd/train/dialogues_001.json"
        target = output / relative
        target.parent.mkdir(parents=True)
        target.write_bytes(raw)
        item = {"path": relative, "bytes": len(raw), "git_blob_sha1": git_blob(raw), "dialogue_shard": True}
        reused = fetch_file(output, item, {"sha256": sha256(raw)})
        assert reused["cache"] == "reused_verified" and reused["sha256"] == sha256(raw)
    group = totals([{**selected[0], **measured, "dialogue_shard": True}])["sgd"]
    assert group["dialogues"] == 1 and group["turns"] == 2 and group["train_shards"] == 1
    try:
        local_file(Path("/tmp/fixture-download"), "../escape.json")
    except ValueError:
        pass
    else:
        raise AssertionError("Escaping output path was accepted")
    try:
        inventory(source, {**document, "truncated": True})
    except ValueError:
        pass
    else:
        raise AssertionError("Incomplete inventory was accepted")
    print("Dataset fetch self-check passed: train-only routes, pinned blobs, SHA-verified cache resume, counts, paths and bounds")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-check", action="store_true")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "out/external-full")
    parser.add_argument("--workers", type=int, choices=range(1, 9), default=8)
    parser.add_argument("--max-bytes", type=int, default=MAX_BYTES)
    args = parser.parse_args()
    if not 0 < args.max_bytes <= MAX_BYTES:
        parser.error("max-bytes must be positive and at most 1,000,000,000")
    if args.self_check:
        self_check()
    else:
        run(args)
