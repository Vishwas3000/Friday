# Laya as Friday's "brain": what it's good at, what it isn't, and whether it needs training

_Last updated: 2026-10-16 · Trust tags: [primary] read at source · [measured] run here · [UNCERTAIN] unconfirmed_

Harness: [`scripts/eval_assistant.py`](../scripts/eval_assistant.py) — laya-android, zero-shot, on the owner's
real list of 207 installed apps. 50 requests: 34 phrased the way the keyword rules were written ("seen")
and 16 paraphrases the rules never saw ("para"). Per-case output (with app names) stays in `captures/`.

## TL;DR [measured]
| Job | Laya zero-shot | Rules | Best design |
|---|---|---|---|
| **Route the request to a skill** (16 skills) | **82% seen / 81% para** | 100% seen / **6% para** | **Hybrid: rules first, Laya fallback → 100% / 81%** |
| **Pick the app from all 207** | **26% seen / 6% para** — not usable | — | **Rules: skill → app category → user default** |
| **Pull out the slot** ("jazz", "mom") | 0% exact / 88% overlap | — | **Rule-trimmed spans + Laya pick → 77% exact / 96% overlap** |
| **Confidence for "ask when unsure"** | routing: **≥0.9 → 100% right**, ≥0.7 → 93% | — | ask the owner below ~0.7 |

- **Laya doesn't need fine-tuning to start.** It is strong exactly where an assistant needs judgment
  (routing, paraphrases, picking among a few options, screen navigation) and weak where a fixed lookup
  table is the right tool (which app does what).
- Latency on the Mac: routing 29 ms, slot 14 ms (tiny inputs → est. ~0.5 s each on the phone).
  The 207-app tournament took 193 ms on the Mac → est. 5–8 s on the phone: too slow as well as inaccurate.

## Findings
1. **Routing generalizes; rules don't.** The keyword rules were perfect on their own phrasings and caught
   1 of 16 paraphrases ("wake me up at 7", "ring dad", "where did my money go this month" all missed).
   Laya got 13 of 16. The hybrid keeps the rules' precision and Laya's coverage.
2. **Routing confidence is calibrated.** At answer_confidence ≥ 0.9 Laya was right 100% of the time
   (44% of requests); ≥ 0.7 → 93% (60%). So "act if sure, ask if not" is principled, not a guess.
   Typical misses are low-confidence ("how much storage is left" → balance, 0.38) — the ask path
   catches them. One confident miss: "put on the office" → device_setting (0.89).
   A recurring wrong attractor is the over-broad `open_app` description — tighten it.
3. **App choice over raw labels fails, in a telling way.** Laya matches surface words, not app function:
   "set an alarm" → a sleep-tracker app ("wake"), "ring dad" → a find-my-device app ("ring"),
   "what were my expenses" → an expense-splitting app, "take me home" → a smart-home app. It knows
   no "music → Spotify". It *is* right when the app is named ("open spotify", "show my gpay
   transactions", "track my amazon order") or lexically obvious (email → Gmail 0.93, card bill → CRED).
   Its confidence here is not calibrated (≥ 0.7 still only 40–50% right), so "ask when unsure" can't
   rescue it. Both phrasings (the launcher-icon target question and a plain "which app?") scored the same.
4. **Rules shaping the options is the multiplier.** Offering only spans that start and end on a content
   word took exact slot match from **0% → 77%** with no model change. Remaining misses are skill-specific
   (a destination is the noun after "to"; a web query is the whole request minus "search for") — rules again.

## Principles for using Laya (what to build on)
1. **Ask it to choose, never to produce.** Every decision becomes a choice or yes/no question.
2. **Keep the options short and relevant — rules make the shortlist.** 207 raw apps: 6–26%.
   A skill's own app category: as good as the routing.
3. **Rules for the phrasings you know, Laya for the ones you don't** (hybrid routing).
4. **Use its calibrated confidence to ask the owner** instead of guessing.
5. **Batch questions about the same state into one pass** (`system_one` takes several questions).
6. **Yes/no checks for verification and safety** — e.g. "is the goal done?" to fix the weak DONE
   (05), "would this tap spend money or send a message?". [UNCERTAIN: not measured yet]
7. **Every approved step is a labelled example** — the data for any later fine-tuning.

## Does Laya need fine-tuning or training?
**Not to start.** [measured, above]
- **Routing:** 81% on unseen paraphrases zero-shot and calibrated; with the rules in front and "ask
  when unsure", that is usable now. *Later, optional:* fine-tune on a few hundred synthetic
  request→skill pairs to push paraphrases toward ~95% — cheap (laya ships `train.py`; minutes on a 4090).
- **App choice:** **don't train it — replace it with rules.** Which app plays music is a fixed fact about
  the owner's phone, not a judgment; a lookup table + the owner's default beats any model.
- **Screen navigation (op + target):** already fine-tuned (laya-android). Known weak spots: crowded
  screens, DONE (05), and Indian apps that AndroidControl likely never saw (GPay/PhonePe — untested).
  *Later:* fine-tune on Friday's own approved steps in the owner's apps. Per-step approval turns
  every run into labelled data for free.
- **Calibration:** re-fit temperatures on our own data with `laya.calibrate` once we have runs.
- [UNCERTAIN] Base Laya (`convaiinnovations/laya`, the general model) may route better than the
  Android fine-tune; not tested. It would be a second model in RAM (D-016), so only if routing disappoints.

## Dead ends
- Raw app choice over the full launcher, in either phrasing (26% / 6%).
- Untrimmed slot spans: Laya prefers longer spans that still contain the command word ("play jazz music").
