
# stt — Android Speech‑to‑Text Module

The **stt** module provides offline speech‑to‑text functionality for Android using:

- Whisper tiny_en (JNI + C++)
- Deterministic voice activity detection (VAD)
- Configurable PCM capture pipeline
- Command‑window behaviour (pre‑roll, silence padding, early close)
- A clean Kotlin API suitable for robotics, agents, and embedded systems

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
- `silencePaddingMs`
- `preRollMs`
- `stableChunkSizeMs`
- `maxUtteranceLengthMs`
- Motion‑mode overrides

### PCM Capture Pipeline

- 16 kHz mono PCM
- Dedicated audio capture thread
- Fixed frame size
- Pre‑roll buffer
- Silence padding
- Deterministic command window

### Clean Kotlin API

The public API surface is strictly enforced — only the following types are exposed:

- `SpeechToText` — main entry point
- `SttConfig` — static configuration data class
- `AudioCapture` — raw microphone capture (primarily for internal use)
- `WhisperBridge` — JNI bridge for Whisper (primarily for internal use)
- `SttError`, `SttErrorCode`, `SttErrorCategory` — structured error types
- `SttErrorListener` — callback interface for structured errors
- `SttReadyListener` — callback interface for model readiness
- `SttTimingSnapshot` — timing diagnostics data class
- `SttLifecycleState` — state machine enum

All other classes are internal.

## Installation

Add the module to your project:

1. Copy the `stt` folder into your Android project.
2. Add it to `settings.gradle.kts`:

```kotlin
include(":stt")
```

1. Add dependency in your app module:

```kotlin
implementation(project(":stt"))
```

## Basic Usage

### 1. Create SpeechToText via the companion factory

```kotlin
val stt = SpeechToText.create(
    energyThreshold = 0.03f,
    silencePaddingMs = 300,
    preRollMs = 100,
    maxUtteranceLengthMs = 4000,
    stableChunkSizeMs = 500,
    motionModeEnergyThreshold = 0.05f,
    motionModeSilencePaddingMs = 150,
    modelPath = "/path/to/ggml-tiny.en.bin"
)
```

### 2. Register result listener

```kotlin
stt.setOnResultListener { text ->
    // handle transcription
}
```

### 3. Start recording

```kotlin
stt.start()
// If the model is still loading, start() queues automatically
// and executes once the model is ready.
```

### 4. Stop and transcribe

```kotlin
stt.stopAndTranscribe()
// Runs blocking on the calling thread — dispatch to background if needed.
```

### 5. Optional: be notified when the model is ready

```kotlin
stt.setReadyListener(object : SttReadyListener {
    override fun onSttReady() {
        // model loaded and warmed up
    }
})
```

### 6. Optional: structured error handling

```kotlin
stt.setSttErrorListener(SttErrorListener { error ->
    Log.e("STT", "${error.category} - ${error.code}: ${error.message}")
})
```

### 7. Clean up

```kotlin
stt.destroy()
```

## Public API Reference

### SpeechToText

Main class controlling PCM capture, VAD, and Whisper inference.

**Factory method:**

```kotlin
companion object {
    fun create(
        energyThreshold: Float,
        silencePaddingMs: Int,
        preRollMs: Int,
        maxUtteranceLengthMs: Int,
        stableChunkSizeMs: Int,
        motionModeEnergyThreshold: Float,
        motionModeSilencePaddingMs: Int,
        modelPath: String
    ): SpeechToText
}
```

**Methods:**

| Method | Description |
| --- | --- |
| `start()` | Begins PCM capture and VAD. Safe to call before model is ready — auto-queues. |
| `stopAndTranscribe()` | Stops capture, drains buffered audio, runs Whisper inference, delivers result. |
| `stop()` | Alias for `stopAndTranscribe()`. |
| `destroy()` | Releases audio resources, unloads Whisper model. |
| `setOnResultListener(l: (String) -> Unit)` | Registers callback for transcription text. |
| `setOnResultWithTimingListener(l: (text: String, timing: SttTimingSnapshot?) -> Unit)` | Registers callback with optional timing snapshot. |
| `setOnErrorListener(l: (Throwable) -> Unit)` | Legacy error callback. |
| `setSttErrorListener(l: SttErrorListener)` | Structured error callback. |
| `setReadyListener(l: SttReadyListener)` | Callback when model load completes. |
| `setDebugOptions(...)` | Test hooks for forced failure scenarios. |

**Properties:**

| Property | Type | Description |
| --- | --- | --- |
| `onTimingListener` | `((Long, Long, Long, Long) -> Unit)?` | Timing diagnostics: PCM, VAD active, Whisper, total (all in ms). |

### SttConfig

Static configuration data class (currently not used by `SpeechToText.create()`; factory takes individual parameters).

```kotlin
data class SttConfig(
    val sampleRate: Int = 16000,
    val bufferSize: Int = 32000,
    val modelPath: String? = null,
    val debugInstrumentation: Boolean = false,
    val chunkSeconds: Int? = 3,
    val overlapSeconds: Int? = 1
)
```

### AudioCapture

Provides a dedicated microphone thread reading PCM16 mono audio. Publishes `FloatArray` frames into a `ConcurrentLinkedQueue`.

```kotlin
class AudioCapture(
    sampleRate: Int = 16000,
    requestedBufferSizeInBytes: Int
) {
    fun start()    // begins capture thread
    fun stop()     // stops capture and releases AudioRecord
    val frameQueue: ConcurrentLinkedQueue<FloatArray>
    fun getQueue(): ConcurrentLinkedQueue<FloatArray>
}
```

### WhisperBridge

Object exposing JNI bindings to `whisper.cpp`.

```kotlin
object WhisperBridge {
    external fun loadModel(modelPath: String)
    external fun transcribe(samples: ShortArray): String
}
```

### SttErrorListener

```kotlin
fun interface SttErrorListener {
    fun onSttError(error: SttError)
}
```

### SttReadyListener

```kotlin
fun interface SttReadyListener {
    fun onSttReady()
}
```

### SttError

```kotlin
data class SttError(
    val category: SttErrorCategory,
    val code: SttErrorCode,
    val message: String,
    val lastRms: Float? = null,
    val lastVadState: Boolean? = null,
    val timingSnapshotMs: Map<String, Long>? = null,
    val context: Map<String, Any> = emptyMap(),
    val cause: Throwable? = null
)
```

### SttTimingSnapshot

```kotlin
data class SttTimingSnapshot(
    val vadMs: Int,
    val utteranceMs: Int,
    val whisperMs: Int,
    val totalMs: Int
)
```

## VAD Behaviour

The VAD pipeline classifies each PCM frame as speech or silence.

Early‑close logic:

- When speech transitions to silence for the configured `silencePaddingMs` duration
- The command window closes immediately
- Whisper receives only the relevant PCM

## Whisper Backend

The JNI layer provides:

- Model loading
- Model unloading
- Full inference (`whisper_full`)
- Segment extraction
- Text assembly

The `ggml-tiny.en` model is recommended for mobile performance.

## Threading Model

- Audio capture runs on a dedicated thread (`AudioCaptureThread` with `THREAD_PRIORITY_AUDIO`)
- Whisper inference runs synchronously on the calling thread (`stopAndTranscribe()`) or on the processor thread (streaming)
- Callbacks are delivered on whatever thread posted them — use `runOnUiThread` or a handler if UI updates are needed

## Lifecycle

``` text
UNINITIALISED → READY → RECORDING → FINALISING → READY → ...
```

- `destroy()` returns to `UNINITIALISED`.

## Error Handling

Errors are surfaced via `SttErrorListener` or `setOnErrorListener`:

- AudioRecord failure
- Whisper model missing / load failure
- JNI load failure
- Timeout due to `maxUtteranceLengthMs`
- Illegal state transitions

## Testing

The demo app in the root project demonstrates:

- VAD behaviour
- PCM capture
- Whisper inference timing
- Configuration tuning

Use the demo app to validate your integration.

## License

MIT (or the license defined in the root project)
