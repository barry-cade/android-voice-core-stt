# android-voice-core-stt

Offline Speech‑to‑Text (STT) for Android using Whisper, deterministic VAD, and configurable PCM capture.

## Overview

android-voice-core-stt is a modular offline speech‑to‑text engine for Android. It provides:

- Whisper tiny_en transcription (offline)
- Deterministic voice activity detection (VAD)
- Configurable command windows
- Pre‑roll and silence padding
- A reusable STT module (stt-core)
- A demo Android app showing real‑time logs and usage

The project is designed for robotics, embedded agents, and voice‑controlled systems where deterministic behaviour, low latency, and offline operation are required.

## Repository Structure

android-voice-core-stt/

- stt-core/  
  - src/ (PCM capture, VAD, Whisper JNI)  
  - jni/ (Whisper C++ bindings)  
  - README.md (API documentation)  
- app/  
  - src/ (UI + integration with stt-core)  
  - README.md (demo usage instructions)  
- README.md (this file)

## Features

### Offline Whisper STT

Uses Whisper tiny_en for fast, offline transcription on mobile CPUs.

### Deterministic VAD

Energy‑based voice activity detection with configurable parameters:

- energyThreshold  
- silencePaddingMs  
- preRollMs  
- stableChunkSizeMs  
- maxUtteranceLengthMs  
- Motion‑mode overrides

### Command Window Architecture

Supports deterministic command windows:

- Fixed duration (e.g., 2–4 seconds)
- VAD early‑close
- Pre‑roll audio capture
- Silence padding
- Max utterance length enforcement

### Clean API

The STT module exposes:

- startRecording()
- stopAndTranscribe()
- setConfig()
- Listener callbacks for STT events

### Demo App Included

Shows:

- Live VAD logs
- PCM capture behaviour
- Whisper inference timing
- Full transcription output
- Config editing

## Architecture

### PCM Capture

A dedicated audio thread captures 16 kHz mono PCM:

- 32000‑byte buffer
- 16000‑sample frames
- Continuous stream during active recording

### VAD

Each frame is analysed:

- RMS energy
- Speech/silence classification
- Silence frame counting
- Early‑close logic

### Whisper Backend

Whisper tiny_en is loaded via JNI:

- Model load/unload
- Full inference (whisper_full)
- Segment extraction
- Text output

### STT Flow

startRecording()  
→ PCM capture thread starts  
→ VAD monitors speech  
→ stopAndTranscribe()  
→ Whisper inference  
→ Transcription result

## Quick Start

### Clone

git clone --recurse-submodules https://github.com/barry-cade/android-voice-core-stt.git

Or if already cloned, initialize the whisper.cpp submodule:

git submodule update --init --recursive

### Build

Open the project in Android Studio and build normally.

### Run Demo App

Install the demo app on an Android device:

1. Grant microphone permission  
2. Tap Start to begin recording  
3. Tap Stop to transcribe  
4. View logs in Logcat under STT_* tags

## Integration Guide

To use the STT module in your own Android app:

1. Copy the stt-core module into your project.
2. Add it to settings.gradle.
3. Add the dependency in your app’s build.gradle.
4. Instantiate the STT engine:

val stt = SttEngine(context, config)

1. Start recording:

stt.startRecording()

1. Stop and transcribe:

stt.stopAndTranscribe { result ->
    // handle transcription
}

1. Adjust configuration:

stt.setConfig(newConfig)

Full API documentation will be available in stt-core/README.md.

## Roadmap

- Add wake‑word integration examples  
- Add Vosk grammar example for command recognition  
- Add dual‑pipeline demo (Rex vs Zip)  
- Add unit tests for VAD  
- Add benchmark mode for Whisper inference  

## License

MIT (or whichever license you choose)
