# 20 — Why each step is slow, and UI memory (Laya "learning the UI")

_2026-10-17 · Pixel 8 (Tensor G3), Laya-Android fp16 on ONNX Runtime 1.20 CPU (4 threads) · Trust tags: [measured] run here · [UNCERTAIN] unconfirmed_

The owner asked: "the flow where it sees the screen and taps is very slow, how can we speed it up — or can Laya learn the UI, because apps don't change their UI a lot?"
Short answer: the screen read is cheap. **Laya's pass is the cost, and it grows with how much is on the screen.**
Remembering paths that worked and replaying them without Laya makes repeat tasks about **10× faster** [measured].

## 1. Where a step's time goes [measured]
The `ask` bridge command now traces every step: read time (with the settle wait), Laya time, candidates and input tokens.

| Task (screen) | Candidates | Tokens | Laya pass | Screen read (settle) |
|---|---|---|---|---|
| Clock, Timers tab | 6 | 390 | 2.9 s | 0.3 s (0) |
| Clock, Timers tab | 11 | 518–528 | 3.0 s | 0.8 s (0.4–0.5) |
| Clock, Alarms tab | 22 | 914–924 | 6.0–12.3 s | 0.6–0.8 s (0.25–0.5) |
| Clock, from Alarms | 19 | 1110–1118 | 8.5–9.0 s | 0.2–0.8 s |
| Play Store, Books | 23 | 1320 | 17.3 s | 1.0 s (0.6) |
| Play Store, Books | 35 | 1868–1884 | 24.4 s, then 38.8 s | 0.5–0.8 s |

- **Laya dominates.** Its time grows with input tokens. Each candidate is an option of up to 48 tokens, and content-heavy screens such as Play Store cards hit the cap.
  - The same token count varies up to about 2× between runs: 924 tokens took 12.3 s and later 6.0 s. A hot phone throttles: the 38.8 s pass came right after a 24.4 s one on the same screen size.
  - That fits the model's size. It has about 126M non-embedding parameters per token, so 1,800 tokens is roughly 0.45 TFLOP. That is tens of seconds for a phone CPU in fp16. [UNCERTAIN: estimate, see research/18 and 19]
- **The screen read is not the problem.** It takes 0.2–1.0 s. The 300 ms "quiet" settle plus banner hiding is most of that.
- **On the Play Store all 35 candidates are app content,** not the status bar or keyboard (both already excluded). Long concatenated labels and scroll containers duplicate their first item. This is where trimming can save tokens (research/18).

## 2. UI memory ("routes"), D-040 [measured]
**Idea.** Apps rarely change their screens. When a path finished a goal once, Friday stores each step's element by signature:
- package, resource id, class and label.

Next time it finds each element on the live screen and acts directly, with **no Laya pass**. The first element that isn't on screen hands the rest of the task to Laya, which carries on from there with the history so far.

**Where paths come from:**
- **Teach demonstrations.** A path is saved at Done, starting inside the app the owner opened. Launcher detours are left out, such as the drawer search box and typing "clock".
- **Runs the owner confirms.** The answer card lists the steps ("I did: tap “Timers”") and offers **Remember these steps**. A confirmed run also writes Teach-format rows (`source: "run"`), so it becomes fine-tuning data (research/14 §5).

**Results on the Pixel 8:**

| Request | First time (Laya) | Next time (memory) |
|---|---|---|
| "open the clock app and go to the timers tab", replayed as "open clock and go to timers" | 22.6 s (8.5 s + 6.6 s Laya) | **2.2 s**, one step, read 0.34 s, no Laya |
| Taught: "Open the Clock app and go to the Stopwatch tab", replayed as "open clock and go to the stopwatch" | (demonstrated) | **2.0 s**, no Laya, landed on the stopwatch |
| The same request while the remembered element was missing | n/a | fell back to Laya cleanly: "“1m” isn't on this screen → Laya", then CLICK Timers, DONE |

**How a step is found again:**
- Same package.
- Among elements with the same class and resource id, the one with the same label.
- Else the only element with that id. This survives text that changes, such as a counter or a time.
- With no id, the label must match exactly one element. Two look-alikes mean "not found", and Laya decides.

Replayed steps still go through `Safety.verdict`: a risky step still asks.

**Task keys:**
- A task's key is its content words, ignoring order, with plurals folded and filler words dropped. So "Open the Clock app and go to the Timers tab" equals "open clock and go to timer".
- "turn on wifi" ≠ "turn off wifi".
- A path that types text from the request ("search for <pizza>") is keyed without that text and refilled each time.

## 3. What went wrong on the way (kept so it isn't repeated)
- **Laya overshoots, so paths must be confirmed.** Clock opened already on the Timers tab, and Laya still tapped the "1m" preset (op 0.68) before saying DONE. That **started a real 1-minute timer** (it rang; stopped and deleted afterwards).
  - My test auto-confirmed that run, so it remembered a wrong path. It was deleted.
  - Hence: only Teach paths and owner-confirmed runs are kept, and the card lists the steps.
  - A completion check (research/15's `done` question) is the real fix.
- **A guessed slot made keys collide.** Skills guess "text to type" even for tasks that type nothing ("go to the timers tab"). Removing those words left the key "clock open", which would match *every* "open clock and …" request.
  - Fix: fixed paths are keyed on all the goal's words. Typed text is left out only for paths that actually typed it.
- **The Settings search case needs the Writer.** "open settings and go to about phone": Laya tapped the Settings search box and Friday wanted to type "settings and go", the best of the request's spans.
  - Laya can only pick text, not write it. The right query ("about phone") is a job for the text LLM (D-032 / D-041, research/13).

## 4. Next speed levers (ranked)
1. **Memory for repeat tasks:** done. The gain grows with use. Teach the owner's frequent tasks once.
2. **Fewer tokens per pass:** label cap and duplicate-container trimming, measured in research/18. A mixed-precision int8 model with the decision heads kept un-quantized was about 3.5× faster on a small input earlier (research/06); being re-tested in research/18.
3. **Fine-tune on the owner's data on the GPU box:** fewer wrong steps (research/14, 15).
4. **Best achievable on this phone class:** research/19.

## Not checked
- Memory on apps whose elements have neither resource ids nor stable labels.
- Multi-step paths longer than 1 step on the phone.
- Paths that type text.
- How often real apps change their UI enough to break a path. The fallback handles it, but the rate is unknown.
