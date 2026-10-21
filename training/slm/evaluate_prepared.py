#!/usr/bin/env python3
"""Compare selected/base models on the same prepared validation subset after SFT.

Raw responses and public writing inputs belong in ignored artifacts, never Git.
These diagnostics do not select a checkpoint or establish Android task success.
"""
import argparse
import gc
import os
import pickle
import random
import re
import tempfile
import time
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

from distill import STUDENT, STUDENT_REVISION, canonical, digest, runtime, save_json, weight_hashes
from train_prepared import ROOT, checked_file, document, file_hash, generate_validation, validation_rows

LIMIT, MAX_NEW_TOKENS = 96, 256
SCOPE = "Post-selection held-out prepared validation; BF16/SDPA research diagnostic, not phone/runtime parity or semantic certification"


def lineage(report, cache):
    config = report["configuration"]
    if (report.get("status") != "complete" or type(report.get("total_steps")) is not int
            or report["total_steps"] <= 0 or report.get("optimizer_steps") != report["total_steps"]
            or report.get("progress", {}).get("step") != report["total_steps"]):
        raise ValueError("Evaluation requires a completed full training epoch")
    if report.get("config_sha256") != digest(canonical(config).encode()):
        raise ValueError("Training configuration hash differs")
    if config.get("model") != STUDENT or config.get("revision") != STUDENT_REVISION:
        raise ValueError("Run did not start from the pinned base student")
    identity = cache["identity"]
    if (cache.get("identity_sha256") != digest(canonical(identity).encode()) or cache != report["feature_cache"]
            or any(identity.get(key) != value for key, value in config["data"].items())
            or identity.get("student") != STUDENT or identity.get("revision") != STUDENT_REVISION
            or identity.get("max_sequence") != config["parameters"]["max_sequence"]
            or identity.get("chat_template_sha256") != config["template_sha256"]
            or identity.get("writing_policy") != "reviewed-only"):
        raise ValueError("Feature cache is not bound to this run/configuration/template")
    if (type(config["parameters"].get("seed")) is not int or config["parameters"]["max_sequence"] != 2048):
        raise ValueError("Expected the queued diagnostic's seeded 2048-token context")
    return config


def owned_cache_path(run, spec):
    name = spec.get("path")
    if (not isinstance(name, str) or Path(name).name != name or Path(name).is_absolute()
            or not isinstance(spec.get("sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", spec["sha256"])
            or type(spec.get("bytes")) is not int or spec["bytes"] <= 0):
        raise ValueError("Unsafe or unhashed owned cache specification")
    folder = (run / "features").resolve()
    path = (folder / name).resolve()
    if not path.is_relative_to(folder):
        raise ValueError("Cache escapes the owned feature directory")
    return path


def guarded_inputs(run, checkpoint):
    report, report_sha = document(run / "training.json")
    cache, cache_sha = document(run / "features/manifest.json")
    config = lineage(report, cache)
    code = config["data"]["code_hashes"]
    if not {"train_prepared.py", "distill.py", "prompts.py", "regression.py"} <= code.keys():
        raise ValueError("Missing helper code identities")
    for name, expected in code.items():
        if Path(name).name != name or file_hash(ROOT / name) != expected:
            raise ValueError("A frozen training/helper source changed: " + name)
    spec = cache["files"]["validation"]
    path = owned_cache_path(run, spec)
    checked_file(path, spec)  # Hash/bytes guard BEFORE unpickling our own feature artifact.
    best = (run / "best").resolve()
    if Path(report["selected_checkpoint"]).resolve() != best:
        raise ValueError("Selected checkpoint is outside this run's best directory")
    best_metadata, best_sha = document(best / "friday_prepared_sft.json")
    if (best_metadata["configuration"] != config or best_metadata["step"] != report["best_step"]
            or best_metadata["validation"]["loss"] != report["best_validation_loss"]
            or best_metadata["weight_sha256"] != report["weight_sha256"]):
        raise ValueError("Selected best metadata differs from completed training")
    if checkpoint == "selected":
        source, revision, local = str(best), None, True
        hashes = weight_hashes(best)
        if hashes != report["weight_sha256"]:
            raise ValueError("Selected safetensors changed")
    elif checkpoint == "base":
        source, revision, local = STUDENT, STUDENT_REVISION, False
        snapshot = ROOT / ".hf" / ("models--" + STUDENT.replace("/", "--")) / "snapshots" / STUDENT_REVISION
        hashes = weight_hashes(snapshot)
    else:
        raise ValueError("checkpoint must be selected or base")
    with path.open("rb") as stream:
        rows = pickle.load(stream)  # Trusted, owned, hash-bound cache; never a publisher pickle.
    if not isinstance(rows, list) or len(rows) != spec["rows"] or not rows:
        raise ValueError("Validation cache row count differs")
    selected = validation_rows(rows, LIMIT, "generation:" + str(config["parameters"]["seed"]))
    if not selected or len({row["id"] for row in selected}) != len(selected):
        raise ValueError("Invalid/duplicate selected validation IDs")
    for row in selected:
        if (row.get("branch") not in {"state", "writing"} or not row.get("prompt_ids")
                or not isinstance(row.get("response"), str)
                or [message["role"] for message in row.get("messages", [])] != ["system", "user"]):
            raise ValueError("Malformed selected owned validation row")
        if row["branch"] == "writing" and row["kind"] not in {"rewrite", "summary"}:
            raise ValueError("Unexpected writing kind in reviewed public cache")
    return report, config, selected, {"training_report_sha256": report_sha, "cache_manifest_sha256": cache_sha,
        "cache_identity_sha256": cache["identity_sha256"], "validation_cache": spec,
        "selected_metadata_sha256": best_sha, "source": source, "revision": revision,
        "local_checkpoint": local, "weight_sha256": hashes}


def publish_new(path, result):
    """Publish a complete JSON file atomically without ever replacing another report."""
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(dir=path.parent, prefix="." + path.name + "-", suffix=".pending")
    os.close(descriptor)
    temporary = Path(name)
    try:
        save_json(temporary, result)
        os.link(temporary, path)  # Atomic no-replace commit on the same filesystem.
    finally:
        temporary.unlink(missing_ok=True)


def evaluate(args):
    if args.output.exists():
        raise ValueError("Refusing to overwrite an existing evaluation report")
    report, config, selected, inputs = guarded_inputs(args.run, args.checkpoint)
    # All completion, configuration, source-code, cache and checkpoint guards precede ML imports.
    torch, transformers, environment = runtime()
    seed = config["parameters"]["seed"]
    random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)
    model = tokenizer = None
    began = time.perf_counter()
    try:
        kwargs = {"cache_dir": ROOT / ".hf", "local_files_only": True}
        if inputs["revision"]:
            kwargs["revision"] = inputs["revision"]
        tokenizer = transformers.AutoTokenizer.from_pretrained(inputs["source"], use_fast=True, **kwargs)
        if not tokenizer.is_fast or digest(tokenizer.chat_template.encode()) != config["template_sha256"]:
            raise ValueError("Tokenizer/template differs from the cached prompts")
        if tokenizer.pad_token_id is None:
            tokenizer.pad_token = tokenizer.eos_token
        for row in selected:
            prefix = tokenizer.apply_chat_template(row["messages"], tokenize=False, add_generation_prompt=True)
            if tokenizer(prefix, add_special_tokens=False)["input_ids"] != list(row["prompt_ids"]):
                raise ValueError("Tokenizer IDs differ from cached validation: " + row["id"])
        model = transformers.AutoModelForCausalLM.from_pretrained(inputs["source"], torch_dtype=torch.bfloat16,
                    attn_implementation="sdpa", **kwargs).to("cuda").eval()
        torch.cuda.reset_peak_memory_stats()
        result = generate_validation(torch, model, tokenizer, selected,
                    argparse.Namespace(max_new_tokens=MAX_NEW_TOKENS, max_sequence=config["parameters"]["max_sequence"]))
        if [row["id"] for row in result["outputs"]] != [row["id"] for row in selected]:
            raise ValueError("Evaluation omitted/reordered a selected row")
        for output, row in zip(result["outputs"], selected):
            if row["branch"] == "writing":
                output.update(input_messages=row["messages"], input_sha256=digest(canonical(row["messages"]).encode()),
                              reference_sha256=digest(row["response"].encode()),
                              source_scope="Public publisher TRAIN references approved in the hash-bound review; source-grounded semantic review remains necessary")
        by_branch = defaultdict(Counter)
        for family, metrics in result["families"].items():
            by_branch[family.split("/")[0]].update(metrics)
        result.update(version=1, complete=True, selection_eligible=False, scope=SCOPE,
            created_utc=datetime.now(timezone.utc).isoformat(), checkpoint=args.checkpoint,
            config_sha256=report["config_sha256"],
            selected_checkpoint=report["selected_checkpoint"] if args.checkpoint == "selected" else None,
            training_selected_checkpoint=report["selected_checkpoint"],
            model={**{key: inputs[key] for key in ("source", "revision", "local_checkpoint", "weight_sha256")},
                   "base_model": STUDENT, "base_revision": STUDENT_REVISION},
            inputs={key: value for key, value in inputs.items() if key not in {"source", "revision", "local_checkpoint", "weight_sha256"}},
            code_sha256={**config["data"]["code_hashes"], "evaluate_prepared.py": file_hash(Path(__file__))},
            selection={"limit": LIMIT, "seed": seed, "salt": "generation:" + str(seed),
                "ids": [row["id"] for row in selected], "source": "validation feature cache only",
                "policy": "Frozen train_prepared.validation_rows: state/writing quotas then family round-robin SHA ranking; identical to queued selected diagnostic"},
            generation={"max_sequence": config["parameters"]["max_sequence"], "max_new_tokens": MAX_NEW_TOKENS,
                "do_sample": False, "dtype": "bfloat16", "attention": "sdpa", "autocast": "bfloat16"},
            by_branch={key: dict(value) for key, value in by_branch.items()}, environment=environment,
            elapsed_s=time.perf_counter() - began, gpu_peak_allocated_bytes=torch.cuda.max_memory_allocated())
        publish_new(args.output, result)
    finally:
        del model, tokenizer
        gc.collect()
        torch.cuda.empty_cache()
    print(canonical({"stage": "prepared_evaluation", "checkpoint": args.checkpoint, "rows": len(selected), "output": str(args.output)}), flush=True)


def self_check():
    code = {key: "a" * 64 for key in ("train_prepared.py", "distill.py", "prompts.py", "regression.py")}
    config = {"model": STUDENT, "revision": STUDENT_REVISION, "data": {"code_hashes": code},
              "parameters": {"seed": 42, "max_sequence": 2048}, "template_sha256": "b" * 64}
    identity = {**config["data"], "student": STUDENT, "revision": STUDENT_REVISION, "max_sequence": 2048,
                "chat_template_sha256": config["template_sha256"], "writing_policy": "reviewed-only"}
    cache = {"identity": identity, "identity_sha256": digest(canonical(identity).encode())}
    report = {"status": "complete", "total_steps": 2, "optimizer_steps": 2, "progress": {"step": 2},
              "configuration": config, "config_sha256": digest(canonical(config).encode()), "feature_cache": cache}
    assert lineage(report, cache) == config
    invalid = [({**report, "status": "running"}, cache), ({**report, "optimizer_steps": 1}, cache),
               ({**report, "config_sha256": "changed"}, cache), (report, {**cache, "identity_sha256": "changed"})]
    for bad_report, bad_cache in invalid:
        try:
            lineage(bad_report, bad_cache)
        except ValueError:
            pass
        else:
            raise AssertionError("Invalid completion/lineage accepted")
    with tempfile.TemporaryDirectory(prefix="friday-eval-prepared-") as directory:
        run = Path(directory)
        spec = {"path": "validation.pickle", "sha256": "c" * 64, "bytes": 1}
        assert owned_cache_path(run, spec).name == "validation.pickle"
        for name in ("../outside.pickle", "/outside.pickle"):
            try:
                owned_cache_path(run, {**spec, "path": name})
            except ValueError:
                pass
            else:
                raise AssertionError("Unsafe cache path accepted")
        cache_file = run / "features/validation.pickle"
        cache_file.parent.mkdir()
        cache_file.write_bytes(b"owned feature cache")
        known = {"sha256": file_hash(cache_file), "bytes": cache_file.stat().st_size}
        checked_file(cache_file, known)
        cache_file.write_bytes(b"changed")
        try:
            checked_file(cache_file, known)
        except ValueError:
            pass
        else:
            raise AssertionError("Changed cache accepted before unpickling")
        output = run / "evaluation.json"
        publish_new(output, {"complete": True})
        try:
            publish_new(output, {"complete": False})
        except FileExistsError:
            pass
        else:
            raise AssertionError("Existing report overwritten")
        assert document(output)[0] == {"complete": True}
    print("Prepared evaluator self-check passed: completion/config/cache lineage, unsafe cache paths and atomic no-overwrite output")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-check", action="store_true")
    parser.add_argument("--run", type=Path)
    parser.add_argument("--checkpoint", choices=("selected", "base"), default="selected")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.self_check:
        self_check()
    elif args.run is None or args.output is None:
        parser.error("--run and --output are required")
    else:
        evaluate(args)
