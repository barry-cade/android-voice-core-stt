# STT Thread Architecture — Deep Dive & Living Plan

> **Purpose:** Single source of truth for the threading model across chat sessions.
> When you start a fresh session, read this file first to refamiliarise yourself
> with the architecture, known issues, and planned work.
>
> **Status:** Last reviewed [2025-07-18] — **I2, I3, I8, O2 fixed**. Next review: after any threading change.
>
> **Authoritative references:**
> - `SpeechToText.kt` — top-level orchestrator
> - `ProcessorController.kt` — VAD + accumulator worker
> - `AudioCapture.kt` — microphone thread
> - `ModelManager.kt` — Whisper executor
> - `whisper_bridge.cpp` — native mutex

---

## 1. Thread Map

| # | Thread / Executor | Created In | Purpose | Priority | Daemon? |
|---|---|---|---|---|---|
| T1 | **AudioCaptureThread** | `AudioCapture.start()` | Reads PCM from `AudioRecord`, enqueues `FloatArray` frames into `ConcurrentLinkedQueue` | Real-time audio | ❌ Normal |
| T2 | **ProcessorControllerThread** | `ProcessorController.start()` | Polls frames from queue, runs VAD + `UtteranceAccumulator`, delivers utterances to whisper executor | Pipeline processing | ❌ Normal |
| T3 | **WhisperExecutor** (single-thread) | `ModelManager` init | Model load, warm-up, `transcribe()` calls, inference dispatch and callbacks | Serialised Whisper access + result dispatch | ✅ Daemon |
| T4 | **Main / UI thread** | Android | `startSession()`, `stopAndTranscribe()`, `destroy()`, callbacks | Lifecycle control | ✅ Main |
| T5 | **StopAndTranscribeThread** (app-only) | `MainActivity.stopRecording()` | Calls `stt.stopAndTranscribe()` off main thread | App-level offload | ❌ Normal |

**Native side:** `whisper_bridge.cpp` uses a global `std::mutex g_mutex` guarding `whisper_context* g_ctx`. Redundant with Java executor (T3) — maintained as defensive belt-and-suspenders.

---

## 2. Data Flow (thread hand-offs)

```
[T1] AudioRecord → read() → short[] → float[] → offer(frameQueue)
                                                      ↓  ConcurrentLinkedQueue
[T2] pollFrame() → Vad.isSpeech() → UtteranceAccumulator.processChunk()
                                         ↓
                                   FrameResult.NormalFinalize / AutoStop / AbnormalTerminate*
                                         ↓
                                   UtteranceListener.onUtteranceReady(pcm)
                                         ↓
                                   submitInferenceToWhisperExecutor()
                                         ↓
                                   [T3] transcribe() → onResult / onResultWithTiming / onTimingListener
```

**Stop path (manual):**
```
[T4/T5] stopAndTranscribe()
    ↓ synchronized(stateLock)
  drain accumulator PCM
    ↓ (Phase 2, outside lock)
  submitInferenceToWhisperExecutor()
    ↓
  [T3] transcribe() → callbacks on whisper executor thread
```

---

## 3. Lock Hierarchy

```
stateLock (SpeechToText.kt)
  └── Guards: startRequested, audioSource, processorController,
              isRunning, stopRequested, timing fields
  └── Held during: start(), stopAndTranscribe(), resetForNextSession(), destroy()

stateMachine.lock (SttLifecycleStateMachine.kt)
  └── Guards: _currentState transitions
  └── Independent lock — never nested inside stateLock in opposite order
```

**Deadlock freedom:** ✅ Verified. The two locks are never acquired in reverse order.
The state machine lock is internal and only touched while `stateLock` is held (or standalone).

---

## 4. Shared State & Volatility

| Field | Type | Written By | Read By | Safe? | Notes |
|---|---|---|---|---|---|
| `stateMachine._currentState` | `@Volatile` | Under internal lock | Any thread | ✅ | Lock-protected write, volatile read |
| `isRunning` (STT) | `AtomicBoolean` | Any thread (CAS) | Multiple | ✅ | |
| `isInferencing` (STT) | `AtomicBoolean` | UtteranceHandler | Same | ✅ | Effectively never contended |
| `stopRequested` (STT) | `@Volatile Boolean` | Main/caller | ProcessorController | ⚠️ | Relies on happens-before via `Thread.join()` in `stop()` |
| `vadActiveMs` (PC) | `@Volatile Long` | Processor loop | After `stop()` returns | ⚠️ | Safe only because `stop()` joins the thread |
| `lastUtteranceDurationMs` (PC) | `@Volatile Int` | Processor loop | After `stop()` returns | ⚠️ | Same as above |
| `isReady` (MM) | `@Volatile Boolean` | WhisperExecutor | STT.start() | ✅ | Single-writer, single-check |
| `isRunning` (AC) | `@Volatile Boolean` | Main + capture thread | Capture loop | ✅ | |
| `frameQueue` (AC) | `ConcurrentLinkedQueue` | Capture thread | Processor thread | ✅ | Lock-free queue |

> `PC` = ProcessorController, `MM` = ModelManager, `AC` = AudioCapture

---

## 5. Known Issues

### I1. `drainQueuedFrames` reimplements processing inline
- **File:** `SpeechToText.kt` (private method)
- **Problem:** Creates temporary `Vad` + `UtteranceAccumulator` instances to drain frames queued during warm-up. Duplicates the processing logic already in `ProcessorController`.
- **Impact:** Low. Works correctly but is a maintenance burden — any change to VAD/accumulator logic must also be made here.
- **Fix:** Make `ProcessorController` support a "drain without starting the loop" mode.

### I2. Bare `catch (Throwable)` in processor loop
- **File:** `ProcessorController.kt`
- **Problem:** Every exception was swallowed silently; the loop continued.
- **Impact:** Medium. Real bugs (NPE in VAD, state corruption) were hidden.
- **Fix:** Log full stack trace, set `isRunning = false`, dispatch `onAbnormalTermination(ENGINE_ERROR)`, break.
- **Status:** ✅ Fixed 2025-07-17

### I3. Non-daemon executor thread leaks if `destroy()` not called
- **File:** `ModelManager.kt` (`whisperExecutor`)
- **Problem:** `Executors.newSingleThreadExecutor()` created a non-daemon thread. If `SpeechToText.destroy()` is not called (e.g., Activity destroyed without cleanup), the thread prevents process exit.
- **Impact:** Low in normal use (Android kills the process), but medium in testing/hot-reload scenarios.
- **Fix:** Custom `ThreadFactory` creating daemon threads.
- **Status:** ✅ Fixed 2025-07-17

### I4. `stopAndTranscribe()` Phase 1/2 split creates a window
- **File:** `SpeechToText.kt` (`stopAndTranscribe`)
- **Problem:** PCM is drained under `stateLock` (Phase 1), then inference runs outside the lock (Phase 2). Between these, another thread calling `startSession()` sees FINALISING and returns ENGINE_ERROR — correct behaviour, but surprising and fragile.
- **Impact:** Low. Works correctly but the design is non-obvious.

### I5. Unbounded queue between capture and processor
- **File:** `AudioCapture.kt` / `ProcessorController.kt`
- **Problem:** `ConcurrentLinkedQueue` has no capacity bound. If the processor thread stalls, memory grows unbounded.
- **Impact:** Low at typical durations (30s → ~$2MB), but a concern for extended sessions.
- **Fix:** Replace with bounded `LinkedBlockingQueue` and handle offer failures.

### I6. Processor loop uses polling + `Thread.sleep(10)`
- **File:** `ProcessorController.kt`
- **Problem:** 10ms sleep on every iteration even when frames are available. Adds ~10ms latency per frame (on top of ~20ms frame duration).
- **Impact:** Low for speech-to-text (not real-time). Wastes CPU during idle.
- **Fix:** Replace polling with `LinkedBlockingQueue.poll(timeout, unit)` or `take()`.

### I7. Redundant C++ mutex
- **File:** `whisper_bridge.cpp`
- **Problem:** `std::mutex g_mutex` serialises all native calls, but the Java `WhisperExecutor` (single-thread) already does this.
- **Impact:** Negligible (uncontended lock is ~ns). But adds cognitive load.
- **Fix:** Remove or document as defensive. Consider `std::recursive_mutex` if retained.

### I8. Whisper executor thread unnamed
- **File:** `ModelManager.kt`
- **Problem:** Default `Executors.newSingleThreadExecutor()` thread name was `pool-1-thread-1`.
- **Impact:** Low. Debugging annoyance.
- **Fix:** Custom `ThreadFactory` naming it `"WhisperExecutor"`.
- **Status:** ✅ Fixed 2025-07-17

---

## 6. Optimisation Candidates

### O1. Blocking queue instead of polling
Replace `ConcurrentLinkedQueue` + `Thread.sleep(10)` with `LinkedBlockingQueue` (optionally bounded).
- Removes latency floor
- Reduces CPU wake-ups
- Provides natural backpressure

### O2. Move inference dispatch to WhisperExecutor
Instead of running `runInferenceAndDispatch()` on the processor thread (delaying its cleanup), submit it to the whisper executor. The thread returns to draining faster.
- **Status:** ✅ Fixed 2025-07-18. Added `ModelManager.submitInference()` and `SpeechToText.submitInferenceAndDispatch()`. All inference dispatch now goes through the whisper executor (T3), never the processor thread (T2) or caller thread.

### O3. Remove `isInferencing` guard if streaming mode is not in use
The `AtomicBoolean` in `UtteranceHandler` is never contended because the processor loop breaks after one utterance. Dead code if streaming is not active.

### O4. Daemon thread factory for whisper executor
**Status:** ✅ Folded into I3, fixed 2025-07-17.

### O5. Named threads across all executors
Give descriptive names to all threads: `"AudioCapture"`, `"Processor"`, `"WhisperExecutor"`.
**Status:** 🟡 Progress — whisper executor named (I8). AudioCaptureThread and ProcessorControllerThread already had names.

---

## 7. Checksums / Verification Checklist

Use this list when evaluating any change to the threading model. Tick off each item.

### Safety checks

- [x] **No nested `synchronized` blocks** that acquire `stateLock` then `stateMachine.lock` in reverse order.
- [ ] **No `@Volatile` field** written by one thread and read by another without a clear happens-before edge.
- [x] **Every `Thread.join()` call** is documented with why the join is safe (no self-join, bounded timeout).
- [ ] **Every `AtomicBoolean` CAS loop** has a bounded retry or fails fast.
- [x] **Every `catch (Throwable)`** in a worker loop either breaks the loop or dispatches an error. _(Fixed I2: `ProcessorController.runProcessingLoop` now breaks and dispatches `onAbnormalTermination`.)_

### Cleanup checks

- [x] **Every executor** is shut down (via `shutdown()` + `awaitTermination()`) when the owning component is destroyed.
- [x] **Every worker thread** sets a descriptive name via `Thread(name)` constructor or `ThreadFactory`. _(AudioCaptureThread, ProcessorControllerThread, WhisperExecutor — all named.)_
- [x] **No non-daemon thread** is created without a guaranteed shutdown path (GC finalisation does not count). _(Fixed I3: whisper executor now uses daemon threads. AudioCapture and Processor threads are short-lived and explicitly joined.)_

### Correctness checks

- [ ] **`stopAndTranscribe()` Phase 1 and Phase 2** are correctly split — no state is accessed outside the lock that requires it.
- [x] **`drainRemainingFrames()`** is only called after `stopRequested` is set and the processor loop has exited.
- [ ] **`runInferenceAndDispatch()`** never accesses `processorController` fields after the processor thread is joined (the fields are stale-free only because of the join).
- [x] **Callbacks** (`onResult`, `onError`, `onTimingListener`) document delivery thread in their KDoc.

### Structural checks (PDP alignment, CATO rules)

- [x] **No nested lambdas** in threading code. All `Runnable` instances are named or assigned to a `val`.
- [x] **No scope functions inside scope functions** — avoid `also { }`, `apply { }`, `let { }` nesting in callback chains.
- [x] **No trailing lambdas** passed to functions within lambda bodies (extract to named `val` first).
- [x] **One write site per mutable field** — each `var` is assigned in exactly one place (its initialiser) or a single dedicated method.
- [x] **Explicit types at API boundaries** — all public/internal function signatures use explicit types, not inference.

---

## 8. Quick Reference: Key File Locations

| Component | File | Key threading lines |
|---|---|---|
| Top-level orchestrator | `stt/src/main/java/.../SpeechToText.kt` | `stateLock`, `stopAndTranscribe()` phase split, `UtteranceHandler` inner class |
| Processing loop | `stt/src/main/java/.../ProcessorController.kt` | `runProcessingLoop()`, `stop()`, `drainRemainingFrames()` |
| Audio capture | `stt/src/main/java/.../AudioCapture.kt` | `captureLoop()`, `stop()` join |
| Whisper lifecycle | `stt/src/main/java/.../ModelManager.kt` | `whisperExecutor`, `initAsync`, `shutdown()` |
| State machine | `stt/src/main/java/.../SttLifecycleStateMachine.kt` | Internal lock, `forceSet()` |
| JNI bridge | `stt/src/main/cpp/whisper_bridge.cpp` | `std::mutex g_mutex` |
| App consumer | `app/src/main/java/.../MainActivity.kt` | `StopAndTranscribeThread`, `runOnUiThread` |
| App test service | `app/src/main/java/.../audio/AudioTestService.kt` | Separate `AudioCapture` + poll loop |

---

> **How to update this document:**
> - After any threading change, update the relevant section(s) and tick/untick checklist items.
> - At the top, update the "Last reviewed" date.
> - If a new issue or optimisation is identified, add it to the relevant section.
> - When an issue is fixed, move it to an "Archive" section at the bottom with the date of fix.
