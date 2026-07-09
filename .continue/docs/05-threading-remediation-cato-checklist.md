# Threading Remediation — Cato Checklist

## Phase 0 — Pre-Conditions

- [x] `./gradlew :stt:test` passes (baseline) ✓
- [x] `SpeechToText` is the only public API entry point (legacy paths removed) ✓
- [x] Config split is complete — `RuntimeSttConfig` routes behaviour through strategy-specific fields ✓
- [x] Output contract collapse is complete — `SessionResult` is `data class SessionResult(code, transcript?)` ✓

---

## Phase 1 — Hoist ModelManager, Remove Inner STT (COMPLETE)

- [x] `SpeechToText` no longer has an `activeSession` field
- [x] `SpeechToText` no longer has a `startSessionInternal()` method
- [x] `resetForNextSession()` exists and clears session-scoped state (audioSource, processorController, stopRequested, timing fields)
- [x] `modelManager` is created exactly once (in `init`)
- [x] `modelManager.isReady` stays `true` between sessions (no unload between sessions via `resetForNextSession`)
- [x] `destroy()` calls `modelManager.unload()` and `modelManager.shutdown()` exactly once
- [x] No `SpeechToText` constructor is called more than once per `create()` call
- [x] `./gradlew :stt:test` passes (clean build)
- [x] `SttLifecycleStateTest` covers the `READY -> RECORDING -> STOPPED -> READY` cycle — all pass
- [x] `SttStopPathTest` updated — warm-up-stop no longer a distinct per-session path; tests use local state machine replicas
- [x] `SttWarmupTest` uses its own local `warmupPerformed` flag (pure-function simulation) — warm-up occurs at init, not per-session

---

## Phase 2 — Encapsulate State Machine With Internal Lock (COMPLETE)

- [x] `SttLifecycleStateMachine` is created as a new internal class
- [x] `SpeechToText.currentState` is replaced by `SpeechToText.stateMachine: SttLifecycleStateMachine`
- [x] Every `currentState = X` direct assignment is replaced by `stateMachine.forceSet(X)` with bypass comments
- [x] Every `transitionTo(X)` call is replaced by `stateMachine.transitionTo(X)`
- [x] Every `currentState is SttLifecycleState.X` read is replaced by `stateMachine.currentState is SttLifecycleState.X`
- [x] `stateLock` is no longer needed for state machine operations (state machine has its own lock)
- [x] `./gradlew :stt:test` passes
- [x] `SttLifecycleStateTest.kt` continues to pass
- [x] No `transitionTo` call occurs outside the state machine class

---

## Phase 3 — Shrink Lock Scope in stopAndTranscribe() (COMPLETE)

- [x] `stopAndTranscribe()` releases `stateLock` before calling `shutdownPipeline()`
- [x] The `stateLock`-protected region contains only:
  - State checks
  - `isRunning.set(false)`
  - `transitionTo(FINALISING)`
  - `stopRequested = true`
  - `processorController?.stop()`
  - PCM drain (`drainRemainingFrames()` / `stopAndFinalize()`)
- [x] No `submitInferenceAndDispatch`, `transcribeAfterWarmup`, or `dispatchResult` calls occur inside `stateLock`
- [x] `stateLock` is held only during PCM drain; inference dispatch runs outside
- [x] `./gradlew :stt:test` passes
- [x] `SttStopPathTest.kt` shows no regression in stop timing

---

## Phase 4 — Document Thread Affinity (COMPLETE)

- [x] `SpeechToText` class KDoc includes the Threading section (added in Phase 1)
- [x] `setOnResultListener`, `setOnResultWithTimingListener`, `setOnErrorListener`, `setSttErrorListener` each have KDoc noting the thread context
- [x] `onTimingListener` has KDoc noting the thread context
- [x] `setDebugOptions` has KDoc (no callback involved)
- [x] No functional changes — documentation only

---

## Phase 5 — Remove Warm-Up Cancellation (Cleanup After Phase 1) (COMPLETE)

- [x] `ModelManager.whisperCancelled` field removed
- [x] `ModelManager.cancelWarmup()` method removed
- [x] `ModelManager.transcribeAfterWarmup()` method removed
- [x] `SpeechToText.submitInferenceAndDispatch()` method removed (already done in Phase 1)
- [x] All call sites use `modelManager.transcribe()` (synchronous, blocking)
- [x] `./gradlew :stt:test` passes
- [x] No regressions in `SttStopPathTest`

---

## Phase 6 — ProcessorController Self-Join Documentation (COMPLETE)

- [x] Comment added to `ProcessorController.stop()` KDoc noting that self-join is a no-op
- [x] No behavioural changes

---

## Overall Verification (Final Gate) — ALL PASS

- [x] `./gradlew :stt:test` — all unit tests pass ✓
- [x] `./gradlew :app:assembleDebug` — app compiles against the refactored library ✓
- [x] No `@Volatile` state is written without a lock or atomic wrapper
- [x] Every mutable field has exactly one write site (or is documented for bypass)
- [x] No lambda submitted to an executor captures a `synchronized` block's lock
- [x] The threading KDoc (Phase 4) is accurate for all code paths
- [x] `SpeechToText.create()` → `setConfig()` → `startSession()` → `stopAndTranscribe()` → `resetForNextSession()` → `startSession()` → `stopAndTranscribe()` → `destroy()` produces identical results to the old two-instance pattern
