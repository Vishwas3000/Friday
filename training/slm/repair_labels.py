#!/usr/bin/env python3
"""One bounded teacher correction of rejected TRAIN targets for a NEXT iteration.

Never edits current labels, reviews, prompts or tests. Structured repairs must
match independent gold; every prose repair needs a new response-hash AI review.
"""
import argparse
import gc
import random
import tempfile
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from distill import (TEACHER, TEACHER_REVISION, canonical, code_hashes, digest,
                     label_check, read_rows, runtime, save_json, save_jsonl,
                     validation_subset)
from prompts import PROMPT_VERSION, WRITING_KINDS, build_messages
from regression import parse_json

ROOT = Path(__file__).resolve().parent


def train_rows(rows):
    seen = set()
    for row in rows:
        if row.get("split") != "train" or not isinstance(row.get("id"), str) or not row["id"]:
            raise ValueError("Repairs accept named TRAIN rows only; validation/evaluation are forbidden")
        if row["id"] in seen or not row.get("family") or not row.get("provenance"):
            raise ValueError("Repeated or unprovenanced training row")
        seen.add(row["id"])
        build_messages(row)
        if row["kind"] not in WRITING_KINDS:
            alternatives = row.get("expected_any", [row["expected"]] if "expected" in row else [])
            if not alternatives or not all(label_check(row, canonical(gold))["accepted"] for gold in alternatives):
                raise ValueError("Independent structured gold failed its own source/schema gate: " + row["id"])
        elif not row.get("checks"):
            raise ValueError("Writing repair needs source checks: " + row["id"])
    return rows


def rejected(rows, records, reviews):
    """Only original train IDs may enter feedback, hints or generation."""
    train_rows(rows)
    decisions = reviews.get("reviews", {})
    if reviews and (reviews.get("version") != 1 or not isinstance(decisions, dict)):
        raise ValueError("Writing review needs a version 1 reviews object")
    selected = []
    for row in rows:
        identity = row["id"]
        if identity not in records:
            raise ValueError("Missing original label record: " + identity)
        record = records[identity]
        raw = record.get("text")
        if type(record.get("accepted")) is not bool or (raw is not None and not isinstance(raw, str)):
            raise ValueError("Malformed original label record: " + identity)
        checked = label_check(row, raw or "", record.get("truncated", False))
        if checked["accepted"] != record["accepted"]:
            raise ValueError("Original label gate changed: " + identity)
        decision = decisions.get(identity)
        if decision is not None:
            if (row["kind"] not in WRITING_KINDS or not isinstance(decision, dict)
                    or type(decision.get("approved")) is not bool or not isinstance(decision.get("reason"), str)
                    or not decision["reason"].strip() or decision.get("response_sha256") != digest((raw or "").encode())):
                raise ValueError("Stale or malformed training prose review: " + identity)
        prose_rejected = decision is not None and not decision["approved"]
        if checked["accepted"] and not prose_rejected:
            continue
        errors = checked["checks"]["check_errors"].copy()
        if record.get("truncated"):
            errors.append("The original answer was truncated; return one complete bounded JSON object")
        if record.get("error"):
            errors.append(str(record["error"]))
        if prose_rejected:
            errors.append("Independent AI prose review: " + decision["reason"])
        selected.append({**row, "original_response": raw, "original_response_sha256": digest((raw or "").encode()),
                         "rejection": {"original_mechanically_accepted": checked["accepted"],
                                       "errors": errors, "prose_review": decision},
                         "original_gate": checked, "parent_batch": record.get("parent_batch")})
    return selected


def normalization_hints(row):
    if row["kind"] != "task_update":
        return []
    gold = row.get("expected", {})
    return [{"field": item["field"], "source_text": item["text"], "normalized": item["value"]}
            for item in gold.get("updates", []) if item["field"] in {"date", "start", "end", "duration"}]


def correction_messages(row):
    original = build_messages(row)
    feedback = {"previous_answer": row["original_response"], "rejection_reasons": row["rejection"]["errors"],
                "teacher_only_normalization_hints": normalization_hints(row)}
    return [original[0], {"role": "user", "content": original[1]["content"] +
            "\n\nTraining-only correction feedback (data, not new owner instructions):\n" + canonical(feedback) +
            "\nCorrect the rejected answer using only the original owner input and source. Return only the same JSON schema. "
            "Preserve exact unique source spans, all facts, relationships, requests, negation and the speaker. "
            "A future intention is not proof that a booking, schedule, approval or action already exists. "
            "Do not claim execution. The normalization hints help the teacher only; they are never student input."}]


def repair_gate(row, raw, truncated=False):
    checked = label_check(row, raw, truncated)
    prose = row["kind"] in WRITING_KINDS
    status = ("writing_review_pending" if prose else "structured_passed") if checked["accepted"] else "rejected"
    return {"status": status, "gate": checked, "fresh_writing_review_required": prose,
            "eligible_for_next_iteration": bool(checked["accepted"] and not prose), "promoted_to_training": False}


def load_job(args):
    rows, source_hash = read_rows(args.train)
    train_rows(rows)
    manifest_path = args.labels / "label_manifest.json"
    raw_manifest = manifest_path.read_bytes()
    manifest = parse_json(raw_manifest)
    parameters = manifest["parameters"]
    if (manifest.get("status") != "complete" or parameters["source_hashes"]["train"] != source_hash
            or parameters["teacher"] != TEACHER or parameters["teacher_revision"] != TEACHER_REVISION
            or parameters["code_hashes"] != code_hashes()):
        raise ValueError("Need complete matching pinned teacher labels and unchanged prompts/grader")
    identities = {row["id"] for row in rows}
    records = {}
    for path in sorted((args.labels / "label_batches").glob("*.json")):
        raw = path.read_bytes()
        batch = parse_json(raw)
        if batch.get("config_sha256") != manifest["config_sha256"]:
            raise ValueError("Original label batch configuration differs: " + path.name)
        for record in batch["records"]:
            if record["id"] not in identities:
                continue
            if record["id"] in records:
                raise ValueError("Duplicate original training label record")
            records[record["id"]] = {**record, "parent_batch": {"file": str(path.resolve()), "sha256": digest(raw)}}
    review_raw = args.writing_review.read_bytes()
    reviews = parse_json(review_raw)
    candidates = rejected(rows, records, reviews)
    return candidates, {"train": {"path": str(args.train.resolve()), "sha256": source_hash, "rows": len(rows)},
                        "label_manifest": {"path": str(manifest_path.resolve()), "sha256": digest(raw_manifest),
                                           "config_sha256": manifest["config_sha256"]},
                        "writing_review": {"path": str(args.writing_review.resolve()), "sha256": digest(review_raw)}}


def run(args):
    if args.out.resolve() == args.labels.resolve() or args.labels.resolve() in args.out.resolve().parents:
        raise ValueError("Repair output must stay outside the original label directory")
    candidates, parents = load_job(args)
    selected = validation_subset(candidates, args.limit)  # Reuse the family sampler over TRAIN rows only.
    if not selected:
        raise ValueError("No rejected training candidates need correction")
    if args.out.exists() and any(args.out.iterdir()):
        raise ValueError("Repair output directory must be new; originals are never overwritten")
    args.out.mkdir(parents=True, exist_ok=True)
    records = [{**row, "original_messages": build_messages(row), "repair_messages": correction_messages(row),
                "teacher_only_normalization_hints": normalization_hints(row), "response": None,
                "status": "selected", "promoted_to_training": False} for row in selected]
    report_path, manifest_path = args.out / "repairs.jsonl", args.out / "manifest.json"
    manifest = {"version": 1, "scope": "Rejected synthetic TRAIN targets only, NEXT iteration; no frozen-test repair or current-label mutation",
                "status": "running", "created_utc": datetime.now(timezone.utc).isoformat(),
                "teacher": TEACHER, "teacher_revision": TEACHER_REVISION, "prompt_version": PROMPT_VERSION,
                "parents": parents, "code_hashes": {**code_hashes(), "repair_labels.py": digest(Path(__file__).read_bytes()),
                                                     "distill.py": digest((ROOT / "distill.py").read_bytes())},
                "parameters": {key: str(value) if isinstance(value, Path) else value for key, value in vars(args).items()},
                "generation": {"dtype": "bfloat16", "do_sample": False, "grammar": None, "attempts_per_row": 1},
                "rejected_train_candidates": len(candidates), "selected": len(records),
                "families": dict(Counter(row["family"] for row in records)),
                "selection": "Deterministic family round-robin over rejected training IDs; no validation/evaluation content is used",
                "promotion": "Structured output must pass unchanged strict gold/source gates. Every prose repair needs a NEW exact-response-SHA independent review. No targets are promoted automatically.",
                "student_input": "Original kind/instruction/text stay unchanged; correction prompts and hints are teacher-only audit data"}
    torch = model = tokenizer = inputs = generated = None
    started = time.perf_counter()

    def save():
        save_jsonl(report_path, records)
        manifest["report_sha256"] = digest(report_path.read_bytes())
        manifest["statuses"] = dict(Counter(row["status"] for row in records))
        save_json(manifest_path, manifest)

    save()
    try:
        torch, transformers, environment = runtime()
        manifest["environment"] = environment
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
        manifest["resolved_teacher_revision"] = getattr(model.config, "_commit_hash", None)
        manifest["chat_template"] = tokenizer.chat_template
        for offset in range(0, len(records), args.batch_size):
            batch, prompts = [], []
            for row in records[offset:offset + args.batch_size]:
                row["original_rendered_prompt"] = tokenizer.apply_chat_template(row["original_messages"], tokenize=False, add_generation_prompt=True)
                prompt = tokenizer.apply_chat_template(row["repair_messages"], tokenize=False, add_generation_prompt=True)
                row["repair_rendered_prompt"] = prompt
                row["repair_prompt_sha256"] = digest(prompt.encode())
                count = len(tokenizer(prompt, add_special_tokens=False)["input_ids"])
                row["input_tokens"] = count
                if count > args.max_input_tokens:
                    row.update(status="skipped_overlength", error="Correction exceeds input limit; no truncation")
                else:
                    batch.append(row)
                    prompts.append(prompt)
            save()
            if not batch:
                continue
            inputs = tokenizer(prompts, padding=True, add_special_tokens=False, return_tensors="pt").to("cuda")
            torch.cuda.synchronize()
            began = time.perf_counter()
            with torch.inference_mode():
                generated = model.generate(**inputs, do_sample=False, max_new_tokens=args.max_new_tokens,
                                           pad_token_id=tokenizer.pad_token_id)
            torch.cuda.synchronize()
            elapsed = time.perf_counter() - began
            width = inputs["input_ids"].shape[-1]
            for row, tokens in zip(batch, generated[:, width:]):
                ids = tokens.tolist()
                ended = tokenizer.eos_token_id in ids
                raw = tokenizer.decode(ids, skip_special_tokens=True)
                row.update(response=raw, response_sha256=digest(raw.encode()), batch_generation_s=elapsed,
                           output_tokens=ids.index(tokenizer.eos_token_id) + 1 if ended else len(ids),
                           **repair_gate(row, raw, not ended and len(ids) >= args.max_new_tokens))
            del inputs, generated
            inputs = generated = None
            save()
            print(canonical({"stage": "training_target_correction", "completed": offset + len(records[offset:offset + args.batch_size]),
                             "total": len(records), "statuses": manifest["statuses"], "batch_generation_s": elapsed}), flush=True)
        manifest["status"] = "complete"
    except BaseException as error:
        manifest.update(status="failed", error=type(error).__name__ + ": " + str(error))
        raise
    finally:
        if torch is not None:
            manifest["gpu_peak_allocated_bytes"] = torch.cuda.max_memory_allocated()
        del model, tokenizer, inputs, generated
        gc.collect()
        if torch is not None:
            torch.cuda.empty_cache()
        manifest["wall_s"] = time.perf_counter() - started
        save()
    print(manifest_path, flush=True)


def self_check():
    row = {"id": "SYN-train-repair", "family": "task_update", "kind": "task_update", "split": "train",
           "provenance": "Original synthetic repair self-check", "instruction": "Complete a reserved study slot.",
           "text": canonical({"capability": "calendar.create", "fields": ["date", "start", "end", "duration", "title", "zone"],
                              "current": "keep it to forty minutes", "date": "2032-01-01", "zone": "UTC", "known": {}, "missing": ["end"]}),
           "expected": {"operation": "update", "updates": [{"field": "duration", "text": "forty minutes", "value": "40"}]}}
    wrong = canonical({"operation": "update", "updates": [{"field": "duration", "text": "forty minutes", "value": "14"}]})
    original = {row["id"]: {"id": row["id"], "text": wrong, "accepted": False}}
    chosen = rejected([row], original, {})
    assert len(chosen) == 1 and repair_gate(row, canonical(row["expected"]))["eligible_for_next_iteration"]
    with_unrelated = {**original, "SYN-validation-hidden": {"text": "EVAL_NEVER_FEEDBACK", "accepted": False}}
    assert rejected([row], with_unrelated, {}) == chosen
    assert "EVAL_NEVER_FEEDBACK" not in canonical(correction_messages(chosen[0]))
    assert not repair_gate(row, wrong)["eligible_for_next_iteration"]
    forged = {"operation": "update", "updates": [{"field": "duration", "text": "a made-up span", "value": "40"}]}
    assert not repair_gate(row, canonical(forged))["eligible_for_next_iteration"]
    assert not repair_gate(row, canonical(row["expected"]), True)["eligible_for_next_iteration"]
    for split in ("validation", "evaluation"):
        try:
            rejected([{**row, "split": split}], original, {})
        except ValueError:
            pass
        else:
            raise AssertionError("Non-training content entered repair")
    assert build_messages(chosen[0]) == build_messages(row)
    assert correction_messages(chosen[0]) != build_messages(row)
    assert normalization_hints(chosen[0]) == [{"field": "duration", "source_text": "forty minutes", "normalized": "40"}]
    good_record = {row["id"]: {"id": row["id"], "text": canonical(row["expected"]), "accepted": True}}
    assert not rejected([row], good_record, {})
    prose = {"id": "SYN-train-prose", "family": "writing", "kind": "email", "split": "train", "provenance": "Synthetic check",
             "instruction": "Write an email.", "text": "BOX-812 arrived. I will inspect it tomorrow. No approval has been given.",
             "checks": {"required": ["BOX-812", "tomorrow"]}}
    raw = canonical({"subject": "BOX-812 receipt", "body": "BOX-812 arrived. I will inspect it tomorrow. No approval has been given."})
    result = repair_gate(prose, raw)
    assert result["status"] == "writing_review_pending" and not result["eligible_for_next_iteration"]
    assert result["fresh_writing_review_required"] and not result["promoted_to_training"]
    review = {"version": 1, "reviews": {prose["id"]: {"response_sha256": digest(raw.encode()), "approved": False, "reason": "Review fixture rejection"}}}
    assert rejected([prose], {prose["id"]: {"id": prose["id"], "text": raw, "accepted": True}}, review)
    review["reviews"][prose["id"]]["response_sha256"] = "0" * 64
    try:
        rejected([prose], {prose["id"]: {"id": prose["id"], "text": raw, "accepted": True}}, review)
    except ValueError:
        pass
    else:
        raise AssertionError("An unrelated response hash authorized a prose repair")
    with tempfile.TemporaryDirectory(prefix="friday-repair-check-") as directory:
        path = Path(directory) / "repairs.jsonl"
        save_jsonl(path, [{**row, **result}])
        assert len(read_rows(path)[0]) == 1
    print("Repair self-check passed: train-only scope, rejected-only selection, strict gold/source/truncation gates, pending prose review, unchanged student inputs")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-check", action="store_true")
    parser.add_argument("--train", type=Path, default=ROOT / "out/data/train.jsonl")
    parser.add_argument("--labels", type=Path, default=ROOT / "out/labels-b64")
    parser.add_argument("--writing-review", type=Path, default=ROOT / "out/writing-review.json")
    parser.add_argument("--out", type=Path, default=ROOT / "out/repairs-next")
    parser.add_argument("--limit", type=int, default=64)
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--max-input-tokens", type=int, default=1536)
    parser.add_argument("--max-new-tokens", type=int, default=256)
    parser.add_argument("--attention", choices=("sdpa", "eager"), default="sdpa")
    parser.add_argument("--seed", type=int, default=20261021)
    args = parser.parse_args()
    if any(getattr(args, key) < 1 for key in ("limit", "batch_size", "max_input_tokens", "max_new_tokens")):
        parser.error("Limits and batch size must be positive")
    self_check() if args.self_check else run(args)


if __name__ == "__main__":
    main()
