# Friday training graphs and continuous monitoring

The active prepared-data experiment has a self-contained HTML dashboard and PNG
and SVG exports. Locally, open `out/live-dashboard/overview.html` relative to this
folder. It reloads every 60 seconds while the background sync refreshes its files.
`metrics.json` contains the recorded points behind the graphs.

## Reading the six graphs

| Graph | What it tells you |
|---|---|
| Weighted held-out response loss | Lower means the reference answers receive more probability. This run weights context/state at 98% and writing at 2%. |
| State and writing held-out loss | Shows whether each skill improves. The combined score can improve while writing remains flat. |
| Actual learning rate | Shows the recorded warmup toward `2e-5`, then gradual decay. This controls the size of parameter updates. |
| Observed training speed | Cumulative supervised response tokens per second, including loop overhead. Checkpoints and validation can cause dips. |
| Pre-clip gradient norm | Measures update size before the trainer clips gradients at 1. An isolated spike can be clipped successfully; it does not alone establish failure. |
| Completed epoch / full target | Progress through one complete pass over 234,834 training examples: 14,678 optimizer updates. |

The figures show observed points, with missing observations left blank. Validation
uses a fixed 158-row subset, including 30 writing references. It supplies previous
annotated dialogue states. These curves measure reference-token prediction;
generated-answer quality and Android task completion need separate evaluation.
No per-batch training-loss curve is fabricated from the gradient or validation
metrics, because that loss was not logged by this trainer.

## Continuous checks

The remote `friday-slm-training-monitor` supervisor process runs at low CPU
priority. Every 60 seconds it reads the current report, validation files and
matching progress events, queries GPU utilization/memory and publishes:

- `overview.html`, `overview.png`, `overview.svg`;
- `metrics.json`, `health.json`, and a history of health changes in `alerts.jsonl`;
- an atomically replaced `live.zip` for transport.

It flags training failure, stopped/exited jobs, no training report updates for
five minutes, an Optuna status older than two minutes, less than 6 GiB free disk,
unavailable GPU metrics, and a weighted validation loss rise above 25% from the
previous check. These checks report conditions; they do not change parameters,
restart training or delete artifacts. Large raw gradients appear in the chart
with the clipping threshold instead of automatically becoming failure alerts.

The monitor stays active through the queued post-training reports. A regression
is counted complete only after every case ran without an execution error, with
matching case coverage, corpus hashes, selected-model metadata and checker code.
A model answer can fail its accuracy checks while its evaluation still finishes.
The prepared-generation report must be a complete atomic report for this run's
selected checkpoint. The monitor exits after completed reports and the completed
Optuna trial, or after a training failure. If collection itself fails, its health
file and supervisor log record the error; the last valid dashboard remains.

The local `dev.friday.slm-sync` launchd job downloads the bundle every 60 seconds
over the existing authenticated SSH connection. It rejects mismatched training
configuration, unexpected/archive traversal entries, duplicate names and oversized
bundles. Files publish atomically, HTML last. Transfer failures retain the last
valid dashboard and retry. The page displays a stale-data warning once its
capture is over three minutes old while monitoring is active.

Remote checks continue if this Mac sleeps or disconnects. Local graph updates
require this Mac and the SSH connection; browser reload and the two background
polls can add delay. The sync job stops after copying the final result. Its
connection arguments, launchd plist and logs remain in ignored `out/monitor-sync/`.
This registration runs in the current login session and is not installed for
automatic startup after a reboot. No web port or external dashboard is exposed.

## Reproduce the remote monitor

Preserve the source layout and run from `training/slm/`. Use a separate environment
so plotting dependencies cannot change the active training environment:

```sh
python3 -m venv .monitor-venv
.monitor-venv/bin/python -m pip install -r plot-requirements.txt
python3 monitor_prepared.py --self-check
python3 plot_prepared.py --self-check
python3 sync_prepared.py --self-check

OMP_NUM_THREADS=1 .monitor-venv/bin/python monitor_prepared.py \
  --run out/prepared-state-stage1-throughput \
  --log out/prepared-state-stage1.log \
  --config-sha256 "$FRIDAY_TRAIN_CONFIG_SHA" \
  --program friday-slm-prepared-state --interval 60
```

Set `FRIDAY_TRAIN_CONFIG_SHA` to the report's exact `config_sha256`. `--once`
collects one snapshot for a rendering smoke check. For a persistent run, use the
instance's existing supervisor and its guide. Plotting uses Matplotlib 3.11.2,
the Agg backend and no model/GPU allocations.

For local transfer, pass private connection settings at runtime:

```sh
python3 sync_prepared.py \
  --host "$FRIDAY_TRAIN_HOST" --port "$FRIDAY_TRAIN_PORT" \
  --identity "$FRIDAY_TRAIN_IDENTITY" --known-hosts "$FRIDAY_TRAIN_KNOWN_HOSTS" \
  --remote-bundle "$FRIDAY_TRAIN_REMOTE_BUNDLE" \
  --config-sha256 "$FRIDAY_TRAIN_CONFIG_SHA" \
  --out out/live-dashboard --interval 60
```

`FRIDAY_TRAIN_REMOTE_BUNDLE` is the remote run's `monitor/live.zip`. The known-hosts
file must already trust that instance. A foreground loop is sufficient; the active
Mac installation uses a launchd plist under ignored `out/monitor-sync/`. Inspect
that job with `launchctl print gui/$(id -u)/dev.friday.slm-sync`. To stop only local
sync, use `launchctl bootout gui/$(id -u)/dev.friday.slm-sync`; the remote trainer
continues. To stop only the remote graph monitor, use
`supervisorctl stop friday-slm-training-monitor` on that instance.

## Checks performed

Standard-library self-checks cover rollback/discarded log events, stale metrics,
failure/exit states, partial regression reports, model-metadata mismatches,
HTML escaping, invalid numbers, safe archives and atomic publication. The real
run also passed collection, Matplotlib PNG/SVG/HTML rendering, verified SSH
download and visual inspection. Full model regression remains queued after SFT.
