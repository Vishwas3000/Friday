# layaTest

Can **Laya**, ConvAI's non-generative "System 1" decision model, operate an Android phone end to end, with everything running locally? That means opening apps, gestures, reading the screen and deciding the next step, with no cloud calls.

- Research: [research/README.md](research/README.md)
- Decisions, with reasons: [docs/DECISIONS.md](docs/DECISIONS.md)

## Quick start (macOS / Windows)

```sh
uv sync
uv run python scripts/bench_model.py        # downloads ByunByun/laya-android (~650 MB), times it on this machine
```

Test device: Pixel 8, Android 16, connected over USB with USB debugging on.
