# Sampled writing-reference review

_21 October 2026 · AI review, not human annotation · No training or source changes in this review_

## Scope and reproducibility

An independent Codex GPT-6 review compared **80 publisher-reference pairs** with their supplied instruction and source: 20 each from CoEdIT grammar correction and DialogSum summarization, in both local splits. These rows come only from publisher TRAIN data. No frozen tests, model responses, training results or device data influenced selection or decisions.

The source manifest SHA-256 is `7a2e3939bac5a0f611bc2c6b7f44981c5ac143bf911df97e01aeab343b3227d2`. The seed is `friday-writing-review-20261021-v1`. Within each `split|dataset` stratum, take the 20 rows with the lowest hexadecimal SHA-256 of:

```text
seed + NUL + split|dataset + NUL + id + NUL + response_sha256
```

Every sampled source and full target was inspected. Approval requires usable English for the requested task, grounded actors and relationships, preserved qualifiers and action status, and an actual correction or informative brief summary. Summaries may omit nonessential details. Conservative rejections include ambiguous unnecessary changes; human adjudication could reasonably disagree with some judgments.

## Results

| Local split | Dataset/task | Population | Sampled | Approved | Rejected |
|---|---|---:|---:|---:|---:|
| Train | CoEdIT grammar correction | 18,666 | 20 | 14 | 6 |
| Train | DialogSum summary | 1,951 | 20 | 9 | 11 |
| Validation | CoEdIT grammar correction | 1,031 | 20 | 16 | 4 |
| Validation | DialogSum summary | 120 | 20 | 14 | 6 |
| **Total** | | **21,768** | **80** | **53** | **27** |

The equal quotas deliberately overrepresent summaries and validation rows. **53/80 is a sample judgment count, not a corpus-wide quality estimate.** The other **21,688 rows remain semantically unreviewed**.

Repeated problems survived the adapter's lexical filters and exact publisher-reference reconstruction:

| Example row IDs | Observed reference problem |
|---|---|
| `WEXT-dialogsum-train_10644-10645`, `WEXT-dialogsum-train_9330-9331` | Rental/purchase inquiries become completed transactions without completion evidence. |
| `WEXT-dialogsum-train_1597-1598`, `WEXT-dialogsum-train_8555-8556`, `WEXT-dialogsum-train_2253-2254` | Advice, service explanation or medical results are assigned to the wrong speaker/recipient. |
| `WEXT-dialogsum-train_2897-2898` | Explicit part-time work is contradicted by a claim of no work experience. |
| `WEXT-dialogsum-train_568-569`, `WEXT-dialogsum-train_12125-12126` | A qualification is removed, or the requested item changes and a stock constraint disappears. |
| `WEXT-coedit-15472-15472`, `WEXT-coedit-3583-3583`, `WEXT-coedit-14525-14525`, `WEXT-coedit-17764-17764` | Correction targets contain a broken phrase, introduced sentence fragment or duplicated/incompatible verbs. |
| `WEXT-coedit-287-287`, `WEXT-coedit-1992-1992` | A rewrite changes the profitability referent or replaces working age with employability. |

Some rejections are conservative judgments about unsupported definiteness/tense changes, incomplete correction or inadequate summary coverage. Each decision has its own concrete reason; these are not all equally severe failures. Approved summaries can omit secondary details, and an approved grammar reference does not validate the source's scientific, medical or social assertions.

## Training handoff

The ignored machine-readable artifact is `training/slm/out/writing-external/prepared/semantic-sample-review.json`, SHA-256 **`6a95125d0da28bf033fc977f702e93f06658940e5249d85b17575d8ab4892f05`**. It contains `version`, `reviewer`, `source_manifest_sha256`, sampling metadata and all **80** entries in `reviews`, keyed by row ID. Every entry includes the exact `input_sha256`, `response_sha256`, `approved` Boolean and reason. Response hashes were recomputed and matched; input hashes are the independently source-audited provenance values.

The training loader should match all three identities and exclude the **27 rejected sampled targets from both training and validation**. Unreviewed rows must remain identified as unreviewed. Existing source or target strings were not edited, and this review did not select a model, checkpoint or hyperparameter.

Repeated actor/completion errors in summaries and malformed grammar targets justify stronger reference screening before a larger SFT run. A bounded research run can measure whether these data help, with retained provenance and an independent unseen writing evaluation. Exact lexical checks and source reconstruction establish construction and provenance; the present single AI review does not certify all reference semantics, provide human approval, or demonstrate Friday app execution.
