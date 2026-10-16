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
- **Who / status:** Claude · Proposed

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
