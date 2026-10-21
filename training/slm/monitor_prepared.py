#!/usr/bin/env python3
"""Refresh local training graphs and health checks without controlling training."""
import argparse
import fcntl
import json
import math
import shutil
import signal
import subprocess
import tempfile
import threading
import time
import zipfile
from datetime import datetime, timezone
from pathlib import Path

from watch_prepared import atomic, canonical, identity, number, read, validations

FIELDS = ("step", "total_steps", "rows", "total_rows", "response_tokens", "eta_s",
          "learning_rate", "gradient_norm", "response_tokens_per_s_with_overhead",
          "response_tokens_per_active_train_s", "loop_wall_s")
REPORTS = ("frozen-main.json", "frozen-domains.json", "final-prepared-validation.json")


def position(report):
    return report.get("progress", {}).get("step", report.get("last_progress", {}).get("step", 0))


def latest_progress(report):
    recorded = report.get("last_progress", {})
    cursor = position(report)
    latest = {key: value for key, value in recorded.items() if key in FIELDS} if recorded.get("step") == cursor else {}
    latest.update(step=cursor, total_steps=report["total_steps"])
    if report["status"] == "complete":
        latest["eta_s"] = 0
    return latest


def progress_history(lines, report):
    parameters = report["configuration"]["parameters"]
    current = position(report)
    history = {}
    for line in lines:
        try:
            event = json.loads(line)
        except (ValueError, TypeError):
            continue  # Supervisor also records warnings and tracebacks.
        # Earlier OOM attempts share this log but predate token-budget batching.
        if (not isinstance(event, dict) or event.get("stage") != "train"
                or event.get("max_batch_tokens") != parameters["max_batch_tokens"]
                or event.get("total_steps") != report["total_steps"]):
            continue
        step = event.get("step")
        if type(step) is not int or not 0 < step <= current:
            continue
        for key in FIELDS:
            if key in event and event[key] is not None:
                number(event[key])
        # A resumed attempt replaces its discarded suffix, never averages it in.
        for discarded in [previous for previous in history if previous >= step]:
            del history[discarded]
        history[step] = {key: event[key] for key in FIELDS if key in event}
    return [history[step] for step in sorted(history)]


def regression_complete(data, source, best, metadata_sha, code):
    outputs, cases = data.get("outputs", []), data.get("cases", [])
    model, corpora = data.get("model", {}), data.get("corpora", [])
    return (data.get("all_cases_attempted") is True and bool(data.get("completed_utc"))
            and "error" not in data and len(cases) == source["case_count"]
            and len(outputs) == len(cases) and len({row["id"] for row in cases}) == len(cases)
            and [row.get("id") for row in outputs] == [row["id"] for row in cases]
            and all(row.get("status") == "generated" and "error" not in row for row in outputs)
            and len(corpora) == 1 and corpora[0].get("sha256") == source["sha256"]
            and corpora[0].get("case_count") == source["case_count"]
            and model.get("local_checkpoint") is True and Path(model.get("source", "")).resolve() == best
            and model.get("local_files", {}).get("friday_prepared_sft.json", {}).get("sha256") == metadata_sha
            and data.get("prompt_source_sha256") == code["prompts.py"]
            and data.get("grader_source_sha256") == code["regression.py"])


def evaluation_status(run, report):
    result = dict.fromkeys(REPORTS, False)
    if report["status"] != "complete":
        return result
    best = (run / "best").resolve()
    if Path(report["selected_checkpoint"]).resolve() != best:
        raise ValueError("Selected checkpoint is not this run's best directory")
    metadata, metadata_sha = read(best / "friday_prepared_sft.json")
    if metadata["configuration"] != report["configuration"] or metadata["weight_sha256"] != report["weight_sha256"]:
        raise ValueError("Final selected-model metadata differs from training")
    sources = {Path(item["path"]).name: item for item in report["configuration"]["data"]["frozen_sources"]}
    for output, fixture in zip(REPORTS[:2], ("slm-regression.json", "slm-domain-regression.json")):
        if (run / output).exists():
            data, _ = read(run / output)
            result[output] = regression_complete(data, sources[fixture], best, metadata_sha,
                                                report["configuration"]["data"]["code_hashes"])
    if (run / REPORTS[2]).exists():
        data, _ = read(run / REPORTS[2])
        rows, outputs = data.get("rows"), data.get("outputs", [])
        result[REPORTS[2]] = (type(rows) is int and 0 < rows <= 96 and len(outputs) == rows
            and len({row.get("id") for row in outputs}) == rows and "error" not in data
            and data.get("selection_eligible") is False
            and Path(data.get("selected_checkpoint", "")).resolve() == best
            and all("error" not in row for row in outputs)
            and sum(item.get("rows", 0) for item in data.get("families", {}).values()) == rows)
    return result


def health(status, age, validation, optuna, free_bytes, job, evaluation):
    messages, level = [], "ok"
    if status == "failed":
        level = "error"
        messages.append("Training failed; inspect its saved error and checkpoint before recovery.")
    elif status == "stopped":
        level = "warning"
        messages.append("Training is stopped; the monitor does not resume or alter it.")
    elif status == "running" and age > 300:
        level = "warning"
        messages.append("No training report update for more than five minutes.")
    if len(validation) > 1 and validation[-1]["loss"] > validation[-2]["loss"] * 1.25:
        level = "warning" if level == "ok" else level
        messages.append("Latest weighted validation loss rose over 25%; review subsequent checks.")
    if free_bytes < 6 * 1024 ** 3:
        level = "warning" if level == "ok" else level
        messages.append("Less than 6 GiB disk space remains; recovery saves need temporary space.")
    if optuna is None or optuna.get("monitor_status") in {"monitor_error", "monitor_paused"}:
        level = "warning" if level == "ok" else level
        messages.append("Optuna observer is unavailable or paused.")
    elif status == "running" and optuna.get("age_s", 0) > 120:
        level = "warning" if level == "ok" else level
        messages.append("Optuna has not refreshed its status for over two minutes.")
    if job == "UNKNOWN":
        level = "warning" if level == "ok" else level
        messages.append("The managed job's status could not be read.")
    if job in {"EXITED", "FATAL", "STOPPED", "BACKOFF"} and (status != "complete" or not all(evaluation.values())):
        level = "error"
        messages.append("The managed training/evaluation job is not running and reports remain incomplete.")
    return {"level": level, "messages": messages}


def gpu_status():
    try:
        output = subprocess.run(["nvidia-smi", "--query-gpu=utilization.gpu,memory.used,memory.total",
                                 "--format=csv,noheader,nounits"], capture_output=True, text=True, timeout=10, check=True)
        values = [number(float(item.strip())) for item in output.stdout.splitlines()[0].split(",")]
        return dict(zip(("utilization_pct", "memory_used_mib", "memory_total_mib"), values))
    except (OSError, subprocess.SubprocessError, ValueError, IndexError):
        return None


def collect(args):
    run = args.run
    report, _ = read(run / "training.json")
    identity(report, args.config_sha256)
    points = [{"step": 0, "loss": number(report["initial_validation"]["loss"]),
               "branch_loss": report["initial_validation"]["branch_loss"]}]
    for step, loss, _ in validations(run / "validation"):
        if step <= report["optimizer_steps"]:
            detail, _ = read(run / "validation" / ("step-%08d.json" % step))
            points.append({"step": step, "loss": loss, "branch_loss": detail["branch_loss"]})
    for point in points:
        branches = point["branch_loss"]
        expected = sum(number(branches[name]) * share for name, share in
                       (("state", 1 - report["configuration"]["parameters"]["writing_share"]),
                        ("writing", report["configuration"]["parameters"]["writing_share"])))
        if not math.isclose(point["loss"], expected, rel_tol=1e-9, abs_tol=1e-12):
            raise ValueError("Validation loss does not match configured branch shares")
    history = progress_history(args.log.read_text().splitlines(), report)
    latest = latest_progress(report)
    optuna = None
    if (run / "optuna/latest.json").exists():
        raw, _ = read(run / "optuna/latest.json")
        if raw.get("configuration_sha256") != args.config_sha256:
            raise ValueError("Optuna belongs to another training configuration")
        optuna = {key: raw.get(key) for key in ("monitor_status", "trial_state", "latest_step", "latest_loss", "updated_utc")}
        optuna["age_s"] = max(0, time.time() - (run / "optuna/latest.json").stat().st_mtime)
    job = None
    if args.program:
        process = subprocess.run(["supervisorctl", "status", args.program], capture_output=True, text=True, timeout=10)
        fields = process.stdout.split()
        job = fields[1] if len(fields) > 1 and fields[0] == args.program else "UNKNOWN"
    evaluation = evaluation_status(run, report)
    free = shutil.disk_usage(run).free
    age = max(0, time.time() - (run / "training.json").stat().st_mtime)
    state = health(report["status"], age, points, optuna, free, job, evaluation)
    gpu = gpu_status()
    if gpu is None:
        state["level"] = "warning" if state["level"] == "ok" else state["level"]
        state["messages"].append("GPU utilization and memory could not be read.")
    if report["status"] == "failed":
        monitor_status = "failed"
    elif report["status"] == "complete" and all(evaluation.values()) and optuna and optuna["trial_state"] == "COMPLETE":
        monitor_status = "complete"
    else:
        monitor_status = "running"
    return {"captured_utc": datetime.now(timezone.utc).isoformat(), "config_sha256": args.config_sha256,
            "status": report["status"], "monitor_status": monitor_status, "health": state,
            "progress": latest, "saved_step": report["optimizer_steps"], "validation": points,
            "history": history, "gpu": gpu, "optuna": optuna,
            "evaluation": evaluation, "managed_job": job, "disk_free_gib": free / 1024 ** 3,
            "training_report_age_s": age, "training_error": report.get("error")}


def publish(out, snapshot):
    from plot_prepared import render
    atomic(out / "metrics.json", snapshot)
    atomic(out / "health.json", {"captured_utc": snapshot["captured_utc"], "monitor_status": snapshot["monitor_status"],
                               **snapshot["health"]})
    render(snapshot, out)
    temporary = out / "live.tmp.zip"
    with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name in ("metrics.json", "health.json", "overview.html", "overview.png", "overview.svg", "alerts.jsonl"):
            if (out / name).is_file():
                archive.write(out / name, name)
    temporary.replace(out / "live.zip")


def monitor(args):
    out = args.run / "monitor"
    out.mkdir(exist_ok=True)
    stop = threading.Event()
    for kind in (signal.SIGTERM, signal.SIGINT):
        signal.signal(kind, lambda number, frame: stop.set())
    with (out / "monitor.lock").open("a") as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        previous = None
        while not stop.is_set():
            try:
                snapshot = collect(args)
                signature = canonical(snapshot["health"])
                if signature != previous:
                    with (out / "alerts.jsonl").open("a") as stream:
                        stream.write(canonical({"captured_utc": snapshot["captured_utc"], **snapshot["health"]}) + "\n")
                    previous = signature
                publish(out, snapshot)
                print(canonical({"stage": "monitor", "step": snapshot["progress"]["step"],
                                 "monitor_status": snapshot["monitor_status"], **snapshot["health"]}), flush=True)
                if args.once or snapshot["monitor_status"] in {"complete", "failed"}:
                    return
            except Exception as error:
                atomic(out / "health.json", {"captured_utc": datetime.now(timezone.utc).isoformat(),
                       "monitor_status": "error", "level": "error", "messages": [type(error).__name__ + ": " + str(error)]})
                raise
            stop.wait(args.interval)


def self_check():
    report = {"configuration": {"parameters": {"max_batch_tokens": 6000}}, "total_steps": 100,
              "status": "running", "last_progress": {"step": 30, "gradient_norm": 7}}
    def event(step, **extra):
        return canonical({"stage": "train", "max_batch_tokens": 6000, "total_steps": 100, "step": step, **extra})
    lines = ["warning", event(10, max_batch_tokens=8000), event(10), event(20), event(30), event(20), event(25), event(40)]
    assert [row["step"] for row in progress_history(lines, report)] == [10, 20, 25]
    rollback = {**report, "progress": {"step": 2}}
    assert progress_history(lines, rollback) == []
    assert latest_progress(rollback) == {"step": 2, "total_steps": 100}
    advanced = {**report, "progress": {"step": 40}}
    assert latest_progress(advanced) == {"step": 40, "total_steps": 100}
    assert latest_progress(report)["gradient_norm"] == 7
    source = {"sha256": "a", "case_count": 1}
    code = {"prompts.py": "p", "regression.py": "g"}
    best = Path("/test/best")
    case = {"all_cases_attempted": True, "completed_utc": "done", "cases": [{"id": "x"}],
        "outputs": [{"id": "x", "status": "generated", "mechanical_pass": False}],
        "corpora": [source], "model": {"source": str(best), "local_checkpoint": True,
            "local_files": {"friday_prepared_sft.json": {"sha256": "m"}}},
        "prompt_source_sha256": "p", "grader_source_sha256": "g"}
    assert regression_complete(case, source, best, "m", code)
    assert not regression_complete({**case, "all_cases_attempted": False}, source, best, "m", code)
    assert not regression_complete({**case, "outputs": [{"id": "x", "status": "error"}]}, source, best, "m", code)
    assert not regression_complete(case, source, best, "wrong-model", code)
    points = [{"loss": 1.0}, {"loss": 0.5}]
    okay = {"monitor_status": "monitor_running"}
    reports = {name: False for name in REPORTS}
    assert health("running", 0, points, okay, 10 * 1024 ** 3, "RUNNING", reports)["level"] == "ok"
    assert health("running", 301, points, okay, 10 * 1024 ** 3, "RUNNING", reports)["level"] == "warning"
    assert health("running", 0, points, okay, 10 * 1024 ** 3, "EXITED", reports)["level"] == "error"
    assert health("failed", 0, points, okay, 10 * 1024 ** 3, "EXITED", reports)["level"] == "error"
    assert health("running", 0, points, {**okay, "age_s": 121}, 10 * 1024 ** 3, "RUNNING", reports)["level"] == "warning"
    assert health("running", 0, points, okay, 10 * 1024 ** 3, "UNKNOWN", reports)["level"] == "warning"
    assert health("running", 0, [{"loss": 1}, {"loss": 1.3}], okay, 10 * 1024 ** 3, "RUNNING", reports)["level"] == "warning"
    with tempfile.TemporaryDirectory() as folder:
        atomic(Path(folder) / "health.json", {"level": "ok"})
        assert read(Path(folder) / "health.json")[0] == {"level": "ok"}
    print("Monitor self-check passed: mixed logs, rollback, stale/failed/exited jobs, loss rise and atomic status")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-check", action="store_true")
    parser.add_argument("--run", type=Path)
    parser.add_argument("--log", type=Path)
    parser.add_argument("--config-sha256")
    parser.add_argument("--program")
    parser.add_argument("--interval", type=int, default=60)
    parser.add_argument("--once", action="store_true")
    args = parser.parse_args()
    if args.self_check:
        self_check()
    elif args.run is None or args.log is None or not args.config_sha256 or args.interval < 10:
        parser.error("--run, --log, --config-sha256 and interval >=10 are required")
    else:
        monitor(args)


if __name__ == "__main__":
    main()
