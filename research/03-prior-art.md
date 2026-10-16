# Prior art: who has made an AI operate an Android phone (fully local, and with Laya)

_Last updated: 2026-10-16 · Trust tags: [primary] read at source · [secondary] search/blog/forum · [measured] run here · [UNCERTAIN] unconfirmed or conflicting_

**Scope.** This file covers who has already built AI that operates an Android phone, with extra weight on fully local setups and on anything built with Laya.

**Where we looked:**
- GitHub, with repo metadata from `gh api` (all ★ star counts and created / last-push dates read 2026-10-16).
- Hugging Face, arXiv, vendor docs, HN and blogs.

Reddit was blocked to our crawler, so r/LocalLLaMA and r/androiddev coverage is thin [UNCERTAIN].

**What was run here.** Nothing in this file except the [measured] items. Those come from this repo's live run on the Pixel 8 + M5, in [05-live-tests.md](05-live-tests.md).

## TL;DR

- **No one has published Laya (or any "System 1" decision model) driving a real phone end to end, fully locally, with a task success rate.**
  - Our live run is the first we can find: 4 of 5 episodes reached the target screen, and 2 of those stopped correctly [measured, 05-live-tests.md]. "First" rests on search only [UNCERTAIN].
  - What exists elsewhere [primary]:
    - Laya-Android: offline AndroidControl only.
    - A Claude Code skill that drives adb with *zero-shot* Laya ("close to random"; the cloud agent takes over).
    - A fine-tune that predicts only the operation.
    - An on-phone app that can call a self-hosted Laya as a safety gate.
- **The closest design is Jev-Mobile** (arXiv 2609.30186, 2026-09-24) [primary].
  - A VLM sets goals at low frequency. A typed decision model (TypeSafe Jev) picks among live a11y-tree candidates at high frequency.
  - AndroidWorld: 0.79, against 0.84 for a step-wise VLM. Executor time per success fell from 94.3 s to 5.2 s.
  - Both models are cloud: Qwen3.8-Max via OpenRouter, and hosted Jev.
  - Community servers already expose Laya behind Jev's `/v1/systemone` API: stuntd, and `laya-apple serve` [primary]. So about six Jev phone projects are near drop-in references for us: droidrun/mobile-jev, jev-mobile ×2, JevDroid and others.
- **The closest local precedent that reads only a11y text is V-Droid** (AW 59.5) [primary].
  - An 8B verifier scores every extracted candidate, at 0.7 s per decision on 2×4090.
  - It used GPT-4 for working memory and ran on an emulator only.
  - Its ablation: scoring each candidate separately (47.4) beat a multiple-choice selector (35.8). That bears directly on Laya's `choice` vs `noul` heads.
  - LiMAC (520M action model, VLM only for text) is the closest "small policy + System 2 writes the text" split.
- **The top AndroidWorld scores all use frontier cloud models** [primary]:
  - DroidRun/Mobilerun 91.4% (2025-10), then 100% with "GPT-6 Astra low".
  - Minitap 100%. In Minitap's ablation, swapping its main reasoner for Qwen3-VL-8B dropped AW to 11.2%.
  - Individuals running small local models report loops and abandoned tasks [primary/secondary].
- **Where Laya-Android sits** (AndroidControl-High Type 76.7 / Grounding 61.7) [primary]:
  - The only like-for-like comparison uses the same InfiGUI-R1 evaluator: InfiGUI-R1-3B scores 82.7 / 74.4 / SR 71.1, with 10× the parameters and screenshots as input.
  - Screenshot models of 2–9B fine-tuned on AndroidControl reach Type 81–87, under looser click rules.
  - The best AW scores for open models of 10B or less are all self-reported, on mixed harnesses: UI-Venus-2-9B 80.2, MobileRL-9B 80.2, UI-Venus-1.5-8B 73.7, MAI-UI-8B 70.7, GUI-Owl-1.5-4B 69.8.
- **A local "System 2" fits the M5 16 GB but is slow.**
  - GUI-Owl-1.5-4B (MIT licence, about 5.5 GB) and Qwen3-VL-4B/8B (official GGUF, MLX and Ollama builds) fit.
  - Expect roughly 5–11 s per screenshot step [UNCERTAIN, estimate], so keep it out of the per-step loop.
  - AutoGLM-Phone-9B took 13–18 s per step on a Mac [secondary].
- **Platform, as of Oct 2026:**
  - Gemini "screen automation" has shipped as a beta on Pixel 10/11 and S26-class phones only (not the Pixel 8). It runs on Computer Control, an OEM-privileged API with no third-party path [primary].
  - Google Play bans autonomous AccessibilityService agents (announced 2025-10-30) [primary], so research runs over host ADB or a sideloaded companion app.
  - Apps fight back: WeChat and banks logged out Doubao-phone users, and 155 apps refuse its GUI agent [secondary].
- **Pitfalls everyone hits:**
  - `uiautomator dump` takes 1–5 s. Ours took 2.4–3.8 s, about 90% of each step [measured]. On-phone accessibility services read the tree in tens of ms [secondary].
  - Unicode text via `adb input text` breaks.
  - The a11y tree has holes in Flutter, React Native, WebView and canvas UIs.
  - FLAG_SECURE screens come back as black screenshots.
  - Agents stop too early or never stop, and they loop.
  - For Laya specifically, target accuracy collapses as the candidate list grows, and the model ships overconfident [primary/secondary].

## Projects comparison

### A. Laya and other System-1 decision models driving phones (the closest prior art)

| Project (author) | Link | Created / last push | ★ | Decision model · System 2 · local? | Runs on | Reads | Acts | License | Maturity | Reported result | Tag |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Laya-Android (Byun Jaeyeon) | github.com/Byun11/laya-android · HF ByunByun/laya-android | 2026-10-07 / 10-07 | 1 | laya-multilingual 322M fine-tune · none · local | host GPU (4090, ~40 ms) | a11y candidates, text only | – (offline eval) | Apache-2.0 | v0.3.0, offline | AC-High Type 76.7 / GR 61.7 / SR n/a (InfiGUI-R1 evaluator, 8,444 steps) | [primary] |
| laya-android skill (surapat-codediva) | github.com/surapat-codediva/laya-android | 2026-10-01 / 10-02 | 0 | base Laya, zero-shot · host coding agent (Claude Code etc.) takes over when Laya is unsure · Laya local, agent usually cloud | host + adb | `uiautomator dump` + focused activity (~2 s) | `adb input` | MIT | 5 commits, emulator timings | none | [primary] |
| laya-android-control (arnabwithab) | HF arnabwithab/laya-android-control | 2026-10-10 | – | base Laya fine-tuned on 18,354 AndroidControl rows, operation only | – | goal + operation spec | – | Apache-2.0 | model card only | operation acc 0.261 → 0.665 | [primary] |
| mobile-agent (KYRIE66nb) | github.com/KYRIE66nb/mobile-agent | 2026-09-26 / 10-11 | 99 | cloud chat/VLM (any OpenAI-compatible) · optional **self-hosted Laya** or Jev as safety gate and for low-risk choices among local candidates | **on-phone app** (Kotlin) | AccessibilityService tree; screenshots via optional root VirtualDisplay | AccessibilityService; optional root | Apache-2.0 | early; tested on a Redmi, Android 16 | none | [primary] |
| Jev-Mobile (Linghua Zhang) | arXiv 2609.30186 | 2026-09-24 | no code | TypeSafe Jev (hosted) · Qwen3.8-Max via OpenRouter · **cloud** | host | live a11y tree → candidates | "Android executor" (unspecified) | – | paper | AW 0.79 vs 0.84 step-wise VLM, 0.78 SeeAct-V | [primary] |
| mobile-jev (droidrun) | github.com/droidrun/mobile-jev | 2026-09-17 / 09-17 | 448 | Jev (cloud); text is an exact span copied from the goal | Mobilerun cloud phones | indexed controls via Mobilerun API | Mobilerun API | MIT | 1-commit demo | Uber flow ~21 s for 9 actions | [primary] |
| jev-mobile (xinwang-nwpu) | github.com/xinwang-nwpu/jev-mobile | 2026-09-21 / 10-07 | 5 | Jev (cloud) · small LLM for typed text, VLM fallback (OpenAI-compatible, so could be local) | host + adb | Jev Bridge app → Portal ContentProvider → uiautomator fallback | `adb input` | MIT | real-phone demo | Bilibili task 18 s; decision mean 540 ms | [primary] |
| jev-mobile (Friedjof) | github.com/Friedjof/jev-mobile | 2026-09-17 / 09-18 | 10 | Jev (cloud) · optional LLM for one recovery step | host + USB | Portal over adb, or companion AccessibilityService; screenshots at escalation | journaled adapter; IME for Unicode | MIT | "hardware-validated" on Google Keep | none | [primary] |
| JevDroid (antiyro) | github.com/antiyro/jevdroid | 2026-09-18 / 09-19 | 3 | Jev via Vercel / TypeSafe (cloud) | host | a11y tree (ADB / UIAutomator2) | ADB | MIT | early alpha | 78.6% on 56 synthetic decisions; 314 ms median | [primary] |
| *Browser/desktop analogues built on Laya (lessons transfer)* | | | | | | | | | | | |
| laya-browser (cklxx) | HF cklxx/laya-browser | 2026-09-21 → 09-29 | – | Laya 322M full fine-tune · local Qwen3-8B-AWQ writes TYPE_TEXT values · **fully local** | 1× RTX 4070 Ti SUPER | page text + action list | jev-ultrafast harness | – | v19s | suite C (27 held-out multi-step tasks ×2) ~20–26%; 22–27 ms per decision | [primary] |
| laya-browser-v1 / laya-desktop-v1 (ikaanakkok) | HF ikaanakkok/* | 2026-10-05 | – | continues laya-browser v17s | RX 7800 XT; Raspberry Pi 5 | DOM / AT-SPI a11y TSV | – | MIT (desktop) | research | element top-1 0.539 (browser), 0.231 (desktop) | [primary] |
| jev-ultrafast (browser-use) | github.com/browser-use/jev-ultrafast | 2026-09-16 / 09-30 | 23,055 | Jev (cloud) for operation + target; small LLM only for typed text | host | numbered element table, no screenshots | CDP | MIT | MVP | Google Flights task 7.1 s (3 repeats) | [primary] |

**Notes on group A**

- **Laya-Android** [primary]:
  - Zero-shot it scores 29.0 / 11.8; fine-tuned, 76.7 / 61.7.
  - Against Jev 1.13.0 on the same 1,000 steps: 77.2 / 62.8 vs 67.2 / 56.7, at 39 ms vs 236 ms median.
  - It loses 9–12 points on unseen apps, tasks and categories, and is weak on WAIT, BACK, horizontal scroll and LONG_PRESS.
  - The card advises against unsupervised real-device use.
  - Its internals are covered in another file.
- **surapat-codediva skill** [primary]:
  - Its roadmap lists the same bottlenecks we measured. Phase 3: a persistent daemon and adaptive settle. Phase 4: a companion AccessibilityService that settles on events (150 ms quiet period), an `EXECUTE_AND_WAIT` call, and snapshot IDs against stale targets.
  - The same action on the same screen structure a third time triggers a handoff.
  - `adb shell input text` cannot type Thai or other Unicode text, and secure windows return black screenshots.
- **KYRIE66nb mobile-agent** [primary]:
  - The safety gate fails closed: on a timeout or error, the action goes to the human.
  - Read-only actions bypass the gate.
  - The external decider may only choose among candidates built locally.
- **Jev-Mobile** [primary]:
  - Candidates come from visible, enabled nodes with valid bounds, typed by their flags: click, long-press, scroll, focus, input. Back, Home, Enter and Open-app are added.
  - Each candidate is checked against a fresh observation before it runs. After every action the device is re-observed and the candidate list rebuilt.
  - When a non-idempotent action gets an ambiguous response, the agent inspects rather than resubmitting.
  - Failures are split into **coverage** (target missing from the tree), **selection** and **handoff**.
  - Only ASCII input is supported, and there is no visual fallback for targets missing from the tree.
- **mobile-jev and xinwang jev-mobile** [primary]:
  - DONE is not proof, so verify against device state.
  - xinwang asks a separate yes/no completion question in the same request. It rejects DONE below 0.5, and after repeated rejections hands off to a visual path.
  - mobile-jev never generates text: it copies an exact span of the goal.
- **Friedjof jev-mobile** [primary]: on Android 11, Mobile MCP's UiAutomation connection suppressed third-party accessibility services. Run one UI-tree backend at a time.
- **laya-browser** [primary]:
  - "Stopping too early" is about 60% of real-site failures.
  - These did not help: a sub-goal planner at run time, a `noul` completion head, and more DONE data.
  - When the GPU drops to idle clocks, decisions go from 22–27 ms to 75–270 ms.
  - The author: "A 322M single-pass policy does not reliably read these goal distinctions."
- **laya-browser-v1 and laya-desktop-v1** [primary]:
  - Target accuracy falls as candidates grow (parent model): 61.7% with 2, 33.3% with 8, 11.7% with 32, and about 2% on the full list (~151).
  - Choosing within 12-option chunks beat an embedding shortlist.
  - A shipped temperature of 5.0256 caused failures; refitting it to 1.7046 fixed them.
  - The desktop card: "the target head is a re-ranker, not a selector".

### B. Phone-control frameworks

| Project (org) | Link | Created / last push | ★ | Model(s) · local? | Runs on | Reads | Acts | License | Maturity | AW / other | Tag |
|---|---|---|---|---|---|---|---|---|---|---|---|
| DroidRun → Mobilerun (droidrun) | github.com/droidrun/mobilerun | 2025-04-12 / 2026-10-15 | 9,670 | any provider; Ollama and OpenAI-compatible supported; results come from cloud models | host + Portal app | a11y tree + screenshots (`--vision`, `--vision-only`) | Portal + ADB | MIT | active; cloud product | AW 91.4% (2025-10-03, models unnamed); mobile-harness 100% ("GPT-6 Astra low") | [primary] |
| Mobilerun Portal | github.com/droidrun/mobilerun-portal | 2025-04-12 / 2026-10-02 | 390 | – | on-phone AccessibilityService app | `a11y_tree(_full)`, `phone_state`, screenshots | gestures and keyboard input via ContentProvider, HTTP :8080, WS :8081 | NOASSERTION | active | – | [primary] |
| Minitap mobile-use | github.com/minitap-ai/mobile-use | 2025-08-16 / 2026-10-14 | 3,265 | cloud LLMs; local via OPENAI_BASE_URL | host + ADB (USB / Wi-Fi) | UI hierarchy first, screenshot when needed | ADB | Apache-2.0 | active; paper | AW 100% (arXiv 2602.07787) | [primary] |
| Open-AutoGLM (Zhipu / zai-org) | github.com/zai-org/Open-AutoGLM | 2025-12-08 / 2026-03-06 | 26,409 | AutoGLM-Phone-9B (MIT weights) on local vLLM/SGLang (NVIDIA ≥24 GB) or cloud API | host + ADB / HDC | screenshots only | ADB + ADB Keyboard | Apache-2.0 | popular; 50+ Chinese apps | no published benchmark | [primary] |
| GELab-Zero (StepFun) | github.com/stepfun-ai/gelab-zero | 2025-11-28 / 2026-05-11 | 2,284 | GELab-Zero-4B-preview, local via Ollama / llama.cpp | host + ADB | screenshots | ADB | MIT repo, Apache-2.0 weights | preview; MCP server | AW "75.86%", protocol unstated [UNCERTAIN] | [primary] |
| Mobile-Agent v1–v3.5 (Alibaba X-PLUG) | github.com/X-PLUG/MobileAgent | 2024-01-26 / 2026-07-07 | 9,302 | GUI-Owl / GUI-Owl-1.5 open weights, or cloud | host | screenshots | ADB | MIT | active | Mobile-Agent-v3 AW 73.3 (framework, 32B) | [primary] |
| Midscene.js Android (ByteDance web-infra) | github.com/web-infra-dev/midscene | 2024-07-23 / 2026-10-16 | 15,303 | screenshot VLMs (UI-TARS, Qwen3.x, AutoGLM, …); configurable base URL, so local is possible | host (Node) | screenshots | ADB, scrcpy | MIT | active | – | [primary] |
| AppAgent (Tencent) | github.com/TencentQQGYLab/AppAgent | 2023-12-20 / 2025-03-19 | 6,904 | GPT-4V / qwen-vl-max (cloud) | host + ADB | labelled screenshots, grid overlay | ADB tap / swipe | MIT | dormant | none in repo | [primary] |
| AutoDroid (MobileLLM) | github.com/MobileLLM/AutoDroid | 2023-10-23 / 2024-03-22 | 500 | GPT-4/3.5 (cloud); on-device Vicuna | host + DroidBot | view hierarchy | DroidBot / ADB | MIT | dormant; MobiCom 2024 | DroidTask (158 tasks): 90.9% action accuracy, 71.3% task SR | [primary] |
| AutoDroid-V2 (same group) | arXiv 2412.18116 | 2024-12 → 2025-05 | – | Llama-3.1-8B fine-tune on llama.cpp 8-bit (Snapdragon 8 Gen 2; M2 Pro 16 GB) · **local** | phone or laptop | element tree via ADB | generated script | – | research | DroidTask 54.4%, AitW subset 47.1% | [primary] |

**Notes on group B**

- **DroidRun's 91.4% method** [primary]:
  - A Manager re-plans after every Executor action.
  - Text-heavy subgoals go to a text agent that clears and replaces text in one atomic step.
  - After each action it waits 0.5 s and compares screens until stable.
  - The device date is injected into the prompt, and the pointer-location overlay is turned off.
- **DroidRun with local models:**
  - The official Ollama guide uses `qwen2.5vl` with `context_window=8192` to avoid running out of memory [secondary].
  - A third-party re-test on 65 AW tasks with Claude 4.5 Sonnet for every framework: DroidRun 43%, M3A 29%, AutoDroid 14%, AppAgent 7%. It was a single run with custom wrappers, so the method is weak [primary article].
- **Minitap** (arXiv 2602.07787) [primary]:
  - Six specialised agents. Decomposition adds about +21 points. Verified execution adds +7 or +15 (the paper is inconsistent): text is checked deterministically against device state after typing. Cycle detection adds +9.
  - The single-agent baseline scored 64%. Its failures: execution reliability 41%, context management 38%, error recovery 21%.
  - Open models such as Qwen3-VL-8B can fill the minor roles. The reasoning core cannot: replacing it gave **11.2%**.
- **Open-AutoGLM** [primary]:
  - A callback confirms sensitive actions, and a human takes over for login and CAPTCHA.
  - Payment and password pages return black screenshots, which trigger a takeover.
  - Some ROMs need "USB debugging (Security settings)" before taps work.
- **GELab-Zero** [primary]: Q4 quantisation has "large precision loss", and llama.cpp quantisation can turn `<THINK>` into `<THIN>`.
- **AutoDroid-V2** [primary]:
  - On the phone, prefill dominates: it dropped from 85.3 s to 14.0 s per prompt once the prompt shrank from 15.4k to 2.8k tokens.
  - Planning once as a script cut LLM time per task by 93% (669 s → 46 s).
- **AutoDroid** [primary]: "not good at determining task completion".

### C. Community on-phone apps and MCP servers

| Name (author) | Link | Created / last push | ★ | Model(s) · local? | Runs on | Reads | Acts | License | Maturity | Tag |
|---|---|---|---|---|---|---|---|---|---|---|
| PokeClaw (agents-io) | github.com/agents-io/PokeClaw | 2026-04-06 / 2026-06-02 | 1,097 | Gemma 4 E2B (~2.6 GB) on LiteRT-LM · **fully local**; optional cloud | **phone only** | a11y tree as text + screenshot tool | AccessibilityService | Apache-2.0 | APK, "local-first prototype" | [primary] |
| Operit (AAswordman) | github.com/AAswordman/Operit | 2025-03-15 / 2026-10-05 | 8,724 | cloud, or local MNN / llama.cpp GGUF / Ollama; AutoGLM built in | phone only | AutoGLM visual screen understanding | Accessibility / Shizuku / root; virtual displays | LGPL-3.0 | v1.12.0 | [primary]; local UI control [UNCERTAIN] |
| Roubao 肉包 (Turbo1123) | github.com/Turbo1123/roubao | 2025-12-09 / 2026-01-08 | 2,393 | Qwen (DashScope), OpenAI, Claude; custom endpoint | phone loop, remote model | screenshot | Shizuku | MIT | stale | [primary] |
| AutoGLM-For-Android (Luokavin) | github.com/Luokavin/AutoGLM-For-Android | 2025-12-27 / 2026-01-15 | 708 | AutoGLM API / ModelScope / any OpenAI-compatible VLM | phone loop, remote model | screenshot | Shizuku shell + its own IME | MIT | stale | [primary] |
| Panda / blurr (Ayush0Chaudhary) | github.com/Ayush0Chaudhary/blurr | 2025-06-05 / 2026-01-13 | 993 | Gemini API (cloud) | phone loop | a11y tree | AccessibilityService | "Personal Use License" | WIP | [primary] |
| Andee (kuafuai) | github.com/kuafuai/andee | 2026-09-17 / 2026-10-16 | 256 | OpenAI-compatible (OpenAI / DeepSeek defaults) | phone loop | a11y outline; screenshot + labelled grid fallback | AccessibilityService | Apache-2.0 + conditions | v0.4.1 | [primary] |
| DroidClaw (unitedbyai) | github.com/unitedbyai/droidclaw | 2026-02-06 / 2026-02-28 | 1,569 | Groq, OpenRouter, OpenAI, Bedrock, **Ollama (local)** | host | `uiautomator dump`; screenshot when the tree is empty | adb (22 actions) | MIT (site) | Show HN 2026-02-16 | [primary] |
| ghost-in-the-droid android-agent | github.com/ghost-in-the-droid/android-agent | 2026-04-08 / 2026-08-13 | 388 | Claude Code, cloud APIs, Ollama, vLLM; on-phone llama.cpp / MediaPipe | host or phone companion | uiautomator XML + screenshot / OCR | `adb shell input` | MIT | v1.3, 62 MCP tools | [primary] |
| mobile-mcp (mobile-next) | github.com/mobile-next/mobile-mcp | 2025-03-28 / 2026-10-15 | 9,643 | none (uses the MCP client's LLM) | host | native a11y tree; screenshot fallback | adb + on-phone DeviceServer (UiAutomation) | Apache-2.0 | active | [primary] |
| agent-device (callstack) | github.com/callstack/agent-device | 2026-01-30 / 2026-10-16 | 5,039 | none (the coding agent's LLM) | host | interactive-only a11y snapshots + diffs | ADB + snapshot helper | MIT | active | [primary] |
| Android-MCP (CursorTouch) | github.com/CursorTouch/Android-MCP | 2025-06-11 / 2026-10-14 | 893 | none | host | a11y hierarchy + optional screenshot | ADB + a11y API | MIT | active; 2–4 s between actions | [primary] |
| android-mcp-server (minhalvp) | github.com/minhalvp/android-mcp-server | 2025-02-28 / 2025-05-28 | 811 | none | host | `uiautomator dump`, clickable + labelled nodes only | `adb shell` | Apache-2.0 | stale | [primary] |
| FIRERPA LAMDA | github.com/firerpa/lamda | 2022-07-10 / 2026-09-27 | 8,611 | built-in agent over any OpenAI-compatible API; on-phone MCP | on-phone server (root / Shizuku) | selectors + OCR / image match | own service | MIT | mature RPA | [primary] |
| AutoGLM-GUI (suyiiyii) | github.com/suyiiyii/AutoGLM-GUI | 2025-12-09 / 2026-10-16 | 1,132 | AutoGLM-Phone-9B (API or self-hosted vLLM/SGLang) | host web UI | screenshot | ADB + scrcpy | Apache-2.0 | active | [primary] |
| NeuralBridge_mcp (dondetir) | github.com/dondetir/NeuralBridge_mcp | 2026-03-08 / 2026-03-16 | 41 | none (MCP over Wi-Fi) | phone companion | compact a11y tree + screenshot | `dispatchGesture` | NOASSERTION | v0.4.0 | [primary]; speed [secondary] |
| adb-agent-bridge (kelvincushman) | github.com/kelvincushman/adb-agent-bridge | 2026-08-22 / 2026-09-11 | 0 | none (agent skill) | host | `uiautomator dump`; SoM / grid fallback | ADB + ADBKeyboard | Apache-2.0 | v0.2.x | [primary] |
| OpenClaw Android node | docs.openclaw.ai/platforms/android | 2026 | – | Gateway providers incl. Ollama | phone is a companion node | camera, notifications, SMS, device info | **no UI tap documented** | – | Play + APK | [primary] |

**Notes on group C**

- **PokeClaw is the only shipped app found that runs the model on the phone with no cloud and no PC** [primary]:
  - On a Pixel 8 Pro, each simple local task took 2 min 11 s to 3 min 06 s.
  - Local free-form tasks were switched off because "Gemma is not smart enough to route free-form tasks"; it uses skill cards and hard-coded routing instead.
  - In cloud mode, GPT-4.1 passed 18 of 20 real-phone tasks.
  - Samsung flags the sideloaded APK as malware.
- **How long one UI-tree read takes** [primary where self-measured, else secondary]:
  - Plain `uiautomator dump`: 2.1–3.0 s, up to about 6 s, which is about 95% of a 2.5 s action cycle (adb-agent-bridge, Samsung). It can also fail mid-animation.
  - NeuralBridge companion: 18–33 ms on a Pixel 7, Android 14, but its first request takes 30–120 s.
  - a11y-bridge: about 50 ms. The repo now returns 404.
  - Persistent uiautomator2 server: about 0.5 s (m34-agent).
- **Holes in the a11y tree** [primary]:
  - Flutter, React Native, WebView, games and canvas UIs (DroidClaw, Andee, surapat Phase 4).
  - WeChat on MIUI serves a blank tree, and vendor ROMs revoke a11y grants after updates (Andee).
  - Filtering to "clickable and labelled" nodes drops icon-only buttons (minhalvp).
- **Persistence on the phone** [primary]:
  - Shizuku must be re-paired after every reboot.
  - MediaProjection consent is lost when the app is force-stopped.
  - Community READMEs enable the service with `adb shell settings put secure enabled_accessibility_services …` or a one-time `WRITE_SECURE_SETTINGS` grant [secondary]. NeuralBridge says sideloaded a11y apps need "Allow restricted settings" on Android 15+ [secondary]. Whether the adb route avoids that on Android 16 is [UNCERTAIN].
- **One UiAutomation at a time** [primary]: mobile-mcp's DeviceServer "holds the device's only UiAutomation connection". uiautomator-based tools cannot run side by side.
- **Bind on-phone servers to localhost and reach them with `adb forward`** [primary]: NeuralBridge has no authentication on Wi-Fi, and Andee's debug WebSocket binds 0.0.0.0 with no authentication.
- **Shortcuts that skip the model** [primary]: Roubao's deep links, ghost-in-the-droid's skills that "replay in seconds with zero LLM calls", and PokeClaw's routing.
- **Look on-device, model in the cloud** [primary]: Panda, Roubao, Andee and the AutoGLM phone apps all run their loop on the phone but call a remote model endpoint.

### D. Vendor agents (short)

| Vendor | What operates apps | Status at 2026-10-16 | Local / cloud | Third-party API? | Tag |
|---|---|---|---|---|---|
| Google | Gemini "screen automation": multi-step tasks in selected apps, run inside an on-device virtual display | Beta since 2026-02-25 on Pixel 10/11, Galaxy S26, Z Flip8 / Fold8 and Tab S12. IN / JP / KR / UK / US, 40+ apps (2026-07-22). **Not on Pixel 8.** | Cloud Gemini | No: it uses Computer Control | [primary]; app count [secondary] |
| Google | Gemini API Computer Use | 2.5 preview 2025-10-07; docs updated 2026-10-13 added `ENVIRONMENT_MOBILE` and a 0–999 grid | Cloud | Yes (an API; your client executes actions, e.g. via ADB) | [primary] |
| Android | Computer Control (preview): background virtual display, screenshots + injected input, max 6 apps per session | Dev doc 2026-05-13. In Android 16 QPR3 it appears as "Screen automation", for the Google app only | – | **No**: privileged `ACCESS_COMPUTER_CONTROL`, OEM-preloaded assistants only | [primary] |
| Android | AppFunctions: apps expose functions to agents | Android 16+, experimental | On-device IPC | Gated (`EXECUTE_APP_FUNCTIONS`, limited agents), and each app must adopt it | [primary] |
| Samsung | Bixby device agent; Gemini screen automation; Perplexity (S26) | Feb 2026; One UI 9 from 2026-09-16 | Mostly cloud | None found | [secondary] |
| Honor | YOYO Agent (Magic8), "3,000+ scenarios" | China, 2025-10-15 | Not stated | None | claim [primary]; capability [UNCERTAIN] |
| Xiaomi | HyperOS 3 "Super XiaoAi" | One review found it could only open apps | ? | None | [secondary] [UNCERTAIN] |
| Apple | Siri AI in iOS 27: onscreen awareness and in-app actions | Beta from 2026-09-14, English only | On-device + Private Cloud Compute; Gemini-based models | App Intents only; no control of arbitrary UIs | [primary] |
| ByteDance | Doubao phone agent on ZTE Nubia (OEM granted it `INJECT_EVENTS`) | Since 2025-12-01. 155 apps refuse it (2026-10-14) | Cloud [UNCERTAIN] | Apps opt out via SAEP | [secondary] |

**Platform and policy for third-party agents:**
- **Google Play policy** (announced 2025-10-30): using the Accessibility API so that an app can "autonomously initiate, plan, and execute actions" is prohibited [primary].
  - Deterministic automation is still allowed, and `isAccessibilityTool` apps are exempt only when they serve users with disabilities [primary].
  - Sova was rejected from Play for "universal automation" [secondary].
  - Consequence: a Laya agent ships as a sideloaded or host-ADB research rig, not a Play app.
- **Android 17 Advanced Protection** (opt-in) limits AccessibilityService to accessibility-tool apps [secondary, 2026-10].
- **Developer verification** applies from 2026-09-30 in BR, ID, SG and TH; ADB installs are exempt [secondary].
- **FLAG_SECURE** windows give blank screenshots [primary], but their a11y tree may still be readable [secondary]. That favours an a11y-only policy.
- **Overlays:** apps can block them with `HIDE_OVERLAY_WINDOWS` (Android 12+) [primary].

**Apps detect and block agents** [secondary]: WeChat logged Doubao users out for an "abnormal login environment", banks demanded the tool be disabled, and Doubao took WeChat operation offline. Some banking apps refuse to run while any screen-control a11y service is enabled [primary, j0taaa/android-use].

### E. Individuals who made a phone "use itself"

- **ThomasBaruzier/android-agent** (2025–26) [primary]:
  - Setup: rooted phone, Termux, Qwen2.5-VL-72B served from 2× RTX 3090.
  - "one action every 15 seconds", because the whole context is re-ingested each step.
  - The 7B "does not work well", and the agent "will abandon the task if something goes wrong".
- **PokeClaw** (2026): fully local on a Pixel 8 Pro, about 2–3 min per simple task; see group C [primary].
- **DroidClaw** (Show HN 2026-02-16) [primary]:
  - Can run on local Ollama.
  - Limits: 30 steps, stuck recovery after 3 unchanged screens, 40 elements per prompt.
  - Self-declared unreliable on Flutter, React Native, WebView, games and captchas. It cannot handle FLAG_SECURE banking apps or pinch-zoom.
- **AyuAnchor/m34-agent** (2026-10) [primary]:
  - A Galaxy M34 drives itself over `adb 127.0.0.1:5555`, with Shizuku turning wireless debugging back on after reboot. The models are cloud.
  - Timing: dump about 3 s, against about 0.5 s with a persistent uiautomator2 server. One step is 3–4 s.
- **Open-AutoGLM issue #230** (2025-12 → 2026-03) [primary]:
  - Setup: AutoGLM-Phone-9B at Q4_K_M (5.74 GB + 1.66 GB mmproj), about 6.5 GB VRAM on an RTX 4060, 20–30 tok/s.
  - Broke: JSON parse errors, truncated streams and endless repetition (`--repeat-penalty 1.1` fixed it).
  - A CPU-only Homebrew llama.cpp gave a 342 s time-to-first-token.
- **oMLX issue #2006** (2026-06-26) [primary]: AutoGLM-Phone-9B on oMLX lost its `<think>` output. The same model as GGUF on llama.cpp was fine.
- **53AI hands-on with GELab-Zero-4B** (2026-01-12) [primary, subjective]:
  - It finished a task that AutoGLM had failed, in about 7 min.
  - It loops on complex tasks, and an on-screen overlay caused repeated actions.
- **UgOrange/gui_agent_skill** (Show HN 2026-02-19) [primary, no measurements]:
  - Claim: 4B/9B GUI models "work well for single interactions" but are "brittle on long workflows".
  - Design: Claude Code plans, and GELab via Ollama executes.
- **Philipp Schmid** (2026-06-25, cloud) [primary]: Gemini computer use over ADB, with coordinates on a 0–999 grid scaled by `wm size`. Advice: type through the tool, not the soft keyboard, and handle ADB disconnects.

## Benchmarks: small (≤10B) open models on AndroidControl-High and AndroidWorld

**How to read the table.** Every number is self-reported unless the row says otherwise. Protocol key:
- **IG**: the InfiGUI-R1 evaluator. A click counts if it is inside the target bbox scaled 1.2×, or within 4% of max(W,H).
- **OA**: the OS-Atlas protocol, 7,708 steps. A click counts if it is within 14% of screen width, about 151 px on a 1080-wide screen.
- **AC**: the original AndroidControl paper. A click counts only inside the element's bbox.

| Model | Params | Input | Protocol | AC-High Type | AC-High GR | AC-High SR | AW SR (setting) | Source | Tag |
|---|---|---|---|---|---|---|---|---|---|
| **Laya-Android** | 0.32B | a11y candidates | IG, 8,444 steps | **76.7** | **61.7** | n/a | not evaluated | HF ByunByun/laya-android | [primary] |
| InfiGUI-R1-3B | 3B | screenshot | IG | 82.7 | 74.4 | 71.1 | – | arXiv 2504.14239 T3 | [primary] |
| PaLM-2S LoRA, a11y tree (AC paper; size undisclosed, closed) | ? | a11y tree | AC | – | – | 64.8 full test / 62.6 Random-500 (T12); IDD part of Random-500: 65.6 r4, 71.5 r64 (T4) | – | arXiv 2406.03679 T4/T12 | [primary] |
| M3A + GPT-4 Turbo, zero-shot (closed reference) | – | a11y tree | AC | – | – | 42.1 | 30.6 a11y-only / 25.4 SoM | 2406.03679 T4; 2405.14573 T3 | [primary] |
| UI-TARS-2B / 7B | 2B / 7B | screenshot | OA | 81.2 / 83.7 | 78.4 / 80.5 | 68.9 / 72.5 | 7B-SFT 33.0 | 2501.12326 T8/T9 | [primary] |
| OS-Atlas-4B / 7B (SFT) | 4B / 7B | screenshot | OA | 84.7 / 85.2 | 73.8 / 78.5 | 67.5 / 71.2 | – | 2410.23218 T5 | [primary] |
| UI-Venus-Navi-7B | 7B | screenshot | own | 86.5 | – | 76.1 | 49.1 (pass@1) | 2508.10833 T11 | [primary] |
| UI-AGILE-3B / 7B | 3B / 7B | screenshot | OA | 78.6 / 80.1 | 60.7 / 61.9 | 56.9 / 60.6 | – | 2507.22025 T3 | [primary] |
| GUI-R1-3B / 7B | 3B / 7B | screenshot | own zero-shot prompt | 58.0 / 71.6 | 56.2 / 65.6 | 46.6 / 51.7 | – | 2504.10458 T2/T3 | [primary] |
| AgentCPM-GUI-8B (on-device pitch) | 8B | screenshot | own re-runs | TM 77.7 | – | EM 69.2 | – | 2506.01391 T3 | [primary] |
| Aguvis-7B | 7B | screenshot | 500 random steps | – | – | 61.5 | 37.1 (with GPT-4o planner) | 2412.04454 T3/T5 | [primary] |
| Qwen2.5-VL-7B (zero-shot) | 7B | screenshot | various re-runs | 68.7–75.1 | ~59 | 47–63 | 25.5–27.6 | GUI-R1, UI-AGILE, CPM, model card | [primary]; wide spread |
| GUI-Owl-7B | 7B | screenshot | own (one exact-match score) | – | – | 72.8 | 66.4 (model alone) | 2508.15144 T6/T7 | [primary] |
| MAI-UI-2B / 8B | 2B / 8B | screenshot | own (one score, undefined) | – | – | 67.3 / 69.1 | 49.1 / 70.7 | 2512.22047 T6/T7 | [primary] |
| GUI-Owl-1.5-2B / 4B / 8B / 8B-Think | 2–8B | screenshot | – | – | – | – | 67.9 / 69.8 / 69.0 / 71.6 (setting unlabelled) | 2602.16855 T1 | [primary] |
| UI-Venus-1.5-2B / 8B | 2B / 8B | screenshot | – | – | – | – | 55.6 / 73.7 | 2602.09082 T2 | [primary] |
| UI-Venus-2-9B (2026-08) | 9B | screenshot | – | – | – | – | 80.2 | HF inclusionAI/UI-Venus-2-9B (licence "pending") | [primary] |
| MobileRL-9B | 9B | **screenshot + a11y** | – | – | – | – | 80.2 | 2509.18119 T1 | [primary] |
| Step-GUI-4B / 8B | 4B / 8B | screenshot | – | – | – | – | pass@1 63.9 / 67.7 (pass@3 75.8 / 80.2) | 2512.15431 T4 | [primary] |
| GELab-Zero-4B-preview | 4B | screenshot | – | – | – | – | "75.86" (= 88/116); setting unstated, likely best-of-k | README | [UNCERTAIN] |
| UI-Voyager | 4B | screenshot | – | – | – | – | 81.0 (pass@1, mean of 64 runs) but **trained on AW tasks** | 2603.24533 | [primary] |
| Holo2-4B / 8B | 4B / 8B | screenshot | – | – | – | – | 64.6 / 60.4 (harness unnamed) | HF Hcompany cards | [primary] |
| Qwen3-VL-2B / 4B / 8B | 2–8B | screenshot | – | – | – | – | 36.4 / 45.3 / 47.6 | cited by Step-GUI, MAI-UI | [secondary] |
| UI-TARS-1.5-7B | 7B | screenshot | – | – | – | – | 30.0 (MAI-UI's run) | MAI-UI T6 | [secondary] |
| Ferret-UI Lite (no public weights) | 3B | screenshot + zoom | – | – | – | 68.9 | 28.0 (mean of 5 runs) | 2509.26539 | [primary] |
| **V-Droid** (verifier) | 8B | **a11y text only** | – | – | – | – | 59.5 | 2503.15937 T2 | [primary] |
| LiMAC (action model + VLM for text) | 0.52B + VLM | a11y + image embeddings | own "overall accuracy" | – | – | 63.1 (not SR) | – | 2410.17883 | [primary] |
| AppVLM | 3B | screenshot + a11y boxes | own "relaxed accuracy" | – | – | 69.0 | 37.8 (82-task subset) | 2502.06395 | [primary] |

For context only, these are larger or closed: LLaDA-UI, a 16.7B MoE non-autoregressive diffusion model, scores AW 53.5 and loops on long tasks. UI-TARS-2 (230B MoE) scores 73.3. The Jev-Mobile system (cloud) scores 0.79. Minitap and Mobilerun's harness (frontier models) score 100 [primary].

**Why these numbers are not apples-to-apples** [primary unless tagged]:
- **Input differs.**
  - a11y-tree input: Laya-Android, V-Droid and the AC paper's agents. Grounding means picking the right element, which avoids pixel error but fails on unlabelled icons and on nodes missing from the tree.
  - Screenshot input: most rows.
  - Both: MobileRL and OS-Genesis.
- **Click rules differ.** OA's 14% radius is lenient compared with IG, and IG is lenient compared with the AC paper's bbox rule. OA numbers therefore flatter screenshot models next to Laya's IG score.
- **IG's text check is lenient.** It passes when either string contains the other, so an empty prediction passes; "open" also accepts a tap on the app icon. This does not affect Laya's Type / GR, but it inflates others' SR.
- **Test sets differ.**
  - Sizes: 7,708 steps (OA), 8,444 (IG), 500 random (Aguvis, AC paper), 1,000 random (UI-TARS §5.5).
  - Subset effect: the same fine-tuned PaLM-2S scores 62.6 on Random-500 but 64.8 on the full test (T12).
- **SR, EM and "step accuracy" are not the same thing.** All require type plus every argument (typed text, app names). Laya's GR counts only click steps. The AC paper's PaLM-2S full step accuracy (64.8) is therefore not comparable with Laya's 61.7.
- **High vs Low.** Low adds the step instruction, which is worth +15–25 SR. Laya reports High only.
- **Offline vs online.** AC scores single steps with gold history. AW is end-to-end on 116 tasks, with humans at 80.0%. Its scores vary by harness and pass@k. The official leaderboard is self-reported, and its "Open?" column is wrong in places.
- **Re-runs disagree.**
  - UI-TARS-7B on AW: 33.0 own, versus 29.3 / 33.2 in V-Droid's re-run.
  - OS-Atlas-7B on AC-High SR: 71.2 versus 56.5 (some tables used zero-shot numbers).

**Where Laya-Android sits:**
- **The only same-evaluator peer is InfiGUI-R1-3B.** Laya trails it by 6.0 on Type and 12.7 on Grounding, with about a tenth of the parameters and no pixels [primary].
- **Type 76.7 is below every model fine-tuned on AndroidControl** (81–86.5). It is in the range of the 2025 RL-trained 3B/7B models and zero-shot Qwen2.5-VL [primary].
- **Grounding 61.7 sits beside SeeClick (62.9) and UI-AGILE (60.7–61.9) at face value**, but those were scored under OA's lenient radius [primary].
- **Reading only the a11y tree is not a handicap per se** [primary]:
  - On AW, GPT-4 Turbo did better with a11y alone (30.6) than with SoM (25.4).
  - V-Droid reached 59.5 with a11y text only.
  - A 2026 study (DailyDroid, 75 tasks) found text-only and multimodal "comparable", with screenshots "marginally higher" (arXiv 2604.17817).

## Local "System 2" VLM options for the M5 16 GB

| Model (AW) | Params / arch | GGUF Q4_K_M + mmproj | MLX 4-bit | Ollama | Est. RAM with 1 screenshot | Fits 16 GB? | Coordinates | Licence | Tag |
|---|---|---|---|---|---|---|---|---|---|
| GUI-Owl-1.5-4B-Instruct (69.8) | 4B, Qwen3-VL | mradermacher 2.6 GB + 0.9 GB (f16) | none (a community 2B build only) | via hf.co | ~5.5 GB | yes | probably 0–999 | MIT (2B checked; 4B not) | [primary]; coords [UNCERTAIN] |
| GUI-Owl-1.5-8B (69.0; Think 71.6) | 8B, Qwen3-VL | mradermacher 5.1 + 1.3 GB | – | hf.co | ~8.7 GB | tight | as above | not checked | [primary] |
| Qwen3-VL-4B / 8B-Instruct (45.3 / 47.6) | Qwen3-VL | **official** 2.5 + 0.84 / 5.03 + 1.16 GB | mlx-community 3.09 / 5.76 GB | **official** `qwen3-vl:4b` 3.3 GB, `:8b` 6.1 GB (Ollama ≥0.12.7) | ~5.5 / ~8.5 GB | yes / tight | 0–999 (cookbook divides by 999) | Apache-2.0 | [primary] |
| MAI-UI-8B (70.7) / 2B (49.1) | Qwen3-VL | mradermacher 5.1 + 1.3 GB | mlx-community 8B 6.9–7.4 GB | hf.co | ~8.7 / ~3.5 GB | tight / yes | 0–999 | Apache-2.0 | [primary]; coords [secondary] |
| GELab-Zero-4B-preview ("75.86") | Qwen3-VL-4B | official recipe Q8 4.28 GB + 0.45 GB (Q4 "large precision loss") | none | Modelfile recipe | ~6.9 GB (Q8) | yes | ? | Apache-2.0 | [primary]; AW [UNCERTAIN] |
| UI-Venus-2-9B (80.2) | Qwen3.5-9B (8 full-attention layers, so a small KV cache) | bartowski 5.91 GB + f16 mmproj (llama.cpp ≥ b10630) | community 5.6–5.95 GB (mlx-vlm 0.6.17) | hf.co | ~8 GB | tight | ? | **"pending"** | [primary] |
| Holo2-4B (64.6) | Qwen3-VL | mradermacher | bf16 only (8B) | hf.co | ~5.5 GB | yes | ? | Apache-2.0 | [primary] |
| AutoGLM-Phone-9B (no AW published) | GLM-4.1V-9B | ggml-org 6.17 + 0.98 GB | mlx-community Multilingual 7.07 GB | hf.co | ~9 GB | very tight; 13–18 s/step on a Mac | 0–1000 | MIT | [primary]; speed [secondary] |
| UI-TARS-1.5-7B (30.0) | Qwen2.5-VL | community 4.78 + 1.35 GB | community | no official build | ~8 GB | tight | **absolute pixels on the resized image** | Apache-2.0 | [primary] |

**Runtime facts**, all dated:
- **llama.cpp support** [primary]:
  - Qwen3-VL since PR #16780, merged 2025-10-30 (use b6907 or later).
  - GLM-4V since PR #18042, merged 2025-12-16.
  - Qwen3.5 needs b10630 or later.
- **mlx-vlm support** [primary]: Qwen3-VL from 0.3.4, glm4v from 0.3.9.
- **M5 speed:**
  - Memory bandwidth is 153 GB/s. Under MLX, time to first token is 3.3–4.1× faster than an M4; this needs macOS 26.2 or later [primary, Apple ML blog 2025-11-19].
  - In llama-bench, a base M5 does about 720 tok/s prefill and 32 tok/s generation (7B Q4_0) [secondary].
- **Screenshot cost:** a 1080×2400 screenshot is about 2,550 visual tokens on Qwen3-VL and about 3,354 on Qwen2.5-VL or GLM-4.1V [primary configs; arithmetic is an estimate].
- **GPU memory cap:** the default GPU-wired limit on a 16 GB Mac is about 10.7–12 GB [secondary].
- **Step time** [UNCERTAIN until measured]: estimated 5–11 s per step. Capping visual tokens near 1,280 roughly halves that.

**Ranking for teacher/fallback on this Mac:**
1. **GUI-Owl-1.5-4B-Instruct.** Best AW per GB, an MIT licence, and Qwen3-VL runtimes. MNN builds also open a path to the phone.
2. **Qwen3-VL-4B or 8B.** The official builds make it the lowest risk. Use it as judge and text writer.
3. **MAI-UI-8B.** It has `ask_user` and `mcp_call` actions, but memory is tight.

Avoid AutoGLM-Phone-9B (slow, tight, unbenchmarked), UI-TARS-1.5-7B (weak at 7B, and a coordinate trap) and UI-Venus-2-9B (licence pending).

**Laya itself already has Apple and Android runtimes** (relevant to the System-1 side):

| Runtime | Hardware | Latency | Note | Tag |
|---|---|---|---|---|
| PyTorch MPS, Laya-Android, `max_len=3072` | **our M5 16 GB** | 37 ms (3 candidates), 60 (14), 303 (50), 793 ms (100) | superlinear, because each candidate appears twice in the input | [measured] 05-live-tests.md |
| FluidInference/laya-coreml | M5 Pro | median 5.2 ms; 3.6 ms at 128 tokens → 17.9 ms at 1,024 | base Laya; self-reported | [primary] |
| tc3oliver/laya-apple (19★) | M4 Max | MLX 9.3 ms at 64 tokens → 71 ms at 1,024. The ANE only takes ≤128 tokens; at 1,024 it silently falls back to CPU (2,201.8 ms) | checked against PyTorch; a plain Core ML export on CPU_AND_NE gave hard mismatches | [primary] |
| mizorewww/laya-mlx (6,949★) | M3 Max | 7.39 ms P50, 688 MiB peak | laya-multilingual | [primary] |
| litert-community/Laya-Multilingual-LiteRT | Galaxy S26 (Snapdragon 8 Elite Gen 5) | GPU 51.9 ms, NPU 36.5 ms, CPU 163 ms at 256 tokens | graphs exist only for 256 and 512 tokens. **Pixel 8 / Tensor G3 untested**; our home screen is ~1,200 tokens | [primary] |
| Acurast phone network | assorted Android phones | 0.2–1 s per decision | not GUI control | [primary blog] |

## What is genuinely new in our attempt

- **A fine-tuned non-autoregressive decision model as the per-step policy, on a real, unrooted consumer phone (Pixel 8, Android 16), with every model local.** Nobody else has published that combination [UNCERTAIN, search-based]. The nearest attempts each miss part of it:
  - Jev-Mobile: both models cloud; device not reported.
  - V-Droid: GPT-4 memory; emulator only.
  - surapat skill: zero-shot Laya plus a cloud agent; emulator.
  - Laya-Android: offline only.
  - KYRIE66nb: Laya only as a gate, with a cloud chat model.
  - PokeClaw: generative model on the phone, minutes per task.
- **Decision latency.** Laya decided in 0.04–0.85 s per step on the M5 [measured]. Published local agents take 15 s per action (ThomasBaruzier), 13–18 s per step (AutoGLM on a Mac), or 2–3 min per task (PokeClaw) [primary/secondary]. With an on-phone accessibility companion instead of `uiautomator dump`, a whole step under ~0.5 s looks plausible [UNCERTAIN until measured].
- **What is *not* new.** These all exist already: the executor-over-a11y-candidates design (Jev-Mobile, V-Droid, LiMAC), the small-policy / large-model split (LiMAC, MAI-UI's on-device/cloud split, Jev-Mobile), and every framework trick listed below. We should reuse them, not reinvent them.

## Lessons to copy

1. **Split by frequency.** A small model picks among candidates every step; a larger model handles goals, text and recovery only.
   - Jev-Mobile kept 0.79 of 0.84 AW while cutting executor time 94.5% [primary].
   - LiMAC calls its VLM for text on less than 15% of actions [primary].
   - MAI-UI ran 40.5% of tasks fully on the phone, with a +33.4% relative gain over the on-device model alone [primary].
2. **Build candidates deterministically, and re-observe after every action.**
   - Use visible, enabled nodes with valid bounds, typed by flags, plus the defaults BACK / HOME / WAIT / DONE / OPEN_APP.
   - Check each candidate against a fresh snapshot, and tag snapshots with IDs to reject stale targets (Jev-Mobile, surapat Phase 4, agent-device) [primary].
3. **Keep each option list short, or chunk it.**
   - Laya's option budget is about 20 options [primary, README].
   - Target accuracy collapses with longer lists: 61.7% at 2 → about 2% at ~151 [primary, laya-browser-v1].
   - V-Droid sees about 20 elements and about 50 actions per step [primary]. Our home screen had 31 candidates [measured].
4. **Test per-candidate scoring.** V-Droid's per-candidate verifier beat a multiple-choice selector, 47.4 vs 35.8 [primary]. Laya's `noul` head (P(true)) can do this, and batching makes it cheap (7.2 ms per question batched on a T4) [primary, README].
5. **Read and act through an on-phone AccessibilityService, not `uiautomator dump`.**
   - Bind it to localhost and reach it via `adb forward`.
   - Settle on events (about 150 ms quiet) rather than fixed sleeps.
   - Run only one UiAutomation client at a time [primary; dump cost 90% of our step, measured].
6. **Type through an IME or `ACTION_SET_TEXT`, never `adb input text`, and check the field afterwards.**
   - Minitap's post-check adds +7–15 points [primary].
   - For Laya, copy an exact span of the goal (mobile-jev) rather than generating text [primary].
7. **Never trust DONE.**
   - Ask a separate completion question (xinwang rejects DONE below 0.5) and verify device state (mobile-jev) [primary].
   - Premature stopping is about 60% of laya-browser failures [primary]. Our DONE was weak too [measured].
8. **Detect loops.** Same action on the same screen 3 times → hand off (surapat, DroidClaw); Minitap's cycle detection adds +9 [primary].
9. **Calibrate before you gate.**
   - Laya ships overconfident: refitting temperature took ECE from 0.466 to 0.081 [secondary].
   - A shipped temperature bug broke laya-browser probes [primary].
   - Calibration can hide confident misses within subgroups (arXiv 2609.33401) [primary].
10. **Plan a visual fallback for a11y holes.** Jev-Mobile counts these as "coverage failures"; the usual culprits are Flutter, React Native, WebView, canvas, and WeChat on MIUI [primary]. Remember that FLAG_SECURE blanks screenshots [primary].
11. **Prefer CLI and intent shortcuts when they exist.**
    - 103 of 116 AW tasks can be solved by CLI (arXiv 2606.19388) [primary].
    - OPEN_APP can be a choice over installed apps (mobile-jev; our Pixel has 206 launchable) [primary/measured]. Deep links and skill replay help too (Roubao, ghost-in-the-droid) [primary].
12. **Safety by construction:**
    - Never auto-grant permission pop-ups. Agents granted 26/32 times versus 0/32 depending only on which app was requesting (arXiv 2608.04755) [primary].
    - Hand login, CAPTCHA and payment to the human (Open-AutoGLM) [primary].
    - Block escalation when a password is on screen (MAI-UI) [primary].
    - Fail closed (KYRIE66nb) [primary].
    - Use throwaway accounts: anti-automation risk control logs users out (Doubao) [secondary].
13. **Training-data ideas for a Laya-style policy:**
    - Upsample rare actions (UI-Venus).
    - Train on the steps where a successful and a failed run diverged (UI-Voyager).
    - Cap "go back" correction data, since more than ~2.5% made V-Droid spam Back.
    - DAgger-style on-policy corrections (laya-browser v19s) [primary].
14. **Local VLM serving hygiene:**
    - Use Metal builds (CPU-only Homebrew llama.cpp gave a 342 s TTFT).
    - Keep the mmproj at f16, and give it at least ~1,024 image tokens for grounding.
    - Recompute the resize for pixel-coordinate models.
    - Pin each model's own prompt and parser [primary/secondary].

## Recommendations

Each entry reads: decision — why — alternative rejected.

1. **Laya-Android runs every step over a11y candidates. A local VLM on the Mac is called only for text and app-name payloads, for stalls, and for completion checks.**
   - Why: this is the Jev-Mobile / LiMAC / V-Droid split with published evidence, and Laya cannot generate text.
   - Rejected: a VLM at every step (5–11 s per step estimated on the M5, and Minitap's 11.2% with a small reasoning core); Laya alone (no text, weak DONE).
2. **Replace `uiautomator dump` with an on-phone AccessibilityService companion: try the open-source Mobilerun Portal first, else a minimal service of our own, on localhost via `adb forward`.**
   - Why: the dump is 2.4–3.8 s and about 90% of the step [measured]. Companion reads are reported at 18–33 ms (NeuralBridge) [secondary] and 0.1–1 s (Portal ContentProvider) [primary]. Companions also fix Unicode input through their IME.
   - Rejected: keeping the dump with smarter settling (still a ≥2 s floor); screenshots (Laya cannot use them).
3. **Cap each Laya question at about 20 options and chunk above that. A/B `choice` against per-candidate `noul` scoring.**
   - Why: list length collapses target accuracy, V-Droid gained +11.6 from per-candidate scoring, and our latency is superlinear (793 ms at 100 candidates) [primary/measured].
   - Rejected: always passing the full candidate list.
4. **Add a completion verifier (a second Laya question or a screen rule, with the VLM judge when in doubt), plus a 3-repeat loop guard and a step cap.**
   - Why: premature stopping dominates failures in laya-browser and in our live run, and Minitap gained points from verification and cycle detection.
   - Rejected: trusting the policy's DONE.
5. **Fit the temperature and the escalation thresholds on our own Pixel traces, and report coverage against error.**
   - Why: Laya is overconfident out of the box, and its calibration can hide confident misses.
   - Rejected: using the model card's probabilities as they are.
6. **System 2: GUI-Owl-1.5-4B-Instruct, with Qwen3-VL-4B-Instruct as the official-runtime fallback, on llama.cpp Metal or mlx-vlm with an f16 mmproj and ~1,280 visual tokens.**
   - Why: best AW per GB under a permissive licence, and it fits beside Laya in 16 GB.
   - Rejected: AutoGLM-Phone-9B (13–18 s per step, very tight, no benchmarks); UI-TARS-1.5-7B (weak, absolute-pixel trap); UI-Venus-2-9B (licence pending).
7. **Make OPEN_APP a Laya choice over installed launchable apps, executed via `monkey -p` or `am start`. Add intent and deep-link options when known.**
   - Why: Laya cannot output names, mobile-jev and Jev-Mobile do this, and most AW tasks are CLI-solvable.
   - Rejected: a hand-kept alias table (doesn't scale); tapping through the launcher.
8. **Evaluation: a fixed real-device suite (≥20 tasks × N runs) reporting pass@1, time per success, and the coverage / selection / handoff failure split. Keep AC-High on the IG evaluator as an offline regression test. Add AndroidWorld on an emulator later for comparability.**
   - Why: AC is step-level with gold history, and AW leaderboard numbers are self-reported on mixed harnesses.
   - Rejected: claiming a place on the AW leaderboard.
9. **Safety scope for v1: a non-critical account; a deny-list for banking, payment and WeChat-like apps; never auto-accept permission dialogs; traces local, with passwords redacted.**
   - Why: apps block agents, permission over-granting is documented, and Open-AutoGLM and MAI-UI follow these patterns.
   - Rejected: unrestricted autonomy.
10. **Defer running Laya on the phone (LiteRT) until the Mac loop works. Then re-export longer graphs (about 1,536 tokens or more) and benchmark on Tensor G3.**
    - Why: the published LiteRT graphs are 256 or 512 tokens and tested only on an S26, while our screens reach about 1,200 tokens.
    - Rejected: going on-device first.

## Open questions

- How well does Laya-Android's grounding hold on Pixel 8 trees across a larger task suite? Its card reports a 9–12 point drop on unseen apps, and uiautomator dumps a single window while AndroidControl used full accessibility forests [UNCERTAIN].
- How fast is Laya-Android at 1,000–3,000 tokens under MLX or Core ML on the M5? Community runtimes have only tested base Laya up to 1,024 tokens, and the ANE fails above 128 [primary].
- Can a sideloaded AccessibilityService (Portal) be enabled over adb on Android 16 without friction from "restricted settings"? [UNCERTAIN]
- Is the a11y tree still readable on FLAG_SECURE screens of the apps we target? [secondary claim only]
- Does V-Droid's result (per-candidate scoring beats multiple choice) transfer to Laya's `noul` versus `choice`?
- Would the existing Jev-format phone tools run unchanged against a local `/v1/systemone`? Both laya-apple `serve` and stuntd provide one. This could be a quick integration test [UNCERTAIN].
- Which local System 2 judges completion and writes text best on this Mac, and what is its real prefill time at ~1,280 vs ~2,550 visual tokens? [UNCERTAIN]
- What setting does GELab-Zero's 75.86 use? Is AutoGLM-Phone-9B the same checkpoint as MobileRL-9B? Jev-Mobile's code, candidate caps and device are all unpublished [UNCERTAIN].

## Dead ends

**Wrong or misleading numbers** [primary unless tagged]:
- "UI-TARS-1.5-7B scores 64.2 on AW." That figure belongs to the unreleased flagship.
- The MAI-UI-8B card's 76.7 is the 235B model's score.
- Mobile-Agent-v3's 73.3 is a framework result, not a model result.
- Step-GUI's 80.2 is pass@3.
- UI-Voyager's 81.0 is in-distribution (trained on AW tasks).
- GELab-Zero's 75.86 has no stated protocol [UNCERTAIN].
- The Qwen2.5-VL-3B card's agent table is garbled.
- GUI-R1 and UI-AGILE print OS-Atlas's zero-shot numbers as SFT.
- The Laya README's "element top-1 0.10 → 0.66, real-task success 0% → 62% at 17–23 ms" does not match the current laya-browser card, where the hardest suite reaches 20–26% [UNCERTAIN].
- "Gemini Computer Use 69.7% AW" is not in Google's post [UNCERTAIN].

**Cloud-only, though marketed or likely to be read as local or on-device** [primary]:
- Panda, Roubao, Andee, android-use and Sova: the loop runs on the phone, the model is in the cloud.
- Every Jev phone project needs hosted Jev.
- AppAgent.
- ghost-in-the-droid's 115/116 on AW was "driven by Claude Code".
- Mobilerun's 100% used "GPT-6 Astra".

**No weights, no code, or gated** [primary]:
- Ferret-UI Lite.
- Jev-Mobile (no code).
- 5omeOtherGuy/operator ("planning, no code").
- L-Operator, an LFM2-VL-1.6B Android-control fine-tune, gated "to qualified investors under NDA".
- VisionLaya (licensed CC BY-NC-SA).
- OmegaUse (no weights).

**Platform** [primary]:
- Gemini screen automation: not on the Pixel 8, and no API.
- Computer Control: OEM-only.
- AppFunctions: gated, and each app must adopt it.
- Ollama's MLX engine: preview, >32 GB RAM recommended, vision support unclear.
- AgentCPM-GUI: no GGUF or MLX builds.
- Holo2: MLX in bf16 only.
- AutoGLM: official docs cover only NVIDIA GPUs with ≥24 GB.

**Stale or gone** [primary]:
- AppAgent and AutoDroid (dormant).
- Roubao, AutoGLM-For-Android and Ruto-GLM (no pushes since 2026-01).
- 4ier a11y-bridge (404).

## Sources

**Laya and System-1 decision models:**
- https://github.com/NandhaKishorM/laya
- https://huggingface.co/convaiinnovations/laya
- https://github.com/Byun11/laya-android
- https://huggingface.co/ByunByun/laya-android
- https://github.com/surapat-codediva/laya-android (+ /issues/1, /issues/2)
- https://huggingface.co/arnabwithab/laya-android-control
- https://github.com/KYRIE66nb/mobile-agent
- https://jevlist.ai/projects/mobile-agent
- https://huggingface.co/cklxx/laya-browser
- https://huggingface.co/ikaanakkok/laya-browser-v1
- https://huggingface.co/ikaanakkok/laya-desktop-v1
- https://huggingface.co/ShaunSpark/laya-mind2web-browser-agent
- https://github.com/browser-use/jev-ultrafast
- https://arxiv.org/abs/2609.30186 (Jev-Mobile)
- https://github.com/droidrun/mobile-jev
- https://mobilerun.ai/blog/posts/give-jev-access-to-mobile
- https://github.com/xinwang-nwpu/jev-mobile
- https://github.com/Friedjof/jev-mobile
- https://github.com/antiyro/jevdroid
- https://github.com/AbdelStark/awesome-typesafe-jev
- https://redreamality.com/blog/open-decision-models-2026-survey-benchmarks
- https://arxiv.org/abs/2609.33401
- https://www.vktr.com/ai-platforms/chatgpt-cocreator-launches-typesafe-ai-with-jev/
- https://acurast.com/blog/network-and-tech/laya-demos-system-one-model/
- https://huggingface.co/litert-community/Laya-Multilingual-LiteRT
- https://github.com/tc3oliver/laya-apple
- https://huggingface.co/FluidInference/laya-coreml
- https://github.com/mizorewww/laya-mlx
- https://huggingface.co/api/models?search=laya

**Frameworks:**
- https://github.com/droidrun/mobilerun
- https://github.com/droidrun/mobilerun-portal
- https://droidrun.ai/benchmark/method/
- https://github.com/droidrun/mobile-harness
- https://github.com/minitap-ai/mobile-use
- https://arxiv.org/html/2602.07787
- https://github.com/zai-org/Open-AutoGLM
- https://github.com/stepfun-ai/gelab-zero
- https://github.com/TencentQQGYLab/AppAgent
- https://github.com/MobileLLM/AutoDroid
- https://arxiv.org/abs/2308.15272
- https://arxiv.org/html/2412.18116
- https://github.com/X-PLUG/MobileAgent
- https://github.com/web-infra-dev/midscene
- https://aimultiple.com/mobile-ai-agent

**Community apps and MCP servers:**
- https://github.com/agents-io/PokeClaw
- https://github.com/AAswordman/Operit
- https://github.com/Turbo1123/roubao
- https://github.com/Luokavin/AutoGLM-For-Android
- https://github.com/iamr0s/Ruto-GLM
- https://github.com/Ayush0Chaudhary/blurr
- https://github.com/kuafuai/andee
- https://droidclaw.ai
- https://github.com/unitedbyai/droidclaw
- https://github.com/ghost-in-the-droid/android-agent
- https://github.com/mobile-next/mobile-mcp
- https://github.com/callstack/agent-device
- https://github.com/CursorTouch/Android-MCP
- https://github.com/minhalvp/android-mcp-server
- https://github.com/firerpa/lamda
- https://github.com/suyiiyii/AutoGLM-GUI
- https://github.com/dondetir/NeuralBridge_mcp
- https://hackernoon.com/i-built-a-100x-faster-android-automation-tool-because-ai-agents-deserve-better
- https://github.com/kelvincushman/adb-agent-bridge
- https://github.com/j0taaa/android-use
- https://docs.openclaw.ai/platforms/android
- https://github.com/AidanPark/openclaw-android

**Individuals:**
- https://github.com/ThomasBaruzier/android-agent
- https://github.com/zai-org/Open-AutoGLM/issues/230
- https://github.com/jundot/omlx/issues/2006
- https://www.53ai.com/news/LargeLanguageModel/2026011281562.html
- https://github.com/AyuAnchor/m34-agent
- https://news.ycombinator.com/item?id=47738583
- https://news.ycombinator.com/item?id=47073173
- https://www.philschmid.de/gemini-android-use

**Vendors and platform:**
- https://blog.google/innovation-and-ai/products/gemini-app/android-multi-step-tasks/
- https://support.google.com/pixelphone/answer/16940971
- https://9to5google.com/2026/07/22/gemini-task-automation-samsung/
- https://9to5google.com/2026/01/15/android-16-qpr3-screen-automation/
- https://developer.android.com/ai/computer-control
- https://developer.android.com/ai/appfunctions
- https://ai.google.dev/gemini-api/docs/computer-use
- https://blog.google/technology/google-deepmind/gemini-computer-use-model/
- https://support.google.com/googleplay/android-developer/answer/16550159
- https://support.google.com/googleplay/android-developer/answer/10964491
- https://thehackernews.com/2026/10/android-17-advanced-protection-locks.html
- https://news.samsung.com/global/samsung-unveils-galaxy-s26-series-the-most-intuitive-galaxy-ai-phone-yet
- https://www.honor.com/ie/news/honor-magic8-china-launch/
- https://www.apple.com/newsroom/2026/09/siri-ai-a-profoundly-more-capable-and-personal-assistant-is-here/
- https://www.yicaiglobal.com/news/wechat-some-chinese-banking-apps-reportedly-restrict-devices-with-bytedances-new-ai-voice-assistant
- https://panews.io/articles/01a13cf8-b32e-750e-8074-a39f759f3206

**Benchmarks and models:**
- https://arxiv.org/html/2406.03679 (AndroidControl)
- https://arxiv.org/html/2405.14573 (AndroidWorld)
- https://arxiv.org/html/2504.14239 (InfiGUI-R1)
- https://github.com/Reallm-Labs/InfiGUI-R1
- https://arxiv.org/html/2501.12326 (UI-TARS)
- https://huggingface.co/ByteDance-Seed/UI-TARS-1.5-7B
- https://arxiv.org/html/2410.23218 (OS-Atlas)
- https://arxiv.org/html/2412.04454 (Aguvis)
- https://arxiv.org/html/2504.10458 (GUI-R1)
- https://arxiv.org/html/2507.22025 (UI-AGILE)
- https://arxiv.org/html/2506.01391 (AgentCPM-GUI)
- https://arxiv.org/html/2502.13923 (Qwen2.5-VL)
- https://arxiv.org/html/2508.15144 (GUI-Owl / Mobile-Agent-v3)
- https://arxiv.org/html/2602.16855v1 (GUI-Owl-1.5 / Mobile-Agent-v3.5)
- https://arxiv.org/html/2512.22047 (MAI-UI)
- https://arxiv.org/html/2508.10833 (UI-Venus)
- https://arxiv.org/html/2602.09082v1 (UI-Venus-1.5)
- https://huggingface.co/inclusionAI/UI-Venus-2-9B
- https://huggingface.co/Hcompany/Holo2-4B
- https://arxiv.org/html/2509.26539v1 (Ferret-UI Lite)
- https://arxiv.org/html/2512.15431 (Step-GUI)
- https://huggingface.co/stepfun-ai/GELab-Zero-4B-preview
- https://huggingface.co/zai-org/AutoGLM-Phone-9B
- https://arxiv.org/html/2509.18119 (MobileRL)
- https://arxiv.org/html/2603.24533v1 (UI-Voyager)
- https://arxiv.org/html/2503.15937 (V-Droid)
- https://github.com/V-Droid-Agent/V-Droid
- https://arxiv.org/html/2410.17883 (LiMAC)
- https://arxiv.org/html/2502.06395 (AppVLM)
- https://arxiv.org/html/2609.13287 (LLaDA-UI)
- https://arxiv.org/html/2509.02544 (UI-TARS-2)
- https://arxiv.org/abs/2604.17817 (screentext vs screenshots)
- https://arxiv.org/abs/2606.19388 (CLI vs GUI)
- https://arxiv.org/abs/2608.04755 (permission pop-ups)
- https://github.com/google-research/android_world

**Local VLMs:**
- https://ollama.com/library/qwen3-vl/tags
- https://huggingface.co/Qwen/Qwen3-VL-4B-Instruct-GGUF
- https://huggingface.co/Qwen/Qwen3-VL-8B-Instruct-GGUF
- https://huggingface.co/mlx-community/Qwen3-VL-8B-Instruct-4bit
- https://huggingface.co/mradermacher/GUI-Owl-1.5-4B-Instruct-GGUF
- https://huggingface.co/mPLUG/GUI-Owl-1.5-8B-Instruct
- https://huggingface.co/Tongyi-MAI/MAI-UI-8B
- https://huggingface.co/bartowski/UI-Venus-2-9B-GGUF
- https://huggingface.co/ggml-org/AutoGLM-Phone-9B-GGUF
- https://huggingface.co/mlx-community/AutoGLM-Phone-9B-Multilingual-4bit
- https://huggingface.co/Mungert/UI-TARS-1.5-7B-GGUF
- https://github.com/bytedance/UI-TARS/blob/main/README_coordinates.md
- https://github.com/ggml-org/llama.cpp/pull/16780
- https://github.com/ggml-org/llama.cpp/pull/18042
- https://machinelearning.apple.com/research/exploring-llms-mlx-m5
- https://github.com/ggml-org/llama.cpp/discussions/4167
- https://ollama.com/blog/mlx
- https://testerhome.com/topics/43325
- https://raw.githubusercontent.com/QwenLM/Qwen3-VL/main/cookbooks/mobile_agent.ipynb
