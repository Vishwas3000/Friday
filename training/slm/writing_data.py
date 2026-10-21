#!/usr/bin/env python3
"""Pinned publisher TRAIN writing references for a future SFT adapter, never the current pilot."""
import argparse
import hashlib
import json
import re
from collections import Counter
from pathlib import Path

from fetch_data import atomic_json, get
from prompts import PROMPT_VERSION, build_messages
from regression import NUMBER, NEGATIVE, identifiers, parse_json

ROOT = Path(__file__).resolve().parent
MAX_BYTES = 150_000_000
COEDIT_REVISION = "e9a255c33ef910bc33a9d2b522653fa87521583e"
DIALOGSUM_REVISION = "848cc8c05e0a04def79326539de827f1b28786a0"
FILES = [
    {"dataset": "grammarly/coedit", "revision": COEDIT_REVISION, "license": "Apache-2.0 (dataset card)",
     "path": "coedit/train.jsonl", "bytes": 19695735,
     "sha256": "2913249158d6a178dc638e870212ff8a432d128eb6b4bdbe969ee805e6063ce3",
     "url": "https://huggingface.co/datasets/grammarly/coedit/resolve/" + COEDIT_REVISION + "/train.jsonl?download=true"},
    {"dataset": "grammarly/coedit", "revision": COEDIT_REVISION, "license": "Apache-2.0 (dataset card)",
     "path": "coedit/README.md", "bytes": 1875,
     "sha256": "6ade266ee39776a2e283870eab9c0c5e4099d88eefe951711a110eb045014efd",
     "url": "https://huggingface.co/datasets/grammarly/coedit/resolve/" + COEDIT_REVISION + "/README.md"},
    {"dataset": "cylnlp/dialogsum", "revision": DIALOGSUM_REVISION, "license": "CC-BY-NC-SA-4.0",
     "path": "dialogsum/train.jsonl", "bytes": 12068822,
     "sha256": "660238631152c062e67186da6622c1e8925579e82563d66d611abb9dcc19bb72",
     "url": "https://raw.githubusercontent.com/cylnlp/dialogsum/" + DIALOGSUM_REVISION + "/DialogSum_Data/dialogsum.train.jsonl"},
    {"dataset": "cylnlp/dialogsum", "revision": DIALOGSUM_REVISION, "license": "CC-BY-NC-SA-4.0",
     "path": "dialogsum/README.md", "bytes": 4201,
     "sha256": "9bd01bdcbe9e53c61f47984110c9e7ccd997ed27b02b983cd184203da9bbbd47",
     "url": "https://raw.githubusercontent.com/cylnlp/dialogsum/" + DIALOGSUM_REVISION + "/README.md"},
]


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def group_key(text):
    return sha(re.sub(r"\s+", " ", text).strip().casefold().encode())


def split_for(group):
    return "validation" if int(group[:16], 16) % 100 < 5 else "train"


def rejection(source, target, instruction):
    if not source.strip() or not target.strip():
        return "empty_text"
    if len(instruction) + len(source) > 2800 or len(target.split()) > 80:
        return "length_limit"
    if re.sub(r"\s+", " ", source).strip() == re.sub(r"\s+", " ", target).strip():
        return "unchanged_target"
    if re.search(r"<\|[^>]*\|>|</?think>", source + target, re.I):
        return "reserved_control_marker"
    if set(NUMBER.findall(source)) != set(NUMBER.findall(target)):
        return "numeric_token_change"
    if identifiers(source) != identifiers(target):
        return "identifier_change"
    if bool(NEGATIVE.search(source)) != bool(NEGATIVE.search(target)):
        return "negation_canary_change"
    return None


def prepare(args):
    args.raw.mkdir(parents=True, exist_ok=True)
    for spec in FILES:
        path = args.raw / spec["path"]
        if not path.exists():
            if not args.fetch:
                raise ValueError("Missing pinned source; rerun with --fetch: " + str(path))
            raw = get(spec["url"], spec["bytes"])
            if len(raw) != spec["bytes"] or sha(raw) != spec["sha256"]:
                raise ValueError("Downloaded source failed size/SHA256: " + spec["path"])
            path.parent.mkdir(parents=True, exist_ok=True)
            temporary = path.with_name(path.name + ".part")
            temporary.write_bytes(raw)
            temporary.replace(path)
        raw = path.read_bytes()
        if len(raw) != spec["bytes"] or sha(raw) != spec["sha256"]:
            raise ValueError("Existing source failed size/SHA256: " + spec["path"])
    if args.output.exists() and any(args.output.iterdir()):
        raise ValueError("Choose an empty output directory; existing prepared data is never overwritten")
    args.output.mkdir(parents=True, exist_ok=True)
    stats, rejected = Counter(), Counter()
    seen_pairs, groups = set(), {"train": set(), "validation": set()}
    raw_bytes = sum(spec["bytes"] for spec in FILES)
    output_bytes = 0
    handles = {split: (args.output / (split + ".jsonl.part")).open("wb") for split in groups}
    try:
        for spec in FILES:
            if not spec["path"].endswith(".jsonl"):
                continue
            with (args.raw / spec["path"]).open("rb") as source_file:
                for index, line in enumerate(source_file, 1):
                    if not line.strip():
                        continue
                    original = parse_json(line)
                    dataset = spec["dataset"]
                    stats[dataset + "/source_rows"] += 1
                    if dataset == "grammarly/coedit":
                        if original["task"] != "gec":
                            rejected[dataset + "/other_task_deferred"] += 1
                            continue
                        instruction, separator, source = original["src"].partition(": ")
                        if not separator or not instruction or not source:
                            raise ValueError("Unexpected CoEdIT instruction/source format")
                        target, identity, kind = original["tgt"], str(original["_id"]), "rewrite"
                    else:
                        instruction = "Summarize this dialogue briefly from an observer's perspective, retaining its main facts and uncertainty."
                        source, target, identity, kind = original["dialogue"], original["summary"], original["fname"], "summary"
                    reason = rejection(source, target, instruction)
                    if reason:
                        rejected[dataset + "/" + reason] += 1
                        continue
                    request = {"kind": kind, "instruction": instruction, "text": source}
                    messages = build_messages(request)
                    response = encoded({"text": target})
                    input_hash, target_hash = sha(encoded(messages).encode()), sha(response.encode())
                    pair = (input_hash, target_hash)
                    if pair in seen_pairs:
                        rejected[dataset + "/duplicate_input_target"] += 1
                        continue
                    seen_pairs.add(pair)
                    group = group_key(source)
                    split = split_for(group)
                    groups[split].add(group)
                    row = {"id": "WEXT-" + ("coedit" if kind == "rewrite" else "dialogsum") + "-" + identity + "-" + str(index),
                        "kind": kind, "split": split, "messages": messages, "response": response,
                        "provenance": {"dataset": dataset, "revision": spec["revision"], "license": spec["license"],
                            "source_split": "train", "source_file": spec["path"], "source_file_sha256": spec["sha256"],
                            "source_record_id": identity, "source_line": index, "source_record_sha256": sha(line),
                            "source_group_sha256": group, "input_sha256": input_hash, "response_sha256": target_hash,
                            "label_source": "publisher reference target, not Qwen or Friday-generated",
                            "semantic_review": "not performed; lexical filters are not semantic verification"}}
                    data = (encoded(row) + "\n").encode()
                    if raw_bytes + output_bytes + len(data) > MAX_BYTES:
                        raise ValueError("Raw plus prepared files exceed the 150 MB experiment cap")
                    handles[split].write(data)
                    output_bytes += len(data)
                    stats[dataset + "/accepted"] += 1
                    stats[split + "/rows"] += 1
        if groups["train"] & groups["validation"]:
            raise ValueError("Source-group leakage across train/validation")
    finally:
        for handle in handles.values():
            handle.close()
    for split in groups:
        (args.output / (split + ".jsonl.part")).replace(args.output / (split + ".jsonl"))
    manifest = {"version": 1, "status": "complete", "purpose": "Future writing SFT data only; no current-pilot import or training",
        "protocol": "messages plus JSON-string publisher-reference response; explicit future loader required",
        "sources": FILES, "prompt_version": PROMPT_VERSION, "prompt_source_sha256": sha((ROOT / "prompts.py").read_bytes()),
        "adapter_sha256": sha(Path(__file__).read_bytes()), "counts": dict(stats), "rejections": dict(rejected),
        "source_bytes": raw_bytes, "prepared_bytes": output_bytes, "max_total_bytes": MAX_BYTES,
        "split_policy": "Publisher TRAIN only; 5% validation by normalized casefolded source SHA group; exact input/target pair dedup",
        "groups": {split: len(values) for split, values in groups.items()}, "cross_split_source_groups": 0,
        "limits": ["No semantic verification, tokenizer-length proof, near-duplicate or cross-corpus semantic decontamination",
                   "Summary filters conservatively reject omitted numeric/code/negation canaries, even when a human summary may be valid",
                   "Public publisher references are not automatically correct or verified Friday app outcomes"],
        "files": {split: {"path": split + ".jsonl", "sha256": sha((args.output / (split + ".jsonl")).read_bytes()),
                          "bytes": (args.output / (split + ".jsonl")).stat().st_size} for split in groups}}
    atomic_json(args.output / "manifest.json", manifest)
    print(json.dumps({"manifest": str(args.output / "manifest.json"), "counts": dict(stats), "rejections": dict(rejected),
                      "raw_plus_prepared_bytes": raw_bytes + output_bytes, "source_groups": manifest["groups"]}, indent=2))


def self_check():
    assert rejection("I has received BOX-71 and 3 items.", "I have received BOX-71 and 3 items.", "Fix grammar") is None
    assert rejection("I cannot join at 5 PM.", "I can join at 5 PM.", "Rewrite") == "negation_canary_change"
    assert rejection("BOX-71 has 3 items.", "BOX-71 has 4 items.", "Rewrite") == "numeric_token_change"
    assert rejection("A1 has 3 items.", "B1 has 3 items.", "Rewrite") == "identifier_change"
    assert rejection("Unchanged.", "Unchanged.", "Rewrite") == "unchanged_target"
    assert group_key("One  source\ntext") == group_key("one source text")
    assert split_for(group_key("same source")) == split_for(group_key("SAME SOURCE"))
    print("Writing-data self-check passed: pinned-reference filters and deterministic source grouping")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-check", action="store_true")
    parser.add_argument("--fetch", action="store_true", help="Download missing pinned TRAIN files/notices only")
    parser.add_argument("--raw", type=Path, default=ROOT / "out/writing-external")
    parser.add_argument("--output", type=Path, default=ROOT / "out/writing-external/prepared")
    args = parser.parse_args()
    self_check() if args.self_check else prepare(args)
