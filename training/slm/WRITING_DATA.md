# Public TRAIN data for Friday's small text model

_21 October 2026 · Downloaded and adapted locally · Future SFT input; no model training or frozen-suite changes_

The existing dialogue-state corpus teaches state tracking, not grammatical rewriting or concise summaries. The [student review](STUDENT_REVIEW.md) also distinguishes preserving source text from actually performing an edit. This addition supplies **21,768 filtered publisher-reference writing examples**, including **19,697 grammar corrections** and **2,071 dialogue summaries**. It does not use Qwen to create their targets.

## Sources verified and downloaded

Only each publisher's **TRAIN** file and README notice were downloaded. Publisher validation/test splits remain unused. All source and prepared data lives under ignored `training/slm/out/writing-external/`; dataset text is not committed.

| Dataset | Immutable revision | Downloaded TRAIN records | Source format and relevance |
|---|---|---:|---|
| [Grammarly CoEdIT](https://huggingface.co/datasets/grammarly/coedit/tree/e9a255c33ef910bc33a9d2b522653fa87521583e) | `e9a255c33ef910bc33a9d2b522653fa87521583e` | 69,071 | JSONL with `_id`, `task`, `src`, `tgt`. The focused adapter uses grammatical-error-correction (`gec`) rows; the source keeps its other editing tasks for later work. |
| [Official DialogSum](https://github.com/cylnlp/dialogsum/tree/848cc8c05e0a04def79326539de827f1b28786a0) | `848cc8c05e0a04def79326539de827f1b28786a0` | 12,460 | JSONL with `fname`, `dialogue`, `summary`, `topic`. Publisher summaries provide an observer-style reference for everyday dialogues. |
| **Total raw TRAIN records** | | **81,531** | **31,770,633 bytes including the two README notices.** |

CoEdIT's pinned dataset card declares **Apache-2.0**. DialogSum's pinned README declares **CC BY-NC-SA 4.0** and describes manually labeled summaries. These are recorded separately in every prepared row's provenance. This collection proceeds within the user's authorized noncommercial research scope; it does not relabel the combined data as Apache-only. [CoEdIT pinned dataset card](https://huggingface.co/datasets/grammarly/coedit/blob/e9a255c33ef910bc33a9d2b522653fa87521583e/README.md), [DialogSum pinned README](https://github.com/cylnlp/dialogsum/blob/848cc8c05e0a04def79326539de827f1b28786a0/README.md)

### Raw-file identities

The downloader checks the following exact sizes and SHA-256 digests. The CoEdIT TRAIN digest also matches the publisher's HF-LFS object digest; DialogSum TRAIN bytes were independently matched to its pinned Git blob during source discovery.

| Pinned source file | Bytes | SHA-256 |
|---|---:|---|
| [CoEdIT TRAIN](https://huggingface.co/datasets/grammarly/coedit/resolve/e9a255c33ef910bc33a9d2b522653fa87521583e/train.jsonl?download=true) | 19,695,735 | `2913249158d6a178dc638e870212ff8a432d128eb6b4bdbe969ee805e6063ce3` |
| CoEdIT README at the same revision | 1,875 | `6ade266ee39776a2e283870eab9c0c5e4099d88eefe951711a110eb045014efd` |
| [DialogSum TRAIN](https://raw.githubusercontent.com/cylnlp/dialogsum/848cc8c05e0a04def79326539de827f1b28786a0/DialogSum_Data/dialogsum.train.jsonl) | 12,068,822 | `660238631152c062e67186da6622c1e8925579e82563d66d611abb9dcc19bb72` |
| DialogSum README at the same revision | 4,201 | `9bd01bdcbe9e53c61f47984110c9e7ccd997ed27b02b983cd184203da9bbbd47` |

## Adapter and measured output

[writing_data.py](writing_data.py) uses Python's standard library and the existing public-download, prompt and lexical-check helpers. It installs no packages and requires no GPU. Missing raw files can be fetched from their immutable URLs; existing raw files are rehashed before use. Existing prepared output is never overwritten.

For CoEdIT `gec`, the publisher instruction prefix is separated from source text at the first `: ` delimiter. All 19,823 `gec` source rows had that delimiter. The original task instruction is retained and the target is the publisher's `tgt`. For DialogSum, the dialogue is the source and the publisher's summary is the target.

Prepared rows contain `messages` using the unchanged shared Writer prompt helper, plus a `response` JSON string with one `text` field. Each provenance record includes dataset/revision/license, original TRAIN split, source file/line/record identity, raw-line SHA-256, file SHA-256, source-group fingerprint and input/response fingerprints. **Label source is the publisher reference, not Qwen, Friday or an invented device observation.**

| Accepted source | Rows |
|---|---:|
| CoEdIT grammatical corrections | 19,697 |
| DialogSum summaries | 2,071 |
| **Prepared total** | **21,768** |

| Local split | Rows | Unique normalized source groups | Bytes |
|---|---:|---:|---:|
| Future training | 20,617 | 20,401 | 36,722,463 |
| Future validation | 1,151 | 1,136 | 2,051,801 |
| **Total** | **21,768** | **21,537** | **38,774,264** |

Raw plus prepared payloads total **70,544,897 bytes**, below the adapter's **150,000,000-byte** cap. The small manifest/notice overhead does not approach that limit.

The corpus was also reproduced on the remote training machine under
`out/writing-external/prepared/`. Its manifest and both JSONL file hashes match the
local result exactly. The data is staged for a separate writing SFT experiment.

### Conservative filtering

Targets are rejected for empty/unchanged text, reserved model-control markers, excessive lengths, changed numeric-token sets, changed alphanumeric identifier sets, or changed lexical negation presence. Exact input/response pairs are deduplicated. The length filter uses instruction plus source ≤2,800 characters and target ≤80 whitespace-separated words; it is not a tokenizer-length proof.

| Dataset | First rejection reason | Rows |
|---|---|---:|
| CoEdIT | Other editing task deferred | 49,248 |
| CoEdIT | Identifier change | 22 |
| CoEdIT | Negation-canary change | 78 |
| CoEdIT | Numeric-token change | 8 |
| CoEdIT | Length limit | 14 |
| CoEdIT | Duplicate input/response pair | 4 |
| DialogSum | Numeric-token change | 6,726 |
| DialogSum | Negation-canary change | 3,585 |
| DialogSum | Identifier change | 30 |
| DialogSum | Length limit | 48 |

Reasons are counted in filter order, not as independent overlapping error counts. Many legitimate summaries omit incidental numbers, speaker identifiers or negated dialogue details. This intentionally strict first adapter drops them; **a rejected summary is not evidence of a bad publisher annotation**. Conversely, surviving lexical checks do not prove correct people/action relationships, complete factual coverage, proper grammar or semantic faithfulness.

### Split and audit

Only publisher TRAIN data is repartitioned. A stable SHA-256 group of whitespace-normalized, casefolded source text assigns approximately 5% to future validation. All variants of the same normalized source stay together, including variants with different task-prefix wording. The measured output has **zero source groups crossing train/validation** and **zero exact input/response duplicates**.

The adapter's standard-library self-check passed. A separate full pass audited all **21,768** output rows for identity uniqueness, JSON target shape, publisher-TRAIN provenance, input/response fingerprints, source-group membership, split consistency and the same lexical filters. No semantic or human review of those 21,768 pairs was performed.

An independent source reconstruction, without importing the adapter, then verified every prepared target against its exact publisher reference and rebuilt every input from its raw TRAIN line using the unchanged prompt helper. All **21,768** rows passed raw-file, source-line and row-hash checks, with zero duplicate IDs, exact input/target duplicates or cross-split source groups. Exactly **one input group has multiple reference targets**; those writing references remain in the same source group and split. This measures provenance and construction, not semantic quality. The ignored audit report is `out/writing-external/prepared/independent-source-check.json`.

| Prepared artifact | SHA-256 |
|---|---|
| `prepared/train.jsonl` | `da9201a6328bc68e8970768893d2d2f39caff52c81d6504f8617a8ccac1da539` |
| `prepared/validation.jsonl` | `0326cee87118869a5850e325912a9a0c7050d1e8070d9b84d9cdb600b398fe4d` |
| `prepared/manifest.json` | `7a2e3939bac5a0f611bc2c6b7f44981c5ac143bf911df97e01aeab343b3227d2` |

## Reproduce and use next

From the repo root:

```sh
python3 training/slm/writing_data.py --self-check
python3 training/slm/writing_data.py --fetch
```

The default raw directory is `training/slm/out/writing-external`; prepared output is its `prepared` subdirectory. The second command requires an empty prepared output directory. To audit a new deterministic build without replacing existing data, use `--output training/slm/out/writing-external/prepared-recheck` only when sufficient disk is available; a duplicate local copy is not needed for the current result.

**Protocol compatibility:** these are future `messages`/`response` SFT rows. They are not directly accepted by the current pilot's Qwen-label manifest loader and are not the `dialogue_state_v2` protocol. No current loader, frozen suite or model was modified, and these rows were not mixed into the completed training run.

The next concrete step is a separate response-only writing experiment with an explicit loader for this format, tokenizer-length checks, a reviewed sample of edits/summaries, controlled rewrite-versus-summary sampling and an independent unseen writing evaluation. Preserve publisher references and row/source hashes through that step. Test grammar correction and requested transformations as well as canary survival; a model that merely copies source text must not earn a rewriting pass.

**Remaining limits:** no near-duplicate/semantic decontamination across datasets or other existing corpora, exhaustive reference-quality audit, tokenizer budget measurement, model training, Pixel testing or Android app integration. These prepared rows add writing supervision, not proof that Friday reads apps or performs tasks reliably.
