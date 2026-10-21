# Online datasets for Friday's next research stage

_Checked 21 October 2026. Research/noncommercial use. No external rows entered the current 1,440-candidate synthetic pilot or frozen 84-case regression._

Start with **SGD for retained task state** and **MultiWOZ 2.2 for hotel constraints
and corrections**. Both complete public **TRAIN** splits are now downloaded under
ignored `training/slm/out/external-full/`; the two initial sample shards remain in
`out/external/`. This document
records technical suitability, basic source/license metadata and measured sample
sizes. No private app data, model weights or large UI dataset shards were downloaded.

## Candidate sources

| Dataset | Format and size | Useful Friday supervision | Access/license and adaptation limits |
|---|---|---|---|
| **Schema-Guided Dialogue (SGD)** | Over 20,000 dialogues across 20 domains. JSON dialogues contain `turns`, per-service `frames`, slot spans and user `state`; schemas define required/optional slots and intents. | Retain supplied values, collect missing fields, handle corrections and distinguish service search from transactional intent. | Public, **CC BY-SA 4.0**. Use official train only for training. Simulated/crowdsourced conversations and service calls are not device completion receipts; schema defaults are not owner-supplied facts. [Official schema/data/license](https://github.com/google-research-datasets/dstc8-schema-guided-dialogue/blob/e852981ae34990f4358979625854259302feaa78/README.md) |
| **Taskmaster-2** | **17,289** spoken two-person dialogues in seven domains, including **2,355 hotel** and **1,602 music** dialogues. JSON `conversation_id`, `instruction_id`, `utterances[].text/speaker/segments`; spans use `start_index`/`end_index`. | More natural phrasing, corrections, hotel comparisons and music browsing; retain instruction-family provenance for grouped splits. | Public, **CC BY 4.0**. TM-2 annotations do not identify API calls or argument accept/reject. Do not apply TM-1's self-dialogue split CSVs to TM-2; define/document dialogue-group splits for this release. [Official TM-2 release](https://github.com/google-research-datasets/Taskmaster/blob/d92cb6af3005f1dc09c39e75e7daf4a04905e00b/TM-2-2020/README.md) |
| **MultiWOZ 2.2** | Corrected multi-domain dialogue corpus, about 10,000 dialogues; JSON shards in explicit `train/dev/test`, with schema, user state and non-categorical spans. State values may have accepted alternatives or `copy_from` provenance. | Hotel/restaurant/train constraints across turns, slot corrections, missing-data questions and cross-domain references. | Public, repository **MIT**. Preserve the 2.2 version and dialogue split; earlier versions share dialogues and must not become “independent” training/test sets. Canonical state is not always an exact span from the current turn. [2.2 format](https://github.com/budzianowski/multiwoz/blob/fe0c8e65cfcd8462bd33c86e35f21addc84ca82b/data/MultiWOZ_2.2/README.md), [license](https://github.com/budzianowski/multiwoz/blob/fe0c8e65cfcd8462bd33c86e35f21addc84ca82b/LICENSE) |
| **xLAM function-calling 60k / APIGen** | **60,000** synthetic examples; JSON `query`, tool schemas and `answers` containing tool names/arguments. Official config exposes one `train` file. | Structured argument extraction and choosing among supplied tool descriptions; possible source for schema variation after an adapter exists. | **CC BY 4.0; currently auto-gated on Hugging Face**, requiring owner account consent/login. No data downloaded. Split by tool/API family as well as example; remove any overlap with held-out tool benchmarks. Do not execute arbitrary included tools or equate valid calls with Friday authority. [Pinned official card](https://huggingface.co/datasets/Salesforce/xlam-function-calling-60k/blob/26d14ebfe18b1f7b524bd39b404b50af5dc97866/README.md) |
| **Berkeley Function Calling Leaderboard (BFCL v4)** | Category JSON/JSONL files plus evaluator/answer resources. At the pinned source, `BFCL_v4_multi_turn_base.json` is **390,425 bytes**; missing-function, missing-parameter and long-context categories are separate files. | **Held-out evaluation/harness design**, especially missing parameters, irrelevance and multi-step state. | Public; repository **Apache 2.0**. Keep benchmark questions/answers out of training, teacher-label generation and Optuna selection. A dataset viewer calling a split `train` does not make an evaluation benchmark a training source. [Official categories](https://github.com/ShishirPatil/gorilla/blob/6ea57973c7a6097fd7c5915698c54c17c5b1b6c8/berkeley-function-call-leaderboard/TEST_CATEGORIES.md), [license](https://github.com/ShishirPatil/gorilla/blob/6ea57973c7a6097fd7c5915698c54c17c5b1b6c8/LICENSE) |
| **AndroidControl** | Over **15,000** demonstrations, **833 apps**, 40 categories. GZIP TFRecords contain `episode_id`, goal, screenshots, accessibility trees, step instructions and JSON actions. Twenty source shards; shard 0 alone is **2,492,987,829 bytes**. | Laya/vision grounding, action choice and app-state harness work; optional typed subgoal extraction with retained observation provenance. | Public GCS. Authors identify **Apache 2.0** and also describe noncommercial distribution in their datasheet; this stage is research/noncommercial. Use official episode/subsplit IDs. Screenshots/actions cannot be dropped directly into the text SFT format. [Official format](https://github.com/google-research/google-research/blob/0159c5e09c10917a962059be5493737cfd611d0f/android_control/README.md), [author datasheet](https://papers.nips.cc/paper_files/paper/2024/file/a79f3ef3b445fd4659f44648f7ea8ffd-Supplemental-Datasets_and_Benchmarks_Track.pdf) |
| **AndroidWorld** | Executable emulator benchmark: **116 task templates across 20 apps**, parameterized fixture setup and persistent-state reward checks. It is not a packaged large demonstration corpus. | End-to-end harness grading, restart/retry checks and actual calendar/note/task outcome verification. | Public, harness **Apache 2.0**; individual apps retain their own source notices. Keep final evaluation task templates/instances separate from training-exposed ones. Language-only scores cannot replace these outcome checks. [Pinned official environment](https://github.com/google-research/android_world/tree/f8e78c0ab0bb8b5daa681afd0296ed0193a98f1f), [license](https://github.com/google-research/android_world/blob/f8e78c0ab0bb8b5daa681afd0296ed0193a98f1f/LICENSE) |

For additional UI sources and existing Friday navigation experiments, see
[navigation data and training](../../research/40-navigation-data-and-training.md).
The [app scenario catalogue](../../research/21-friday-use-case-catalogue.md) remains
separate proposed acceptance work, including spending, holdings and activity.
None of these dialogue corpora supplies authorized CRED, investment-account or
Health Connect data, nor proof that Friday can read those installed apps.

## Immutable source versions

| Source | Revision inspected |
|---|---|
| `google-research-datasets/dstc8-schema-guided-dialogue` | `e852981ae34990f4358979625854259302feaa78` |
| `google-research-datasets/Taskmaster` | `d92cb6af3005f1dc09c39e75e7daf4a04905e00b` |
| `budzianowski/multiwoz` | `fe0c8e65cfcd8462bd33c86e35f21addc84ca82b` |
| `Salesforce/xlam-function-calling-60k` | `26d14ebfe18b1f7b524bd39b404b50af5dc97866` |
| `ShishirPatil/gorilla` | `6ea57973c7a6097fd7c5915698c54c17c5b1b6c8` |
| `google-research/google-research`, AndroidControl documentation | `0159c5e09c10917a962059be5493737cfd611d0f` |
| `google-research/android_world` | `f8e78c0ab0bb8b5daa681afd0296ed0193a98f1f` |

AndroidControl data lives outside its code repository. The inspected GCS shard-0
metadata records generation **`1717537205165914`**, size **2,492,987,829 bytes**, and
CRC32C **`ZUFzIw==`**. A future download must retain each object's generation/checksum
and the exact split files; a documentation commit alone does not pin the data.
Only this small metadata object was fetched here, not the 2.49 GB shard.
[Primary object metadata](https://storage.googleapis.com/storage/v1/b/gresearch/o/android_control%2Fandroid_control-00000-of-00020)

## Downloaded public training samples

Local paths below are relative to `training/slm/out/external/`. The ignored
`download_manifest.json` records immutable URLs, revisions, licenses, bytes, SHA256,
dialogue/turn counts and observed service coverage for all eight downloaded files.
Total source size is **13,945,979 bytes**, including schemas, READMEs and licenses.
No development or test dialogue shard was downloaded.

| Sample | Observed coverage | Bytes | SHA256 |
|---|---|---:|---|
| `sgd/train/dialogues_001.json` | **128 train dialogues / 2,466 turns**, all `Restaurants_1`; not representative of SGD's full 20-domain release | 3,128,575 | `5ce28a6e656bc88c1da29c983d243d5a81eb37de92698da3864d1cf02894ca24` |
| `sgd/train/schema.json` | 26 service schemas | 75,764 | `97b6c21673aa7e5c2d1ceef52aa35170c565223cdcbf33bb24c69739bda04a82` |
| `multiwoz22/data/MultiWOZ_2.2/train/dialogues_001.json` | **512 train dialogues / 7,060 turns**; 217 include hotels, 224 restaurants, 182 trains, 177 attractions, 92 taxis, 4 hospitals and 1 bus; multi-domain counts overlap | 10,678,768 | `895a8109bf01fa5ecf15ccdbd2dfe1628bd923f6b61dcd2e26b10ee5076a1596` |
| `multiwoz22/data/MultiWOZ_2.2/schema.json` | Eight domain schemas | 18,123 | `ae9e2390f38fb967af64623c2f4f7e0c636fb377ad523b582a03161d3ddbdf68` |

These counts were computed from the pinned downloaded JSON files; they are sample
coverage measurements, not model scores. The immutable sources are the
[SGD train shard](https://raw.githubusercontent.com/google-research-datasets/dstc8-schema-guided-dialogue/e852981ae34990f4358979625854259302feaa78/train/dialogues_001.json),
[SGD schema](https://raw.githubusercontent.com/google-research-datasets/dstc8-schema-guided-dialogue/e852981ae34990f4358979625854259302feaa78/train/schema.json),
[MultiWOZ train shard](https://raw.githubusercontent.com/budzianowski/multiwoz/fe0c8e65cfcd8462bd33c86e35f21addc84ca82b/data/MultiWOZ_2.2/train/dialogues_001.json)
and [MultiWOZ schema](https://raw.githubusercontent.com/budzianowski/multiwoz/fe0c8e65cfcd8462bd33c86e35f21addc84ca82b/data/MultiWOZ_2.2/schema.json).

To recreate just those four files from the repo root, using the same ignored paths:

```sh
mkdir -p training/slm/out/external/sgd/train
mkdir -p training/slm/out/external/multiwoz22/data/MultiWOZ_2.2/train
curl --fail --location --output training/slm/out/external/sgd/train/dialogues_001.json \
  https://raw.githubusercontent.com/google-research-datasets/dstc8-schema-guided-dialogue/e852981ae34990f4358979625854259302feaa78/train/dialogues_001.json
curl --fail --location --output training/slm/out/external/sgd/train/schema.json \
  https://raw.githubusercontent.com/google-research-datasets/dstc8-schema-guided-dialogue/e852981ae34990f4358979625854259302feaa78/train/schema.json
curl --fail --location --output training/slm/out/external/multiwoz22/data/MultiWOZ_2.2/train/dialogues_001.json \
  https://raw.githubusercontent.com/budzianowski/multiwoz/fe0c8e65cfcd8462bd33c86e35f21addc84ca82b/data/MultiWOZ_2.2/train/dialogues_001.json
curl --fail --location --output training/slm/out/external/multiwoz22/data/MultiWOZ_2.2/schema.json \
  https://raw.githubusercontent.com/budzianowski/multiwoz/fe0c8e65cfcd8462bd33c86e35f21addc84ca82b/data/MultiWOZ_2.2/schema.json
```

The download manifest also identifies the source README/license files. Retain those
notices and verify the hashes above when moving the samples. Keep downloaded and
adapted rows ignored; commit adapter source, provenance definitions and measured
summaries rather than copying corpora into Git.

## Complete public TRAIN download

[fetch_data.py](fetch_data.py) now reproduces the complete pinned SGD and MultiWOZ
2.2 **train** download. It uses only the standard library, eight workers by default,
and a hard maximum of **1,000,000,000 bytes**. It discovers files from pinned GitHub
trees and selects train dialogue shards, schemas and source README/license files;
no dev/test dialogues are fetched. File content must match the tree's Git blob and
expected byte length. Atomic files and manifest updates support resumption, with
every cached file rechecked against its recorded SHA256 and pinned Git blob.

From the repo root:

```sh
python3 training/slm/fetch_data.py --self-check
python3 -u training/slm/fetch_data.py \
  --output-dir training/slm/out/external-full --workers 8
```

Rerun the same command to verify/reuse completed files and finish an interrupted
download. A second active writer to the same output directory is refused. The
ignored `out/external-full/manifest.json` records the source pins, complete inventory,
fetcher digest, each file's URL/bytes/Git blob/SHA256 and measured domain/turn counts.

**Measured complete download, 21 October 2026:**

| Source | Train shards | Dialogues | All turns | USER turns | Bytes including schemas/notices |
|---|---:|---:|---:|---:|---:|
| SGD | 127 | 16,142 | 329,964 | 164,982 | 439,402,926 |
| MultiWOZ 2.2 | 17 | 8,437 | 113,552 | 56,776 | 171,401,763 |
| **Total** | **144** | **24,579** | **443,516** | **221,758** | **610,804,689** |

The full inventory has **150 files**: 144 dialogue shards plus six schema/README/
license files. SGD's source services include calendar, hotels, banks, media, music,
flights and other domains; MultiWOZ includes **3,369 hotel-containing dialogues**.
Per-service dialogue counts overlap in multi-domain conversations and are retained
in the manifest. These are raw source counts, **not** ready-made Writer training
examples or a model/device success score. The current synthetic pilot is unchanged.

## Proposed adapter for the next stage

No external importer has been connected to the current `make_data.py`/`distill.py`
pilot. The separate proposed **`dialogue_state_v2`** research protocol uses the
previous user state, service schema, preceding system utterance and current user
utterance as inputs; the annotated **current USER frame's state** is the target.
The target and future turns must never enter the prompt. This is a different
interface from Friday's current `task_update` span patch and needs its own adapter,
checker and grouped validation before training or production integration. No
converted-row yield or model result is claimed here yet.

Before external rows become training candidates:

1. Preserve dataset/revision, source file SHA, original split, dialogue ID and turn
   index on every derived row. Group **all turns and paraphrases of one dialogue**
   into the same split. Hash/deduplicate against the existing synthetic splits and
   frozen cases; track shared upstream dialogue IDs across dataset versions.
2. Build only a prefix ending at a user turn. Derive known state from earlier turns
   and current updates from actual annotated user spans. Never expose future turns,
   full-dialogue final state, expected outputs or grader hints in the model input.
3. Match the current Friday schema explicitly. Reject ambiguous/non-unique copied
   spans, unsupported fields and inherited state mislabeled as new owner input.
   MultiWOZ `copy_from` values need retained provenance; they are not automatically
   literal spans of the latest reply. Dates need a trustworthy reference clock;
   do not reinterpret an old “tomorrow” using the download date.
4. Validate normalized labels independently, then run the existing teacher/gold
   gate. Recheck prose separately and bind independent AI review to each generated
   response hash. A source assistant's “booked” is dialogue content, not evidence
   that Friday booked anything.
5. Freeze a separate, dialogue-grouped validation plan and source-balanced mixture
   before training. Keep official test/BFCL/AndroidWorld evaluation material out of
   labels, Optuna and checkpoint selection. Include clarification/correction cases;
   a successful-dialogue-only mixture is insufficient for recovery.

The first small adapter can use a few verified current-turn field updates from
these train shards, with independent holdout dialogues, while leaving the ongoing
synthetic-only experiment unchanged. Public pretrained teacher/student exposure to
widely used dialogue benchmarks is unknown, so even clean new splits do not prove
absence of pretraining contamination.
