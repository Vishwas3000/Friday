# On-device tokenizer for the encoder (Phase 2c)

_Last updated: 2026-10-16 · Trust tags: [primary] read at source · [secondary] search/blog/forum · [measured] run here · [UNCERTAIN] unconfirmed or conflicting_

Gets Laya-Android's tokenizer onto the Pixel 8 in Kotlin, byte-identical to the Python fast
tokenizer. Staged class: [`../phase2-staging/Tokenizer.kt`](../phase2-staging/Tokenizer.kt).
Follows from research/04 ("Reuse the Java one on Android"), but lands on DJL instead — see below.

## TL;DR
- **Tokenizer is a BPE** (Gemma-2 / mmBERT), `byte_fallback`, vocab 256,000, 580,604 merges, with a
  `Replace " "→"▁"` normalizer and a `Metaspace` pre-tokenizer [primary, tokenizer.json].
- **Load the existing `tokenizer.json` through DJL's Rust `tokenizers` binding** — exact by
  construction, no algorithm port. Pinned to **0.33.0**, because the Android native `.so` is only
  published at 0.33.0.
- **Gotcha (measured, load-bearing):** `tokenizer.json` embeds `truncation{max_length:48}` — a
  residue of laya's option encoding. The Rust tokenizer (so DJL) honours it and truncates **every
  state to 48 tokens**; `transformers` (laya's path) ignores it. We must **disable truncation and
  padding** in DJL. With that, DJL == transformers on all test strings [measured].
- **Size:** arm64-v8a `libdjl_tokenizer.so` = **8.06 MB** uncompressed; the Java API jar is tiny. Set
  `abiFilters = ["arm64-v8a"]` or the aar also drags in armeabi-v7a/x86/x86_64 (~31.7 MB of `.so`).

## What we must match [primary, laya `common.py` / `onnx_agent.py`]
laya tokenizes the state with one call, in `ONNXAgent._encode_state` and `build_sequence`:

```python
encode_text(tok, serialize_state(state).replace(tok.mask_token, " "),
            add_special_tokens=False)["input_ids"]
```

`serialize_state` is `json.dumps(state, ensure_ascii=False)` for a dict/list, else the string as-is.
`tok` is a `transformers` `AutoTokenizer` (a `PreTrainedTokenizerFast` wrapping Rust `tokenizers`).
So on-device we need `encode(text, addSpecialTokens) -> LongArray` returning the same ids. laya
builds the sequence itself with `cls_token_id`/`sep_token_id`/`mask_token_id`, so in practice it
always calls with `add_special_tokens=False`; the boolean is passed through to stay faithful.

Special tokens, fixed by the checkpoint [primary, special_tokens_map.json / tokenizer_config.json]:
`<pad>`=0 (pad), `<eos>`=1 (SEP), `<bos>`=2 (CLS), `<unk>`=3, `<mask>`=4. `mask_token` string =
`"<mask>"`, `pad_token_id` = 0. Both are hardcoded in `Tokenizer.kt` (DJL 0.33.0 has no getters).

## tokenizer.json, read at source [primary]
| Field | Value |
|---|---|
| `model.type` | `BPE`, `byte_fallback: true`, `fuse_unk`, `ignore_merges`, `unk_token: <unk>` |
| vocab / merges | 256,000 / 580,604 |
| `normalizer` | `Replace` `" "` → `"▁"` (U+2581 metaspace) |
| `pre_tokenizer` | `Metaspace` replacement `"▁"`, `prepend_scheme: always`, `split: true` |
| `post_processor` | `TemplateProcessing`: `<bos> A <eos>` single / `<bos> A <eos> B <eos>` pair |
| `decoder` | `Sequence`[Replace `"▁"`→`" "`, ByteFallback, Fuse] |
| `truncation` | **`{direction: Right, max_length: 48, strategy: LongestFirst, stride: 0}`** ← the gotcha |
| `padding` | `null` |

No `tokenizer.model` (SentencePiece proto) ships, so SentencePiece-based paths are out.

## Chosen: DJL HuggingFace tokenizers, pinned 0.33.0 [primary, Maven + DJL source]
Load `tokenizer.json` with the Rust `tokenizers` library via DJL's JNI binding. It is the same
library `transformers` wraps, so identical ids are by construction, not by a re-implementation.

**Exact coordinates** (version-locked to 0.33.0 — see "why 0.33.0" below):
```kotlin
implementation("ai.djl.huggingface:tokenizers:0.33.0")   // Java API (pulls ai.djl:api:0.33.0)
runtimeOnly("ai.djl.android:tokenizer-native:0.33.0")     // aar: jni/<abi>/libdjl_tokenizer.so
```
- `ai.djl.huggingface:tokenizers` latest is 0.38.0 (2026-09-09), but `ai.djl.android:tokenizer-native`
  is published **only at 0.33.0** (2025-06-26) [primary, repo1 maven-metadata]. The JNI `.so` and the
  Java binding are version-coupled, so the whole path pins to 0.33.0. 0.33.0 is >2 weeks old. ✓
- Android load path: DJL's `jni/LibUtils.loadLibrary()` does
  `if (java.vendor.url == "http://www.android.com/") System.loadLibrary("djl_tokenizer")` — i.e. on
  Android it loads the aar's bundled `.so` from the APK, no runtime download, **no `ai.djl.android:core`
  needed** for the tokenizer alone [primary, LibUtils.java v0.33.0]. This is what makes it run on
  Android and not just desktop JVM.
- The aar ships `jni/{arm64-v8a,armeabi-v7a,x86,x86_64}/libdjl_tokenizer.so` [measured, `unzip -l`].
  arm64-v8a is 8.06 MB; the other three (~23.6 MB) are dead weight on the Pixel 8 → `abiFilters`.

**API used** (`HuggingFaceTokenizer` v0.33.0 source, read at the tag) [primary]:
- `HuggingFaceTokenizer.builder().optTokenizerPath(path).optTruncation(false).optPadding(false).build()`
  — `build()` accepts a file path; `optTruncation(false)`→`DO_NOT_TRUNCATE`, `optPadding(false)`→`DO_NOT_PAD`.
- `encode(text, addSpecialTokens, withOverflowingTokens=false).getIds()` → `long[]`. The 3-arg encode
  takes `addSpecialTokens` per call (the 2-arg form does not exist), matching laya's passthrough.

### Why disable truncation/padding (do not skip this) [measured]
With DJL defaults (`newInstance(Path)`), the constructor sets `truncation=LONGEST_FIRST` and reads
`maxLength` from the handle = the embedded **48**, so `updateTruncationAndPadding()` calls
`setTruncation(handle, 48, …)`. Every state >48 tokens would come back truncated, while laya returns
the whole list and slices it itself in `build_sequence`. Disabling truncation fixes it; padding is
`null` in the file and harmless for single-sequence encode, but disabled too for faithfulness.

## Rejected / alternatives
| Option | Evidence | Verdict |
|---|---|---|
| **laya-java tokenizer** (pure-Java port laya ships; reads tokenizer.json, parity-tested) | research/04: not built for Android — records/sealed types/Java 11 APIs need desugaring/higher minSdk; holds merges as nested `HashMap`s, ~100–250 MB ART heap [UNCERTAIN]. | **Backup / parity oracle.** Heap cost is real on a RAM-tight phone; DJL keeps vocab+merges in compact Rust native memory. Keep as a third parity reference. |
| DJL desktop version (0.38.0) | Newest Java API, but **no** matching Android `.so` (native stops at 0.33.0). | No — would have no native lib on Android. |
| Rust `tokenizers` via cargo-ndk + own JNI | Exact, but adds a Rust/NDK build chain to every CI run. | Only if DJL 0.33.0 ever breaks on a newer Android. |
| onnxruntime-extensions tokenizer ops | `-android` stuck at 0.13.0 (2024-10) vs 0.15.2 on PyPI [primary, research/04]. | Avoid (stale). |
| SentencePiece-android | No `.model` file ships, and the Metaspace pre-tokenizer ≠ stock SP. | Dead end. |

Prior art: `edwardmonteiro/laya-android` runs base Laya on-device using **HF tokenizers through DJL**
plus ONNX Runtime Android [primary, self-reported, research/01] — i.e. this exact path is known to work
on a phone.

## Parity-test plan (gate before trusting on-device)
Compute the ids in Python and in the Kotlin/DJL lib for the same strings and assert equality. The
Python oracle and the Rust core (what DJL wraps) already agree here with truncation disabled
[measured, `transformers 5.20.0` + `tokenizers 0.23.3`], so the remaining risk is purely the DJL
wrapper config — which the test pins down.

Expected ids, `add_special_tokens=False` (the state path). These are the assertions:

| # | input (post `.replace("<mask>"," ")`) | expected ids |
|---|---|---|
| 1 | `Settings` | `[20813]` |
| 2 | `{"pkg": "com.android.settings", "tree": [{"id": 0, "role": "Button", "text": "Bluetooth"}, {"id": 1, "role": "Switch", "text": "Off"}]}` | 52 ids: `[19946, 25228, 1192, 664, 872, 235265, 6851, 235265, 11889, 824, 664, 9197, 1192, 892, 9766, 539, 1192, 235248, 235276, 235269, 664, 8247, 1192, 664, 2829, 824, 664, 1082, 1192, 664, 50641, 16406, 19946, 539, 1192, 235248, 235274, 235269, 664, 8247, 1192, 664, 16219, 824, 664, 1082, 1192, 664, 6192, 235281, 12765, 235270]` |
| 3 | `Bluetooth सेटिंग्स 🔵` (byte_fallback) | `[24764, 11670, 235935, 42828, 62607, 235248, 243357]` |
| 4 | `turn   off` (from `turn <mask> off`) | `[2894, 235248, 235248, 1401]` |
| 5 | `  multiple   spaces\tand\ttabs` | `[235248, 6733, 235248, 235248, 14130, 226, 639, 226, 31973]` |

Sanity checks: `add_special_tokens=True` on #1 → `[2, 20813, 1]` (wraps `<bos> … <eos>`); on #2 → the
52 ids wrapped as `[2, …, 1]` (54). String #2 deliberately exceeds 48 tokens so it catches the
truncation gotcha — if DJL is misconfigured it returns 48 ids, not 52.

Steps:
1. Python oracle — regenerate with the repo venv (reproduces the table above):
   `cd <repo> && HF_HUB_OFFLINE=1 uv run python <script> <snapshot>/tokenizer` using either
   `transformers.AutoTokenizer.from_pretrained(dir)` or `tokenizers.Tokenizer.from_file(...)` with
   `no_truncation()`; both match. (Throwaway script used for this note is not committed — fold it into
   `scripts/make_fixture.py` if a committed fixture is wanted.)
2. Kotlin side — load the same `tokenizer.json`, run `Tokenizer.encode(s, false)` on the 5 strings,
   assert the arrays equal the table. Run it as an instrumented (on-device/emulator) test, since the
   native `.so` only loads under Android — a desktop JVM unit test would need the desktop native jar.
3. Extend to real data before trusting fp16 end-to-end: replay ~20 serialized states from `captures/`
   through both and diff, same as research/06's note that parity is a smoke test until real states run.

## Open / next
- **[gate] Run the parity test on the Pixel 8 / emulator.** Byte-identity is proven for the Python
  oracle vs the Rust core here; the DJL-on-Android wrapper is confirmed by source reading, not yet by a
  run on hardware [UNCERTAIN until run].
- Decision to log (orchestrator): **D-022** — on-device tokenizer = DJL `tokenizers` 0.33.0 + Android
  `tokenizer-native` 0.33.0, truncation/padding disabled; rejected laya-java (heap) and the newer DJL
  (no Android native). docs/DECISIONS.md is the orchestrator's to edit (out of this task's scope).
- Only the state-encode path is staged. laya's option encoding uses `truncation=True, max_length=48`
  per call; if/when the full sequence builder is ported to Kotlin, add a truncating encode variant
  (DJL `optMaxLength(48)` + `optTruncation(true)`, or slice in Kotlin) — do not re-enable truncation on
  this shared instance.
