# STT Behavioural Contract (PDP-Aligned)

The authoritative behavioural specification for the Speech-To-Text pipeline.

---

## 1. System Purpose

The STT system converts live microphone audio into text using Whisper via a
**push-to-talk** interaction model:

- User presses **Start** -> microphone opens -> user speaks
- User presses **Stop** -> microphone closes -> utterance is transcribed -> text is returned

The system must behave deterministically, with predictable lifecycle transitions,
stable timing, and no hidden concurrency.

The internal pipeline is:

> PCM -> VAD -> UtteranceAccumulator -> Whisper -> transcript

The system is **stop-triggered**: transcription happens only when the user (or
caller) explicitly invokes `stopAndTranscribe()`. No streaming partial results
are emitted.

---

## 2. Lifecycle States

The STT system has four valid states:

```text
UNINITIALISED --> READY --> RECORDING --> FINALISING
                     ^                                    |
                     +------------------------------------+
```

### UNINITIALISED

- Model not loaded
- Warm-up not performed
- No capture allowed
- No inference allowed

### READY

- Model loaded
- Warm-up completed
- System prepared to begin recording
- Capture may begin only in this state

### RECORDING

- Microphone active
- PCM frames flowing
- VAD accumulating utterance
- STOP may be invoked

### FINALISING

- Capture stopped
- PCM finalised
- Whisper inference running
- Transition back to READY after inference

### Legal transition matrix

| From | To | Notes |
| --- | --- | --- |
| UNINITIALISED | READY | Model load + warm-up complete |
| READY | RECORDING | `start()` succeeds |
| RECORDING | FINALISING | `stop()` / `stopAndTranscribe()` invoked |
| FINALISING | READY | Inference complete, text dispatched |

Any transition not listed above is **illegal** and must produce an
`SttError` with code `PIPELINE_ILLEGAL_STATE`.

---

## 3. Warm-Up Behaviour

Warm-up is a mandatory, one-time operation per model load.

### Warm-up must

- run before the system enters READY
- run without producing user-visible output
- run without blocking the UI thread
- produce a dummy inference (`[BLANK_AUDIO]`, 3200 samples of silence at 16kHz)
  only for timing calibration
- set `isReady = true` only after completion

### Warm-up must never

- enter RECORDING
- interfere with STOP
- interfere with VAD
- interfere with user audio

### Warm-up behaviour when `start()` was called early (queued start)

- If `start()` was called before READY, `AudioCapture` **may** be started early
  (during warm-up) to buffer PCM frames. This is an intentional optimisation
  that reduces the latency between READY and the first available PCM.
- The warm-up inference (`[BLANK_AUDIO]`) completes independently of any PCM
  buffering -- the buffered frames are **not** fed to Whisper during warm-up.
- After warm-up completes, the buffered PCM becomes available to the
  `ProcessorController` when `start()` is replayed from the READY callback.

---

## 4. Start Behaviour

`start()` means "begin recording now, or queue until READY."

### Start must

- only transition READY -> RECORDING when the system is in READY
- start microphone capture
- begin PCM flow
- activate VAD
- prepare utterance accumulation

### Queued start (early call behaviour)

If `start()` is called while the system is UNINITIALISED or before
`modelManager.isReady` is true, the system must:

- **queue** the start request (`startRequested = true`)
- optionally begin PCM buffering (AudioCapture) immediately so that audio is
  available the moment READY fires
- remain stable
- log the reason (`"start() called early -- queued until READY"`)
- replay the start automatically when the internal `onSttReady` callback fires

This means the user **does not need to press Start again** -- the system
remembers the intent and executes it as soon as it is able.

### Start must never

- run during FINALISING *(returns early with a log warning)*
- run while already running (`isRunning == true`) *(idempotent -- returns early)*
- crash or throw if called at the wrong time *(always returns gracefully)*

---

## 5. STOP Behaviour

STOP means finalise the current utterance and produce text.

There are two public entry points:

- `stop()` -- delegates to `stopAndTranscribe()`
- `stopAndTranscribe()` -- the canonical implementation

### STOP must

- stop microphone capture
- drain all remaining PCM frames from the capture queue into the accumulator
- finalise the utterance (applying stabilisation rules)
- run Whisper inference on the final PCM
- return text via `onResult` / `onResultWithTiming` callbacks
- transition RECORDING -> FINALISING -> READY

### STOP may produce no result if

- no PCM was accumulated (call before any recording) -- warns and returns
- the transcribed text is blank (`text.isNotBlank()` guard) -- suppresses dispatch

### Queued stop

If `stop()` / `stopAndTranscribe()` is called before recording has started
(i.e. `isRunning` is false), the system must:

- set `stopRequested = true`
- remain stable
- log `"[STOP] queued -- recording not started yet"`
- execute the full stop path as soon as recording begins

When the queued start replays and enters RECORDING, the processor starts,
processes buffered frames, then immediately executes the queued stop -- this
allows any frames buffered during warm-up to be transcribed.

### STOP must never

- run during UNINITIALISED
- run during READY
- run during warm-up
- run during model load
- crash or throw under any condition

---

## 6. PCM Flow Behaviour

PCM must only flow during RECORDING.

### PCM must never flow

- during UNINITIALISED
- during READY
- during FINALISING

*Exception: when `start()` is called early and AudioCapture begins buffering
during warm-up, PCM frames are enqueued into the capture queue but are **not
processed** by VAD or the accumulator until the explicit `start()` path
executes during RECORDING.*

### PCM must be

- continuous
- timestamp-aligned (wall-clock times recorded at `start()` and `stop()`)
- thread-safe (`ConcurrentLinkedQueue`)
- non-blocking (dedicated audio thread, separate processor thread)

### PCM frame properties

- Format: PCM 16-bit mono, 16kHz sample rate
- Delivered as `FloatArray` (normalised to `[-1.0, 1.0]`)
- Buffer size: controlled by `requestedBufferSizeInBytes` (default 32000 bytes)

---

## 7. VAD Behaviour

VAD is a pure RMS-energy voice activity detector. It operates on `FloatArray`
frames and returns a boolean speech-vs-silence decision per frame.

### VAD must

- operate only on PCM frames during RECORDING
- use configurable `energyThreshold` (default `0.03`)
- apply hysteresis (30% threshold reduction once speech is detected) to prevent
  flickering at speech boundaries
- track `vadConfidence` (diagnostic only, `0.0..1.0`) derived from energy
  proximity and consecutive speech frame count
- accumulate speech duration (`vadActiveMs`) for timing diagnostics

### VAD must never

- run during warm-up
- run during model load
- run during READY
- run during FINALISING
- interact with Whisper or audio capture directly

### VAD does NOT

- apply high-pass filtering
- use zero-crossing rate
- implement any form of noise suppression

---

## 8. Utterance Accumulation Behaviour

The `UtteranceAccumulator` transforms incoming `FloatArray` frames into
complete utterance buffers with deterministic latency-stabilisation.

### Pre-roll rule (100ms)

- The first 100ms of PCM is appended to the utterance buffer unconditionally.
- Speech detection is disabled during pre-roll (VAD results are ignored).
- This ensures `STOP` always has real audio to finalise, even with large
  AudioCapture frame sizes.

### Trailing silence (250ms)

- After STOP or VAD finalisation, 250ms of synthetic silence is appended to
  the utterance PCM.
- Always present, always identical, always mel-light (zero amplitude).

### Minimum utterance length (700ms)

- If utterance duration < 700ms after trailing silence, pad with silence until
  total duration = 700ms.
- This guarantees a deterministic mel-shape for short commands regardless of
  STOP timing.

### Max utterance length (7000ms default)

- When total duration exceeds `maxUtteranceLengthMs`, the utterance is
  automatically finalised.
- An `SttError` with code `INTERNAL_EXCEPTION` is emitted to the error
  listener, but the utterance is still dispatched for transcription.

### VAD-triggered finalisation

- When VAD detects silence for `silencePaddingMs` (default 600ms) **and** the
  minimum utterance length (700ms) has been met, the utterance is automatically
  finalised via the `UtteranceListener` callback.
- The silence padding check is enforced only after minimum length is satisfied.

### Force finalisation

`forceFinalize()` returns all buffered PCM even if VAD never fired. Returns
null only if no frames have ever been buffered.

### State reset

`resetForNextUtterance()` clears all state -- accumulator buffer, speech
active flag, silence counter, pre-roll flags. Used for streaming mode.

---

## 9. Stabilisation Summary

| Rule | Value | Purpose |
| --- | --- | --- |
| Pre-roll | 100ms | Prevent empty PCM on early STOP |
| Trailing silence | 250ms | Mel-light padding for Whisper |
| Minimum utterance | 700ms | Deterministic mel-shape for short commands |
| Silence threshold | configurable (default 600ms) | VAD silence finalisation |
| Max utterance | configurable (default 7000ms) | Hard upper bound on recording |

---

## 10. PCM Drain at STOP

When `stopAndTranscribe()` is invoked, the system performs a deterministic
drain sequence:

1. Set `isRunning = false`
2. Transition RECORDING -> FINALISING
3. Stop the `ProcessorController` worker thread
4. Set `stopRequested = true`
5. **Drain remaining frames**: poll the `AudioCapture` frame queue directly and
   feed each frame through VAD and the accumulator explicitly
6. Call `stopAndFinalize()` on the processor to retrieve the final PCM buffer
7. Stop AudioCapture (`AudioCapture.stop()`)
8. Run Whisper inference on the final PCM
9. Dispatch the result
10. Transition FINALISING -> READY

This ensures that frames still in the queue when STOP fires are not lost -- they
are drained and included in the final utterance.

---

## 11. Timing Instrumentation

The system captures timing snapshots for every utterance.

### Timing values captured

| Metric | Source | Description |
| --- | --- | --- |
| `pcmMs` | Wall clock | Total PCM capture duration (start -> stop) |
| `vadActiveMs` | Accumulated per-frame | Cumulative duration of VAD-detected speech |
| `utteranceMs` | Accumulator | Duration of the stabilised utterance PCM |
| `whisperMs` | Wall clock | Whisper inference duration |
| `totalMs` | Wall clock | User-perceived duration (utterance start -> result) |

### Timing delivery

- `onTimingCallback` receives a `SttTiming` object on each utterance
- `onTimingListener` receives raw `(pcmMs, vadActiveMs, whisperMs, totalMs)` on each utterance
- Timing is also embedded in `SttError.context` when errors occur

### Warm-up timing

- `warmUpMs` is logged after the dummy inference completes

---

## 12. Error Classification

All errors are reported via `SttErrorListener` with a structured `SttError` object.

### Error categories (`SttErrorCategory`)

- `UNKNOWN` -- catch-all

### Error codes (`SttErrorCode`)

| Code | Meaning |
| --- | --- |
| `MODEL_LOAD_FAILED` | Whisper model could not be loaded |
| `INFERENCE_FAILED` | Whisper transcription failed |
| `PIPELINE_ILLEGAL_STATE` | Illegal lifecycle transition attempted |
| `INTERNAL_EXCEPTION` | Catch-all for unexpected errors (e.g., max utterance timeout) |

### Error payload

Each `SttError` carries:

- `category` -- `SttErrorCategory`
- `code` -- `SttErrorCode`
- `message` -- human-readable description
- `cause` -- optional `Throwable`
- `lastRms` -- optional RMS energy at time of error (diagnostic)
- `lastVadState` -- optional VAD state at time of error (diagnostic)
- `timingSnapshotMs` -- optional timing map `(pcmMs, vadActiveMs, whisperMs, totalMs)`
- `context` -- optional `Map<String, Any>` for additional diagnostic data

---

## 13. Concurrency Guarantees

The system must guarantee:

| Concern | Mechanism |
| --- | --- |
| Model load | Async on `whisperExecutor` (single-thread) |
| Warm-up | Async on `whisperExecutor` |
| Inference | Synchronous on the calling thread (blocking by design -- caller is NOT the UI thread) |
| Capture | Threaded (`AudioCaptureThread`, `THREAD_PRIORITY_AUDIO`) |
| Processor (VAD + accumulation) | Threaded (`ProcessorControllerThread`) |
| Lifecycle transitions | `synchronized(stateLock)` -- atomic |
| UI-thread blocking | Never -- all callbacks are dispatched from worker threads; UI updates are the caller's responsibility |

### Thread model diagram

```text
UI Thread
  +-> start() / stopAndTranscribe()
       +-> synchronized(stateLock)
            +-> AudioCaptureThread (PCM enqueue)
                 +-> ProcessorControllerThread (VAD + accumulation)
                      +-> Whisper inference (on processor or calling thread)
                           +-> Callback dispatch (onResult, onError)
```

---

## 14. User Experience Contract

From the user's perspective:

### Happy path

1. User presses **Start**
2. If STT is READY -> recording begins immediately
3. If STT is not READY -> Start is queued; recording begins automatically as
   soon as the model is ready (no second button press needed)
4. User speaks
5. User presses **Stop**
6. Text is returned

### What the user never sees

- No blank transcript for utterances that were too short (700ms minimum
  enforcement prevents empty Whisper inputs)
- No [BLANK_AUDIO] output -- the warm-up dummy inference is internal only
- No freezes -- all blocking work happens off the UI thread
- No clipping -- pre-roll ensures the start of speech is always captured

### Error states

- If model load fails -> `SttError(MODEL_LOAD_FAILED)` -> error listener fires
- If inference fails -> `SttError(INFERENCE_FAILED)` -> error listener fires
- If an illegal transition is attempted -> `SttError(PIPELINE_ILLEGAL_STATE)`
- If max utterance length exceeded -> utterance is finalised and transcribed;
  an `SttError(INTERNAL_EXCEPTION)` is emitted as informational

---

## 15. Deterministic Timing

The system must guarantee:

- warm-up duration is measured and logged
- inference duration is measured and delivered via timing callbacks
- PCM frame timing is stable (dedicated audio thread, fixed buffer size)
- STOP finalisation timing is stable (drain loop bounded by queue depth)
- no main-thread stalls (all blocking operations on background threads)

### Performance targets (informational)

| Phase | Target | Measurement point |
| --- | --- | --- |
| Model load | < 3s | Executor submit -> isReady |
| Warm-up inference | < 500ms | `whisperModel.transcribe(WARMUP_PCM)` |
| Utterance transcription | < 500ms per utterance | `transcribe(samples)` wall time |
| STOP drain | < 50ms | Frame queue drain loop |
| Total round-trip | < 1s after STOP (typical) | STOP press -> result callback |

---

## 16. Public API Surface

Only the following types are part of the **public API** and may be consumed by
external modules:

| Type | Purpose |
| --- | --- |
| `SpeechToText` | Orchestrator -- `start()`, `stop()`, `stopAndTranscribe()`, `destroy()`, `create()` factory |
| `SttConfig` | Configuration data class for app-level settings |
| `AudioCapture` | Android AudioRecord wrapper for microphone PCM capture |
| `WhisperBridge` | JNI bridge to native Whisper (`loadModel`, `transcribe`, `unloadModel`) |

### Public callback interfaces

- `SttReadyListener` -- notified when the system enters READY
- `SttErrorListener` -- notified when an error occurs
- `SttError` / `SttErrorCategory` / `SttErrorCode` -- structured error payloads

### Everything else is `internal`

---

## 17. Testing Philosophy

The test suite validates the contract at multiple levels:

| Test layer | What it tests | Key files |
| --- | --- | --- |
| State transitions | Every legal and illegal transition | `SttLifecycleStateTest.kt` |
| Deterministic PCM | Pre-roll, trailing silence, minimum length enforcement | `SttDeterministicTest.kt` |
| Orchestration | Start/stop gates, queued start/stop, destroy | `SpeechToTextTest.kt` |
| Stop path | Drain behaviour, finalisation | `SttStopPathTest.kt` |
| Warm-up | Async completion, isReady flag | `SttWarmupTest.kt` |
| Error codes | Every error path | `SttErrorCodeTest.kt` |
| Timing | Snapshot delivery | `SttTimingSnapshotTest.kt` |
| VAD | Frame-level speech detection | `VadTest.kt` |
| Accumulator | Buffer management, stabilisation | `UtteranceAccumulatorTest.kt` |
| Smoke | Public API surface | `PublicApiSmokeTest.kt` |
| Config | Validation rules | `RuntimeSttConfigTest.kt` |

---

## 18. Configuration Parameters

Parameters exposed via `RuntimeSttConfig` (internal) / `SttConfig` (public):

| Parameter | Default | Range | Description |
| --- | --- | --- | --- |
| `energyThreshold` | 0.03 | [0.0001, 1.0] | VAD RMS energy threshold |
| `silencePaddingMs` | 600 | [50, 5000] | Silence duration before VAD finalisation |
| `preRollMs` | 100 | [0, 2000] | Pre-roll window before speech detection |
| `maxUtteranceLengthMs` | 7000 | [1000, 20000] | Hard max utterance duration |
| `stableChunkSizeMs` | 500 | [50, 2000] | Stable block alignment for Whisper |
| `motionMode.energyThreshold` | 0.05 | [0.0001, 1.0] | Energy threshold in motion mode |
| `motionMode.silencePaddingMs` | 300 | [50, 5000] | Silence padding in motion mode |

---

## 19. Motion Mode

Motion mode is an alternative VAD configuration intended for scenarios where
the microphone or robot is in motion (higher ambient noise, variable
orientation).

- Higher default energy threshold (0.05 vs 0.03)
- Shorter default silence padding (300ms vs 600ms)
- Selected by setting `config.motionMode` values at construction time

Motion mode does **not** change any lifecycle, PCM flow, or stabilisation
behaviour -- only VAD sensitivity parameters.

---

## 20. Debug Hooks

For integration testing, the following debug options are available via
`setDebugOptions()`:

| Flag | Effect |
| --- | --- |
| `forceAudioInitFailure` | Causes `start()` to emit an error and return before AudioCapture starts |
| `forceWhisperLoadFailure` | Causes model load to fail with `MODEL_LOAD_FAILED` |
| `forceTimeout` | Causes `UtteranceAccumulator` to emit `PIPELINE_ILLEGAL_STATE` on first speech frame |

All hooks require `setDebugOptions()` to have been called **before** `start()`.

---

*End of Behavioural Contract*
