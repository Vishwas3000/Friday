"""Train the icon classifier (research/25, D-055), export it for the phone, and check it.

    python train.py [--epochs 40]

- Model: MobileNetV3-Small from scratch (no ImageNet weights, so no ImageNet terms), 64x64 grey in, probabilities out.
  The ONNX takes exactly what the phone has: a grey crop scaled to 64x64, values /255 ("image": N x 1 x 64 x 64).
- Checks: accuracy on UIST'18's held-out real icons and on held-out renders; a PyTorch-vs-ONNX Runtime parity check;
  the confidence threshold at which named icons are >= 95% right (below it the phone leaves the icon unnamed).
- Output (out/): icons.onnx (~6.5 MB), labels.json (classes, the words Laya sees, threshold), metrics.json.
"""
import argparse
import json
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
import torch.nn as nn
from torch.utils.data import DataLoader, Dataset
from torchvision.models import mobilenet_v3_small
from torchvision.transforms import v2 as T

OUT = Path("out")


class Net(nn.Module):
    """Grey 64x64 in [0, 1] -> class logits."""

    def __init__(self, k):
        super().__init__()
        self.m = mobilenet_v3_small(num_classes=k)

    def forward(self, x):
        return self.m(((x - 0.5) / 0.5).expand(-1, 3, -1, -1))


class Probs(nn.Module):  # what ships: softmax inside the graph
    def __init__(self, net):
        super().__init__()
        self.net = net

    def forward(self, image):
        return torch.softmax(self.net(image), 1)


class Icons(Dataset):
    def __init__(self, x, y, train, crop_min=0.7, blur=0.2):
        self.x, self.y = torch.from_numpy(x), torch.from_numpy(y)
        # app icons come light or dark, padded or tight, sharp or soft
        self.aug = T.Compose([T.RandomResizedCrop(64, scale=(crop_min, 1.0), ratio=(0.85, 1.15), antialias=True),
                              T.RandomInvert(0.5), T.ColorJitter(brightness=0.3, contrast=0.3),
                              T.RandomApply([T.GaussianBlur(3, (0.1, 1.0))], p=blur)]) if train else None

    def __len__(self):
        return len(self.y)

    def __getitem__(self, i):
        x = self.x[i][None].float() / 255
        return (self.aug(x) if self.aug else x), self.y[i]


@torch.no_grad()
def predict(model, x, dev, bs=1024):
    model.eval()
    out = []
    for i in range(0, len(x), bs):
        out.append(torch.softmax(model(torch.from_numpy(x[i:i + bs])[:, None].float().to(dev) / 255), 1).cpu())
    return torch.cat(out).numpy()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--epochs", type=int, default=40)
    ap.add_argument("--batch", type=int, default=512)
    ap.add_argument("--lr", type=float, default=3e-3)
    # E1's search space (tune.py); the defaults are E0's recipe (research/26 §3.5)
    ap.add_argument("--wd", type=float, default=0.05)
    ap.add_argument("--smooth", type=float, default=0.1)
    ap.add_argument("--crop-min", type=float, default=0.7)
    ap.add_argument("--blur", type=float, default=0.2)
    ap.add_argument("--eval-only", action="store_true", help="skip training: threshold, export and checks from out/best.pt")
    ap.add_argument("--subset", choices=["all", "real", "renders"], default="all", help="ablations A1/A2 (research/26 §4)")
    a = ap.parse_args()
    OUT.mkdir(exist_ok=True)
    d = dict(np.load("data/icons.npz"))
    if a.subset != "all":  # UIST'18's 113,854 real crops come first in x_train (prepare.py), then the renders
        n = int(d.get("n_real", 113854))
        rows = slice(0, n) if a.subset == "real" else slice(n, None)
        d["x_train"], d["y_train"] = d["x_train"][rows], d["y_train"][rows]
    meta = json.loads(Path("data/classes.json").read_text())
    classes, other = meta["classes"], meta["classes"].index("other")
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    torch.manual_seed(0)
    model = Net(len(classes)).to(dev)
    print("parameters %.2fM | train %d | device %s" % (sum(p.numel() for p in model.parameters()) / 1e6, len(d["y_train"]), dev), flush=True)
    if not a.eval_only:
        fit(model, d, a, dev)
    evaluate(model, d, meta, dev)


def fit(model, d, a, dev):
    assert dev == "cuda", "no CUDA: this PyTorch build doesn't match the driver (setup.sh picks the build)"
    dl = DataLoader(Icons(d["x_train"], d["y_train"], True, a.crop_min, a.blur), batch_size=a.batch, shuffle=True, num_workers=8,
                    drop_last=True, persistent_workers=True)
    opt = torch.optim.AdamW(model.parameters(), lr=a.lr, weight_decay=a.wd)
    steps = a.epochs * len(dl)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=a.lr, total_steps=steps, pct_start=0.05)
    loss_fn = nn.CrossEntropyLoss(label_smoothing=a.smooth)
    best, t0 = -1.0, time.time()
    for ep in range(a.epochs):
        model.train()
        for x, y in dl:
            x, y = x.to(dev, non_blocking=True), y.to(dev, non_blocking=True)
            with torch.autocast(dev, dtype=torch.bfloat16, enabled=dev == "cuda"):
                loss = loss_fn(model(x), y)
            opt.zero_grad(set_to_none=True)
            loss.backward()
            opt.step()
            sched.step()
        real = (predict(model, d["x_test_real"], dev).argmax(1) == d["y_test_real"]).mean()
        rend = (predict(model, d["x_test_render"], dev).argmax(1) == d["y_test_render"]).mean()
        print("epoch %2d  loss %.3f  real %.4f  renders %.4f  %.0fs" % (ep + 1, loss.item(), real, rend, time.time() - t0), flush=True)
        if real + rend > best:
            best = real + rend
            torch.save(model.state_dict(), OUT / "best.pt")


def evaluate(model, d, meta, dev):
    classes, other = meta["classes"], meta["classes"].index("other")
    model.load_state_dict(torch.load(OUT / "best.pt", map_location=dev))
    # the threshold: the lowest confidence at which named (non-"other") icons are >= 95% right over the real, render and
    # unseen-"other" test sets -- a name given to an icon from outside the list it never saw counts as wrong
    has_other = "x_test_other" in d
    p = np.concatenate([predict(model, d["x_test_real"], dev), predict(model, d["x_test_render"], dev)] +
                       ([predict(model, d["x_test_other"], dev)] if has_other else []))
    y = np.concatenate([d["y_test_real"], d["y_test_render"]] + ([d["y_test_other"]] if has_other else []))
    pred, conf = p.argmax(1), p.max(1)
    named = pred != other
    threshold = 1.0
    for t in np.arange(0.05, 1.0, 0.01):
        keep = named & (conf >= t)
        if keep.sum() and (pred[keep] == y[keep]).mean() >= 0.95:
            threshold = round(float(t), 2)
            break
    keep = named & (conf >= threshold)
    real_n, rend_n = len(d["y_test_real"]), len(d["y_test_render"])  # the unseen-"other" predictions come after both
    per_class = {classes[c]: round(float((pred[:real_n][d["y_test_real"] == c] == c).mean()), 3)
                 for c in range(len(classes)) if (d["y_test_real"] == c).any()}
    metrics = {"real_top1": float((pred[:real_n] == d["y_test_real"]).mean()),
               "render_top1": float((pred[real_n:real_n + rend_n] == d["y_test_render"]).mean()),
               "threshold": threshold, "precision_at_threshold": float((pred[keep] == y[keep]).mean()),
               "named_share_at_threshold": float(keep.mean()), "per_class_real": per_class}
    if has_other:  # how often an unseen icon from outside the list still gets a name
        tail = slice(len(y) - len(d["y_test_other"]), None)
        metrics["unseen_other_named"] = float(keep[tail].mean())
        metrics["unseen_other_named_by_threshold"] = {f"{t:.1f}": float(((pred[tail] != other) & (conf[tail] >= t)).mean())
                                                      for t in (0.3, 0.5, 0.7, 0.9)}

    # export what the phone runs, then check ONNX Runtime agrees with PyTorch. The wrapper is put in eval mode first:
    # export restores a module's previous mode afterwards, and a fresh wrapper's "train" flipped BatchNorm back to
    # batch statistics, so the first run's check compared train-mode PyTorch with eval-mode ONNX (diff 0.85; E0)
    w = Probs(model.cpu()).eval()
    sample = d["x_test_real"][:64][:, None].astype(np.float32) / 255
    with torch.no_grad():
        ref = w(torch.from_numpy(sample)).numpy()
    onnx_path = OUT / "icons.onnx"
    torch.onnx.export(w, torch.zeros(1, 1, 64, 64), str(onnx_path), input_names=["image"], output_names=["probs"],
                      dynamic_axes={"image": {0: "n"}, "probs": {0: "n"}}, opset_version=17, dynamo=False)
    got = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"]).run(None, {"image": sample})[0]
    metrics["onnx_max_diff"] = float(np.abs(ref - got).max())
    metrics["onnx_mb"] = round(onnx_path.stat().st_size / 1e6, 2)
    (OUT / "labels.json").write_text(json.dumps({"classes": classes, "words": meta["words"], "threshold": threshold,
                                                 "input": {"size": 64, "gray": True, "scale": "x/255"}}))
    (OUT / "metrics.json").write_text(json.dumps(metrics, indent=1))
    print(json.dumps({k: v for k, v in metrics.items() if k != "per_class_real"}, indent=1))
    worst = sorted(per_class.items(), key=lambda kv: kv[1])[:10]
    print("weakest real classes:", worst)


if __name__ == "__main__":
    main()
