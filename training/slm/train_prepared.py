#!/usr/bin/env python3
"""Response-only mixed prepared-corpus SFT; offline research, never device execution.

Uses the pinned BASE student, not the earlier distilled checkpoint. Public reference
targets and sampled/AI review do not certify semantics or Android task completion.
"""
import argparse
import ast
import gc
import hashlib
import math
import os
import pickle
import random
import re
import shutil
import signal
import tempfile
import time
from array import array
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

from distill import STUDENT, STUDENT_REVISION, canonical, digest, pad_batch, response_mask, runtime, save_json, weight_hashes
from prompts import build_messages
from regression import NEGATIVE, NUMBER, identifiers, load_cases, parse_json

ROOT = Path(__file__).resolve().parent
STATE_PINS = {
    "sgd": ("google-research-datasets/dstc8-schema-guided-dialogue", "e852981ae34990f4358979625854259302feaa78", "train/schema.json"),
    "multiwoz22": ("budzianowski/multiwoz", "fe0c8e65cfcd8462bd33c86e35f21addc84ca82b", "data/MultiWOZ_2.2/schema.json"),
}
WRITING_PINS = {"grammarly/coedit": "e9a255c33ef910bc33a9d2b522653fa87521583e",
                "cylnlp/dialogsum": "848cc8c05e0a04def79326539de827f1b28786a0"}
SCOPE = "Prepared public TRAIN references; full response-only SFT; no phone/runtime parity or semantic certification"
TOKENIZATION_BATCH_SIZE = 128
TOKENIZER_THREADS = 16


def file_hash(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def document(path):
    raw = path.read_bytes()
    value = parse_json(raw)
    if not isinstance(value, dict):
        raise ValueError("Expected JSON object: " + str(path))
    return value, digest(raw)


def inside(root, relative):
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()):
        raise ValueError("Path escapes dataset: " + str(relative))
    return path


def checked_file(path, spec):
    actual = file_hash(path)
    if actual != spec["sha256"] or ("bytes" in spec and path.stat().st_size != spec["bytes"]):
        raise ValueError("File hash/length mismatch: " + str(path))
    return actual


def literal_system(path):
    tree = ast.parse(path.read_text(encoding="utf-8"))
    return next(ast.literal_eval(node.value) for node in tree.body if isinstance(node, ast.Assign)
                and any(isinstance(target, ast.Name) and target.id == "SYSTEM" for target in node.targets))


def state_schema_errors(value, schema):
    if not isinstance(value, dict) or set(value) != {"service", "active_intent", "slot_values", "requested_slots"}:
        return ["shape"]
    if (not isinstance(value["service"], str) or not isinstance(value["active_intent"], str)
            or not isinstance(value["slot_values"], dict) or not isinstance(value["requested_slots"], list)
            or not all(isinstance(slot, str) for slot in value["requested_slots"])):
        return ["types"]
    slots = {slot["name"]: slot for slot in schema["slots"]}
    if value["service"] != schema["service_name"] or value["active_intent"] not in {i["name"] for i in schema["intents"]} | {"NONE"}:
        return ["service_or_intent"]
    if not value["slot_values"].keys() <= slots.keys() or not set(value["requested_slots"]) <= slots.keys():
        return ["slot_names"]
    for name, values in value["slot_values"].items():
        if not isinstance(values, list) or not values or not all(isinstance(v, str) and v for v in values):
            return ["slot_values"]
        if slots[name].get("is_categorical") and not set(values) <= set(slots[name].get("possible_values", [])) | {"dontcare"}:
            return ["categorical_enum"]
    return []


def sources(args):
    """Bind prepared files to the exact adapter, raw source manifests and audited template."""
    code = {name: file_hash(ROOT / name) for name in
            ("train_prepared.py", "distill.py", "probe.py", "prompts.py", "regression.py", "dialogue_data.py", "verify_dialogue_data.py", "writing_data.py")}
    state, state_hash = document(args.state_data / "manifest.json")
    audit, audit_hash = document(args.state_data / "independent-check.json")
    raw, raw_hash = document(args.state_sources / "manifest.json")
    if state.get("status") != "complete" or state.get("protocol") != "dialogue_state_v2" or raw.get("status") != "complete":
        raise ValueError("Need completed v2 preparation and source download")
    if state["adapter_sha256"] != code["dialogue_data.py"] or state["source_manifest"]["sha256"] != raw_hash:
        raise ValueError("State adapter/source manifest differs from preparation")
    system = literal_system(ROOT / "dialogue_data.py")
    for field, expected in (("manifest_sha256", state_hash), ("source_manifest_sha256", raw_hash),
                            ("adapter_sha256", code["dialogue_data.py"]), ("audit_script_sha256", code["verify_dialogue_data.py"]),
                            ("system_prompt_sha256", digest(system.encode())), ("rows_verified", state["prepared_rows"])):
        if audit.get(field) != expected:
            raise ValueError("Independent state audit binding mismatch: " + field)
    for field in ("declared_target_schema_violations", "categorical_enum_violations", "target_vs_current_human_annotation_mismatches",
                  "input_vs_prior_states_and_raw_utterances_mismatches", "conversation_overlap", "input_fingerprint_overlap"):
        if audit.get(field) != 0:
            raise ValueError("Independent state audit failed: " + field)
    if audit.get("all_sources_official_train") is not True:
        raise ValueError("State audit did not establish official TRAIN provenance")
    entries, schemas = {}, {}
    for entry in raw["files"].values():
        dataset = entry["dataset"]
        if dataset not in STATE_PINS or (entry["repo"], entry["revision"]) != STATE_PINS[dataset][:2]:
            raise ValueError("Unexpected state dataset/revision")
        checked_file(inside(args.state_sources, dataset + "/" + entry["source_path"]), entry)
        entries[(dataset, entry["source_path"])] = entry
    for dataset, (_, _, relative) in STATE_PINS.items():
        schema_path = inside(args.state_sources, dataset + "/" + relative)
        schemas[dataset] = {s["service_name"]: s for s in parse_json(schema_path.read_bytes())}
    writing, writing_hash = document(args.writing_data / "manifest.json")
    if writing.get("status") != "complete" or writing["adapter_sha256"] != code["writing_data.py"] or writing["prompt_source_sha256"] != code["prompts.py"]:
        raise ValueError("Writing preparation/prompt/adapter mismatch")
    from writing_data import FILES
    if writing["sources"] != FILES:
        raise ValueError("Writing source inventory differs from pinned adapter")
    for entry in writing["sources"]:
        if WRITING_PINS.get(entry["dataset"]) != entry["revision"]:
            raise ValueError("Unexpected writing dataset revision")
        checked_file(inside(args.writing_raw, entry["path"]), entry)
    for folder, manifest in ((args.state_data, state), (args.writing_data, writing)):
        for split in ("train", "validation"):
            checked_file(inside(folder, manifest["files"][split]["path"]), manifest["files"][split])
    review, review_hash = document(args.writing_review)
    if review.get("version") != 1 or not isinstance(review.get("reviewer"), str) or not review["reviewer"].strip():
        raise ValueError("Writing review needs version 1 and named reviewer")
    if review.get("source_manifest_sha256") != writing_hash or not isinstance(review.get("reviews"), dict) or not review["reviews"]:
        raise ValueError("Writing review must bind this manifest and name reviewed rows")
    frozen, frozen_sources = load_cases(args.frozen)
    frozen_inputs = {digest(canonical(build_messages(row)).encode()) for row in frozen}
    return {"code_hashes": code, "state": state, "writing": writing, "entries": entries, "schemas": schemas,
            "system": system, "review": review, "frozen_inputs": frozen_inputs, "frozen_ids": {r["id"] for r in frozen},
            "identity": {"state_manifest_sha256": state_hash, "state_source_manifest_sha256": raw_hash,
                         "state_audit_sha256": audit_hash, "writing_manifest_sha256": writing_hash,
                         "writing_review_sha256": review_hash, "frozen_sources": frozen_sources, "code_hashes": code}}


def writing_references(args, info):
    """Retain only hashes of publisher records, not a second in-memory text corpus."""
    refs = {}
    for spec in info["writing"]["sources"]:
        if not spec["path"].endswith(".jsonl"):
            continue
        with inside(args.writing_raw, spec["path"]).open("rb") as stream:
            for line_no, raw in enumerate(stream, 1):
                if not raw.strip():
                    continue
                record = parse_json(raw)
                if spec["dataset"] == "grammarly/coedit":
                    if record["task"] != "gec":
                        continue
                    instruction, separator, text = record["src"].partition(": ")
                    if not separator:
                        raise ValueError("Missing publisher instruction separator")
                    target, identity, kind = record["tgt"], str(record["_id"]), "rewrite"
                else:
                    instruction = "Summarize this dialogue briefly from an observer's perspective, retaining its main facts and uncertainty."
                    text, target, identity, kind = record["dialogue"], record["summary"], record["fname"], "summary"
                messages = build_messages({"kind": kind, "instruction": instruction, "text": text})
                group = digest(re.sub(r"\s+", " ", text).strip().casefold().encode())
                refs[(spec["path"], line_no)] = {"dataset": spec["dataset"], "revision": spec["revision"], "license": spec["license"],
                    "record_id": identity, "record_sha256": digest(raw), "input_sha256": digest(canonical(messages).encode()),
                    "response_sha256": digest(canonical({"text": target}).encode()), "group": group,
                    "source_file_sha256": spec["sha256"], "kind": kind}
    return refs


def check_review(row, reviews):
    decision = reviews.get(row["id"])
    if decision is None:
        return None
    if (not isinstance(decision, dict) or type(decision.get("approved")) is not bool
            or not isinstance(decision.get("reason"), str) or not decision["reason"].strip()
            or decision.get("input_sha256") != row["provenance"]["input_sha256"]
            or decision.get("response_sha256") != row["provenance"]["response_sha256"]):
        raise ValueError("Invalid/stale writing review for " + row["id"])
    return decision["approved"]


def guard_identity(row, input_hash, group, seen_ids, seen_inputs, seen_groups, info):
    if row["id"] in seen_ids or row["id"] in info["frozen_ids"] or input_hash in info["frozen_inputs"]:
        raise ValueError("Repeated identity or frozen exact-input leakage: " + row["id"])
    seen_ids.add(row["id"])
    for value, seen in ((input_hash, seen_inputs), (group, seen_groups)):
        if value in seen and seen[value] != row["split"]:
            raise ValueError("Train/validation input or source-group leakage: " + row["id"])
        seen[value] = row["split"]


def prepared_rows(args, info, stats):
    refs = writing_references(args, info)
    seen_ids, seen_inputs, seen_groups, reviewed_ids = set(), {}, {}, set()
    for folder, manifest, branch in ((args.state_data, info["state"], "state"), (args.writing_data, info["writing"], "writing")):
        for split in ("train", "validation"):
            count = 0
            with inside(folder, manifest["files"][split]["path"]).open("rb") as stream:
                for line in stream:
                    if not line.strip():
                        continue
                    row = parse_json(line)
                    count += 1
                    if (not isinstance(row.get("id"), str) or not row["id"] or row.get("split") != split
                            or [m["role"] for m in row["messages"]] != ["system", "user"]
                            or not all(isinstance(m.get("content"), str) for m in row["messages"])):
                        raise ValueError("Invalid prepared identity/messages/split")
                    p, target = row["provenance"], parse_json(row["response"])
                    if p.get("source_split") != "train" or not p.get("license"):
                        raise ValueError("Missing official TRAIN source/license: " + row["id"])
                    ih, th = digest(canonical(row["messages"]).encode()), digest(row["response"].encode())
                    if branch == "state":
                        dataset = p["dataset"]
                        if dataset not in STATE_PINS or row["kind"] != "dialogue_state_v2":
                            raise ValueError("Unknown state dataset/kind")
                        if (p["repo"], p["commit"]) != STATE_PINS[dataset][:2] or row["messages"][0]["content"] != info["system"]:
                            raise ValueError("State revision/SYSTEM differs from audited adapter")
                        entry = info["entries"][(dataset, p["file"])]
                        if entry.get("split") != "train" or p["file_sha256"] != entry["sha256"]:
                            raise ValueError("State source shard identity differs")
                        schema_entry = info["entries"][(dataset, STATE_PINS[dataset][2])]
                        payload = parse_json(row["messages"][1]["content"])
                        schema = info["schemas"][dataset][p["service"]]
                        if (p["schema_sha256"] != schema_entry["sha256"] or payload["selected_service_schema"] != schema
                                or state_schema_errors(target, schema)):
                            raise ValueError("State target/schema mismatch: " + row["id"])
                        if ih != p["input_fingerprint"] or th != p["target_fingerprint"] or digest((ih + "\n" + th).encode()) != p["fingerprint"]:
                            raise ValueError("State row fingerprint mismatch")
                        group = "dialogue:" + dataset + ":" + p["dialogue_id"]
                        threshold = int(manifest["parameters"]["validation_fraction"] * (1 << 256))
                        expected = "validation" if int(digest((dataset + "\n" + p["dialogue_id"]).encode()), 16) < threshold else "train"
                        family = "state/" + dataset + "/" + p["service"]
                    else:
                        ref = refs[(p["source_file"], p["source_line"])]
                        if (row["kind"] != ref["kind"] or p["dataset"] != ref["dataset"] or p["revision"] != ref["revision"]
                                or p["source_record_id"] != ref["record_id"] or p["source_record_sha256"] != ref["record_sha256"]
                                or p["source_file_sha256"] != ref["source_file_sha256"] or p["source_group_sha256"] != ref["group"]
                                or ih != ref["input_sha256"] or th != ref["response_sha256"]
                                or ih != p["input_sha256"] or th != p["response_sha256"]
                                or not isinstance(target, dict) or set(target) != {"text"} or not isinstance(target["text"], str)):
                            raise ValueError("Writing row differs from publisher reference: " + row["id"])
                        group, family = "writing:" + ref["group"], "writing/" + row["kind"]
                        expected = "validation" if int(ref["group"][:16], 16) % 100 < 5 else "train"
                    if expected != split:
                        raise ValueError("Source-group split mismatch: " + row["id"])
                    guard_identity(row, ih, group, seen_ids, seen_inputs, seen_groups, info)
                    stats[split + "/source/" + family] += 1
                    if branch == "writing":
                        approved = check_review(row, info["review"]["reviews"])
                        if approved is not None:
                            reviewed_ids.add(row["id"])
                            stats[split + "/review_" + ("approved/" if approved else "rejected/") + family] += 1
                        if approved is not True:
                            stats[split + "/excluded_review/" + family] += 1
                            continue
                    yield {**row, "branch": branch, "family": family, "group": group}
            expected_count = manifest["files"][split].get("rows", manifest.get("counts", {}).get(split + "/rows"))
            if count != expected_count:
                raise ValueError("Prepared row count mismatch")
    if reviewed_ids != info["review"]["reviews"].keys():
        raise ValueError("Review names unknown prepared writing IDs")


def example_texts(tokenizer, row):
    messages, response = row["messages"], row["response"]
    prefix = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
    full = tokenizer.apply_chat_template(messages + [{"role": "assistant", "content": response}], tokenize=False, add_generation_prompt=False)
    if not full.startswith(prefix + response):
        raise ValueError("Template altered response/prefix")
    return prefix, full


def encoded_feature(tokenizer, row, maximum, prefix, encoded, prompt_ids=None):
    if len(encoded["input_ids"]) > maximum:
        return None
    labels = response_mask(encoded["input_ids"], encoded["offset_mapping"], len(prefix))
    if tokenizer.eos_token_id is None or tokenizer.eos_token_id not in [label for label in labels if label != -100]:
        raise ValueError("Assistant EOS is not supervised: " + row["id"])
    feature = {key: row[key] for key in ("id", "kind", "branch", "family", "group")}
    feature.update({"input_ids": array("i", encoded["input_ids"]), "labels": array("i", labels),
                    "response_tokens": sum(label != -100 for label in labels[1:])})
    if row["split"] == "validation":
        if prompt_ids is None:
            prompt_ids = tokenizer(prefix, add_special_tokens=False, padding=False, truncation=False)["input_ids"]
        feature.update({"messages": row["messages"], "response": row["response"], "prompt_ids": array("i", prompt_ids)})
    return feature


def tokenize_example(tokenizer, row, maximum):
    """Single-example oracle retained for focused batch-equivalence checks."""
    prefix, full = example_texts(tokenizer, row)
    encoded = tokenizer(full, add_special_tokens=False, return_offsets_mapping=True, padding=False, truncation=False)
    return encoded_feature(tokenizer, row, maximum, prefix, encoded)


def tokenize_examples(tokenizer, rows, maximum):
    if not rows:
        return []
    rendered = [example_texts(tokenizer, row) for row in rows]
    encoded = tokenizer([full for _, full in rendered], add_special_tokens=False,
                        return_offsets_mapping=True, padding=False, truncation=False)
    validation_indices = [i for i, row in enumerate(rows)
                          if row["split"] == "validation" and len(encoded["input_ids"][i]) <= maximum]
    prefixes = {}
    if validation_indices:
        batch = tokenizer([rendered[i][0] for i in validation_indices], add_special_tokens=False,
                          padding=False, truncation=False)
        prefixes = dict(zip(validation_indices, batch["input_ids"]))
    return [encoded_feature(tokenizer, row, maximum, rendered[i][0],
            {"input_ids": encoded["input_ids"][i], "offset_mapping": encoded["offset_mapping"][i]}, prefixes.get(i))
            for i, row in enumerate(rows)]


def feature_cache(args, info, tokenizer):
    identity = {**info["identity"], "student": STUDENT, "revision": STUDENT_REVISION,
                "max_sequence": args.max_sequence, "writing_policy": "reviewed-only",
                "chat_template_sha256": digest(tokenizer.chat_template.encode()),
                "tokenization_batch_size": TOKENIZATION_BATCH_SIZE, "tokenizer_threads": TOKENIZER_THREADS}
    key = digest(canonical(identity).encode())
    path = args.out / "features"
    metadata_path = path / "manifest.json"
    if metadata_path.exists():
        metadata, _ = document(metadata_path)
        if metadata.get("identity_sha256") != key:
            raise ValueError("Feature cache differs from current data/code/template/policy")
        data = {}
        for split, spec in metadata["files"].items():
            cache = inside(path, spec["path"])
            checked_file(cache, spec)
            # Only our own hash-bound local cache is unpickled, never publisher inputs.
            with cache.open("rb") as stream:
                data[split] = pickle.load(stream)
        return data, metadata
    path.mkdir(exist_ok=True)
    data, stats = {"train": [], "validation": []}, Counter()
    started, seen = time.perf_counter(), 0
    next_report = 2000
    def consume(rows):
        nonlocal seen, next_report
        for row, feature in zip(rows, tokenize_examples(tokenizer, rows, args.max_sequence)):
            tag = row["split"] + "/" + row["family"]
            if feature is None:
                stats["overlength_rows/" + tag] += 1
            else:
                data[row["split"]].append(feature)
                stats["accepted_rows/" + tag] += 1
                stats["response_tokens/" + tag] += feature["response_tokens"]
                stats["sequence_tokens/" + tag] += len(feature["input_ids"])
        seen += len(rows)
        if seen >= next_report:
            elapsed = time.perf_counter() - started
            print(canonical({"stage": "tokenize", "rows_checked": seen, "accepted": {s: len(v) for s, v in data.items()},
                             "elapsed_s": elapsed, "rows_per_s": seen / elapsed,
                             "tokenization_batch_size": TOKENIZATION_BATCH_SIZE, "tokenizer_threads": TOKENIZER_THREADS}), flush=True)
            next_report = (seen // 2000 + 1) * 2000
    pending = []
    for row in prepared_rows(args, info, stats):
        pending.append(row)
        if len(pending) == TOKENIZATION_BATCH_SIZE:
            consume(pending)
            pending = []
    if pending:
        consume(pending)
    if not all(data.values()) or not any(item["branch"] == "writing" for item in data["train"]):
        raise ValueError("Need token-fitting state and reviewed writing training data plus validation")
    files = {}
    for split, values in data.items():
        cache = path / (split + ".pickle")
        temporary = cache.with_suffix(".tmp")
        with temporary.open("wb") as stream:
            pickle.dump(values, stream, protocol=5)
            stream.flush()
            os.fsync(stream.fileno())
        temporary.replace(cache)
        files[split] = {"path": cache.name, "sha256": file_hash(cache), "bytes": cache.stat().st_size, "rows": len(values)}
    metadata = {"version": 1, "identity_sha256": key, "identity": identity, "files": files,
                "counts": dict(stats), "preparation_s": time.perf_counter() - started,
                "limits": "Whole chat plus response/EOS bounded; no truncation or cross-example packing. Exact frozen-input exclusion only."}
    save_json(metadata_path, metadata)
    return data, metadata


def family_factors(features, writing_share):
    counts = Counter()
    for item in features:
        counts[item["branch"]] += item["response_tokens"]
    if set(counts) != {"state", "writing"} or not 0 < writing_share < 1:
        raise ValueError("Both branches and a writing share between zero and one are required")
    total = sum(counts.values())
    return {branch: share * total / counts[branch] for branch, share in (("state", 1 - writing_share), ("writing", writing_share))}, counts


def batches_for(features, batch_size, seed, bucket_size):
    generator = random.Random(seed)
    order = list(range(len(features)))
    generator.shuffle(order)
    batches = []
    for offset in range(0, len(order), bucket_size):
        bucket = sorted(order[offset:offset + bucket_size], key=lambda i: len(features[i]["input_ids"]))
        batches.extend(bucket[start:start + batch_size] for start in range(0, len(bucket), batch_size))
    generator.shuffle(batches)
    return batches


def stratified(rows, limit, salt):
    groups = defaultdict(list)
    for row in sorted(rows, key=lambda r: digest((salt + r["id"]).encode())):
        groups[row["family"]].append(row)
    selected, used = [], set()
    while groups and len(selected) < limit:
        for family in sorted(list(groups)):
            choices = groups[family]
            index = next((i for i, row in enumerate(choices) if row["group"] not in used), 0)
            row = choices.pop(index)
            selected.append(row)
            used.add(row["group"])
            if not choices:
                del groups[family]
            if len(selected) == limit:
                break
    return selected


def validation_rows(rows, limit, salt):
    writing_limit = limit // 3
    state = stratified([r for r in rows if r["branch"] == "state"], limit - writing_limit, salt)
    writing = stratified([r for r in rows if r["branch"] == "writing"], writing_limit, salt)
    return state + writing


def state_score(gold, predicted, schema):
    valid = not state_schema_errors(predicted, schema)
    if not valid:
        return {"valid_json_schema": False, "literal_annotation_match": False, "alias_aware_joint_state": False,
                "intent_match": False, "slots_alias_match": False, "requested_slots_match": False}
    slots = predicted["slot_values"].keys() == gold["slot_values"].keys() and all(
        all(value in gold["slot_values"][slot] for value in values) for slot, values in predicted["slot_values"].items())
    intent = predicted["active_intent"] == gold["active_intent"] and predicted["service"] == gold["service"]
    requested = set(predicted["requested_slots"]) == set(gold["requested_slots"])
    return {"valid_json_schema": True, "literal_annotation_match": predicted == gold,
            "alias_aware_joint_state": bool(intent and slots and requested), "intent_match": intent,
            "slots_alias_match": bool(slots), "requested_slots_match": requested}


def writing_score(row, predicted):
    valid = (isinstance(predicted, dict) and set(predicted) == {"text"}
             and isinstance(predicted["text"], str) and bool(predicted["text"].strip()))
    if not valid:
        return {"valid_json_schema": False, "literal_reference_match": False, "lexical_canaries_pass": False, "copies_source": False}
    source = row["messages"][1]["content"].split("\n\nSource text:\n", 1)[1]
    text = predicted["text"]
    return {"valid_json_schema": True, "literal_reference_match": predicted == parse_json(row["response"]),
            "lexical_canaries_pass": (set(NUMBER.findall(source)) == set(NUMBER.findall(text)) and identifiers(source) == identifiers(text)
                                      and bool(NEGATIVE.search(source)) == bool(NEGATIVE.search(text))),
            "copies_source": re.sub(r"\s+", " ", source).strip() == re.sub(r"\s+", " ", text).strip()}


def generate_validation(torch, model, tokenizer, rows, args):
    outputs, summary = [], defaultdict(Counter)
    model.eval()
    with torch.inference_mode():
        for row in rows:
            budget = min(args.max_new_tokens, args.max_sequence - len(row["prompt_ids"]))
            raw, truncated = "", True
            if budget > 0:
                ids = torch.tensor([list(row["prompt_ids"])], dtype=torch.long, device="cuda")
                with torch.autocast("cuda", dtype=torch.bfloat16):
                    output = model.generate(input_ids=ids, attention_mask=torch.ones_like(ids), do_sample=False,
                                            max_new_tokens=budget, pad_token_id=tokenizer.pad_token_id,
                                            eos_token_id=tokenizer.eos_token_id, use_cache=True)
                generated = output[0, ids.shape[1]:].tolist()
                truncated = tokenizer.eos_token_id not in generated
                raw = tokenizer.decode(generated, skip_special_tokens=True)
                del output, ids
            try:
                predicted = parse_json(raw)
                score = (state_score(parse_json(row["response"]), predicted, parse_json(row["messages"][1]["content"])["selected_service_schema"])
                         if row["branch"] == "state" else writing_score(row, predicted))
            except (ValueError, TypeError, KeyError):
                score = {"valid_json_schema": False}
            score["truncated"] = truncated
            score["completed_response_checks_pass"] = not truncated and (
                score.get("alias_aware_joint_state", False) if row["branch"] == "state"
                else score.get("lexical_canaries_pass", False) and not score.get("copies_source", False))
            for key in ("literal_annotation_match", "alias_aware_joint_state", "literal_reference_match"):
                if key in score:
                    score[key] = score[key] and not truncated
            summary[row["family"]]["rows"] += 1
            for key, value in score.items():
                summary[row["family"]][key] += int(value)
            outputs.append({"id": row["id"], "family": row["family"], "text": raw, "reference": row["response"], **score})
    return {"rows": len(rows), "families": {k: dict(v) for k, v in summary.items()}, "outputs": outputs,
            "scope": "Held-out source TRAIN dialogues; not frozen evaluation. Alias-aware state score differs from literal full-array match. Writing lexical checks are not semantic verification."}


def atomic_torch_save(torch, path, value):
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("wb") as stream:
        torch.save(value, stream)
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(path)


def train(args):
    if args.out.exists() and any(args.out.iterdir()) and not args.resume:
        raise ValueError("Use a new output directory or --resume")
    if args.resume and not (args.out / "training.json").exists():
        raise ValueError("Resume needs an existing training manifest")
    args.out.mkdir(parents=True, exist_ok=True)
    stop = {"requested": False, "signal": None}
    previous_handlers = {}
    for kind in (signal.SIGTERM, signal.SIGINT):
        previous_handlers[kind] = signal.getsignal(kind)
        signal.signal(kind, lambda number, frame: stop.update(requested=True, signal=number))
    torch = model = optimizer = scheduler = None
    started = time.perf_counter()
    report_path = args.out / "training.json"
    report = {"version": 1, "scope": SCOPE, "status": "preparing", "created_utc": datetime.now(timezone.utc).isoformat()}
    if args.resume:
        report, _ = document(report_path)
    try:
        info = sources(args)
        # job.sh normally disables tokenizer parallelism; batching uses a bounded CPU thread pool.
        os.environ["TOKENIZERS_PARALLELISM"] = "true"
        os.environ["RAYON_NUM_THREADS"] = str(TOKENIZER_THREADS)
        torch, transformers, environment = runtime()
        environment.update({"tokenizers_parallelism": os.environ["TOKENIZERS_PARALLELISM"],
                            "tokenizers_rayon_threads": TOKENIZER_THREADS})
        random.seed(args.seed)
        torch.manual_seed(args.seed)
        torch.cuda.manual_seed_all(args.seed)
        torch.cuda.reset_peak_memory_stats()
        tokenizer = transformers.AutoTokenizer.from_pretrained(STUDENT, revision=STUDENT_REVISION, cache_dir=ROOT / ".hf", use_fast=True)
        if not tokenizer.is_fast:
            raise ValueError("Fast tokenizer required for boundary masking")
        tokenizer.padding_side = "right"
        if tokenizer.pad_token_id is None:
            tokenizer.pad_token = tokenizer.eos_token
        immutable = {"data": info["identity"], "environment": environment, "model": STUDENT, "revision": STUDENT_REVISION,
            "parameters": {k: v for k, v in vars(args).items() if k not in {"out", "resume", "max_steps", "self_check"} and not isinstance(v, (Path, list))},
            "template_sha256": digest(tokenizer.chat_template.encode())}
        config_hash = digest(canonical(immutable).encode())
        if args.resume and report.get("config_sha256") != config_hash:
            raise ValueError("Resume requires identical data/code/environment/template and training configuration")
        report.update({"status": "tokenizing", "config_sha256": config_hash, "configuration": immutable,
                       "precision": "FP32 parameters/AdamW states; BF16 autocast; SDPA; response-only labels incl. assistant EOS",
                       "writing_review_scope": info["review"]["reviewer"], "writing_policy": "reviewed-only",
                       "checkpoint_selection": "Fixed capped held-out response-only branch means with configured writing/state shares; never frozen regression"})
        save_json(report_path, report)
        features, cache = feature_cache(args, info, tokenizer)
        factors, branch_tokens = family_factors(features["train"], args.writing_share)
        batches = batches_for(features["train"], args.batch_size, args.seed, args.bucket_size)
        sampler_hash = digest(canonical(batches).encode())
        total_steps = math.ceil(len(batches) / args.accumulate)
        mean_tokens = sum(branch_tokens.values()) / len(features["train"])
        loss_rows = validation_rows(features["validation"], args.validation_loss_limit, "loss:" + str(args.seed))
        generation_rows = validation_rows(features["validation"], args.validation_generation_limit, "generation:" + str(args.seed))
        if not all(any(r["branch"] == branch for r in loss_rows) for branch in ("state", "writing")):
            raise ValueError("Validation loss cap must include both branches")
        report.update({"status": "loading_model", "feature_cache": cache, "total_steps": total_steps,
                       "family_loss_factors": factors, "train_branch_response_tokens": dict(branch_tokens),
                       "loss_formula": "factor(branch)=share(branch)*all_train_tokens/branch_train_tokens; group weighted token NLL / (global_mean_tokens_per_train_row * group_rows). No per-group weighted-token renormalization; not a claim about realized gradient contribution.",
                       "validation_loss_ids": [r["id"] for r in loss_rows], "validation_generation_ids": [r["id"] for r in generation_rows],
                       "sampler_sha256": sampler_hash, "chat_template": tokenizer.chat_template})
        save_json(report_path, report)
        model = transformers.AutoModelForCausalLM.from_pretrained(STUDENT, revision=STUDENT_REVISION, cache_dir=ROOT / ".hf",
                    torch_dtype=torch.float32, attn_implementation="sdpa").to("cuda")
        model.config.use_cache = False
        optimizer = torch.optim.AdamW(model.parameters(), lr=args.learning_rate, weight_decay=0.01)
        warmup = max(1, math.ceil(total_steps * 0.05))
        def schedule(step):
            return (step + 1) / warmup if step < warmup else max(0.0, (total_steps - step) / max(1, total_steps - warmup))
        scheduler = torch.optim.lr_scheduler.LambdaLR(optimizer, schedule)
        progress = {"step": 0, "next_batch": 0, "rows": 0, "tokens": 0, "active_train_s": 0.0, "loop_wall_s": 0.0,
                    "best_loss": None, "best_step": None, "nll_by_family": {}, "tokens_by_family": {}}
        latest = args.out / "latest.pt"
        if args.resume and latest.exists():
            actual_latest_hash = file_hash(latest)
            # The atomic file is authoritative: a kill can occur before its JSON sidecar commits.
            saved = torch.load(latest, map_location="cpu", weights_only=True)
            if saved["config_sha256"] != config_hash or saved["sampler_sha256"] != sampler_hash or saved["cache_sha256"] != cache["identity_sha256"]:
                raise ValueError("Recoverable checkpoint data/sampler/config mismatch")
            position = saved["progress"]
            if (position["step"] < 0 or position["step"] > total_steps
                    or position["next_batch"] != min(position["step"] * args.accumulate, len(batches))):
                raise ValueError("Recoverable checkpoint sampler cursor is invalid")
            model.load_state_dict(saved["model"])
            optimizer.load_state_dict(saved["optimizer"])
            scheduler.load_state_dict(saved["scheduler"])
            progress = saved["progress"]
            random.setstate(saved["random_rng"])
            torch.set_rng_state(saved["torch_rng"])
            torch.cuda.set_rng_state_all(saved["cuda_rng"])
            report["recovered_ahead_of_sidecar"] = report.get("latest_sha256") != actual_latest_hash
            report["latest_sha256"] = actual_latest_hash
            report["progress"], report["optimizer_steps"] = progress, progress["step"]
            del saved
        elif args.resume and report.get("optimizer_steps", 0):
            raise ValueError("Manifest claims optimizer steps but no recoverable checkpoint exists")
        elif args.resume:
            # Preparation or initial validation may have been interrupted before checkpoint zero.
            report.pop("initial_validation", None)
        if (args.out / "best.previous").exists() and not (args.out / "best").exists():
            (args.out / "best.previous").replace(args.out / "best")
        if args.resume and (args.out / "best").exists():
            best_info, _ = document(args.out / "best/friday_prepared_sft.json")
            if best_info["configuration"] != immutable or best_info["weight_sha256"] != weight_hashes(args.out / "best"):
                raise ValueError("Best checkpoint weights/configuration differ")
            # A completed best save may precede the next optimizer commit; retain its verified candidate.
            best_loss = best_info["validation"]["loss"]
            if progress["best_loss"] is not None and progress["best_loss"] < best_loss:
                raise ValueError("Recoverable checkpoint names a better model than the retained best weights")
            progress["best_loss"], progress["best_step"] = best_loss, best_info["step"]
            report["weight_sha256"] = best_info["weight_sha256"]
        base_wall, loop_started = progress["loop_wall_s"], None

        def tensors(items):
            plain = [{"input_ids": list(r["input_ids"]), "labels": list(r["labels"])} for r in items]
            return {key: torch.tensor(value, dtype=torch.long, device="cuda") for key, value in pad_batch(plain, tokenizer.pad_token_id).items()}

        def losses(items):
            batch = tensors(items)
            with torch.autocast("cuda", dtype=torch.bfloat16):
                output = model(input_ids=batch["input_ids"], attention_mask=batch["attention_mask"], use_cache=False)
                # Shift labels, not logits: avoid another full contiguous logits copy at 2k length.
                shifted = torch.nn.functional.pad(batch["labels"], (0, 1), value=-100)[:, 1:].contiguous()
                values = torch.nn.functional.cross_entropy(output.logits.float().reshape(-1, output.logits.shape[-1]),
                         shifted.reshape(-1), ignore_index=-100, reduction="none").view(shifted.shape)
                sums = values.sum(dim=1)
            return sums

        def validation():
            model.eval()
            nll, tokens = Counter(), Counter()
            with torch.inference_mode():
                for offset in range(0, len(loss_rows), args.batch_size):
                    rows = loss_rows[offset:offset + args.batch_size]
                    sums = losses(rows).tolist()
                    for row, value in zip(rows, sums):
                        nll[row["family"]] += value
                        tokens[row["family"]] += row["response_tokens"]
            means = {}
            for branch in ("state", "writing"):
                keys = [key for key in tokens if key.startswith(branch + "/")]
                means[branch] = sum(nll[key] for key in keys) / sum(tokens[key] for key in keys)
            score = (1 - args.writing_share) * means["state"] + args.writing_share * means["writing"]
            if not math.isfinite(score):
                raise ValueError("Nonfinite validation loss")
            return {"loss": score, "branch_loss": means,
                    "family_loss": {key: {"nll": nll[key], "tokens": tokens[key], "loss": nll[key] / tokens[key]} for key in tokens}}

        def save_best(detail):
            temporary, best, old = args.out / "best.tmp", args.out / "best", args.out / "best.previous"
            if temporary.exists():
                shutil.rmtree(temporary)
            temporary.mkdir()
            model.save_pretrained(temporary, safe_serialization=True)
            tokenizer.save_pretrained(temporary)
            hashes = weight_hashes(temporary)
            save_json(temporary / "friday_prepared_sft.json", {"scope": SCOPE, "configuration": immutable,
                      "validation": detail, "step": progress["step"], "weight_sha256": hashes})
            if old.exists():
                shutil.rmtree(old)
            if best.exists():
                best.replace(old)
            temporary.replace(best)
            if old.exists():
                shutil.rmtree(old)
            report["weight_sha256"] = hashes

        def checkpoint():
            if loop_started is not None:
                progress["loop_wall_s"] = base_wall + time.perf_counter() - loop_started
            atomic_torch_save(torch, latest, {"version": 1, "config_sha256": config_hash, "sampler_sha256": sampler_hash,
                "cache_sha256": cache["identity_sha256"], "model": model.state_dict(), "optimizer": optimizer.state_dict(),
                "scheduler": scheduler.state_dict(), "progress": progress, "random_rng": random.getstate(),
                "torch_rng": torch.get_rng_state(), "cuda_rng": torch.cuda.get_rng_state_all()})
            report["latest_sha256"] = file_hash(latest)
            report["progress"] = progress
            report["optimizer_steps"] = progress["step"]
            save_json(report_path, report)

        if "initial_validation" not in report:
            initial = validation()
            report["initial_validation"] = initial
            progress["best_loss"], progress["best_step"] = initial["loss"], 0
            save_best(initial)
            checkpoint()
        report["status"] = "running"
        save_json(report_path, report)
        loop_started = time.perf_counter()
        evaluations = args.out / "validation"
        evaluations.mkdir(exist_ok=True)
        cursor = progress["next_batch"]
        while cursor < len(batches) and not stop["requested"] and (not args.max_steps or progress["step"] < args.max_steps):
            model.train()
            optimizer.zero_grad(set_to_none=True)
            group = batches[cursor:cursor + args.accumulate]
            group_rows = sum(map(len, group))
            denominator = mean_tokens * group_rows
            began = time.perf_counter()
            for indices in group:
                rows = [features["train"][i] for i in indices]
                sums = losses(rows)
                weights = torch.tensor([factors[r["branch"]] for r in rows], device="cuda", dtype=torch.float32)
                loss = (sums * weights).sum() / denominator
                if not bool(torch.isfinite(loss).item()):
                    raise ValueError("Nonfinite training loss")
                loss.backward()
                for row, value in zip(rows, sums.detach().tolist()):
                    family = row["family"]
                    progress["nll_by_family"][family] = progress["nll_by_family"].get(family, 0.0) + value
                    progress["tokens_by_family"][family] = progress["tokens_by_family"].get(family, 0) + row["response_tokens"]
                    progress["tokens"] += row["response_tokens"]
                del loss, sums, weights
            norm = torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0, error_if_nonfinite=True)
            optimizer.step()
            scheduler.step()
            optimizer.zero_grad(set_to_none=True)
            torch.cuda.synchronize()
            progress["active_train_s"] += time.perf_counter() - began
            progress["step"] += 1
            progress["rows"] += group_rows
            cursor += len(group)
            progress["next_batch"] = cursor
            progress["loop_wall_s"] = base_wall + time.perf_counter() - loop_started
            evaluated = (progress["step"] % args.validate_every == 0 or cursor == len(batches)
                         or bool(args.max_steps and progress["step"] >= args.max_steps))
            if evaluated:
                detail = {"step": progress["step"], **validation()}
                if generation_rows:
                    detail["generation"] = generate_validation(torch, model, tokenizer, generation_rows, args)
                save_json(evaluations / ("step-%08d.json" % progress["step"]), detail)
                if detail["loss"] < progress["best_loss"]:
                    progress["best_loss"], progress["best_step"] = detail["loss"], progress["step"]
                    save_best(detail)
                report["last_validation"] = {key: value for key, value in detail.items() if key != "generation"}
            if evaluated or progress["step"] % args.checkpoint_every == 0 or cursor == len(batches) or stop["requested"]:
                checkpoint()
            if progress["step"] == 1 or progress["step"] % args.log_every == 0:
                elapsed = base_wall + time.perf_counter() - loop_started
                remaining = sum(branch_tokens.values()) - progress["tokens"]
                throughput = progress["tokens"] / elapsed if elapsed else 0
                event = {"stage": "train", "step": progress["step"], "total_steps": total_steps,
                         "rows": progress["rows"], "total_rows": len(features["train"]), "response_tokens": progress["tokens"],
                         "response_tokens_per_s_with_overhead": throughput,
                         "response_tokens_per_active_train_s": progress["tokens"] / progress["active_train_s"],
                         "eta_s": remaining / throughput if throughput else None,
                         "gradient_norm": float(norm.item()), "learning_rate": scheduler.get_last_lr()[0],
                         "loop_wall_s": elapsed, "invocation_wall_s": time.perf_counter() - started}
                report["last_progress"] = event
                report["progress"] = progress
                save_json(report_path, report)
                print(canonical(event), flush=True)
        progress["loop_wall_s"] = base_wall + time.perf_counter() - loop_started
        report["status"] = "complete" if cursor == len(batches) else "stopped"
        report["stop_signal"] = stop["signal"]
        report["selected_checkpoint"] = str((args.out / "best").resolve())
        report["best_step"] = progress["best_step"]
        report["best_validation_loss"] = progress["best_loss"]
        checkpoint()
        best_metadata, _ = document(args.out / "best/friday_prepared_sft.json")
        best_metadata.update({"trained_optimizer_steps": progress["step"], "status": report["status"]})
        save_json(args.out / "best/friday_prepared_sft.json", best_metadata)
    except BaseException as error:
        report.update({"status": "failed", "error": type(error).__name__ + ": " + str(error)})
        raise
    finally:
        report["last_invocation_wall_s"] = time.perf_counter() - started
        if torch is not None:
            report["gpu_peak_allocated_bytes"] = torch.cuda.max_memory_allocated()
        save_json(report_path, report)
        for kind, handler in previous_handlers.items():
            signal.signal(kind, handler)
        del optimizer, scheduler, model
        gc.collect()
        if torch is not None:
            torch.cuda.empty_cache()
    print(canonical({"stage": "finished", "status": report["status"], "selected_checkpoint": report["selected_checkpoint"],
                     "steps": report["optimizer_steps"]}), flush=True)


def self_check():
    class Tokenizer:
        eos_token_id = 1
        def apply_chat_template(self, messages, tokenize=False, add_generation_prompt=False):
            prefix = "SYSTEM:user:assistant:"
            return prefix if add_generation_prompt else prefix + messages[-1]["content"] + "~"
        def __call__(self, text, **kwargs):
            if isinstance(text, list):
                rows = [self(item, **kwargs) for item in text]
                return {key: [row[key] for row in rows] for key in ("input_ids", "offset_mapping")}
            return {"input_ids": [1 if char == "~" else ord(char) for char in text],
                    "offset_mapping": [(i, i + 1) for i in range(len(text))]}
    row = {"id": "a", "kind": "rewrite", "branch": "writing", "family": "writing/rewrite", "group": "g",
           "split": "validation", "messages": [{"role": "system", "content": "x"}, {"role": "user", "content": "y"}], "response": '{"text":"ok"}'}
    feature = tokenize_example(Tokenizer(), row, 1000)
    boundary = len("SYSTEM:user:assistant:")
    assert all(v == -100 for v in feature["labels"][:boundary]) and feature["labels"][-1] == 1
    assert tokenize_example(Tokenizer(), row, 2) is None
    mixed = [row, {**row, "id": "train", "split": "train", "response": '{"text":"caf\u00e9"}'},
             {**row, "id": "overlength", "response": "x" * 200}]
    assert tokenize_examples(Tokenizer(), mixed, 100) == [tokenize_example(Tokenizer(), item, 100) for item in mixed]
    assert tokenize_examples(Tokenizer(), [], 100) == []
    padded = pad_batch([{"input_ids": [1, 2], "labels": [-100, 2]}, {"input_ids": [3], "labels": [3]}], 0)
    assert padded["labels"][1] == [3, -100] and padded["attention_mask"][1] == [1, 0]
    factors, counts = family_factors([{"branch": "state", "response_tokens": 900}, {"branch": "writing", "response_tokens": 100}], 0.3)
    assert math.isclose(factors["writing"] * counts["writing"] / sum(counts.values()), 0.3)
    items = [{"input_ids": [0] * n} for n in (5, 9, 1, 8, 3)]
    batches = batches_for(items, 2, 42, 4)
    assert sorted(i for batch in batches for i in batch) == list(range(5)) and batches == batches_for(items, 2, 42, 4)
    schema = {"service_name": "rail", "intents": [{"name": "find"}], "slots": [{"name": "time"}]}
    gold = {"service": "rail", "active_intent": "find", "slot_values": {"time": ["09:00", "9:00"]}, "requested_slots": []}
    score = state_score(gold, {**gold, "slot_values": {"time": ["9:00"]}}, schema)
    assert score["alias_aware_joint_state"] and not score["literal_annotation_match"]
    assert not state_score(gold, {**gold, "slot_values": {"time": ["10:00"]}}, schema)["alias_aware_joint_state"]
    assert state_schema_errors({**gold, "slot_values": {"time": []}}, schema)
    review_row = {"id": "r", "provenance": {"input_sha256": "a", "response_sha256": "b"}}
    assert check_review(review_row, {"r": {"input_sha256": "a", "response_sha256": "b", "approved": False, "reason": "wrong actor"}}) is False
    try:
        check_review(review_row, {"r": {"input_sha256": "changed", "response_sha256": "b", "approved": True, "reason": "ok"}})
    except ValueError:
        pass
    else:
        raise AssertionError("Stale review accepted")
    with tempfile.TemporaryDirectory(prefix="friday-prepared-check-") as directory:
        path = Path(directory) / "file"
        path.write_bytes(b"source")
        checked_file(path, {"sha256": digest(b"source"), "bytes": 6})
        path.write_bytes(b"tamper")
        try:
            checked_file(path, {"sha256": digest(b"source")})
        except ValueError:
            pass
        else:
            raise AssertionError("Tampered source accepted")
    print("Prepared trainer self-check passed: single/batch equivalence, response/EOS/pad masks, whole-sequence rejection, global family factors, deterministic buckets, alias-aware states, review hashes and source tampering")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-check", action="store_true")
    parser.add_argument("--state-data", type=Path, default=ROOT / "out/dialogue-v2-full")
    parser.add_argument("--state-sources", type=Path, default=ROOT / "out/external-full")
    parser.add_argument("--writing-data", type=Path, default=ROOT / "out/writing-external/prepared")
    parser.add_argument("--writing-raw", type=Path, default=ROOT / "out/writing-external")
    parser.add_argument("--writing-review", type=Path)
    parser.add_argument("--frozen", nargs="+", type=Path, default=[ROOT.parents[1] / "tests/slm-regression.json",
                                                               ROOT.parents[1] / "tests/slm-domain-regression.json"])
    parser.add_argument("--out", type=Path)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--max-sequence", type=int, default=2048)
    parser.add_argument("--batch-size", type=int, default=4)
    parser.add_argument("--accumulate", type=int, default=4)
    parser.add_argument("--bucket-size", type=int, default=512)
    parser.add_argument("--learning-rate", type=float, default=2e-5)
    parser.add_argument("--writing-share", type=float, default=0.3)
    parser.add_argument("--seed", type=int, default=20261021)
    parser.add_argument("--validation-loss-limit", type=int, default=192)
    parser.add_argument("--validation-generation-limit", type=int, default=96)
    parser.add_argument("--max-new-tokens", type=int, default=256)
    parser.add_argument("--validate-every", type=int, default=500)
    parser.add_argument("--checkpoint-every", type=int, default=200)
    parser.add_argument("--log-every", type=int, default=10)
    parser.add_argument("--max-steps", type=int, default=0, help="Invocation may stop at this total optimizer-step count; 0 finishes the one epoch")
    args = parser.parse_args()
    if args.self_check:
        self_check()
        return
    if args.out is None or args.writing_review is None:
        parser.error("--out and --writing-review are required")
    if (min(args.max_sequence, args.batch_size, args.accumulate, args.bucket_size, args.validate_every, args.checkpoint_every, args.log_every) < 1
            or args.validation_loss_limit < 3 or args.validation_generation_limit < 0 or args.max_steps < 0
            or args.learning_rate <= 0 or not 0 < args.writing_share < 1):
        parser.error("Invalid training bounds")
    train(args)


if __name__ == "__main__":
    main()
