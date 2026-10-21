#!/usr/bin/env python3
"""Four synthetic SLM cases; GPU smoke only, never Android runtime parity."""
import argparse
import gc
import hashlib
import json
import platform
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
MODELS = {
    "smollm2": ("HuggingFaceTB/SmolLM2-360M-Instruct", "a10cc1512eabd3dde888204e902eca88bddb4951"),
    "lfm2": ("LiquidAI/LFM2-350M", "f37d3f5c8c5484bc01dad379a595cf4c68c4e70e"),
    "qwen3": ("Qwen/Qwen3-4B-Instruct-2507", "cdbee75f17c01a7cc42f958dc650907174af0554"),
}
CASES = [
    {"id": "duration",
     "system": "You interpret replies to a saved calendar task. Return only JSON with a string field duration_minutes. Use the duration supplied by the owner. Do not invent missing information.",
     "user": "Saved task: block my calendar on 2026-10-23 at 14:00 Asia/Kolkata. Missing: duration. Owner reply: make it half an hour.",
     "expected": {"duration_minutes": "30"}},
    {"id": "combined_fields",
     "system": "Extract every supplied calendar field. Reference date: 2026-10-21. Normalize date to YYYY-MM-DD and start to HH:mm. Copy the time_zone phrase exactly. Return only JSON with string fields date, start and time_zone.",
     "user": "this Friday, 2:00 p.m. Indian time",
     "expected": {"date": "2026-10-23", "start": "14:00", "time_zone": "Indian time"}},
    {"id": "message",
     "system": "Write one short polite message. Preserve all source facts and numbers. Return only JSON with a string field text. Do not claim the message was sent.",
     "user": "I will be 15 minutes late.", "expected": None,
     "semantic_review": ["One short polite message", "Preserves 15 minutes late", "Does not claim it was sent"]},
    {"id": "negation",
     "system": "Rewrite the source politely as a short message. Keep the negative meaning and the exact reference code. Return only JSON with a string field text.",
     "user": "I cannot attend the call today. Reference REF42.", "expected": None,
     "semantic_review": ["Preserves inability to attend the call today", "Preserves REF42 exactly", "Short and polite"]},
]
SCOPE = "BF16 unconstrained GPU smoke with model chat templates; NOT production prompt/runtime or phone parity"


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key: " + key)
        result[key] = value
    return result


def reject_constant(value):
    raise ValueError("Nonfinite JSON value: " + value)


def evaluate(case, raw):
    expected = case["expected"]
    result = {"valid_json": False, "schema_valid": False,
              "exact_fields_match": None if expected is None else False}
    try:
        value = json.loads(raw, object_pairs_hook=unique_object, parse_constant=reject_constant)
        if not isinstance(value, dict):
            raise ValueError("Expected a JSON object")
        result["valid_json"] = True
        keys = set(expected) if expected is not None else {"text"}
        result["schema_valid"] = set(value) == keys and all(
            isinstance(item, str) and bool(item.strip()) for item in value.values())
        if expected is not None:
            result["exact_fields_match"] = result["schema_valid"] and value == expected
    except (ValueError, TypeError) as error:
        result["json_error"] = str(error)
    if expected is None:
        result["semantic_review"] = {"status": "pending", "criteria": case["semantic_review"]}
    return result


def self_check():
    duration, combined, message, _ = CASES
    assert evaluate(duration, '{"duration_minutes":"30"}')["exact_fields_match"]
    assert evaluate(combined, json.dumps(combined["expected"]))["exact_fields_match"]
    for raw in ('```json\n{"duration_minutes":"30"}\n```', '{} trailing', '"30"',
                '{"duration_minutes":"30","duration_minutes":"60"}', '{"duration_minutes":NaN}'):
        assert not evaluate(duration, raw)["valid_json"], raw
    for raw in ('{"duration_minutes":30}', '{"duration_minutes":"30","extra":"x"}', '{}'):
        assert not evaluate(duration, raw)["schema_valid"], raw
    prose = evaluate(message, '{"text":"I will be 15 minutes late."}')
    assert prose["schema_valid"] and prose["exact_fields_match"] is None
    assert prose["semantic_review"]["status"] == "pending"
    assert not evaluate(message, '{"text":""}')["schema_valid"]
    assert json.loads(json.dumps(prose)) == prose
    print("SLM probe self-check passed (strict JSON, schemas, exact fields, pending prose review)")


def run(names, output):
    # Lazy imports keep --self-check independent of GPU packages and model downloads.
    import torch
    import transformers
    import safetensors
    from transformers import AutoModelForCausalLM, AutoTokenizer

    if transformers.__version__ != "4.57.6" or safetensors.__version__ != "0.8.0":
        raise SystemExit("Run setup.sh: required transformers 4.57.6 and safetensors 0.8.0")
    if not torch.cuda.is_available() or not torch.cuda.is_bf16_supported():
        raise SystemExit("This probe requires a CUDA GPU supporting BF16")
    report = {"scope": SCOPE, "created_utc": datetime.now(timezone.utc).isoformat(),
              "environment": {"python": platform.python_version(), "torch": torch.__version__,
                              "torch_location": torch.__file__, "cuda": torch.version.cuda,
                              "transformers": transformers.__version__, "safetensors": safetensors.__version__,
                              "gpu": torch.cuda.get_device_name(), "dtype": "bfloat16", "attention": "eager"},
              "generation": {"max_new_tokens": 96, "do_sample": False, "grammar": None},
              "cases": CASES, "models": []}
    output.parent.mkdir(parents=True, exist_ok=True)

    def save():
        temporary = output.with_name(output.name + ".tmp")
        temporary.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        temporary.replace(output)

    save()
    for name in names:
        model_id, revision = MODELS[name]
        record = {"model": model_id, "revision": revision, "outputs": []}
        report["models"].append(record)
        torch.cuda.empty_cache()
        torch.cuda.reset_peak_memory_stats()
        tokenizer = model = inputs = tokens = None
        try:
            started = time.perf_counter()
            tokenizer = AutoTokenizer.from_pretrained(model_id, revision=revision, cache_dir=ROOT / ".hf")
            model = AutoModelForCausalLM.from_pretrained(
                model_id, revision=revision, cache_dir=ROOT / ".hf", torch_dtype=torch.bfloat16,
                attn_implementation="eager").to("cuda").eval()
            torch.cuda.synchronize()
            record["load_s"] = time.perf_counter() - started
            record["chat_template"] = tokenizer.chat_template
            record["chat_template_sha256"] = hashlib.sha256(
                json.dumps(tokenizer.chat_template, sort_keys=True).encode()).hexdigest()
            for case in CASES:
                prompt = tokenizer.apply_chat_template(
                    [{"role": "system", "content": case["system"]}, {"role": "user", "content": case["user"]}],
                    tokenize=False, add_generation_prompt=True)
                inputs = tokenizer(prompt, return_tensors="pt").to("cuda")
                torch.cuda.synchronize()
                started = time.perf_counter()
                with torch.inference_mode():
                    tokens = model.generate(**inputs, max_new_tokens=96, do_sample=False,
                                            pad_token_id=tokenizer.eos_token_id)
                torch.cuda.synchronize()
                elapsed = time.perf_counter() - started
                count = inputs["input_ids"].shape[-1]
                raw = tokenizer.decode(tokens[0, count:], skip_special_tokens=True)
                record["outputs"].append({"id": case["id"], "text": raw, **evaluate(case, raw),
                                          "generation_s": elapsed, "input_tokens": count,
                                          "output_tokens": tokens.shape[-1] - count,
                                          "rendered_prompt": prompt})
                save()
        except Exception as error:
            record["error"] = type(error).__name__ + ": " + str(error)
        finally:
            record["gpu_peak_allocated_bytes"] = torch.cuda.max_memory_allocated()
            del inputs, tokens, model, tokenizer
            gc.collect()
            torch.cuda.empty_cache()
            save()
    print(output)
    return int(any("error" in model for model in report["models"]))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-check", action="store_true")
    parser.add_argument("--models", nargs="+", choices=MODELS, default=["smollm2", "lfm2"])
    parser.add_argument("--output", type=Path, default=ROOT / "out" / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ") + ".json"))
    args = parser.parse_args()
    if args.self_check:
        self_check()
    else:
        raise SystemExit(run(args.models, args.output))
