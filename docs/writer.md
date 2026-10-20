# Local text generation

Friday's Writer generates short English drafts on the phone. Laya continues to choose UI actions. No cloud service is called by the Writer.

## Try it in the app

Open Friday, type a request, and tap **Run on this phone**:

```text
Write a polite message saying I will be 15 minutes late.
Write a polite WhatsApp message to Rahul saying I will be 15 minutes late.
Message Rahul on WhatsApp saying I cannot attend today.
Draft an email to alex@example.test in Gmail saying I received the plan.
Rewrite this politely: I cannot attend the call today.
Draft an email saying I received the plan and will send comments tomorrow.
Write a short title for a trip to Bengaluru.
Summarize this: The meeting moved to Friday. The venue is undecided.
```

The result appears as a draft. When you name an app or recipient, Friday asks you to review the draft before putting it there. Tap **Run** to continue or **Skip** to keep it in Friday. With no destination, the draft stays in the answer card.

Writing and messaging requests use the same generation and handoff path. Asking to write or polish quoted source still uses the SLM. An explicit **exactly** or **verbatim** instruction preserves your text; a direct message command with a quoted body also preserves it.

The accepted draft is carried unchanged into the destination. Email uses Android's composer intent, phone-number chat requests use supported composer links, and named WhatsApp/SMS contacts use bounded recipient navigation. Other apps can receive the text through Android sharing. If the app requires a recipient picker, Friday reports that selection is still pending. It only reports populated fields after checking the app's visible text. You review the final composer and tap **Send** yourself.

Requests beginning with write/draft/compose/create/generate/suggest and naming a message, reply, email, title, query or summary use this path. Rewrite/rephrase/polish/summarize/summarise are also recognized. A leading “please” is accepted. Provide the actual text after a colon for rewrites and summaries; the Writer has no implicit access to an open conversation.

## Setup and build

Optional local settings belong in the ignored `.env` file: copy it with `cp .env.example .env`, then run commands such as `uv run --env-file .env python scripts/setup_writer.py --install` or `uv run --env-file .env python scripts/writer_test.py`. `FRIDAY_ADB` selects the adb executable (default `adb`); `FRIDAY_DEVICE_SERIAL` selects a device (empty lets adb choose the single connected device). The test harness reads `FRIDAY_BRIDGE_HOST` and `FRIDAY_BRIDGE_PORT` (defaults `127.0.0.1` and `8765`) after you forward the bridge. The corresponding `--adb`, `--serial`, `--host` and `--port` flags override environment settings. Scripts also accept an already-exported shell environment; they do not load `.env` automatically or require a dotenv package. Keep real device identifiers and any future secrets out of `.env.example`.

Use Python 3, the Android SDK/NDK already required by this repo, and the existing Gradle wrapper:

```sh
python3 scripts/setup_writer.py --runtime-only
cd android
./gradlew :app:assembleDebug
```

Install the debug APK using the normal phone setup, then install the weights:

```sh
python3 scripts/setup_writer.py --install --serial <device-serial>
```

The setup script pins llama.cpp b11701 (`01fc3aae846caa644f0cee360f108d4c48e4aaa1`) and verifies the source archive and model SHA-256 hashes. Source is extracted into ignored `android/third_party/llama.cpp/`; models stay in ignored `models/writer/`. Neither large directory is committed. The default weights are LFM2.5-1.2B-Instruct QAD-Q4_0, 695,755,488 bytes. The model is copied atomically into Friday's private `files/writer.gguf`, with an on-device checksum check.

The Android native build targets the existing Pixel 8 arm64 device scope: CPU, four inference threads, 1,024 context tokens, optimized kernels, no GPU backend. The APK includes llama.cpp's MIT notice. The separately downloaded LFM weights use their [custom model license](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct/blob/main/LICENSE).

The optional Qwen3.5-0.8B Q8_0 comparison is provisioned with:

```sh
python3 scripts/setup_writer.py --qwen --install --serial <device-serial>
```

## Execution and limits

1. `Assistant` recognizes an explicit writing request, including “open APP and write…”, before its ordinary Laya routing. The message/email skill invokes the same Writer for natural language instructions.
2. `AgentService` obtains exclusive model ownership and releases any shared Laya engine.
3. The Writer validates the request and checks available memory before allocating a model.
4. llama.cpp loads the model and generates constrained JSON. Request time and output tokens are bounded.
5. Kotlin parses the object and checks supported fields, basic factual constraints and output limits.
6. Only accepted complete output becomes a draft. The model is freed before another request can run.
7. A requested app/recipient is read from the original command header, separately from the generated prose. An owner review precedes handoff. The same draft is passed to an intent or typed only after a contact is checked; app launch alone never counts as a verified composer.

UI Stop cancels loading or generation through a native cancellation flag. Timeouts, truncation, invalid input, unavailable memory and missing weights are separate outcomes. They do not expose a partial draft as accepted text.

Every request creates and frees a model context. Repeated calls can benefit from the operating system's file cache; they are not resident-model warm runs. Timing fields distinguish model loading, prefill, decode and total request time.

Validation is limited: valid JSON and preserved numbers do not prove factual or semantic correctness. Names, relationships, dates and the intended meaning still need review. Test reports keep mechanical checks separate from semantic review.

## Test

```sh
adb -s <device-serial> forward tcp:8765 localabstract:layatest_agent
python3 scripts/writer_test.py --self-check
python3 scripts/writer_test.py
python3 scripts/writer_test.py --model writer-qwen.gguf
```

Reports, actual synthetic outputs and timings go under ignored `captures/writer-tests/`. Twenty fixtures cover generation, factual preservation, word limits, malicious source text, missing values, model-path rejection, truncation and timeouts. Passing a rejection case does not count as good generation. Manual Stop and model-lifecycle procedures are included with the fixtures.

Handoff verification also covers both writing and message verbs, an “open APP and…” prefix, tone adjectives, quoted source versus verbatim bodies, app names inside source text, unknown apps, exact intent payloads and recipient readback. Device checks use synthetic drafts and leave Send to the owner.

The diagnostic bridge accepts:

```json
{"cmd":"writer","kind":"message","instruction":"Write a polite short message.","text":"I will be 15 minutes late.","max_tokens":128,"timeout_ms":20000}
```

Supported kinds are `message`, `rewrite`, `email`, `title`, `query` and `summary`. The response includes a status, model identity, timings and token counts. Only an `ok` response contains `text`, or `subject` and `body` for email. The diagnostic model filename is allowlisted; arbitrary file paths are rejected.

Related design and evaluation notes: [research/13](../research/13-text-llm.md), [research/31](../research/31-slm-text-generation.md), [text fixtures](../tests/writer-cases.json).


## Internal intent resolution

The coordinator can call Writer with `kind=intent`, one attempt, at most 128 output tokens and a 30-second deadline. Its constrained JSON contains `mode`, `action`, `target`, `scope` and `source_id`; source identifiers are restricted to the supplied records and `request`. It reuses the installed local runtime and weights without a new production dependency.

`RequestResolver` binds literal spans and hashes to the current request or selected-chat source excerpts. The resulting object is persisted before navigation and cannot choose an app package or recipient. Sections are kept separate from text entry; unresolved or unsupported interpretations wait for input.

This is experimental. Real probes have returned plausible JSON with the wrong item or scope, and Qwen comparisons have timed out. Structural validation is distinct from semantic correctness; the final readable-source prompt and scoped-media flow need Pixel acceptance. See [current coverage](FEATURE_TEST_COVERAGE.md#interpretation-and-shared-execution-checkpoint).
