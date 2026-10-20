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

The Gemini → MakeMyTrip workflow is a bounded Nepal travel prototype with saved research context, full-response capture and explicit hotel inputs. The latest build's response capture and hotel date/result-header guards are under live verification; the Nepal workflow has not yet passed an end-to-end test. Complete hotel results require matching destination, dates and guests.

A local Room coordinator now runs durable Research and registered device capabilities through one execution loop. Supported entries include native calendar creation, visible bill reading, app opening/search and standalone text drafting. Plans retain typed inputs, dependencies, attempts and source-bound evidence; uncertain writes are inspected before retry. Request interpretation is still deterministic, and general multi-app planning remains development work. The installed Pixel build passes **35/35 synthetic coordinator checks** and the **19-group component selfcheck**. See the [capability checkpoint](research/41-capability-checkpoint.md).

Research live checks cover whole-response Copy, fresh-session capture, label isolation, short answers, Stop, duplicate-free rechecks and clipboard freshness. Restart Resume succeeded **after an initial failure, operator repair and installed keyboard fix**. **M1 has not passed:** automatic conversation location, clean restart acceptance and content constraints remain open. The latest Writer suite was blocked from generation by memory availability; Gmail field population was visually verified, but automatic readback remains unavailable. See the [live report](research/37-pixel-research-live-acceptance.md) and [implementation plan](docs/COORDINATOR_PLAN.md).

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
| [Navigation and training plan](research/38-navigation-architecture-and-harness-plan.md) | Harness, teacher-model and dataset decisions, with staged experiments |
| [Live task diagnostics](research/36-nepal-live-flow-and-timing.md) | Actual Nepal and Andaman failures, stage timings and remaining verification |
| [Decisions](docs/DECISIONS.md) | Design choices and their rationale |

Device captures, private task data and downloaded models belong in ignored local directories. They are not needed in the public repository.

## Models and attribution

Friday's current action-model path uses a quantized checkpoint derived from [Laya-Android](https://huggingface.co/ByunByun/laya-android), built on ConvAI's Laya decision model. The repository also contains [student distillation, training and evaluation](research/28-laya-student.md). D-062 in the [decision log](docs/DECISIONS.md#d-062--the-half-depth-student-s11-and-how-it-was-made-research28) records S11-A as a candidate whose deployment awaits phone validation; it is not established as the shipped model.

The text runtime is [llama.cpp](https://github.com/ggml-org/llama.cpp), with LFM2.5-1.2B as the default Writer and an optional Qwen comparison. Their model licenses and setup are described in the [Writer guide](docs/writer.md); the runtime notice is included with the app.
