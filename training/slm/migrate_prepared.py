#!/usr/bin/env python3
"""One explicit, verified checkpoint migration for the token-budget execution fix.

Stop this run before migration. This preserves saved state and data, not bitwise
equivalence of future kernels, rounding or dropout under different microbatches.
"""
import argparse
import copy
import os
import shutil
from datetime import datetime, timezone
from pathlib import Path

from distill import canonical, digest, save_json, weight_hashes
from train_prepared import ROOT, atomic_torch_save, document, file_hash

SOURCE_SHA = "83b7aea989532b30e6cd82b6730d3e5e2ad2cf0a22989f6438352d7bad39fff5"
TARGET_SHA = "b271afcb5d64cceb362cd1c2bc77f2e60ebd00da41088932dbc7c64c76aa2967"


def configuration_after(before):
    if before["data"]["code_hashes"]["train_prepared.py"] != SOURCE_SHA:
        raise ValueError("Only the recorded pre-token-budget trainer can migrate")
    if "max_batch_tokens" in before["parameters"]:
        raise ValueError("Token-budget configuration already exists")
    after = copy.deepcopy(before)
    after["data"]["code_hashes"]["train_prepared.py"] = TARGET_SHA
    after["parameters"]["max_batch_tokens"] = 6000
    return after


def identical(left, right, torch):
    if torch.is_tensor(left):
        return (torch.is_tensor(right) and left.dtype == right.dtype
                and left.shape == right.shape and torch.equal(left, right))
    if type(left) is not type(right):
        return False
    if isinstance(left, dict):
        return left.keys() == right.keys() and all(identical(left[k], right[k], torch) for k in left)
    if isinstance(left, (list, tuple)):
        return len(left) == len(right) and all(identical(a, b, torch) for a, b in zip(left, right))
    return left == right


def migrate(run):
    import torch
    if file_hash(ROOT / "train_prepared.py") != TARGET_SHA:
        raise ValueError("Target trainer is not the reviewed token-budget fix")
    report, report_sha = document(run / "training.json")
    if report.get("status") != "failed" or "OutOfMemoryError" not in report.get("error", ""):
        raise ValueError("Migration requires the stopped, failed OOM run")
    before = report["configuration"]
    for name, expected in before["data"]["code_hashes"].items():
        if name != "train_prepared.py" and file_hash(ROOT / name) != expected:
            raise ValueError("Another source dependency changed: " + name)
    old_config_sha = digest(canonical(before).encode())
    if report["config_sha256"] != old_config_sha:
        raise ValueError("Source configuration fingerprint differs")
    after = configuration_after(before)
    new_config_sha = digest(canonical(after).encode())
    cache_path = run / "features/manifest.json"
    cache, cache_sha = document(cache_path)
    if (cache["identity_sha256"] != digest(canonical(cache["identity"]).encode())
            or cache["identity"]["code_hashes"] != before["data"]["code_hashes"]):
        raise ValueError("Cache identity differs from the recorded source")
    for spec in cache["files"].values():
        if Path(spec["path"]).name != spec["path"] or file_hash(cache_path.parent / spec["path"]) != spec["sha256"]:
            raise ValueError("Prepared feature pickle changed")
    new_cache = copy.deepcopy(cache)
    new_cache["identity"]["code_hashes"]["train_prepared.py"] = TARGET_SHA
    new_cache["identity_sha256"] = digest(canonical(new_cache["identity"]).encode())
    latest = run / "latest.pt"
    old_latest_sha = file_hash(latest)
    if report["latest_sha256"] != old_latest_sha:
        raise ValueError("Saved checkpoint hash differs from the completed commit")
    saved = torch.load(latest, map_location="cpu", weights_only=True)
    if (saved["config_sha256"] != old_config_sha or saved["cache_sha256"] != cache["identity_sha256"]
            or saved["sampler_sha256"] != report["sampler_sha256"]
            or saved["progress"]["step"] != report["optimizer_steps"]):
        raise ValueError("Saved optimizer state has another lineage")
    best_path = run / "best/friday_prepared_sft.json"
    best, best_sha = document(best_path)
    if best["configuration"] != before or best["weight_sha256"] != weight_hashes(best_path.parent):
        raise ValueError("Best checkpoint metadata or weights changed")
    backup = run / "compatibility-token-budget"
    backup.mkdir()
    os.link(latest, backup / "latest.pt")
    for src, name in ((run / "training.json", "training.json"),
                      (cache_path, "feature-manifest.json"), (best_path, "best-metadata.json")):
        shutil.copy2(src, backup / name)
    migrated = dict(saved)
    migrated.update(config_sha256=new_config_sha, cache_sha256=new_cache["identity_sha256"])
    temporary = run / "migrated.latest.pt"
    atomic_torch_save(torch, temporary, migrated)
    verified = torch.load(temporary, map_location="cpu", weights_only=True)
    changed = {"config_sha256", "cache_sha256"}
    if saved.keys() != verified.keys() or not all(identical(saved[k], verified[k], torch) for k in saved if k not in changed):
        raise ValueError("Non-header checkpoint state changed; original remains backed up")
    new_latest_sha = file_hash(temporary)
    receipt = {"version": 1, "created_utc": datetime.now(timezone.utc).isoformat(),
               "migration_script_sha256": file_hash(Path(__file__)), "source_trainer_sha256": SOURCE_SHA,
               "target_trainer_sha256": TARGET_SHA, "configuration_before_sha256": old_config_sha,
               "configuration_after_sha256": new_config_sha, "checkpoint_before_sha256": old_latest_sha,
               "checkpoint_after_sha256": new_latest_sha, "source_report_sha256": report_sha,
               "source_cache_manifest_sha256": cache_sha, "source_best_metadata_sha256": best_sha,
               "saved_optimizer_step": saved["progress"]["step"], "sampler_sha256": saved["sampler_sha256"],
               "feature_files_unchanged": cache["files"], "all_non_header_checkpoint_state_exactly_equal": True,
               "change": "Only trainer code identity and max_batch_tokens=6000; original optimizer groups and denominator retained.",
               "limit": "Future kernel shapes, rounding and dropout grouping may change numerical trajectory."}
    save_json(backup / "receipt.json", receipt)
    temporary.replace(latest)
    save_json(cache_path, new_cache)
    best["configuration"] = after
    best["original_configuration_sha256"] = old_config_sha
    best["compatibility_migration"] = "compatibility-token-budget/receipt.json"
    save_json(best_path, best)
    report.setdefault("recovery_history", []).append({"reason": report.pop("error"), "receipt": "compatibility-token-budget/receipt.json"})
    report.update(status="awaiting_resume", configuration=after, config_sha256=new_config_sha,
                  feature_cache=new_cache, latest_sha256=new_latest_sha, progress=saved["progress"],
                  optimizer_steps=saved["progress"]["step"])
    save_json(run / "training.json", report)
    print(canonical(receipt), flush=True)


def self_check():
    before = {"data": {"code_hashes": {"train_prepared.py": SOURCE_SHA, "other.py": "unchanged"}},
              "parameters": {"batch_size": 4, "accumulate": 4}}
    after = configuration_after(before)
    assert after["parameters"] == {**before["parameters"], "max_batch_tokens": 6000}
    assert after["data"]["code_hashes"]["other.py"] == "unchanged"
    assert before["data"]["code_hashes"]["train_prepared.py"] == SOURCE_SHA
    try:
        configuration_after(after)
    except ValueError:
        pass
    else:
        raise AssertionError("A repeated migration was accepted")
    class NoTensors:
        is_tensor = staticmethod(lambda value: False)
    assert identical({"rng": (3, [1, 2], None)}, {"rng": (3, [1, 2], None)}, NoTensors)
    assert not identical({"cursor": 2}, {"cursor": 3}, NoTensors)
    assert not identical({"cursor": 2}, {"cursor": True}, NoTensors)
    print("Migration self-check passed: narrow configuration upgrade and exact nested-state comparison")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=Path)
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()
    if args.self_check:
        self_check()
    elif args.run is None:
        parser.error("--run is required")
    else:
        migrate(args.run)
