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
│   ├── README.md                 # Full API documentation
│   └── build.gradle.kts
├── app/                          # Demo Android application
│   ├── src/main/java/.../        # UI + integration with :stt module
│   ├── src/main/res/             # Layouts and UI resources
│   ├── README.md                 # Demo usage instructions
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
- `init(configJson: String)` — accepts JSON config, returns JSON result/error
- `transcribe()` — stops capture, runs inference, delivers result as JSON
- `setOnMessageListener(l: (String) -> Unit)` — receives JSON result/error messages

Everything else in the module is `internal`. The build includes an automated
API surface check (`checkSttApiSurface`) that enforces this at compile time.

### Demo App Included

Shows:

- Live transcription output
- Timing diagnostics (PCM, VAD, Whisper inference)
- Structured error display
- Config display (loaded from `stt_config.json`)

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
SpeechToText(context)
  → setOnMessageListener(...)
  → init(configJson)
      → model loads (async)
      → audio capture thread starts
      → VAD monitors speech
  → transcribe()
      → drains remaining audio
      → Whisper inference
      → JSON result delivered via listener
```

The lifecycle follows: `UNINITIALISED → READY → RECORDING → FINALISING → READY → ...`

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
2. Tap **Start** to begin recording
3. Tap **Stop** to transcribe
4. View logs in Logcat under `STT_*` tags

## Integration Guide

To use the STT module in your own Android app:

1. Copy the `stt` folder into your project.
2. Add `include(":stt")` to `settings.gradle.kts`.
3. Add `implementation(project(":stt"))` in your app's `build.gradle.kts`.
4. Instantiate and use:

```kotlin
val stt = SpeechToText(applicationContext)

stt.setOnMessageListener { json ->
    // json is a JSON string — inspect "type" field
    // "result": successful transcription
    // "error": an error occurred
    runOnUiThread {
        // update UI
    }
}

val configJson = buildConfigJson(modelPath, "en", "MANUAL")
val initResult = stt.init(configJson)
// initResult is a JSON string — check for errors

// To stop and transcribe:
stt.transcribe()
```

Full API documentation is available in [`stt/README.md`](stt/README.md).

## JSON Config

The `init()` method accepts a JSON string. See [`stt/README.md`](stt/README.md#json-schemas) for the full schema.

## Return Codes

Results and errors are delivered as JSON strings via `setOnMessageListener`.
See [`stt/README.md`](stt/README.md#output-result) for the output format.

## Roadmap

- Add wake‑word integration examples
- Add Vosk grammar example for command recognition
- Add dual‑pipeline demo (Rex vs Zip)
- Add unit tests for VAD
- Add benchmark mode for Whisper inference

## License

MIT (or whichever license you choose)
