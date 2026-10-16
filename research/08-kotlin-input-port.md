# Kotlin input-build + decode port (Phase 2 on-device)

_Last updated: 2026-10-16 · Trust tags: [primary] read at source · [measured] run here · [UNCERTAIN] unconfirmed_

## TL;DR
- Ported the **deterministic** half of the Laya-Android pipeline to Kotlin so the on-device ONNX
  model gets byte-identical input and its outputs decode identically:
  [`phase2-staging/LayaInput.kt`](../phase2-staging/LayaInput.kt) (package `dev.layatest.agent`).
- Covers: `option_label`, `history_entry`, `decide()`'s state/questions assembly
  (scripts/laya_android.py); `serialize_state`, `build_head`/`build_sequence`, `collate_items`
  (laya/common.py); `_encode_state` state-reuse, `_infer_batch` casts, `_decode_answers`
  (laya/onnx_agent.py).
- **Not** ported (out of scope / unused by Friday): `parse_forest`/`build_candidates` (the caller
  supplies candidates), `window_*`/`predict_long`, `score`/`noul` question types, `option_order` /
  `unpermute` (decide never sets it → identity), `temperature_by_options` (`{}` for this checkpoint),
  `lang_temperatures`, binning calibration.
- Verified every expected value against the real Python before hardcoding it; the staging file ends
  with an assert-based `main()` self-check mirroring `scripts/laya_android.py`'s.

## Tokenizer contract assumed [primary]
Another agent owns `Tokenizer.kt`. This port depends only on its encoder, passed as a function:

```
encode: (text: String, addSpecialTokens: Boolean) -> LongArray
```

returning the same ids as HF `tok(text, add_special_tokens=...)["input_ids"]`. We only ever call it
with `addSpecialTokens=false` (as the Python does). **Correctness of the whole port rides on this
matching HF for the mmBERT/gemma tokenizer, including leading-space handling** (options are encoded as
`" " + option`). Wire it with `tokenizer::encode`. The option `max_length=48` truncation is done by
slicing the returned array to 48 (the Python comment confirms this equals `truncation=True`).

## Constants hardcoded from the pin (ByunByun/laya-android @5a03b2b) [primary]
- Special-token ids (tokenizer/special_tokens_map.json): `CLS=<bos>=2`, `SEP=<eos>=1`, `MASK=<mask>=4`,
  `PAD=<pad>=0`; `mask_token="<mask>"`. collate pads ids with `PAD=0` (so `LongArray` zero-init is it).
- `temperature = [1.1609286069869995, 1.0, 1.0]` indexed by qtype; `choice=0`
  (rl_agent_config.json). `temperature_by_options={}`, so `t_scale` is always `temperature[0]`.
- `max_len=3072`, `head_max_len=1536`.

shortcut: re-read those two files if `PIN["revision"]` changes.

## `build_sequence` notes / things to watch [primary]
- Layout: `[CLS] "choice question: "+ins [SEP] ([MASK] opt)* [SEP] state[:room] [SEP]`. There are **two
  SEPs** — one closes the options (emitted by `build_head`), one closes the state. Markers are the
  **absolute** index of each `[MASK]`.
- `room = max_len - headLen - 1` (the `-1` reserves the closing SEP); dict state truncates **right**
  (`state_ids[:room]`); a list state would truncate left, but Friday states are always dicts.
- State is serialized **once** and reused across both questions (`_encode_state` passes `state_ids`),
  so `serialize_state` runs once, not per question.
- `serialize_state` == `json.dumps({goal,history,ui}, ensure_ascii=False)`: keys in insertion order,
  `", "`/`": "` separators, and only `"`, `\`, C0 controls escaped (`/` and non-ASCII stay verbatim).
  org.json's `toString()` does **not** match (no spaces, escapes `/`), so the port hand-rolls the
  string. [measured] matched Python byte-for-byte on a quote/backslash/newline/unicode state.
- The op-budget / per-option cap and head truncation are ported verbatim but never fire for Friday
  (12 ops + N short labels ≪ head_max_len=1536).
- Guard kept from `_encode_state`: if a question's markers don't all survive `max_len`, `build`
  throws rather than let the model decode onto the wrong options.

## Verification [measured]
Ran `HF_HUB_OFFLINE=1 uv run python` on the real tokenizer + `laya.common` for a tiny 2-candidate
state and the shipped `models/fixture.json` (rows=2, seq=318, markers=14):
- `serialize_state`, `render_options`, and `build_sequence` markers reproduced by hand. The tiny
  state's **operation** markers `[12,19,30,38,46,54,62,72,83,91,99,108]` equal fixture row 0 exactly
  (the operation question is state-independent) — a strong cross-check on marker placement.
- `_decode_answers` on `fixture.expected_logits` at `temperature[0]`: operation argmax **8 → "BACK"**,
  target argmax **0 → "0"**; full-precision probabilities captured and asserted (self-check, ±3e-4 to
  absorb float32-vs-double + 4dp rounding).
- The Kotlin self-check (`main()`) can't run without the real `Tokenizer`, so its marker-placement
  asserts use a fixed-3-token **stub** (positions hand-checkable: first marker at 5, stride 4); the
  decode and `serialize_state` asserts use the real fixture values above. No kotlinc in this env, so
  the Kotlin was **not compiled** [UNCERTAIN] — logic transliterated to Python and checked instead.

## Open items
- Real `Tokenizer.encode` parity with HF is the one unverified dependency; a 1-token drift shifts every
  marker. Once `Tokenizer.kt` lands, feed `models/fixture.json`'s state through `buildInputs` and assert
  the 5 arrays equal the fixture (that closes the gap the stub leaves).
- `buildInputs` returns 2D arrays (`Array<LongArray>` / `Array<BooleanArray>` + `LongArray` qtype);
  whoever creates the `OnnxTensor`s must keep the row order (operation, then target).
