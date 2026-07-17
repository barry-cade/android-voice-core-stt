# STT Module Architecture

> **Revamp date:** This document reflects the current codebase as of the JSON-boundary +
> two-phase lifecycle + epoch-based callback rejection refactor.
>
> **Previous version (`ARCHITECTURE_MAP.md`) described the pre-JSON-boundary
> implementation and is retained as historical reference only.**

---

## Table of Contents

1. [JSON Boundary Philosophy](#1-json-boundary-philosophy)
2. [Public API Surface](#2-public-api-surface)
3. [Boundary Layer: SttJsonAdapter](#3-boundary-layer-sttjsonadapter)
4. [Two-Phase Lifecycle](#4-two-phase-lifecycle)
5. [Pipeline: End-to-End Data Flow](#5-pipeline-end-to-end-data-flow)
6. [Mode-Dependent Paths](#6-mode-dependent-paths)
7. [Threading Model](#7-threading-model)
8. [Lock Model](#8-lock-model)
9. [Epoch Model (Stale Callback Rejection)](#9-epoch-model-stale-callback-rejection)
10. [Controller Architecture](#10-controller-architecture)
11. [Strategy Model](#11-strategy-model)
12. [Error and Callback Architecture](#12-error-and-callback-architecture)
13. [Internal Type Inventory](#13-internal-type-inventory)
14. [Build-Time API Governance](#14-build-time-api-governance)
15. [Testing Strategy](#15-testing-strategy)

---

## 1) JSON Boundary Philosophy

The STT module communicates with the app layer **exclusively through JSON strings**.
This is a deliberate architectural choice with the following properties:

- **No type coupling**: The app never imports STT internal types. All data crosses the
  boundary as `String`.
- **Stable contract**: JSON schemas evolve independently of Kotlin type hierarchies.
- **Testable boundary**: `SttJsonAdapter` is the single point of JSON serialisation
  and deserialisation. No other component reads or writes JSON.
- **Language agnostic**: The JSON boundary would allow future non-Kotlin consumers
  (e.g., JNI, Python, Go) without changing the STT core.

### Boundary diagram

```
  App module                    JSON boundary              STT module
  ----------                    --------------              ----------
  MainActivity.kt               SttJsonAdapter              SpeechToText (companion)
      |                              |                           |
      +- loadModel(json) ----------->|--> SttConfig              |
      |                              |    SttSessionConfig       |
      |                              |    RuntimeSttConfig       |
      |                              |    ModelManager.load()    |
      |                              |                           |
      +- startSession() -------------|  -> PCM capture start     |
      |                              |    Processor start (auto) |
      |                              |                           |
      +- transcribe() ---------------|  -> Stop capture          |
      |                              |    Finalise PCM           |
      |                              |    Submit inference       |
      |                              |                           |
      |<--- "result"/"error" --------+<-- dispatchResult/json    |
      |                              |    dispatchError/json     |
```

## 2) Public API Surface

The entire public API fits in a single class.

### Entry point: `SpeechToText` (companion object)

| Method | Signature | Purpose |
|--------|-----------|---------|
| `loadModel` | `(context: Context, configJson: String): SttError?` | Load model, configure pipeline, run warm-up. Does NOT start capture. Safe at app startup. |
| `startSession` | `(): SttError?` | Begin audio capture and transcription session. Must be called after `loadModel`. |
| `init` | `(context: Context, configJson: String): SttError?` | Convenience: `loadModel` + `startSession` in one call. |
| `transcribe` | `()` | Stop current utterance, run inference, dispatch result. |
| `setOnMessageListener` | `(listener: (String) -> Unit)` | Register single JSON message callback. |

### Output JSON shapes

**Result:**

```json
{ "type": "result", "text": "...", "code": "SUCCESS",
  "timing": { "vadActiveMs": 1200, "utteranceMs": 3200, "inferenceMs": 450, "totalMs": 5200 } }
```

**Error:**

```json
{ "type": "error", "category": "CONFIG_ERROR", "code": "MODEL_LOAD_FAILED",
  "message": "File not found", "details": ["modelPath=/data/app/model.bin"] }
```

### Visibility rules

| Visibility | Types |
|------------|-------|
| public | `SpeechToText` (class + companion) |
| internal | Everything else |

## 3) Boundary Layer: SttJsonAdapter

`SttJsonAdapter` is the **single component** that translates between JSON strings
and internal Kotlin types.

### Input path

```
JSON string --> SttJsonAdapter.parseConfig() --> SttConfig
                                                    |
                                                    v
                                             SttSessionConfig.from()
                                                    |
                                                    v
                                             RuntimeSttConfig
                                               (strategy instances)
```

### Output path

```
Internal types --> SttJsonAdapter.buildResultJson()
                  SttJsonAdapter.buildErrorJson()
                  SttJsonAdapter.buildDebugJson()
                              |
                              v
                         JSON string --> setOnMessageListener()
```

### Key properties

- **Manual JSON parsing** (regex-based) -- no `org.json` dependency, works in pure unit tests
- Supports both **flat format** (preferred) and **legacy nested format** (`ttsEngineConfig`, `vadConfig`)
- Validates required fields, returns descriptive `IllegalArgumentException`
- Escapes JSON string values correctly
- Only called by `SttCallbackDispatcher.dispatchResult/dispatchError` (output) and
  `SpeechToText.loadModel` (input)

### Input JSON schema (flat, preferred)

```json
{
  "modelPath": "/path/to/model.bin",
  "language": "en",
  "debugLoggingEnabled": true,
  "energyThreshold": 0.03,
  "preRollMs": 100,
  "stableChunkSizeMs": 500,
  "drainMode": "DRAIN_FROM_NEXT_FRAME",
  "startType": "MANUAL",
  "stopType": "AUTO_SILENCE",
  "silenceMs": 1200,
  "maxDurationMs": 30000,
  "warmupEnabled": true,
  "warmupDurationMs": 3000,
  "sessionTimeoutMs": 0,
  "bufferSizeSamples": 4000
}
```

## 4) Two-Phase Lifecycle

The STT module exposes a **two-phase lifecycle** that decouples model loading
from session capture. This is the most significant architectural change from
the previous single-call `init` design.

### Phase 1: Load Model (app startup)

```
SpeechToText.loadModel(context, configJson)
    |
    +- SttJsonAdapter.parseConfig(json) --> SttConfig
    +- SttSessionConfig.from(sttConfig) --> immutable session config
    +- ModelManager.loadModelIfNeeded() --> sync model load on caller thread
    +- ModelManager.runWarmup() --> optional warm-up inference
    +- Reconstruct CaptureManager with runtime buffer size
    +- ModeController.selectController(): build PollingController
    +- Build SttProcessingController (auto mode only): Vad, UtteranceAccumulator, ProcessorController
    +- LifecycleController.onReady() --> state=READY
```

**Key properties:**

- Does NOT start AudioCapture
- Does NOT start any worker threads (except the model is loaded synchronously)
- Idempotent -- subsequent calls return immediately once the model is loaded
- Safe to call in `Application.onCreate()` or Activity `onCreate()`

### Phase 2: Start Session (user action)

```
SpeechToText.startSession()
    |
    +- Guard: model ready? config set? not already capturing?
    +- Increment sessionEpoch (AtomicLong)
    +- Clear stopRequest
    +- PipelineStage: IDLE --> CAPTURING
    +- SessionController.beginSession() --> record session start timestamp
    +- CaptureController.startCapture():
    |   +- CaptureManager.beginPcmCapture() --> starts AudioRecord synchronously
    |   +- (auto mode) CaptureManager.beginSttProcessing() --> starts drain thread
    +- (manual mode) CaptureManager.activatePcmCapture() + MinimalPollingController.start()
    +- (auto mode) ProcessorController.start() --> T3 worker thread
```

### Transcribe (stop utterance)

```
SpeechToText.transcribe()
    |
    +- Guard: session timeout?
    +- StopStrategy.shouldStop()?
    +- PipelineStage: CAPTURING --> FINALISING
    +- Set isRunning=false, stopRequest.raise()
    +- ModeController.stopController() --> join worker thread
    +- CaptureController.finaliseAndStop(vadGate) --> drain queue, stop AudioRecord
    +- (if PCM empty) --> STOPPED --> READY, return
    +- PipelineStage: FINALISING --> INFERENCING
    +- Submit inference via InferenceController
    |   +- ModelManager.submitInference() --> WhisperExecutor task
    |       +- transcribe() JNI call under C++ mutex
    |       +- decideDispatch() --> epoch check + pipeline stage check
    |       +- dispatchResult() --> build JSON --> onMessageListener
    |       +- onComplete() --> STOPPED --> READY
    +- Epoch cleared, ready for next session
```

### Auto-mode utterance flow (no explicit transcribe call)

```
ProcessorController loop (T3):
    pollFrame() --> VAD.isSpeech() --> UtteranceAccumulator.processChunk()
        +- FrameResult.UtteranceReady --> handleUtteranceReady()
            +- PipelineStage: CAPTURING --> INFERENCING
            +- Submit inference (same path as transcribe)
            +- onPostDispatch() --> INFERENCING --> CAPTURING (continues recording)
```

### Lifecycle state machine

```
UNINITIALISED --> INITIALISED (onInit)
INITIALISED   --> READY       (onReady -- model loaded)
READY         --> RECORDING   (onStart -- capture active)
RECORDING     --> FINALISING  (onFinalising -- stopping capture)
FINALISING    --> STOPPED     (onStop -- capture stopped)
STOPPED       --> READY       (onReset -- ready for next session)
```

**Bypass transitions** (via `forceSet`):

- READY/INITIALISED --> FINALISING -- stop requested before start completed
- RECORDING/FINALISING --> READY -- reset while still active
- Any --> UNINITIALISED -- terminal destroy

### Pipeline stages (runtime flow control)

```
IDLE --> CAPTURING --> INFERENCING --> DISPATCHING --> CAPTURING (auto mode, loop)
IDLE --> CAPTURING --> FINALISING --> INFERENCING --> DISPATCHING --> IDLE (manual mode, complete)
Any --> IDLE (error/empty-PCM path)
```

Managed by `SttPipelineState` which validates legal transitions and logs illegal ones.
Legal transitions:

| From | To |
|------|----|
| IDLE | CAPTURING |
| CAPTURING | INFERENCING, FINALISING, IDLE |
| FINALISING | INFERENCING, IDLE |
| INFERENCING | DISPATCHING, CAPTURING, IDLE |
| DISPATCHING | CAPTURING, IDLE |

## 5) Pipeline: End-to-End Data Flow

```
+----------+    +------------+    +------------+    +------------+    +-----------+
| Audio    |-->| Capture    |-->| VAD /      |-->| Whisper    |-->| Callback  |
| Capture  |    | Manager    |    | Accumulator |    | Model      |    | Dispatcher|
| (T1)     |    | (T2/T3)    |    | (T3)       |    | (T4)       |    | (T4)      |
+----------+    +------------+    +------------+    +------------+    +-----------+
     |               |                |                   |                |
     |   PCM FloatArray via ConcurrentLinkedQueue          |                |
     |               |                |                   |                |
     |   ----------->|  pollFrame()   |                   |                |
     |               |--------------->|  processChunk()   |                |
     |               |                |------------------>| submitInference|
     |               |                |  UtteranceReady   |--------------->|
     |               |                |                   |                |
     |               |                |                   |     result/    |
     |               |                |                   |     error JSON |
     |               |                |                   |     ----> app  |
```

### Primary data path (auto mode)

1. **T1 (AudioCaptureThread):** `AudioRecord.read()` --> `ShortArray` --> normalize to
   `FloatArray` --> `ConcurrentLinkedQueue.offer()`
2. **T2 (DrainThread) or T3 (ProcessorThread):** `CaptureManager.pollFrame()` --> dequeue
   frame, append to session buffer, return frame
3. **T3 (ProcessorThread):** `Vad.isSpeech(frame)` -->
   `UtteranceAccumulator.processChunk(frame, isSpeech)`
4. **T3 --> T4 handoff:** `FrameResult.UtteranceReady` --> `handleUtteranceReady()` -->
   `submitInferenceAndDispatch()`
5. **T4 (WhisperExecutor):** `ModelManager.submitInference()` --> C++ `transcribe()` -->
   `onResult()` callback
6. **T4 (callback):** `decideDispatch()` (epoch + pipeline stage check) -->
   `SttInferenceController` builds `SttTimingSnapshot` -->
   `callbackDispatcher.dispatchResult()` --> JSON --> `setOnMessageListener()`

### Manual mode path

1. **T1 (AudioCaptureThread):** Same as auto mode
2. **T3 (MinimalPollingThread):** `CaptureManager.pollFrame()` (with optional `VadGate` energy
   filter) --> unconditionally append to session buffer
3. **Caller thread (transcribe):** `CaptureManager.finalize(vadGate)` --> returns raw PCM -->
   `submitInferenceAndDispatch()` --> same T4 path as auto mode

## 6) Mode-Dependent Paths

The module supports two operating modes, determined by the combination of
start and stop strategy types.

| Mode | Start Strategy | Stop Strategy | Controller | VAD | Accumulator |
|------|---------------|---------------|------------|-----|-------------|
| **Manual** | `ManualStart` | `ManualStop` | `MinimalPollingController` | `VadGate` (optional, energy-only) | None |
| **Auto** | `ManualStart` | `AutoSilenceStop` | `ProcessorController` | `Vad` (full stateful) | `UtteranceAccumulator` |

### Manual mode flow

1. User presses Start --> `loadModel()` then `startSession()`
2. PCM frames accumulate in CaptureManager session buffer
3. `MinimalPollingController` polls frames, discarding them to prevent unbounded queue growth
4. (Optional) `VadGate` filters out silence frames so only speech-level PCM enters the buffer
5. User presses Stop --> `transcribe()` --> finalise PCM --> submit inference --> dispatch result
6. Session returns to READY

### Auto mode flow

1. User presses Start --> `loadModel()` then `startSession()`
2. `ProcessorController` polls frames, runs through full `Vad` with hysteresis
3. `UtteranceAccumulator` tracks utterance boundaries: pre-roll --> speech --> silence -->
   utterance ready
4. On `UtteranceReady`: inference submitted automatically, result dispatched
5. Processor loop continues recording -- the `StopStrategy` decides when capture ends
6. When silence exceeds `autoSilenceMs`, `AutoSilenceStop` triggers full stop

### Session timeout

Both modes support `sessionTimeoutMs` (default 0 = no timeout). When set,
`transcribe()` is called automatically if the session exceeds the limit,
preventing abandoned sessions from holding the microphone indefinitely.

## 7) Threading Model

| Thread | Owns | Notes |
|--------|------|-------|
| **Caller thread** | Public lifecycle methods (`loadModel`, `startSession`, `init`, `transcribe`) | Serialised via `SpeechToText.stateLock` |
| **T1: AudioCaptureThread** | `AudioRecord.read()`, PCM frame `ConcurrentLinkedQueue.offer()` | Guarded by `AudioCapture.stateLock` for start/stop. Single-producer queue. |
| **T2: DrainThread** | Warm-up PCM buffering into session buffer | Created by `CaptureManager`. Stopped on first `pollFrame()` call (processor takes over) or `finalize()`. |
| **T3: ProcessorThread** | VAD, utterance accumulation, PCM polling | Created by `ProcessorController.start()`. Joined in `stop()`. |
| **T3: MinimalPollingThread** | Queue drain (manual mode), optional VadGate filtering | Created by `MinimalPollingController.start()`. Joined in `stop()`. |
| **T4: WhisperExecutor** | Model load/unload/transcribe | Single-thread `ExecutorService` in `ModelManager`. All native inference serialised through this thread. |

### Thread safety principles

- `stateLock` guards ALL public lifecycle methods end-to-end
- `stateLock` is NOT held across blocking operations (thread joins, native inference, executor shutdown)
- Blocking operations are performed OUTSIDE `stateLock` (they happen before or after the lock scope)
- Callbacks are delivered on the Whisper executor thread, never on the caller thread

### Self-join safety

All thread joins (`MinimalPollingController.stop`, `ProcessorController.stop`,
`CaptureManager.finalize`, `AudioCapture.stop`) guard against self-join by checking
`thread !== Thread.currentThread()` before calling `join()`. This prevents a worker
thread from joining itself (which would hang forever).

## 8) Lock Model

| Lock | Held By | Protects | Notes |
|------|---------|----------|-------|
| `SpeechToText.stateLock` | Caller thread | Lifecycle state, pipeline stage transitions, `isRunning`, `isInferencing`, controller references | Non-reentrant -- serialised externally |
| `CaptureManager.stateLock` | Caller thread | `captureStarted`, `draining`, `sttActive`, `drainThread`, `currentDrainMode` | Short-duration |
| `CaptureManager.sessionBufferLock` | Drain/processor threads | `sessionBuffer` (mutable list) | Never held together with `stateLock` |
| `AudioCapture.stateLock` | Caller thread | AudioRecord lifecycle, `isRunning`, `workerThread` | Internal to AudioCapture |
| `SttCallbackDispatcher.listenerLock` | Any | Listener references | `synchronized` block |
| `SttLifecycleStateMachine` internal lock | Any | State transitions | Kotlin `synchronized` |
| `ModelManager.stateLock` | Caller thread | `modelPath` | Short-duration |
| `SttPipelineState` internal lock | Caller thread | Pipeline stage transitions | Kotlin `synchronized` |
| `SttModeController` internal lock | Caller thread | Controller construction | Write path only; reads use `@Volatile` |
| `SttSessionController` internal lock | Caller thread | Timing fields | All reads/writes acquire lock |
| C++ `g_mutex` | Whisper executor | Global whisper context | `std::mutex` in `whisper_bridge.cpp` |

### Lock ordering

The critical ordering invariant: **Never hold `CaptureManager.stateLock` and
`sessionBufferLock` simultaneously.** Worker threads access `sessionBufferLock`
without `stateLock`, and the caller thread acquires `stateLock` before calling
methods that may touch the session buffer.

## 9) Epoch Model (Stale Callback Rejection)

- `sessionEpoch` is an `AtomicLong` incremented on each `startSession()` call
- `currentSessionEpoch` is snapshotted at inference submission time
- `decideDispatch()` (called on Whisper executor thread) checks:
  - Does `sessionEpochAtSubmission == currentSessionEpoch`? (epoch match check)
  - Can pipeline stage transition to `DISPATCHING`? (not already finalised/destroyed)
- If either check fails, the callback is dropped with a diagnostic log

This prevents stale results from:

- Rapid start/stop/start cycles
- Late-arriving inference results from previous sessions
- Destroy called while inference is in-flight

### onComplete and onPostDispatch callbacks

The `createOnComplete` callback (invoked in the `finally` block of the Whisper executor
task) ensures that `isInferencing` is reset and the pipeline transitions to IDLE
even if the task throws. The `createOnPostDispatch` callback handles the **auto-mode
loop-back**: after dispatching, the pipeline returns to CAPTURING so recording continues.

## 10) Controller Architecture

The module uses layered controller decomposition. Each controller has a single
responsibility and delegates to the next layer.

```
SpeechToText (orchestrator)
    |
    +-- SttLifecycleController --> SttLifecycleStateMachine (lifecycle state)
    +-- SttSessionController (timing, session markers)
    +-- SttModeController --> MinimalPollingController | ProcessorController (mode selection)
    +-- SttCaptureController --> CaptureManager --> AudioCapture (PCM lifecycle)
    +-- SttProcessingController --> Vad, UtteranceAccumulator, ProcessorController (auto-mode)
    +-- SttInferenceController --> ModelManager --> WhisperBridge --> JNI (inference)
    +-- SttCallbackDispatcher (result/error dispatch)
    +-- SttPipelineState (runtime pipeline stage)
    +-- SttThreadController (utility, instantiated but not actively wired)
```

### Controller responsibilities

| Controller | Responsibility | Owns | Does NOT own |
|------------|---------------|------|--------------|
| `SpeechToText` | Orchestrate lifecycle, wire controllers, convert PCM format, enforce high-level state gating | All controller references, `stateLock`, `AtomicBoolean` flags | VAD, accumulator, mode logic, JSON parsing |
| `SttLifecycleController` | Enforce legal lifecycle transitions | `SttLifecycleStateMachine` | PCM, threading, mode branching |
| `SttSessionController` | Track session/inference/PCM/utterance timing | Timing state | Lifecycle, mode, callbacks |
| `SttModeController` | Build mode-specific controller from config | `MinimalPollingController`, `ProcessorController` refs | VAD, accumulator, lifecycle |
| `SttCaptureController` | Wrap `SessionManager` behind stable API | `SessionManager` reference | VAD, accumulation, inference |
| `SttProcessingController` | Construct auto-mode pipeline (VAD, accumulator, processor) | `Vad`, `UtteranceAccumulator`, `ProcessorController` | Lifecycle, mode switching |
| `SttInferenceController` | Submit inference, build timing snapshot, dispatch result | `ModelManager` reference, `SttCallbackDispatcher` reference | Lifecycle, PCM, VAD |
| `SttCallbackDispatcher` | Dispatch results/errors to registered listeners | Listener references | Lifecycle, threading, mode |
| `SttPipelineState` | Validate and track runtime pipeline stage | Internal stage state | Lifecycle, threading, mode |
| `SttThreadController` | Thread management utility | Not actively wired | All current threading is handled by individual controllers |

### ModelManager

`ModelManager` owns the Whisper model lifecycle: load, unload, transcribe. It
wraps the JNI bridge (`WhisperBridge`) and provides a single-thread executor
for all native calls.

| Method | Purpose |
|--------|---------|
| `loadModelIfNeeded()` | Synchronous model load (blocking, on caller thread) |
| `initAsync(onReady)` | Async model load (on Whisper executor thread) |
| `runWarmup(durationMs)` | Optional warm-up inference (blocking, on caller thread) |
| `submitInference(pcm, onResult, onComplete)` | Submit transcription task to WhisperExecutor |
| `transcribe(pcm)` | Direct synchronous transcribe (for test fallback) |
| `unload()` | Unload model, reset ready flag |
| `shutdown()` | Shut down executor with 5s timeout |

### CaptureManager

`CaptureManager` owns the PCM queue, session buffer, and drain thread.

| Method | Purpose |
|--------|---------|
| `begin(mode)` | Start PCM capture + start STT processing (deprecated, use pair methods) |
| `beginPcmCapture()` | Start AudioCapture synchronously, clear session buffer |
| `beginSttProcessing()` | Start drain thread for warm-up buffering |
| `activatePcmCapture()` | Mark PCM as active (manual mode, no drain thread) |
| `pollFrame()` | Dequeue frame, append to session buffer, return it |
| `pollFrameWithoutAppend()` | Dequeue frame without appending (for VadGate path) |
| `appendFrameToSession(frame)` | Append a pre-polled frame after VadGate approval |
| `finalize(vadGate)` | Stop drain thread, drain remaining queue, stop AudioCapture, return PCM |
| `reset()` | Clear session buffer and queue without stopping capture |
| `restartCapture()` | Restart AudioCapture after finalize |
| `shutdown()` | Terminal: stop drain thread + AudioCapture permanently |

### AudioCapture

`AudioCapture` provides the dedicated microphone thread (T1).

- Reads `AudioRecord` in a loop, normalises `ShortArray` to `FloatArray`, enqueues to `ConcurrentLinkedQueue`
- `start()`: creates AudioRecord, calls `startRecording()`, starts worker thread
- `stop()`: signals stop (outside lock), joins worker thread (outside lock), releases AudioRecord (under lock)

## 11) Strategy Model

Start and stop strategies are **fully orthogonal** interfaces. They are
instantiated by `RuntimeSttConfig.from()` which maps sealed `StartTrigger` /
`StopTrigger` types to concrete `StartStrategy` / `StopStrategy` instances.

### StartStrategy hierarchy

```
StartStrategy (interface)
    shouldStart(events: SttEvents, vad: Vad?): Boolean
    |
    +-- ManualStart: returns true when manualStartPressed event is raised
    +-- VadStart: returns true when VAD reports sustained speech above vadStartThreshold
    +-- WakeWordStart: returns true when wake word detected (placeholder)
```

### StopStrategy hierarchy

```
StopStrategy (interface)
    shouldStop(events: SttEvents, vad: Vad?, elapsedMs: Int): Boolean
    |
    +-- ManualStop: returns true when manualStopPressed event is raised
    +-- AutoSilenceStop: returns true when silence exceeds silenceMs OR duration exceeds maxDurationMs
    +-- DurationStop: returns true when elapsedMs >= maxDurationMs
```

### Sealed trigger types (JSON boundary)

The app supplies trigger types via JSON strings. These are parsed into sealed
types `StartTrigger` and `StopTrigger`, then converted to strategy instances:

```
JSON "startType": "MANUAL"       --> StartTrigger.Manual       --> ManualStart()
JSON "startType": "VAD_START"    --> StartTrigger.VadStart     --> VadStart(config)
JSON "stopType": "MANUAL"         --> StopTrigger.Manual        --> ManualStop()
JSON "stopType": "AUTO_SILENCE"   --> StopTrigger.AutoSilence   --> AutoSilenceStop(config)
JSON "stopType": "DURATION"       --> StopTrigger.Duration      --> DurationStop(config)
```

### Event model

`SttEvents` holds `AtomicBoolean`-backed one-shot event flags:

| Event | Set by | Read by |
|-------|--------|---------|
| `manualStartPressed` | `startSession()` | Start strategies (ManualStart) |
| `manualStopPressed` | `transcribe()` | Stop strategies (ManualStop) |
| `utteranceReady` | Processing pipeline | Stop strategies (AutoSilenceStop) |

Flags are cleared on session reset. `raise()` is idempotent -- the flag stays
raised until explicitly cleared.

### StopRequest

`StopRequest` is a single-write-site holder for the stop signal. Written only
in `transcribe()`, cleared on `sessionEpoch` increment and full teardown.
Read by polling controllers to know when to stop looping.

## 12) Error and Callback Architecture

### SttCallbackDispatcher

Single point of callback dispatch. Holds all listener references under a
`synchronized` lock.

| Method | Dispatch target | Output |
|--------|----------------|--------|
| `dispatchResult(text, code, timing)` | `onResult`, `onResultWithTiming`, `onMessageListener` | JSON result message |
| `dispatchError(error)` | `sttErrorListener`, `onMessageListener` | JSON error message |
| `dispatchTiming(captureMs, vadActiveMs, whisperMs, totalMs)` | `onTimingListener` | Raw timing (internal) |
| `clearListeners()` | All | Clears references (memory leak prevention) |

### SttError hierarchy

```
SttErrorCode (enum)              SttErrorCategory (enum)
    MODEL_LOAD_FAILED ---------------> WHISPER_ERROR
    INFERENCE_FAILED ----------------> WHISPER_ERROR
    CAPTURE_FAILED ------------------> CAPTURE_ERROR
    CONFIG_PARSE_FAILED -------------> CONFIG_ERROR
    CONFIG_NOT_SET ------------------> CONFIG_ERROR
    PIPELINE_ILLEGAL_STATE ----------> UNKNOWN
    INTERNAL_EXCEPTION --------------> UNKNOWN
```

Each `SttError` carries:

- `code`: closed `SttErrorCode` enum value
- `category`: derived from `code.category`
- `message`: human-readable description
- `cause`: optional originating throwable
- `details`: optional diagnostic bullet points (replaces old `context` map)

### SttTimingSnapshot

Immutable per-utterance timing data class serialised into result JSON:

```json
{ "vadActiveMs": 1200, "utteranceMs": 3200, "captureMs": 5000,
  "silencePaddingMs": 800, "preRollMs": 100, "inferenceMs": 450,
  "totalPipelineMs": 5200 }
```

## 13) Internal Type Inventory

All types listed here are `internal` (Kotlin visibility). They must never be
imported by the app module.

### Configuration

- `SttConfig` -- internal config data class (parsed from JSON)
- `RuntimeSttConfig` -- runtime config with strategy instances
- `SttSessionConfig` -- immutable per-session config bundle
- `TtsEngineConfig` -- legacy config wrapper (deprecated, retained for nested parsing)
- `DrainMode` (enum) -- PCM drain strategy (`DRAIN_FROM_NEXT_FRAME`, `DRAIN_FROM_HEAD`)

### Start/Stop strategies (sealed + implementations)

- `StartTrigger` (sealed interface) -- `Manual`, `VadStart`, `WakeWordStart`
- `StopTrigger` (sealed interface) -- `Manual`, `AutoSilence`, `Duration`
- `StartStrategy` (interface) -- `ManualStart`, `VadStart`, `WakeWordStart`
- `StopStrategy` (interface) -- `ManualStop`, `AutoSilenceStop`, `DurationStop`
- `SttEvents` -- observable `AtomicBoolean` event flags
- `StopRequest` -- single-write-site stop signal holder

### Controllers

- `SttLifecycleController` -- lifecycle state transitions
- `SttLifecycleStateMachine` -- thread-safe state machine
- `SttLifecycleState` (sealed) -- `UNINITIALISED`, `INITIALISED`, `READY`, `RECORDING`, `FINALISING`, `STOPPED`
- `SttSessionController` -- session timing and buffer lifecycle
- `SttModeController` -- manual/auto mode selection
- `SttCaptureController` -- PCM capture start/stop/reset
- `SttProcessingController` -- auto-mode VAD/accumulator/processor construction
- `ProcessorController` -- auto-mode frame polling loop (T3)
- `MinimalPollingController` -- manual-mode frame polling loop (T3)
- `SttInferenceController` -- inference submission and dispatch adaptation
- `SttPipelineState` -- deterministic pipeline stage transitions
- `SttPipelineStage` (enum) -- `IDLE`, `CAPTURING`, `FINALISING`, `INFERENCING`, `DISPATCHING`
- `PollingController` (interface) -- common contract for T3 controllers
- `SttThreadController` -- thread management utility (not actively wired)

### Capture

- `AudioCapture` -- microphone PCM reader (T1)
- `CaptureManager` -- session PCM buffer and drain manager (T2/T3)
- `SessionManager` (interface) -- contract for PCM session management
- `AudioSource` (interface) -- contract for microphone PCM sourcing

### VAD and accumulation

- `Vad` -- energy-based voice activity detection with hysteresis
- `VadConfig` -- VAD configuration (retained for legacy)
- `VadGate` -- lightweight stateless energy gate (manual mode)
- `UtteranceAccumulator` -- pre-roll + speech + silence boundary management
- `UtteranceListener` (interface) -- utterance-ready event contract
- `FrameResult` (sealed) -- `Continue`, `UtteranceReady`
- `RmsSampler` -- RMS computation and logging utility

### Callback and error

- `SttCallbackDispatcher` -- callback dispatch to registered listeners
- `SttError` -- structured error data class
- `SttErrorCode` (enum) -- error code enumeration
- `SttErrorCategory` (enum) -- high-level error categories
- `SttErrorListener` (fun interface) -- structured error callback
- `SttReadyListener` (fun interface) -- readiness callback (retained)
- `SttTimingSnapshot` -- immutable per-utterance timing data class
- `ProcessingListener` (fun interface) -- utterance-ready callback for processing pipeline

### Model

- `WhisperModel` (interface) -- Whisper model contract
- `WhisperBridge` (internal object) -- JNI bridge implementation
- `ModelManager` -- model lifecycle + inference executor

### JSON

- `SttJsonAdapter` -- JSON serialisation/deserialisation

### Logging and utilities

- `SttLogger` -- module-level logging utility
- `SttReturnCode` (enum) -- return codes for utterance status

## 14) Build-Time API Governance

A custom Gradle task `checkSttApiSurface` runs as part of the `check` lifecycle.
It enforces:

1. **Only `SpeechToText` is `public`.** Any other public top-level type triggers
   a build failure with a detailed mismatch report.
2. **`WhisperBridge` is `internal`** and exposes exactly the expected JNI signatures:
   - `loadModel(modelPath: String)`
   - `transcribe(samples: ShortArray): String`
   - `unloadModel()`
   - No legacy `init` handle-based API

### How it works

```
Gradle check task
    |
    +-- checkSttApiSurface
        |
        +-- Scan all .kt files in src/main/java/dev/barrycade/voicecore/stt/
        +-- Regex: find public top-level type declarations
        +-- Compare against expectedPublicApiTypes = { "SpeechToText" }
        +-- Report missing / unexpected types as build error
        +-- Verify WhisperBridge source content
```

## 15) Testing Strategy

### Unit tests (JUnit 4, no Android dependency)

| Test file | Focus |
|-----------|-------|
| `SpeechToTextNewApiTest` | Two-phase lifecycle (`loadModel` + `startSession`), epoch rejection, JSON boundary, mode integration |
| `SttPipelineSequencingTest` | `startSession` + `transcribe` sequencing, state machine compliance |
| `SttPipelineBehaviourTest` | Pipeline stage transitions under various scenarios |
| `SttPipelineStateTest` | Legal/illegal pipeline stage transitions |
| `SttInferenceControllerTest` | Inference submission, dispatch, timing snapshot |
| `SttCallbackDispatcherTest` | Callback dispatch, listener safety,

| `SttErrorDeliveryTest` | Error dispatch through all listener types |
| `ModelManagerTest` | Model lifecycle, forced failure, executor shutdown |
| `ProcessorControllerTest` | Processing loop, VAD, accumulator integration |
| `UtteranceAccumulatorTest` | Pre-roll, speech detection, silence timeout, force timeout |
| `VadTest` | Energy threshold, hysteresis, confidence, duration tracking |
| `VadGateTest` | Stateless energy-gate, Manual mode VAD |
| `SttSessionControllerTest` | Session/inference/PCM timing, reset |
| `RuntimeSttConfigTest` | JSON parsing, strategy instantiation |
| `StrategyCombinationTest` | Mode detection, strategy selection |
| `WarmupInvocationTest` | Warm-up integration with model lifecycle |

### Fake implementations

- `FakeWhisperModel` -- deterministic test double for Whisper model
- `FakeCaptureManager` -- in-memory PCM buffer with configurable frame delivery
- `FakeAudioCapture` -- deterministic PCM frame source

### Testing principles

- All unit tests run without Android dependencies (JUnit 4 + `Robolectric` not required)
- Fakes implement the same interfaces as production (`WhisperModel`, `SessionManager`, `AudioSource`)
- `FakeWhisperModel.transcribe()` returns recognisable text derived from PCM properties
- Timing-sensitive tests use controlled wall-clock simulation rather than real time
- Epoch-based tests verify stale callback rejection by creating overlapping sessions

---
