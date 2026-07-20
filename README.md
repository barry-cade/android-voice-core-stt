# android-voice-core-stt

Offline speech‑to‑text (STT) components for Android, including:

- A full Whisper‑based STT engine (`:stt`)
- A standalone Vosk recogniser module (`:vosk`)

The project is designed for robotics, embedded agents, and voice‑controlled systems where deterministic behaviour, low latency, and offline operation are required.

## Overview

android-voice-core-stt is a modular speech stack containing two independent subsystems:

### 1. Whisper STT Engine (`:stt`)
A production‑grade pipeline:

- PCM → VAD → accumulator → Whisper → transcript  
- Deterministic lifecycle (READY → RECORDING → FINALISING)
- Pre‑roll, silence padding, minimum utterance enforcement
- Strict public API (`SpeechToText`, `SttConfig`, `AudioCapture`, `WhisperBridge`)

### 2. Vosk Recogniser (`:vosk`)
A lightweight, standalone recogniser:

- PCM → Vosk → text
- No VAD, no accumulator, no Whisper dependency
- Suitable for commands, wake‑words, offline robotics, and low‑latency tasks

These subsystems are **independent** and may be used separately depending on application needs.

## Repository Structure

```text
android-voice-core-stt/
├── stt/                          # Whisper STT engine (full pipeline)
│   ├── src/main/java/.../stt/
│   ├── src/main/cpp/             # Whisper JNI bindings
│   ├── ARCHITECTURE.md
│   ├── README.md
│   └── build.gradle.kts
├── vosk/                         # Standalone Vosk recogniser
│   ├── src/main/java/.../vosk/
│   ├── README.md
│   └── build.gradle.kts
├── app/                          # Demo Android application (Whisper STT only)
│   ├── src/main/java/...
│   ├── src/main/res/
│   └── build.gradle.kts
├── README.md                     # This file
└── settings.gradle.kts           # Includes :app, :stt, :vosk
