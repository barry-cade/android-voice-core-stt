# STT Module Architecture

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
  ──────────                    ──────────────              ──────────
  MainActivity.kt               SttJsonAdapter              SpeechToText (internal)
      │                              │                           │
      ├─ init(configJson) ──────────►│──► SttConfig              │
      │                              │                           │
      │                              │    Session config ───────►│
      │                              │    Runtime config         │
      │                              │    Strategies             │
      │                              │                           │
      │◄─── "result" / "error" ──────┤◄── dispatchResult/json    │
      │                              │    dispatchError/json     │
      │                              │                           │
```

## 2) Sealed Internal Architecture

All STT internal types are `internal` (Kotlin visibility modifier). The API surface
enforcement task (`checkSttApiSurface`) verifies at build time that only the approved
public type (`SpeechToText`) is visible.

### Visibility rules

| Visibility | Types |
|------------|-------|
| `public` | `SpeechToText` only |
| `public` top-level function | `SpeechToText(context: Context?): SpeechToText` |
| `internal` (default) | All controllers, managers, configs, strategies, events, states |

## 3) Boundary Layer: SttJsonAdapter

`SttJsonAdapter` is the **single component** that translates between JSON strings
and internal Kotlin types.

### Input path

```
JSON string ──► SttJsonAdapter.parseConfig() ──► SttConfig
                                                    │
                                                    ▼
                                             SttSessionConfig.from()
                                                    │
                                                    ▼
                                             RuntimeSttConfig
                                               (strategy instances)
```

### Output path

```
Internal types ──► SttJsonAdapter.buildResultJson()
                  SttJsonAdapter.buildErrorJson()
                  SttJsonAdapter.buildDebugJson()
                              │
                              ▼
                         JSON string ──► setOnMessageListener()
```

### Key properties

- Manual JSON parsing (regex-based) — no `org.json` dependency, works in unit tests
- Supports both flat format (preferred) and legacy nested format
- Validates required fields, returns descriptive error messages
- Escapes JSON string values correctly

## 4) Pipeline (High-Level)

```
┌──────────┐    ┌────────────┐    ┌──────────┐    ┌────────────┐    ┌───────────┐
│ Audio    │───►│ Capture    │───►│ VAD /    │───►│ Whisper    │───►│ Callback  │
│ Capture  │    │ Manager    │    │ Accum.   │    │ Model      │    │ Dispatcher│
└──────────┘    └────────────┘    └──────────┘    └────────────┘    └───────────┘
     │               │                │                │                │
  T1: Audio      T2: Drain       T3: Processor    T4: Whisper       Caller
  Capture       Thread          Thread            Executor          thread
  Thread
```

### Pipeline stages

| Stage | Description |
|-------|-------------|
| `IDLE` | No session active |
| `CAPTURING` | PCM capture + buffering active |
| `INFERENCING` | Whisper inference running |
| `DISPATCHING` | Result being dispatched to listener |
| `FINALISING` | Session stopping, resources released |

### Mode-dependent paths

**Manual mode** (Manual start, Manual stop):
1. `init()` → load model → start PCM capture
2. PCM frames buffered in CaptureManager session buffer
3. `transcribe()` → stop capture → finalise PCM → submit inference
4. Result dispatched via listener

**Auto mode** (Manual start, Auto silence stop):
1. `init()` → load model → start PCM capture + VAD + processor
2. Processor thread polls frames, runs VAD, feeds UtteranceAccumulator
3. Silence detected → UtteranceAccumulator emits `UtteranceReady`
4. Inference submitted → result dispatched
5. Automatic return to IDLE for next utterance

## 5) Threading Model

| Thread | Owns | Notes |
|--------|------|-------|
| Caller thread | Public lifecycle methods (`init`, `transcribe`) | Serialised via `stateLock` |
| Audio capture (T1) | `AudioRecord` reads, PCM frame enqueue | Guarded by `AudioCapture.stateLock` for start/stop |
| Capture drain (T2) | Warm-up PCM buffering into session buffer | Guarded by `CaptureManager.sessionBufferLock` |
| Processor (T3) | VAD, utterance accumulation, PCM polling | Started/stopped via `ProcessorController` |
| Whisper executor (T4) | Model load/unload/transcribe | Single-thread executor in `ModelManager` |

### Lock boundaries

- `stateLock` guards ALL public lifecycle methods end-to-end
- `stateLock` is NOT held across blocking operations (thread joins, native inference, executor shutdown)
- Blocking operations are performed OUTSIDE `stateLock`
- Callbacks are delivered on the Whisper executor thread, never on the caller thread

## 6) Lock Model

| Lock | Held By | Protects | Notes |
|------|---------|----------|-------|
| `SpeechToText.stateLock` | Caller thread | Lifecycle state, pipeline stage transitions, `isRunning`, `isInferencing`, controller references | Re-entrant? No — serialised externally |
| `CaptureManager.stateLock` | Caller thread | `captureStarted`, `draining`, `sttActive`, `drainThread`, `currentDrainMode` | Short-duration |
| `CaptureManager.sessionBufferLock` | Drain/processor threads | `sessionBuffer` (mutable list) | Never held together with `stateLock` |
| `AudioCapture.stateLock` | Audio capture thread | AudioRecord lifecycle | Internal to AudioCapture |
| `SttCallbackDispatcher.listenerLock` | Any | Listener references | Synchronized block |
| `SttLifecycleStateMachine` internal lock | Any | State transitions | Kotlin `synchronized` |
| `ModelManager.stateLock` | Caller thread | `modelPath` | Short-duration |
| C++ `g_mutex` | Whisper executor | Global whisper context | `std::mutex` in `whisper_bridge.cpp` |

## 7) Epoch Model (Stale Callback Rejection)

- `sessionEpoch` is an `AtomicLong` incremented on each session start
- `currentSessionEpoch` is snapshotted at inference submission
- Callbacks whose epoch does not match `currentSessionEpoch` are dropped
- This prevents stale results from:
  - Out-of-order sessions
  - Rapid start/stop cycles
  - Late-arriving inference results from previous sessions

## 8) Internal Types (Not Part of Public API)

The following types are `internal` and should never be imported by the app module:

### Controllers
- `SttLifecycleController` — lifecycle state transitions
- `SttSessionController` — session timing and buffer lifecycle
- `SttModeController` — manual/auto mode selection
- `SttCaptureController` — PCM capture start/stop/reset
- `SttProcessingController` — processor/VAD/accumulator construction
- `SttInferenceController` — inference submission and dispatch
- `ProcessorController` — auto-mode frame polling loop
- `SttThreadController` — thread management utility (currently not actively wired)
- `SttPipelineState` — deterministic pipeline stage transitions

### Configuration
- `SttConfig` — internal config data class (parsed from JSON)
- `RuntimeSttConfig` — runtime config with strategy instances
- `SttSessionConfig` — immutable per-session config bundle
- `SttRunConfig` — legacy run config (deprecated)
- `SttRunConfigValidator` — legacy config validator
- `SttReturnCode` — return codes
- `ReturnCodeMapper` — legacy return code mapping
- `StartTrigger` (sealed) — start strategy types
- `StopTrigger` (sealed) — stop strategy types
- `DrainMode` — PCM drain strategy enum

### Capture
- `AudioCapture` — microphone PCM reader
- `CaptureManager` — session PCM buffer and drain manager
- `SessionManager` — interface for PCM session management
- `AudioSource` — base interface for audio sources

### VAD and Accumulation
- `Vad` — energy-based voice activity detection
- `VadConfig` — VAD configuration
- `UtteranceAccumulator` — utterance boundary management
- `UtteranceListener` — interface for utterance-ready events
- `FrameResult` — sealed result from accumulator
- `RmsSampler` — RMS computation utility

### Strategies
- `StartStrategy` (interface) — start strategy contract
- `ManualStart` — manual start strategy
- `VadStart` — VAD-based start strategy
- `WakeWordStart` — wake word start strategy
- `StopStrategy` (interface) — stop strategy contract
- `ManualStop` — manual stop strategy
- `AutoSilenceStop` — auto-silence stop strategy
- `DurationStop` — timed stop strategy
- `StopRequest` — single-write-site stop signal holder

### Events
- `SttEvents` — observable event flags for strategies
- `EventFlag` — AtomicBoolean-based one-shot event

### Callback and Error
- `SttCallbackDispatcher` — callback dispatch to registered listeners
- `SttError` — structured error data class
- `SttErrorCode` — error code enum
- `SttErrorCategory` — error category enum
- `SttErrorListener` — fun interface for error callbacks
- `SttReadyListener` — fun interface for readiness callbacks
- `SttTimingSnapshot` — timing diagnostics data class

### Lifecycle
- `SttLifecycleState` (sealed) — lifecycle states
- `SttLifecycleStateMachine` — thread-safe state transitions

### Pipeline
- `SttPipelineStage` (enum) — deterministic pipeline stages
- `SttInferenceController.InferenceRequest` — inference request data class
- `SttInferenceController.DispatchDecision` — dispatch permission data class

### Model
- `WhisperModel` (interface) — Whisper model contract
- `WhisperBridge` (internal object) — JNI bridge implementation
- `ModelManager` — model lifecycle + inference executor

### JSON
- `SttJsonAdapter` — JSON serialisation/deserialisation

### Logging
- `SttLogger` — module-level logging utility

## 9) Summary of Removed API

The following methods were deprecated and removed during the JSON boundary refactor:

| Old Method | Replacement |
|------------|-------------|
| `setConfig(SttConfig)` | `init(configJson: String)` |
| `initStt(SttConfig)` | `init(configJson: String)` |
| `startSession()` | `init()` (starts capture automatically) |
| `stopAndTranscribe()` | `transcribe()` |
| `resetForNextSession()` | Handled internally by `transcribe()` |
| `destroy()` | Handled via lifecycle controller (no public API) |
| `setDebugOptions()` | Removed (test hooks internal) |
| `setOnResultListener()` | `setOnMessageListener(l: (String) -> Unit)` |
| `setOnResultWithTimingListener()` | `setOnMessageListener(l: (String) -> Unit)` |
| `setOnErrorListener()` | `setOnMessageListener(l: (String) -> Unit)` |
| `setSttErrorListener()` | `setOnMessageListener(l: (String) -> Unit)` |
| `setReadyListener()` | Removed (no readiness callback needed) |

## 10) Sealed Boundary Confirmation

The JSON boundary between the app module and the STT module is **fully sealed**:

- ✅ No `internal` type leaks into public method signatures
- ✅ `checkSttApiSurface` verifies only `SpeechToText` is public
- ✅ All data crosses the boundary as JSON strings
- ✅ `SttJsonAdapter` is the sole JSON handler
- ✅ No Kotlin type coupling between app and STT internals
- ✅ Build-time API surface enforcement
- ✅ Unit tests confirm message format and parsing
