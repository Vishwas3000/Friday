#!/usr/bin/env python3
"""Frozen, language-only HF BF16 regression; never executes Android capabilities."""
import argparse
import gc
import hashlib
import json
import platform
import re
import time
from collections import defaultdict
from datetime import date, datetime, timezone
from pathlib import Path

from probe import MODELS, reject_constant, unique_object
from prompts import GOAL_CAPABILITIES, GOAL_FIELDS, PROMPT_VERSION, WRITING_KINDS, build_messages, prompt_digest_input

ROOT = Path(__file__).resolve().parents[2]
TEACHER, TEACHER_REVISION = MODELS["qwen3"]
SCOPE = ("Frozen synthetic language regression using BF16 HF generation and shared research prompts; "
         "no constrained grammar, Android runtime parity, app navigation, calendar writes, bill reading or bookings")
NUMBER = re.compile(r"\d+(?:[.,:/-]\d+)*")
CODE = re.compile(r"(?<![\w-])[A-Za-z0-9]+(?:[-_][A-Za-z0-9]+)*(?![\w-])")
NEGATIVE = re.compile(r"\b(?:no|not|never|cannot|can't|won't|don't|unable|unavailable|declin\w*|refus\w*)\b", re.I)


def parse_json(raw):
    return json.loads(raw, object_pairs_hook=unique_object, parse_constant=reject_constant)


def sha256(value):
    return hashlib.sha256(value).hexdigest()


def normalize_case(row):
    if "saved_goal" in row:
        return {"id": row["id"], "family": "task_update", "kind": "task_update",
                "instruction": row["saved_goal"], "text": json.dumps(row["input"], ensure_ascii=False),
                "split": row["split"], "expected": row["expected"],
                "checks": {"manual_rubric": row.get("semantic_checks", [])},
                "metadata": {key: row[key] for key in ("production_path", "current_validator", "expected_partial_context") if key in row}}
    return row.copy()


def comparable(value, kind):
    if kind == "task_update" and isinstance(value, dict) and isinstance(value.get("updates"), list):
        return {**value, "updates": sorted(value["updates"], key=lambda row: json.dumps(row, sort_keys=True))}
    return value


def load_cases(paths):
    cases, by_id, sources = [], {}, []
    for path in paths:
        raw = path.read_bytes()
        document = parse_json(raw)
        if document.get("version") != 1 or not isinstance(document.get("cases"), list):
            raise ValueError("Expected a version 1 case manifest: " + str(path))
        sources.append({"path": str(path), "sha256": sha256(raw), "case_count": len(document["cases"])})
        for original in document["cases"]:
            row = normalize_case(original)
            if not isinstance(row.get("id"), str) or not row["id"] or row.get("split") != "evaluation":
                raise ValueError("Regression requires named evaluation-only rows")
            if not isinstance(row.get("family"), str) or not row["family"]:
                raise ValueError("Every case needs a reporting family")
            build_messages(row)
            alternatives = row.get("expected_any", [row["expected"]] if "expected" in row else [])
            if row["kind"] not in WRITING_KINDS and not alternatives:
                raise ValueError("Structured case has no independent expected output: " + row["id"])
            for pattern in row.get("checks", {}).get("forbidden_regex", []):
                re.compile(pattern)
            signature = (prompt_digest_input(row), [comparable(item, row["kind"]) for item in alternatives])
            if row["id"] in by_id:
                previous = by_id[row["id"]]
                # JSON object spacing is not a changed input to the task envelope.
                same_text = row["text"] == previous["text"]
                if not same_text and row["kind"] in {"task_update", "intent"}:
                    same_text = parse_json(row["text"]) == parse_json(previous["text"])
                old_alternatives = previous.get("expected_any", [previous["expected"]] if "expected" in previous else [])
                if not (row["kind"] == previous["kind"] and row["instruction"] == previous["instruction"] and same_text
                        and signature[1] == [comparable(item, row["kind"]) for item in old_alternatives]):
                    raise ValueError("Conflicting duplicate case: " + row["id"])
                continue
            by_id[row["id"]] = row
            cases.append(row)
    if not cases:
        raise ValueError("No frozen regression cases")
    return cases, sources


def unique_span(source, span):
    start = source.find(span)
    return bool(span.strip()) and start >= 0 and source.find(span, start + 1) < 0


def identifiers(text):
    return {m.group() for m in CODE.finditer(text) if re.search("[A-Za-z]", m.group()) and re.search("[0-9]", m.group())
            and not re.fullmatch(r"\d+(?:am|pm|st|nd|rd|th)", m.group(), re.I)}


def evaluate(case, raw):
    """Strict structural/exact checks, with prose semantics explicitly left for review."""
    result = {"valid_json": False, "schema_valid": False, "provenance_valid": None,
              "values_valid": None, "expected_match": None, "mechanical_checks_pass": None,
              "mechanical_pass": False, "check_errors": []}
    errors = result["check_errors"]
    checks = case.get("checks", {})
    metadata = case.get("metadata", {})
    rubric = checks.get("manual_rubric", case.get("manual_rubric", metadata.get("semantic_review", metadata.get("semantic_checks", []))))
    result["semantic_review"] = {"status": "pending", "criteria": rubric,
                                 "note": "Literal/type/exact-output checks do not establish general semantic or app-task success."}
    result["native_guard_expectation"] = {"measured": False, **{key: metadata[key]
        for key in ("native_guard", "current_validator", "production_path") if key in metadata}}
    try:
        value = parse_json(raw)
        if not isinstance(value, dict):
            raise ValueError("Output must be one complete JSON object")
        result["valid_json"] = True
        result["parsed"] = value
    except (ValueError, TypeError) as error:
        errors.append("JSON: " + str(error))
        return result
    kind = case["kind"]
    schema, provenance, typed = [], [], []
    if kind == "task_update":
        envelope = parse_json(case["text"])
        fields = set(envelope["fields"])
        if (set(value) != {"operation", "updates"} or not isinstance(value.get("operation"), str)
                or value["operation"] not in {"update", "new", "clarify"} or not isinstance(value.get("updates"), list)):
            schema.append("task_update needs operation enum and updates array only")
        else:
            rows = value["updates"]
            if len(rows) > 8 or ((value["operation"] == "update") != bool(rows)):
                schema.append("update needs 1..8 rows; new/clarify need no rows")
            seen = set()
            for row in rows:
                if not isinstance(row, dict) or set(row) != {"field", "text", "value"} or not all(isinstance(item, str) for item in row.values()):
                    schema.append("each update needs exactly three string fields")
                    continue
                field, span, normalized = row["field"], row["text"], row["value"]
                if field not in fields or field in {"action", "all_day", "overnight"} or field in seen:
                    schema.append("unknown, authoritative or repeated field: " + field)
                seen.add(field)
                if not unique_span(envelope["current"], span):
                    provenance.append("span is absent, empty or repeated: " + repr(span))
                try:
                    if not normalized.strip():
                        raise ValueError("empty value")
                    if field == "date":
                        if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", normalized) or date.fromisoformat(normalized) < date.fromisoformat(envelope["date"]):
                            raise ValueError("invalid/past date")
                    elif field in {"start", "end"}:
                        if not re.fullmatch(r"(?:[01][0-9]|2[0-3]):[0-5][0-9]", normalized):
                            raise ValueError("time must be HH:mm")
                    elif field == "duration":
                        if not re.fullmatch(r"[0-9]+", normalized) or int(normalized) <= 0:
                            raise ValueError("duration must be positive whole minutes as a string")
                    elif field in {"query", "title", "zone"}:
                        if normalized != span.strip():
                            raise ValueError("literal field changed its copied value")
                except ValueError as error:
                    typed.append(field + ": " + str(error))
    elif kind == "intent":
        keys = {"mode", "action", "target", "scope", "source_id"}
        if (set(value) != keys or not all(isinstance(item, str) for item in value.values())
                or not isinstance(value.get("mode"), str) or value["mode"] not in {"search", "navigate", "act"}):
            schema.append("intent needs exact string fields and mode enum")
        else:
            envelope = parse_json(case.get("text") or "[]")
            sources = envelope if isinstance(envelope, list) else envelope.get("sources", [])
            by_id = {item["id"]: item["text"] for item in sources}
            by_id["request"] = case["instruction"]
            cited = by_id.get(value["source_id"])
            if not value["action"] or not re.search(r"(?<!\w)" + re.escape(value["action"]) + r"(?!\w)", case["instruction"], re.I):
                provenance.append("action is not in the current request")
            if cited is None:
                provenance.append("unknown source_id")
            elif value["target"] and not unique_span(cited, value["target"]):
                provenance.append("target is not an exact unique cited span")
            if value["scope"] and not unique_span(case["instruction"], value["scope"]):
                provenance.append("scope is not an exact unique current-request span")
            destination = envelope.get("destination_app", {}) if isinstance(envelope, dict) else {}
            target = value["target"]
            if value["source_id"] == "request" and target and target.lower() in {str(destination.get("label", "")).lower(), str(destination.get("package", "")).lower()}:
                if not any(quote + target + end in case["instruction"] for quote, end in [('"', '"'), ('“', '”')]):
                    provenance.append("destination app was used as an unquoted content target")
            if value["mode"] != "navigate" and not target and value["scope"]:
                typed.append("item/search mode needs an exact target")
            result["handoff_ready"] = bool(target or value["scope"]) and not provenance and not typed
            if not target and not value["scope"]:
                result["handoff_note"] = "Unresolved extraction; native clarification required, not an executable intent."
    elif kind == "goal":
        if (set(value) != {"capabilities", "fields", "missing"} or not isinstance(value.get("capabilities"), list)
                or not isinstance(value.get("fields"), dict) or not isinstance(value.get("missing"), list)):
            schema.append("goal needs capabilities array, fields object and missing array only")
        else:
            caps, supplied, missing = value["capabilities"], value["fields"], value["missing"]
            if not all(isinstance(cap, str) and cap in GOAL_CAPABILITIES for cap in caps) or len(set(map(str, caps))) != len(caps):
                schema.append("unknown or repeated research capability")
            if not all(key in GOAL_FIELDS and isinstance(item, str) and item.strip() for key, item in supplied.items()):
                schema.append("goal fields must be allowed nonempty strings")
            if not all(isinstance(key, str) and key in GOAL_FIELDS for key in missing) or len(set(map(str, missing))) != len(missing):
                schema.append("unknown or repeated missing field")
            for field, span in supplied.items():
                if isinstance(span, str) and not (span and (span in case["instruction"] or span in case.get("text", ""))):
                    provenance.append("goal field is not a literal owner/context span: " + field)
            result["handoff_ready"] = False
            result["handoff_note"] = "Research-only interpretation labels; no production planner or device execution."
    elif kind in WRITING_KINDS:
        keys = {"subject", "body"} if kind == "email" else {"text"}
        if set(value) != keys or not all(isinstance(item, str) and item.strip() for item in value.values()):
            schema.append("writing output needs exactly the required nonempty string fields")
        else:
            output = "\n".join(value.values())
            source = case.get("text") or case["instruction"]
            missing_ids, new_ids = identifiers(source) - identifiers(output), identifiers(output) - identifiers(source)
            missing_numbers, new_numbers = set(NUMBER.findall(source)) - set(NUMBER.findall(output)), set(NUMBER.findall(output)) - set(NUMBER.findall(source))
            result["prose_canaries"] = {"missing_identifiers": sorted(missing_ids), "new_identifiers": sorted(new_ids),
                "missing_numeric_tokens": sorted(missing_numbers), "new_numeric_tokens": sorted(new_numbers),
                "source_has_negation": bool(NEGATIVE.search(source)), "output_has_negation": bool(NEGATIVE.search(output))}
            if missing_ids or new_ids:
                errors.append("identifier preservation failed")
            if missing_numbers or new_numbers:
                errors.append("numeric-token preservation failed")
            if NEGATIVE.search(source) and not NEGATIVE.search(output):
                errors.append("source negation has no output lexical canary")
            for literal in checks.get("required", []):
                if literal not in output:
                    errors.append("missing required literal: " + literal)
            for literal in checks.get("forbidden", []):
                if literal.lower() in output.lower():
                    errors.append("forbidden literal: " + literal)
            for pattern in checks.get("forbidden_regex", []):
                if re.search(pattern, output, re.I):
                    errors.append("forbidden pattern: " + pattern)
            body = value["body"] if kind == "email" else value["text"]
            if "max_words" in checks and len(body.split()) > checks["max_words"]:
                errors.append("output exceeds max_words")
            if "subject_max_words" in checks and len(value.get("subject", "").split()) > checks["subject_max_words"]:
                errors.append("subject exceeds subject_max_words")
            result["mechanical_checks_pass"] = not errors
    else:
        schema.append("unsupported language kind")
    result["schema_valid"] = not schema
    if kind not in WRITING_KINDS:
        result["provenance_valid"] = not provenance and not schema
        result["values_valid"] = not typed and not schema
    errors.extend("schema: " + item for item in schema)
    errors.extend("provenance: " + item for item in provenance)
    errors.extend("value: " + item for item in typed)
    alternatives = case.get("expected_any", [case["expected"]] if "expected" in case else [])
    if alternatives:
        result["expected_match"] = any(comparable(value, kind) == comparable(item, kind) for item in alternatives)
        if not result["expected_match"]:
            errors.append("output differs from every independent expected object")
    partial = metadata.get("expected_partial_context")
    if kind == "task_update" and partial and not schema:
        merged = parse_json(case["text"]).get("known", {}).copy()
        if value["operation"] == "update":
            merged.update({row["field"]: row["value"] for row in value["updates"]})
        result["partial_context_fields_match"] = all(merged.get(key) == item for key, item in partial["fields"].items())
        if not result["partial_context_fields_match"]:
            errors.append("supplied fields do not preserve expected partial context")
    result["mechanical_pass"] = not errors
    return result


def counters(rows):
    groups = defaultdict(lambda: defaultdict(int))
    for row in rows:
        group = groups[row["family"]]
        group["cases"] += 1
        group["generated"] += row.get("status") == "generated"
        group["errors"] += row.get("status") == "error"
        group["truncations"] += bool(row.get("truncated"))
        for key in ("valid_json", "schema_valid", "provenance_valid", "values_valid", "expected_match", "mechanical_checks_pass", "mechanical_pass"):
            if row.get(key) is not None:
                group[key + "_scored"] += 1
                group[key + "_count"] += bool(row[key])
        group["semantic_reviews_pending"] += row.get("semantic_review", {}).get("status") == "pending"
    return {key: dict(value) for key, value in sorted(groups.items())}


def run(args):
    cases, sources = load_cases(args.cases)
    if args.family:
        cases = [row for row in cases if row["family"] in args.family]
        if not cases:
            raise ValueError("No cases in the requested families")
    if args.output.exists():
        raise ValueError("Refusing to overwrite an existing regression report")
    local = Path(args.model).is_dir()
    revision = args.revision or (TEACHER_REVISION if args.model == TEACHER else None)
    if not local and (not revision or not re.fullmatch("[0-9a-f]{40}", revision)):
        raise ValueError("A remote model requires an immutable --revision commit SHA")
    report = {"scope": SCOPE, "created_utc": datetime.now(timezone.utc).isoformat(), "prompt_version": PROMPT_VERSION,
              "frozen_before_model_load": True, "corpora": sources, "cases": cases, "outputs": [],
              "split_policy": "Evaluation rows must never train, label training data or select a checkpoint. Changing prompts after seeing these results contaminates this set.",
              "model": {"source": args.model, "requested_revision": revision, "local_checkpoint": local},
              "prompt_source_sha256": sha256(Path(__file__).with_name("prompts.py").read_bytes()),
              "grader_source_sha256": sha256(Path(__file__).read_bytes()),
              "generation": {"dtype": "bfloat16", "attention": args.attention, "do_sample": False, "grammar": None,
                             "max_input_tokens": args.max_input_tokens, "max_new_tokens": args.max_new_tokens}}
    args.output.parent.mkdir(parents=True, exist_ok=True)

    def save():
        report["by_family"] = counters(report["outputs"])
        temporary = args.output.with_name(args.output.name + ".tmp")
        temporary.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        temporary.replace(args.output)

    save()
    import torch
    import transformers
    from transformers import AutoModelForCausalLM, AutoTokenizer

    if transformers.__version__ != "4.57.6":
        raise ValueError("Use the isolated setup.sh environment: Transformers 4.57.6 required")
    if not torch.cuda.is_available() or not torch.cuda.is_bf16_supported():
        raise ValueError("Regression requires a CUDA GPU supporting BF16")
    report["environment"] = {"python": platform.python_version(), "torch": torch.__version__, "torch_location": torch.__file__,
        "cuda": torch.version.cuda, "transformers": transformers.__version__, "gpu": torch.cuda.get_device_name(),
        "gpu_total_bytes": torch.cuda.get_device_properties(0).total_memory}
    tokenizer = model = inputs = tokens = None
    try:
        torch.cuda.empty_cache()
        torch.cuda.reset_peak_memory_stats()
        started = time.perf_counter()
        kwargs = {"revision": revision, "cache_dir": args.cache, "trust_remote_code": False}
        tokenizer = AutoTokenizer.from_pretrained(args.model, **kwargs)
        model = AutoModelForCausalLM.from_pretrained(args.model, **kwargs, torch_dtype=torch.bfloat16, attn_implementation=args.attention).to("cuda").eval()
        torch.cuda.synchronize()
        report["load_s"] = time.perf_counter() - started
        report["model"]["resolved_revision"] = getattr(model.config, "_commit_hash", None)
        if local:
            report["model"]["local_files"] = {str(path.relative_to(args.model)): {"bytes": path.stat().st_size, "sha256": sha256(path.read_bytes())}
                for path in sorted(Path(args.model).glob("*.json"))}
        report["chat_template"] = tokenizer.chat_template
        report["chat_template_sha256"] = sha256(json.dumps(tokenizer.chat_template, sort_keys=True).encode())
        save()
        for case in cases:
            row = {"id": case["id"], "family": case["family"], "kind": case["kind"], "status": "error"}
            report["outputs"].append(row)
            try:
                messages = build_messages(case)
                prompt = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
                inputs = tokenizer(prompt, return_tensors="pt", add_special_tokens=False).to("cuda")
                count = inputs["input_ids"].shape[-1]
                row.update({"rendered_prompt": prompt, "prompt_sha256": sha256(prompt.encode()), "input_tokens": count})
                if count > args.max_input_tokens:
                    raise ValueError("Input exceeds the explicit regression context limit; it was not truncated")
                maximum = min(args.max_new_tokens, int(case.get("max_tokens", 192 if case["kind"] == "task_update" else 128)))
                torch.cuda.reset_peak_memory_stats()
                torch.cuda.synchronize()
                started = time.perf_counter()
                with torch.inference_mode():
                    tokens = model.generate(**inputs, max_new_tokens=maximum, do_sample=False, pad_token_id=tokenizer.eos_token_id)
                torch.cuda.synchronize()
                elapsed = time.perf_counter() - started
                generated = tokens[0, count:].tolist()
                eos = model.generation_config.eos_token_id
                eos = eos if isinstance(eos, list) else [eos]
                truncated = len(generated) >= maximum and (not generated or generated[-1] not in eos)
                raw = tokenizer.decode(generated, skip_special_tokens=True)
                row.update({"status": "generated", "raw_response": raw,
                    "raw_with_special_tokens": tokenizer.decode(generated, skip_special_tokens=False),
                    "generation_s": elapsed, "output_tokens": len(generated), "output_tokens_per_s": len(generated) / elapsed,
                    "max_new_tokens": maximum, "truncated": truncated, "finish_reason": "length" if truncated else "eos_or_stop",
                    "gpu_peak_allocated_bytes": torch.cuda.max_memory_allocated(), **evaluate(case, raw)})
                if truncated:
                    row["mechanical_pass"] = False
                    row["check_errors"].append("generation reached token limit without EOS")
            except Exception as error:
                row["error"] = type(error).__name__ + ": " + str(error)
            finally:
                inputs = tokens = None
                save()
                print(json.dumps({key: row.get(key) for key in ("id", "family", "status", "mechanical_pass", "generation_s", "truncated")}), flush=True)
    except Exception as error:
        report["error"] = type(error).__name__ + ": " + str(error)
    finally:
        del inputs, tokens, model, tokenizer
        gc.collect()
        torch.cuda.empty_cache()
        report["completed_utc"] = datetime.now(timezone.utc).isoformat()
        report["all_cases_attempted"] = len(report["outputs"]) == len(cases)
        save()
    print(args.output)
    return int("error" in report or any(row["status"] == "error" for row in report["outputs"]))


def self_check():
    case = {"id": "fixture", "family": "task_update", "kind": "task_update", "instruction": "Block a calendar session.",
        "text": json.dumps({"capability": "calendar.create", "fields": ["date", "start", "end", "duration", "title", "zone"],
            "current": "Make it half an hour.", "date": "2026-10-21", "zone": "Asia/Kolkata", "known": {}}),
        "expected": {"operation": "update", "updates": [{"field": "duration", "text": "half an hour", "value": "30"}]}}
    assert evaluate(case, json.dumps(case["expected"]))["mechanical_pass"]
    for raw in ('{} trailing', '```json\n{}\n```', '{"a":1,"a":2}', '{"a":NaN}', '[]'):
        assert not evaluate(case, raw)["valid_json"]
    for change in ({"field": "package"}, {"value": 30}, {"text": "missing"}, {"value": "0"}):
        wrong = {"operation": "update", "updates": [{**case["expected"]["updates"][0], **change}]}
        assert not evaluate(case, json.dumps(wrong))["mechanical_pass"]
    assert not evaluate(case, '{"operation":[],"updates":[]}')["schema_valid"]
    assert not unique_span("aaa", "aa")
    prose = {"kind": "rewrite", "instruction": "Rewrite politely.", "text": "I cannot attend REF42 at 5 PM.",
        "checks": {"required": ["REF42"], "max_words": 20}}
    good = evaluate(prose, '{"text":"Sorry, I cannot attend REF42 at 5 PM."}')
    assert good["mechanical_pass"] and good["semantic_review"]["status"] == "pending"
    for text in ("I can attend REF42 at 5 PM.", "I cannot attend XREF42 at 5 PM.", "I cannot attend REF42 at 6 PM."):
        assert not evaluate(prose, json.dumps({"text": text}))["mechanical_pass"]
    intent = {"kind": "intent", "instruction": "play a song from my library", "text": "[]",
        "expected": {"mode": "navigate", "action": "play", "target": "", "scope": "my library", "source_id": "request"}}
    assert evaluate(intent, json.dumps(intent["expected"]))["mechanical_pass"]
    unresolved = {**intent, "instruction": "play something above", "expected": {
        "mode": "act", "action": "play", "target": "", "scope": "", "source_id": "request"}}
    result = evaluate(unresolved, json.dumps(unresolved["expected"]))
    assert result["mechanical_pass"] and not result["handoff_ready"]
    assert not evaluate(intent, '{"mode":[],"action":"play","target":"","scope":"","source_id":"request"}')["schema_valid"]
    goal = {"kind": "goal", "instruction": "Search Google Maps for Lake Park", "text": "",
        "expected": {"capabilities": ["maps.search"], "fields": {"app": "Google Maps", "query": "Lake Park"}, "missing": []}}
    assert evaluate(goal, json.dumps(goal["expected"]))["mechanical_pass"]
    changed = {**goal["expected"], "fields": {"query": "invented hotel"}}
    assert not evaluate(goal, json.dumps(changed))["provenance_valid"]
    assert build_messages({**prose, "expected": {"text": "SECRET_EXPECTED"}}) == build_messages(prose)
    print("SLM regression self-check passed (strict JSON, schema, provenance, typed values, literal canaries, review separation)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-check", action="store_true")
    parser.add_argument("--cases", nargs="+", type=Path, default=[ROOT / "tests/slm-regression.json"])
    parser.add_argument("--model", default=TEACHER)
    parser.add_argument("--revision")
    parser.add_argument("--attention", choices=["sdpa", "eager"], default="sdpa")
    parser.add_argument("--family", nargs="+")
    parser.add_argument("--max-input-tokens", type=int, default=2048)
    parser.add_argument("--max-new-tokens", type=int, default=256)
    parser.add_argument("--cache", type=Path, default=Path(__file__).resolve().parent / ".hf")
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parent / "out" / (datetime.now(timezone.utc).strftime("regression-%Y%m%dT%H%M%S.%fZ") + ".json"))
    args = parser.parse_args()
    if args.self_check:
        self_check()
    else:
        raise SystemExit(run(args))
