#!/usr/bin/env python3
"""Teacher labels and response-only full SFT; synthetic offline research only."""
import argparse
import gc
import hashlib
import json
import math
import random
import shutil
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from probe import MODELS
from prompts import PROMPT_VERSION, WRITING_KINDS, build_messages, prompt_digest_input
from regression import counters, evaluate, load_cases, parse_json

ROOT = Path(__file__).resolve().parent
TEACHER, TEACHER_REVISION = MODELS["qwen3"]
STUDENT, STUDENT_REVISION = MODELS["smollm2"]
SCOPE = "Synthetic teacher-response distillation; no Android execution, phone/runtime parity or semantic certification"


def digest(value):
    return hashlib.sha256(value).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def save_json(path, value):
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + "\n", encoding="utf-8")
    temporary.replace(path)


def save_jsonl(path, rows):
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(canonical(row) + "\n")
    temporary.replace(path)


def read_rows(path):
    raw = path.read_bytes()
    rows = [parse_json(line) for line in raw.decode("utf-8").splitlines() if line.strip()]
    if not rows or not all(isinstance(row, dict) for row in rows):
        raise ValueError("Expected nonempty JSONL objects: " + str(path))
    return rows, digest(raw)


def input_key(row):
    text = row.get("text", "")
    if row["kind"] in {"task_update", "intent"}:
        text = parse_json(text)
    return digest(canonical({"kind": row["kind"], "instruction": row["instruction"], "text": text}).encode())


def guard_splits(train, validation, frozen):
    """Gold and prompts remain separate; frozen cases are used only for a leakage guard."""
    frozen_keys, frozen_ids = {input_key(row) for row in frozen}, {row["id"] for row in frozen}
    seen_keys, seen_ids = set(), set()
    for split, rows in (("train", train), ("validation", validation)):
        if not rows:
            raise ValueError("Empty " + split + " split")
        for row in rows:
            if row.get("split") != split or not row.get("id") or not row.get("family") or not row.get("provenance"):
                raise ValueError("Missing row identity, split or provenance")
            build_messages(row)
            if row["kind"] not in WRITING_KINDS and not ("expected" in row or row.get("expected_any")):
                raise ValueError("Structured label needs independent gold: " + row["id"])
            if row["kind"] in WRITING_KINDS and not row.get("checks"):
                raise ValueError("Writing label needs mechanical checks: " + row["id"])
            key = input_key(row)
            if key in frozen_keys or row["id"] in frozen_ids:
                raise ValueError("Frozen regression leakage: " + row["id"])
            if key in seen_keys or row["id"] in seen_ids:
                raise ValueError("Repeated training/validation row: " + row["id"])
            seen_keys.add(key)
            seen_ids.add(row["id"])


def label_check(row, raw, truncated=False):
    score = evaluate(row, raw)
    accepted = bool(score["mechanical_pass"] and not truncated)
    if row["kind"] not in WRITING_KINDS:
        accepted = accepted and score["expected_match"] is True
    return {"accepted": accepted, "checks": score, "truncated": bool(truncated),
            "semantic_review_required": row["kind"] in WRITING_KINDS}


def response_mask(ids, offsets, response_start):
    if len(ids) != len(offsets):
        raise ValueError("Token offsets do not match token IDs")
    # A token crossing the prompt boundary stays masked, even if it includes response text.
    labels = [token if start >= response_start and end > start else -100
              for token, (start, end) in zip(ids, offsets)]
    if not any(label != -100 for label in labels[1:]):
        raise ValueError("Response has no supervised next-token labels")
    return labels


def response_features(tokenizer, row, response, max_sequence):
    messages = build_messages(row)
    prefix = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
    full = tokenizer.apply_chat_template(messages + [{"role": "assistant", "content": response}],
                                         tokenize=False, add_generation_prompt=False)
    if not full.startswith(prefix + response):
        raise ValueError("Chat template changed the assistant response or generation prefix")
    encoded = tokenizer(full, add_special_tokens=False, return_offsets_mapping=True)
    ids = encoded["input_ids"]
    if len(ids) > max_sequence:
        raise ValueError("Sequence exceeds limit without truncation: " + row["id"])
    return {"input_ids": ids, "labels": response_mask(ids, encoded["offset_mapping"], len(prefix))}


def pad_batch(features, pad_id):
    width = max(len(item["input_ids"]) for item in features)
    batch = {"input_ids": [], "attention_mask": [], "labels": []}
    for item in features:
        length = len(item["input_ids"])
        batch["input_ids"].append(item["input_ids"] + [pad_id] * (width - length))
        batch["attention_mask"].append([1] * length + [0] * (width - length))
        batch["labels"].append(item["labels"] + [-100] * (width - length))
    return batch


def code_hashes():
    return {name: digest((ROOT / name).read_bytes()) for name in ("prompts.py", "regression.py")}


def runtime():
    # Training imports stay lazy so parser, split and mask checks need only the standard library.
    import torch
    import transformers
    import safetensors
    if transformers.__version__ != "4.57.6" or safetensors.__version__ != "0.8.0":
        raise ValueError("Run setup.sh: transformers 4.57.6 and safetensors 0.8.0 are required")
    if not torch.cuda.is_available() or not torch.cuda.is_bf16_supported():
        raise ValueError("A CUDA GPU supporting BF16 is required")
    environment = {"torch": str(torch.__version__), "torch_location": torch.__file__,
                   "cuda": torch.version.cuda, "gpu": torch.cuda.get_device_name(),
                   "transformers": transformers.__version__, "safetensors": safetensors.__version__}
    return torch, transformers, environment


def label(args):
    train, train_hash = read_rows(args.train)
    validation, validation_hash = read_rows(args.validation)
    frozen, frozen_sources = load_cases([args.regression])
    guard_splits(train, validation, frozen)
    rows = train + validation
    parameters = {"teacher": TEACHER, "teacher_revision": TEACHER_REVISION,
                  "student": STUDENT, "student_revision": STUDENT_REVISION,
                  "seed": args.seed, "batch_size": args.batch_size, "attention": args.attention,
                  "max_input_tokens": args.max_input_tokens, "max_new_tokens": args.max_new_tokens,
                  "do_sample": False, "dtype": "bfloat16", "prompt_version": PROMPT_VERSION,
                  "source_hashes": {"train": train_hash, "validation": validation_hash},
                  "frozen_sources": frozen_sources, "code_hashes": code_hashes()}
    config_hash = digest(canonical(parameters).encode())
    manifest_path = args.out / "label_manifest.json"
    if manifest_path.exists():
        manifest = parse_json(manifest_path.read_text())
        if not args.resume or manifest.get("config_sha256") != config_hash:
            raise ValueError("Existing label job requires --resume and identical inputs/configuration")
    else:
        if args.out.exists() and any(args.out.iterdir()):
            raise ValueError("Label output directory is not empty")
        args.out.mkdir(parents=True, exist_ok=True)
        manifest = {"version": 1, "scope": SCOPE, "parameters": parameters, "config_sha256": config_hash,
                    "trainer_sha256": digest(Path(__file__).read_bytes()),
                    "created_utc": datetime.now(timezone.utc).isoformat(), "candidate_counts": dict(Counter(row["split"] for row in rows))}
    batches_dir = args.out / "label_batches"
    batches_dir.mkdir(exist_ok=True)
    records = []
    pending = []
    for index, offset in enumerate(range(0, len(rows), args.batch_size)):
        batch = rows[offset:offset + args.batch_size]
        path = batches_dir / ("%06d.json" % index)
        if path.exists():
            saved = parse_json(path.read_text())
            if saved.get("config_sha256") != config_hash or [item["id"] for item in saved["records"]] != [item["id"] for item in batch]:
                raise ValueError("Conflicting label batch checkpoint: " + str(path))
            for row, record in zip(batch, saved["records"]):
                if record.get("text") is not None and label_check(row, record["text"], record.get("truncated", False))["accepted"] != record["accepted"]:
                    raise ValueError("Checkpoint no longer passes the same label gate")
            records.extend(saved["records"])
        else:
            pending.append((batch, path))
    manifest["status"] = "running"
    save_json(manifest_path, manifest)
    torch = model = tokenizer = inputs = generated = None
    started = time.perf_counter()
    try:
        if pending:
            torch, transformers, environment = runtime()
            random.seed(args.seed)
            torch.manual_seed(args.seed)
            torch.cuda.manual_seed_all(args.seed)
            torch.cuda.reset_peak_memory_stats()
            tokenizer = transformers.AutoTokenizer.from_pretrained(TEACHER, revision=TEACHER_REVISION, cache_dir=ROOT / ".hf")
            tokenizer.padding_side = "left"
            if tokenizer.pad_token_id is None:
                tokenizer.pad_token = tokenizer.eos_token
            model = transformers.AutoModelForCausalLM.from_pretrained(
                TEACHER, revision=TEACHER_REVISION, cache_dir=ROOT / ".hf", torch_dtype=torch.bfloat16,
                attn_implementation=args.attention).to("cuda").eval()
            manifest["environment"] = environment
            manifest["chat_template"] = tokenizer.chat_template
            manifest["resolved_teacher_revision"] = getattr(model.config, "_commit_hash", None)
            save_json(manifest_path, manifest)
            for batch, path in pending:
                batch_records, selected, prompts = [], [], []
                for row in batch:
                    prompt = tokenizer.apply_chat_template(build_messages(row), tokenize=False, add_generation_prompt=True)
                    count = len(tokenizer(prompt, add_special_tokens=False)["input_ids"])
                    if count > args.max_input_tokens:
                        batch_records.append({"id": row["id"], "text": None, "accepted": False,
                                              "error": "Input exceeds token limit; no truncation", "input_tokens": count})
                    else:
                        selected.append(row)
                        prompts.append(prompt)
                generation_s = 0.0
                if selected:
                    inputs = tokenizer(prompts, padding=True, add_special_tokens=False, return_tensors="pt").to("cuda")
                    torch.cuda.synchronize()
                    began = time.perf_counter()
                    with torch.inference_mode():
                        generated = model.generate(**inputs, do_sample=False, max_new_tokens=args.max_new_tokens,
                                                   pad_token_id=tokenizer.pad_token_id)
                    torch.cuda.synchronize()
                    generation_s = time.perf_counter() - began
                    width = inputs["input_ids"].shape[-1]
                    for row, tokens, attention in zip(selected, generated[:, width:], inputs["attention_mask"]):
                        ids = tokens.tolist()
                        ended = tokenizer.eos_token_id in ids
                        count = ids.index(tokenizer.eos_token_id) + 1 if ended else len(ids)
                        raw = tokenizer.decode(ids, skip_special_tokens=True)
                        batch_records.append({"id": row["id"], "text": raw,
                                              **label_check(row, raw, not ended and len(ids) >= args.max_new_tokens),
                                              "input_tokens": int(attention.sum().item()), "output_tokens": count})
                    del inputs, generated
                    inputs = generated = None
                by_id = {record["id"]: record for record in batch_records}
                ordered = [by_id[row["id"]] for row in batch]
                save_json(path, {"config_sha256": config_hash, "generation_s": generation_s, "records": ordered})
                records.extend(ordered)
                manifest["completed_candidates"] = len(records)
                manifest["accepted_so_far"] = sum(record["accepted"] for record in records)
                save_json(manifest_path, manifest)
                print(canonical({"stage": "label", "completed": len(records), "total": len(rows),
                                 "accepted": manifest["accepted_so_far"], "batch_generation_s": generation_s}), flush=True)
        by_id = {record["id"]: record for record in records}
        accepted = {split: [] for split in ("train", "validation")}
        family_counts = {}
        for row in rows:
            record = by_id[row["id"]]
            counts = family_counts.setdefault(row["family"], {"candidates": 0, "accepted": 0})
            counts["candidates"] += 1
            counts["accepted"] += record["accepted"]
            if record["accepted"]:
                accepted[row["split"]].append({**row, "response": record["text"],
                    "label_source": {"model": TEACHER, "revision": TEACHER_REVISION}, "label_checks": record["checks"]})
        files = {}
        for split, labeled in accepted.items():
            path = args.out / (split + ".labels.jsonl")
            save_jsonl(path, labeled)
            files[split] = {"path": path.name, "sha256": digest(path.read_bytes()), "rows": len(labeled)}
        manifest.update({"status": "complete", "files": files, "families": family_counts,
                         "rejected": sum(not record["accepted"] for record in records),
                         "writing_labels_require_semantic_review": sum(row["kind"] in WRITING_KINDS for values in accepted.values() for row in values),
                         "last_invocation_s": time.perf_counter() - started})
        if not all(accepted.values()):
            raise ValueError("No accepted teacher labels in one split; inspect raw label batches")
    except BaseException as error:
        manifest.update({"status": "failed", "error": type(error).__name__ + ": " + str(error)})
        raise
    finally:
        if torch is not None:
            manifest["gpu_peak_allocated_bytes"] = torch.cuda.max_memory_allocated()
        del generated, inputs, model, tokenizer
        gc.collect()
        if torch is not None:
            torch.cuda.empty_cache()
        save_json(manifest_path, manifest)
    print(manifest_path, flush=True)


def validation_subset(rows, limit):
    groups = {}
    for row in sorted(rows, key=lambda row: row["id"]):
        groups.setdefault(row["family"], []).append(row)
    selected = []
    while groups and len(selected) < limit:
        for family in sorted(list(groups)):
            if len(selected) == limit:
                break
            selected.append(groups[family].pop(0))
            if not groups[family]:
                del groups[family]
    return selected


def validation_responses(torch, model, tokenizer, rows, args):
    """Score only a fixed synthetic validation subset, never frozen regression."""
    outputs = []
    previous_padding = tokenizer.padding_side
    tokenizer.padding_side = "left"
    model.eval()
    try:
        for offset in range(0, len(rows), args.batch_size):
            batch = rows[offset:offset + args.batch_size]
            selected, prompts = [], []
            for row in batch:
                prompt = tokenizer.apply_chat_template(build_messages(row), tokenize=False, add_generation_prompt=True)
                count = len(tokenizer(prompt, add_special_tokens=False)["input_ids"])
                if count + 256 > args.max_sequence:
                    outputs.append({"id": row["id"], "family": row["family"], "kind": row["kind"],
                                    "status": "error", "accepted_response": False, "input_tokens": count,
                                    "error": "Validation input exceeds sequence limit; no truncation"})
                else:
                    selected.append(row)
                    prompts.append(prompt)
            if not selected:
                continue
            inputs = tokenizer(prompts, padding=True, add_special_tokens=False, return_tensors="pt").to("cuda")
            with torch.inference_mode(), torch.autocast("cuda", dtype=torch.bfloat16):
                tokens = model.generate(**inputs, do_sample=False, max_new_tokens=256,
                                        pad_token_id=tokenizer.pad_token_id, use_cache=True)
            width = inputs["input_ids"].shape[-1]
            for row, generated in zip(selected, tokens[:, width:]):
                ids = generated.tolist()
                truncated = tokenizer.eos_token_id not in ids and len(ids) >= 256
                raw = tokenizer.decode(ids, skip_special_tokens=True)
                checked = label_check(row, raw, truncated)
                outputs.append({"id": row["id"], "family": row["family"], "kind": row["kind"],
                    "text": raw, "status": "generated", **checked["checks"], "truncated": truncated,
                    "accepted_response": checked["accepted"]})
    finally:
        tokenizer.padding_side = previous_padding
    accepted = sum(item["accepted_response"] for item in outputs)
    return {"split": "validation", "selection_eligible": True, "case_ids": [row["id"] for row in rows],
            "prompt_sha256": digest(canonical([prompt_digest_input(row) for row in rows]).encode()),
            "cases": len(outputs), "accepted_responses": accepted, "accepted_fraction": accepted / len(outputs),
            "family_metrics": counters(outputs), "outputs": outputs,
            "note": "Mechanical/exact response checks only; prose meaning still requires review. Frozen regression was not used."}


def weight_hashes(path):
    result = {}
    for file in sorted(path.glob("*.safetensors")):
        hasher = hashlib.sha256()
        with file.open("rb") as handle:
            for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
                hasher.update(chunk)
        result[file.name] = hasher.hexdigest()
    if not result:
        raise ValueError("Checkpoint has no safetensors weights")
    return result


def reviewed_writing(splits, review):
    known = {row["id"]: row for rows in splits.values() for row in rows if row["kind"] in WRITING_KINDS}
    entries = {}
    if review is not None:
        if (not isinstance(review, dict) or set(review) != {"version", "reviewer", "reviews"}
                or review["version"] != 1 or not isinstance(review["reviewer"], str) or not review["reviewer"].strip()
                or not isinstance(review["reviews"], dict)):
            raise ValueError("Writing review needs version 1, reviewer and reviews object")
        entries = review["reviews"]
        for row_id, decision in entries.items():
            if row_id not in known:
                raise ValueError("Review references an unknown/non-writing label: " + row_id)
            if (not isinstance(decision, dict) or set(decision) != {"response_sha256", "approved", "reason"}
                    or type(decision["approved"]) is not bool or not isinstance(decision["reason"], str) or not decision["reason"].strip()
                    or decision["response_sha256"] != digest(known[row_id]["response"].encode("utf-8"))):
                raise ValueError("Invalid or response-hash-mismatched writing review: " + row_id)
    selected, counts = {}, {}
    for split, rows in splits.items():
        selected[split] = []
        counts[split] = {"structured": 0, "writing_approved": 0, "writing_rejected": 0, "writing_unreviewed": 0}
        for row in rows:
            if row["kind"] not in WRITING_KINDS:
                counts[split]["structured"] += 1
                selected[split].append(row)
            elif row["id"] not in entries:
                counts[split]["writing_unreviewed"] += 1
            elif not entries[row["id"]]["approved"]:
                counts[split]["writing_rejected"] += 1
            else:
                counts[split]["writing_approved"] += 1
                selected[split].append(row)
    return selected, {"reviewer": review["reviewer"] if review else None, "counts": counts,
                      "policy": "Only independently approved, response-hash-bound writing labels enter loss",
                      "note": "AI review is not human semantic certification; missing reviews exclude writing labels."}


def train_once(args):
    label_manifest = parse_json((args.data / "label_manifest.json").read_text())
    if label_manifest.get("status") != "complete" or label_manifest["parameters"]["code_hashes"] != code_hashes():
        raise ValueError("Training requires complete teacher labels and unchanged shared prompts/checker")
    if (label_manifest["parameters"]["teacher_revision"] != TEACHER_REVISION
            or label_manifest["parameters"]["student_revision"] != STUDENT_REVISION):
        raise ValueError("Teacher/student revisions do not match this experiment")
    splits = {}
    for split in ("train", "validation"):
        info = label_manifest["files"][split]
        if Path(info["path"]).name != info["path"]:
            raise ValueError("Label filename must stay inside the job directory")
        rows, actual_hash = read_rows(args.data / info["path"])
        if actual_hash != info["sha256"] or len(rows) != info["rows"]:
            raise ValueError("Labeled data hash/count mismatch: " + split)
        for row in rows:
            if not label_check(row, row["response"])["accepted"] or row.get("label_source") != {"model": TEACHER, "revision": TEACHER_REVISION}:
                raise ValueError("Unverified teacher response: " + row["id"])
        splits[split] = rows
    guard_splits(splits["train"], splits["validation"], [])
    review = parse_json(args.writing_review.read_bytes()) if args.writing_review else None
    splits, review_summary = reviewed_writing(splits, review)
    if not all(splits.values()):
        raise ValueError("Writing review gate left an empty train/validation split")
    review_summary["file_sha256"] = digest(args.writing_review.read_bytes()) if args.writing_review else None
    score_candidates = splits["validation"]
    score_source = {"type": "accepted_teacher_validation_labels", "rows": len(score_candidates)}
    if args.validation_candidates is not None:
        score_candidates, candidate_hash = read_rows(args.validation_candidates)
        if candidate_hash != label_manifest["parameters"]["source_hashes"]["validation"]:
            raise ValueError("Original validation candidate hash differs from the teacher-label job")
        guard_splits(splits["train"], score_candidates, [])
        score_source = {"type": "original_validation_candidates_including_teacher_rejections",
                        "path": str(args.validation_candidates), "sha256": candidate_hash, "rows": len(score_candidates)}
    if args.out.exists() and any(args.out.iterdir()):
        raise ValueError("Training output directory is not empty; use a new experiment directory")
    args.out.mkdir(parents=True, exist_ok=True)
    manifest_path = args.out / "training.json"
    manifest = {"version": 1, "scope": SCOPE, "status": "running", "model": STUDENT, "revision": STUDENT_REVISION,
                "teacher": TEACHER, "teacher_revision": TEACHER_REVISION, "prompt_version": PROMPT_VERSION,
                "label_manifest_sha256": digest((args.data / "label_manifest.json").read_bytes()),
                "data": label_manifest["files"], "code_hashes": code_hashes(),
                "trainer_sha256": digest(Path(__file__).read_bytes()),
                "parameters": {key: str(value) if isinstance(value, Path) else value for key, value in vars(args).items() if key not in {"command", "data", "out"}},
                "checkpoint_selection": "validation response-only token loss; frozen regression is never used",
                "writing_review": review_summary,
                "writing_labels_require_semantic_review": label_manifest["writing_labels_require_semantic_review"], "epochs": []}
    save_json(manifest_path, manifest)
    torch = model = tokenizer = optimizer = scheduler = None
    started = time.perf_counter()
    try:
        torch, transformers, environment = runtime()
        manifest["environment"] = environment
        manifest["training_precision"] = "FP32 parameters/optimizer with BF16 autocast; response-only loss"
        random.seed(args.seed)
        torch.manual_seed(args.seed)
        torch.cuda.manual_seed_all(args.seed)
        torch.cuda.reset_peak_memory_stats()
        tokenizer = transformers.AutoTokenizer.from_pretrained(STUDENT, revision=STUDENT_REVISION, cache_dir=ROOT / ".hf", use_fast=True)
        if not tokenizer.is_fast:
            raise ValueError("Response-only boundary masking requires a fast tokenizer")
        tokenizer.padding_side = "right"
        if tokenizer.pad_token_id is None:
            tokenizer.pad_token = tokenizer.eos_token
        features = {split: [response_features(tokenizer, row, row["response"], args.max_sequence) for row in rows]
                    for split, rows in splits.items()}
        manifest["token_counts"] = {split: {"rows": len(items), "max_sequence": max(len(item["input_ids"]) for item in items),
            "response_tokens": sum(sum(label != -100 for label in item["labels"][1:]) for item in items)} for split, items in features.items()}
        model = transformers.AutoModelForCausalLM.from_pretrained(
            STUDENT, revision=STUDENT_REVISION, cache_dir=ROOT / ".hf", torch_dtype=torch.float32,
            attn_implementation=args.attention).to("cuda")
        model.config.use_cache = False
        manifest["resolved_student_revision"] = getattr(model.config, "_commit_hash", None)
        optimizer = torch.optim.AdamW(model.parameters(), lr=args.learning_rate, weight_decay=0.01)
        batches_per_epoch = math.ceil(len(features["train"]) / args.batch_size)
        steps = math.ceil(batches_per_epoch / args.accumulate) * args.epochs
        warmup = max(1, math.ceil(steps * 0.05))

        def schedule(step):
            if step < warmup:
                return (step + 1) / warmup
            return max(0.0, (steps - step) / max(1, steps - warmup))

        scheduler = torch.optim.lr_scheduler.LambdaLR(optimizer, schedule)

        def batch_tensor(items):
            return {key: torch.tensor(value, dtype=torch.long, device="cuda")
                    for key, value in pad_batch(items, tokenizer.pad_token_id).items()}

        def validation_loss():
            model.eval()
            total, count = 0.0, 0
            with torch.no_grad():
                for offset in range(0, len(features["validation"]), args.batch_size):
                    batch = batch_tensor(features["validation"][offset:offset + args.batch_size])
                    tokens = int((batch["labels"][:, 1:] != -100).sum().item())
                    with torch.autocast("cuda", dtype=torch.bfloat16):
                        loss = model(**batch).loss
                    total += float(loss.item()) * tokens
                    count += tokens
            return total / count

        def checkpoint(path, detail):
            temporary = path.with_name(path.name + ".tmp")
            if temporary.exists():
                shutil.rmtree(temporary)
            temporary.mkdir(parents=True)
            model.save_pretrained(temporary, safe_serialization=True)
            tokenizer.save_pretrained(temporary)
            save_json(temporary / "friday_distillation.json", {**manifest, "checkpoint": detail})
            if path.exists():
                shutil.rmtree(path)
            temporary.replace(path)

        best_loss, step = math.inf, 0
        score_rows = validation_subset(score_candidates, args.validation_generation_limit)
        manifest["generated_validation_case_ids"] = [row["id"] for row in score_rows]
        manifest["generated_validation_source"] = score_source
        manifest["initial_validation_loss"] = validation_loss()
        save_json(manifest_path, manifest)
        for epoch in range(args.epochs):
            began = time.perf_counter()
            model.train()
            order = list(range(len(features["train"])))
            random.Random(args.seed + epoch).shuffle(order)
            batches = [order[offset:offset + args.batch_size] for offset in range(0, len(order), args.batch_size)]
            epoch_loss, epoch_tokens = 0.0, 0
            for group_offset in range(0, len(batches), args.accumulate):
                optimizer.zero_grad(set_to_none=True)
                group_tokens = 0
                for indices in batches[group_offset:group_offset + args.accumulate]:
                    batch = batch_tensor([features["train"][index] for index in indices])
                    tokens = int((batch["labels"][:, 1:] != -100).sum().item())
                    with torch.autocast("cuda", dtype=torch.bfloat16):
                        loss = model(**batch).loss
                    if not bool(torch.isfinite(loss).item()):
                        raise ValueError("Nonfinite training loss")
                    (loss * tokens).backward()
                    epoch_loss += float(loss.item()) * tokens
                    epoch_tokens += tokens
                    group_tokens += tokens
                for parameter in model.parameters():
                    if parameter.grad is not None:
                        parameter.grad.div_(group_tokens)
                gradient_norm = torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0, error_if_nonfinite=True)
                optimizer.step()
                scheduler.step()
                step += 1
                if step == 1 or step % 10 == 0:
                    print(canonical({"stage": "train", "epoch": epoch + 1, "step": step, "total_steps": steps,
                                     "response_loss": epoch_loss / epoch_tokens, "gradient_norm": float(gradient_norm.item()),
                                     "elapsed_s": time.perf_counter() - started}), flush=True)
            val_loss = validation_loss()
            if not math.isfinite(val_loss):
                raise ValueError("Nonfinite validation loss")
            detail = {"epoch": epoch + 1, "step": step, "train_loss": epoch_loss / epoch_tokens,
                      "validation_loss": val_loss, "wall_s": time.perf_counter() - began}
            manifest["epochs"].append(detail)
            if val_loss < best_loss:
                best_loss = val_loss
                manifest["best_epoch"] = epoch + 1
                manifest["best_validation_loss"] = val_loss
                if score_rows:
                    response_score = validation_responses(torch, model, tokenizer, score_rows, args)
                    save_json(args.out / "best_validation_responses.json", response_score)
                    manifest["best_validation_response_score"] = {key: value for key, value in response_score.items() if key != "outputs"}
                checkpoint(args.out / "best", detail)
            save_json(manifest_path, manifest)
            print(canonical({"stage": "validation", **detail, "best_epoch": manifest["best_epoch"]}), flush=True)
        manifest.update({"status": "complete", "wall_s": time.perf_counter() - started,
                         "best_checkpoint": "best", "optimizer_steps": step,
                         "weight_sha256": weight_hashes(args.out / "best"),
                         "resume_note": "Best inference weights are retained; restarting within an unfinished epoch is unsupported."})
        save_json(args.out / "best" / "friday_distillation.json", manifest)
    except BaseException as error:
        manifest.update({"status": "failed", "error": type(error).__name__ + ": " + str(error)})
        raise
    finally:
        if torch is not None:
            manifest["gpu_peak_allocated_bytes"] = torch.cuda.max_memory_allocated()
        del optimizer, scheduler, model, tokenizer
        gc.collect()
        if torch is not None:
            torch.cuda.empty_cache()
        save_json(manifest_path, manifest)
    print(manifest_path, flush=True)
    return manifest


def train(args):
    if not args.trials:
        return train_once(args)
    import optuna
    if optuna.__version__ != "4.5.0":
        raise ValueError("This bounded study requires optuna==4.5.0 in the job venv")
    if args.validation_generation_limit < 1:
        raise ValueError("Optuna needs at least one generated synthetic validation response")
    if args.validation_candidates is None:
        raise ValueError("Optuna requires --validation-candidates from the original synthetic validation split")
    args.out.mkdir(parents=True, exist_ok=True)
    parameters = {key: str(value) if isinstance(value, Path) else value for key, value in vars(args).items() if key not in {"command", "data", "out", "trials", "learning_rate"}}
    parameters.update({"label_manifest_sha256": digest((args.data / "label_manifest.json").read_bytes()),
                       "code_hashes": code_hashes(), "trainer_sha256": digest(Path(__file__).read_bytes()),
                       "writing_review_sha256": digest(args.writing_review.read_bytes()) if args.writing_review else None,
                       "learning_rates": [2e-5, 5e-5, 1e-4], "seed": args.seed})
    config_hash = digest(canonical(parameters).encode())
    study = optuna.create_study(study_name="friday-synthetic-response-pilot", direction="maximize",
        storage="sqlite:///" + str((args.out / "study.sqlite3").resolve()), load_if_exists=True,
        sampler=optuna.samplers.GridSampler({"learning_rate": parameters["learning_rates"]}, seed=args.seed))
    saved_hash = study.user_attrs.get("config_sha256")
    if saved_hash is not None and saved_hash != config_hash:
        raise ValueError("Existing Optuna study has different data/prompt/training configuration")
    study.set_user_attr("config_sha256", config_hash)
    study.set_user_attr("parameters", parameters)
    if any(trial.state == optuna.trial.TrialState.RUNNING for trial in study.trials):
        raise ValueError("Study has an unfinished RUNNING trial; inspect it before restarting")
    summary_path = args.out / "study.json"

    def save_study():
        complete = [trial for trial in study.trials if trial.state == optuna.trial.TrialState.COMPLETE]
        selected = min(complete, key=lambda trial: (-trial.value, trial.user_attrs["validation_loss"], trial.number)) if complete else None
        result = {"version": 1, "scope": SCOPE, "optuna": optuna.__version__, "config_sha256": config_hash,
                  "parameters": parameters, "objective": "maximize generated synthetic VALIDATION accepted fraction",
                  "tie_break": "lower teacher-response validation loss, then lower trial number",
                  "trials": [{"number": trial.number, "state": trial.state.name, "value": trial.value,
                              "parameters": trial.params, "attributes": trial.user_attrs} for trial in study.trials],
                  "selected_trial": selected.number if selected else None,
                  "selected_checkpoint": selected.user_attrs["checkpoint"] if selected else None,
                  "frozen_regression_used_for_selection": False}
        save_json(summary_path, result)

    def objective(trial):
        if (digest((args.data / "label_manifest.json").read_bytes()) != parameters["label_manifest_sha256"]
                or (digest(args.writing_review.read_bytes()) if args.writing_review else None) != parameters["writing_review_sha256"]):
            raise ValueError("Teacher label manifest or writing reviews changed during the study")
        local = argparse.Namespace(**vars(args))
        local.trials = 0
        local.learning_rate = trial.suggest_categorical("learning_rate", parameters["learning_rates"])
        local.out = args.out / ("trial-%03d" % trial.number)
        report = train_once(local)
        score = report["best_validation_response_score"]
        trial.set_user_attr("validation_loss", report["best_validation_loss"])
        trial.set_user_attr("response_score", score)
        trial.set_user_attr("checkpoint", str((local.out / "best").resolve()))
        trial.set_user_attr("weight_sha256", report["weight_sha256"])
        trial.set_user_attr("best_epoch", report["best_epoch"])
        return score["accepted_fraction"]

    save_study()
    remaining = max(0, args.trials - len(study.trials))
    if remaining:
        study.optimize(objective, n_trials=remaining, callbacks=[lambda *_: save_study()], gc_after_trial=True)
    save_study()
    print(summary_path, flush=True)


def self_check():
    class FakeTokenizer:
        def apply_chat_template(self, messages, tokenize, add_generation_prompt):
            return "".join(item["role"] + ":" + item["content"] + "|" for item in messages) + ("assistant:" if add_generation_prompt else "")

        def __call__(self, text, **kwargs):
            return {"input_ids": list(range(len(text))), "offset_mapping": [(index, index + 1) for index in range(len(text))]}

    def row(name, split):
        return {"id": name, "family": "writing", "kind": "message", "instruction": name,
                "text": "I will be 15 minutes late.", "split": split, "provenance": "synthetic self-check",
                "checks": {"required": ["15"], "manual_rubric": ["preserve meaning"]}}

    first, second, frozen = row("one", "train"), row("two", "validation"), row("three", "evaluation")
    guard_splits([first], [second], [frozen])
    for bad_train, bad_validation, bad_frozen in (([first], [{**first, "split": "validation"}], []),
                                                  ([first], [second], [first])):
        try:
            guard_splits(bad_train, bad_validation, bad_frozen)
        except ValueError:
            pass
        else:
            raise AssertionError("Split/frozen contamination was not rejected")
    assert label_check(first, '{"text":"I will be 15 minutes late."}')["accepted"]
    assert not label_check(first, '{"text":"I will be 50 minutes late."}')["accepted"]
    assert not label_check(first, '```json\n{"text":"I will be 15 minutes late."}\n```')["accepted"]
    assert not label_check(first, '{"text":"I will be 15 minutes late."}', truncated=True)["accepted"]
    assert label_check(first, '{"text":"I will be 15 minutes late."}')["semantic_review_required"]
    labeled = {**first, "response": '{"text":"I will be 15 minutes late."}'}
    review = {"version": 1, "reviewer": "Independent AI fixture", "reviews": {first["id"]: {
        "response_sha256": digest(labeled["response"].encode()), "approved": True, "reason": "Fixture preserves source"}}}
    assert reviewed_writing({"train": [labeled]}, None)[0]["train"] == []
    assert reviewed_writing({"train": [labeled]}, review)[0]["train"] == [labeled]
    changed = {**labeled, "response": '{"text":"I will be 50 minutes late."}'}
    try:
        reviewed_writing({"train": [changed]}, review)
    except ValueError:
        pass
    else:
        raise AssertionError("Stale writing review response hash was accepted")
    assert [item["id"] for item in validation_subset([first, second, frozen], 2)] == ["one", "three"]
    assert response_mask([1, 2, 3, 4], [(0, 0), (0, 4), (3, 6), (6, 8)], 4) == [-100, -100, -100, 4]
    feature = response_features(FakeTokenizer(), first, '{"text":"hello"}', 10000)
    start = next(index for index, value in enumerate(feature["labels"]) if value != -100)
    assert all(value == -100 for value in feature["labels"][:start])
    assert all(value != -100 for value in feature["labels"][start:])
    short = {"input_ids": [7, 8], "labels": [-100, 8]}
    batch = pad_batch([short, {"input_ids": [7, 8, 9], "labels": [-100, 8, 9]}], 8)
    assert batch["labels"][0] == [-100, 8, -100] and batch["attention_mask"][0] == [1, 1, 0]
    try:
        response_features(FakeTokenizer(), first, "hello", 2)
    except ValueError:
        pass
    else:
        raise AssertionError("Overlength sequence was silently truncated")
    print("Distillation self-check passed: label gates, split guards, response masks, padding and limits")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("self-check")
    labels = commands.add_parser("label", help="Generate and strictly gate pinned teacher labels")
    labels.add_argument("--train", type=Path, required=True)
    labels.add_argument("--validation", type=Path, required=True)
    labels.add_argument("--regression", type=Path, default=ROOT.parents[1] / "tests" / "slm-regression.json")
    labels.add_argument("--out", type=Path, required=True)
    labels.add_argument("--resume", action="store_true")
    labels.add_argument("--batch-size", type=int, default=8)
    labels.add_argument("--max-input-tokens", type=int, default=1536)
    labels.add_argument("--max-new-tokens", type=int, default=256)
    labels.add_argument("--seed", type=int, default=20261021)
    labels.add_argument("--attention", choices=("sdpa", "eager"), default="sdpa")
    training = commands.add_parser("train", help="Full student SFT using validation loss for selection")
    training.add_argument("--data", type=Path, required=True)
    training.add_argument("--out", type=Path, required=True)
    training.add_argument("--epochs", type=int, default=2)
    training.add_argument("--batch-size", type=int, default=4)
    training.add_argument("--accumulate", type=int, default=4)
    training.add_argument("--learning-rate", type=float, default=5e-5)
    training.add_argument("--max-sequence", type=int, default=2048)
    training.add_argument("--seed", type=int, default=20261021)
    training.add_argument("--attention", choices=("sdpa", "eager"), default="sdpa")
    training.add_argument("--trials", type=int, choices=range(4), default=0,
                          help="0: one run; 1..3: bounded Optuna LR grid, selected on synthetic validation responses")
    training.add_argument("--validation-generation-limit", type=int, default=48,
                          help="Fixed family-stratified synthetic validation subset; never frozen regression")
    training.add_argument("--validation-candidates", type=Path,
                          help="Original synthetic validation JSONL including teacher rejections; required for Optuna")
    training.add_argument("--writing-review", type=Path,
                          help="Independent response-SHA-bound approvals; unreviewed writing is excluded by default")
    args = parser.parse_args()
    for key in ("batch_size", "epochs", "accumulate", "max_sequence", "max_input_tokens", "max_new_tokens"):
        if hasattr(args, key) and getattr(args, key) < 1:
            parser.error(key + " must be positive")
    if hasattr(args, "learning_rate") and (not math.isfinite(args.learning_rate) or args.learning_rate <= 0):
        parser.error("learning-rate must be finite and positive")
    if hasattr(args, "validation_generation_limit") and args.validation_generation_limit < 0:
        parser.error("validation-generation-limit must be nonnegative")
    {"label": label, "train": train, "self-check": lambda _: self_check()}[args.command](args)


if __name__ == "__main__":
    main()
