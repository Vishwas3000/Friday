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

The Gemini → MakeMyTrip workflow is an experimental Nepal travel flow with saved research context and explicit hotel inputs. Complete hotel results require verification of destination, dates and guests. General travel planning, arbitrary app workflows and reliable completion across all apps remain development work.

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
| [Decisions](docs/DECISIONS.md) | Design choices and their rationale |

Device captures, private task data and downloaded models belong in ignored local directories. They are not needed in the public repository.

## Models and attribution

Friday's action model is a trained student derived from [Laya-Android](https://huggingface.co/ByunByun/laya-android), built on ConvAI's Laya decision model. [Student training and evaluation](research/28-laya-student.md) document its provenance and measured limits.

The text runtime is [llama.cpp](https://github.com/ggml-org/llama.cpp), with LFM2.5-1.2B as the default Writer and an optional Qwen comparison. Their model licenses and setup are described in the [Writer guide](docs/writer.md); the runtime notice is included with the app.
