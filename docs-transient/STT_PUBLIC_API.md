# Speech‑To‑Text (STT) Public API Specification  

**Version:** 1.0  
**Module:** `dev.barrycade.voicecore.stt`  
**Author:** Mike & Copilot  
**Purpose:** Define the minimal, stable, robot‑friendly public API for the STT subsystem.

---

## Overview

The STT subsystem provides a simple, deterministic interface for performing speech recognition inside the Moto (Android) robot brain. It is designed to:

- load Whisper/Vosk once at app startup  
- accept configuration at startup and at runtime  
- run continuously  
- perform inference on demand  
- return results synchronously as JSON  
- hide all internal pipeline complexity  

The public API is intentionally minimal.  
All lifecycle, threading, VAD, wake‑word, and audio capture behaviour is internal.

---

## Public API Summary

The STT module exposes **three** public methods:

- `init(configJson)`  
- `configure(configJson)`  
- `transcribe(): String`

Everything else is internal.

---

## Public API Details

### 1. `init(configJson)`

**Purpose:**  
Perform one‑time startup of the STT subsystem.

**Responsibilities:**  

- Load Whisper/Vosk model into memory  
- Allocate buffers  
- Initialise VAD  
- Initialise wake‑word detector  
- Initialise command mode  
- Start audio capture  
- Enter wake‑word mode (if configured)  
- Apply initial configuration  

**Notes:**  

- Called once at app startup  
- Does not require Android `context`  
- Heavy operation (model load + pipeline init)  
- Must be called before `transcribe()` or `configure()`  

---

### 2. `configure(configJson)`

**Purpose:**  
Apply new configuration at runtime without restarting STT.

**Responsibilities:**  

- Update grammar  
- Update wake‑word  
- Update silence timeout  
- Update STT mode  
- Update thresholds  
- Update partials flag  
- Update auto‑return behaviour  
- Update personality behaviour (future)  

**Notes:**  

- Lightweight operation  
- Does not reload model  
- Does not restart audio capture  
- Does not reset pipeline  
- Safe to call at any time after `init()`  

---

### 3. `transcribe(): String`

**Purpose:**  
Perform inference on the current utterance and return JSON.

**Responsibilities:**  

- End current utterance  
- Run inference  
- Produce JSON result  
- Reset internal state  
- Re‑enter wake‑word mode (if configured)  

**Notes:**  

- Synchronous call  
- Returns a JSON string  
- No listener required  
- No callbacks  
- No partials unless configured internally  

---

## What Is NOT Part of the Public API

The following methods are **removed** from the public surface:

- `loadModel(context, configJson)`  
- `startSession()`  
- `stopSession()`  
- `shutdown()`  
- `setOnMessageListener(listener)`  
- All instance‑level APIs  
- All lifecycle control  
- All audio capture control  
- All VAD control  
- All wake‑word control  

These remain **internal** to the STT module.

---

## Internal Components (Non‑Public)

The following classes exist inside the module but are **not** part of the public API:

- `SttConfig`  
- `AudioCapture`  
- `WhisperBridge`  
- `RuntimeSttConfig`  
- `SttSessionConfig`  
- `Vad`, `VadConfig`, `VadGate`  
- `UtteranceAccumulator`  
- `UtteranceListener`  
- `CaptureManager`  
- `SessionManager`  
- `DrainMode`  
- `StartStrategy`, `StopStrategy`  
- `ManualStart`, `ManualStop`, `AutoSilenceStop`, `DurationStop`  
- `FrameResult`, `SessionResult`  

These are implementation details and must remain internal.

---

## Expected Behaviour at App Startup

1. App launches  
2. STT module calls `init(configJson)`  
3. Whisper/Vosk loads  
4. STT pipeline starts  
5. Wake‑word mode becomes active  
6. Robot is ready to listen  

---

## Expected Behaviour at Runtime

- Configuration may be changed at any time via `configure(configJson)`  
- STT continues running without interruption  
- `transcribe()` may be called whenever an utterance is ready  
- JSON results are returned synchronously  

---

## Optional Future Extension

You may optionally support:

- dynamic config per `transcribe()` call  
- remote configuration via Moto HTTP API  
- personality behaviour modes  
- multi‑client override (PC → Moto → Rover)

These do not affect the core API.

---

## Final Public API (Minimal Form)

``` text
init(configJson)
configure(configJson)
transcribe(): String
```

This is the complete and final public API for the STT subsystem.

---

If you want, I can also generate:

- the matching Kotlin interface  
- the internal architecture diagram  
- the JSON config schema  
- the Moto configuration API (for PC override)  

Just say the word.
