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
- **Who / status:** Claude · **Pending** the user's agreement on the phasing

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
- **Who / status:** Claude · Proposed
