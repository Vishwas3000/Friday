# 21 — Has anyone already built Friday? Working prototypes, as of 2026-10-17

_Last updated: 2026-10-17 · Trust tags: [primary] read at source · [secondary] search/blog/forum · [measured] run here · [UNCERTAIN] unconfirmed or conflicting_

The owner asked: "search if anyone built a working prototype of what we are trying to build here."

**Scope.** Working prototypes only: code, APKs or demos, not papers.
- This file extends [03-prior-art.md](03-prior-art.md) (2026-10-16) and does not repeat it. 03 already covers the Jev phone projects, DroidRun/Mobilerun, Minitap, the AutoGLM apps, PokeClaw, Operit, Panda, Andee, the MCP servers and the vendor table.
- Items from 03 come back here only when something changed, or when the comparison needs them.

**Friday, for comparison:**
- Everything runs on a stock, unrooted Pixel 8.
- An AccessibilityService reads and operates any app.
- Laya-Android (322M, non-generative, ONNX Runtime CPU) picks each step. Rules, skills and intents come first.
- UI memory replays remembered paths with no model.
- Teach mode turns the owner's demonstrations into memory and training rows.
- An on-device wake word, and read-only money questions.

**How we looked:**
- Four parallel web-research passes: on-phone agents, OEM products, teach/replay and memory, and voice assistants.
- A Laya-specific pass: GitHub repo and code search, and the Hugging Face model API.
- Stars and dates come from `gh api` on 2026-10-17.
- Nothing was installed or run. Every speed figure below is the project's own unless tagged [measured].
- Reddit, Play Store and F-Droid pages did not render, so coverage there is thin [UNCERTAIN].

## Answer in one screen

1. **Nobody has published Friday's combination** [search-based; UNCERTAIN as a "first"].
   - No public project uses a non-generative decision model as the per-step UI policy on a phone.
   - The Laya-Android checkpoint has no public user on GitHub apart from one CSV list [primary, code search].
   - No project combines fully local any-app control with UI memory, Teach mode and a local wake word, and shows it working.
2. **Fully local agents that tap through any app exist, but each is slow, heavyweight or tiny** [primary]:
   - **PokeClaw** (1,098★; Gemma 4 E2B on LiteRT-LM): about 2.5 min per single-screen task on a Pixel 8 Pro. No code pushed since 2026-06-02.
   - **MobiAgent's phone runner** (1,920★): an 8-bit MNN vision model served inside Termux, driving the phone through uiautomator2. It asks for 12 GB+ of RAM and a Snapdragon 8 Gen 3 or 8 Elite; the Pixel 8 has 8 GB. The group's own paper times its 4B model at 153.2 s per task on a Snapdragon phone, CPU-only, before memory.
   - **Hobby apps on Gemma 4 or llama.cpp** (Genie, deft, JARVIS, BlackClaw, codai phone, YinYue): 0–8★ each, and none publishes a speed.
   - **Two projects backed off:** OmniBot removed its local MNN inference on 2026-07-16. ClosePaw hides its local client because "on-device models are still too slow".
3. **Closest by feature** [primary]:
   - **UI memory:** KYRIE66nb/mobile-agent's Recipes are the nearest match. They are parameterized step lists replayed with no model, and the model resumes from the failed step. Its model, though, is a cloud LLM. Genie (local Gemma 4) replays cached plans by label and re-plans on a miss.
   - **Teach mode:**
     - Autobile: teach by doing, compiled into semantic skills, on-device model first. 0★, five days of commits.
     - P.A.N.D.A: fully offline, records by accessibility node identity, reports "stopped at step N". It has no model for new tasks.
     - SUGILITE did teach-by-demonstration in 2017 and is now stale.
   - **Wake word + phone control:**
     - Dicio is the mature fully-local one (openWakeWord + Vosk), but it runs intents only, with no UI control.
     - Google Voice Access operates any app, one spoken command at a time.
   - **Same architecture, but on desktops:** macos-use, LayaBrowse, etri and two laya-computer-use repos read the accessibility tree and let a local Laya choose the action. Some add local Whisper.
4. **Commercial (§3):**
   - No shipping phone is verified to operate arbitrary app UIs fully on the device. The only claim is ZTE's Nebula-GUI 7B on the nubia Z70/Z80 Ultra, and it comes from the vendor alone [secondary; UNCERTAIN].
   - Google's screen automation runs the target app in an on-device virtual display, but the model runs in the cloud, and the Pixel 8 is excluded [primary].
   - Chinese OEMs are moving to MCP/A2A, intents and skills, keeping GUI tapping as a fallback [secondary].
   - No OEM ships teach-by-demonstration [primary].
5. **What is genuinely new in Friday** (§6):
   - A non-generative step model on a stock phone, with every model local.
   - A local hand-off from memory to the step model at the first missing element.
   - Teach and confirmed runs that feed fine-tuning of the phone's own policy.
   - Measured repeat tasks in about 2 s.

## 1. Fully on-phone agents (open source; agent loop and model both on the phone)

**Operate any app's UI:**

| Project | Model + runtime | Memory / teach | Maturity (★, created → last push) | Licence | Published speed | Tag |
|---|---|---|---|---|---|---|
| PokeClaw (agents-io/PokeClaw) | Gemma 4 E2B/E4B, LiteRT-LM CPU/GPU; cloud optional | text "skills"; no replay | 1,098★, 2026-04-06 → 06-02; APK v0.7.1 | Apache-2.0 | Pixel 8 Pro, E2B, 4 quick tasks: mean 2 min 34 s (CPU), 2 min 46 s (GPU), cold start | [primary] |
| MobiAgent phone runner (IPADS-SAI/MobiAgent) | MobiMind, AWQ 8-bit, MNN `llm_demo` server in Termux; uiautomator2 | AgentRR record/replay flag | 1,920★; runner added 2025-12-26; last push 2026-07-17 | Apache-2.0 | none for the runner, which wants ≥12 GB RAM and a Snapdragon 8 Gen 3 / 8 Elite. Their MobiMem paper: MobiMind-4B took 153.2 s per task on a Snapdragon phone (CPU-only), 1.6–9× faster with action memory | [primary] |
| Operit (AAswordman/Operit) | MNN (vision), llama.cpp GGUF (text only), Ollama, or cloud | memory, workflows | 8,752★; v1.12.2 | LGPL-3.0 | none | [primary] code; local UI control [UNCERTAIN] |
| deft (bedda-tech/deft) | Gemma 4 on react-native-executorch; optional E4B planner + FunctionGemma 270M dispatcher | polling watches | 6★, 2026-04-13 → 10-15; APK v1.4.5 | MIT | none | [primary] |
| Genie (Akeem1955/Genie) | Gemma 4 E4B, LiteRT-LM GPU, 1,024-token context; Vosk wake word "Gemma" | cached plans, per-screen maps, facts | 7★, 2026-04-16 → 05-18; no APK | none | none | [primary] |
| BlackClaw (tuangel134/BlackClaw) | Gemma 4 E2B/E4B, LiteRT-LM; Vosk wake word; cloud switch | schedules | 8★, 2026-05-29 → 09-06; APK | Apache-2.0 | none | [primary] |
| JARVIS (ansaribilal14/jarvis) | llama.cpp, user's GGUF, GBNF-constrained JSON; cloud presets optional | skill builder with "pick on screen" | 3★, 2026-09-11 → 09-20; APK v2.3.0 | MIT | none | [primary] |
| codai phone (codai-ro/codai-phone) | Gemma 4 E2B + codai-nano fine-tunes, LiteRT-LM; "local-first" hybrid | – | 0★, 2026-09-13 → 10-16; APK | Apache-2.0 | none | [primary] |
| YinYue Mobile (miqian-nomad/YinyueMobile) | MNN + OpenCL or llama.cpp; Qwen3 | – | 1★, one day (2026-08-04) | MIT | none | [primary] |
| phone-agent (Dexter2344/phone-agent) | Ollama Gemma 4 E4B in Termux + ADB + ML Kit OCR | task memory | 2★, 2026-06-09 → 08-09 | none | none | [primary] |
| RikkaHub Agent (ExTV/rikkahub-agent) | local LiteRT (Gemma/Qwen), Gemini Nano via AICore (beta), or any cloud | workflows | 374★, 2026-05-03 → 09-24 | AGPL-3.0 | none | [primary]; local loop quality [UNCERTAIN] |

**Intents, functions or recipes only (no free UI control):**

| Project | Model + runtime | What it can do | Maturity | Licence | Published speed / accuracy | Tag |
|---|---|---|---|---|---|---|
| Edge Gallery "Mobile Actions" (google-ai-edge/gallery) | FunctionGemma 270M fine-tune, LiteRT-LM | 7 fixed functions: torch on/off, create contact, send email, show map, open Wi-Fi settings, create calendar event | 24,983★; Play + APK | app Apache-2.0; model Gemma licence (gated) | Pixel 7 Pro CPU: 1,916 tok/s prefill, 142 tok/s decode; accuracy 58% → 85% after fine-tuning | [primary] |
| GHOST (vNeeL-code/GHOST) | Gemma 4 E2B/E4B, LiteRT-LM | intents, app launch, Termux; taps not documented | 166★, 2025-07-15 → 2026-10-16; APK | MIT | none | [primary]; UI [UNCERTAIN] |
| mllm ChatBotApp + PhoneLM | PhoneLM-1.5B-Call on mllm, CPU or Qualcomm NPU | Android intents | 42★ / 70★; last pushes 2025-03 / 2024-11 | none / Apache-2.0 | DroidCall accuracy 76.5% vs GPT-4o-mini 71.0% | [primary] |
| P.A.N.D.A (just-uniqq/P.A.N.D.A) | no LLM: Vosk + TF-IDF nearest-centroid classifier + JSON accessibility recipes | recipes and taught macros | 0★, one day (2026-09-22) | none | ~1.4–1.5 s from end of speech to action (a 1.2 s silence timeout dominates), Dimensity 8300 | [primary, self-reported] |

**Notes:**
- **PokeClaw** [primary]:
  - Its local results are single-screen tasks: clipboard, notifications, battery, storage.
  - GPU was not faster than CPU on the Pixel 8 Pro.
  - Free-form local tasks stay off (03).
  - Since June the only activity is bug reports and two unmerged pull requests from forks.
  - A wake word is deferred pending "privacy, permission, and battery design".
- **MobiAgent** [primary]:
  - The Termux runner calls a local MNN server on 127.0.0.1:8080 for planner, decider and grounder.
  - The same group released, on 2026-07-17:
    - ClawMate, a "proactive on-device agent system" for HarmonyOS NEXT only.
    - MobiInfer, MNN on Qualcomm and Kirin NPUs, with a phone GUI-agent demo.
  - The MobiMem paper (2025-12) timed MobiMind-4B without memory at 14.1 s per task on an A100, 27.4 s on an Ascend 910B, and 153.2 s on a Snapdragon phone running CPU-only. Action memory gave 1.6–9× on the phone and 1.3–2.1× on the A100.
  - Besides PokeClaw's, that is the only published on-phone task time for a generative GUI agent we found.
- **Operit** [primary code; UNCERTAIN in practice]:
  - Its UI sub-agent refuses to run unless the UI model accepts images.
  - Only its MNN provider has an image path. So local UI control needs an MNN vision model, which is neither documented nor benchmarked.
- **Genie** [primary]:
  - It looks up memory in this order: built-in skills, then stored skills (SQL `LIKE` on the goal text), then live planning.
  - A cached step that fails falls back to live planning. A `successCount` ranks stored skills.
  - Vosk spots the wake word; Android's SpeechRecognizer then takes the sentence.
- **deft** splits planning (E4B) from dispatch (FunctionGemma 270M) "for lower per-step latency", without numbers [primary].
- **Needle 3** (cactus-compute/needle, 14,064★) is a component, not an agent [primary]:
  - An 8–29 MB 2-bit tool-caller, plus 16.9 MB "Whistle" speech-to-text.
  - It returns a confidence per call, meant for act / confirm / refuse routing.
- **The Mobile Actions data** (google/mobile-actions, CC-BY-4.0, 9,654 rows) is open [primary]. Google pitches FunctionGemma as a "traffic controller" that handles common commands on the device and routes harder ones to larger models [primary].

## 2. Laya and other decision models since 03

### 2.1 On phones: nothing new drives the UI [primary]
- **Searches:**
  - GitHub repo search ("laya" with android / phone / accessibility / mobile agent) and Hugging Face model search ("laya" with android / mobile / gui / phone) found no new Laya GUI agent after 2026-10-16.
  - Code search found no public repo using `ByunByun/laya-android` apart from one CSV list. Code search covers indexed default branches only [UNCERTAIN].
- **Laya runs inside several Android apps that are not GUI agents.** All use base Laya, not Laya-Android:
  - **woaiys3/laya-decide** (2★, pushed 2026-10-17): an offline "pick among options" toy. The 35 MB APK downloads a 262 MB int4 model in the app, and an MCP server is built in. Its author reports zero-shot confidences of 0.09–0.14.
  - **charioteer/laya-mobile** (HF, 2026-10-01): ONNX q8 (MatMulNBits, 654 MB) for Android and Core ML fp16-512 for iOS, with 46 parity vectors. An iPhone 13 Pro Max Neural Engine test is in OpenJevSwift PR 85.
  - **alifthi/Laya-Android-SDK** (0★): a ggml C/C++ runtime. One GGUF file carries the tokenizer and calibration. NDK cross-compile, CPU only.
  - **rookery-labs/laya-onnx-android** (0★): a Rust JNI tokenizer plus ORT Mobile for a "Corvus Browser". It claims < 40 ms and publishes no results.
  - **Zhanfg/LayaEdge:** a README stub.
  - Already in research/01: edwardmonteiro's on-phone Laya game, androidli's int4 build and ggml-org's GGUF.
- **AkhileshManda/local-settings-slm** distils Laya into Qwen2.5-0.5B for 70+ settings actions: 66.67% routing accuracy on its own suite. The Android accessibility app is only planned.
- **KYRIE66nb/mobile-agent is still the only phone app with a Laya hook.** In v0.1.20 (2026-10-17, 111★), Laya or Jev remains an optional, self-hosted backend for the safety gate, in SHADOW or ENFORCE mode. Its Recipes are in §4.2.

### 2.2 Desktop analogues: Friday's architecture, but not on a phone [primary]

| Project | ★, dates | What it does | Licence |
|---|---|---|---|
| ArdaOzd/macos-use | 1★, 2026-09-22 | Local Whisper small.en plus local Laya (or hosted Jev) operate a native Mac app or a Chrome tab through accessibility. Preview by default; rejects stale targets and ambiguous labels; skips password fields. | MIT |
| aryanbhujade/laya-mlx-voice-browser ("LayaBrowse") | 10★, 09-22 → 09-26; demo video | Hotkey plus Apple Speech. laya-browser v10s on laya-mlx chooses a goal, an operation, a target and literal text. Success is reported only when verified on the page; a bare DONE ends as `unverified_done`. Its 126-step development replay went from 109 to 119 passes. | MIT |
| prajazz66/etri-voice-control | 0★, 09-22 | Linux desktop: always-on faster-whisper, qwen3:4b as planner, Laya grounding over AT-SPI + OCR. A spoken "yes" is needed for destructive actions. | proprietary, view-only |
| adityachauhan0/laya-computer-use | 0★, 10-09 | macOS MCP server: accessibility table → Laya MLX. Rejects stale indexes; asks approval below 0.5 confidence. A TextEdit write is shown; Calculator "12 × 34" stopped at ~0.05 confidence. | none |
| ChenneyZhuang/laya-computer-use | 0★, 09-25 | macOS: Laya decides every step over the AX tree, reusing laya-browser-agent's loop. | Apache-2.0 |
| FluidInference/FluidUse | 518★, 09-21 → 10-14 | macOS form filling through accessibility with a 706K-parameter model (~1 ms on the ANE), plus Laya Core ML buckets (3.7 ms per short question on an M5 Pro). | Apache-2.0 |

These show that Laya over an accessibility tree, with local speech, works on desktops. None runs on a phone, and none has memory or teach features.

### 2.3 New Jev phone projects since 2026-10-12 (decisions in the cloud) [primary]
- **dongxinsuperman/jev-phone** (11★): a multimodal Jev picks numbered boxes drawn from the UI tree onto a screenshot. Android, iOS and HarmonyOS. Model round trip 0.65–0.76 s; 1.0–1.9 s per step.
- **fiveapplesonthetable/phone-journeys** (0★): three bounded journeys, each with an independent success check.
- **srsergi0/jev-adb** (0★): an MCP server over ADB.

### 2.4 New small phone policies (weights only, no app) [primary]
- **thealper2/smollm2-135m-android-control** (2026-09-22): a SmolLM2-135M LoRA. It reads a text element list and writes one JSON action, including typed text and app names.
  - Test set (n = 904): exact action 0.695, action type 0.883, click target 0.488, open-app 0.824, input text 0.792.
  - Its prompt includes the per-step instruction (AndroidControl-Low style), so these numbers are not comparable with Laya-Android's High numbers.
- **nba156/gemma-3n-E4B-it-Android-Control-84k** (2026-10-16; GGUF on 10-17) and OfficerChul's E2B version (2025-10): screenshot in, coordinates out. No evaluation in the cards.

## 3. Commercial and OEM agents

This section firms up several items 03 left [UNCERTAIN].

**Bottom line:**
- **No shipping phone is verified to operate arbitrary third-party app UIs fully on the device.**
  - The one claim is ZTE's Nebula-GUI 7B on the nubia Z70/Z80 Ultra: narrow tasks such as one-sentence ticket booking, 30+ apps. It comes from vendor PR only [secondary; UNCERTAIN].
- **Everyone else is cloud or hybrid.** The 2026 Chinese launches put MCP/A2A, intents and skills first, and keep GUI tapping as a fallback [secondary].
- **The Pixel 8 is excluded from every Google agent feature** [primary]:
  - It is missing from the screen automation, Magic Cue and Proactive Assistance lists.
  - It is also missing from the ML Kit GenAI Prompt API devices (nano-v3 on Pixel 9/10, nano-v4 on Pixel 11).
  - So Friday has to bring its own model.
- **No OEM ships teach-by-demonstration.** The nearest are set-once or triggered tasks (Honor's "YOYO tasks"), and Rabbit's teach mode stayed web-only [primary].

| Product | What it operates | Where the model runs | Availability | Third-party access | Teach / memory | Tag |
|---|---|---|---|---|---|---|
| Google Gemini screen automation | any app UI, 40+ selected apps, inside an on-device virtual display | cloud: "Gemini takes screenshots… reviewed by trained reviewers" | beta; Pixel 10/11, S26, Z Flip8/Fold8, Tab S12; IN/JP/KR/UK/US; **not Pixel 8/9** | none: Computer Control is OEM-preloaded and privileged | none | [primary] |
| Gemini "Device assistance" (formerly Utilities) | the phone itself: settings, alarms, opening apps, media, notifications | cloud | Android, including Pixel 8 | – | – | [primary] |
| Pixel Magic Cue / Proactive Assistance | suggestions and one-tap actions; does not operate apps | "on your device or in the cloud" | Pixel 10/11 only | – | personal data | [primary] |
| Samsung Bixby (One UI 8.5 / 9) | Galaxy settings and features | needs a data connection; web answers "through Perplexity" | beta 2026-02-20; S26 | – | – | [primary] |
| Perplexity on the S26 | Samsung apps and "select" third-party apps | cloud | S26 | – | – | [secondary] |
| Honor YOYO (MagicOS 10 → 11) | UIs through MagicGUI (Qwen2-VL-7B), plus MCP and services | hybrid: task planning and tool calls in the cloud | Magic8 (2025-10), Magic9 (2026-09); China | MCP | habits; set-once "YOYO tasks" | [primary + secondary] |
| Xiaomi Super XiaoAi 2.0 / miclaw | system apps plus ~10 third-party services through Intents or an AppTool SDK, not GUI | hybrid; expert mode paid in MiMo points | HyperOS 4 beta, 2026-08 | Intents, AppTool SDK | memory you can view and delete | [secondary] |
| vivo Xiao V Pro (OriginOS 7) | 6,000+ "atomic capabilities", mostly vivo's own apps; third parties through Alipay mini-programs | cloud API, per SuperCLUE's listing | X500 / iQOO 16, 2026-09 | Alipay mini-programs | memory | [secondary] |
| OPPO Xiaobu / AgentMatrix (ColorOS 17) | partner services: Alipay (~200 services), Tencent Maps, WeChat | hybrid: "device-cloud collaboration" | Find X10 / OnePlus 16, 2026-09 | Manus deal, 2026-10-13 | PersonaX | [secondary] |
| Huawei Xiaoyi (HarmonyOS 7, HMAF 2.0) | 2,100+ system skills, intents, MCP/A2A; no GUI tapping mentioned | hybrid, "local-first" | developer beta 2026-06 | Insight-Intent decorators, Agent Kit | long-term memory | [primary + secondary] |
| Apple Siri (iOS 27) | App Intents and annotated entities only: without them "Siri can't act" | on-device + Private Cloud Compute | 2026-09 | App Intents | personal context | [primary] |
| ByteDance Doubao (nubia M153 → NaviX Ultra) | any UI via `INJECT_EVENTS`; now MCP/A2A first, GUI as fallback | hybrid: OCR, embeddings and NER local; reasoning in the cloud | NaviX Ultra on sale 2026-09-16, China | apps opt out via SAEP | – | [secondary] |
| ZTE Nebula-GUI 7B | GUI, 30+ apps | vendor says on-device | nubia Z70/Z80 Ultra | – | – | [secondary; UNCERTAIN] |
| StepFun STEPX Neo (new) | partner services via MCP/A2A; settings and alarms on the device | hybrid: "fast reactions on-device, deep thinking in the cloud" | on sale 2026-10-16, China | MCP/A2A | two-part memory | [secondary] |
| Rabbit (teach mode, OS3) | websites; PCs and cloud VMs. Phones are chat clients only | cloud, plus a local agent on PCs | OS3 2026-09-22 | skills added by URL | teach mode: websites only (2024-11) | [primary] |
| Motorola LAM → Qira | the LAM stayed a demo; Qira acts through connectors (Gmail, Calendar, Slack) | Qira "prioritizes on-device" | Razr Ultra 2026 with Android 17, from 2026-10-06 | connectors | learns intent | [primary + secondary] |
| Nothing; Perplexity Assistant; Deutsche Telekom T Phone 3 | no UI operation; actions through integrations | cloud | – | – | – | [secondary] |
| MediaTek Dimensity Agentic Engine; Qualcomm demos | demos: "hybrid structure of edge and cloud" | an on-device demo only | no shipping product named | – | – | [primary + secondary] |

**Notes:**
- **Google** [primary]:
  - Screen automation runs "in apps using an on-device virtual phone environment". The Gemini Privacy Hub (updated 2026-10-13) says screenshots taken during automation are "reviewed by trained reviewers", so the model is in the cloud. This firms up 03's "cloud".
  - You "can't take control of a task" on the Pixel 10 or Galaxy S26 yet. Daily caps of 5/12/20/120 requests by subscription tier are [secondary].
  - Computer Control (docs of 2026-05-13) needs the privileged `ACCESS_COMPUTER_CONTROL`, allows at most 6 target apps per session, and describes no opt-out for target apps.
  - Voice Access only gained "Hey Google, start Voice Access" (2025-12-02). We found no Gemini natural-language or multi-step mode [UNCERTAIN: absence of evidence].
- **Samsung** firms up 03's "mostly cloud". Samsung calls Bixby "a conversational device agent that lets users control Galaxy settings and features", which "requires Samsung Account login and data connection" [primary]. Modes & Routines are still user-built if/then rules [secondary].
- **Honor** firms up 03's [UNCERTAIN]:
  - YOYO operates UIs "relying on the MagicGUI multimodal model" [primary].
  - MagicGUI is Qwen2-VL-7B, called an "on-device GUI agent", with weights for academic use only. Its published inference example uses CUDA [primary].
  - Planning is "end-to-end cloud computing" [secondary, quoting Honor].
  - MagicOS 11 (2026-09-15) adds an "Agent Harness", tasks of 100+ steps, and YOYO tasks that run when a trigger fires [primary].
- **Xiaomi** firms up 03's "only opens apps". Third-party apps come in through Intents or an AppTool SDK (AIDL JSON-RPC), not GUI [secondary].
- **vivo:**
  - PhoneGPT (2024-10) demonstrated reading and operating the screen, with BlueLM-3B on the device and a ~100B cloud model for planning [secondary].
  - Its research model BlueLM-GUI (35B-A3B, AndroidWorld 84.9, RL on hundreds of real phones) does not say where it runs [primary, arXiv 2609.12394].
- **Doubao / ZTE:**
  - 03's "cloud" is better described as hybrid: OCR, embeddings and NER run locally, and reasoning runs in the cloud [secondary].
  - As of 2026-10-16, only ByteDance and ZTE apps work reliably, plus a few third parties (Ctrip, Caocao) [secondary].
- **Qualcomm's** Summit 2026 demo ran a StepFun 30B MoE "entirely on the end side" on a reference design. Its China chairman expects steady personal agents only in 2027–2028 [secondary].
- **The industry's direction matches Friday's order:** structured actions first (intents, skills, MCP), GUI operation as the fallback.

## 4. Closest matches by feature

### 4.1 Teach by demonstration, record and replay

| Project | How it learns | How it finds elements again | Parameters? | On the phone? | Maturity | Licence | Tag |
|---|---|---|---|---|---|---|---|
| Autobile (leestana01/autobile) | demonstration → trace cleanup → intent confirmation → compiled semantic skill | exact locator / accessibility semantics first, then on-device model (Gemini Nano via ML Kit), then opt-in cloud | "learns the intent, not the taps" | yes; the LiteRT-LM engine is not shipped yet | 0★, 2026-09-11 → 09-15; APK v0.2.0 | Apache-2.0 | [primary] |
| P.A.N.D.A | touch recording by accessibility node identity and bounds; encrypted macros; step editor | node identity + bounds; replay reports "stopped at step N" | no | yes, no INTERNET permission | 0★, one day | none | [primary] |
| JARVIS v2.3 | step builder; "pick on screen"; "honest quick record" | element picked on a screenshot | – | yes | 3★ | MIT | [primary] |
| Key Mapper "Interact with app element" | 60 s record mode | uniqueId → label confirmed by id (or by class) → id / class | no | yes | 2,743★; v4.6.1, 2026-10-10 | GPL-3.0 | [primary] |
| Automate "Interact" block | "Record interactions" per block | XPath over class, id, text, description; "may have to be manually modified" | expressions | yes | commercial | proprietary | [primary] |
| MacroDroid "UI Interaction"; Tasker AutoInput Actions v2 | point at one element; no multi-step recorder | text / view id / x,y | Tasker variables | yes | commercial | proprietary | [secondary] |
| Klick'r (Smart AutoClicker) | scenario editor | image matching | no | yes | 3,680★ | GPL-3.0 | [primary] |
| SUGILITE (also backs APPINITE, PUMICE, EPIDOSITE) | demonstration + voice | first unique feature: label → view id or child label → bbox | yes: spoken words matched to clicked labels; siblings kept as alternative values | scripts stored on the phone; speech and parser on servers | 63★; last push 2022-04 | none | [primary] |
| GPTVoiceTasker (UIST'24) | screen graph built from its own runs (not the owner's ordinary use; research/24) | path replayed by bounds | yes (LLM) | graph on the phone; GPT-4 by API | 13★; 2024-04 | none | [primary] |

**Results and notes** [primary unless tagged]:
- **SUGILITE:** 65 of 76 user-made scripts (85.5%) ran.
  - It checks at teach time that the chosen feature is unique, and asks the user when it is not.
  - On failure the user can wait, stop, "replace" the rest, or "create a fork", which works like try/catch.
  - Building it needs a Google Cloud speech credential, and its semantic-parser server is dead.
- **GPTVoiceTasker:** tasks took 92.5 s against 162.2 s with Voice Access. Replay from stored paths was 82.7% correct, or 72.0% with parameters.
- **JARVIS is the warning for Teach mode.** "Four recorder rewrites in a row failed on a real device", including raw touch capture through Shizuku, which on Android 14+ "could record nothing at all". v2.3 deleted the recorder; users now build steps and pick elements on a screenshot.
  - This bears on Friday. Teach records accessibility click events, and our notes say Compose apps and self-hiding buttons send none (Play Store tabs, Clock's Reset). For those, an optional mode reads the real touches from the Mac (`scripts/teach_touches.py`), which needs the PC.
  - JARVIS's "pick on screen" is a phone-only fallback for exactly those steps.
- **Nobody records a multi-step parameterized flow in the consumer apps.** MacroDroid and AutoInput build steps by pointing at elements [secondary]. No voice trigger was verified for any of them [UNCERTAIN].
- **Elsewhere:**
  - Rabbit's teach mode is web-only and cloud, in beta since Nov 2024 [secondary].
  - Gemini's task automation has no teach or save feature [primary].

### 4.2 UI memory and skill caching

| Project | Where the model runs | What is remembered | How it matches | On a miss | Published gain | Maturity | Tag |
|---|---|---|---|---|---|---|---|
| KYRIE66nb/mobile-agent "Recipes" | phone loop; cloud LLM (any OpenAI-compatible) | verified flow as a deterministic step list with `{contact}`, `{message}` slots; `recipe_save` after a successful run; built-in `wechat_send_message` | selectors: text, description, id, type, regex | returns the failed step's index; the model continues from there | none | 111★; v0.1.20, 2026-10-17 | [primary] |
| Genie | phone, local Gemma 4 E4B | tool-call list (click by label) keyed by goal text | SQL `LIKE` on the goal; label lookup | live planning | none | 7★ | [primary] |
| Autobile | phone first | compiled, versioned semantic skill | exact locator / accessibility semantics | bounded recovery, repair proposals | none | 0★ | [primary] |
| Mobilerun macros | host (Python) + device app | the agent's own recorded runs | screen similarity ≥ 0.85, then tap the recorded x,y | `--on-mismatch agent` restarts the whole goal | none | 9,678★; macros 2025-07, screen check 2026-05-27 | [primary] |
| MobileGPT (MobiCom'24) | cloud (Python server + OpenAI; Android client) | sub-tasks with `<arg_i>` placeholders; pages matched by embeddings of the hierarchy | text/description → child (depth, rank, text) → parent's text | LLM | latency −62.5%, cost −68.8% | 28★; 2024-10 | [primary] |
| AgentRR / MobiMem (MobiAgent `agent_rr/`) | host runner over ADB; MobiMind | action tree keyed by task text | embedding ≥ 0.8 + reranker ≥ 0.75; element check by bbox + crop SSIM > 0.9, or OCR text | cached action invalidated | AgentRR: 2–3× on average. MobiMem: 14.1 → 8.6 s per task with MobiMind-4B (likely on an A100); up to 4.5× on tasks with > 92% reuse (GUI-Owl-7B); 1.6–9× on a Snapdragon phone, CPU-only | 1,920★ | [primary] |
| AutoDroid | cloud | UI transition graph from offline exploration | Instructor-XL similarity above a threshold | LLM | shortcut right 75% of the time, saving 38% of steps; LLM calls −13.7%, latency −21.3% | 500★; 2024-03 | [primary] |
| AppAgentX | cloud | evolving shortcuts (Neo4j + Pinecone vision embeddings) | vision | LLM | steps 9.1 → 5.7; 23 → 16 s per step | 678★; 2025-04 | [primary] |
| Mobile-Agent-E | cloud | Shortcuts + Tips | LLM picks, with (x,y) | LLM | satisfaction 75.1 → 86.9; shortcuts are 12–15% of operations | in X-PLUG/MobileAgent | [primary] |
| PhoneCLI / OpenPhone (HKUDS) | 3B model on an RTX 3090, ADB from a host | compiled app maps | – | model | map replay < 1 s vs 3.5–4.2 s per model step | 991★ | [primary] |
| OmniBot | cloud vision model (local MNN removed 2026-07-16) | record → "Register as Function" → replay | – | cloud vision model | none | 2,049★ | [primary] |

**Notes** [primary]:
- **Mobilerun's screen check** is a Jaccard overlap of (resource_id, class, text, desc, clickable, enabled, focused), weighted 0.85, plus package/activity weighted 0.15. It waits up to 5 s for ≥ 0.85.
- **AgentRR replay rates:** 30–60% of tasks with a uniform task mix, 60–85% with a power-law mix. Replay correctness is > 99%.
- **MobiMem reuse:** 37.5% for the plain action tree, against 59.7% with LLM-made templates and 77.3% with human-made templates. About 6,000 actions take 1.54 MB.
- **Transferable from the web:**
  - Agent Workflow Memory: +24.6% / +51.1% relative success (Apache-2.0).
  - SkillWeaver: +31.8% / +39.8%. Skills built by strong agents lift weaker agents by up to 54.3% (MIT).
- **How Friday's UI memory differs:**
  - **Matching:** it matches element signatures (package, resource id, class, label) on the live tree. Key Mapper and MobileGPT work this way too. Mobilerun replays coordinates, and AgentRR and AppAgentX match by vision.
  - **On a miss:** it hands off to the step model at the first missing element, with the history so far. mobile-agent does the same, but with a cloud model. Genie re-plans; Mobilerun restarts the whole goal.
  - **Where it runs:** everything stays on the phone, and the model it falls back to is non-generative.

### 4.3 Local wake word plus phone control

| Project | Wake word | Speech-to-text | LLM | Phone actions | Fully offline? | Maturity | Licence | Tag |
|---|---|---|---|---|---|---|---|---|
| Dicio (DicioTeam/dicio-android) | openWakeWord | Vosk small (~50 MB) or any speech-input app | none | intents and system APIs; no AccessibilityService | yes (web skills aside) | 1,513★; v4.2, pushed 2026-10-11; F-Droid, Play | GPL-3.0 | [primary] |
| Dina (Kakauet/Dina) | openWakeWord "Dina" | Android or Moonshine | LFM2.5-1.2B LoRA on llama.cpp + GBNF | alarms, timers, lists, conversions; refuses music, calls, messages | yes | 1★, 2026-10-07 → 10-11 | MIT (some model licences non-commercial) | [primary] |
| Genie | Vosk ("Gemma") | Android SpeechRecognizer | Gemma 4 E4B, local | any app | mostly [UNCERTAIN: recognizer may go online] | 7★ | none | [primary] |
| BlackClaw | Vosk | – | Gemma 4 local, or cloud | any app | partly | 8★ | Apache-2.0 | [primary] |
| P.A.N.D.A | Vosk ("Panda") | Vosk: Indian English, Hindi, Telugu packs | none | recipes and taught macros | yes | 0★ | none | [primary] |
| Operit | speech keyword, or personal MFCC + DTW templates | sherpa-ncnn (zh/en) or cloud | MNN / llama.cpp, or cloud | tools, intents, GUI automation | possible | 8,752★ | LGPL-3.0 | [primary]; local GUI [UNCERTAIN] |
| Google Voice Access | none of its own ("Hey Google, start Voice Access", or a shortcut) | Google; offline with language packs | none | any app, one command at a time: numbers, labels, grid, visible text | yes, with packs | Google | proprietary | [primary] |
| Panda (blurr) / OpenDroid | Porcupine or a SpeechRecognizer loop / a SpeechRecognizer loop | Android | Gemini API / LiteRT-LM, Gemini Nano or cloud | any app | no / partly | 993★ / 1,159★ | personal-use / Apache-2.0 per README | [primary] |
| AnaaySampat/jarvis | openWakeWord "Hey Jarvis", re-checked by Whisper | Groq Whisper | Gemini / Groq | any app | no | 16★ | MIT | [primary] |
| n1dlee/friday-mobile (unrelated "Friday") | Vosk keyword spotting + on-device speaker verification (ResNet34) | Groq Whisper | Groq gpt-oss | ~40 local commands + tools | no | 2★, 2026-10-06 | none declared | [primary] |
| Home Assistant companion app | microWakeWord, experimental | HA server | server | HA entities; phone actions only as server-sent commands | no | 3,975★ | Apache-2.0 | [primary] |

**Notes:**
- **Dicio:**
  - LLM support has been an open issue (#132) since 2023.
  - Its maintainer's 2024 /e/OS proof of concept (Vosk + llama.cpp Phi-2 → JSON skills) was called slow.
  - Users in that issue (June 2026) still call on-phone LLM tool calling underwhelming [primary].
- **Voice Access:** you chain steps yourself, one utterance each [primary]. We found no Gemini natural-language command mode as of 2026-10-17 [UNCERTAIN: absence of evidence].
- **Home Assistant's docs back Friday's charging-only listening.** Third-party wake words use more power than "OK Google" because apps "can't use Google's low-power hardware, so the CPU stays active" [primary].
- **Panda and OpenDroid** call their wake word "offline", but it is a SpeechRecognizer loop with substring matching [primary]. Friday's openWakeWord is the sounder design.
- **Picovoice** offers "no dedicated free or paid plans for personal or non-commercial use" [primary]. Free-tier keys reportedly stopped working after 2026-06-30 [secondary, a forum post quoting Picovoice].
- **friday-mobile** "learns" phrases the LLM worked out, so the next time skips the model. That is close to Friday's rules-first design [primary].

## 5. One table: the closest prototypes against Friday

| Project | Runs where | Model / runtime | Operates any app? | Memory / teach? | Maturity | What Friday can reuse |
|---|---|---|---|---|---|---|
| **Friday** (this repo) | phone only | Laya-Android 322M on ORT CPU; rules + intents first | yes, accessibility | UI memory by element signature, handing off to Laya at a miss; Teach → memory + training rows | private; measured on a Pixel 8 | – |
| PokeClaw | phone; cloud optional | Gemma 4 E2B, LiteRT-LM | yes | text skills | 1,098★; no code since 06-02 | its single-screen local task list as a baseline |
| MobiAgent (phone runner; AgentRR) | phone via Termux on a flagship; AgentRR on a host | MobiMind 8-bit on MNN | yes, screenshots | record/replay (AgentRR) | 1,920★ | template-vs-tree reuse numbers; embedding + reranker task keys |
| KYRIE66nb/mobile-agent | phone loop + cloud LLM | any OpenAI-compatible model; optional self-hosted Laya gate | yes; root virtual display optional | Recipes with slots; scheduled and notification triggers | 111★; v0.1.20 | failed-step hand-off; per-task `tool_scope` whitelist, read-only by default |
| Genie | phone | Gemma 4 E4B, LiteRT-LM GPU | yes | cached plans by goal; screen maps; facts | 7★; May 2026 | `successCount` ranking of remembered paths |
| Autobile | phone first; cloud opt-in | Gemini Nano (ML Kit) | yes | teach by doing → compiled skill; triggers; versions | 0★; Sept 2026 | "an unverifiable outcome is never reported as success"; per-step post-conditions; skill versioning |
| P.A.N.D.A | phone, no INTERNET permission | none (Vosk + TF-IDF) | recipes only | Teach by node identity; "stopped at step N" | 0★ | a CI check that the manifest has no network permission; Indian-language speech packs |
| JARVIS | phone; local by default | llama.cpp GGUF + GBNF | yes | step builder, "pick on screen" | 3★ | its failed raw-touch recorder; pick-on-screen for steps that send no click event |
| Dicio | phone | none; openWakeWord + Vosk | intents only | – | 1,513★ | already our wake-word reference (research/16) |
| Edge Gallery "Mobile Actions" | phone | FunctionGemma 270M, LiteRT-LM | 7 intents | – | 24,983★ | the CC-BY-4.0 dataset for the text layer |
| Google Voice Access | phone | Google speech | any app, one command at a time | – | Google | number/label overlay as a manual fallback |
| LayaBrowse / macos-use | Mac | Laya, with Apple Speech / Whisper | browser / Mac apps | – | 10★ / 1★ | typed goal + verified outcome; stale-target rejection |
| Mobilerun macros | host + phone app | any LLM | yes | replay of recorded coordinates | 9,678★ | whole-screen similarity check before each replayed step |
| Key Mapper | phone | none | one element per action | recorder + matcher | 2,743★ | its element-matching order (ideas only; GPL-3.0) |
| SUGILITE | phone + servers | none (2017) | yes, scripts | demonstration with parameters | 63★; stale | teach-time uniqueness check, sibling values, forks |
| Gemini screen automation | app in an on-device virtual display; model in the cloud | Gemini | 40+ selected apps | none | beta; Pixel 10/11, S26; not Pixel 8 | nothing technical: the first-party path is closed to the Pixel 8 and to third parties |
| Honor YOYO / Doubao / ZTE Nebula-GUI | hybrid; ZTE claims on-device | MagicGUI 7B / Doubao / Nebula-GUI 7B | GUI in some apps; moving to MCP/A2A | set-once tasks (Honor) | shipping in China | confirmation of the order "structured actions first, GUI as fallback" |

## 6. Verdict

**Has anyone built a working, fully local, general app-operating assistant on a stock phone?** Partly.
- **Yes, as prototypes.** Several projects run a model on the phone and tap through any app, with no PC and no cloud: PokeClaw, MobiAgent's Termux runner, and hobby apps (Genie, deft, JARVIS, BlackClaw, codai phone, YinYue) [primary].
- **None is a usable daily assistant on its own evidence** [primary]:
  - PokeClaw takes minutes per single-screen task, and turned free-form local tasks off.
  - MobiAgent needs a flagship with 12 GB+ RAM and a Termux setup. Its paper measured 153.2 s per task on a Snapdragon phone, CPU-only, before memory.
  - The hobby apps publish no speed and no success rate.
  - Two projects removed or hid their local mode as too slow.
- **None combines it with UI memory, Teach mode and a local wake word** [primary, search-based].

**The closest, by axis:**
- Fully local and general: **PokeClaw**.
- Feature set: **KYRIE66nb/mobile-agent**, but with a cloud LLM.
- Local, with wake word and memory: **Genie** (tiny and unmeasured).
- Teach mode: **Autobile** and **P.A.N.D.A**.
- Architecture: **macos-use** and **LayaBrowse**, on desktops.

**Genuinely new in Friday** [search-based; UNCERTAIN as a "first"]:
1. **A non-generative decision model as the per-step UI policy, on a stock phone, with every model local.** Every other phone agent uses a generative LLM or VLM, or no model at all.
2. **Local replay by element signature, with no model, handing off mid-task to the step model at the first missing element.** mobile-agent does the hand-off with a cloud model. Genie re-plans and Mobilerun restarts the goal.
3. **Teach demonstrations and owner-confirmed runs feed both replay memory and fine-tuning of the phone's own step policy.** Others either only replay (SUGILITE, Autobile, P.A.N.D.A, mobile-agent) or cache agent runs on a host (AgentRR/MobiMem). None we found trains the phone's own step model from them.
4. **Measured speed on the target phone** [measured, research/20]: 2.0–2.2 s for a repeat task from memory, 22.6 s the first time for a two-step task. PokeClaw reports minutes for single-screen tasks, and MobiMem 153.2 s per task for a 4B model on a Snapdragon CPU [primary]. The tasks and phones differ, so this is only indicative [UNCERTAIN].
5. **Read-only UPI money questions behind the fingerprint:** no prototype found, but we did not search this in depth [UNCERTAIN].

**Not new** [primary]:
- Skills and intents before the model: Genie, PokeClaw, P.A.N.D.A, friday-mobile, Autobile.
- Deterministic replay with slots: mobile-agent, MobileGPT, SUGILITE.
- Risk gates that fail closed: mobile-agent, Autobile, P.A.N.D.A.
- Verified outcomes: Autobile, LayaBrowse.
- A local wake word with local speech-to-text: Dicio, Dina, P.A.N.D.A.

## 7. What Friday can reuse (ranked)
1. **Stopping, our weakest point** (research/17, 20) [primary]:
   - LayaBrowse: Laya picks a typed goal, and success needs a verified outcome. A bare DONE ends as `unverified_done`.
   - Autobile: a post-condition per step, and "an unverifiable outcome is never reported as success".
2. **UI-memory matching** [primary]:
   - Key Mapper's order: uniqueId → label confirmed by id (or by class) → id / class.
   - Key Mapper records TYPE_VIEW_CLICKED and TYPE_VIEW_FOCUSED, and dumps the window on a window change, because "the source is not necessarily the element the user wants to tap".
   - MobileGPT anchors unlabelled icons by parent and child.
   - Copy the ideas, not the GPL code.
3. **Teach** [primary]:
   - From SUGILITE: check at teach time that the signature is unique on screen; keep sibling values as alternatives; save the model's continuation after a miss as a fork of the path.
   - From JARVIS: don't build an on-phone raw-touch recorder. Use "pick on screen" for steps that send no click event.
4. **A replay guard:** Mobilerun's whole-screen similarity of ≥ 0.85 before each replayed step [primary].
5. **Task keys** [primary]:
   - If content-word keys miss paraphrases, use AgentRR's embedding ≥ 0.8 plus reranker ≥ 0.75.
   - Slots nearly double reuse: 37.5% for a plain tree, 59.7–77.3% with templates.
6. **Later, for unattended runs:** mobile-agent's per-task `tool_scope` whitelist, read-only by default, with cooldowns and daily caps [primary].
7. **Wake word:** confirm each wake with a second check by local speech-to-text, as AnaaySampat/jarvis does with Whisper [primary].
8. **Text layer** [primary]:
   - The Mobile Actions dataset (CC-BY-4.0).
   - Needle's per-call confidence for act / confirm / refuse.

## Dead ends

**"On-device", but cloud or no UI control** [primary]:
- Nexa Octopus v2: the Android code "will be published later" and never was. Licence CC-BY-NC-4.0, and its 0.38 s claim names no device.
- Llama Stack's Android demo: tool calling "works with remote inference only".
- AgentCPM-GUI: an 8B model served by vLLM on a GPU host; no phone app.
- MAI-UI: the paper's 2B on-device plus 32B cloud split has no phone runtime in the repo.
- PhoneCLI/OpenPhone: its "on-device" 3B model ran on an RTX 3090.
- PalmClaw: cloud API; UI automation is still on its roadmap.
- PhoneClaw: iOS, API skills, no taps.
- Oh-Matilda, Operator-on-Android, AutoGLM-TERMUX, PocketMind: cloud models.
- Lynk: phone control for agents on a PC only.
- AndyClaw: its local Qwen2.5-1.5B mode documents system toggles only.

**OEM claims that didn't hold up:**
- "Gemini controls apps without the cloud" (mixvale, 2026-03): it confuses the on-device virtual display with where the model runs. Google's screenshot-reviewer statement contradicts it [primary].
- NaviX Ultra "all key AI processing on-device": a reviewer found the reasoning runs in the cloud [secondary].
- MagicGUI "on-device": no phone runtime is published, and its inference example uses CUDA [primary].
- SuperCLUE's "离线" (offline) means a static evaluation, not running without a network [secondary].
- Xiaomi's "One-Step Access" app counts conflict (80 vs 100 apps), and one review found it only opens apps [secondary].

**Local inference removed or hidden** [primary]:
- OmniBot removed local MNN on 2026-07-16.
- ClosePaw hides its LFM2.5-1.2B client as "too slow".

**Vendor and framework samples are chat or in-app only:**
- ADK for Android 0.1: in-app tools only. Qualcomm AI Hub apps: chat only [primary].
- Arm learning paths: chatbots only. MediaTek: no public code [secondary].
- ClawMate is HarmonyOS only [primary].

**No code, or not a replay system** [primary unless tagged]:
- AgentRR: no standalone repo (the code is in MobiAgent's `agent_rr/`).
- AutoTask [secondary], Mobile-Agent-V and KITE [UNCERTAIN]: no public code found.
- PromptRPA: a dataset only.
- MobA: "still under development".
- DroidBot-GPT: no memory.
- LearnAct: offline evaluation code only.
- CopyPaste-Android: a coursework app replaying coordinates.

**Old or server-bound voice assistants** [primary]:
- Saiy (last push 2018); Mycroft-Android (2021, needs a server).
- OVOS: no Android app.
- SEPIA and Rhasspy Mobile: need a server.
- Leon: no Android app.
- Sapphire/Athena: pre-alpha.
- RunAnywhere SDK: "wake-word detection is not implemented".

**Laya on Android, but no UI control** [primary]:
- laya-decide, LayaEdge (a stub), laya-onnx-android, Laya-Android-SDK, yvyvyvbing/Laya-Android-Coin (a WASM toy).
- local-settings-slm: the app is only a plan.

**Too thin to assess** (under 3★ or no README) [primary]:
- heySaathi, LORA, RuneL89/Ghost, ChatLense, NudgeAI, aeva2, mobileKinetic-Agent, omnix-agent, EdgeAgent.
- 5omeOtherGuy/operator still says "planning" [UNCERTAIN].
- workbuddy-PokeClaw is a fork.

## Not checked
- **Nothing was installed or run.** No prototype was timed on our Pixel 8, and every small-repo claim is self-reported.
- **Whether each repo still builds** was judged from READMEs and issues only.
- **Coverage gaps:** Play Store, F-Droid and Reddit pages did not render. Chinese OEM facts rest mostly on Chinese news sites and vendor PR; none was tested independently. Gitee and other non-GitHub code hosts were not searched.
- **Read-only money assistants** for UPI apps were not searched specifically.
- **Andee** was pushed again on 2026-10-17 and was not re-read.
- **Operit's** local vision-model path is inferred from code, not docs.

## Sources

**On-phone agents:**
- https://github.com/agents-io/PokeClaw
- https://github.com/IPADS-SAI/MobiAgent (`phone_runner/README.md`, `agent_rr/`) · https://github.com/IPADS-SAI/ClawMate · https://github.com/doulujiyao12/mobiinfer · https://arxiv.org/abs/2509.00531 · https://arxiv.org/abs/2512.15784
- https://github.com/AAswordman/Operit
- https://github.com/bedda-tech/deft · https://github.com/Akeem1955/Genie · https://github.com/tuangel134/BlackClaw · https://github.com/ansaribilal14/jarvis · https://github.com/codai-ro/codai-phone · https://github.com/miqian-nomad/YinyueMobile · https://github.com/Dexter2344/phone-agent · https://github.com/ExTV/rikkahub-agent · https://github.com/vNeeL-code/GHOST · https://github.com/just-uniqq/P.A.N.D.A
- https://github.com/google-ai-edge/gallery · https://ai.google.dev/gemma/docs/mobile-actions · https://developers.googleblog.com/on-device-function-calling-in-google-ai-edge-gallery/ · https://blog.google/technology/developers/functiongemma/ · https://huggingface.co/datasets/google/mobile-actions
- https://github.com/lx200916/ChatBotApp · https://github.com/UbiquitousLearning/PhoneLM · https://github.com/cactus-compute/needle
- Dead ends: https://huggingface.co/NexaAI/Octopus-v2 · https://github.com/meta-llama/llama-stack-client-kotlin · https://github.com/OpenBMB/AgentCPM-GUI · https://arxiv.org/abs/2512.22047 · https://github.com/HKUDS/PhoneCLI · https://github.com/omnimind-ai/OmniBot · https://github.com/imoonkey/closepaw · https://github.com/ModalityDance/PalmClaw · https://github.com/kellyvv/PhoneClaw · https://developers.googleblog.com/adk-kotlin-android-building-ai-agents/ · https://github.com/qualcomm/ai-hub-apps

**Laya and decision models:**
- https://github.com/KYRIE66nb/mobile-agent
- https://github.com/woaiys3/laya-decide · https://huggingface.co/charioteer/laya-mobile · https://github.com/Algorythm-Canada/OpenJevSwift · https://github.com/alifthi/Laya-Android-SDK · https://github.com/rookery-labs/laya-onnx-android · https://github.com/Zhanfg/LayaEdge · https://github.com/AkhileshManda/local-settings-slm
- https://github.com/ArdaOzd/macos-use · https://github.com/aryanbhujade/laya-mlx-voice-browser (`docs/GOAL_LOOP.md`) · https://github.com/prajazz66/etri-voice-control · https://github.com/adityachauhan0/laya-computer-use · https://github.com/ChenneyZhuang/laya-computer-use · https://github.com/FluidInference/FluidUse
- https://github.com/dongxinsuperman/jev-phone · https://github.com/fiveapplesonthetable/phone-journeys · https://github.com/srsergi0/jev-adb
- https://huggingface.co/thealper2/smollm2-135m-android-control · https://huggingface.co/nba156/gemma-3n-E4B-it-Android-Control-84k · https://huggingface.co/OfficerChul/gemma-3n-E2B-it-Android-Control-84k
- https://github.com/NandhaKishorM/laya (Community Tools) · https://huggingface.co/api/models?search=laya

**Teach, replay and memory:**
- https://github.com/leestana01/autobile (`docs/ARCHITECTURE.md`)
- https://github.com/keymapperorg/KeyMapper (`AccessibilityNodeRecorder.kt`, `PerformActionsUseCase.kt`)
- https://github.com/tobyli/Sugilite_development · http://toby.li/files/TobyLi-CHI2017-Sugilite.pdf
- https://github.com/vuminhduc796/GPTVoiceTasker · https://arxiv.org/html/2401.14268v3
- https://github.com/fclab-skku/MobileGPT · https://arxiv.org/abs/2312.03003
- https://github.com/droidrun/mobilerun (`mobilerun/macro/`)
- https://arxiv.org/abs/2505.17716 (AgentRR)
- https://github.com/MobileLLM/AutoDroid · https://arxiv.org/html/2308.15272v4
- https://github.com/Westlake-AGI-Lab/AppAgentX · https://arxiv.org/html/2503.02268
- https://github.com/X-PLUG/MobileAgent · https://arxiv.org/html/2501.11733
- https://github.com/Nain57/Smart-AutoClicker
- https://llamalab.com/automate/doc/block/interact.html · https://rubydoc.info/gems/ruby-macrodroid/0.9.6/UIInteractionAction · https://joaoapps.com/?p=4162
- https://github.com/zorazrw/agent-workflow-memory · https://github.com/OSU-NLP-Group/SkillWeaver · https://arxiv.org/html/2510.14308v2
- https://rabbit.tech/support/article/rabbit-teach-mode
- https://github.com/lgy0404/LearnAct · https://github.com/OpenDFM/MobA · https://github.com/MobileLLM/AutoDroid-V2 · https://github.com/dkincorporated/CopyPaste-Android

**Voice:**
- https://github.com/DicioTeam/dicio-android (issues #132, #131, #320) · https://gitlab.e.foundation/e/os/open-source-llm-assistant
- https://github.com/Kakauet/Dina · https://github.com/yashab-cyber/opendroid · https://github.com/AnaaySampat/jarvis · https://github.com/n1dlee/friday-mobile · https://github.com/yuga-hashimoto/openclaw-assistant · https://github.com/souravanand001/ai-assistant-android · https://github.com/Ayush0Chaudhary/blurr (`docs/PORCUPINE_SETUP.md`)
- https://support.google.com/accessibility/android/answer/6151854 · https://support.google.com/accessibility/android/answer/6151848
- https://www.home-assistant.io/voice_control/android/ · https://companion.home-assistant.io/docs/notifications/notification-commands/
- https://picovoice.ai/docs/faq/general/ · https://community.home-assistant.io/t/fyi-picovoice-confirmed-free-tier-accesskeys-will-stop-working-after-june-30-2026/1012744
- Dead ends: https://github.com/brandall76/Saiy-PS · https://github.com/MycroftAI/Mycroft-Android · https://github.com/OpenVoiceOS/ovos-PHAL-plugin-termux · https://github.com/SEPIA-Framework/sepia-html-client-app · https://github.com/leon-ai/leon · https://github.com/Nailik/rhasspy_mobile · https://github.com/RunanywhereAI/runanywhere-sdks

**Commercial and OEM:**
- Google: https://support.google.com/pixelphone/answer/16940971 · https://support.google.com/gemini/answer/13594961 · https://developer.android.com/ai/computer-control · https://developers.google.com/ml-kit/genai · https://support.google.com/pixelphone/answer/16508057 · https://support.google.com/pixelphone/answer/17579905 · https://support.google.com/gemini/answer/15235441 · https://blog.google/products-and-platforms/platforms/android/accessibility-update-expanded-dark-theme-gemini-talkback/ · https://blog.google/innovation-and-ai/products/gemini-app/android-multi-step-tasks/ · https://techcrunch.com/2026/02/25/gemini-can-now-automate-some-multi-step-tasks-on-android · https://www.androidpolice.com/pixel-gemini-controls-apps/
- Samsung: https://www.samsungmobilepress.com/articles/bixby-one-ui-8-5-conversational-device-agent · https://www.androidauthority.com/galaxy-s26-hey-plex-3648380/
- Honor: https://www.honor.com/cn/news/honor-yoyo-certificate/ · https://www.honor.com/cn/news/magic-os-launch/ · https://github.com/MagicAgent-GUI/MagicGUI · https://www.fonearena.com/blog/466738/honor-magicos-10-features-devices.html
- Xiaomi: https://finance.sina.com.cn/tech/mobile/n/n/2026-08-19/doc-ininuvaw8423761.shtml · https://www.ithome.com/0/926/401.htm · https://eu.36kr.com/en/p/3443194625365637
- vivo: https://www.qbitai.com/2024/10/199324.html · https://www.ithome.com/1/002/936.htm · https://www.huxiu.com/article/4892711.html · https://news.mydrivers.com/1/1145/1145261.htm · https://arxiv.org/abs/2609.12394
- OPPO: https://i.ifeng.com/c/8wUcfK6tPT6 · https://80aj.com/2026/10/13/manus-oppo-ai-agent-coloros/ · https://arxiv.org/abs/2510.19386 · https://arxiv.org/abs/2510.11496
- Huawei: https://www.qbitai.com/2026/06/435953.html · https://gitcode.com/openharmony/docs/blob/master/zh-cn/application-dev/application-models/insight-intent-decorator-development.md
- Apple: https://developer.apple.com/videos/play/wwdc2026/343/
- Doubao, ZTE, StepFun: https://m.huxiu.com/article/4887888.html · https://finance.sina.com.cn/tech/digi/2025-12-03/doc-infzpsmw1188883.shtml · https://www.ithome.com/1/012/704.htm · https://news.pedaily.cn/202610/570372.shtml · https://gagadget.com/en/719069-nubia-navix-ultra-the-worlds-first-ai-agent-phone-with-some-big-caveats/ · https://www.qbitai.com/2025/11/348740.html · https://www.qbitai.com/2026/10/503201.html
- Rabbit: https://www.rabbit.tech/newsroom/teach-mode-beta · https://www.rabbit.tech/blog/first-look-at-rabbits-Android-agent · https://www.prnewswire.com/news-releases/rabbit-unveils-os3-the-agentic-operating-system-for-every-device-302886403.html
- Motorola and Lenovo: https://news.lenovo.com/pressroom/press-releases/lenovo-unveils-lenovo-and-motorola-qira/ · https://memeburn.com/android-17-is-coming-to-50-motorola-phones-qira-is-not-coming-to-all-of-them/
- Perplexity: https://techcrunch.com/2025/01/23/perplexity-launches-an-assistant-for-android
- Chips: https://www.mediatek.com/tek-talk-blogs/from-smart-into-intelligent.-making-ai-agents-work-for-you · https://eu.36kr.com/en/p/3998754973749376
