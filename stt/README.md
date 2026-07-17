# stt — Android Speech‑to‑Text Module

The **stt** module provides offline speech‑to‑text functionality for Android using:

- Whisper tiny_en (JNI + C++)
- Deterministic voice activity detection (VAD)
- Configurable PCM capture pipeline
- Command‑window behaviour (pre‑roll, silence padding, early close)
- A pure JSON boundary between the app and the STT engine

This module is designed to be reusable and independent of the demo app.

## Features

### Offline Whisper STT

- Uses Whisper tiny_en model
- Runs fully offline
- JNI bindings to C++ Whisper implementation
- Returns full transcription text

### Deterministic VAD

Energy‑based voice activity detection with configurable parameters:

- `energyThreshold`
- `preRollMs`
- `stableChunkSizeMs`
- Silence‑based auto‑stop

### PCM Capture Pipeline

- 16 kHz mono PCM
- Dedicated audio capture thread
- Fixed frame size
- Pre‑roll buffer
- Silence padding / early close

### Pure JSON Boundary

All inter-module communication uses JSON strings:

- `loadModel(context, configJson): SttError?` — accepts JSON config, returns null on success or SttError? on failure. Does NOT start capture.
- `startSession(): SttError?` — begins audio capture and transcription session. Must follow `loadModel`.
- `init(context, configJson): SttError?` — convenience: `loadModel` + `startSession` in one call.
- `transcribe()` — stops current utterance, runs inference, delivers JSON via listener
- `setOnMessageListener(l: (String) -> Unit)` — receives JSON result/error messages

## Public API

The public API surface is strictly enforced by `checkSttApiSurface` in `build.gradle.kts`.
Only the following top-level type is exposed:

- `SpeechToText` — main entry point

All other types are `internal`.

### Constructor

```kotlin
class SpeechToText internal constructor(...)
```

The constructor is `internal`. Create instances using the public constructor
(which accepts optional test doubles via named parameters):

```kotlin
val stt = SpeechToText(context = applicationContext)
```

### Methods

| Method | Description |
|--------|-------------|
| `loadModel(context, configJson): SttError?` | Load model from JSON config, configure pipeline, run warm-up. Returns `null` on success, or an `SttError` on failure. Does NOT start capture. Safe at app startup. |
| `startSession(): SttError?` | Begin audio capture and transcription session. Must be called after `loadModel`. |
| `init(context, configJson): SttError?` | Convenience: `loadModel` + `startSession` in one call. |
| `transcribe()` | Stop current utterance, run inference, deliver result via `setOnMessageListener`. |
| `setOnMessageListener(l: (String) -> Unit)` | Unified message listener. Receives JSON result or error strings. |

## JSON Schemas

### Input config (to `init()`)

```json
{
  "modelPath": "/path/to/ggml-tiny.en.bin",
  "language": "en",
  "debugLoggingEnabled": true,
  "energyThreshold": 0.03,
  "preRollMs": 100,
  "stableChunkSizeMs": 500,
  "drainMode": "DRAIN_FROM_NEXT_FRAME",
  "startType": "MANUAL",
  "stopType": "MANUAL",
  "silenceMs": 1200,
  "maxDurationMs": 30000,
  "warmupEnabled": true,
  "warmupDurationMs": 3000,
  "sessionTimeoutMs": 0,
  "bufferSizeSamples": 4000
}
```

#### Input fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `modelPath` | `string` | Yes | Absolute path to the Whisper model binary |
| `language` | `string` | No (default: `"en"`) | Language code |
| `debugLoggingEnabled` | `boolean` | No (default: `false`) | Enable detailed debug logging |
| `energyThreshold` | `number` | Yes | RMS energy threshold for VAD speech detection |
| `preRollMs` | `integer` | Yes | Pre-roll PCM duration before utterance start (ms) |
| `stableChunkSizeMs` | `integer` | Yes | Stable speech chunk duration for utterance confirmation (ms) |
| `drainMode` | `string` | Yes | `"DRAIN_FROM_NEXT_FRAME"` or `"DRAIN_FROM_HEAD"` |
| `startType` | `string` | No (default: `"MANUAL"`) | `"MANUAL"`, `"VAD_START"`, or `"WAKEWORD"` |
| `stopType` | `string` | No (default: `"MANUAL"`) | `"MANUAL"`, `"AUTO_SILENCE"`, or `"DURATION"` |
| `warmupEnabled` | `boolean` | No (default: `false`) | Enable Whisper warm-up |
| `warmupDurationMs` | `integer` | No (default: `0`) | Warm-up duration (ms) |
| `sessionTimeoutMs` | `integer` | No (default: `0`) | Max session duration before auto-finalise (0 = no timeout) |
| `bufferSizeSamples` | `integer` | No (default: `4000`) | PCM capture buffer size in samples |

For `stopType = "AUTO_SILENCE"`, also include:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `silenceMs` | `integer` | Yes | Silence duration that triggers stop (ms) |
| `maxDurationMs` | `integer` | Yes | Maximum allowed session duration (ms) |

For `startType = "VAD_START"`, also include:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `vadStartThreshold` | `number` | Yes | Energy threshold for VAD-based start |
| `minSpeechMs` | `integer` | Yes | Minimum consecutive speech ms for VAD-based start |

For `startType = "WAKEWORD"`, also include:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `wakeWord` | `string` | Yes | Wake word phrase |
| `confidenceThreshold` | `number` | Yes | Detection confidence threshold |

### Output result (from `init()` return value and `setOnMessageListener`)

**Success:**

```json
{
  "type": "result",
  "text": "transcribed text",
  "code": "SUCCESS",
  "timing": {
    "captureMs": 3200,
    "inferenceMs": 450,
    "totalMs": 5200
  }
}
```

**Error:**

```json
{
  "type": "error",
  "code": "MODEL_LOAD_FAILED",
  "message": "File not found at /data/app/model.bin"
}
```

**Init result (success):**

```json
{
  "type": "result",
  "text": "",
  "code": "SUCCESS",
  "timing": null
}
```

**Init result (error):**

```json
{
  "type": "error",
  "code": "INVALID_CONFIG",
  "message": "Missing required field: modelPath"
}
```

## Threading and Callbacks

- **Audio capture** runs on a dedicated thread (`AudioCaptureThread` with `THREAD_PRIORITY_AUDIO`)
- **Whisper inference** runs on a dedicated single-thread executor (`WhisperExecutor`)
- **Callbacks** (via `setOnMessageListener`) are delivered on the inference thread, **not** on the main thread
- Callers must post to their own `Handler` or `Dispatchers.Main` for UI updates
- **Lifecycle methods** (`loadModel`, `startSession`, `init`, `transcribe`) are serialised internally via `stateLock`
- **Do not call lifecycle methods from within callbacks** — this produces undefined behaviour

## Lifecycle

```
UNINITIALISED → INITIALISED → READY → RECORDING → FINALISING → STOPPED → READY → ...
```

- `loadModel()` transitions to `READY`. Does NOT start capture.
- `startSession()` transitions to `RECORDING`. Starts PCM capture and processor.
- `transcribe()` transitions through FINALISING → STOPPED → READY.
- `init()` does both `loadModel()` + `startSession()` in one call.
- Construction creates the object but does NOT load the model or start capture.

## Stale Callback Rejection (Epoch Model)

- Each session has a monotonically incrementing `sessionEpoch` (`AtomicLong`)
- At inference submission, the current epoch is snapshotted
- Callbacks whose epoch does not match the current active epoch are dropped
- This prevents stale results from out-of-order sessions

## Start/Stop Strategies

Configured via JSON fields `startType` and `stopType`:

- **Manual start / Manual stop** (`startType: "MANUAL"`, `stopType: "MANUAL"`):
  - Start and stop on explicit caller request
  - No VAD processing during capture
  - PCM buffered until `transcribe()` is called

- **Manual start / Auto silence stop** (`stopType: "AUTO_SILENCE"`):
  - Capture stops automatically after sustained silence
  - VAD is active during capture
  - UtteranceAccumulator manages utterance boundaries

- **VAD start** (`startType: "VAD_START"`):
  - Recording starts automatically when speech crosses the VAD threshold
  - Requires `vadStartThreshold` and `minSpeechMs` in the JSON config

## Installation

```kotlin
// settings.gradle.kts
include(":stt")

// app/build.gradle.kts
implementation(project(":stt"))
```

## Basic Usage Example

```kotlin
// Register listener before loadModel (it is buffered by the companion)
SpeechToText.setOnMessageListener { json ->
    // json is a JSON string — inspect "type" field
    // "result": successful transcription
    // "error": an error occurred
    runOnUiThread {
        // update UI
    }
}

// Phase 1: load model at app startup
val configJson = buildConfigJson(modelPath, "en", "MANUAL")
val loadError = SpeechToText.loadModel(applicationContext, configJson)
if (loadError != null) {
    // handle config/model error
    return
}

// Phase 2: start session on user action
val sessionError = SpeechToText.startSession()
if (sessionError != null) {
    // handle session error
    return
}

// On Stop button:
SpeechToText.transcribe()
// Result arrives asynchronously via the message listener

// Or use the convenience method (loadModel + startSession in one call):
// val initError = SpeechToText.init(applicationContext, configJson)
```

## VAD Behaviour

The VAD pipeline classifies each PCM frame as speech or silence.

Early-close logic (Auto mode):

- When speech transitions to silence for the configured `silenceMs` duration
- The command window closes immediately
- Whisper receives only the relevant PCM

## Error Codes

| Code | Category | Description |
|------|----------|-------------|
| `MODEL_LOAD_FAILED` | WHISPER_ERROR | Whisper model failed to load |
| `INFERENCE_FAILED` | WHISPER_ERROR | Whisper inference returned an error |
| `CAPTURE_FAILED` | CAPTURE_ERROR | Audio capture failed to start |
| `CONFIG_PARSE_FAILED` | CONFIG_ERROR | JSON config parsing failed |
| `CONFIG_NOT_SET` | CONFIG_ERROR | Config not provided before session |
| `PIPELINE_ILLEGAL_STATE` | UNKNOWN | Illegal lifecycle transition |
| `INTERNAL_EXCEPTION` | UNKNOWN | Unexpected internal error |

Errors are delivered via `setOnMessageListener` as JSON, or returned directly
from `loadModel()` / `init()` as `SttError?`.

## Testing

```bash
./gradlew :stt:test
```

Unit tests (JUnit 4, no Android dependency) cover:

- Two-phase lifecycle (loadModel + startSession)
- Pipeline stage transitions and legal/illegal transitions
- Epoch-based stale callback rejection
- JSON boundary parsing and serialisation
- VAD energy threshold, hysteresis, silence timeout
- UtteranceAccumulator pre-roll, speech detection, force timeout
- Start/stop strategy combination and mode detection
- Inference submission, dispatch, timing snapshot
- Error dispatch through all listener types
- Model lifecycle, forced failure, executor shutdown
- Callback dispatch and listener safety

Test doubles: `FakeWhisperModel`, `FakeCaptureManager`, `FakeAudioCapture`
