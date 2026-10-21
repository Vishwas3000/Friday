# Online datasets for Friday's next research stage

_Checked 21 October 2026. Research/noncommercial use. No external rows entered the current 1,440-candidate synthetic pilot or frozen 84-case regression._

Start with **SGD for retained task state** and **MultiWOZ 2.2 for hotel constraints
and corrections**. Both complete public **TRAIN** splits are downloaded under
ignored `training/slm/out/external-full/`; the two initial sample shards remain in
`out/external/`. A separate human-annotation adapter has prepared **260,562**
`dialogue_state_v2` rows remotely, with an independently verified 50,000-row local subset. This
document records source versions, download counts, adapter gates and limits.
No private app data, model weights or large UI dataset shards were downloaded.

### Local storage update, 22 October 2026

The local public caches `external-full`, `dialogue-v2`, `dialogue-v2-clean`,
`dialogue-v2-sample` and `dialogue-v2-final` are now lossless archives under ignored
`training/slm/out/local-cache-archives/`. Every archive was independently streamed
and checked against every original file's SHA256 before reclaiming its unpacked
cache. Matching JSON manifests retain archive hashes, member hashes and restore
commands. The remote run still uses its original input paths and bytes.

Restore the needed local cache before rerunning a loader or source audit. From
`training/slm/out/`, for example:

```sh
tar -xzf local-cache-archives/external-full.tar.gz
tar -xzf local-cache-archives/dialogue-v2-final.tar.gz
```

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
| **CoEdIT** | Publisher JSONL `src`, `tgt`, `task`, `_id`; 69,071 downloaded TRAIN records across editing tasks. | The focused adapter retains 19,697 filtered grammatical-error-correction reference pairs. | Public, dataset card **Apache 2.0**. Other editing tasks are deferred; lexical canaries do not prove a reference preserves meaning. [Pinned official card](https://huggingface.co/datasets/grammarly/coedit/blob/e9a255c33ef910bc33a9d2b522653fa87521583e/README.md) |
| **DialogSum** | Publisher JSONL `fname`, `dialogue`, `summary`, `topic`; 12,460 downloaded TRAIN records. | The focused adapter retains 2,071 short observer-style summary references. | Public, **CC BY-NC-SA 4.0**. Conservative fact-token filters discard many valid summaries; surviving references remain semantically unreviewed. [Pinned official release](https://github.com/cylnlp/dialogsum/tree/848cc8c05e0a04def79326539de827f1b28786a0) |

For additional UI sources and existing Friday navigation experiments, see
[navigation data and training](../../research/40-navigation-data-and-training.md).
The [app scenario catalogue](../../research/21-friday-use-case-catalogue.md) remains
separate proposed acceptance work, including spending, holdings and activity.
None of these dialogue corpora supplies authorized CRED, investment-account or
Health Connect data, nor proof that Friday can read those installed apps.

## Writing references prepared separately

[WRITING_DATA.md](WRITING_DATA.md) records **81,531 downloaded publisher TRAIN
records** from CoEdIT and DialogSum and the exact source/output hashes. The separate
[writing_data.py](writing_data.py) adapter retains **21,768 reference pairs**:
**19,697 grammar corrections + 2,071 summaries**, grouped into **20,617 future train /
1,151 future validation rows**. Raw and prepared files remain under ignored
`out/writing-external/`; publisher validation/test files were not downloaded.

Publisher targets require semantic screening beyond lexical filters. The active
[prepared-data stage](PREPARED_TRAINING.md) includes 23 independently AI-approved
writing training references and 30 approved validation references after sampled
review. Rejected and unreviewed targets remain excluded following the failed
teacher-filter calibration. These rows remain separate from the completed
synthetic pilot and use a different manifest from `dialogue_state_v2`.
The writing guide supplies the standard-library `--self-check`/`--fetch` commands,
grouped split rules, licenses and measured exclusions.

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
| `grammarly/coedit` | `e9a255c33ef910bc33a9d2b522653fa87521583e` |
| `cylnlp/dialogsum` | `848cc8c05e0a04def79326539de827f1b28786a0` |

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

The verified inventory SHA256 is
`3d5d53e0394fc995090a0291e855eec1122bec34ff07d08f535df5d19f62bf9c`.
The completed local raw manifest SHA256 is
`315b1650323284eaa2c13f5544fa9404e4f794fbb54a1816704d227fd26e9740`.
Manifest metadata can change when download verification is rerun; the adapter
records the exact manifest and source-file hashes used for each prepared dataset.

## Human-annotated dialogue state: next-stage data

[dialogue_data.py](dialogue_data.py) is a standard-library adapter for the separate
**`dialogue_state_v2`** research protocol. It uses existing human state annotations;
there is no teacher labeling or GPU training in this preparation step. These rows
are **not compatible** with the current Writer prompt, `task_update` span-patch
contract or `distill.py` input. The 1,440-candidate pilot and frozen 84 cases remain
unchanged.

| Part | Contents |
|---|---|
| Input | Selected service schema; selected service's previous state; other services' previous states; preceding SYSTEM utterance; current USER utterance |
| Response | Full current annotated USER state: `service`, `active_intent`, `slot_values` (arrays of strings), `requested_slots` |
| Provenance | Dataset/repository/revision/license, official TRAIN source file and SHA256, schema SHA256, dialogue ID, turn index, service/domain, input and target fingerprints |

Every frame in one USER turn reads the **same state snapshot from before that
turn**. The cache updates after all current frames are emitted; SYSTEM frame
annotations never update it. Current frame annotations, span labels and future
turns are excluded from input. State values are retained exactly as annotated;
canonical values and cross-service references need not be literal spans of the
current utterance. Historical annotation errors remain contextual data, and the
prompt states that they are not authoritative schema definitions.

### Reproduce preparation

Preserve the repository layout and first complete the raw download above. From
the repo root, prepare the full available compatible corpus on a machine with
enough disk space:

```sh
python3 training/slm/dialogue_data.py --self-check
python3 -u training/slm/dialogue_data.py \
  --sources training/slm/out/external-full \
  --out training/slm/out/dialogue-v2-full --max-rows 300000
python3 training/slm/verify_dialogue_data.py --self-check
python3 -u training/slm/verify_dialogue_data.py \
  --sources training/slm/out/external-full \
  --prepared training/slm/out/dialogue-v2-full
```

The row limit is a cap, not a request to generate invented examples. The complete
source scan currently finds **260,562 unique, compatible, non-conflicting rows**.
For the independently verified 50,000-row subset, use a new output directory:

```sh
python3 -u training/slm/dialogue_data.py \
  --sources training/slm/out/external-full \
  --out training/slm/out/dialogue-v2-final --max-rows 50000
```

Existing nonempty output directories are refused. Each run writes train/validation
JSONL and a manifest containing adapter/source hashes, exclusions and counts.
The independent standard-library verifier reconstructs labels and prior context
from raw dialogues without importing adapter functions and writes
`independent-check.json`. It binds the exact source manifest and adapter hashes
and checks every row's SYSTEM prompt against the adapter's AST-literal prompt.
Use ordinary Python without `-O`; the audit refuses optimized mode because it
uses assertions.
The default input bound is 8,000 characters, with overlong rows dropped rather
than truncated. The default validation fraction is 0.1. All turns from one dialogue
stay together using a deterministic hash of dataset plus dialogue ID. Identical
full inputs are globally deduplicated across splits; all rows with conflicting
targets for an identical input are dropped. A capped subset balances service
quotas within each split after scanning every source shard.

### Measured full preparation

**21 October 2026:** the full remote `out/dialogue-v2-full/` export completed with
**260,562 rows**, and the corrected independent audit **passed every row**. It
found zero schema, categorical enum, annotation, prior-context or split/input
overlap violations. Maximum observed input size was **6,754 characters**. The corpus
contains **173,668 SGD / 86,894 MultiWOZ rows**. Full JSONL files remain on the remote
training machine because the laptop lacks space for another full copy.

| Full artifact | Rows | Bytes | SHA256 |
|---|---:|---:|---|
| `train.jsonl` | 234,811 | 1,241,846,235 | `95c04227f1db41daa9f4a57355fe47a42c9e94f78cdc83fcc95e4da9a402b7e1` |
| `validation.jsonl` | 25,751 | 135,713,640 | `6015e68e01c83f3f1c57f6c93128f98f9dd29f902f3de42c3d11cd0c9fad875e` |
| **Total** | **260,562** | **1,377,559,875** | — |

The full export includes **41,506 annotated slot-value changes**, **40,536 unchanged
states** and **2,918 resets**. Counts describe source annotations, not model
performance. Dialogue grouping determines the available split sizes; retaining
every compatible row need not produce an exact 90/10 row ratio.

| Full audit artifact | SHA256 |
|---|---|
| Prepared `manifest.json` | `2072410419d07028e6696a2f84825a6c3c70fc154fb1af1e12e29bed0f787786` |
| Remote source `manifest.json` | `f9e12590e08321b983e180029566ac0b90c7881334f313b3a0c45b045d9453eb` |
| `dialogue_data.py` | `fd0ce2cd434677db5046b504c7cb481cf38a45109e67b0a93e54402fdd59dcea` |
| Exact SYSTEM prompt | `ecbb8cf8cacfb1fd9d798cbb255c4a82a62e1b3babb90a996dfa729645230c85` |
| `verify_dialogue_data.py` | `490a33f5eb9453ca6ef393ad534f80cfacb41b8428be728a227162d703b4861c` |
| `independent-check.json` | `3c036b1e75e0eaa11d87396dacd39ac71458c20011131776e357980d6edcc463` |
| Metadata ZIP, 35,507 bytes | `beac658f9f099744d850f25ee94cff32b23c2ff7d14dfcefb6e33e6ca6b5b411` |

The corrected audit binds source bytes, adapter and SYSTEM prompt rather than
trusting self-consistent row hashes alone. Local negative checks rejected a changed
SYSTEM even after row/file hashes were updated, a changed source manifest and a
changed adapter. The remote source manifest differs from the local raw manifest
because download metadata is location/run specific; both retain the pinned source
file identities.

The completed manifest, source manifest, exact adapter/verifier and independent
audit report are backed up under ignored local `out/dialogue-v2-full-metadata/`,
with their digests verified. Metadata is not a backup of the full JSONL files:
preserve those on suitable storage before recycling the remote disk,
or reproduce them using the pinned raw sources and exact adapter. The prepared
JSONL total is larger than the raw downloader's 1 GB bound; that bound applies
only to source downloads.

### Target gates and verified local subset

An emitted target must use the selected schema's declared intent, or `NONE`, and
declared supplied/requested slot names. Categorical values must occur in
`possible_values`, with the exact corpus sentinel `dontcare` allowed for established
no preference. Unknown values are dropped, never rewritten to a plausible value or
a different intent. The sentinel follows the
[official SGD state representation](https://github.com/google-research/google-research/blob/master/schema_guided_dst/README.md).

The full source scan drops **4,207 schema-incompatible frames**, including 30
categorical exceptions, plus **2,680 duplicate rows** and **148 conflicting-input
groups containing 540 rows**. For example, a source `find_taxi` absent from a schema
declaring only `book_taxi` is dropped; it is never converted into booking intent.

**Verified local subset, 21 October 2026:** `out/dialogue-v2-final/` contains
**45,000 train / 5,000 validation rows**, comprising **40,840 SGD / 9,160 MultiWOZ**
rows across 24 dataset/domain groups and 67 split/dataset/service groups. It includes
8,553 annotated slot-value changes, 6,909 unchanged states and 904 resets. These
are annotation counts, not measured model skills.

Two independent checks reconstructed every one of the 50,000 prompts and targets
from original source dialogues without importing adapter functions. The checks
verified all raw shard/schema/prepared-file hashes, before-turn state snapshots,
declared names and categorical values, and found zero dialogue overlap, duplicate
inputs or invalid target enums. Maximum observed input size was **6,709 characters**.
Earlier `dialogue-v2/` and `dialogue-v2-clean/` outputs are quarantined intermediates;
only the final path has this enum-inclusive audit.

| Verified local artifact | SHA256 |
|---|---|
| `dialogue_data.py` | `fd0ce2cd434677db5046b504c7cb481cf38a45109e67b0a93e54402fdd59dcea` |
| `out/dialogue-v2-final/train.jsonl` | `927c3037a09fedd16bff8b209f548381af7edce228c9fdcdd72267672808f213` |
| `out/dialogue-v2-final/validation.jsonl` | `4c8f10804216b17cb503580ec4b88c5183f7667c32b619c0128b1a2f52ba0c30` |
| Local prepared manifest | `20a8a59c18ccce357490ff34118ccd674bc3e80c5846642608194b7c4932bab9` |

The manifest's absolute source location can differ across machines; compare the
recorded source and JSONL hashes as well as the local manifest digest when moving
artifacts. Keep raw/prepared rows and audit reports ignored, with source notices
and manifests in backups. The local 50,000-row output is a sampled subset, separate
from the full remote corpus and its own completed manifest/audit.

### Limits before training or app integration

This is a full-state, schema-guided dataset, not supervision for an arbitrary
Friday UI action or message writer. A separate trainer/checker and explicit
production protocol migration are still needed. Prior states are human gold
annotations (teacher forcing); generated-state rollouts, long-range dialogue
references and recovery under incorrect predicted context remain unmeasured.
Character bounds do not establish tokenizer length or phone memory/latency limits.

Do not treat historical “tomorrow” as relative to the download date. Source
assistant claims and transactional intent are dialogue content, not owner
authorization, private app access or completion evidence. Keep official test,
BFCL and AndroidWorld held-out material out of training and selection; future
mixtures need overlap checks against their own frozen evaluations. Public
pretrained teacher/student exposure to these widely used corpora is unknown, so
dialogue-isolated splits do not prove absence of pretraining contamination.
