# Friday

**An AI assistant for Android that can read the screen and operate apps.**

Friday opens apps, taps, scrolls and types to carry out requests. Its action and short text models run on the device. Tasks can also use another app's services, such as Gemini research, when requested.

## Implemented

- **Chat and voice:** a conversation screen with local history, a floating voice bubble and an optional “Hey Friday” wake word.
- **Device and app actions:** Android intents and accessibility controls for navigation, search, media, settings, alarms and other tasks.
- **Writing:** short English messages, emails, rewrites, titles, queries and summaries. A reviewed draft can be handed to a requested app; sending remains a separate step.
- **Message reading:** bounded reading and summarization of accessible message text or inbox previews, with the source and coverage reported.
- **Learning:** Teach demonstrations, remembered routes, feedback and training-data tooling.
- **Task controls:** progress, Stop, action checks and approval for sensitive steps. Authentication stays with the user.

## Experimental and planned

App layouts and accessibility support vary. Longer tasks can still fail or require clarification; opening an app does not establish task completion.

All new requests now use the local Room coordinator: Research, message reading → summary, drafting → unsent app composer, explicit hotel search, app navigation and registered device actions. Typed steps retain inputs, dependencies, attempts and source evidence. Existing version-one Research tasks retain their original Resume path; the old country-specific hotel loop is read-only compatibility.

App names and explicit search text are bound before execution. An experimental local SLM resolver separates action, item and app section, and can cite recent verified results from the selected chat. Model output cannot choose an app package or recipient. **General interpretation remains incomplete:** real model probes still made semantic mistakes; source-span validation alone does not prove the right item was selected.

The migration built successfully. An intermediate Pixel build passed **42/42 synthetic coordinator checks**, four Research selector groups and the 19-group component check. The expanded 45-case suite and final Pixel installation are pending after the device disconnected. Live hotel completion, broad media playback, inaccessible app readback and clean automatic Research restart remain unpassed. See [current coverage and migration limits](docs/FEATURE_TEST_COVERAGE.md), the [earlier live report](research/37-pixel-research-live-acceptance.md), and the [coordinator plan](docs/COORDINATOR_PLAN.md).

## Getting started

The current Android build targets arm64 Pixel 8 hardware and Android 16. Other devices are not yet validated. Development requires Python 3.12+, `uv`, JDK 17, Android SDK 36 and NDK 28.2.

Prepare the native text runtime and build:

```sh
uv sync
python3 scripts/setup_writer.py --runtime-only
cd android
./gradlew :app:assembleDebug
```

The action model, tokenizer and text weights are installed separately. See the [Android guide](docs/app.md) and [Writer setup](docs/writer.md) for device provisioning. Enable Friday's accessibility service, open Friday and enter a request.

## Documentation

| Guide | Contents |
|---|---|
| [System](docs/SYSTEM.md) | Architecture and components |
| [Android app](docs/app.md) | Device setup and debugging bridge |
| [Writer](docs/writer.md) | Text models, draft handoff and checks |
| [Research index](research/README.md) | Experiments, measurements and limitations |
| [Task completion](research/29-task-completion-speed-and-accuracy.md) | Speed and accuracy backlog |
| [Cross-app workflows](research/34-cross-app-reading-and-research.md) | Message reading and research context |
| [Task coordinator research](research/35-task-coordinator-research.md) | Proposed redesign, model limits and acceptance cases |
| [Coordinator implementation plan](docs/COORDINATOR_PLAN.md) | Persistent task contracts, migration milestones, progress and acceptance gates |
| [Evaluation harness](docs/NAVIGATION_HARNESS.md) | Independent outcome checks, replayable records, timing and explicit coverage limits |
| [Feature test coverage](docs/FEATURE_TEST_COVERAGE.md) | Current parallel regression results, failed checks and feature acceptance gaps |
| [Navigation and training plan](research/38-navigation-architecture-and-harness-plan.md) | Harness, teacher-model and dataset decisions, with staged experiments |
| [Live task diagnostics](research/36-nepal-live-flow-and-timing.md) | Actual Nepal and Andaman failures, stage timings and remaining verification |
| [Decisions](docs/DECISIONS.md) | Design choices and their rationale |

Device captures, private task data and downloaded models belong in ignored local directories. They are not needed in the public repository.

## Models and attribution

Friday's current action-model path uses a quantized checkpoint derived from [Laya-Android](https://huggingface.co/ByunByun/laya-android), built on ConvAI's Laya decision model. The repository also contains [student distillation, training and evaluation](research/28-laya-student.md). D-062 in the [decision log](docs/DECISIONS.md#d-062--the-half-depth-student-s11-and-how-it-was-made-research28) records S11-A as a candidate whose deployment awaits phone validation; it is not established as the shipped model.

The text runtime is [llama.cpp](https://github.com/ggml-org/llama.cpp), with LFM2.5-1.2B as the default Writer and an optional Qwen comparison. Their model licenses and setup are described in the [Writer guide](docs/writer.md); the runtime notice is included with the app.
