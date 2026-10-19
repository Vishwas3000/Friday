import time, random, sys
t0 = time.time()
import student as ST
import laya.train as LT
from laya.common import collate_items, encode_text
from transformers import AutoTokenizer
tok = AutoTokenizer.from_pretrained(str(ST.teacher_dir() / "tokenizer"))
print("import+tok %.1f s" % (time.time() - t0), flush=True)
t0 = time.time()
for i in range(200):
    encode_text(tok, " 12: Settings (TextView) selected", add_special_tokens=False, truncation=True, max_length=48)
print("tok call with truncation: %.3f ms" % ((time.time() - t0) / 200 * 1000), flush=True)
t0 = time.time()
for i in range(200):
    encode_text(tok, " 12: Settings (TextView) selected", add_special_tokens=False)
print("tok call no truncation: %.3f ms" % ((time.time() - t0) / 200 * 1000), flush=True)
t0 = time.time()
for i in range(200):
    tok._tokenizer.encode(" 12: Settings (TextView) selected", add_special_tokens=False)
print("rust encode: %.3f ms" % ((time.time() - t0) / 200 * 1000), flush=True)
rows = ST.steps("val")[:200]
t0 = time.time()
items, sk = ST.distil_items(tok, rows, {}, 0.0, 2.0)
print("items", len(items), "built in %.1f s" % (time.time() - t0), flush=True)
rng = random.Random(0)
t0 = time.time(); n = 0
for s in range(0, 160, 8):
    chunk = [LT.encode_item(tok, it, 3072, 1536, LT.draw_option_order(it, rng, ("choice",))) for it in items[s:s+8]]
    b = collate_items([chunk], tok.pad_token_id); n += 1
print("encode+collate per micro-batch of 8: %.1f ms" % (1000 * (time.time() - t0) / n), flush=True)
