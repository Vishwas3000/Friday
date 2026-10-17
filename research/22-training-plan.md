# 22 — How to train Laya for Friday (the plan), and what else to look into

_2026-10-18 · Consolidates research/14 (data), 15 (method), 18–19 (speed), 20 (UI memory), 21 (prototypes) and D-043..D-048 · Trust tags: [measured] run here · [primary] read at source · [UNCERTAIN] not yet checked_

## The short answer
Don't train Laya once. Run a **data flywheel**:
1. Friday collects experiences on the phone.
2. A teacher model and the owner turn the mistakes into labels.
3. Laya-Android is fine-tuned on the GPU box on all of it, with the public data replayed so nothing is forgotten.
4. A new checkpoint ships only if it beats the current one on the owner's own task suite.

Every turn of this loop is cheap (minutes of training, research/15). The biggest gains come from training on the states **Laya itself gets wrong**, not only on clean demonstrations.

## 1. What Laya must learn (one model, several questions)
Laya answers multiple-choice questions, so each skill is just another kind of row in the same fine-tune.

| Question | What it decides | Where it's weak today [measured] | Rows from |
|---|---|---|---|
| `operation` | tap / scroll / type / back / done… | overshoots after the goal; doesn't think to scroll (research/20) | demos, corrections, AndroidControl replay |
| `target` | which element | unfamiliar apps: GPay "Pay anyone" for "payment"; YouTube suggestion arrows | demos, corrections, teacher labels |
| `done` (new, yes/no) | is the goal already met on this screen? | DONE recall 0.55–0.75 [primary]; its overshoots started real timers | every screen of every demo (no = before the end, yes = the end) |
| `app` (new) | which installed app for a request | 48% zero-shot on automatic profiles (research/15) | de-named AndroidControl, teacher-written requests, the owner's request log |
| `skill` | which skill handles a request | rules cover common phrasing; Hinglish untested | request log, MASSIVE / Hinglish-TOP |
| hint-aware `target` | use "last time: tap X" when it fits, ignore it when stale | out of distribution today (works lexically, zero-shot) | the same rows with correct **and** stale hints added to the goal |

## 2. The data, in order of value per row
1. **Corrections on Laya's own mistakes (DAgger-style).** The state Laya actually reached, with the right action. These teach exactly what it gets wrong. Sources:
   - the owner: "Show me" on the result card (built, D-048);
   - a teacher model (§5.1, to look into) that labels every screen Friday visits.
2. **Teach demonstrations** (built, D-033 / D-039). Clean paths, including scrolls and the finished screen (DONE). Weight 3–5×; DONE rows 5× (research/14).
3. **Verified or confirmed runs** (built, D-048): the outcome checked out, or the owner tapped ✓. Weight 1×.
4. **Rejected runs** (✗ Wrong, blocked or skipped steps): negatives. Kept apart until we know how laya's trainer can use "not this option" rows ([UNCERTAIN]: soft labels with the rejected option at 0 are one way).
5. **Public replay:** AndroidControl episodes (processed with laya-android's own builder) at least 1:1 with personal rows. Its app rows are de-named, because 93.4% of its goals name the app (98.5% → 32.0% without the name, research/14).
6. **Teacher-written requests** for app kinds public data lacks (UPI, ride-hailing, food delivery in India). Options are rendered exactly as AppProfiler renders them on the phone.

**Target for the first personal fine-tune:**
- About 150 owner demos: the ~40–50 most frequent tasks × 3, about 1–2 min each (D-045).
- Every confirmed or verified run, and corrections.
- 20% of task families plus the latest week held out as the owner test set. Never split by step.

## 3. The recipe (research/15, measured pilots)
- **Base:** `ByunByun/laya-android` (pinned revision). laya-android itself took 3 epochs, 2.7 h on a 4090.
- **Joint multi-task fine-tune with `laya-train`:**
  - embeddings frozen (196M of the 322M params are the 256k-token table);
  - encoder lr 1e-5, head lr 5e-5–1e-4;
  - 2 epochs, effective batch 32;
  - soft-CE, shuffled options;
  - AndroidControl replay about 40% of each epoch.
- **Pilot evidence:**
  - 741 rows / 4 min lifted app choice 58 → 66% (tournament) and 66 → 74% (BM25 shortlist + one pass). Not yet significant (p ≈ 0.3).
  - Without replay it flipped 1 of 5 tap targets, so replay is required.
- **Gates for every checkpoint:**
  1. AndroidControl validation within 1 point of the base.
  2. Better on the owner test set.
  3. Better on the **on-phone task suite** (§5.2).
  4. Per-question temperatures re-fitted and shipped with it (today `LayaInput.kt` hard-codes one set; the pilot moved it 1.16 → 1.51).
- **Ship:**
  1. ONNX export with the bit-exact check (research/06).
  2. 8-bit MatMulNBits (52/52 decisions kept, research/18) on onnxruntime-android 1.31, with INTERNET stripped from the manifest.
  3. Push to the phone and run `decide_test` + `step_parity`.
  4. A/B on the suite against the current model; keep the old file for rollback.
- **Cadence:** retrain from the base on all data so far (not from the last checkpoint, so errors don't compound), weekly or when about 50 new demos/corrections arrive.

## 4. Where it runs
- **GPU box** (D-034, D-042): everything that trains. The run-packs for research/14 and 15 are ready. **Blocked on the instance's SSH port** (<previous-port> / <previous-port> refused).
- **Mac:** at most one small model job at a time; no training (D-042).
- **Phone:** collection, inference, the task suite. Training data leaves the phone only to the Mac (`captures/`, git-ignored) and the box, and is deleted after training (D-033).

## 5. What else to look into for this goal (ranked)
1. **A local teacher model on the box to label Friday's own screens ("AI DAgger").** [UNCERTAIN: which model]
   - For every state Friday visits (logged rows), a strong open GUI-agent model picks the right action. Laya's input is the accessibility tree as text, so a large text LLM fits too.
   - This turns every run, good or bad, into labelled training data without the owner demonstrating. It's the main way to scale beyond about 150 demos.
   - It must run **on the box**, not a cloud API: screens are personal.
   - To check: the box's VRAM, and teacher accuracy on our 52 measured (screen, goal) pairs.
2. **An on-phone task suite: the north-star metric.**
   - About 30 of the owner's real tasks, each with an automatic check where possible (media playing, the alarm in Android's alarm record, a screen word present), run before and after every model or code change.
   - Today we judge from a handful of runs. Build this before the first fine-tune.
3. **Hint-aware training.** Loop 1 puts "last time: …" into Laya's goal. Train with right and stale hints, or Laya may follow a wrong hint.
4. **Unlabelled elements.** Icons without text appear to Laya as `- (FrameLayout)`, and it has no vision. Options:
   - resource-id words as labels when there's no text (`tab_menu_stopwatch` → "stopwatch"; cheap, but must change the Python and Kotlin builders together and be trained with);
   - later, a tiny on-device icon captioner.
5. **A smaller, faster student, after the first fine-tune:**
   - prune the vocabulary to the tokens actually used (shrinks the 196M embedding table: RAM, not compute);
   - distil into fewer layers (compute).
   - Laya passes are the cost per step: 390 tokens 2.9 s … 1,868 tokens 24–39 s (research/20).
6. **An emulator farm for self-play.** Android emulators on the box would let Friday run thousands of tasks on common apps (Clock, Settings, YouTube, Maps, Chrome) with automatic checks, keeping successes as data (rejection sampling). [UNCERTAIN] Vast instances are containers, and emulators need `/dev/kvm`. Check on the box; without it, skip.
7. **Hinglish and Hindi requests.** mmBERT is multilingual, but routing and app choice are untested on Hinglish. Add Hinglish-TOP-style rows and a few owner requests to the suite.
8. **Shipping models to the phone safely.** A versioned model file, a sha256 check, parity and suite checks on the phone, and rollback. Today models are pushed by hand over adb.

## Not checked
- Which teacher model the box can run.
- Whether `laya-train` supports negative-only rows.
- `/dev/kvm` on the box.
- Hinglish accuracy.
- Any fine-tune at full scale (only the 4-minute pilot ran).
