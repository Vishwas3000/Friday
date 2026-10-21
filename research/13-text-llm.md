# Friday's Writer: a small on-device LLM for text only

_Last updated: 2026-10-17 · Trust tags: [primary] read at source · [secondary] search/blog/forum/community measurement · [measured] run here · [UNCERTAIN] unconfirmed or conflicting_

**Question.** Laya chooses but can't write. Friday still needs text:
- message bodies, replies and short emails;
- reminder titles;
- cleaned-up search queries;
- one-line answers read off the screen.

**Follow-up (2026-10-20):** the local Writer is now implemented and installed: [app guide](../docs/writer.md), [actual phone results](33-writer-live-tests.md), [evaluation plan](31-slm-text-generation.md). The initial suite exposed quality failures and retesting of refinements is pending. This note's older runtime comparisons and borrowed speed figures remain historical; current code uses ORT 1.31. D-058 keeps active support English only.

The owner decided on 2026-10-17 to add a lightweight on-device LLM for these text outputs only, under four rules:
- Laya keeps every decision: routing, slots, apps and UI steps (D-024).
- The LLM loads on demand, is freed after, and is never resident with Laya (D-016).
- Every draft goes through the Run / Skip / Stop card before it is typed or sent (D-025).
- This lifts D-021's "no generative LLM near-term" for text outputs only. It still needs a decision entry (D-0xx; DECISIONS.md is not edited here).

**Method.**
- **Web research:** two sub-agents covered candidate models and Android runtimes, reading HF API/model cards, release notes, Maven POMs and community measurements. Sources are at the end.
- **Mac quality check [measured]:** Qwen3.5-0.8B was tested end to end:
  - bf16, an 8-bit copy and two 4-bit copies, on transformers 5.20 / torch 2.14.1 on MPS;
  - the published Q4_K_M GGUF, on llama.cpp b11701 (official macOS build), CPU, 4 threads.
- **Stopped:** Qwen3-1.7B and Falcon-H1-1.5B-Deep were cut off mid-download. The Mac was then ruled out for model runs: 16 GB, swapping, disk 97% full.
- **Tokenizer-only checks:** those two models and LFM2.5.
- **Where the harness lives:** `writer_prompts.py`, `writer_eval.py` and `writer_gguf.py` stayed in the session scratchpad, not in the repo.
- **Phone:** nothing ran on it (unplugged). See [To measure on the Pixel 8](#to-measure-on-the-pixel-8).

## TL;DR
- **Pick: llama.cpp (our own small JNI library, built by the NDK from a pinned release) + LFM2.5-1.2B-Instruct, the `QAD-Q4_0` GGUF.**
  - 696 MB, LFM Open License v1.0, ungated.
  - **Why llama.cpp:**
    - It decoded fastest, or within ~10% of ExecuTorch, in the one third-party head-to-head on a Tensor phone (§3.1).
    - It runs every candidate model, including the hybrid Qwen3.5 and LFM2 families.
    - It has GBNF grammars and JSON-schema → grammar conversion, so JSON can be forced.
    - It is MIT-licensed and releases daily.
    - It adds ~6.5 MB of stripped native code for text only [estimate from the release `.so` sizes].
    - It shares no native library with ONNX Runtime, so it can't clash with `onnxruntime-android` 1.20.0.
    - Swapping the model is a file change.
    - On a Mali GPU every LLM path is broken, slower or unsupported, so CPU-only is no loss (§3).
  - **Why LFM2.5-1.2B:**
    - It is the strongest instruction-follower that fits ~1 GB. In the same vendor harness it scores IFEval 86.2 / IFBench 47.3 / Multi-IF 61.0, against Qwen3.5-0.8B's 59.9 / 22.9 / 41.7 and Qwen3-1.7B's 73.7 / 21.3 / 56.5 [primary, vendor].
    - It is already measured on the Pixel 8's SoC with llama.cpp [secondary, crowd-sourced PocketPal]:
      - Q4_0 on a Pixel 8a: 134 tok/s prefill, 22.2 tok/s decode;
      - Q4_K_M on a Pixel 8: 91 / 22.3.
    - The vendor measured 719 MB of RAM with llama.cpp Q4_0 on a phone [primary, vendor].
    - Its QAD (quantization-aware distillation) Q4_0 file keeps 97.4% of bf16 quality [primary, vendor].
- **Runner-up: Qwen3.5-0.8B, Q8_0 GGUF (812 MB, Apache-2.0, ungated), on the same llama.cpp.**
  - It is the only model we measured, and it got the language and script right for Hindi and Hinglish.
  - Its English drafts were weak:
    - 2 of 6 sendable as-is (bf16 = 8-bit, byte-identical);
    - every polite decline came out wrong in some way: no clear "no", no reason, or, at 4-bit, the opposite ("I will join the call at 3 pm today").
  - Ship it instead if the Pixel 8 A/B shows LFM2.5 can't handle the owner's Hinglish, or if Apache-2.0 becomes a requirement.
- **Hindi caveat for the pick:**
  - LFM2.5 doesn't list Hindi [primary]. Its tokenizer spends 80 tokens on a Devanagari line that Qwen3.5 encodes in 30, but only 32 on the same message in Hinglish (Qwen3.5: 29) [measured].
  - Until the A/B says otherwise, a Devanagari request skips the Writer and uses the owner's own words (the current rule path).
- **Expected on the Pixel 8 [UNCERTAIN until measured in Friday itself]:**
  - ~0.75–0.9 GB RAM;
  - prefill ~90–135 tok/s;
  - decode ~22 tok/s (the crowd-sourced Tensor G3 rows above);
  - so a ~150-token prompt with a ~40-token draft takes about 3 s once loaded, plus ~1–2 s to load.
- **The prompt matters more than the precision [measured]:**
  - Building the prompt per kind (chat / reply / email) and choosing the language by rule raised the 0.8B from 20 to 24 of 32. It also removed subject lines from chats and English replies to Hindi requests.
  - 8-bit was byte-identical to bf16.
  - 4-bit damage was random. One 4-bit variant echoed the command back in both Hindi cases; the real Q4_K_M flipped a decline into an acceptance.

## 1. What the Writer must do
| Job | Owner's request (+ context Friday already has) | Writer output | Who does the rest |
|---|---|---|---|
| Message body | "text mom I'll be late" | "Hi Mom, I'll be late today." | Recipient and app come from rules (`messageParts`, `AppResolver`) |
| Reply | "reply to rahul that I agree" + Rahul's last message from the screen | "Sounds good, Friday works for me!" | Laya opens the chat; INPUT_TEXT types it |
| Short email | "email my manager asking for leave tomorrow" | `{"subject": …, "body": …}` (JSON, §6.4) | `ACTION_SENDTO` with `EXTRA_SUBJECT` / `EXTRA_TEXT` (existing `email()`) |
| Reminder title | "remind me to pay the electricity bill tomorrow at 9" | `{"title": "Pay the electricity bill"}` | Date and time stay with `Slots` (rules) |
| Search-query cleanup | "search for that biryani place near koramangala open now" | "biryani Koramangala open now" | Web-search intent |
| One-line answer | "how much data is left?" + the screen's texts | "2.1 GB left, valid till 24 Oct." | Shown on the answer card. Money answers are shown, not logged (D-026) |

**Budget:**
- ≤ ~1 GB RAM, loaded on demand, never resident with Laya (D-016).
- Output ≤ ~120 tokens.
- Prompts are ~120–300 tokens; screen answers reach ~1,000.
- English first; Hinglish and Hindi ideally.
- Nothing is sent without the card.

## 2. Candidate models (≤ ~1.5B params, October 2026)
- All rows are [primary] from HF API, model cards and vendor blogs, unless tagged.
- IFEval / IFBench / Multi-IF use **one** harness: Liquid's table on the LFM2.5 cards. Multi-IF covers 8 languages, including Hindi.
- Vendor-reported numbers from other harnesses are in brackets.

| Model (HF repo) | Params | License · HF gate | Hindi listed? | IFEval / IFBench / Multi-IF | GGUF Q4 / Q8 (MB) | Notes |
|---|---|---|---|---|---|---|
| **LiquidAI/LFM2.5-1.2B-Instruct** | 1.17B (2026-01) | LFM Open License v1.0 (Apache-style; commercial rights only below $10M annual revenue) · open | No (en ar zh fr de ja ko es) | **86.2 / 47.3 / 61.0** | QAD-Q4_0 696 · Q4_K_M 731 / Q8_0 1,246 | 10 short-convolution + 6 GQA layers, 65K vocab, 32K context; official GGUF and ONNX; LiteRT-LM via community |
| **Qwen/Qwen3.5-0.8B** | 0.75B text + vision tower (2026-03) | Apache-2.0 · open | "201 languages" | 59.9 / 22.9 / 41.7 (Qwen non-thinking IFEval: 52.1) | Q4_K_M 533 / Q8_0 812 | 18 Gated DeltaNet + 6 attention layers; vision-language checkpoint, text-only use; non-thinking by default |
| Qwen/Qwen3.5-2B | ~1.9B LM + 0.33B vision | Apache-2.0 · open | yes ("201") | (Qwen non-thinking IFEval 61.2) | Q4_K_M 1,271–1,396 / Q8_0 2,012 | Best ≤2B on Hinglish in Indi-RomCoM [secondary]. **Over budget** (~1.5 GB RAM) |
| Qwen/Qwen3-1.7B | 2.03B (2025-04) | Apache-2.0 · open | Yes (119 languages) | 73.7 / 21.3 / 56.5 | Q4_K_M 1,107 / Q8_0 1,834 | Vendor: 1,306 MB RAM and 40 tok/s on an S25 Ultra (llama.cpp Q4_0). Devanagari costs 2× the tokens (§4.4). **Over budget** |
| Qwen/Qwen3-0.6B | 0.75B | Apache-2.0 · open | Yes | (TII harness IFEval 62.2) | 397–484 / 639–805 | Thinking on by default |
| google/gemma-3-1b-it | 1.0B | Gemma Terms · **gated** | No ("English" for 1B) | 63.3 / 20.5 / 44.3 (Google: 80.2) | 806 / 1,069 | LiteRT-LM's home model |
| meta-llama/Llama-3.2-1B-Instruct | 1.24B | Llama 3.2 Community · **gated** | Yes | 52.4 / 15.9 / 30.2 | 808 / 1,321 | Hindi near chance on MILU (27.0) [secondary] |
| ibm-granite/granite-4.0-h-1b | 1.46B | Apache-2.0 · open | No (12 languages) | (IBM IFEval 78.5) | 901 / 1,559 | Mamba2 hybrid; 5–10 tok/s decode on a Pixel 8a CPU (LiteRT) [secondary] |
| tiiuae/Falcon-H1-1.5B-Deep-Instruct | 1.55B | Falcon LLM License (Apache-based + usage policy) · open | Family lists Hindi | (TII IFEval 83.5) | 938 / 1,658 | 66 parallel attention + Mamba2 layers: slow decode. No ORT-GenAI support |
| HuggingFaceTB/SmolLM2-1.7B-Instruct | 1.71B | Apache-2.0 · open | No | (HF IFEval 56.7) | 1,056 / 1,820 | English only |
| google/gemma-4-E2B-it | 5.1B incl. per-layer embeddings | **Apache-2.0** · open (new for Gemma) | 140+ pretrained | (Liquid Multi-IF 69.4) | Q4_K_M 3.1–3.5 GB | **Over budget**: 1.36–1.73 GB CPU peak on an S26 Ultra |

**Picks by axis.**
- **English instruction following:** LFM2.5-1.2B, then granite-4.0-h-1b and Falcon-H1-1.5B-Deep.
- **Hindi:** Qwen3.5-2B (over budget), then Qwen3-1.7B (over budget), then Qwen3.5-0.8B.
- **Licence:** the Qwen3.5 and Qwen3 models, Granite and SmolLM2 are Apache-2.0. LFM (revenue cap), Falcon (usage policy) and Gemma 3 / Llama (gated custom terms) are not.

No published evaluation scores ≤2B models on drafting messages or emails, or on Hindi generation; the closest proxies are Multi-IF and Indi-RomCoM (arXiv 2606.30790) [secondary].

## 3. Runtimes for a Kotlin app on the Pixel 8
| Runtime | How it gets into Friday | Pixel 8 / Tensor G3 evidence | Forced JSON | Clash with ORT 1.20.0? | Verdict |
|---|---|---|---|---|---|
| **llama.cpp** (MIT) | **No official AAR or Maven artifact** [primary]. Build a pinned tag with NDK/CMake plus a ~150-line JNI (§6.1). The official `llama-b11701-bin-android-arm64.tar.gz` (76 MB) holds CLI tools and unstripped, 16 KB-aligned `.so` files: libllama 37.7 MB, ~3.8 MB stripped; one CPU variant ~1 MB stripped [primary]. Text-only footprint ~6.5 MB stripped, ~12.5 MB with libllama-common [estimate]. Upstream's `examples/llama.android/lib` (Arm's `com.arm.aichat`, rewritten 2025-12) has a Kotlin `InferenceEngine` but no grammar, hard-codes 8,192 context, needs minSdk 33 and NDK 29, and has open infinite-generation bugs (#18432, #18476) [primary] | Crowd-sourced (PocketPal, llama.cpp, 7 threads) [secondary]: Pixel 8a LFM2.5-1.2B Q4_0 134 / 22.2 tok/s; Pixel 8 Gemma 3 1B Q4_0 141 / 24.9. Vendor: LFM2.5-1.2B Q4_0 on an S25 Ultra 335 / 70 tok/s at 719 MB [primary]. Q4_0 weights are repacked at load for i8mm (Pixel 8 phi2 load went 9.4 → 1.5 s with that patch, PR #9921) [primary] | **Yes:** `llama_sampler_init_grammar(vocab, gbnf, "root")`; JSON schema → GBNF via `common/json-schema-to-grammar.h` or the Python script [primary]. Needs one extra JNI argument | **No:** own `.so` files, no ORT symbols. Share the NDK r28 `libc++_shared.so` Friday already ships, or link it statically | **Pick** |
| LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android` **0.18.0**, 2026-10-06, Apache-2.0) | One Gradle dependency. The AAR is 20.9 MB, one 22.2 MB arm64 `.so`; minSdk 24. Its POM needs **kotlin-reflect 2.4.0**, so Kotlin ≥ 2.3; it also needs an R8 keep rule. Kotlin `Engine` / `Conversation` / `Flow` API; `initialize()` can take "up to 10 seconds" [primary] | Pixel 8a CPU, community cards [secondary]: LFM2.5-1.2B int8 143 / 20.4 tok/s, TTFT 1.48 s; Qwen3.5-0.8B int8 50–134 / 8.0–13.6. RAM runs high: Qwen3.5-0.8B CPU peak 2.0 GB on an S26; LFM2-2.6B int8 3.66 GB on a Pixel 8a. GPU (OpenCL) on Mali-G715: mixed reports (works for Gemma 4 per a Google collaborator; missing-library, corrupted-output and queue errors in other issues); Qwen3.5-0.8B's GPU engine didn't fit on a Pixel 8a. NPU is Tensor G5/G6 only [primary] | **Yes:** `ResponseFormat.json(schema)` / `.regex(...)`, with llguidance compiled into the `.so` [primary]. Exact Kotlin wiring [UNCERTAIN] | No (one self-contained `.so`) [UNCERTAIN, unchecked] | **Runner-up runtime:** no native build and a Kotlin API, but a bigger APK, higher RAM, community-converted model files (Qwen3.5 re-quantized 2026-10-12), and LFM2 needs a fresh Engine per conversation (state bug, #3165) |
| MediaPipe LLM Inference (`tasks-genai` 0.10.35) | 42.4 MB AAR | none for Pixel | No | No | **Dead end:** "maintenance-only … migrate to LiteRT-LM" [primary] |
| ONNX Runtime GenAI (v0.17.0, 2026-09-28) | **Not on Maven Central.** A GitHub-release AAR (21.5 MB) added with `files(...)`. It doesn't bundle `libonnxruntime.so`: it `dlopen`s Friday's [primary]. Its Java API has `Model` / `Tokenizer` / `Generator` | Pixel 9 Pro XL ORT CPU in §3.1. No Pixel 8 LLM numbers | **No on Android:** guidance (llguidance) is a build option that defaults off, and `GeneratorParams` in Java has no guidance method [primary] | **Not at Gradle level**, since nothing is upgraded. **At runtime it's a risk:** genai 0.15–0.17 are built against ORT 1.26 and call newer API entries unguarded (the version floor is 18), and its model builder emits opset 22 while ORT 1.20 stops at 21. genai ≥ 0.15 also adds 1DS telemetry (`libmat.so`, 28.6 MB); ORT ≥ 1.29 adds the INTERNET permission [primary; crash risk UNCERTAIN] | Not worth touching Laya's runtime (D-023) |
| ExecuTorch (`executorch-android` 1.5.1, BSD-3) | 7.6 MB AAR; experimental `LlmModule`; no chat-template helper; `.pte` export per model on a Linux box | Pixel 9 Pro XL CPU in §3.1; Vulkan on Mali decodes at half its CPU speed [primary] | Not checked | No | **Qwen3.5 exports only in fp32** ("q8da4w … intentionally deferred") [primary]; per-model export work |
| MNN-LLM (Apache-2.0, 3.6.1) | No Maven artifact; prebuilt 6.2 MB zip; **C++ API only** | Exynos 1580: Llama-3.2-1B decode MNN 21.8 vs llama.cpp 17.5 tok/s (MNN-AECS paper) [primary] | No grammar found [UNCERTAIN] | No | Fast, but no Kotlin layer; we'd port MNN Chat's JNI |
| Cactus, GenieX (ex-Nexa), MLC LLM | — | — | — | — | **Dead ends:** Cactus has a custom licence (free only for individuals' non-commercial use or tiny companies); GenieX is Snapdragon-only; MLC is GPU-only and slower than the CPU on Mali, and Llama 3.2 crashes on a Pixel 8 [primary] |

**GPU on the Pixel 8 (Mali-G715): plan for the CPU.**
- llama.cpp's OpenCL backend refuses non-Adreno/Intel GPUs ("Unsupported GPU Family").
- llama.cpp's Vulkan backend has open Mali bugs: wrong results at subgroup 16, device lost, hangs in the background, ~15× slower than the CPU.
- On a Tensor G4 (same GPU), llama.cpp's GPU path decoded Qwen3-0.6B at 24–31 tok/s against 46.5 on the CPU [primary, issues + arXiv 2605.08195].

### 3.1 Same-phone comparisons
**Runtimes on a Pixel 9 Pro XL (Tensor G4; CPU, 4 threads; 256-token prompt, 256 generated)** [primary, arXiv 2605.08195, Table 5]. Prefill / decode in tok/s:

| Runtime | Qwen3-0.6B | Llama-3.2-1B |
|---|---|---|
| llama.cpp Q4_0 | 241 / **46.5** | 158–202 / 29.6–30.9 |
| ExecuTorch XNNPACK 8da4w | 169–380 / 37–42 | 236–297 / 31–35 |
| ORT CPU | 144–286 / 26.5–35 | 133–268 / 22.6–39.4 |
| LiteRT (the paper's own export) | 96–110 / 9.5–10.3 | 94–118 / 20–21.7 |

The authors are the ExecuTorch team. The Tensor G4's CPU is roughly a G3 with faster cores, so expect the Pixel 8 to land lower.

**llama.cpp on Tensor G3** (PocketPal leaderboard: llama.rn, i.e. llama.cpp; pp512 / tg128; 7 threads). "mem" is the whole React Native app's peak PSS [secondary, crowd-sourced]:

| Phone | Model, quant | Prefill tok/s | Decode tok/s | App PSS |
|---|---|---:|---:|---:|
| Pixel 8a | **LFM2.5-1.2B Q4_0** | 134.2 | **22.2** | 1.13 GB |
| Pixel 8 | LFM2.5-1.2B Q4_K_M | 91.4 | 22.3 | 1.58 GB |
| Pixel 8 | Gemma 3 1B Q4_0 | 141.2 | 24.9 | 1.08 GB |
| Pixel 8 | Gemma 3 1B Q8_0 | 94.0 | 10.0 | 1.35 GB |
| Pixel 8 | Llama 3.2 1B Q8_0 | 63.6 | 9.4 | 1.59 GB |
| Pixel 8 | Qwen2.5 1.5B Q8_0 | 54.0 | 8.8 | 2.05 GB |

- Q4_0 prefills fastest, because of its ARM repack; K- and IQ-quants prefill slower.
- Q8_0 roughly halves decode against Q4_0 at the same size.
- No Qwen3.5 rows exist yet.

## 4. Quality check on the Mac [measured]
**Prompts.**
- 8 invented requests from an invented owner, "Arjun": 6 English, 1 Hinglish, 1 Hindi.
- Two of them carry the other person's last message, as Friday would read it off the screen.
- Greedy decoding, thinking off.

| id | Kind | Request |
|---|---|---|
| late_mom | chat | write a message to mom saying I'll be late tonight, but nicely |
| leave_email | email | write an email to my manager Priya asking for leave tomorrow |
| agree_rahul | reply to "Shall we move the team lunch to Friday?" | reply to rahul that I agree |
| bday_ananya | chat | text ananya happy birthday, keep it short and fun |
| plumber | chat | tell suresh I can't make it at 5 today, ask if 7 works |
| decline_neha | reply to "Can you join the client call at 3 pm today?" | reply to neha no politely, I have a doctor's appointment |
| hinglish_papa | chat | papa ko message karo ki main 8 baje tak ghar pahunch jaunga, traffic bahut hai |
| hindi_maa | chat | माँ को मैसेज करो कि मैंने खाना खा लिया है, चिंता मत करना |

**Prompt v2 (the one to ship):**
- A 3-sentence system prompt: write as the owner, use only the given facts, output only the message.
- A user turn built per kind: chat, reply (with their last message) or email.
- The language set by rule, not guessed:
  - any Devanagari character → Hindi;
  - ≥ 2 common Hindi function words (`ko`, `karo`, `hai`, `ki`, `tak`, …) → Hinglish;
  - otherwise English.
- This is ~120–146 tokens per prompt.
- **v1** was one generic prompt that described the email format for every request.

**Scoring by hand.** One point each for:
- **I** (intent): every point the owner gave is there, none contradicted;
- **N** (natural): sounds like a person, in the asked tone and in the request's language and script;
- **C** (concise): a chat has 1–3 sentences with no subject or signature; an email has subject + greeting + ≤ 4 sentences + sign-off;
- **F** (faithful): no invented fact, reason, promise or request.

The verdict is whether the owner would **send** it as-is, **edit** it, or need a **redo**. The card has no edit button, so "edit" means the owner types it.

### 4.1 Results: Qwen3.5-0.8B
| id | v1 prompt, bf16 | v2 bf16 = 8-bit | v2 4-bit RTN (Q4_0-like) | v2 Q4_K_M emulated | v2 **Q4_K_M GGUF, llama.cpp** |
|---|---|---|---|---|---|
| late_mom | 2 edit | 2 edit | 3 edit | 3 edit | 3 edit |
| leave_email | 4 send | 2 edit | 3 edit | 3 edit | 1 redo |
| agree_rahul | 2 edit | 4 send | 4 send | 4 send | 4 send |
| bday_ananya | 4 send | 4 send | 4 send | 4 send | 4 send |
| plumber | 3 edit | 3 edit | 2 redo | 4 send | 4 send |
| decline_neha | 0 redo | 1 redo | 3 edit | 3 edit | **2 redo (opposite)** |
| hinglish_papa | 3 edit | 4 send | 2 redo (echo) | 4 send | 4 send |
| hindi_maa | 2 redo | 4 send | 2 redo (echo) | 4 send | 4 send |
| **Total / 32 · send-edit-redo** | **20** · 2-4-2 | **24** · 4-3-1 | **23** · 2-3-3 | **29** · 5-3-0 | **26** · 5-1-2 |

**Example outputs, verbatim:**
- **v1, generic prompt:** `Subject: Late message to Mom / Hi Mom, I'll be late tonight but I'll try to be nice.`
  - It answered the Devanagari request in English: `Hi Maa, I have eaten the food. Don't worry.`
- **v2 bf16:**
  - `I'll be late tonight, but I'll make it up to you nicely.` (an invented promise, and "nicely" taken literally)
  - `I can't make it at 5 today, but 7 works.` (the question became a statement)
  - `I am sorry, but I have a doctor's appointment that needs to be scheduled.` (no "no"; the reason is garbled)
  - `Traffic bahut hai, toh main 8 baje tak ghar pahunch jaunga.`
  - `मैंने खाना खा लिया है, चिंता मत करना।`
- **4-bit RTN** echoed the command: `Papa ko message karo ki main 8 baje tak ghar pahunch jaunga, traffic bahut hai` and `आपको मैसेज लिखो कि मैंने खाना खा लिया है…`
- **Q4_K_M GGUF:**
  - `I will join the call at 3 pm today.` (the opposite of the request)
  - `I am writing to ask if you would be available tomorrow for my leave request. Please let me know your availability.`

### 4.2 What it shows
1. **Prompt and rules beat model tricks.** Per-kind prompts with the language chosen by rule: 20 → 24 of 32. This fixed subject lines in chats, an email-shaped reply and the wrong language for Hindi. Keep these rules in the Writer whichever model ships.
2. **8-bit is free.** 8-bit RTN gave byte-identical text to bf16 on 8 of 8 prompts.
3. **4-bit is a lottery at 0.8B.**
   - The scores (23, 29, 26) move both ways around bf16's 24, because single greedy samples flip on small numeric changes.
   - The failures are real, though:
     - the crude 4-bit echoed the command in both Hindi cases;
     - the published Q4_K_M turned a decline into an acceptance.
   - For a 0.8B model, use Q8_0. For 1B+ models, prefer QAD or Q4_K_M files and test them on the 8 prompts.
4. **The 0.8B's steady weaknesses, whatever the precision:**
   - "decline + reason" is never fully right;
   - tone words are taken literally ("make it nicely");
   - emails get filler lines that ask for things ("let me know your availability").
5. **Hindi and Hinglish, with the v2 prompt, were right** (language, script, meaning) in 4 of 5 configurations: bf16, 8-bit and both Q4_K_M. The crude 4-bit echoed the command. With the v1 prompt, the Hindi request got an English reply.
6. **Costs on the Mac** (M5, llama.cpp CPU, 4 threads, Q4_K_M): 430–600 tok/s prefill and 113–118 tok/s decode, so drafts take under a second there. The phone will be several times slower (§5).

### 4.3 Not tested here
Qwen3-1.7B, Falcon-H1-1.5B-Deep, LFM2.5-1.2B, Qwen3.5-2B and Gemma 3 1B were not tested here: the Mac was ruled out for model runs before they ran. The pick therefore rests on vendor and community evidence until the Pixel 8 A/B.

### 4.4 Tokens per message [measured, tokenizers only]
Decode time scales with output tokens, so a tokenizer that splits Devanagari finely makes Hindi drafts slower.

| Tokenizer (vocab) | English (85 chars) | Hinglish (83) | Hindi, Devanagari (65) |
|---|---:|---:|---:|
| Qwen3.5 (248k) | 24 | 29 | **30** |
| Qwen3 (151k) | 24 | 30 | 64 |
| Falcon-H1 (65k) | 26 | 29 | 32 |
| LFM2.5 (65k) | 24 | 32 | **80** |
| Gemma 2, Laya's own (256k; proxy for Gemma 3) | 26 | 27 | 27 |

### 4.5 Memory per token, from the configs [measured arithmetic]
| Model | KV cache (f16) | Fixed recurrent state | At 1,024 tokens |
|---|---|---|---|
| Qwen3.5-0.8B (6 of 24 layers use attention) | 12 KB/token | 18 MB (DeltaNet, f32) | ~30 MB |
| LFM2.5-1.2B (6 GQA of 16 layers; 8 KV heads × 64) | 12 KB/token | tiny (10 short-conv layers, 3-token cache) | ~13 MB |
| Falcon-H1-1.5B-Deep (66 layers, attention + Mamba2) | 66 KB/token | 99 MB (SSM) | ~165 MB |
| Qwen3-1.7B (28 attention layers) | 112 KB/token | — | ~112 MB |

Hybrid models keep the KV cache negligible even for 1,000-token screen-text prompts, so weights dominate RAM.

## 5. What it should cost on the Pixel 8 [UNCERTAIN until measured]
| | LFM2.5-1.2B QAD-Q4_0 (pick) | Qwen3.5-0.8B Q8_0 (runner-up) |
|---|---|---|
| File | 696 MB | 812 MB |
| RAM while drafting | ~0.75–0.9 GB: vendor 719 MB on an S25 Ultra at 1K prefill [primary]. Repack copies weights to malloc'd memory, so RSS ≈ file size + KV + buffers [primary code; inference] | ~0.9–1.0 GB: weights + 30 MB state + compute buffers [estimate] |
| Prefill | **~90–135 tok/s**: llama.cpp on Tensor G3 (Pixel 8a Q4_0 134; Pixel 8 Q4_K_M 91) [secondary, PocketPal] | ~50–130 tok/s: LiteRT int8 on a Pixel 8a 50–134 [secondary]; llama.cpp Q8_0 on 1B models 64–94 [secondary] |
| Decode | **~22 tok/s** (Pixel 8a 22.2, Pixel 8 22.3) [secondary, PocketPal] | **~8–14 tok/s** (LiteRT int8 8.0–13.6 on a Pixel 8a; llama.cpp Q8_0 runs at ~half of Q4_0) [secondary] |
| A typical draft (≈150-token prompt + 40 tokens) | ≈ 1.1–1.6 s + 1.8 s ≈ **3–3.5 s** | ≈ 1.2–3 s + 3–5 s ≈ **4–8 s** |
| Load (cold, mmap + Q4_0 repack) | ~1–2 s: a 2.7B Q4_0 (phi2) loaded in 1.5 s on a Pixel 8 after the repack patch [primary, PR #9921] | ~1–2 s [estimate] |

**Order inside a message request:**
1. Compose the draft first, before any Laya step.
2. Free the LLM.
3. Load Laya (D-030 cold load: ~23 s).

If Laya is warm when the Writer is needed, Friday frees Laya first (D-016) and pays its reload later. Intents that prefill text (`ACTION_SENDTO`, `sms_body`) avoid that reload.

## 6. Recommendation
**Writer = llama.cpp (JNI, pinned b11701) + LFM2.5-1.2B-Instruct-QAD-Q4_0.gguf.**
- **Runner-up:** the same runtime with Qwen3.5-0.8B-Q8_0.gguf. It is Apache-2.0, measured here, better for Hindi and weaker in English.
- **Shipping gate:** run the A/B in [To measure](#to-measure-on-the-pixel-8) first, on the phone, with the 7 non-Devanagari prompts. Devanagari requests bypass the Writer under the pick.
  - Ship LFM2.5 if it sends at least as many drafts as-is as Qwen3.5-0.8B (3 of 7 here, bf16 = 8-bit) and gets the Hinglish one right. On a tie, its ~2× decode speed decides.
  - Otherwise ship Qwen3.5-0.8B.

### 6.1 Build path (no Maven AAR needed)
1. **Pin the source:** add llama.cpp at tag `b11701` (2026-10-16) as a git submodule, e.g. `android/third_party/llama.cpp`, so Mac and Windows builds get the same code.
2. **CMake, at `app/src/main/cpp/CMakeLists.txt`:**
   - `add_subdirectory(<llama.cpp> build-llama)`, then
   - `add_library(writer SHARED writer_jni.cpp)`, linked to `llama`, `android` and `log`.
   - Flags, following upstream's Android module:
     - `-DBUILD_SHARED_LIBS=OFF` (one `libwriter.so`);
     - `-DGGML_NATIVE=OFF` **plus** `-DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+i8mm+fp16`. Every Tensor G3 core (X3, A715, A510) is Armv9.0 with I8MM, DotProd, FP16 and SVE2, and no SME [primary].
       - Without the arch flag, ggml builds for baseline ARMv8 and skips its dotprod/i8mm kernels. Some community wrappers ship exactly that (Llamatik, SmolChat).
       - **Avoid `armv9-a`/SVE2:** on a Dimensity 9400, the SVE2 variant prefilled at 123 tok/s against 260 for i8mm (issue #29884, open) [primary].
       - Upstream's module builds every variant instead (`GGML_BACKEND_DL` + `GGML_CPU_ALL_VARIANTS`). That's 7 CPU `.so` files plus `extractNativeLibs`, and the loader can pick the SVE2 one. Combining the arch flag with ALL_VARIANTS is a configure error;
     - `-DGGML_OPENMP=OFF` (the docs and release builds) vs `ON` (Arm's module, citing a "huge perf loss on TG128" without it): measure both on the phone;
     - `-DGGML_LLAMAFILE=OFF`, `-DLLAMA_OPENSSL=OFF` (`LLAMA_CURL` was removed in #18828);
     - `-DLLAMA_BUILD_COMMON=OFF` unless the JSON-schema converter runs on the device (§6.4).
   - `GGML_CPU_KLEIDIAI=ON` is optional. It fetches KleidiAI v1.31.0 at configure time (needs network), and without SME it only covers Q4_0/Q8_0. Start without it: llama.cpp's own Q4_0 → 4x8 repack already uses i8mm.
3. **Gradle, in `app/build.gradle.kts`:**
   - `ndkVersion = "28.2.13676358"` (installed);
   - `externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.31.6" } }` (SDK component `cmake;3.31.6`; 3.22.1 should also work for llama.cpp's root, which is [UNCERTAIN]);
   - keep `abiFilters += "arm64-v8a"`.
4. **APK impact:** ~6.5 MB of stripped arm64 code for text only [estimate from the release `.so` sizes]. AGP strips release native libraries. NDK r28+ aligns to 16 KB by default [primary].
5. **No Maven AAR** [primary]. Upstream's `com.arm.aichat` module is source-only, with minSdk 33 and NDK 29; it has no grammar, 8,192 context and `@FastNative` on long calls.
   - The Maven wrappers are small one-person projects, and some are built for baseline ARMv8. `io.github.ljcamargo:llamacpp-kotlin:0.4.0` (MIT, i8mm builds) is the least bad.
   - Our own ~150-line JNI keeps grammar, cancel and timeouts under our control. Use plain JNI, not `@FastNative`, for generate.

### 6.2 The model file
- **What:** `LFM2.5-1.2B-Instruct-QAD-Q4_0.gguf`, 695,755,488 bytes, from `LiquidAI/LFM2.5-1.2B-Instruct-GGUF` at revision `8ed288026e23958ad9dfa92d53ed773a8eee7125`.
- **sha256** prefix `bb741ebb106d543e`. Pin the full hash, as D-006 does for Laya.
- **URL:** https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF/resolve/8ed288026e23958ad9dfa92d53ed773a8eee7125/LFM2.5-1.2B-Instruct-QAD-Q4_0.gguf
- **Gate:** none (open repo) [primary].
- **Licence:** LFM Open License v1.0. It is Apache-2.0-style, but the commercial-use rights hold only below $10M annual revenue, and modified files must carry notices [primary, `LICENSE`]. A personal app is fine; flag it if Friday is ever distributed commercially.
- **Delivery:** push to the app's `filesDir` the way Laya's model is pushed. It does not go in the APK.
- **Runner-up file:**
  - `unsloth/Qwen3.5-0.8B-GGUF` at revision `6ab461498e2023f6e3c1baea90a8f0fe38ab64d0`, `Qwen3.5-0.8B-Q8_0.gguf`.
  - 811,843,840 bytes, sha256 prefix `0ad885ffd4bb022f`.
  - Apache-2.0, open.
  - Skip the vision `mmproj` file.

### 6.3 Expected cost on the Pixel 8
See §5: ~0.75–0.9 GB RAM; prefill ~90–135 tok/s; decode ~22 tok/s. That is ~3–3.5 s per draft after a ~1–2 s load.
- **Citations:**
  - RAM: Liquid's S25 Ultra llama.cpp Q4_0 row (719 MB) [primary].
  - Speed: the PocketPal llama.cpp rows for LFM2.5-1.2B on a Pixel 8 and a Pixel 8a [secondary, crowd-sourced, 7 threads, inside a React Native app].
  - Friday's own numbers come from the checklist.
- **Gate on memory before loading:** `ActivityManager.MemoryInfo.availMem` ≥ ~1.5 GB, as D-016 does.

### 6.4 Forcing JSON
llama.cpp constrains sampling with a GBNF grammar: `llama_sampler_init_grammar(vocab, grammar, "root")`, added to the sampler chain before the greedy sampler [primary, `include/llama.h` at b11701].
- **How to write the grammars:**
  - Write them as constants, one per output shape.
  - Or generate them offline from a JSON schema with llama.cpp's `examples/json_schema_to_grammar.py`.
  - Generating them on the device with `common/json-schema-to-grammar.h` needs `LLAMA_BUILD_COMMON` (libllama-common is ~6 MB stripped in the release build). That isn't worth it for 2–3 fixed shapes.
- **Email**, `{"subject": "...", "body": "..."}`:
  ```
  root ::= "{" ws "\"subject\":" ws str "," ws "\"body\":" ws str ws "}"
  str  ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" ["\\/bfnrt] )* "\""
  ws   ::= [ \t\n]*
  ```
- **Reminder:** `{"title": "..."}` uses the same `str` rule.
- **Plain drafts** (chat, reply, query, answer) need no grammar. Stop on `<|im_end|>` and cap at 120–160 tokens.
- **Costs and limits:**
  - The grammar costs a little per token on short outputs [UNCERTAIN; measure].
  - It guarantees the shape, not the content: the card still shows the text.

### 6.5 Clash with `onnxruntime-android` 1.20.0
**None expected:**
- llama.cpp/ggml doesn't link ONNX Runtime.
- Each library carries its own symbols: ORT ships `libonnxruntime.so`, and ours would be `libwriter.so` with ggml statically inside.
- The one shared piece is the C++ runtime. Either build with `ANDROID_STL=c++_shared` and reuse the NDK r28 `libc++_shared.so` that Friday already commits for DJL (same NDK, so the same ABI), or use `c++_static`.

**Memory is the real clash.** The two models are never resident together (D-016).

**Contrast with ONNX Runtime GenAI** (§3), which would `dlopen` Friday's own `libonnxruntime.so` 1.20.0:
- it was built against ORT 1.26;
- its model builder emits opset 22, beyond ORT 1.20's 21;
- moving to ORT ≥ 1.29 adds the INTERNET permission and telemetry.

Any of these means touching Laya's validated runtime (D-023). **LiteRT-LM** is one self-contained `.so`, so no clash is expected there either [UNCERTAIN, not checked].

### 6.6 Kotlin sketch: lazy load, ~3-minute idle unload, timeouts
```kotlin
/**
 * Native side: writer_jni.cpp wraps llama.cpp: load with mmap, n_ctx 1024, 4 threads; greedy + optional GBNF;
 * a cancel flag; clear the memory/KV before each draft. The prompt already carries the BOS token, so tokenize
 * with add_special=false and parse_special=true.
 */
private object WriterNative {
    init { System.loadLibrary("writer") }
    external fun load(path: String, nCtx: Int, threads: Int): Long          // 0 on failure
    external fun generate(h: Long, prompt: String, maxTokens: Int, grammar: String?, deadlineMs: Long): String?
    external fun cancel(h: Long)                                            // sets an atomic flag the token loop checks
    external fun free(h: Long)
}

/** Drafts short text on-device (research/13). Text only; Laya keeps every decision (D-024). */
class Writer private constructor(private val h: Long) : AutoCloseable {
    /** Greedy draft; null on timeout or cancel. The caller shows it on the Run/Skip/Stop card (D-025). */
    fun draft(system: String, user: String, maxTokens: Int = 160, grammar: String? = null, timeoutMs: Long = 20_000): String? =
        WriterNative.generate(h, chatml(system, user), maxTokens, grammar, SystemClock.elapsedRealtime() + timeoutMs)
            ?.trim()?.takeIf { it.isNotEmpty() }

    fun cancel() = WriterNative.cancel(h)
    override fun close() = WriterNative.free(h)

    companion object {
        const val MODEL = "writer.gguf"  // LFM2.5-1.2B-Instruct-QAD-Q4_0, sha256-pinned
        fun load(file: File) = WriterNative.load(file.absolutePath, 1024, 4)
            .also { require(it != 0L) { "Writer model failed to load" } }.let(::Writer)

        // LFM2.5 template (ChatML-like). For Qwen3.5, append "<think>\n\n</think>\n\n" after the assistant tag.
        private fun chatml(system: String, user: String) =
            "<|startoftext|><|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
    }
}
```
In `AgentService`, next to the shared Laya engine (D-030):
```kotlin
private var writer: Writer? = null
private val unloadWriter = Runnable { synchronized(this) { writer?.close(); writer = null } }

/** The Writer, loaded on the first text step; Laya is freed first (D-016: never both resident). */
@Synchronized fun writer(): Writer {
    main.removeCallbacks(unloadWriter)
    writer?.let { return it }
    freeLaya()                                                   // closes the warm LayaEngine, if any
    val mem = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
    check(mem.availMem > 1_500_000_000L) { "Not enough free memory to write right now" }
    return Writer.load(File(filesDir, Writer.MODEL)).also { writer = it }   // ~1–2 s; do it off the main thread
}
fun writerIdle() = main.postDelayed(unloadWriter, 3 * 60_000L)  // same 3 min as Laya; also free on onTrimMemory
```
- **Symmetry:** `laya()` must close the Writer first, the same way. Load the Writer on the request thread, never the main thread.
- **Skill side:** a new `SkillContext.write(kind, to, request, incoming): String?`. It builds the per-kind prompt of §4, with the language chosen by rule, and returns null on timeout, which falls back to the owner's own words.
- **Message skill:** `Message` calls `write` when the request asks for composing ("write…", "reply…", "nicely/politely", or an email without a quoted body). It then shows `confirm("Send to $to:\n\n$draft")` before any typing. Skip falls back to the copied span; Stop ends.
- **Approvals stay:** the INPUT_TEXT and Send taps still ask (D-025).
- **Timeouts:**
  - load ≤ 10 s;
  - each draft ≤ 20 s, enforced by a deadline checked every token;
  - the banner's Stop calls `cancel()`.
- **Guards worth a few lines:**
  - drop a `Subject:` line from chat drafts;
  - reject drafts that echo the command ("message karo", "write a message") and fall back to the owner's words;
  - mark numbers or times in the draft that aren't in the request, so the owner checks them on the card.

## To measure on the Pixel 8
The phone was unplugged, so these are the first runs to make. The official Android build (`llama-b11701-bin-android-arm64.tar.gz`) includes `llama-bench` and `llama-cli`; push it with the model to `/data/local/tmp`.
1. **Speed:**
   - `LD_LIBRARY_PATH=. ./llama-bench -m <gguf> -t 4 -p 128,512,1024 -n 64 -r 3`, then again at `-t 5` with `taskset 1f0`. The Tensor G3's big cores are cpu4–8: 4× A715 + 1× X3.
   - Run it for LFM2.5 QAD-Q4_0, Qwen3.5-0.8B Q8_0 and Q4_K_M, and Qwen3-1.7B Q4_K_M as a ceiling.
   - Record prefill and decode tok/s, and load time (cold and warm).
2. **RAM:** peak RSS/PSS while drafting (`/proc/<pid>/status` VmHWM, `dumpsys meminfo`), at n_ctx 1,024 and with a 1,000-token screen-text prompt.
3. **Quality A/B:**
   - Run the 8 prompts of §4 with the v2 builder, through `llama-cli` with greedy decoding. Do it for the pick and the runner-up.
   - Score with the same rubric. Add ~10 more covering reminder titles, query cleanup and screen answers.
   - Shipping gate: §6.
4. **JSON:** the email and reminder grammars always produce valid JSON. Measure the per-token cost.
5. **Thermals:** 10 drafts back to back (Laya slowed 2.4 → 8 s under sustained load, research/README).
6. **The swap cost:** Laya cold reload right after a draft (D-030: ~23 s). Decide whether messaging should compose before routing to the app at all.
7. **Build check:** after the JNI build, record APK size before and after. Confirm one `libc++_shared.so` and 16 KB alignment (`zipalign -c -P 16`).
8. **Build-flag A/B:**
   - Measure the `armv8.6-a+i8mm` build against OpenMP on/off and KleidiAI on/off, with `llama-bench` built the same way as `libwriter.so`.
   - The release tarball's `llama-bench` is a BACKEND_DL build. Note which CPU variant it loads; the SVE2 trap is issue #29884.

## Open questions and risks
- **Every Pixel 8 speed figure is borrowed:**
  - crowd-sourced PocketPal rows (llama.cpp inside a React Native app, 7 threads);
  - community LiteRT-LM cards on a Pixel 8a;
  - the vendor's S25 Ultra row.
  - None comes from an app built like Friday. Qwen3.5's Gated DeltaNet on llama.cpp has no Tensor G3 data at all.
- **Hindi with the pick:** LFM2.5 doesn't list Hindi, and Devanagari costs 2.7× the tokens. The fallback rule (Devanagari → the owner's own words) is a stopgap until the A/B.
- **Small models invert meaning:** declines, negations and "ask if". The card is the safety net. There is no edit button yet, so a weak draft costs the owner a Skip plus typing it themselves. An Edit field on the card is the obvious next UX step.
- **Licence:** LFM's revenue clause doesn't matter for a personal app, but it isn't OSI-approved. If that matters, ship the runner-up (Apache-2.0).
- **Single greedy samples are noisy** at this size (§4.2). Any A/B needs ≥ 20 prompts before trusting a 1–2 point difference.

## Dead ends
- **Gemini Nano / ML Kit GenAI:** the Pixel 8 is unsupported, and it only works in the foreground (research/04).
- **MediaPipe LLM Inference:** maintenance-only.
- **MLC LLM:** GPU-only and slower than the CPU on Mali; Llama 3.2 crashes on a Pixel 8.
- **Every LLM GPU path on the Mali-G715:**
  - llama.cpp OpenCL (Adreno/Intel only);
  - llama.cpp Vulkan (wrong results, hangs, ~15× slower);
  - ExecuTorch Vulkan (decode at half its CPU speed);
  - ORT (no LLM GPU path);
  - LiteRT-LM GPU for Qwen3.5-0.8B (engine creation ran out of memory on a Pixel 8a).
- **The Tensor NPU** (LiteRT-LM NPU, Tensor SDK): G5/G6 only.
- **ONNX Runtime GenAI on Android:**
  - not on Maven;
  - no constrained decoding in its Java API;
  - built against ORT 1.26 with opset-22 models;
  - telemetry from 0.15.
- **GenieX (ex-Nexa SDK):** Snapdragon-only.
- **Cactus:** restrictive custom licence; its Maven POM contradicts its repo licence.
- **Community llama.cpp Kotlin wrappers:** one-person projects, some built for baseline ARMv8 (no i8mm/dotprod). Archived or empty ones: Inferkt, KotlinMania/llama.kotlin, kmp-ai.
- **Qwen3.5 on ExecuTorch:** fp32 export only for now.
- **Gemma 3 1B and Llama 3.2 1B:** gated licences. Gemma 3 1B is English-only; Llama 3.2 1B's Hindi is near chance.
- **Gemma 4 E2B, Qwen3.5-2B, Qwen3-1.7B, LFM2.5-2.6B:** over the ~1 GB budget (1.3–1.7 GB).
- **SmolLM4, Qwen3.6/3.8 small sizes, Phi ≤ 2B, Granite 4.1/4.2 < 3B:** don't exist. Sarvam has nothing ≤ 2B instruct.
- **Hindi fine-tunes of small models:** community uploads without evals.
- **One generic prompt for every kind (v1):** subject lines in chats, and English replies to Hindi requests [measured].
- **Crude 4-bit (RTN, Q4_0-like) on a 0.8B:** echoed the command back in Hindi [measured].
- **fp16/bf16 model runs on the 16 GB Mac:** ruled out after the Mac swapped hard. Benchmark on the phone.

## Sources
**Models (HF API, cards, licences; read 2026-10-17)**
- https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct (card, `LICENSE`)
- https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF (files, `qad/README.md`)
- https://www.liquid.ai/blog/qad (2026-08-19)
- https://huggingface.co/Qwen/Qwen3.5-0.8B
- https://huggingface.co/Qwen/Qwen3.5-2B
- https://huggingface.co/Qwen/Qwen3-1.7B
- https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF
- https://huggingface.co/unsloth/Qwen3-1.7B-GGUF
- https://huggingface.co/tiiuae/Falcon-H1-1.5B-Deep-Instruct (and `-GGUF`)
- https://falconllm.tii.ae/falcon-terms-and-conditions.html
- https://huggingface.co/google/gemma-3-1b-it
- https://huggingface.co/google/gemma-4-E2B-it
- https://huggingface.co/meta-llama/Llama-3.2-1B-Instruct
- https://huggingface.co/ibm-granite/granite-4.0-h-1b
- https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct
- https://qwenlm.github.io/blog/qwen3/
- https://huggingface.co/blog/gemma3

**Community measurements [secondary]**
- https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct (Pixel 8a CPU int8 row; one-Engine-per-conversation note)
- https://huggingface.co/litert-community/Qwen3.5-0.8B (Pixel 8a CPU rows; GPU-doesn't-fit note; 2026-10-12 re-quantization)
- https://github.com/john-rocky/edge-compat
- https://github.com/google-ai-edge/LiteRT-LM/issues/3165

**Runtimes** (via the runtime sub-agent unless marked; "re-checked" means it re-read the source itself)
- **llama.cpp:**
  - https://github.com/ggml-org/llama.cpp at `b11701`: `include/llama.h` (grammar sampler; read here), `examples/llama.android/lib` (CMakeLists, `build.gradle.kts`; read here), `common/json-schema-to-grammar.h`, `examples/json_schema_to_grammar.py`, `.github/workflows/release.yml`
  - Releases: https://github.com/ggml-org/llama.cpp/releases/tag/b11701 (macOS arm64 sha256 `e4219b14…`, verified here; android-arm64 tarball listing)
  - Issues and PRs: #29884 (SVE2 vs i8mm variant), #9921 (Q4_0 repack; Pixel 8 load time), #18828 (`LLAMA_CURL` removed), #18432 / #18476 (Android example), #28637 / #10406 / #23359 / #9464 (Vulkan on Mali)
- **Community Kotlin wrappers:** https://github.com/shubham0204/SmolChat-Android, plus Maven `com.llamatik:core`, `io.github.ljcamargo:llamacpp-kotlin`, `io.github.aatricks:llmedge`
- **Benchmarks:**
  - https://huggingface.co/spaces/a-ghorbani/ai-phone-leaderboard (PocketPal crowd data)
  - https://arxiv.org/html/2605.08195v1 (ExecuTorch paper, Pixel 9 Pro XL, Table 5)
  - https://arxiv.org/abs/2506.19884 (MNN-AECS)
  - https://arxiv.org/abs/2410.03613
- **LiteRT-LM:**
  - https://github.com/google-ai-edge/LiteRT-LM (`docs/api/kotlin/getting_started.md`, `docs/api/cpp/constrained-decoding.md`, issues #1850, #1860, #2202, #2421, #3165, #3280, #3509, #3739)
  - https://dl.google.com/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml
  - https://developers.google.com/edge/litert/next/litert_lm_npu
- **MediaPipe:** https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference
- **ORT GenAI:**
  - https://github.com/microsoft/onnxruntime-genai (release v0.17.0; `src/models/onnxruntime_api.h`; model builder)
  - https://onnxruntime.ai/docs/reference/compatibility.html
- **ExecuTorch:** https://github.com/pytorch/executorch (`examples/models/qwen3_5/README.md`)
- **Cactus:** https://github.com/cactus-compute/cactus/blob/main/LICENSE
- **Android:**
  - https://developer.android.com/guide/practices/page-sizes
  - https://developer.android.com/build/include-native-symbols
  - https://developer.android.com/reference/dalvik/annotation/optimization/FastNative
  - https://github.com/llvm/llvm-project/blob/main/llvm/lib/Target/AArch64/AArch64Processors.td (Tensor G3 core features)

**Evaluations [secondary]**
- https://arxiv.org/abs/2606.30790 (Indi-RomCoM)
- https://arxiv.org/abs/2411.02538 (MILU)
- https://huggingface.co/datasets/facebook/Multi-IF

## 21 October follow-up: smaller task interpreter and distillation

The installed Writer remains LFM2.5-1.2B QAD-Q4_0. Its structured-context experiment was rejected before
inference with about 1.03 GB available against the existing 1.5 GB memory requirement. Native calendar
context recovery worked independently. Lowering that guard without profiling would not establish a safe
memory budget.

Candidates to benchmark; none has been installed or trained in this checkpoint:

| Candidate | Proposed Friday role | Primary evidence / limit |
| --- | --- | --- |
| [FunctionGemma 270M](https://ai.google.dev/gemma/docs/functiongemma) | Interpret bounded task fields and propose registered calls | Specialized function caller; intended for task-specific fine-tuning rather than direct dialogue. Its prompt/call format differs from Friday's current Writer template. |
| [SmolLM2-360M-Instruct](https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct) | Short English message rewriting/drafting baseline | Small general text model, Apache 2.0. The publisher's function-calling claim specifically concerns its 1.7B variant; 360M task-call quality needs its own evaluation. |
| [LFM2-350M](https://huggingface.co/LiquidAI/LFM2-350M) | Smaller LFM-family text baseline | Edge-oriented 350M model, under Liquid's license. Friday's draft fidelity and template/runtime compatibility remain unmeasured. |

Google publishes a [Mobile Actions tuning recipe](https://ai.google.dev/gemma/docs/mobile-actions).
Its [FunctionGemma model card](https://huggingface.co/google/functiongemma-270m-it) reports Mobile Actions
accuracy improving from 58% to 85% after specialization. Its dynamic-int8 mobile build used 551 MB peak RSS
on a Samsung S25 Ultra with 1024 context length. These are vendor measurements on another device/task set;
they are not Friday acceptance results or Pixel memory/latency estimates.

Recommended experiment:

1. Benchmark unchanged candidates on held-out Friday task updates and short-message cases before training.
2. Use a stronger teacher to produce synthetic context/reply/validated-patch examples, covering multiple
   supplied fields, corrections, references, new tasks, cancellation, ambiguity and negation. Keep owner
   records private and use reviewed/redacted examples only. Evaluate complete field values and source spans.
3. Distill into the smallest student that passes: supervised fine-tuning on teacher outputs, initially with
   LoRA. Tune interpretation and prose separately so their quality can be compared independently. A 4090 is
   a plausible development target for these small students; actual training memory/time requires a pilot.
4. Merge adapters, quantize, then rerun semantic tests: training adapts behavior, while quantization reduces
   weight storage. Neither removes KV-cache/runtime costs or the need for calibrated admission checks.
5. Measure cold/warm latency, peak RSS, unsupported-input behavior and task completion on the Pixel with
   the coordinator held fixed. Keep validation, app authority and verified completion in Kotlin.

Start with FunctionGemma for the field-update role and SmolLM2/LFM2 for prose. This is a proposed comparison,
not a model replacement or completed distillation run.
