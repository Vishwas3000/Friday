#!/usr/bin/env python3
"""Monitor one fixed prepared-SFT baseline in Optuna; no search or trainer control."""
import argparse
import fcntl
import hashlib
import json
import math
import re
import signal
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path

FIXED = {"learning_rate": 2e-5, "batch_size": 4, "accumulate": 4,
         "max_batch_tokens": 6000, "max_sequence": 2048, "writing_share": 0.02}
SCOPE = "One fixed baseline trial for future comparison; no parameter search, pruning or training control"


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def read(path):
    raw = path.read_bytes()
    value = json.loads(raw)
    if not isinstance(value, dict):
        raise ValueError("Expected JSON object: " + str(path))
    return value, hashlib.sha256(raw).hexdigest()


def number(value):
    if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
        raise ValueError("Expected finite nonnegative validation loss")
    return float(value)


def identity(report, expected):
    config = report["configuration"]
    actual = hashlib.sha256(canonical(config).encode()).hexdigest()
    if report.get("config_sha256") != expected or actual != expected:
        raise ValueError("Training configuration changed; require the post-migration SHA256")
    parameters = config["parameters"]
    if any(type(parameters.get(k)) is not type(v) or parameters[k] != v for k, v in FIXED.items()):
        raise ValueError("This watcher only tracks the declared fixed baseline parameters")
    if type(parameters.get("seed")) is not int or not isinstance(report.get("precision"), str):
        raise ValueError("Missing seed or precision metadata")
    source = config["data"]
    for key in ("state_manifest_sha256", "state_source_manifest_sha256", "writing_manifest_sha256", "writing_review_sha256"):
        if not isinstance(source.get(key), str) or not re.fullmatch("[0-9a-f]{64}", source[key]):
            raise ValueError("Missing source hash: " + key)
    return {"configuration_sha256": expected, "source_identity": source,
            "seed": parameters["seed"], "precision": report["precision"]}


def validations(folder):
    for path in sorted(folder.glob("step-*.json")):
        match = re.fullmatch(r"step-(\d+)\.json", path.name)
        if match is None:
            continue
        value, sha = read(path)
        if type(value.get("step")) is not int or value["step"] != int(match[1]):
            raise ValueError("Validation filename/step mismatch: " + str(path))
        yield value["step"], number(value["loss"]), sha


def terminal(report):
    if report["status"] == "failed":
        return "FAIL", None
    if report["status"] == "complete":
        if type(report.get("optimizer_steps")) is not int or report["optimizer_steps"] != report.get("total_steps"):
            raise ValueError("Training says complete before all optimizer steps")
        return "COMPLETE", number(report["best_validation_loss"])
    return None, None  # stopped/awaiting_resume are resumable, not completed trials.


def atomic(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n")
    temporary.replace(path)


def watch(args):
    report, _ = read(args.run / "training.json")
    bound = identity(report, args.config_sha256)
    folder = args.run / "optuna"
    folder.mkdir(exist_ok=True)
    with (folder / "monitor.lock").open("a") as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        import optuna  # Lazy: --self-check needs only the standard library.
        if optuna.__version__ != "4.5.0":
            raise ValueError("Use the existing optuna==4.5.0 environment")
        study = optuna.create_study(study_name="prepared-fixed-baseline", direction="minimize",
            storage="sqlite:///" + str((folder / "study.sqlite3").resolve()), load_if_exists=True,
            sampler=optuna.samplers.RandomSampler(seed=bound["seed"]), pruner=optuna.pruners.NopPruner())
        existing = study.get_trials(deepcopy=False)
        if len(existing) > 1 or (study.user_attrs.get("identity") != bound and (existing or study.user_attrs)):
            raise ValueError("Study already binds a different configuration or multiple trials")
        study.set_user_attr("identity", bound)
        study.set_user_attr("scope", SCOPE)
        frozen = existing[0] if existing else None
        if frozen and frozen.state.is_finished():
            state, objective = terminal(report)
            if state != frozen.state.name or objective != frozen.value:
                raise ValueError("Finished trial and current training status disagree")
            atomic(folder / "latest.json", {**bound, "scope": SCOPE, "monitor_status": "monitor_complete",
                "training_status": report["status"], "trial_number": frozen.number, "trial_state": state, "objective": objective})
            return
        trial = optuna.trial.Trial(study, frozen._trial_id) if frozen else study.ask()
        for key, value in FIXED.items():
            (trial.suggest_int if type(value) is int else trial.suggest_float)(key, value, value)
        for key, value in bound.items():
            trial.set_user_attr(key, value)
        trial.set_user_attr("scope", SCOPE)
        values = dict(frozen.intermediate_values) if frozen else {}
        stop = {"requested": False}
        for kind in (signal.SIGTERM, signal.SIGINT):
            signal.signal(kind, lambda number, frame: stop.update(requested=True))
        state, objective = "RUNNING", None

        def save(status, error=None):
            step = max(values, default=None)
            atomic(folder / "latest.json", {**bound, "scope": SCOPE, "updated_utc": datetime.now(timezone.utc).isoformat(),
                "monitor_status": status, "training_status": report["status"], "trial_number": trial.number,
                "trial_state": state, "fixed_parameters": FIXED, "reported_steps": sorted(values),
                "latest_step": step, "latest_loss": values.get(step), "objective": objective, "error": error})

        try:
            while not stop["requested"]:
                report, _ = read(args.run / "training.json")
                if identity(report, args.config_sha256) != bound:
                    raise ValueError("Training metadata changed during monitoring")
                for step, loss, sha in validations(args.run / "validation"):
                    key = "validation_sha256_" + str(step)
                    prior = trial.user_attrs.get(key)
                    if (prior is not None and prior != sha) or (step in values and values[step] != loss):
                        raise ValueError("Previously reported validation changed")
                    if step not in values:
                        trial.set_user_attr(key, sha)
                        trial.report(loss, step)
                        values[step] = loss
                ending, objective = terminal(report)
                if ending:
                    if ending == "FAIL":
                        trial.set_user_attr("training_failure_reason", str(report.get("error", "Training failed without a reason")))
                        study.tell(trial, state=optuna.trial.TrialState.FAIL)
                    else:
                        study.tell(trial, objective)
                    state = ending
                    save("monitor_complete")
                    return
                save("monitor_running")
                time.sleep(args.poll_seconds)
            save("monitor_paused")  # The trial stays RUNNING; training is untouched.
        except Exception as error:
            save("monitor_error", type(error).__name__ + ": " + str(error))
            raise


def self_check():
    config = {"parameters": {**FIXED, "seed": 42}, "data": {key: "a" * 64 for key in
        ("state_manifest_sha256", "state_source_manifest_sha256", "writing_manifest_sha256", "writing_review_sha256")}}
    sha = hashlib.sha256(canonical(config).encode()).hexdigest()
    report = {"configuration": config, "config_sha256": sha, "precision": "FP32/BF16", "status": "awaiting_resume"}
    assert identity(report, sha)["seed"] == 42 and terminal(report) == (None, None)
    assert terminal({"status": "stopped"}) == (None, None) and terminal({"status": "failed"}) == ("FAIL", None)
    assert terminal({"status": "complete", "optimizer_steps": 3, "total_steps": 3, "best_validation_loss": 0.5}) == ("COMPLETE", 0.5)
    for action in (lambda: identity(report, "b" * 64), lambda: number(True), lambda: number(float("nan")),
                   lambda: terminal({"status": "complete", "optimizer_steps": 1, "total_steps": 3})):
        try:
            action()
        except ValueError:
            pass
        else:
            raise AssertionError("Unsafe metadata accepted")
    with tempfile.TemporaryDirectory() as temporary:
        folder = Path(temporary)
        atomic(folder / "step-00000003.json", {"step": 3, "loss": 0.75})
        (folder / "step-00000004.json.tmp").write_text("partial")
        assert [(step, loss) for step, loss, _ in validations(folder)] == [(3, 0.75)]
        atomic(folder / "step-00000004.json", {"step": 5, "loss": 0.5})
        try:
            list(validations(folder))
        except ValueError:
            pass
        else:
            raise AssertionError("Mismatched validation accepted")
    print("Watcher self-check passed: metadata pin, finite losses, atomic files, paused/failed/complete distinctions")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-check", action="store_true")
    parser.add_argument("--run", type=Path)
    parser.add_argument("--config-sha256")
    parser.add_argument("--poll-seconds", type=int, choices=(5, 10), default=10)
    args = parser.parse_args()
    if args.self_check:
        self_check()
        return
    if args.run is None or not args.config_sha256 or not re.fullmatch("[0-9a-f]{64}", args.config_sha256):
        parser.error("--run and the post-migration --config-sha256 are required")
    watch(args)


if __name__ == "__main__":
    main()
