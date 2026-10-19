"""Where does a training micro-batch go? fwd+bwd time per item for a few configs (research/28 speed check)."""
import random, sys, time
import torch
import student as ST
import laya.train as LT
from laya.common import collate_items
LT._forward = ST._forward_bf16
model, tok, cfg = LT.load_checkpoint("ckpt/s11-init")
rows = ST.steps("val")
items, _ = ST.distil_items(tok, rows, {}, 0.0, 2.0)
dev = torch.device("cuda")
model.to(dev).train()
rng = random.Random(0)
def run(mb, ckpt, sort, n_items=256):
    model.encoder.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False}) if ckpt else model.encoder.gradient_checkpointing_disable()
    model.head_checkpointing = ckpt
    its = list(items)
    rng.shuffle(its)
    its = its[:n_items]
    if sort:
        its.sort(key=lambda it: len(it["state_ids"]) + 12 * it["k"])
    torch.cuda.synchronize(); t0 = time.time(); tok_n = 0
    for s in range(0, len(its), mb):
        chunk = [LT.encode_item(tok, it, 3072, 1536, LT.draw_option_order(it, rng, ("choice",))) for it in its[s:s+mb]]
        b = collate_items([chunk], tok.pad_token_id)
        tok_n += b["input_ids"].numel()
        logits = LT._forward(model, b, dev, True, False)
        loss = LT.soft_ce_loss(logits, b["target"].to(dev), b["marker_mask"].to(dev))
        loss.backward()
        loss.item()
    torch.cuda.synchronize(); dt = time.time() - t0
    print("mb %2d ckpt %d sorted %d: %.1f ms/item, %.0f padded tokens/item, peak %.1f GB" % (
        mb, ckpt, sort, 1000 * dt / len(its), tok_n / len(its), torch.cuda.max_memory_allocated() / 1e9), flush=True)
    model.zero_grad(set_to_none=True)
    torch.cuda.reset_peak_memory_stats()
run(8, 1, 0, 64)  # warm-up
for mb, ck, so in [(8, 1, 0), (8, 0, 0), (32, 1, 0), (32, 0, 0), (32, 1, 1)]:
    run(mb, ck, so)
