#!/usr/bin/env python3
"""Render an offline training snapshot; Matplotlib is imported only by render()."""
import argparse
import html
import io
import json
import math
import os
import re
import tempfile
from datetime import datetime
from pathlib import Path

ACTIVE = {"running", "training", "preparing", "tokenizing", "loading_model", "awaiting_resume"}
FIELDS = ("step", "total_steps", "rows", "total_rows", "eta_s", "learning_rate", "gradient_norm",
          "response_tokens_per_s_with_overhead")


def numeric(value, label, required=False):
    if value is None and not required:
        return None
    if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
        raise ValueError("Expected finite nonnegative number: " + label)
    return value


def validate(snapshot):
    if not isinstance(snapshot, dict):
        raise ValueError("Snapshot must be an object")
    for name in ("captured_utc", "status"):
        if not isinstance(snapshot.get(name), str) or not snapshot[name]:
            raise ValueError("Missing snapshot text: " + name)
    try:
        captured = datetime.fromisoformat(snapshot["captured_utc"].replace("Z", "+00:00"))
        if captured.utcoffset() is None:
            raise ValueError("Timestamp needs a UTC offset")
    except ValueError as error:
        raise ValueError("captured_utc must be an ISO timestamp with timezone") from error
    config = snapshot.get("config_sha256")
    if config is not None and not (isinstance(config, str) and re.fullmatch(r"[0-9a-f]{64}", config)):
        raise ValueError("Invalid configuration SHA256")
    health, progress = snapshot.get("health"), snapshot.get("progress")
    if not isinstance(health, dict) or health.get("level") not in {"ok", "warning", "error"}:
        raise ValueError("Invalid health level")
    if not isinstance(health.get("messages"), list) or not all(isinstance(x, str) for x in health["messages"]):
        raise ValueError("Health messages must be strings")
    if not isinstance(progress, dict):
        raise ValueError("Progress must be an object")
    history, validation = snapshot.get("history", []), snapshot.get("validation", [])
    if not isinstance(history, list) or not isinstance(validation, list):
        raise ValueError("History and validation must be arrays")
    for row in [progress] + history:
        if not isinstance(row, dict):
            raise ValueError("Progress event must be an object")
        for field in FIELDS:
            numeric(row.get(field), field)
        if row.get("total_steps") is not None and row.get("step") is not None and row["step"] > row["total_steps"]:
            raise ValueError("Completed steps exceed planned steps")
    for row in validation:
        if not isinstance(row, dict) or not isinstance(row.get("branch_loss", {}), dict):
            raise ValueError("Invalid validation event")
        numeric(row.get("step"), "validation step", True)
        numeric(row.get("loss"), "validation loss", True)
        for branch in ("state", "writing"):
            numeric(row.get("branch_loss", {}).get(branch), branch + " loss")
    gpu = snapshot.get("gpu")
    if gpu is not None:
        if not isinstance(gpu, dict):
            raise ValueError("GPU state must be an object")
        for key in ("utilization_pct", "memory_used_mib", "memory_total_mib"):
            numeric(gpu.get(key), key)
        if gpu.get("utilization_pct") is not None and gpu["utilization_pct"] > 100:
            raise ValueError("GPU utilization exceeds 100%")
        if (gpu.get("memory_used_mib") is not None and gpu.get("memory_total_mib") is not None
                and gpu["memory_used_mib"] > gpu["memory_total_mib"]):
            raise ValueError("GPU memory used exceeds total")
    optuna = snapshot.get("optuna")
    if optuna is not None:
        if not isinstance(optuna, dict):
            raise ValueError("Optuna state must be an object")
        numeric(optuna.get("latest_step"), "Optuna step")
        for key in ("monitor_status", "trial_state"):
            if optuna.get(key) is not None and not isinstance(optuna[key], str):
                raise ValueError("Invalid Optuna text")
    return snapshot


def duration(seconds):
    if seconds is None:
        return "unknown"
    minutes = int(math.ceil(seconds / 60))
    return f"{minutes // 60}h {minutes % 60:02d}m" if minutes >= 60 else f"{minutes}m"


def summary(snapshot):
    progress = snapshot["progress"]
    step, total = progress.get("step"), progress.get("total_steps")
    percent = 100 * step / total if step is not None and total else None
    return {"percent": percent, "percent_text": f"{percent:.1f}%" if percent is not None else "unknown",
            "eta": duration(progress.get("eta_s")), "status": snapshot["status"]}


def page(snapshot, svg):
    data, escape = summary(snapshot), html.escape
    health = snapshot["health"]
    messages = "".join("<li>" + escape(item) + "</li>" for item in health["messages"])
    gpu, optuna = snapshot.get("gpu") or {}, snapshot.get("optuna") or {}
    def shown(value):
        return "unknown" if value is None else str(value)
    device = f"GPU {shown(gpu.get('utilization_pct'))}% · memory {shown(gpu.get('memory_used_mib'))}/{shown(gpu.get('memory_total_mib'))} MiB"
    observer = f"Optuna: {shown(optuna.get('trial_state'))} · {shown(optuna.get('monitor_status'))} · last validation step {shown(optuna.get('latest_step'))}"
    evaluation = snapshot.get("evaluation")
    reports = (f"Post-training reports: {sum(value is True for value in evaluation.values())}/{len(evaluation)} complete"
               if isinstance(evaluation, dict) else "")
    captured = json.dumps(snapshot["captured_utc"]).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
    active = "true" if snapshot.get("monitor_status") == "running" or snapshot["status"] in ACTIVE else "false"
    return f"""<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="refresh" content="60"><title>Friday training progress</title>
<style>body{{margin:24px auto;padding:0 18px;max-width:1400px;font:16px system-ui;color:#172536;background:#f5f7fb}}
main{{background:white;padding:20px;border-radius:12px}}h1{{margin:0 0 8px;font-size:25px}}p{{line-height:1.5}}
svg{{width:100%;height:auto}}.muted{{color:#526174;font-size:14px}}.health{{padding:12px;border-left:5px solid #3e8765;background:#f0f7f3}}
.warning{{border-color:#bb7b19;background:#fff8e8}}.error{{border-color:#b54242;background:#fff0f0}}
#stale{{padding:14px;background:#fff0cf;border:2px solid #b77a11;font-weight:700}}[hidden]{{display:none!important}}
code{{overflow-wrap:anywhere;font-size:12px}}</style><main>
<h1>Friday · epoch {escape(data['percent_text'])} · {escape(data['status'])} · ETA {escape(data['eta'])}</h1>
<p class="muted">Captured {escape(snapshot['captured_utc'])} · refresh every 60 seconds</p>
<p id="stale" hidden>Data is stale — monitoring/sync needs attention</p>
<div class="health {escape(health['level'])}"><strong>Health: {escape(health['level'])}</strong><ul>{messages}</ul></div>
<p class="muted">{escape(device)}<br>{escape(observer)}</p>
<p class="muted">{escape(reports)}</p>
{svg}
<p>Validation loss measures fit to held-out reference responses. Separate state and writing curves reveal changes hidden by the weighted objective. Learning rate and speed use recorded observations.</p>
<p>Gradient norms are measured before clipping; the dashed line marks the clipping threshold of 1. A value above 1 alone does not indicate failure. Epoch percent uses completed optimizer steps divided by planned steps.</p>
<p class="muted">Missing observations remain blank. ETA uses the trainer's current estimate. Configuration <code>{escape(snapshot.get('config_sha256') or 'unknown')}</code></p>
</main><script>const captured=Date.parse({captured}),active={active};
function stale(){{document.getElementById('stale').hidden=!(active&&(!Number.isFinite(captured)||Date.now()-captured>180000));}}
stale();setInterval(stale,10000);</script></html>"""


def atomic(path, data):
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".overview-", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        temporary.replace(path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def render(snapshot, out):
    validate(snapshot)
    import matplotlib
    matplotlib.use("Agg", force=True)
    import matplotlib.pyplot as plt
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    data = summary(snapshot)
    events = {}
    for row in snapshot.get("history", []) + [snapshot["progress"]]:
        if row.get("step") is not None:
            events.setdefault(row["step"], {}).update({k: v for k, v in row.items() if v is not None})
    history = [{"step": step, **row} for step, row in sorted(events.items())]
    validation = sorted(snapshot.get("validation", []), key=lambda row: row["step"])
    with plt.rc_context({"font.family": "DejaVu Sans", "font.size": 9, "axes.spines.top": False,
                         "axes.spines.right": False, "svg.fonttype": "path"}):
        fig, axes = plt.subplots(2, 3, figsize=(14, 8.4), facecolor="white")
        def line(ax, rows, value, label, color):
            points = [(row["step"], value(row)) for row in rows if row.get("step") is not None and value(row) is not None]
            if points:
                ax.plot(*zip(*points), label=label, color=color, marker="o" if len(points) < 3 else ".", linewidth=1.6, markersize=4)
            return points
        def style(ax, title, ylabel, observed):
            ax.set(title=title, xlabel="Optimizer step", ylabel=ylabel)
            ax.grid(alpha=0.2)
            if not observed:
                ax.text(0.5, 0.5, "No observations yet", transform=ax.transAxes, ha="center", va="center", color="#687789")
        try:
            ax = axes[0, 0]
            points = line(ax, validation, lambda r: r.get("loss"), "Weighted validation", "#2468b4")
            style(ax, "Weighted held-out response loss", "Loss", points)
            ax = axes[0, 1]
            points = []
            for branch, color in (("state", "#2468b4"), ("writing", "#cf7931")):
                points += line(ax, validation, lambda r, b=branch: r.get("branch_loss", {}).get(b), branch.title(), color)
            style(ax, "State and writing held-out loss", "Loss", points)
            if points:
                ax.legend(frameon=False)
            for ax, key, title, ylabel, color in ((axes[0, 2], "learning_rate", "Actual learning rate", "Learning rate", "#8060b0"),
                    (axes[1, 0], "response_tokens_per_s_with_overhead", "Observed training speed", "Response tokens / second, incl. overhead", "#29856b"),
                    (axes[1, 1], "gradient_norm", "Pre-clip gradient norm", "Gradient norm", "#b55e53")):
                points = line(ax, history, lambda r, k=key: r.get(k), title, color)
                style(ax, title, ylabel, points)
                if key == "learning_rate":
                    ax.ticklabel_format(axis="y", style="sci", scilimits=(0, 0))
                if key == "gradient_norm":
                    ax.axhline(1, color="#566679", linestyle="--", linewidth=1, label="Clipping threshold = 1")
                    values = [value for _, value in points]
                    if values and max(values) > 30:
                        ax.set_yscale("symlog", linthresh=1)
                    ax.set_ylim(bottom=0)
                    ax.legend(frameon=False, fontsize=8)
            ax = axes[1, 2]
            total = snapshot["progress"].get("total_steps")
            points = line(ax, history, lambda r: 100 * r["step"] / total if total else None, "Completed", "#2468b4")
            style(ax, "Completed epoch / full target", "Optimizer steps completed (%)", points)
            ax.set_ylim(0, 102)
            if total:
                ax.set_xlim(0, total)
                ax.axhline(100, color="#8090a0", linestyle=":", label="Full epoch target")
                ax.legend(frameon=False, fontsize=8)
            fig.suptitle(f"Friday · epoch {data['percent_text']} · {data['status'][:60]} · ETA {data['eta']}", fontsize=15, fontweight="bold", y=0.98)
            fig.text(0.5, 0.932, f"Captured {snapshot['captured_utc']} · Health: {snapshot['health']['level']}", ha="center", color="#526174")
            fig.text(0.5, 0.018, "Recorded observations only · Held-out reference loss · Gradient norms before clipping · ETA is an estimate", ha="center", color="#526174", fontsize=9)
            fig.tight_layout(rect=(0, 0.045, 1, 0.905), h_pad=2.2, w_pad=2)
            png, vector = io.BytesIO(), io.StringIO()
            fig.savefig(png, format="png", dpi=170)
            fig.savefig(vector, format="svg")
            svg = vector.getvalue()
            svg = svg[svg.index("<svg"):]
            atomic(out / "overview.png", png.getvalue())
            atomic(out / "overview.svg", svg.encode())
            atomic(out / "overview.html", page(snapshot, svg).encode())
        finally:
            plt.close(fig)
    return {"out": str(out), "files": ["overview.png", "overview.svg", "overview.html"], **data}


def self_check():
    snapshot = {"captured_utc": "2026-10-21T12:00:00+00:00", "config_sha256": "a" * 64,
                "status": "running", "health": {"level": "ok", "messages": []},
                "progress": {"step": 25, "total_steps": 100, "eta_s": 3660}, "history": [], "validation": []}
    validate(snapshot)
    assert summary(snapshot)["percent"] == 25 and summary(snapshot)["eta"] == "1h 01m"
    text = page({**snapshot, "status": '<img src=x onerror="bad">', "health": {"level": "warning", "messages": ["<script>bad()</script>"]}}, "<svg></svg>")
    assert "&lt;img" in text and "&lt;script&gt;" in text and '<img src=x' not in text
    assert 'content="60"' in text and "180000" in text and "Data is stale" in text
    evaluated = page({**snapshot, "status": "complete", "monitor_status": "running",
                      "evaluation": {"first": True, "second": False}}, "<svg></svg>")
    assert "active=true" in evaluated and "Post-training reports: 1/2 complete" in evaluated
    assert summary({**snapshot, "progress": {}})["percent"] is None
    for bad in (float("nan"), float("inf"), -1, True, "12"):
        try:
            validate({**snapshot, "history": [{"step": 1, "gradient_norm": bad}]})
        except ValueError:
            pass
        else:
            raise AssertionError("Invalid metric accepted")
    print("Plot self-check passed: summary, unknown series, HTML escaping, stale badge and finite metric guards")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-check", action="store_true")
    parser.add_argument("--snapshot", type=Path)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    if args.self_check:
        self_check()
    elif args.snapshot is None or args.out is None:
        parser.error("--snapshot and --out are required")
    else:
        print(json.dumps(render(json.loads(args.snapshot.read_text()), args.out)))
