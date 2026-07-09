# Threading Architecture — Remediation Plan

Intent + Ordered Phases with Verification Checks

---

## Intent

The STT pipeline's thread management has evolved organically and contains several
design smells that make correctness reasoning difficult under peer review. The
code works in practice, but the concurrency model is not provably correct.

This plan fixes the threading architecture so that:

- **Thread ownership is explicit** — every mutable field is written from exactly
  one thread context, or is guarded by a named lock.
- **Lifecycle transitions are atomic** — the state machine enforces its own lock
  discipline internally, not by convention.
- **Model lifecycle is hoisted** — no per-session thread-in-thread nesting. The
  model stays warm across sessions, eliminating the warm-up cancellation problem.
- **Lock scope is minimal** — no lock is held across executor submissions or
  blocking C++ calls.
- **Thread affinity is documented** — callers know which thread delivers their
  callbacks.

---

## Phases (Ordered by Dependency)

Each phase is safe to apply independently. Verification checks must pass before
declaring a phase complete.

---

### Phase 0 — Pre-Conditions (Must Be True Before Starting)

- [ ] `./gradlew :stt:test` passes
- [ ] `SpeechToText` is the only public API entry point (legacy paths removed)
- [ ] The config split (Phase 2 of prior refactor) is complete — `RuntimeSttConfig`
      routes behaviour through strategy-specific fields
- [ ] The output contract collapse (Phase 5 of prior refactor) is complete —
      `SessionResult` is `data class SessionResult(code, transcript?)`

---

### Phase 1 — Hoist ModelManager, Remove Inner STT

**Goal**: `ModelManager` is created once in the outer `SpeechToText` constructor.
No per-session inner `SpeechToText` instance. Model stays loaded and warm across
sessions.

**Rationale**: The current `startSessionInternal()` creates a second `SpeechToText`
instance containing its own `ModelManager`, its own `whisperExecutor` thread pool,
and its own `stateLock`. This means:

- Model is reloaded and re-warmed every session (~4s wasted per utterance)
- Thread-in-thread nesting makes `destroy()` and `activeSession` routing fragile
- Two `stateLock` objects exist concurrently, each protecting overlapping state

**Design**:

```text
Before:    Outer STT ──> startSessionInternal() ──> Inner STT (own ModelManager, own threads)
                                                          │
                                                    stopAndTranscribe routes through inner
                                                          │
                                                    inner STT is GC'd after session

After:     SpeechToText (single instance, ModelManager in init)
                │
           resetForNextSession() ──> clears session state (audioSource, processorController)
           startSession()        ──> reuses warmed model, starts new capture
           stopAndTranscribe()   ──> stops capture, runs inference, dispatches result
           destroy()             ──> unloads model, shuts down executor (once)
```

**Changes**:

- `SpeechToText`:
  - Remove `activeSession` field
  - Remove `startSessionInternal()` — inline its logic into `startSession()`
  - Add `resetForNextSession()`:
    - Calls `stopCapture()` and `processorController?.stop()` if running
    - Resets `audioSource`, `processorController`, `stopRequested`, timing fields
    - Does NOT unload the model or shut down the executor
    - Returns state to `READY` (model is still warm)
  - `startSession()` now:
    1. Reuses existing `modelManager` (already loaded and warm from init)
    2. Creates fresh `CaptureController` and `ProcessorController`
    3. Starts capture and processing immediately (no warm-up wait)
  - `destroy()` now:
    1. Calls `resetForNextSession()` first
    2. Calls `modelManager.unload()` and `modelManager.shutdown()`
    3. Sets state to `UNINITIALISED`
  - `init` block:
    - Still creates `ModelManager` immediately
    - Calls `modelManager.initAsync()` to load model and warm up
      - The warm-up callback transitions to `READY`
      - If `startSession()` is called before warm-up completes, either:
        - Queue start request, or
        - Return `INVALID_STATE` and let caller retry
        (Product decision — document whichever is chosen)

- `ModelManager`:
  - No structural changes needed
  - `isReady` persists across sessions (no `unload` between sessions)
  - `warmupPerformed` flag stays true across sessions (reset only by `unload`)

- `ResetForNextSession` semantics:
  - Called after `stopAndTranscribe()` result is dispatched
  - Can also be called manually by the API consumer
  - Safe to call multiple times (idempotent)

**Verification Checks**:

- [ ] `SpeechTotext` no longer has an `activeSession` field
- [ ] `SpeechToText` no longer has a `startSessionInternal()` method
- [ ] `resetForNextSession()` exists and clears session-scoped state
- [ ] `modelManager` is created exactly once (in `init`)
- [ ] `modelManager.isReady` stays `true` between sessions
- [ ] `destroy()` calls `modelManager.unload()` and `modelManager.shutdown()` exactly once
- [ ] No `SpeechToText` constructor is called more than once per `create()` call
- [ ] `./gradlew :stt:test` passes
- [ ] `SttLifecycleStateTest` covers the `READY -> RECORDING -> STOPPED -> READY` cycle
- [ ] `SttStopPathTest` covers STOP during warm-up (warm-up is now at init, not per-session — verify this test is updated or removed)
- [ ] `SttWarmupTest` covers warm-up at init time

---

### Phase 2 — Encapsulate State Machine With Internal Lock

**Goal**: `transitionTo()` is provably atomic regardless of which caller
invokes it. No caller-side `synchronized` convention required.

**Rationale**: Currently `transitionTo()` reads `currentState`, checks legality,
and writes `currentState` — all without holding a lock. It relies on callers
to hold `stateLock` before calling. Analysis showed that callers do hold the
lock correctly in practice, but the convention is fragile. A new developer
adding a path that calls `transitionTo()` outside `stateLock` would introduce
a subtle race.

**Design**:

Extract the state machine into an `internal` class with its own lock:

```kotlin
internal class SttLifecycleStateMachine {
    private val lock = Any()
    @Volatile
    private var _currentState: SttLifecycleState = SttLifecycleState.UNINITIALISED

    /** Read-only snapshot — safe without lock (volatile). */
    val currentState: SttLifecycleState
        get() = _currentState

    /**
     * Transition to [newState]. Returns false if transition is illegal.
     * Thread-safe: guarded by internal lock.
     */
    fun transitionTo(newState: SttLifecycleState): Boolean {
        synchronized(lock) {
            val from = _currentState
            if (from == newState) return true
            val valid = when (from) {
                is SttLifecycleState.UNINITIALISED -> newState is SttLifecycleState.READY
                is SttLifecycleState.READY -> newState is SttLifecycleState.RECORDING ||
                        newState is SttLifecycleState.STOPPED
                is SttLifecycleState.RECORDING -> newState is SttLifecycleState.FINALISING
                is SttLifecycleState.FINALISING -> newState is SttLifecycleState.STOPPED
                else -> false
            }
            if (valid) {
                _currentState = newState
                return true
            }
            SttLogger.lifecycleE("illegal transition: ${from.javaClass.simpleName} -> ${newState.javaClass.simpleName}")
            return false
        }
    }

    /**
     * Force-set the state without validation.
     * Used for bypass paths (e.g., early stop during warm-up).
     * Thread-safe: guarded by internal lock.
     */
    fun forceSet(state: SttLifecycleState) {
        synchronized(lock) {
            _currentState = state
        }
    }
}
```

**Replace**:
- `@Volatile internal var currentState: SttLifecycleState` → `val stateMachine = SttLifecycleStateMachine()`
- All `currentState = X` direct assignments → `stateMachine.forceSet(SttLifecycleState.X)`
- All `transitionTo(SttLifecycleState.X)` → `stateMachine.transitionTo(SttLifecycleState.X)`
- All `currentState is SttLifecycleState.X` → `stateMachine.currentState is SttLifecycleState.X`

`forceSet()` is the only way to bypass validation. Its call sites must be
documented with the reason for the bypass (e.g., early stop during warm-up
where the normal lifecycle was never entered).

**Call site audit** (all sites that currently write `currentState`):

| Site | Current | After |
|---|---|---|
| `init` (default) | `UNINITIALISED` | Constructor default — no change |
| `initAsync` callback in `startSessionInternal` → `READY` | `transitionTo(READY)` | `stateMachine.transitionTo(READY)` |
| `start()` → `RECORDING` | `transitionTo(RECORDING)` | `stateMachine.transitionTo(RECORDING)` |
| `stopAndTranscribe()` → `FINALISING` | `transitionTo(FINALISING)` | `stateMachine.transitionTo(FINALISING)` |
| `shutdownPipeline(pcm)` → various | `transitionTo(...)` + direct `=` | `stateMachine.transitionTo(...)` + `stateMachine.forceSet(...)` |
| `shutdownPipeline()` → various | `transitionTo(...)` + direct `=` | `stateMachine.transitionTo(...)` + `stateMachine.forceSet(...)` |
| `setStoppedDirect()` → `STOPPED` | Direct `=` | `stateMachine.forceSet(STOPPED)` |
| `destroy()` → `UNINITIALISED` | Direct `=` | `stateMachine.forceSet(UNINITIALISED)` |

**Verification Checks**:

- [ ] `SttLifecycleStateMachine` is a new internal class
- [ ] `SpeechToText.currentState` is replaced by `SpeechToText.stateMachine: SttLifecycleStateMachine`
- [ ] Every `currentState = X` direct assignment is replaced by `stateMachine.forceSet(X)` with a comment explaining the bypass
- [ ] Every `transitionTo(X)` call is replaced by `stateMachine.transitionTo(X)`
- [ ] Every `currentState is SttLifecycleState.X` read is replaced by `stateMachine.currentState is SttLifecycleState.X`
- [ ] `stateLock` is no longer needed for state machine operations (but may still guard other state — see Phase 3)
- [ ] `./gradlew :stt:test` passes
- [ ] `SttLifecycleStateTest.kt` continues to pass (update if `stateMachine` visibility changes)
- [ ] No `transitionTo` call occurs outside the state machine class

---

### Phase 3 — Shrink Lock Scope in `stopAndTranscribe()`

**Goal**: Do not hold `stateLock` while submitting work to `whisperExecutor`
or transcribing PCM.

**Rationale**: In the RECORDING/FINALISING path of `stopAndTranscribe()`, the
entire shutdown sequence runs inside `synchronized(stateLock)`. This includes
`shutdownPipeline(pcm)` which calls `submitInferenceAndDispatch()` →
`modelManager.transcribeAfterWarmup()` → `whisperExecutor.submit()`.
Holding the lock during executor submission is unnecessary: none of the state
accessed during inference dispatch (`stopTrigger`, `config`, `timingUtteranceStartMs`,
listener references) is mutable under `stateLock` protection, except
`timingPcmStartMs` and `timingUtteranceStartMs` which are only written once
and read-only after stop.

**Design**:

Extract PCM capture and drain inside the lock, then drop the lock before
running inference dispatch:

```kotlin
fun stopAndTranscribe() {
    // Phase 1 removes activeSession routing — simplify to direct execution

    val pcm: FloatArray?
    val drainCode: SttReturnCode
    
    synchronized(stateLock) {
        SttLogger.pcm("[STOP] entered -- isRunning=${isRunning.get()}, state=${stateMachine.currentState}")
        if (!stopTrigger.shouldStop()) return

        if (stateMachine.currentState is SttLifecycleState.STOPPED) return

        // ── Early stop during warm-up / READY ──────────────────
        if (stateMachine.currentState is SttLifecycleState.UNINITIALISED ||
            stateMachine.currentState is SttLifecycleState.READY
        ) {
            handleEarlyStop()  // extracted — still inside lock
            return
        }

        // ── RECORDING or FINALISING ────────────────────────────
        isRunning.set(false)
        if (!stateMachine.transitionTo(SttLifecycleState.FINALISING)) return

        stopRequested = true
        processorController?.stop()

        pcm = processorController?.drainRemainingFrames()
            ?: processorController?.stopAndFinalize()
        drainCode = if (pcm != null) SttReturnCode.SUCCESS else SttReturnCode.NO_SPEECH
    }
    // ── stateLock released ─────────────────────────────────────

    if (pcm != null) {
        shutdownPipeline(pcm, drainCode)
    } else {
        synchronized(stateLock) {
            stateMachine.forceSet(SttLifecycleState.STOPPED)
        }
    }
}
```

**Key principle**: The lock guards *mutation* of shared state (`isRunning`,
`stopRequested`, `processorController`, `audioSource`). Once the PCM is
extracted and processor/audio are stopped, the shared state is stable — no
lock needed for the read-only inference dispatch.

**Verification Checks**:

- [ ] `stopAndTranscribe()` releases `stateLock` before calling `shutdownPipeline()`
- [ ] The `stateLock`-protected region in `stopAndTranscribe()` contains only:
  - State checks
  - `isRunning.set(false)`
  - `transitionTo(FINALISING)`
  - `stopRequested = true`
  - `processorController?.stop()`
  - PCM drain via `drainRemainingFrames()` / `stopAndFinalize()`
- [ ] No `submitInferenceAndDispatch`, `transcribeAfterWarmup`, or `dispatchResult` calls occur inside `stateLock`
- [ ] `stateLock` is held only for the PCM drain exit path; inference dispatch runs outside
- [ ] `./gradlew :stt:test` passes
- [ ] `SttStopPathTest.kt` confirms no regression in stop timing

---

### Phase 4 — Document Thread Affinity

**Goal**: Every public callback's thread context is documented in KDoc.
Callers know whether they need to post to a Handler.

**Rationale**: `onResult`, `onResultWithTiming`, `onError`, and
`sttErrorListener` callbacks are dispatched from different threads depending
on the code path:

| Path | Thread | Current doc |
|---|---|---|
| Normal stop (RECORDING → FINALISING → STOPPED) | `ProcessorControllerThread` | None |
| Early stop during warm-up (via `transcribeAfterWarmup`) | `whisperExecutor` thread | None |
| Error dispatch (`dispatchError`) | Caller's thread (varies) | None |

**Design**:

Add a `Threading` section to `SpeechToText` class KDoc:

```
 * ## Threading
 *
 * Result and error callbacks are **not** delivered on the main thread.
 * Callers must post to their own [android.os.Handler] or [kotlinx.coroutines.Dispatchers.Main]
 * if main-thread delivery is required.
 *
 * | Callback | Delivery thread |
 * |---|---|
 * | [onResult] | Internal worker thread (processor or whisper executor) |
 * | [onResultWithTiming] | Same as [onResult] |
 * | [onError] | The thread that encountered the error |
 * | [sttErrorListener] | Same as [onError] |
 *
 * Lifecycle methods ([setConfig], [startSession], [stopAndTranscribe], [destroy])
 * are not thread-safe. Callers must serialise calls to these methods.
```

**Verification Checks**:

- [ ] `SpeechToText` class KDoc includes the Threading section above
- [ ] `setOnResultListener`, `setOnResultWithTimingListener`, `setOnErrorListener`, `setSttErrorListener` each have KDoc noting the thread context
- [ ] No functional changes — documentation only

---

### Phase 5 — Remove Warm-Up Cancellation (Cleanup After Phase 1)

**Goal**: After Phase 1, warm-up runs once at init and the model stays loaded.
The warm-up cancellation machinery (`whisperCancelled`, `transcribeAfterWarmup`,
`cancelWarmup`) is dead code and should be removed.

**Rationale**: `transcribeAfterWarmup()` was introduced to avoid blocking the
stop thread on the C++ whisper mutex held by the warm-up call. With warm-up
moved to init (pre-session), the stop path never contends with a warm-up
inference. The `whisperCancelled` flag and the async submission path are
unnecessary.

**Changes**:

- `ModelManager`:
  - Remove `whisperCancelled` field
  - Remove `cancelWarmup()` method
  - Remove `transcribeAfterWarmup()` method
  - Retain `transcribe()` as the only inference path (synchronous, blocking)
- `SpeechToText`:
  - `submitInferenceAndDispatch()` → replace with direct call to `runInferenceAndDispatch()`
  - Remove `submitInferenceAndDispatch()` method
  - Early-stop path in `stopAndTranscribe()` calls `modelManager.transcribe()` directly
    (no more async submission)

**Verification Checks**:

- [ ] `ModelManager.whisperCancelled` field removed
- [ ] `ModelManager.cancelWarmup()` method removed
- [ ] `ModelManager.transcribeAfterWarmup()` method removed
- [ ] `SpeechToText.submitInferenceAndDispatch()` method removed
- [ ] All call sites use `modelManager.transcribe()` (synchronous)
- [ ] `./gradlew :stt:test` passes
- [ ] No regressions in `SttStopPathTest` (stop during warm-up no longer a distinct path)

---

### Phase 6 — (Deferred) ProcessorController Self-Join Documentation

**Goal**: Clarify the `ProcessorController.stop()` self-join behaviour.

**Current**: `processorController?.stop()` calls `workerThread?.join(500)`.
When called from within `ProcessorControllerThread` (via `onAutoStop`/`onAbnormalTermination`
callbacks), `Thread.join()` on the current thread is a no-op in Java — it returns
immediately. This is correct but confusing.

**Design decision**: This is safe because:

1. `onAutoStop`/`onAbnormalTermination` are invoked after `isRunning.set(false)`
   and `break` from the processing loop — the thread is already exiting.
2. The `join(500)` on self is a no-op and does not block.
3. After Phase 1, the `shutdownPipeline()` inside the callback sets
   `processorController = null`, so no re-entrant call to `stop()` is possible.

**Change**: Add a comment to `ProcessorController.stop()`:

```kotlin
/**
 * Stop the processor worker thread.
 *
 * Safe to call from within the worker thread itself: Thread.join() on the
 * current thread is a no-op and returns immediately.
 */
fun stop() {
```

**Verification Checks**:

- [ ] Comment added to `ProcessorController.stop()` KDoc
- [ ] No behavioural changes

---

## Deferred / Not Planned

| Item | Reason |
|---|---|
| JNI cancellation flag in whisper_bridge.cpp | Deprecated by Phase 1 — warm-up runs once at init, never contended |
| Auto-stop inference gap (AutoStop does not transcribe PCM) | Product decision, not implementation. Flag for discussion with product owner. Current behaviour: auto-silence stop discards PCM. See Phase 6 of the original deep-dive report. |
| Main-thread callback delivery | Robot application, not UI-driven. KDoc note (Phase 4) is sufficient. |
| `ConcurrentLinkedQueue` → `BlockingQueue` | Current polling + sleep(10ms) is fine for 16kHz audio. No latency issue. |

---

## Summary of Files Touched

| File | Phase | Change |
|---|---|---|
| `SpeechToText.kt` | 1, 2, 3, 4, 5 | Remove inner STT; add `resetForNextSession()`; replace `currentState` with `stateMachine`; shrink lock scope; add threading KDoc; remove `submitInferenceAndDispatch()` |
| `ModelManager.kt` | 1, 5 | No structural change in Phase 1 (already hoisted); remove cancellation machinery in Phase 5 |
| `SpeechToText.kt` — new inner class | 2 | `SttLifecycleStateMachine` with internal lock |
| `SttLifecycleStateMachine.kt` (new file) | 2 | Extracted state machine (if not inlined) |
| `ProcessorController.kt` | 6 | Self-join comment |
| `SttStopPathTest.kt` | 1, 5 | Update/remove warm-up-stop tests — no longer a distinct path |
| `SttWarmupTest.kt` | 1 | Update to verify warm-up at init, not per-session |

---

## Overall Verification (Final Gate)

Before marking this plan complete, the following must all pass:

- [ ] `./gradlew :stt:test` — all unit tests pass
- [ ] `./gradlew :app:assembleDebug` — app compiles against the refactored library
- [ ] No `@Volatile` state is written without a lock or atomic wrapper
- [ ] Every mutable field has exactly one write site (or is documented for bypass)
- [ ] No lambda submitted to an executor captures a `synchronized` block's lock
- [ ] The threading KDoc (Phase 4) is accurate for all code paths
- [ ] `SpeechToText.create()` → `setConfig()` → `startSession()` → `stopAndTranscribe()` → `resetForNextSession()` → `startSession()` → `stopAndTranscribe()` → `destroy()` produces identical results to the old two-instance pattern
