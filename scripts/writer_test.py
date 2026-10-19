"""Exercise Friday's local Writer bridge using synthetic text, without opening or sending to other apps.

Forward the bridge first: adb forward tcp:8765 localabstract:layatest_agent
    python3 scripts/writer_test.py
    python3 scripts/writer_test.py --case W01 --model writer-qwen.gguf
    python3 scripts/writer_test.py --self-check

Reports go to ignored captures/writer-tests/. Mechanical checks do not establish tone,
faithfulness or semantic correctness; inspect each successful output using its manual rubric.
Every request is cold: Friday frees the Writer model after a request.
"""
import argparse
import json
import math
import os
import re
import socket
import sys
import time
from collections import Counter
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
CASES = ROOT / "tests/writer-cases.json"
STATUSES = {"ok", "invalid", "cancelled", "timeout", "length", "unavailable", "error", "busy"}
MILLISECONDS = ("load_ms", "prefill_ms", "decode_ms", "total_ms")
TOKENS = ("input_tokens", "output_tokens")


def call(request, host="127.0.0.1", port=8765):
    # The app owns its 30 s deadline; allow time to return its structured timeout response.
    with socket.create_connection((host, port), timeout=35) as connection:
        with connection.makefile("rw", encoding="utf-8") as stream:
            stream.write(json.dumps(request, ensure_ascii=False) + "\n")
            stream.flush()
            line = stream.readline()
    if not line:
        raise ValueError("bridge closed without a response")
    return json.loads(line)


def word_count(text):
    return len(text.split())


def check_response(case, response):
    """Return only mechanically observable failures, never a semantic pass judgment."""
    if not isinstance(response, dict):
        return ["response is not a JSON object"], ""
    fails = []
    status = response.get("status")
    if not isinstance(status, str) or status not in STATUSES:
        fails.append("unknown/missing status: %r" % status)
    if status not in case["expected_statuses"]:
        fails.append("expected %s, received %s" % ("|".join(case["expected_statuses"]), status))
    if not isinstance(response.get("model"), str):
        fails.append("missing model identifier")
    for name in MILLISECONDS:
        if name not in response and status != "ok" and name != "total_ms":
            continue
        value = response.get(name)
        if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
            fails.append("invalid/missing %s" % name)
    for name in TOKENS:
        if name not in response and status != "ok":
            continue
        if type(response.get(name)) is not int or response[name] < 0:
            fails.append("invalid/missing %s" % name)
    if "validation_errors" in response and (
        not isinstance(response["validation_errors"], list)
        or not all(isinstance(value, str) for value in response["validation_errors"])
    ):
        fails.append("validation_errors must be a string array")
    if status != "ok":
        for field in ("text", "subject", "body"):
            if field in response:
                fails.append("non-ok response exposes accepted output key: %s" % field)
        return fails, ""
    if response.get("validation_errors"):
        fails.append("ok response contains validation errors")

    email = case["request"].get("kind") == "email"
    fields = ("subject", "body") if email else ("text",)
    for field in fields:
        if not isinstance(response.get(field), str) or not response[field].strip():
            fails.append("missing/non-string/empty %s" % field)
    output = response.get("body" if email else "text", "")
    if not isinstance(output, str):
        output = ""
    checks = case.get("checks", {})
    for literal in checks.get("required", []):
        if literal not in output:
            fails.append("missing literal: %r" % literal)
    for pattern in checks.get("required_regex", []):
        if not re.search(pattern, output, re.IGNORECASE):
            fails.append("missing pattern: %r" % pattern)
    for literal in checks.get("forbidden", []):
        if literal.casefold() in output.casefold():
            fails.append("forbidden literal: %r" % literal)
    for pattern in checks.get("forbidden_regex", []):
        if re.search(pattern, output, re.IGNORECASE):
            fails.append("forbidden pattern: %r" % pattern)
    if "max_words" in checks and word_count(output) > checks["max_words"]:
        fails.append("%d words exceeds %d" % (word_count(output), checks["max_words"]))
    if email and "subject_max_words" in checks:
        subject = response.get("subject", "")
        if isinstance(subject, str) and word_count(subject) > checks["subject_max_words"]:
            fails.append("subject word limit exceeded")
    return fails, output


def run(case, model, host="127.0.0.1", port=8765):
    request = {"cmd": "writer", "model": model, "max_tokens": 128, "timeout_ms": 20000}
    request.update(case["request"])
    start = time.monotonic()
    try:
        response = call(request, host, port)
    except (OSError, ValueError) as error:
        response = {"status": "transport_error", "error": str(error)}
    wall_ms = round((time.monotonic() - start) * 1000)
    fails, output = check_response(case, response)
    return {
        "id": case["id"], "description": case["description"], "request": request,
        "expected_statuses": case["expected_statuses"], "response": response,
        "wall_ms": wall_ms, "mechanical_pass": not fails, "failures": fails,
        "output_words": word_count(output),
        "semantic_review": "pending" if isinstance(response, dict) and response.get("status") == "ok"
                           else "no accepted output",
        "manual_rubric": case["manual_rubric"],
    }


def self_check():
    fixtures = json.loads(CASES.read_text())["cases"]
    assert len(fixtures) == 20 and len({case["id"] for case in fixtures}) == 20
    for fixture in fixtures:
        assert set(fixture["expected_statuses"]) <= STATUSES
        for key in ("required_regex", "forbidden_regex"):
            for pattern in fixture.get("checks", {}).get(key, []):
                re.compile(pattern)
    case = {"request": {"kind": "message"}, "expected_statuses": ["ok"],
            "checks": {"required": ["REF42"], "forbidden": ["confirmed"], "max_words": 3}}
    good = {"status": "ok", "text": "REF42 received", "model": "writer.gguf",
            "load_ms": 1, "prefill_ms": 2, "decode_ms": 3, "total_ms": 6,
            "input_tokens": 5, "output_tokens": 2, "validation_errors": []}
    assert check_response(case, good) == ([], "REF42 received")
    assert check_response(case, {**good, "text": "REF43 confirmed extra words"})[0]
    assert check_response(case, {**good, "output_tokens": True})[0]
    assert check_response(case, {**good, "total_ms": float("nan")})[0]
    assert check_response(case, [good])[0]
    assert check_response(case, {**good, "status": []})[0]
    for status in STATUSES - {"ok"}:
        negative = {"request": {"kind": "summary"}, "expected_statuses": [status]}
        rejected = {"status": status, "model": "writer.gguf", "total_ms": 0}
        assert check_response(negative, rejected) == ([], "")
        for field in ("text", "subject", "body"):
            for value in ("leaked draft", "", None):
                failures, output = check_response(negative, {**rejected, field: value})
                assert "non-ok response exposes accepted output key: %s" % field in failures
                assert output == ""
    assert check_response(case, {**good, "attempts": 2, "total_input_tokens": 10,
                                 "total_output_tokens": 4}) == ([], "REF42 received")
    email = {"request": {"kind": "email"}, "expected_statuses": ["ok"],
             "checks": {"required": ["REF42"], "max_words": 3, "subject_max_words": 2}}
    assert not check_response(email, {**good, "subject": "Update", "body": "REF42 received"})[0]
    assert check_response(email, good)[0]
    print("Writer harness self-check passed; no bridge or model was used.")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--case", action="append", default=[], metavar="ID", help="run one case; repeat for several")
    parser.add_argument("--model", choices=("writer.gguf", "writer-qwen.gguf"), default="writer.gguf")
    parser.add_argument("--host", default=os.environ.get("FRIDAY_BRIDGE_HOST") or "127.0.0.1",
                        help="bridge host (default: FRIDAY_BRIDGE_HOST or 127.0.0.1)")
    parser.add_argument("--port", type=int, default=os.environ.get("FRIDAY_BRIDGE_PORT") or "8765",
                        help="bridge port (default: FRIDAY_BRIDGE_PORT or 8765)")
    parser.add_argument("--out", type=Path, help="report path (default: captures/writer-tests/<timestamp>.json)")
    parser.add_argument("--self-check", action="store_true", help="check harness logic without a device")
    args = parser.parse_args()
    if args.self_check:
        self_check()
        return 0
    fixture = json.loads(CASES.read_text())
    cases = fixture["cases"]
    unknown = set(args.case) - {case["id"] for case in cases}
    if unknown:
        parser.error("unknown case IDs: " + ", ".join(sorted(unknown)))
    cases = [case for case in cases if not args.case or case["id"] in args.case]
    results = []
    for case in cases:
        result = run(case, args.model, args.host, args.port)
        results.append(result)
        response = result["response"]
        status = response.get("status", "invalid object") if isinstance(response, dict) else "invalid object"
        print("%s CHECK %s %-11s %6.2fs %s" % (
            result["id"], "PASS" if result["mechanical_pass"] else "FAIL", status,
            result["wall_ms"] / 1000, "; ".join(result["failures"]) or result["description"]), flush=True)
    counts = Counter(r["response"].get("status", "invalid object") if isinstance(r["response"], dict)
                     else "invalid object" for r in results)
    accepted = sum(r["semantic_review"] == "pending" for r in results)
    passed = sum(r["mechanical_pass"] for r in results)
    out = args.out or ROOT / "captures/writer-tests" / (
        datetime.now().strftime("%Y%m%d-%H%M%S-%f") + "-" + Path(args.model).stem + ".json")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({
        "recorded_at": datetime.now().astimezone().isoformat(), "model_requested": args.model,
        "fixture_version": fixture["version"], "lifecycle": "cold per request; app unloads writer",
        "mechanical_passes": passed, "case_count": len(results), "status_counts": dict(counts),
        "semantic_reviews_pending": accepted, "manual_scenarios": fixture["manual_scenarios"],
        "results": results,
    }, ensure_ascii=False, indent=2) + "\n")
    print("\nMechanical checks: %d/%d; accepted outputs needing semantic review: %d. %s" % (
        passed, len(results), accepted, dict(counts)))
    print("Report: %s" % out)
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
