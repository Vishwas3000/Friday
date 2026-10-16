# Picking the right app: give Laya each app's purpose, and narrow by rule when a skill fits

_Last updated: 2026-10-17 · Trust tags: [primary] read at source · [secondary] search/recall · [measured] run here · [UNCERTAIN]_

Harness: [`scripts/eval_apps.py`](../scripts/eval_apps.py) — laya-android, zero-shot, the owner's 207 installed
apps, 50 requests (34 that one of Friday's 16 skills covers, 16 long-tail ones it doesn't, e.g. "how many steps
did I walk today"). A purpose + kind per app lives in `captures/app_purposes.json` (git-ignored: it is the
owner's app list). Per-case output stays in `captures/`.

## TL;DR [measured]
| Setup (a `predict_tournament` "which app?" over the apps) | skill-covered (34) | long tail (16) | all (50) |
|---|---|---|---|
| **A** app names only | 32% | 44% | 36% |
| **B** name + purpose ("Fit - activity and steps tracking") | 50% | **75%** | 58% |
| **C** route → keep only the skill's kinds of app → B among those | **91%** | 19% | 68% |

- **Laya understands apps once it is told what they are for.** Purposes lift the long tail 44% → 75% with no
  training. With names alone it matches words: "play" → *Play Games*, "wake" → a sleep tracker, "ring" → find-my-device.
- **Narrowing by rule wins when a skill fits (91%), and fails when one doesn't (19%)**: a fixed skill list
  forces long-tail requests into the wrong skill ("steps" → navigate → Maps, "recharge my phone" → device_setting).
- **Best design: C when the request fits a skill, B when it doesn't** (≈86% on this set: 31/34 + 12/16).
- **B's confidence is usable**: ≥ 0.5 → 90% right (40% of requests), ≥ 0.7 → 92%. Below that, ask the owner
  with the top few and remember the answer. C's confidence is weaker (its misses are wrong shortlists).
- B still misses most music requests ("play" pulls toward *Play Games*/*Google*); C fixes them. Another reason
  to prefer C whenever routing is confident.
- Cost on the Mac: 0.4 s per request for routing + three tournaments; B over all apps is ~13 groups, so on the
  phone it is the expensive path (est. several seconds warm) — fine for the long tail only.

## How experts give an assistant "app knowledge" [secondary unless noted]
1. **Read what apps declare.** Android apps register intent filters (`upi://` for every UPI app, play-from-search
   for music players, `google.navigation:` for maps, `tel:`, `mailto:`) — queryable on the phone with no
   maintenance [primary: Android PackageManager]. Google Assistant's *App Actions* has apps declare capabilities
   (built-in intents, incl. health/fitness ones) in `shortcuts.xml` [secondary].
2. **Retrieve by description, then let the model choose a few.** Tool-use research (ToolLLM's API retriever;
   "Tool documentation enables zero-shot tool-usage") finds descriptions matter more than names — matching B/C here.
3. **Owner priors.** Remember the owner's picks and corrections; optionally weight apps by real use (UsageStats,
   needs the owner's grant).
4. **Explore once, keep notes.** Phone-agent research (AutoDroid, AppAgent) explores each app once and keeps a
   memory of what its screens do, then reuses it.
5. **Use a data API instead of an app when one exists.** Steps / sleep / calories: Health Connect (Android's
   on-phone health store; Fit and Fitbit sync into it), read with the owner's permission — no screen reading.

## Plan (D-031)
- App knowledge = purpose + kind per app from (a) a built-in catalog of popular public apps, (b) intent-filter
  capability discovery on the phone, (c) the owner's corrections. The owner's own list never leaves the phone.
- Routing: rules or confident Laya → the skill's kinds (C); otherwise Laya over all apps with purposes (B);
  low confidence → ask with the top 3 and remember.
- Health questions → Health Connect, with Fit's screen as the fallback.

## Dead ends
- Names-only app choice (A), again (36% here; 6–26% in research/09 with a different set).
