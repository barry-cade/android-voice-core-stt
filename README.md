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

- `SpeechToText` — main entry point with factory `create()`, `start()`, `stopAndTranscribe()`, `destroy()`
- `SttConfig` — static configuration data class
- `AudioCapture` — microphone capture
- `WhisperBridge` — JNI bindings
- Structured error types (`SttError`, `SttErrorCode`, `SttErrorCategory`, `SttErrorListener`)
- `SttReadyListener` — model readiness callback
- `SttTimingSnapshot` — timing diagnostics

All other classes are internal.

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
SpeechToText.create(config)
  → setOnResultListener(...)
  → start()
      → model warms up (async)
      → audio capture thread starts
      → VAD monitors speech
  → stopAndTranscribe()
      → drains remaining audio
      → Whisper inference
      → transcription result delivered to listener
  → destroy()
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

> **Deprecation notice:** The legacy [SttConfig] API is now deprecated. It remains
> fully supported but will be removed in a future major version. New integrations
> should use [SttRunConfig] with [SpeechToText.setConfig] and [SpeechToText.startSession].

To use the STT module in your own Android app:

1. Copy the `stt` folder into your project.
2. Add `include(":stt")` to `settings.gradle.kts`.
3. Add `implementation(project(":stt"))` in your app's `build.gradle.kts`.
4. Instantiate and use:

```kotlin
val config = SttConfig(
    energyThreshold = 0.03f,
    silencePaddingMs = 300,
    preRollMs = 100,
    maxUtteranceLengthMs = 4000,
    stableChunkSizeMs = 500,
    motionModeEnergyThreshold = 0.05f,
    motionModeSilencePaddingMs = 150,
    modelPath = "/path/to/ggml-tiny.en.bin"
)
val stt = SpeechToText.create(config)

stt.setOnResultListener { text ->
    // handle transcription
}

stt.start()
// ...
stt.stopAndTranscribe()
stt.destroy()
```

Full API documentation is available in [`stt/README.md`](stt/README.md).

> **Note:** The [SttConfig] type and [SpeechToText.create] shown above are the
> legacy path. They remain fully functional but are deprecated. New code should
> use [SttRunConfig] with [SpeechToText.setConfig] and [SpeechToText.startSession]
> (see next section).

## New SttRunConfig API (Phase 1+)

A second, complementary API path was introduced alongside the existing [SttConfig]/[SpeechToText.create] path. This new API uses [SttRunConfig] for configuration and [SpeechToText.setConfig]/[SpeechToText.startSession] for lifecycle control. Both API paths coexist — existing code using [SttConfig] continues to work unchanged.

### Overview

The new API introduces the following types:

- **SttRunConfig** — single configuration object wrapping engine config, lifecycle strategy, and strategy-specific parameters.
- **TtsEngineConfig** — engine-level configuration (model path, language, timing).
- **SttLifeCycleStrategy** — enum determining how recording starts and stops (MANUAL_MANUAL or MANUAL_AUTO).
- **ManualManualSpecific** — strategy-specific parameters for MANUAL_MANUAL mode.
- **ManualAutoSpecific** — strategy-specific parameters for MANUAL_AUTO mode.
- **SessionResult** — pure data class containing a return code and optional transcript.
- **SttReturnCode** — expanded enum with new codes (SUCCESS, CONFIG_NOT_SET, INVALID_CONFIG, MAX_DURATION_REACHED, AUTO_SILENCE_TRIGGERED, ABNORMAL_SILENCE, ENGINE_ERROR).

### Example JSON Config

The existing `stt_config.json` is used for both API paths. Example:

```json
{
  "energyThreshold": 0.03,
  "preRollMs": 100,
  "stableChunkSizeMs": 500,
  "startStrategy": "manual",
  "stopStrategy": "manual",
  "manualManual": {
    "maxDurationMs": 30000,
    "abnormalSilenceMs": 5000
  },
  "manualAuto": {
    "maxDurationMs": 30000,
    "autoSilenceMs": 1200
  }
}
```

### Example Kotlin Usage

```kotlin
val runConfig = SttRunConfig(
    ttsEngineConfig = TtsEngineConfig(
        modelPath = "/path/to/ggml-tiny.en.bin",
        language = "en",
        preRollMs = 100,
        stableChunkSizeMs = 500,
        debugLoggingEnabled = false
    ),
    ttsLifeCycleStrategy = SttLifeCycleStrategy.MANUAL_MANUAL,
    strategySpecific = ManualManualSpecific(
        energyThreshold = 0.03f,
        maxDurationMs = 30000,
        abnormalSilenceMs = 5000
    )
)

val stt = SpeechToText.create(
    SttConfig(modelPath = "/path/to/ggml-tiny.en.bin")
)

// Set the new config (validates internally)
val setResult = stt.setConfig(runConfig)
if (setResult.code != SttReturnCode.SUCCESS) {
    // handle config rejection
    return
}

// Register result listener
stt.setOnResultListener { text ->
    // handle transcription
}

// Start session
val sessionResult = stt.startSession()
if (sessionResult.code != SttReturnCode.SUCCESS) {
    // handle session error
}
```

### Lifecycle Strategies

| Strategy | Start Trigger | Stop Trigger | Description |
|----------|---------------|--------------|-------------|
| MANUAL_MANUAL | ManualStartTrigger | ManualStopTrigger | Caller controls start and stop explicitly. Silence longer than [abnormalSilenceMs] triggers abnormal termination. |
| MANUAL_AUTO | ManualStartTrigger | AutoSilenceStopTrigger | Caller controls start. Recording stops automatically when silence exceeds [autoSilenceMs]. |

### Strategy-Specific Types

The `strategySpecific` field on `SttRunConfig` is typed via an enforced contract:

- **ManualManualSpecific**: `energyThreshold`, `maxDurationMs`, `abnormalSilenceMs` — all must be > 0.
- **ManualAutoSpecific**: `energyThreshold`, `maxDurationMs`, `autoSilenceMs` — all must be > 0.

Passing the wrong type for the selected [SttLifeCycleStrategy] causes `setConfig()` to return `INVALID_CONFIG`.

### Return Codes

| Code | Meaning |
|------|---------|
| SUCCESS | Session started or completed successfully. |
| CONFIG_NOT_SET | `setConfig()` was not called before `startSession()`. |
| INVALID_CONFIG | Config failed validation (see SttRunConfigValidator). |
| MAX_DURATION_REACHED | Utterance exceeded max duration. |
| AUTO_SILENCE_TRIGGERED | Auto-silence threshold reached (MANUAL_AUTO only). |
| ABNORMAL_SILENCE | Abnormal silence detected (MANUAL_MANUAL only). |
| ENGINE_ERROR | Internal pipeline error. |

### Validation Behaviour

`SttRunConfigValidator` enforces all field constraints deterministically:

- **Type contract**: `strategySpecific` must match the type required by `ttsLifeCycleStrategy`.
- **Numeric constraints**: `energyThreshold > 0`, `maxDurationMs > 0`, `abnormalSilenceMs > 0`, `autoSilenceMs > 0`, `preRollMs >= 0`, `stableChunkSizeMs >= 0`.
- **String constraints**: `modelPath` and `language` must be non-blank.
- **No inference**: Any missing or invalid field causes immediate rejection. No defaults are applied.

### Coexistence with the Old API

- The existing [SttConfig] + [SpeechToText.create] + [SpeechToText.start()] path is **untouched**.
- The new [SttRunConfig] + [SpeechToText.setConfig()] + [SpeechToText.startSession()] path is **additive**.
- Both paths can be used side by side (e.g., for A/B testing during migration).
- Internal pipeline, triggers, VAD, and Whisper backend are shared by both paths.

## Migration Status (Phase 7)

- **New API (SttRunConfig)** is stable and fully supported.
- **Legacy API (SttConfig)** is deprecated but fully functional.
- **Removal** of legacy API is planned for a future major version.
- Both APIs coexist without conflict.

## Roadmap

- Add wake‑word integration examples
- Add Vosk grammar example for command recognition
- Add dual‑pipeline demo (Rex vs Zip)
- Add unit tests for VAD
- Add benchmark mode for Whisper inference

## License

MIT (or whichever license you choose)
