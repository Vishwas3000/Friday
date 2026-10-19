# Decision log

Every project decision, with its reason. Entries are never deleted; replaced ones are marked **Superseded**.
**Proposed** means Claude decided without the user's confirmation, so the user can veto it.

| ID | Date | Decision | Who | Status |
|---|---|---|---|---|
| D-001 | 2026-10-16 | Policy model = Laya-Android (`ByunByun/laya-android`), not base Laya | Claude | Proposed |
| D-002 | 2026-10-16 | Pin `laya==0.3.28` | Claude | Proposed |
| D-003 | 2026-10-16 | Python 3.12 via uv, not the system Python 3.15 | measured | Decided |
| D-004 | 2026-10-16 | Never commit phone captures (dumps, screenshots) | Claude | Proposed |
| D-005 | 2026-10-16 | Keep transformers 5.20; don't pin 4.57.6 | measured | Proposed |
| D-006 | 2026-10-16 | Pin model revision + sha256; run offline after the first download | Claude | Proposed |
| D-007 | 2026-10-16 | Mac + adb loop is the test bench, not the product | Claude | Proposed |
| D-008 | 2026-10-16 | Product = one native Kotlin app (AccessibilityService + Laya on ONNX Runtime) | Claude | **Pending** user agreement |
| D-009 | 2026-10-16 | Text/app names: rules + Laya choice first, a small local LLM only on demand | Claude | Proposed |
| D-010 | 2026-10-16 | Browser tasks drive the user's Chrome through accessibility, no CDP/WebView in v1 | Claude | Proposed |
| D-011 | 2026-10-16 | Safety gates in every runner | Claude | Proposed |
| D-012 | 2026-10-16 | Sideloaded personal app, not the Play Store | Claude | Proposed |
| D-013 | 2026-10-16 | Port laya-android's candidate rules instead of vendoring the files | Claude | Proposed |
| D-014 | 2026-10-16 | Don't declare `isAccessibilityTool`; keep the platform's sensitive-view wall | Claude | Proposed |
| D-015 | 2026-10-16 | Skip uiautomator2 for the bench; build the app's own tree export instead | Claude | Proposed |
| D-016 | 2026-10-16 | Strict on-device resource budget for Phase 2 (Laya ≤0.8 GB, one pass at a time, load on demand) | Claude | Proposed |
| D-017 | 2026-10-16 | App name = Friday (package stays dev.layatest.agent) | user | Decided |
| D-018 | 2026-10-16 | V1 = Phase 1 (app = eyes+hands, Laya on Mac, approve each step); on-device model + monitoring deferred | Claude | Proposed |

---

### D-001 — Policy model = Laya-Android, not base Laya
- **Decision:** Use the community fine-tune `ByunByun/laya-android` (322M, mmBERT-base, trained on AndroidControl) as the step policy.
- **Why:** It is the only Laya checkpoint trained for Android control. On AndroidControl-High, base Laya zero-shot scores Type 29.0 / Grounding 11.8, while Laya-Android scores 76.7 / 61.7 (model card, 2026-10).
- **Rejected:** base Laya zero-shot, which is near chance; fine-tuning our own checkpoint now, which is premature before we see live behaviour.
- **Evidence:** [research/01](../research/01-laya-and-laya-android.md), https://huggingface.co/ByunByun/laya-android
- **Who / status:** Claude · Proposed

### D-002 — Pin `laya==0.3.28`
- **Decision:** Pin the exact version the Laya-Android card names.
- **Why:** The package moves fast: 46 releases, with 0.3.28 on 2026-10-05 and 0.4.8 on 2026-10-15 (PyPI JSON, checked 2026-10-16). The fine-tuned head was trained against 0.3.28.
- **Rejected:** tracking latest (0.4.8), since head or serialization changes could silently change the checkpoint's behaviour.
- **Who / status:** Claude · Proposed. Revisit if the laya-android repo bumps its pin.

### D-003 — Python 3.12 via uv
- **Decision:** `.python-version` = 3.12, managed by `uv`.
- **Why:** The Homebrew Python 3.15 on this Mac has a broken `pyexpat` (`Symbol not found: _XML_SetAllocTrackerActivationThreshold`), so `xml.etree` cannot even parse a uiautomator dump [measured 2026-10-16]. 3.12 has torch/transformers wheels on macOS and Windows.
- **Rejected:** the system Python 3.15.
- **Who / status:** measured · Decided

### D-004 — Never commit phone captures
- **Decision:** `captures/` and dump files are git-ignored, and benchmarks use synthetic UI labels.
- **Why:** Accessibility dumps and screenshots contain notifications, messages and contacts. "Everything local" includes the git history.
- **Rejected:** committing real dumps as test fixtures. Use scrubbed or synthetic fixtures instead.
- **Who / status:** Claude · Proposed

### D-005 — Keep transformers 5.20
- **Decision:** Don't pin `transformers==4.57.6`, the version Laya-Android was evaluated with.
- **Why:** On the repo's quickstart example our stack (transformers 5.20, MPS) gives the same choices with p 0.9338 / 0.9339, against 0.9347 / 0.9313 published [measured 2026-10-16].
- **Rejected:** pinning 4.57.6, recommended by research agent 01. That is an extra constraint with no observed benefit.
- **Revisit:** if a replay A/B over logged steps shows any decision flips.
- **Who / status:** measured · Proposed

### D-006 — Pin model revision + sha256; run offline
- **Decision:** `laya.load(MODEL, revision="5a03b2b…", expected_sha256={...})` in `scripts/laya_android.py`. After the first download, run with `HF_HUB_OFFLINE=1`. This is verified to load offline.
- **Why:** "Everything local" means no network at run time, and a changed upload must not silently change behaviour.
- **Rejected:** loading by Hub id, which contacts the Hub on every load.
- **Who / status:** Claude, from agent 01 · Proposed

### D-007 — The Mac + adb loop is the test bench, not the product
- **Decision:** `scripts/live_adb.py` exists only to measure the model on the real phone.
- **Why:** About 90% of each ~3 s step is `uiautomator dump` [measured]. An on-phone AccessibilityService reads the tree in-process.
- **Rejected:** building the product on adb, or on uiautomator2 or Appium.
- **Who / status:** Claude · Proposed

### D-008 — Product = one native Kotlin app
- **Decision:** One app holding the AccessibilityService (screen tree, gestures, global actions, app launch, `ACTION_SET_TEXT`). Laya-Android runs on ONNX Runtime Android (CPU EP), driven by a port of `laya-java`, with Laya's exporter and the Gemma-2 `tokenizer.json`.
- **Why:** The user asked to "pack all these things into a native app" with everything local. ONNX Runtime CPU is the only runtime proven on this graph and available on the Pixel 8. Its TPU is closed to third parties and NNAPI is deprecated.
- **Rejected:** LiteRT GPU first (fixed shapes, fp16 LayerNorm risk; kept as Plan B); NNAPI/TPU; ExecuTorch; Gemini Nano.
- **Open risk:** on-phone latency is estimated at 1.3–6.5 s per step [UNCERTAIN]. Measure it before committing; the fallback is Laya on the Mac over USB/Wi-Fi.
- **Who / status:** Claude · Agreed: staged, app first (user chose 2026-10-16). Phase 1 validated on the Pixel 8 — see research/05.

### D-009 — Text and app names: rules + Laya choice first
- **Decision:**
  - OPEN_APP is a Laya `choice` over installed-app labels, short-listed by fuzzy match against the goal.
  - INPUT_TEXT copies a span from the goal.
  - A small local LLM loads only when neither works: Qwen3.5-4B on the Mac, Qwen3.5-0.8B or FunctionGemma 270M on the phone.
- **Why:** The model emits no payloads [primary]. The rules covered every live episode so far [measured]. RAM on the phone can't hold Laya and an LLM at once.
- **Rejected:** Gemini Nano (Pixel 8 unsupported, foreground-only); any cloud LLM (breaks local-only); Gemma 4 E2B on the phone (1.4–1.7 GB peak).
- **Who / status:** Claude, from agents 01/03/04 · Proposed

### D-010 — Browser: drive the user's Chrome through accessibility
- **Decision:** Treat Chrome like any other app.
- **Why:**
  - Chrome exposes page elements in the accessibility tree.
  - It keeps the user's logins.
  - It matches Laya-Android's training data.
  - It is one code path for every app.
- **Rejected:**
  - Chrome DevTools Protocol: USB host only, and impossible inside an on-phone app.
  - An in-app WebView with JS injection: no user sessions, and off Laya's training distribution.
- **Caveat:** web pages produce many candidates, so chunking/short-listing (risk 1 in research/README) is mandatory.
- **Who / status:** Claude (answering the user's question) · Proposed

### D-011 — Safety gates in every runner
- **Decision:**
  - Confirm each step by default.
  - Labels with risky words (send, pay, delete, install, allow…) always need a human.
  - Stop on 3 identical actions, and cap the step count.
  - Log every step locally (`captures/`, git-ignored).
  - Still to add: never auto-accept permission dialogs, re-check the foreground app before acting, and confidence thresholds fitted on our own logs.
- **Why:** The scores are offline only, and the Laya-Android authors warn against unsupervised use. Prior art shows agents granting permission pop-ups 26 of 32 times (arXiv 2608.04755).
- **Rejected:** unsupervised autonomy; using `act_probability` as the gate (no signal per the Laya card).
- **Who / status:** Claude · Proposed

### D-012 — Sideloaded personal app, not the Play Store
- **Decision:** Install with `adb install` and enable the service by hand.
- **Why:** Google Play bans autonomous AccessibilityService agents (policy announced 2025-10-30) [primary, research/03].
- **Rejected:** a Play Store listing.
- **Who / status:** Claude · Proposed

### D-013 — Port laya-android's candidate rules instead of vendoring the files
- **Decision:** `scripts/laya_android.py` re-implements `src/accessibility.py`, `src/candidates.py` and `src/serialization.py` for uiautomator XML: the same filters, dedup, sort, label and flag rules, with an assert self-check.
- **Why:** The upstream code expects AndroidControl's node schema. An XML-native port is about as small as an adapter, and it was checked line by line. The Kotlin app will need its own port anyway.
- **Rejected:** vendoring the files unchanged plus an adapter (research agent 01's recommendation).
- **Revisit:** if any replay shows a label mismatch.
- **Who / status:** Claude · Done — Python `scripts/laya_android.py` + Kotlin `Candidates.kt` (Phase 2d), the latter validated on-device: the `selfcheck` command reproduces the Python's candidate labels exactly (2026-10-16).

### D-014 — Don't declare `isAccessibilityTool`
- **Decision:** Our service ships without `android:isAccessibilityTool="true"`.
- **Why:** Without the flag, Android hides `accessibilityDataSensitive` views from the service and drops its gestures on them. That covers runtime-permission dialogs, PackageInstaller install buttons and the screen-record Start/Stop dialog (research/02 §4.3). This is a platform-enforced version of D-011's "never auto-accept permission dialogs". It is also the honest label: this is automation, not assistive technology.
- **Rejected:** declaring the flag, as research agent 02 suggested. It would unlock those views, and it only matters for Android 17's opt-in Advanced Protection, which is exactly when the owner wants agents blocked.
- **Who / status:** Claude · Proposed

### D-015 — Skip uiautomator2 for the Mac bench
- **Decision:** Don't add openatx/uiautomator2 (agent 02's POC suggestion). Make the app's AccessibilityService the fast tree source, reachable from the Mac over `adb forward` during development.
- **Why:** uiautomator2 would cut the ~2.5 s dump, but the app's service has to be built anyway. The service gives the training-format forest (all windows, `editable`, important-views-only) that u2 cannot, and doing both is duplicate work.
- **Rejected:** uiautomator2, which also installs two helper APKs; DroidRun Portal (HEAD is AGPL-3.0 and asks for SMS/contacts permissions).
- **Who / status:** Claude · Proposed

### D-016 — Strict on-device resource budget (Phase 2)
- **Decision:** When Laya moves onto the phone: keep it ≤ ~0.8 GB (int8 linears + mmap/quantized embedding table), run one forward pass at a time (operation first, target only for CLICK/LONG_PRESS, ≤1,536 tokens), load the model on demand and free it on `onTrimMemory`/idle, never keep a text LLM resident with it, and gate on `MemAvailable`.
- **Why:** the user asked to manage memory/resources carefully (2026-10-16); fp32 is 1.28 GB and the phone had ~1.1 GB free at one point. Details in research/04 and docs/app.md.
- **Rejected:** shipping the fp32 graph; keeping the model resident; dynamic int8 without a ≥99% parity gate (it agrees with fp32 on only 83%).
- **Who / status:** Claude · Proposed

### D-017 — App name = Friday
- **Decision:** Display name "Friday" (matches the GitHub repo). Package id stays `dev.layatest.agent` to avoid re-enabling the service and moving source dirs.
- **Why:** the user named it on 2026-10-16.
- **Who / status:** user · Decided

### D-018 — V1 scope = Phase 1
- **Decision:** V1 ships Phase 1: the Friday app is eyes+hands, Laya decides on the Mac over USB, the owner approves every step on the phone, everything local. On-device inference (Phase 2), notification/SMS/call monitoring, and smarter decisioning (short-listing, completion check, calibration) are **not** in V1.
- **Why:** the user chose "staged, app first" and "ask before every step", and deferred monitoring.
- **Rejected:** bundling on-device inference into V1 (its latency/accuracy are unmeasured) and monitoring (deferred by the user).
- **Who / status:** Claude · Proposed

### D-019 — Export to ONNX via the legacy TorchScript exporter, fastpath off
- **Decision:** `scripts/export_onnx.py` exports `agent.model` with `torch.onnx.export(..., opset=17, dynamo=False)` after `torch.backends.mha.set_fastpath_enabled(False)`. Output `models/laya-android.onnx` (git-ignored, 1288 MB fp32).
- **Why:** the head's `nn.TransformerEncoderLayer` takes a fused `aten::_transformer_encoder_layer_fwd` kernel in eval that the exporter can't lower and that bypasses laya's export-built `_DynamicMultiheadAttention`; disabling the fastpath forces the modular (exportable) path, same maths. Parity is **bit-exact** vs the torch Agent and laya's `ONNXAgent` (`max|Δprob|=0.00e+00`), so the fp32 graph is a trustworthy quantization baseline [measured, research/06].
- **Rejected:** the new `torch.export`/dynamo ONNX path (needs `dynamic_shapes`, more churn) — the legacy path already gives bit-exact parity.
- **Who / status:** Claude · Proposed

### D-020 — On-device model = fp16, not int8 (first candidate)
- **Decision:** ship Laya on-device as **fp16** (`scripts/quantize_onnx.py` → `models/laya-android.fp16.onnx`, 645 MB, ≤ 0.8 GB budget). int8 stays a later lever, not the default.
- **Why:** the 256k embedding is 61% of weights and ORT dynamic-int8 leaves it fp32 (~0.9 GB, over budget) *and* quantizes activations (→ ~83% parity, D-016). fp16 halves every weight uniformly with near-exact parity (operation/target top-1 4/4 on a 3/8/14/25-candidate smoke set, `max|Δprob|=5.3e-3`), and the Pixel 8 (ARMv9) has native fp16 [measured, research/06].
- **Gotcha:** `onnxconverter_common` fp16 breaks ModernBERT's deliberate fp32 islands (RoPE/Cast/Softmax); the script blocks those nodes. Parity so far is a smoke test, not a full eval — replay real `captures/` before trusting it on-device.
- **Rejected:** fp32 on-device (1.28 GB, OOM risk); dynamic int8 as the default (over budget + 83% parity).
- **Revisit:** if on-device latency/RAM needs it, go int8-of-linears + mmap-embedding.
- **Who / status:** Claude · Superseded 2026-10-18 by D-047 step 2 (8-bit MatMulNBits on ORT 1.31)

### D-021 — Input is typed now; voice (STT + speaker verification) is deferred; no LLM near-term
- **Decision:** Friday takes a **typed** goal for now, routed straight into Laya + rules. STT and voice-based owner verification ("only I can command it") are a later phase. No generative LLM is added near-term.
- **Why:** the user said "for now I will type … later we will add STT and other things (like analysing my voice, so only I can command it)" (2026-10-16). Typed goals already work; adding an LLM/voice now would blow the resource budget and scope. Keeps Phase 2 focused on getting Laya on-device.
- **Note:** Laya is non-generative by architecture — a conversational/free-language layer, if ever wanted, is a *separate* small LLM (the brain/voice) with Laya as the hands; fine-tuning Laya cannot add language. Speaker verification is a local convenience gate, not strong auth — the real guard stays per-step approval + the risky-word gate (D-011).
- **Rejected (for now):** adding a generative LLM; building STT/voice-auth before on-device Laya is proven.
- **Who / status:** user · Decided (scope); Claude · Proposed (rationale)

### D-022 — On-device tokenizer = DJL HuggingFace `tokenizers` (load tokenizer.json), pinned 0.33.0
- **Decision:** tokenize on-device by loading the checkpoint's `tokenizer.json` through DJL's Rust `tokenizers` JNI binding — `ai.djl.huggingface:tokenizers:0.33.0` + `runtimeOnly("ai.djl.android:tokenizer-native:0.33.0")` — not by porting the algorithm. Staged at `phase2-staging/Tokenizer.kt`.
- **Why:** byte-identical by construction (the Gemma-2/mmBERT BPE with `byte_fallback`, 256k vocab); 0.33.0 is the only release shipping the Android arm64 `.so` (8 MB). Id-parity vs `transformers` verified on 5 strings [measured, research/07].
- **Gotcha:** `tokenizer.json` embeds `truncation{max_length:48}`; the Kotlin wrapper MUST build with `optTruncation(false)/optPadding(false)` or every state is cut to 48 tokens (laya's transformers path ignores it).
- **Rejected:** laya-java tokenizer (HashMap merges → ~100–250 MB ART heap); DJL 0.38.0 (no Android native); hand-rolled JNI; sentencepiece-android (no `.model` ships).
- **Confirmed [measured on-device 2026-10-16]:** on the Pixel 8 the DJL tokenizer yields **byte-identical** input to Python — `decide_test` reports `input_ids_match: true`, `marker_pos_match: true`, and the full on-device tokenize→build→ORT→decode returns BACK / target 0, matching the Mac. The ported Kotlin (`Tokenizer.kt` + `LayaInput.kt`) composes exactly.
- **Build note:** `libdjl_tokenizer.so` dynamically needs `libc++_shared.so`, which DJL's aar omits (ORT static-links its own). We commit the NDK's arm64 copy at `android/app/src/main/jniLibs/arm64-v8a/`.
- **Who / status:** Claude (subagent + integration) · Validated

### D-023 — On-device runtime config = fp16 + ORT CPU arena disabled, 4 threads (the gate result)
- **Decision:** run the exported model on-device as **fp16** with `setCPUArenaAllocator(false)` + `setMemoryPatternOptimization(false)` and 4 intra-op threads.
- **Why [measured, research/06]:** fp16 keeps decisions bit-close to fp32 (on-device Δlogit 0.006); disabling the arena cuts peak PSS **1354 → 688 MB** (under the 0.8 GB D-016 budget); 4 threads beats 8 (big.LITTLE). ~2.4 s/step cool. **The Phase-2 viability gate is passed.**
- **Supersedes part of D-016:** the RAM driver is ORT's memory arena + intermediates, **not** the weights — so quantization is not the memory lever, the arena switch is.
- **Rejected:** dynamic int8 as the default (3.5× faster at 677 ms and 389 MB, but it **breaks the operation decision** — op top-1 2/4, max |Δprob| 0.11); keeping the arena (1.35 GB, over budget); 8 threads (1201 ms).
- **Revisit:** int8 with the operation head/scorer kept fp32 if we later need the speed; a cool, throttling-isolated latency for the winning config is still to confirm.
- **Who / status:** Claude · Proposed

### D-024 — Friday is an assistant: route → slots → app → do → read → answer
- **Decision:** a typed request runs through `Assistant` (Android): **route** it to one of 16 skills (keyword rules first, Laya for paraphrases, ask the owner below 0.7 confidence); the **skill** fills slots by rule, resolves the app, acts, optionally reads data off the screen, and returns an **answer** card. Laya *chooses* (skills, slot spans, UI steps); rules *produce* (dates, times, app choice, math). Safety sits under the skill interface so no skill can skip it.
- **Why [measured, research/09]:** routing — rules 100% seen / 6% paraphrased, Laya 82% / 81%, hybrid 100% / 81%, confidence calibrated (≥0.9 → 100% right). Slots — rule-trimmed spans + Laya: 77% exact vs 0%. On-device routing matches the Mac to two decimals.
- **Rejected:** a generative LLM brain for now (D-021); one open-ended Laya loop for everything (it can't fill slots or pick apps).
- **Who / status:** user directed ("plan everything… build it end to end", 2026-10-16) · Claude designed · Built (dbd7e58)

### D-025 — Approvals: auto-run safe steps, always ask on risky ones
- **Decision:** scrolling, going back, opening apps and ordinary taps run on their own. Typing, and taps on send/pay/buy/order/book/delete/call/confirm/allow/… always ask (Run / Skip / Stop). A status banner with **Stop** is shown while Friday works.
- **Why:** a 30-step task can't need 30 taps; the owner chose this (2026-10-16). Supersedes D-011's ask-every-step default for the on-phone assistant.
- **Who / status:** user · Decided

### D-026 — Money apps are in scope, read-only
- **Decision:** inside UPI/bank/card/tracker apps (`AppResolver.MONEY`) Friday **never** types and **never** taps Pay/Send/Transfer/Request/… — blocked even if the owner taps Run. On fingerprint / screen-lock / UPI-PIN screens Friday waits for the owner and never acts. Money answers are shown, not logged.
- **Why:** the owner wants "what were my expenses last month" and will authenticate with their own fingerprint (2026-10-16). Replaces the old "banking/UPI out of scope" line. Where an app blocks accessibility, we still respect it (D-014; research/11).
- **Who / status:** user (scope) · Claude (rails) · Built

### D-027 — App choice is a lookup, not a model call
- **Decision:** skill → catalog of well-known app ids → installed → the owner's remembered pick (asked once). An app named in the request wins. Laya never picks among all installed apps.
- **Why [measured, research/09]:** Laya over all 207 apps was right 6–26% of the time, matching words not purpose ("wake" → a sleep tracker), with uncalibrated confidence.
- **Who / status:** Claude · Built

### D-028 — Execution order: answer > intent/API > narrow screen + Laya
- **Decision:** answer from an API when possible (battery, storage, next alarm); else a direct intent (alarm, timer, navigation, web search, dial, play-from-search, Wi-Fi request); else open the narrowest screen and let Laya act on a short list; Laya from anywhere is the last resort. System Allow dialogs (Wi-Fi, Bluetooth) are tapped by the owner, never auto-accepted (D-011).
- **Why:** intents are instant and reliable; Laya is accurate on short lists (research/05, 09, 10).
- **Who / status:** Claude (from research/10) · Built for Phone & time; Media and People & places in progress

### D-029 — Expenses: measure readability first, then pick the method
- **Decision:** before building the expenses extractor, check with the owner whether Friday can read each payment app's history (`scripts/probe_screen.py`, structure only — it never prints amounts, names or dates). The owner's UX is "open the app and total it", summing **debits** (not GPay's own monthly figure, which is net of money received). Fallbacks (research/11): a bank statement file parsed on the phone (covers every UPI app and cards), then an in-app total, then summing rows.
- **Why:** GPay is Flutter and its accessibility tree is unknown; some bank apps block (ICICI won't run with Developer options on).
- **Who / status:** Claude · Pending (owner probe)

### D-030 — Keep Laya warm; load it in parallel; pre-warm on open
- **Decision:** one shared engine in the service, model and tokenizer loaded in parallel plus one warm-up pass, freed after 3 idle minutes (never mid-request), pre-warmed when the owner opens Friday.
- **Why [measured 2026-10-16]:** a cold Laya request cost ~23 s (model 10 s, tokenizer 8 s, first pass 4.5 s); rule-routed requests don't load Laya at all. Keeps D-016's load-on-demand / free-when-idle.
- **Measured on the Pixel 8 (2026-10-17):** cold request 4.6 s with the files in Android's page cache (model 2.8 s ‖ tokenizer 2.3 s, then a 1.4 s routing pass; a truly cold cache measured 10 s ‖ 8 s), warm 1.4 s — was ~24 s.
- **Who / status:** Claude · Validated

### D-031 — App understanding is learned, not hand-written
- **Decision:** Friday must not depend on hand-written per-app knowledge ("GPay is for expenses", per-app recipes). What an app is for, and how to operate it, comes from (a) app profiles generated automatically on the phone from what each app declares (label, package-name words, Play category, intent-filter capabilities, launcher shortcuts / App Actions) and (b) a Laya fine-tuned to match requests to those profiles and to operate apps. Platform capabilities used through Android APIs and intents (alarm, timer, navigation, web search, battery) stay — they aren't per-app knowledge.
- **Why:** owner, 2026-10-17: "we don't want to hardcode usecases for an app… finetune laya to understand the basic utility of all the apps". Measured (research/12): purposes lift app choice 36% → 58%, so profiles are the lever; generating them removes the hand-written list.
- **Supersedes:** the hand-written purpose catalog planned in research/12 / D-027 (the catalog agent was redirected to an automatic profiler). The 16-skill keyword rules stay only as a fast path for common phrasings.
- **Who / status:** user (direction) · Claude (method) · Profiles built (D-038); method proposed (D-043), data plan proposed (D-044); training waits for the GPU box (D-034, D-042)

### D-032 — A lightweight on-device LLM writes text; Laya still decides
- **Decision:** add a small generative model used **only** when a step needs text the owner will see before it's used (message, email and reply bodies). It loads on demand, is freed after, and is never resident together with Laya (D-016). Its output always goes through the approval card before anything is typed or sent.
- **Why:** owner, 2026-10-17: "if I ask it to write a text it should be able to do it… use some light weight LLM to offload the works when it involves any text outputs". Laya chooses but cannot generate.
- **Supersedes:** D-021's "no generative LLM near-term", for text output only.
- **Who / status:** user · Proposed — D-041 (llama.cpp + LFM2.5-1.2B, research/13), gated on a Pixel 8 comparison

### D-033 — Teach mode: the owner demonstrates, Friday records, and it can all be deleted
- **Decision:** the owner types what they'll do and taps **Teach this**. A red banner shows while recording. For each step Friday records the settled screen (raw forest) and the owner's action (tap, long-press, scroll, typed text, opening an app), and **Done** records where the task ends. Cancel keeps nothing. **"Delete all teaching data"** wipes the phone's recordings, `scripts/pull_teach.py --wipe` also removes the Mac copy, and copies used for training on the GPU box are deleted after each run.
- **Why:** owner chose Teach mode as the training-data source (2026-10-17). It gives the best labels for operating the owner's own apps and for DONE, Laya's weak spot (research/05, 17). Privacy: the owner said the data is "only for me and research purpose, not gonna publish". Deletability: owner, "make sure we can delete the data once we are done".
- **Who / status:** user · Validated on the Pixel 8 (Clock: open → Stopwatch → Stop → Done recorded; Compose apps via D-039); Teach recordings also become remembered paths (D-040); export script pending

### D-034 — Train on the owner's remote GPU box, isolated from its other projects
- **Decision:** fine-tuning runs on the owner's remote CUDA machine: one dedicated directory with its own environment, no global installs or system changes, a GPU-usage check before each run, nothing outside our directory touched. The owner's personal data (Teach mode) may be used there.
- **Why:** owner offered it, 2026-10-17 ("don't delete or interfere with the existing projects"); Laya-Android's own fine-tune needed an RTX 4090-class GPU (2.7 h).
- **Who / status:** user · Rule confirmed by the owner (D-042): "for any training use the remote vast machine". Run-packs for research/14 and 15 are ready; waiting for the instance's current SSH port (<previous-port> and <previous-port> refused). Agents' own SSH attempts are blocked by the permission check, so main runs box commands.

### D-035 — A wake word for Friday, at very low standby power
- **Decision:** add a wake word ("Hey Friday") so Friday can be started hands-free, with standby power kept minimal and audio never leaving the phone. Speech-to-text and owner-voice verification follow later.
- **Why:** owner, 2026-10-17. Fine-tuning research comes first (owner: "but first research on how can we finetune laya").
- **Who / status:** user · In progress — openWakeWord inside Friday's ONNX Runtime (research/16); phrases "hey Friday" and "hi Friday" (owner, 2026-10-17); always-on listening dropped, see D-046

### D-036 — Friday operates the app it opens: the task continues inside it, and Laya is told what was done
- **Decision:** "open X and <task>" opens X and hands the rest to Laya inside X, instead of stopping. Friday keeps a trail of what it did in a request (OPEN_APP, taps, typed text — intent launches included) and passes it to Laya as `history`.
- **Why [measured, research/17]:** both were why Friday "only opened apps". With an empty history Laya re-picks OPEN_APP (0.80–0.95) inside the app; with "OPEN_APP Clock" it picks the right step (CLICK Timers 0.96). On the phone: "open the clock app and go to the stopwatch tab" → CLICK Stopwatch 0.96.
- **Open:** Laya still overshoots (CLICK Start 0.55 after reaching the goal) → a completion check plus D-033 data.
- **Who / status:** Claude · Validated on the Pixel 8

### D-037 — An alarm is an alarm, and Friday checks before saying it's done
- **Decision:** the owner's word decides: "alarm", "wake me" or "remind" makes an alarm (at now + duration for "in 8 mins"); only "timer" makes a timer. After setting an alarm Friday reads Android's next-alarm record before claiming success, otherwise it says it couldn't confirm. Answers say where to find the result.
- **Why:** owner bug report, 2026-10-17: "set an alarm for 8 mins from now" showed "timer set" and nothing appeared in the alarm list. Logs showed the timer had been set (research/17); the wrong kind, and claimed without checking.
- **Who / status:** Claude · Validated on the Pixel 8 ("Alarm set for 5:19 AM (in 8 min) — Clock › Alarms", 0.8 s)

### D-038 — App profiles are generated on the phone (AppProfiler)
- **Decision:** each installed app's profile is built on the phone from what it declares: label, package-name words, Play category, which capability intents it handles (play-from-search, navigation, geo, dial, mailto, UPI pay, set alarm, camera, image view, web links; one `<queries>` intent per probe, `MATCH_ALL`), and its static launcher shortcuts (parsed from the app's shortcuts XML by attribute id). Whole items are cut to fit a 35-word budget.
- **Why [measured]:** 205 apps profiled in 1 s. Zero-shot app choice: profiles 48% vs names 36% vs hand-written purposes 58% (research/15). In 22 of 26 misses a right app did declare a capability, so reading profiles, not the profiles themselves, is the gap → fine-tuning (D-043).
- **Rejected:** a hand-written catalog (D-031); Wikidata as a profile source (research/14: covers 66 of the owner's 207 apps).
- **Open:** extra probes listed in research/15 (Health Connect, media/telecom services, SET_TIMER, CREATE_NOTE, calendar INSERT, widgets, web domains); budget in tokens (~27 words hit the 48-token option cap), not words.
- **Who / status:** Claude · Built; probes to extend

### D-039 — Teach mode takes the owner's real touches when the phone is plugged in
- **Decision:** `scripts/teach_touches.py --goal …` starts Teach mode in raw-touch mode and forwards every tap, long-press and edge swipe (BACK / HOME) read from the touchscreen with `adb shell getevent`; the phone matches each point against the screen it was showing, exactly like a click event, and ignores click events for that session. Touches on Friday's own banner are dropped. The screen behind a step is re-read at most 1.5 s after it starts changing, so screens that never settle (a running stopwatch) are still read. Only taps on app icons count as OPEN_APP on the launcher.
- **Why [measured]:** Compose apps send no click event for a finger tap (Play Store tabs: none), nor do buttons that hide themselves (Clock's Reset). With raw touches: Play Store → Books recorded as OPEN_APP, CLICK Books (target 24), BACK, DONE.
- **Rejected:** `AccessibilityServiceInfo.setMotionEventSources(SOURCE_TOUCHSCREEN)` (the service would take the touches over; an observe-only variant isn't public API); an overlay that intercepts and re-injects touches (breaks gestures); `sendevent` (blocked on a user build, and only useful for tests). On-phone-only raw touches would need Shizuku (later, if teaching away from the Mac matters).
- **Open:** the getevent reader is self-checked but not yet tried with a real finger.
- **Who / status:** Claude · Phone side validated

### D-040 — UI memory: remembered paths replay without Laya
- **Decision:** a path that finished a goal is stored per app as element signatures (package, resource id, class, label) and replayed by finding each element on the live screen; the first element that isn't there hands the rest to Laya. Paths come only from Teach demonstrations and runs the owner confirms on the answer card ("I did: tap “Timers”" → Remember these steps); confirmed runs also write Teach-format training rows. Replayed steps still pass `Safety.verdict`. "Delete all learned data" wipes paths and recordings.
- **Why [measured, research/20]:** Laya is the step cost (390 tokens 2.9 s … 1868 tokens 24–39 s); the screen read is 0.2–1 s. Clock "go to timers": 22.6 s with Laya → 2.2 s from memory; a taught path replayed in 2.0 s. Owner: "can laya learn the UI coz apps dont change their ui a lot".
- **Rejected:** saving every run that ends in DONE (Laya overshot and started a real 1-minute timer — that path would have been memorised); exact-string goal keys (rephrasings miss); keying on the goal minus a guessed slot (made "open clock and …" match every Clock path).
- **Who / status:** user (idea) · Claude · Validated on the Pixel 8 (1-step paths)

### D-041 — Writer = llama.cpp + LFM2.5-1.2B-Instruct (QAD-Q4_0); runner-up Qwen3.5-0.8B (Q8_0)
- **Decision:** text outputs (message and email bodies, reminder titles, search-query cleanup, one-line answers from screen text) come from llama.cpp (tag b11701, JNI + CMake, armv8.6-a+dotprod+i8mm+fp16, no SVE2 build) running LFM2.5-1.2B-Instruct QAD-Q4_0 (696 MB). Output is grammar-constrained JSON. Loaded on the first text step after Laya is freed, unloaded after 3 idle minutes; never resident with Laya (D-016); every draft goes through the approval card (D-025).
- **Why:** best instruction-following that fits ~1 GB (IFEval 86.2 vs 59.9 for Qwen3.5-0.8B, vendor-run); ~134 tok/s prefill / 22 tok/s decode reported on Pixel 8a-class phones [secondary]; llama.cpp shares no native library with ONNX Runtime. Settings showed the need: Laya picked "settings and go" to type where "about phone" was meant (research/20).
- **Gate:** compare both models on the Pixel 8 with research/13's prompts; ship LFM2.5 if it gives at least as many sendable drafts (Qwen3.5-0.8B: 3 of 7 on the Mac) and handles the Hinglish one. LFM2.5 doesn't support Hindi script: such requests skip the Writer until tested.
- **Rejected:** LiteRT-LM (bigger APK and RAM, community-converted models), ONNX Runtime GenAI (would touch Laya's validated runtime; needs ORT 1.26 / opset 22), ExecuTorch, MediaPipe, GPU paths on Mali, gated Gemma 3 / Llama 3.2 1B, models over 1 GB.
- **Who / status:** Claude · Proposed (research/13)

### D-042 — Compute rule: all training on the GPU box; the Mac runs one model job at a time
- **Decision:** every training or fine-tuning run happens on the owner's Vast GPU box (D-034), never on the Mac. On the 16 GB Mac at most one model process runs at a time across all agents (check `pgrep` first); phone models are benchmarked on the phone; large dataset downloads go to the box. Every agent prompt carries this rule.
- **Why:** 2026-10-17 two research agents' fine-tune pilots (9 GB and 3.2 GB of GPU memory) ran alongside an eval (3 GB) and the build daemons (2.5 GB): 17 GB of swap. Owner: "what made the mac very slow… check and fix it", then "for any training use the remote vast machine… send it to all the agents". Stopping them took free memory from 13% to 65%.
- **Who / status:** user · Rule

### D-043 — App choice: automatic profile → BM25 shortlist → one Laya pass, then a joint fine-tune
- **Decision:** per request, BM25 cuts ~200 profiled apps to 16–32 (plus the owner's remembered pick); one Laya pass answers skill and app together; per-question calibrated thresholds decide act vs ask (top 3) and the answer is remembered. Training (on the box): one joint fine-tune of laya-android with a new `app` question, a new `done` yes/no question and some `skill` rows, AndroidControl replay ~40% per epoch, embeddings frozen, encoder lr 1e-5, head lr 5e-5–1e-4, 2 epochs, shuffled options. Each new checkpoint ships with re-fitted per-question temperatures (today `LayaInput.kt` hard-codes one set).
- **Why [measured, research/15]:** on rich purposes, BM25-16 + one pass 66% vs a full tournament 58%, at one sequence instead of 14; a 4-minute pilot fine-tune lifted it to 74% (not yet significant, p ≈ 0.3) but flipped 1 of 5 tap targets without replay. Laya's own encoder as the shortlister: 36–48% recall@16, below BM25 (dead end).
- **Who / status:** Claude · Proposed; waits for the box

### D-044 — Fine-tuning data: de-named public episodes + teacher-written requests + the owner's own runs
- **Decision:** (1) AndroidControl "which app" rows with the app name stripped from the goal and soft labels; (2) requests written by a teacher LLM for app kinds AndroidControl lacks (UPI, ride-hailing, food delivery in India), with options rendered exactly as AppProfiler renders them; (3) the owner's Teach recordings and confirmed runs at 3–5× weight (DONE rows 5×), held out by task family plus the latest week; replay AndroidControl at least 1:1; every checkpoint gated on AndroidControl validation (within 1 point).
- **Why [measured, research/14]:** 93.4% of AndroidControl's app-opening goals name the app: zero-shot accuracy 98.5% with the name vs 32.0% without, so un-stripped rows teach name matching, not app understanding. AndroidControl has no goals for Paytm, PhonePe, GPay, CRED, Swiggy, Ola or Rapido.
- **Who / status:** Claude · Proposed; run-pack ready for the box

### D-045 — What Friday collects on the phone to fine-tune Laya
- **Decision:** six kinds of data, in this priority, all kept on the phone → Mac `captures/` (git-ignored) → GPU box for training → deleted after (D-033):
  1. **Teach demos:** the owner does a task once. Each step gives the screen and the owner's action, and Done marks the end. Teaches operating the owner's own apps (operation + target) and when a task is finished. *Built* (D-033, D-039).
  2. **Confirmed runs:** Laya's own runs the owner keeps with "Remember these steps", written as the same rows. *Built* (D-040).
  3. **Corrections:** when Laya proposes a wrong step, the owner taps **Show me** and does that one step; Friday records it and carries on. These rows fix exactly the mistakes Laya makes (interactive imitation, DAgger-style), so they're worth the most per row. *To build.*
  4. **Completion labels:**
     - "Not done" on every screen of 1–3, and "done" on their final screens.
     - A **"the last step was extra"** choice on the result card, for overshoots like the 1-minute-timer one in research/20.
     - These teach Laya's weakest skill, stopping. *Partly built.*
  5. **Request log (text only, no screens):**
     - What the owner typed (later: said), and the skill and app Friday chose.
     - The owner's answer when asked "which app?", and how the request ended (done / stopped / stuck).
     - Teaches understanding requests and choosing apps, and shows what to teach next. *To build.*
  6. **App profiles** (built, D-038), optionally plus **how often the owner uses each app**. Usage stats need a one-time permission and add a "used daily" signal to app choice, with nothing hand-written.
- **Not collected:** passwords (skipped already), screen images (Laya reads the accessibility tree), notifications / SMS / call logs (owner deferred them), anything while Friday is idle.
- **Later, only if the owner opts in:**
  - (a) Passive capture of normal phone use, with a text LLM writing the goal each stretch of use accomplished (reverse task synthesis, as in OS-Genesis). Big volume, but privacy-heavy.
  - (b) Friday exploring apps by itself while charging, navigation only. Needs its own safety design first.
- **Target for the first personal fine-tune (research/15 Phase 2):**
  - The owner's ~40–50 most frequent tasks × 3 demos each: about 150 demos, 600–900 steps, about 1–2 min per demo.
  - Plus every confirmed run and correction.
  - Hold out 20% of task families plus the latest week as the owner's test set.
  - Train alongside the public data of D-044: de-named AndroidControl replay at least 1:1, and teacher-written requests for Indian app kinds.
- **Why:**
  - Laya's measured failures: wrong app for long-tail requests (research/12, 15), overshooting the goal and weak DONE (research/17, 20), and picking text it can't write (research/20 → the Writer).
  - Public data doesn't cover the owner's apps: AndroidControl has none of Paytm, PhonePe, GPay, CRED, Swiggy, Ola, Rapido (research/14).
  - The owner's own screens and corrections are the only source for those.
  - Owner, 2026-10-17: "decide what are the data that we can collect to finetune laya".
- **Who / status:** user (asked) · Claude (decided) · 1–2 built; 3–5 to build next (after the wake word lands); 6 usage stats optional

### D-046 — No always-on mic: Friday is started without listening; the wake word only while charging, by choice
- **Decision:**
  - Friday doesn't keep the microphone open by default.
  - It is started by triggers that need no Friday mic:
    - **"Hey Google, open Friday"**: Google's own low-power hotword opens our app.
    - **Pixel Quick Tap**: double-tap the back of the phone → Open app → Friday.
    - The launcher icon.
  - Every launch opens ready to type: goal box focused, keyboard up.
  - The "Hey Friday" detector is an opt-in mode the owner turns on: listen only while charging.
  - When it's off, the mic is fully released.
- **Why:**
  - Owner, 2026-10-17, after testing: "the mic is always on… i want something like hey google where the mic led is not on".
  - That is impossible for a third-party app on a stock Pixel. "Hey Google" uses the low-power audio chip and hotword permissions that Android reserves for the preinstalled assistant (research/16, from the Android 16 source).
  - Any other app listening in the background shows the green mic indicator, which apps can't hide.
- **Rejected:**
  - Listening whenever the phone is unlocked (indicator always on; the owner said no).
  - Rooting to install Friday as a privileged app with the hotword permissions: breaks banking/UPI apps' integrity checks, and the Pixel's audio chip only carries Google's models.
  - Making Friday the default assistant app: it would replace Gemini/"Hey Google" on long-press; owner's choice, not taken.
  - A floating accessibility button (not asked for).
- **Later:** research/16 notes an EU ruling (2026-07-16) requiring Google to open always-on wake words to other assistants in Android 18 by August 2027. Revisit if it reaches the owner's phone [UNCERTAIN for India].
- **Who / status:** user · Claude · In progress (wake-word agent switching defaults)

### D-047 — Faster Laya steps: ask the operation first now; 8-bit MatMulNBits + a newer ONNX Runtime after a phone bench
- **Decision:**
  1. `LayaEngine.step` asks the operation alone, and runs the target row (every element again, as options) only for CLICK, LONG_PRESS and INPUT_TEXT. Nothing is padded to the long row.
  2. Next, once benched on the Pixel 8 (speed and decisions): ship the 8-bit MatMulNBits model (blocks of 32, head fp32, embedding fp16; 578 MB) with onnxruntime-android 1.31, stripping the INTERNET permission newer versions add.
  3. Keep today's fp16 file on ORT 1.20 until then.
- **Why [measured, research/18–19]:**
  - Operation-first is exact (max logit difference 1.7e-5). Non-tap steps take 24–38% of today's compute and taps 74–88%. At 20% taps, that averages about 0.41×.
  - ORT 1.20 has no fp16 CPU kernels: our fp16 model is cast to fp32 on every pass.
  - 8-bit MatMulNBits matched fp32 on 52/52 operations and 52/52 tap targets at 0.61–0.64× fp32 time (Mac, phone-like kernels). ORT 1.20 refuses it.
- **Rejected:**
  - Dynamic int8 in any form on ORT 1.20: 41–45/52 operations whatever stays in float; the cause is one activation scale per tensor.
  - 4-bit MatMulNBits: runs on 1.20 but loses 3/34 tap targets.
  - Bumping ORT while keeping the fp16 file: 2.5× slower than fp32 math on ORT 1.31 with phone-like kernels.
  - Label cap 40→32 (4% fewer tokens, 5/52 decisions changed).
  - Dropping duplicate scroll containers (4.8% fewer tokens, 3/52 changed).
  - Judging int8 on the Mac's default ORT 1.31 (its SME kernels hide the error the phone would see).
- **Side effect:** non-tap steps carry no target, so training rows and remembered paths stop storing an unused one for BACK / SCROLL, and skipped non-tap steps don't block an element.
- **Who / status:** Claude · 1 validated on the Pixel 8: same answers on 5/5 live screens; non-tap steps 1.5–4.9× faster (Play Store scroll 23.1 → 4.7 s), taps 1.1–1.8×. 2 **shipped 2026-10-18** after the phone bench (research/18 §6b): 1.73–1.85 s vs 3.83–3.90 s on the fixture (2.1×), 651 vs 753 MB, logits equal to the Mac's phone-like run (0.1816 vs 0.182). The 1.31 AAR's INTERNET and ACCESS_NETWORK_STATE permissions are removed. fp32 on 1.20 was rejected: 1.3× faster for +600 MB. Pinning ONNX Runtime's threads to the mid cores (live, 3 of 4 sat on little cores while Friday is in the `foreground` cpuset, which excludes the X3): experiment, off by default, waiting for an A/B

### D-048 — Friday learns from experience: on-phone hints now, retraining on the box; exact replay stays a shortcut
- **Decision:** two learning loops. Laya always makes the decision; remembered paths stop being scripts.
  1. **On the phone, instantly, no training.** Each finished task is an experience: the app, the goal, the steps, and what the finished screen looked like. An experience is kept when it went right:
     - the owner taps **✓ Right**;
     - an outcome check passes (music playing, the alarm in Android's alarm record, dated transactions on screen);
     - or it was taught.

     **✗ Wrong** keeps the run as a negative and forgets a path that led there. **Show me** starts Teach mode on the same goal.

     Next time a similar goal comes up in that app (shared content words, not an exact phrase), its steps go to Laya as a hint, e.g. "last time: tapped 'See transaction history'". Laya then matches the element by meaning even if it moved or was renamed. A screen that matches the remembered finished screen counts as done.
  2. **On the GPU box, periodically.** Laya-Android is fine-tuned on these experiences (taught, confirmed, verified; corrections; negatives) plus public replay (D-043, D-044). The new checkpoint ships only if it beats the current one on the owner's held-out tasks.
  - **Exact replay** of a remembered path stays as the fast path (≈2 s, no Laya) when every element is on screen.
  - When replay stops at a missing element, Laya carries on with the remaining steps as hints.
- **Why:** owner, 2026-10-17: "we might need to think smarter, trainable and learnable approach to do things, not just noting down steps. we need to make it learnable".
  - Literal replay broke when GPay reopened on another page (research/20): GPay resumed on the last run's page, and Laya wandered to a loans page.
  - Hints and retraining generalise across screens, rephrasings and new tasks in the same app.
  - The prototype survey (research/21) points the same way. LayaBrowse reports success only when the outcome is verified, and MobiMem-style memory made tasks 1.6–9× faster.
- **Rejected:**
  - Retraining only (owner chose both loops; improvement would take days).
  - Letting Friday explore apps on its own now (loop 3: later, after these two).
  - Dropping exact replay (owner kept it as a shortcut).
- **Who / status:** user (chose loops 1+2 and the shortcut) · Claude (design) · Loop 1 built and measured on the Pixel 8: a reworded Clock task got its hint and stopped at the goal (13 s, was 35 s with a Start overshoot); already-there in 6 s. Learned on the way: a finished screen is fingerprinted by what changed since the start (tab bars matched every Clock tab), selected tabs are read from the whole screen (they aren't candidates), and "done when the tap names what the goal is about" stops overshoots. Loop 2 waits for the box's SSH port

### D-049 — A task suite to judge every change; Laya picks targets from a 16-element shortlist
- **Decision:**
  1. **Task suite** (`scripts/task_suite.py`). It runs real tasks on the phone, each with automatic checks: the app in front, words on screen, the selected tab, the answer, media playing, or a system setting. It reports pass/fail, total time, Laya time and steps, saves each run to `captures/suite/`, and `--compare` diffs two runs.
     - Generic tasks live in `scripts/suite_tasks.json`; the owner's own go in `captures/suite_tasks.json` (git-ignored).
     - Runs are unattended (a step that would wait for the owner is skipped) and muted.
     - Memory is off by default, or read-only with `--memory`: the suite never teaches Friday.
  2. The bridge's `ask` takes `memory` (on / read / off), `unattended` and `shortlist`.
  3. **Target shortlist.** On screens with more than 16 elements, the target row shows the 16 most related to the goal (`shortlist()` in `Candidates.kt`): elements matching the goal's words or hint, then text fields, then the screen's top and bottom. The operation row still sees every element, so operations can't change.
- **Why:**
  - The owner: "what are the things we need to work on for making Friday really a fast assistant that does the task properly"; approved this plan ("yea do it", 2026-10-18).
  - Until now changes were judged from a handful of runs (research/22 §5.2).
  - Shortlist, on the phone, 52 pairs (research/18 §9): 30/34 same targets; the 4 changes were 1 better, 2 equivalent, 1 debatable, none worse. Tap steps on crowded screens took 0.63× the time (35 elements: about 19 s → 8 s).
- **Rejected:**
  - k = 12: one worse pick (store search → the Apps tab).
  - k = 20: less gain.
  - Shortening the operation row too: it would risk DONE and SCROLL decisions; untested.
  - Label trims (research/18 §5).
- **Who / status:** owner (asked, approved) · Claude · Shortlist measured and on (k = 16). Suite built; first baseline run pending.

### D-050 — Fixes from the owner's failed requests (2026-10-18), ahead of the task card
- **Decision:**
  1. **Payments questions get one goal.** spend_query runs Laya on "show the transaction history" whatever the wording, so the remembered path, the skim and done-on-tap work for every phrasing.
  2. **"last 10" reads 10.** It scrolls down up to 3 pages when one screen shows fewer.
  3. **The skim waits for a screen with elements.** It was being used up on GPay's empty loading screen.
  4. **An app opened by name wins.** "open bistro and …" now opens Bistro even though it isn't on the shopping list.
  5. **"open X and search for Y" searches inside X**, not Google in Chrome.
- **Why:**
  - "Just a last 10 transaction from Gpay" took 54 s: it tapped the Money tab, then the You tab, and read nothing. It shared no words with the remembered "payment history" path and its skim was spent on the loading screen.
  - "Open bistro and order a desert for me" opened Swiggy and got stuck.
  - The design review (research/23 §1.1 and §1.5) found 4 and 5 in the code.
- **Who / status:** Claude · Built (self-checks extended). The owner's retest is pending.

### D-051 — The task card: understand each request once (research/23)
- **Decision (proposed):** one `TaskCard` is filled once per request by `understand()`, from today's own rules (skill, app, about, query and slots, read or act, risk, done-check), and read by every later stage. Ship in four steps, behaviour unchanged first:
  - (a) log the card;
  - (b) skills read it instead of re-parsing;
  - (c) checks run on it (stop the moment the task is done);
  - (d) the Writer fills it when rules can't.
- **Why:** about a dozen places re-read the raw request with different word lists, and several wrong actions come straight from that (research/23 §1: ten found, two fixed in D-050).
- **Rejected:** a framework or plugin system; letting the Writer decide mode, risk, done or the app's package.
- **Who / status:** owner approved building it (2026-10-18) · design by a Claude sub-agent, reviewed by Claude · **Step (a) built 2026-10-18:** `Card.kt` `understand()` fills the card from today's rules; every request's trace and log entry carries it, and `cardSelfCheck()` covers five examples. Nothing reads it yet. Seen on the first live card: the about-words keep question words ("what is battery level"); to change in its own measured commit. Step (b) next. Open question for the owner: should "open X and …" always show X? ("open settings and check the storage" answers without opening Settings.)

### D-052 — Fixes from the task suite (14 → 20 of 24); icon labels steps 1–3
- **Decision:**
  1. **Taps** click the element itself through accessibility (re-found on the live screen), with a finger tap as fallback.
  2. **The element list:**
     - leaves out system windows (status bar, navigation bar, shade);
     - names unlabelled elements from their resource id (icon step 1);
     - adds text the app draws but doesn't mark tappable when it is at least twice the tappable elements (Compose / React Native food and grocery apps).
  3. **Understanding and done checks:**
     - "open X and …" shows X (not an API answer);
     - a search stays inside the app opened and is submitted with Enter;
     - "done" checks wait until a search has typed;
     - a switch for "turn on/off" stops once it shows the wanted state;
     - payments questions use one goal and read "last N";
     - the skim waits for a screen with elements and isn't used for searches.
  4. **Safety:**
     - taps on add, dismiss and address ask;
     - so do taps on an alarm's time in Clock;
     - the bridge survives a client that disconnects.
  5. **Icon steps 2–3:**
     - Friday names an unlabelled element it tapped by the first words the next screen gained (`meanings.json`, wiped with "Delete all learned data");
     - the service may take screenshots, for a test of whether money apps' pixels are readable.
- **Why:**
  - Task suite baseline: 14/24 tasks. The same 24 after the fixes: **20/24**, median 9.7 s (was 9.6).
  - Failures came from wrong checks, routing, Enter not pressed, a double toggle, early "done", a "%" in a label crashing the trace, the status-bar battery icon and taps landing under overlays.
  - The tests also exposed three unsafe taps on the owner's phone, now asking:
    - an alarm was created ("Add alarm" → OK);
    - "Dismiss" was tapped in Clock;
    - a Zepto delivery address was picked.
- **Still failing:**
  - Play Store updates and Amazon orders (Laya doesn't know where they live: Teach or the app map);
  - Photos search;
  - Swiggy and Zepto (their restaurant and product pages expose 0–5 tappable elements).
- **Who / status:** owner (asked for each fix and the icon plan) · Claude · Built and installed. Steps 1–3 of the icon plan are in; the step-3 GPay screenshot test is pending, and steps 4–5 depend on it.

### D-053 — Voice requests from a floating bubble, with Android's on-device recognizer
- **Decision:**
  - A floating button over every app (`Bubble.kt`, an accessibility overlay, off by default; switch in the main screen).
  - **Tap:** Friday listens (red), with live words at the top of the screen. **Tap again:** it runs what it heard; if speech ended first, it shows green ▶ and the next tap runs it.
  - **Long-press:** the typed request box. **Drag:** move it.
  - Speech uses `SpeechRecognizer.createOnDeviceSpeechRecognizer` only. If no offline model is installed, Friday says so instead of going online.
  - The button hides while a request runs or Friday is taught.
- **Why:** the owner asked for "a small bubble floating around the screen … tapping should start the listening and tap again should run the query" (2026-10-18).
  - The on-device recognizer ships no model in Friday, keeps audio on the phone, and leaves Friday without INTERNET.
  - research/21 flagged that the plain `SpeechRecognizer` some prototypes use may go online.
- **Rejected:**
  - Shipping Whisper / Vosk now: 40–150 MB and more CPU, for what the phone already does offline.
  - The default (possibly online) recognizer.
  - An always-on microphone (D-046 stands: the mic opens only on a tap).
- **Supersedes:** D-021's "typed only" for this path. Speaker verification is still deferred.
- **Who / status:** owner (asked) · Claude · Built and installed; the owner's first try is pending. If the wake word is listening while charging, the mic may report busy.

### D-054 — Understanding fixes from the owner's requests and research/23's bug list
- **Decision:**
  1. **Viewing orders, not buying.** "my … order", "last/recent/past order", "orders" and "order history/status/details" open the orders page. They use the goal "open <app> and show my orders", which a taught path replays, and then read up to the asked count of order lines ("last 5 orders").
  2. **App names tolerate a typo.** A word of 5+ letters within 1 edit of a one-word label (2 for 8+ letters) names that app ("amazzon" → Amazon). Everyday words ("alarm", "music", …) never do.
  3. **"orders"** routes to shopping.
  4. **"when's my next alarm"** is a status question.
  5. **"timer for 5"** is 5 minutes, not a 5:00 AM alarm.
  6. **The people skills** read their slots from the task part ("open google maps to navigate to MG road" → "MG road").
- **Why:** the owner asked "Show my last order that I did on Amazon" and "show mem last 5 orders in amazzon". The first searched Amazon for "Amazon" (82.7 s); in the second, neither "orders" nor "amazzon" was recognised. Items 4–6 are research/23 §1.2–1.4.
- **Who / status:** owner (reported) · Claude · Built and installed; self-checks pass. The phone was too hot to re-run the requests (thermal status 3).

### D-055 — Train our own tiny icon classifier on the GPU box
- **Decision:**
  - **Model:** MobileNetV3-Small, from scratch, 64×64 grey in, ~6.5 MB fp32 ONNX out.
  - **Data:**
    - UIST'18's 113,854 labelled Rico app icons in 99 classes;
    - Material Symbols renders (Apache-2.0) for the classes they lack (QR code, scan, camera, wallet, rupee, translate, apps, sort), for today's look of the common classes, and for an "other" class.
  - **Run-pack:** `training/icons/` (`setup.sh`, `prepare.py`, `train.py`, `run.sh`). Everything runs in `/root/friday-laya/icons` on the box.
  - **Ship gate:**
    - a confidence threshold at ≥ 95% precision; below it an icon stays unnamed;
    - a PyTorch-vs-ONNX parity check;
    - then the owner's own icons and the task suite before Laya sees its labels.
- **Why:**
  - The owner asked for it ("do it on the remote machine. I give you permission to train the Icon classifier model", 2026-10-19).
  - No released model fits (research/25: the UIST'18 network is 294 MB, 38.5M parameters).
  - Training our own is minutes of GPU time.
- **Licences:**
  - Rico's terms grant no licence to its screenshots and bind the downloader's employer. The owner accepted that for personal research, not published (2026-10-17 and 2026-10-19).
  - From scratch, so no ImageNet terms.
  - Renders-only is the clean fallback.
- **Rejected:**
  - Porting the UIST'18 network (147 MB fp32).
  - COALA's GPL classifier; captioners (0.7–1.9 GB).
  - int8 MobileNetV3: hard-swish and squeeze-excite lose accuracy under ORT int8.
- **Who / status:** owner (approved; rented an RTX 4090, 2026-10-19) · Claude · **E0 trained (research/26 §6):** 94.1% real top-1, 98.3% right / 92% named at the shipped threshold 0.7, 6.53 MB, ONNX parity 1e-6. **Shipped E0b** (E1's settings): 94.07% real top-1, threshold 0.90 (99.2% right, 86% named, 5% of unseen icons wrongly named). **E4 on the phone:** only 7 unnamed icons on the first screens of 10 everyday apps, mostly photos and logos; 1 named, wrongly (a plus-shaped logo as "add"). **E5 passed:** suite 20/25 as before, naming fired once (Spotify "share").

### D-056 — Use the rented GPU for Laya at scale: AndroidControl evaluation of Friday's input, and a half-depth student
- **Decision:** two experiments run in parallel on the rented RTX 4090. Each has its own folder and venv.
  - **research/27, Laya at scale** (`/root/friday-laya/laya`):
    - stream AndroidControl one shard at a time; keep the steps, not the screenshots;
    - reproduce Laya-Android's published test numbers;
    - then measure, on thousands of held-out steps, each of Friday's input changes (system windows out, id labels, loose text, icon names) and the shortlist's recall at k.
    - The original-format rows it writes are also research/22's replay data for later fine-tunes.
  - **research/28, a student** (`/root/friday-laya/laya-student`):
    - 11 of 22 layers (the even ones, so the global-attention layers line up), distilled from Laya-Android on AndroidControl train;
    - exported the same way as the phone model (8-bit MatMulNBits);
    - plus an analysis of how far the 256k vocabulary could be trimmed.
- **Why:**
  - The owner asked for more GPU work and research that improves the system ("you can parallelly do the GPU related things", 2026-10-19).
  - Reliability: the shortlist and the input changes were judged on only 52 screens (research/18 §9). Speed and memory: the owner wants it "as light as possible in both compute and memory".
  - Both fit the 40 GB rented disk when shards are streamed.
- **Rejected for now:**
  - Icon classifier v2: E4 found little for it to do (D-055).
  - A custom wake-word model: off by default, and the bubble (D-053) replaced it.
  - A VLM labelling the owner's screens: needs exploration screenshots first.
  - A full fine-tune on Friday's format: waits for research/27 to show which changes matter.
- **Who / status:** owner (asked) · Claude (chose the experiments; two agents run them) · **running**.

### D-057 — "Open X and search for Y" uses the app's own search before Laya
- **Decision:** when the task card's task is a search ("search for", "look for/up", "find") and the request names the
  app, Friday tries the app's own search in one launch:
  - a store's `market://search?q=`;
  - a map's `geo:0,0?q=`;
  - Android's search request (`ACTION_SEARCH` + `SearchManager.QUERY`).
  - It keeps the result only if the app is in front and its screen shows the query's words within 5 s. Otherwise it
    restarts the app and Laya does the task as before.
  - Skills can now read the task card (`SkillContext.card`); this is D-051 step (b)'s first use.
- **Why:** the suite's searches took 3–4 Laya steps of ~10 s each, and Maps failed once (2026-10-19).
  - Measured after the change:
    - Play Store 32–40 s → 2.7 s;
    - YouTube 28 s → 1.9 s;
    - Maps 47 s (failed) → 4.0 s (passes).
  - 39 apps on the owner's phone declare a search activity (Gmail, Meesho, Spotify, Keep, Contacts…). These are
    platform contracts, not per-app guesses.
- **Rejected:**
  - Per-app web search URLs (e.g. Photos' `photos.google.com/search/…` opened a results page, but not clearly for the
    query).
  - Tapping a "Search" element without Laya: kept as an idea for apps without a search intent (Photos), not built.
- **Not fixed:**
  - Photos: no search intent; Laya taps a photo on a 43-element screen.
  - Swiggy: its screens show no tappable elements to accessibility (0 candidates), so Laya can't work there at all.
- **Who / status:** Claude, from the suite's failures (the owner's standing ask: make Friday fast and reliable) ·
  **shipped, verified on the 3 suite tasks.**


### D-058 — English only, for now
- **Decision:** Friday supports English only: requests, speech, word lists, test tasks and the Writer. Hindi and
  Hinglish (research/13's language table) are parked.
- **Why:** the owner's call (2026-10-19: "we will only focus on giving support for english for now"). It also frees
  memory: most of Laya's 256k-token multilingual vocabulary (196M of its 322M parameters) is never used by English UI
  text, so research/28's vocabulary trim can keep only English tokens plus byte fallback.
- **Who / status:** owner · **in force.** research/28 was told mid-run; the app's code needed no change.
