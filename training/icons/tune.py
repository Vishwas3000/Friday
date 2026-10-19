"""E1 (research/26 §4): an Optuna search for the icon classifier, scored on a validation split carved from the training
set by unique image (its 114k real crops repeat 49k icons, so a random split would leak copies into validation).

    uv pip install optuna && python tune.py --trials 12      # writes out/tune.json (every trial, the best settings)

The test sets stay untouched for the final report. Trials run one at a time (augmentation is CPU-bound on this box).
"""
import argparse
import hashlib
import json
import time
from pathlib import Path

import numpy as np
import optuna
import torch
import torch.nn as nn
from torch.utils.data import DataLoader
from torchvision.models import mobilenet_v3_small
from torchvision.transforms import v2 as T

from train import Icons, predict

OUT = Path("out")


class Net(nn.Module):  # train.Net with a width multiplier; same input contract
    def __init__(self, k, width):
        super().__init__()
        self.m = mobilenet_v3_small(num_classes=k, width_mult=width)

    def forward(self, x):
        return self.m(((x - 0.5) / 0.5).expand(-1, 3, -1, -1))


def split_by_image(x, y, share=0.05, seed=0):
    """Validation = all copies of ~5% of the unique images, per class."""
    keys = np.array([hashlib.blake2b(im.tobytes(), digest_size=8).hexdigest() for im in x])
    rng = np.random.default_rng(seed)
    val_keys = set()
    for c in np.unique(y):
        uk = np.unique(keys[y == c])
        val_keys.update(rng.choice(uk, max(1, int(len(uk) * share)), replace=False))
    is_val = np.isin(keys, list(val_keys))
    return np.where(~is_val)[0], np.where(is_val)[0], len(np.unique(keys))


def macro_acc(p, y):
    pred = p.argmax(1)
    return float(np.mean([(pred[y == c] == c).mean() for c in np.unique(y)]))


def objective(trial, d, tr, va, k, dev, epochs):
    cfg = {"lr": trial.suggest_float("lr", 1e-3, 1e-2, log=True), "wd": trial.suggest_float("wd", 0.01, 0.1, log=True),
           "smooth": trial.suggest_float("smooth", 0.0, 0.2), "crop_min": trial.suggest_float("crop_min", 0.5, 0.9),
           "blur": trial.suggest_float("blur", 0.0, 0.4), "width": trial.suggest_categorical("width", [0.75, 1.0])}
    torch.manual_seed(trial.number)
    ds = Icons(d["x_train"][tr], d["y_train"][tr], True)
    ds.aug = T.Compose([T.RandomResizedCrop(64, scale=(cfg["crop_min"], 1.0), ratio=(0.85, 1.15), antialias=True),
                        T.RandomInvert(0.5), T.ColorJitter(brightness=0.3, contrast=0.3),
                        T.RandomApply([T.GaussianBlur(3, (0.1, 1.0))], p=cfg["blur"])])
    dl = DataLoader(ds, batch_size=512, shuffle=True, num_workers=8, drop_last=True, persistent_workers=True)
    model = Net(k, cfg["width"]).to(dev)
    opt = torch.optim.AdamW(model.parameters(), lr=cfg["lr"], weight_decay=cfg["wd"])
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=cfg["lr"], total_steps=epochs * len(dl), pct_start=0.05)
    loss_fn = nn.CrossEntropyLoss(label_smoothing=cfg["smooth"])
    xv, yv = d["x_train"][va], d["y_train"][va]
    score = 0.0
    for ep in range(epochs):
        model.train()
        for x, y in dl:
            x, y = x.to(dev, non_blocking=True), y.to(dev, non_blocking=True)
            with torch.autocast(dev, dtype=torch.bfloat16, enabled=dev == "cuda"):
                loss = loss_fn(model(x), y)
            opt.zero_grad(set_to_none=True)
            loss.backward()
            opt.step()
            sched.step()
        score = macro_acc(predict(model, xv, dev), yv)
        trial.report(score, ep)
        if trial.should_prune():
            raise optuna.TrialPruned()
    return score


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--trials", type=int, default=12)
    ap.add_argument("--epochs", type=int, default=25)
    a = ap.parse_args()
    OUT.mkdir(exist_ok=True)
    d = np.load("data/icons.npz")
    k = len(json.loads(Path("data/classes.json").read_text())["classes"])
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    tr, va, unique = split_by_image(d["x_train"], d["y_train"])
    print("train %d, validation %d (by unique image; %d unique images)" % (len(tr), len(va), unique), flush=True)
    study = optuna.create_study(direction="maximize", sampler=optuna.samplers.TPESampler(seed=0),
                                pruner=optuna.pruners.MedianPruner(n_startup_trials=3, n_warmup_steps=5))
    t0 = time.time()
    study.optimize(lambda t: objective(t, d, tr, va, k, dev, a.epochs), n_trials=a.trials,
                   callbacks=[lambda s, t: print("trial %d %s %s %.0fs" % (t.number, t.state.name, t.value, time.time() - t0), flush=True)])
    trials = [{"n": t.number, "state": t.state.name, "value": t.value, "params": t.params} for t in study.trials]
    (OUT / "tune.json").write_text(json.dumps({"best": study.best_params, "best_value": study.best_value, "trials": trials}, indent=1))
    print("best macro validation accuracy %.4f with %s" % (study.best_value, study.best_params), flush=True)


if __name__ == "__main__":
    main()
