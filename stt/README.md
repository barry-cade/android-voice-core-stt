
# stt-core — Android Speech‑to‑Text Module

The **stt-core** module provides offline speech‑to‑text functionality for Android using:

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
- Returns full transcription text and segment metadata

### Deterministic VAD

Energy‑based VAD with configurable parameters:

- energyThreshold  
- silencePaddingMs  
- preRollMs  
- stableChunkSizeMs  
- maxUtteranceLengthMs  
- motionMode overrides  

### PCM Capture Pipeline

- 16 kHz mono PCM
- Dedicated audio thread
- Fixed frame size
- Pre‑roll buffer
- Silence padding
- Deterministic command window

### Clean Kotlin API

The module exposes:

- startRecording()
- stopAndTranscribe()
- setConfig()
- Listener callbacks for STT events
- Runtime configuration updates

## Installation

Add the module to your project:

1. Copy the `stt-core` folder into your Android project.
2. Add it to `settings.gradle`:

include(":stt-core")

1. Add dependency in your app module:

implementation(project(":stt-core"))

## Basic Usage

### Create configuration

val config = RuntimeSttConfig(
    energyThreshold = 0.03f,
    silencePaddingMs = 300,
    preRollMs = 100,
    maxUtteranceLengthMs = 4000
)

### Instantiate the engine

val stt = SttEngine(context, config)

### Start recording

stt.startRecording()

### Stop and transcribe

stt.stopAndTranscribe { result ->
    val text = result.text
    // handle transcription
}

### Update configuration at runtime

stt.setConfig(newConfig)

## Public API

### SttEngine

Core class controlling PCM capture, VAD, and Whisper inference.

Methods:

- startRecording()  
  Starts PCM capture and VAD monitoring.

- stopAndTranscribe(callback)  
  Stops capture and runs Whisper inference.  
  Returns transcription result via callback.

- setConfig(config)  
  Updates runtime configuration.

- destroy()  
  Releases audio resources and unloads Whisper model.

### RuntimeSttConfig

Holds all configurable parameters:

- energyThreshold  
- silencePaddingMs  
- preRollMs  
- stableChunkSizeMs  
- maxUtteranceLengthMs  
- motionMode overrides  

### MotionModeConfig

Optional overrides for:

- walking  
- running  
- riding  
- stationary  

Allows tuning VAD thresholds based on robot motion.

### SttResult

Returned by stopAndTranscribe():

- text  
- segments  
- inferenceTimeMs  
- pcmDurationMs  

## VAD Behaviour

The VAD pipeline classifies each PCM frame as:

- speech  
- silence  

Early‑close logic:

- When speech transitions to silence for a configured duration  
- The command window closes immediately  
- Whisper receives only the relevant PCM

## Whisper Backend

The JNI layer provides:

- Model loading
- Model unloading
- Full inference (whisper_full)
- Segment extraction
- Text assembly

The tiny_en model is recommended for mobile performance.

## Threading Model

- Audio capture runs on a dedicated thread
- Whisper inference runs on a background thread
- Callbacks are delivered on the main thread

This prevents UI blocking and ensures deterministic timing.

## Error Handling

Possible error cases:

- AudioRecord failure  
- Whisper model missing  
- JNI load failure  
- Timeout due to maxUtteranceLengthMs  

Errors are surfaced via callback or logs.

## Testing

The demo app in the root project demonstrates:

- VAD behaviour
- PCM capture
- Whisper inference timing
- Configuration tuning

Use the demo app to validate your integration.

## License

MIT (or the license defined in the root project)
