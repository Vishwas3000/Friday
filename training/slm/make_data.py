#!/usr/bin/env python3
"""Generate seeded, independent synthetic candidates; never train on frozen evaluation.

Defaults to 1,200 training and 240 validation rows. Teacher outputs must still
match independently constructed structured gold; writing needs semantic review.
"""
import argparse
import hashlib
import json
import random
import re
import tempfile
from collections import Counter
from datetime import date, time, timedelta
from pathlib import Path

from prompts import GOAL_CAPABILITIES, GOAL_FIELDS, PROMPT_VERSION, prompt_digest_input

ROOT = Path(__file__).resolve().parents[2]
CALENDAR_FIELDS = ["date", "start", "end", "duration", "title", "zone"]
SPLITS = {
    "train": {
        "reference": date(2030, 2, 11), "names": ["Rhea", "Kellan", "Amari", "Tobin"],
        "places": ["Lugano", "Bruges", "Aalborg", "Brno"],
        "adjectives": ["Mint", "Cobalt", "Cedar", "Lunar", "Saffron"],
        "nouns": ["Ledger", "Harbor", "Workshop", "Sketch", "Notebook"],
        "sections": ["my collection", "my archive", "my downloads"],
        "amounts": [20, 40, 50, 70, 80, 100, 110],
    },
    "validation": {
        "reference": date(2031, 7, 9), "names": ["Petra", "Nolan", "Elio", "Sana"],
        "places": ["Girona", "Turku", "Linz", "Tartu"],
        "adjectives": ["Umber", "Jade", "Opal", "Birch", "Velvet"],
        "nouns": ["Journal", "Beacon", "Studio", "Mosaic", "Portfolio"],
        "sections": ["our saved items", "the catalog", "my favorites"],
        "amounts": [14, 21, 28, 35, 49, 56, 63, 77],
    },
}


def encoded(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=True, separators=(",", ":"))


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def fingerprint(row):
    return digest(prompt_digest_input(row))


def patch(*updates, operation="update"):
    return {"operation": operation, "updates": [dict(zip(("field", "text", "value"), values)) for values in updates]}


def task_input(current, reference, known, missing, capability="calendar.create"):
    return encoded({"capability": capability,
                    "fields": CALENDAR_FIELDS if capability == "calendar.create" else ["query"],
                    "current": current, "date": reference.isoformat(), "zone": "UTC",
                    "known": known, "missing": missing,
                    "question": "Which remaining task detail should I use?"})


def sample(index, split, rng):
    pool = SPLITS[split]
    training = split == "train"
    reference = pool["reference"]
    day = (reference + timedelta(days=rng.randint(2, 75))).isoformat()
    name, place = rng.choice(pool["names"]), rng.choice(pool["places"])
    item = "%s %s %04d" % (rng.choice(pool["adjectives"]), rng.choice(pool["nouns"]), index)
    section = rng.choice(pool["sections"])
    hour = rng.choice([7, 8, 11, 13, 17, 18, 20] if training else [6, 9, 12, 14, 16, 19, 21])
    start = "%02d:%02d" % (hour, rng.choice([5, 25, 35, 50] if training else [10, 20, 40, 55]))
    minutes = rng.choice(pool["amounts"])
    code = ("SYN-A" if training else "SYN-B") + "%05d" % index
    known = {"date": day, "start": start, "title": item}
    category = index % 12
    row = {"id": "SYN-%s-%06d" % (split, index), "split": split,
           "provenance": "Independent seeded synthetic candidate; gold constructed before teacher inference. No device/app/private/evaluation contents.",
           "metadata": {"generator_version": 1, "reference_date": reference.isoformat(),
                        "review_status": "teacher_target_not_generated"}}
    family = ""
    if category == 0:
        family = "spoken_duration" if training else "fraction_duration"
        choices = ([('a quarter of an hour', '15'), ('two hours', '120'), ('one hundred minutes', '100')]
                   if training else [('three quarters of an hour', '45'), ('two and a half hours', '150'), ('one hour and thirty-five minutes', '95')])
        phrase, value = rng.choice(choices)
        current = ("Let this session last %s, please." if training else "For the saved slot, use a length of %s.") % phrase
        row.update(kind="task_update", instruction="Reserve a calendar slot for " + item + ".",
                   text=task_input(current, reference, known, ["end"]), expected=patch(("duration", phrase, value)))
    elif category == 1:
        family = "relative_date" if training else "date_correction"
        if training:
            days = rng.choice([2, 4, 6, 8])
            words = {2: 'two', 4: 'four', 6: 'six', 8: 'eight'}[days]
            span = "in " + words + " days"
            current = "The date should be " + span + "."
            value = (reference + timedelta(days=days)).isoformat()
        else:
            span, value = day, day
            current = "The corrected appointment date is " + span + "."
        row.update(kind="task_update", instruction="Keep the calendar reservation for " + item + ".",
                   text=task_input(current, reference, known, ["date"]), expected=patch(("date", span, value)))
    elif category == 2:
        variant = (index // 12) % 3
        if variant == 0:
            family = "date_clock_pair" if training else "clock_date_pair"
            current = ("Use %s, starting at %s." % (day, start) if training else
                       "Starting at %s on %s is the correction." % (start, day))
            fields, missing = {"title": item}, ["date"]
            expected = patch(("date", day, day), ("start", start, start))
        elif variant == 1:
            family = "finish_clock" if training else "end_clock_revision"
            end = "%02d:%s" % (hour + 1, start[-2:])
            current = ("End at %s." if training else "The finish should be %s.") % end
            fields, missing, expected = known, ["end"], patch(("end", end, end))
        else:
            family = "begin_clock" if training else "start_clock_revision"
            current = ("Start at %s." if training else "The intended start is at %s.") % start
            fields, missing, expected = known, ["start"], patch(("start", start, start))
        row.update(kind="task_update", instruction="Continue the calendar setup for " + item + ".",
                   text=task_input(current, reference, fields, missing), expected=expected)
    elif category == 3:
        choice = (index // 12) % 3
        if choice == 0:
            family = "literal_title" if training else "rename_title"
            current = ('Call the session "%s".' if training else 'Its corrected label should be "%s".') % item
            row.update(kind="task_update", instruction="Complete the saved event, preserving the times.",
                       text=task_input(current, reference, {"date": day, "start": start, "duration": str(minutes)}, ["title"]),
                       expected=patch(("title", item, item)))
        elif choice == 1:
            family = "search_replacement" if training else "search_revision"
            query = item + (" field recordings" if training else " instrument practice")
            current = ('Use "%s" in its place.' if training else 'Actually, the replacement phrase is "%s".') % query
            row.update(kind="task_update", instruction="Search the previously chosen app for the next item.",
                       text=task_input(current, reference, {"query": code}, ["query"], "app.search"),
                       expected=patch(("query", query, query)))
        else:
            family = "zone_selection" if training else "zone_revision"
            zone = rng.choice(["Europe/Oslo", "Asia/Tokyo"] if training else ["Europe/Vienna", "Pacific/Auckland"])
            current = ("Keep everything else; use %s." if training else "The intended time zone is %s.") % zone
            row.update(kind="task_update", instruction="Complete the calendar slot for " + item + ".",
                       text=task_input(current, reference, known, ["zone"]), expected=patch(("zone", zone, zone)))
    elif category == 4:
        choice = (index // 12) % 3
        family = ("unresolved_reply" if training else "unresolved_reference") if choice != 2 else ("separate_task" if training else "independent_task")
        if choice == 0:
            current = "I haven't chosen a value yet." if training else "That detail is still undecided."
            expected = patch(operation="clarify")
        elif choice == 1:
            current = "the setting I referred to earlier" if training else "use whatever was in the earlier note"
            expected = patch(operation="clarify")
        else:
            current = ("Open the calculator for %s." if training else "Launch the camera for %s.") % code
            expected = patch(operation="new")
        search = (index // 12) % 2 == 1
        row.update(kind="task_update", instruction=("Continue the selected app search for " if search else "Continue the pending calendar event ") + item + ".",
                   text=task_input(current, reference, {"query": code} if search else known,
                                   ["query"] if search else ["end"], "app.search" if search else "calendar.create"),
                   expected=expected)
    elif category == 5:
        family = "section_navigation" if training else "collection_navigation"
        action = "show" if training else "open"
        instruction = ("show %s in Example Reader" if training else "open %s using Example Reader") % section
        row.update(kind="intent", instruction=instruction,
                   text=encoded({"sources": [], "destination_app": {"package": "dev.example.reader", "label": "Example Reader"}}),
                   expected={"mode": "navigate", "action": action, "target": "", "scope": section, "source_id": "request"})
        row["instruction"] += ". Reference " + code + "."
    elif category == 6:
        family = "listed_item" if training else "ordinal_item"
        source_id = "source-" + code
        second = "Supplement " + code
        instruction = "open the first document above" if training else "view the second document in that list"
        row.update(kind="intent", instruction=instruction,
                   text=encoded({"sources": [{"id": source_id, "text": "Documents:\n1. " + item + "\n2. " + second}],
                                 "destination_app": {"package": "dev.example.reader", "label": "Example Reader"}}),
                   expected={"mode": "act", "action": "open" if training else "view", "target": item if training else second,
                             "scope": "", "source_id": source_id})
    elif category == 7:
        family = "literal_search" if training else "quoted_search"
        query = item + (" Google Maps notes" if training else " Spotify workflow")
        action = "find" if training else "search"
        instruction = ('find "%s"' if training else 'search for "%s"') % query
        row.update(kind="intent", instruction=instruction,
                   text=encoded({"sources": [], "destination_app": {"package": "dev.example.reader", "label": "Example Reader"}}),
                   expected={"mode": "search", "action": action, "target": query, "scope": "", "source_id": "request"})
    elif category == 8:
        if (index // 12) % 2 == 0:
            family = "research_then_stay" if training else "research_stay_dependency"
            season = "quiet travel months" if training else "less crowded travel periods"
            instruction = ("Ask Gemini about %s in %s, then look for hotels with MakeMyTrip. Reference %s." if training else
                           "First research %s for %s with Gemini; afterward search MakeMyTrip for hotels. Tag %s.") % (season, place, code)
            row.update(kind="goal", instruction=instruction, text="",
                       expected={"capabilities": ["gemini.research", "hotel.search"],
                                 "fields": {"app": "MakeMyTrip", "location": place, "season": season}, "missing": ["date", "end"]})
        else:
            family = "calendar_complete" if training else "calendar_single_slot"
            instruction = ("Reserve my calendar on %s at %s for %s minutes, named %s." if training else
                           "Create one calendar appointment called %s for %s minutes on %s at %s.")
            instruction = instruction % ((day, start, minutes, item) if training else (item, minutes, day, start))
            row.update(kind="goal", instruction=instruction, text="",
                       expected={"capabilities": ["calendar.create"], "fields": {"date": day, "start": start,
                                 "duration": str(minutes) + " minutes", "title": item}, "missing": []})
    elif category == 9:
        choice = (index // 12) % 3
        if choice == 0:
            family = "read_bill_summary" if training else "bill_read_dependency"
            instruction = ("Read my credit card bills in CRED and prepare a summary. Ref %s." if training else
                           "Get my credit card bills from CRED before summarizing them. Reference %s.") % code
            row.update(kind="goal", instruction=instruction, text="",
                       expected={"capabilities": ["bills.read", "text.draft"], "fields": {"app": "CRED", "query": "credit card bills"}, "missing": []})
        elif choice == 1:
            family = "message_source_summary" if training else "dm_read_dependency"
            app = "WhatsApp" if training else "Instagram"
            instruction = ("Read %s's latest DM in %s and summarize it. Ref %s." if training else
                           "Summarize the latest DM from %s on %s after reading it. Tag %s.") % (name, app, code)
            row.update(kind="goal", instruction=instruction, text="",
                       expected={"capabilities": ["messages.read", "text.draft"], "fields": {"app": app, "recipient": name, "scope": "latest"}, "missing": []})
        else:
            family = "draft_then_prepare" if training else "compose_handoff"
            body = "I will bring " + code + " tomorrow"
            instruction = ('Write a kind WhatsApp message to %s saying "%s".' if training else
                           'Prepare a polite note in WhatsApp for %s: "%s".') % (name, body)
            row.update(kind="goal", instruction=instruction, text="",
                       expected={"capabilities": ["text.draft", "message.prepare"], "fields": {"app": "WhatsApp", "recipient": name, "text": body}, "missing": []})
    else:
        variant = (index // 12) % 3
        if category == 10:
            family = ("brief_message", "grammar_repair", "search_cleanup")[variant] if training else ("courteous_note", "sentence_fix", "query_polish")[variant]
            kind = ("message", "rewrite", "query")[variant]
            if kind == "message":
                source = ("I cannot bring %s until tomorrow. Please keep it on hold." if training else
                          "I will not collect %s today. Please retain it for %s.") % ((code,) if training else (code, name))
                instruction = "Write a concise polite message, at most 35 words." if training else "Make a courteous note in at most 35 words."
                checks = {"required": [code, "tomorrow"] if training else [code, name, "today"],
                          "max_words": 35,
                          "forbidden": ["sent successfully", "traffic", "sick"]}
            elif kind == "rewrite":
                source = ("I has packed %s with %s notebooks." if training else "We was given %s and %s folders.") % (code, minutes)
                instruction = "Correct the grammar in at most 25 words." if training else "Fix this sentence, keeping it under 25 words."
                checks = {"required": [code, str(minutes)], "max_words": 25}
            else:
                source = "museums near " + place + "; reference " + code
                instruction = "Make one clear search query of at most 18 words." if training else "Polish this into a single search phrase under 18 words."
                checks = {"required": [place, code, "museums"], "max_words": 18}
        else:
            family = ("brief_email", "decision_summary", "document_title")[variant] if training else ("receipt_email", "status_summary", "heading_title")[variant]
            kind = ("email", "summary", "title")[variant]
            if kind == "email":
                source = "%s arrived. I will inspect it on %s. No approval has been given." % (code, day)
                instruction = ("Draft an email: subject at most 7 words; body at most 55 words." if training else
                               "Write a receipt email with a subject of at most 7 words and body of at most 55 words.")
                checks = {"required": [code, day], "forbidden": ["has been approved", "inspection is complete", "attached"],
                          "max_words": 55, "subject_max_words": 7}
            elif kind == "summary":
                source = "%s owns %s. The draft is ready. The budget is undecided and delivery has not happened." % (name, code)
                instruction = "Summarize these facts in at most 35 words." if training else "Give a factual update under 35 words."
                checks = {"required": [name, code], "forbidden": ["budget approved", "delivered successfully"],
                          "max_words": 35}
            else:
                source = "%s compares public gardens in %s." % (code, place)
                instruction = "Write a descriptive title of at most 8 words." if training else "Give this note one heading, at most 8 words."
                checks = {"required": [code, place], "max_words": 8}
        row.update(kind=kind, instruction=instruction, text=source, checks=checks)
        row["metadata"].update(review_required=True, semantic_review=[
            "Retain every supplied factual clause, relationship, numeric token, reference code and negation.",
            "Do not add facts, causes, accounts, recipients, commitments or actions already completed."])
    row["family"] = row["kind"] if row["kind"] in {"task_update", "intent", "goal"} else "writing"
    row["metadata"]["template_family"] = split + "." + family
    return row


def validate_gold(row):
    assert row["split"] in SPLITS and row["instruction"] and isinstance(row["text"], str)
    assert row.get("expected") or row.get("checks"), "Missing independent target/checks"
    kind = row["kind"]
    if kind == "task_update":
        inp, out = json.loads(row["text"]), row["expected"]
        assert set(out) == {"operation", "updates"} and out["operation"] in {"update", "new", "clarify"}
        assert (out["operation"] == "update") == bool(out["updates"])
        allowed = CALENDAR_FIELDS if inp["capability"] == "calendar.create" else ["query"]
        assert set(inp["fields"]) == set(allowed)
        seen = set()
        for update in out["updates"]:
            assert set(update) == {"field", "text", "value"}
            field, span, value = (update[key] for key in ("field", "text", "value"))
            assert field in allowed and field not in seen and span.strip() and value.strip()
            seen.add(field)
            assert inp["current"].count(span) == 1, (row["id"], span)
            if field == "date":
                assert date.fromisoformat(value) >= date.fromisoformat(inp["date"])
            elif field in {"start", "end"}:
                assert re.fullmatch(r"\d{2}:\d{2}", value) and time.fromisoformat(value)
            elif field == "duration":
                assert value.isdecimal() and int(value) > 0
            else:
                assert value == span.strip()
    elif kind == "intent":
        inp, out = json.loads(row["text"]), row["expected"]
        assert set(out) == {"mode", "action", "target", "scope", "source_id"}
        assert out["mode"] in {"navigate", "act", "search"} and out["action"] in row["instruction"]
        sources = {"request": row["instruction"], **{s["id"]: s["text"] for s in inp["sources"]}}
        assert out["source_id"] in sources
        if out["target"]:
            assert sources[out["source_id"]].count(out["target"]) == 1
        if out["scope"]:
            assert row["instruction"].count(out["scope"]) == 1
    elif kind == "goal":
        out = row["expected"]
        assert set(out) == {"capabilities", "fields", "missing"}
        assert set(out["capabilities"]) <= GOAL_CAPABILITIES and len(set(out["capabilities"])) == len(out["capabilities"])
        assert set(out["fields"]) <= GOAL_FIELDS and set(out["missing"]) <= GOAL_FIELDS
        assert not set(out["missing"]) & set(out["fields"])
        assert all(isinstance(value, str) and value and (value in row["instruction"] or value in row["text"]) for value in out["fields"].values())
    else:
        assert kind in {"message", "rewrite", "email", "title", "query", "summary"}
        assert row["metadata"]["review_required"]
        assert all(token in row["text"] or token in row["instruction"] for token in row["checks"]["required"])
    assert len(row["instruction"]) + len(row["text"]) <= 2800
    fingerprint(row)


def eval_inputs():
    path = ROOT / "tests/slm-regression.json"
    payload = json.loads(path.read_text())
    rows = payload["cases"]
    assert all(row["split"] == "evaluation" for row in rows)
    fingerprints = {fingerprint(row) for row in rows}
    # Only collision fingerprints are used; evaluation values never seed generation.
    return fingerprints, {"path": str(path.relative_to(ROOT)), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "rows": len(rows)}


def candidates(count, split, seed):
    rng = random.Random(seed + (0 if split == "train" else 1000003))
    return [sample(index, split, rng) for index in range(count)]


def generate(output, count=1440, seed=20261021, validation_fraction=1 / 6):
    if count < 12 or not 0 < validation_fraction < 1:
        raise ValueError("Use at least 12 rows and a validation fraction between zero and one")
    validation_count = max(1, int(round(count * validation_fraction)))
    if validation_count >= count:
        raise ValueError("Both train and validation need rows")
    evaluation, evaluation_info = eval_inputs()
    sets = {"train": candidates(count - validation_count, "train", seed),
            "validation": candidates(validation_count, "validation", seed)}
    seen, templates = {}, {}
    for split, rows in sets.items():
        for row in rows:
            validate_gold(row)
        seen[split] = {fingerprint(row) for row in rows}
        templates[split] = {row["metadata"]["template_family"] for row in rows}
        if len(seen[split]) != len(rows) or seen[split] & evaluation:
            raise ValueError("Duplicate or frozen evaluation prompt overlap in " + split)
    if seen["train"] & seen["validation"] or templates["train"] & templates["validation"]:
        raise ValueError("Training and validation overlap")
    files = {split: "".join(encoded(row) + "\n" for row in rows) for split, rows in sets.items()}
    manifest = {"version": 1, "generator": "training/slm/make_data.py", "seed": seed,
                "prompt_version": PROMPT_VERSION, "rows": count, "validation_fraction": validation_fraction,
                "counts": {split: len(rows) for split, rows in sets.items()},
                "files": {split: {"path": split + ".jsonl", "sha256": digest(files[split]), "rows": len(rows)} for split, rows in sets.items()},
                "evaluation": evaluation_info,
                "families": {split: dict(Counter(row["family"] for row in rows)) for split, rows in sets.items()},
                "template_families": {split: sorted(values) for split, values in templates.items()},
                "checks": {"train_validation_prompt_overlap": 0, "evaluation_prompt_overlap": 0, "structured_gold_span_errors": 0},
                "policy": "Teacher candidates only. Accept structured targets only when strict schema/span checks and independent gold agree. Writing lexical checks leave semantic review pending. Train on train only; select on validation only. Frozen evaluation must not tune prompts, checkpoints or data.",
                "limitations": "Original English template data, not realistic app observations. Split-specific templates and value pools reduce simple leakage but do not prove semantic independence or generalization. No navigation/action success is labeled.",
                "prefix_stability": "For a fixed seed each split is generated as an independent stream. Smaller --rows picks a prefix of the same train/validation streams; it does not create new data from evaluation feedback."}
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    paths = [output / (split + ".jsonl") for split in sets] + [output / "manifest.json"]
    if any(path.exists() for path in paths):
        raise FileExistsError("Choose a new output directory; existing candidate files are not overwritten")
    for split, content in files.items():
        temporary = output / (split + ".jsonl.tmp")
        temporary.write_text(content)
        temporary.replace(output / (split + ".jsonl"))
    temporary = output / "manifest.json.tmp"
    temporary.write_text(json.dumps(manifest, indent=2) + "\n")
    temporary.replace(output / "manifest.json")
    return manifest


def self_check():
    from regression import evaluate

    for key in ("names", "places", "adjectives", "nouns", "sections", "amounts"):
        assert not set(SPLITS["train"][key]) & set(SPLITS["validation"][key])
    gold_rows = 0
    for split, count in (("train", 1200), ("validation", 240)):
        for row in candidates(count, split, 20261021):
            validate_gold(row)
            if "expected" in row:
                score = evaluate(row, encoded(row["expected"]))
                assert score["mechanical_pass"], (row["id"], score["check_errors"])
                gold_rows += 1
    with tempfile.TemporaryDirectory(prefix="friday-slm-candidates-") as temp:
        root = Path(temp)
        first = generate(root / "one", 144, 713)
        repeated = generate(root / "two", 144, 713)
        smaller = generate(root / "small", 72, 713)
        assert first == repeated
        for split in ("train", "validation"):
            left = (root / "one" / (split + ".jsonl")).read_bytes()
            right = (root / "two" / (split + ".jsonl")).read_bytes()
            prefix = (root / "small" / (split + ".jsonl")).read_bytes()
            assert left == right and left.startswith(prefix)
            assert hashlib.sha256(left).hexdigest() == first["files"][split]["sha256"]
        assert first["checks"] == {"train_validation_prompt_overlap": 0, "evaluation_prompt_overlap": 0, "structured_gold_span_errors": 0}
        assert first["counts"] == {"train": 120, "validation": 24}
        broken = candidates(1, "train", 1)[0]
        broken["expected"]["updates"][0]["text"] = "fabricated owner span"
        try:
            validate_gold(broken)
        except AssertionError:
            pass
        else:
            raise AssertionError("A forged source span was accepted")
    print("Synthetic data self-check passed: %d structured golds through the shared grader; deterministic prefixes, disjoint prompts/templates, exact spans, typed gold, manifests" % gold_rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-check", action="store_true")
    parser.add_argument("--output-dir", type=Path, default=Path(__file__).resolve().parent / "out/data")
    parser.add_argument("--rows", type=int, default=1440)
    parser.add_argument("--seed", type=int, default=20261021)
    parser.add_argument("--validation-fraction", type=float, default=1 / 6)
    args = parser.parse_args()
    if args.self_check:
        self_check()
    else:
        manifest = generate(args.output_dir, args.rows, args.seed, args.validation_fraction)
        print(json.dumps({"directory": str(args.output_dir), "counts": manifest["counts"], "checks": manifest["checks"]}))


if __name__ == "__main__":
    main()
