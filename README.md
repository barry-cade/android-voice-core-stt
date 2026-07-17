# android-voice-core-stt

Offline Speech‑to‑Text (STT) for Android using Whisper, deterministic VAD, and configurable PCM capture.

## Overview

android-voice-core-stt is a modular offline speech‑to‑text engine for Android. It provides:

- Whisper tiny_en transcription (offline)
- Deterministic voice activity detection (VAD)
- Configurable command windows
- Pre‑roll and silence padding
- A reusable STT module (`:stt`)
- A demo Android app showing real‑time logs and usage

The project is designed for robotics, embedded agents, and voice‑controlled systems where deterministic behaviour, low latency, and offline operation are required.

## Repository Structure

``` text
android-voice-core-stt/
├── stt/                          # Reusable STT library module
│   ├── src/main/java/.../stt/    # PCM capture, VAD, Whisper JNI, public API
│   ├── src/main/cpp/             # Whisper C++ bindings (JNI)
│   ├── ARCHITECTURE.md           # Internal architecture documentation
│   ├── README.md                 # Module-level documentation
│   └── build.gradle.kts
├── app/                          # Demo Android application
│   ├── src/main/java/.../        # UI + integration with :stt module
│   ├── src/main/res/             # Layouts and UI resources
│   └── build.gradle.kts
├── README.md                     # This file
└── settings.gradle.kts           # Includes :app and :stt
```

## Features

### Offline Whisper STT

Uses Whisper tiny_en for fast, offline transcription on mobile CPUs.

### Deterministic VAD

Energy‑based voice activity detection with configurable parameters:

- `energyThreshold`
- `silencePaddingMs`
- `preRollMs`
- `stableChunkSizeMs`
- `maxUtteranceLengthMs`
- Motion‑mode overrides

### Command Window Architecture

Supports deterministic command windows:

- Fixed duration (e.g., 2–4 seconds)
- VAD early‑close
- Pre‑roll audio capture
- Silence padding
- Max utterance length enforcement

### Clean Public API

The `:stt` module exposes a strictly enforced public API surface:

- `SpeechToText` — the single public entry point

All configuration and results flow through a **pure JSON boundary**:
- `loadModel(context, configJson)` — loads model, configures pipeline, runs warm-up. Does NOT start capture. Safe at app startup.
- `startSession()` — begins audio capture and transcription. Must be called after `loadModel`.
- `transcribe()` — stops current utterance, runs inference, delivers result as JSON via listener.
- `init(context, configJson)` — convenience: `loadModel` + `startSession` in one call.
- `setOnMessageListener(l: (String) -> Unit)` — receives JSON result/error messages

Everything else in the module is `internal`. The build includes an automated
API surface check (`checkSttApiSurface`) that enforces this at compile time.

### Demo App Included

Shows:

- Live transcription output
- Timing diagnostics (PCM, VAD, Whisper inference)
- Structured error display via error banner
- Config display (loaded from config assets)
- Radio button strategy selector (Manual/Manual vs Manual/Auto)
- Model preload at startup with background copying from assets

## Architecture

### PCM Capture

A dedicated audio thread captures 16 kHz mono PCM:

- Configurable buffer size
- 16 kHz sample rate
- `FloatArray` frames published to a concurrent queue
- Continuous stream during active recording

### VAD

Each frame is analysed:

- RMS energy
- Speech/silence classification
- Silence frame counting
- Early‑close logic
- Pre‑roll and silence padding

### Whisper Backend

Whisper tiny_en is loaded via JNI:

- Model load/unload
- Full inference (`whisper_full`)
- Segment extraction
- Text output

### STT Flow

``` text
SpeechToText.setOnMessageListener(...)

Phase 1 (app startup):
  → loadModel(context, configJson)
      → parse config JSON
      → load Whisper model (synchronous)
      → run warm‑up inference (optional)
      → build pipeline scaffolding
      → state: READY

Phase 2 (user action):
  → startSession()
      → increment session epoch
      → start AudioCapture (T1)
      → start processor (T3: VAD + accumulator)
      → state: RECORDING / pipeline: CAPTURING

Transcribe (user action or auto-silence):
  → transcribe()
      → stop capture and drain PCM
      → submit inference on Whisper executor (T4)
      → decideDispatch() → epoch check + pipeline stage check
      → build result JSON → dispatch via listener
      → state: READY
```

The lifecycle follows:
`UNINITIALISED → INITIALISED → READY → RECORDING → FINALISING → STOPPED → READY → ...`

## Quick Start

### Clone

```bash
git clone --recurse-submodules https://github.com/barry-cade/android-voice-core-stt.git
```

If already cloned, initialize the whisper.cpp submodule:

```bash
git submodule update --init --recursive
```

### Build

Open the project in Android Studio and build normally.

### Run Demo App

Install the demo app on an Android device:

1. Grant microphone permission
2. Model loads automatically at app startup (background copy from assets)
3. Select strategy: **Manual/Manual** or **Manual/Auto** via radio buttons
4. Tap **Start** to begin recording
5. Tap **Stop** to transcribe (Manual mode) or wait for auto-silence (Auto mode)
6. View logs in Logcat under `APP` and `STT_*` tags

## Integration Guide

To use the STT module in your own Android app:

1. Copy the `stt` folder into your project.
2. Add `include(":stt")` to `settings.gradle.kts`.
3. Add `implementation(project(":stt"))` in your app's `build.gradle.kts`.
4. Register the message listener **before** calling `loadModel` (it is buffered):

```kotlin
SpeechToText.setOnMessageListener { json ->
    // json is a JSON string — inspect "type" field
    // "result": successful transcription
    // "error": an error occurred
    runOnUiThread {
        // update UI
    }
}
```

5. Call `loadModel` at app startup (e.g. Application.onCreate or Activity.onCreate):

```kotlin
val configJson = buildConfigJson(modelPath, ...)
val error = SpeechToText.loadModel(context, configJson)
// error is null on success, non-null on failure
```

6. Call `startSession` on the Start button:

```kotlin
val error = SpeechToText.startSession()
if (error == null) {
    // session active, PCM capture running
}
```

7. Call `transcribe` on the Stop button:

```kotlin
SpeechToText.transcribe()
// Result arrives asynchronously via the message listener
```

Full API documentation is available in [`stt/README.md`](stt/README.md).

## Output JSON

Results and errors are delivered as JSON strings via `setOnMessageListener`.
See [`stt/README.md`](stt/README.md#output-result) for the output format.

## License

MIT (or whichever license you choose)
