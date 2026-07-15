# Error Subsystem — Session-Persistent Task Checklist

**Instructions for CATO:** This file tracks task progress across sessions.  
**Status markers:** `[ ]` = not started, `[~]` = in progress, `[x]` = done  
**Owner:** Single developer (no backward compatibility required)

---

## Prerequisites

- [ ] Current session has read `ERROR_SUBSYSTEM_PHASE2_PLAN.md` for architectural context
- [ ] `ERROR_SUBSYSTEM_REWORK_PLAN.md` Phase 1 is fully [DONE] — confirm by running:
  ```
  ./gradlew :stt:compileDebugKotlin :app:compileDebugKotlin
  ./gradlew :stt:testDebugUnitTest :app:testDebugUnitTest
  ```
- [ ] Baseline tests pass before starting Phase 2 changes

---

## Phase 2 — Semantic Filling

### 2.1 Add `SttErrorCode.category` compile-time mapping

**File:** `stt/src/main/java/dev/barrycade/voicecore/stt/SttErrorCode.kt`

- [ ] Add `val category: SttErrorCategory` parameter to enum
- [ ] Map each code:
  - `MODEL_LOAD_FAILED` → `WHISPER_ERROR`
  - `INFERENCE_FAILED` → `WHISPER_ERROR`
  - `CAPTURE_FAILED` → `CAPTURE_ERROR`
  - `VAD_FAILED` → `VAD_ERROR`
  - `PIPELINE_ILLEGAL_STATE` → `UNKNOWN`
  - `INTERNAL_EXCEPTION` → `UNKNOWN`
- [ ] Verify `SttErrorCode.entries.size == 6`
- [ ] Run `./gradlew :stt:compileDebugKotlin`

### 2.2 Simplify `SttError` — remove dead fields

**File:** `stt/src/main/java/dev/barrycade/voicecore/stt/SttError.kt`

- [ ] Remove `timingSnapshotMs` field
- [ ] Remove `lastRms` field
- [ ] Remove `lastVadState` field
- [ ] Remove `vadConfidence` field
- [ ] Remove `avgRms` field
- [ ] Remove `peakRms` field
- [ ] Remove `noiseFloorRms` field
- [ ] Remove `motionModeActive` field
- [ ] Replace `context: Map<String, Any?> = emptyMap()` with `details: List<String> = emptyList()`
- [ ] Remove `category` as a constructor parameter (derive from `code.category` instead)
- [ ] Update KDoc to reflect new fields
- [ ] Run `./gradlew :stt:compileDebugKotlin` (will fail — 2.3 fixes callers)

### 2.3 Update all `SttError(...)` construction sites

**File:** `stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt`

- [ ] `startProcessor()` — model init failure: use `SttErrorCode.MODEL_LOAD_FAILED.category`, replace `context` with `details`
- [ ] `startProcessor()` — forced audio init failure: use `SttErrorCode.CAPTURE_FAILED.category`, replace `context` with `details`
- [ ] `handleInferenceError()` — use `SttErrorCode.INTERNAL_EXCEPTION.category`, replace `context` with `details`
- [ ] Any other `SttError(...)` calls in this file (search for `SttError(`)

**File:** `stt/src/main/java/dev/barrycade/voicecore/stt/ModelManager.kt`

- [ ] `runInitSequence()` catch: use `SttErrorCode.MODEL_LOAD_FAILED.category`, add `details`
- [ ] `handleForcedFailure()`: same
- [ ] `loadModel()` catch: same
- [ ] `loadModelIfNeeded()` catch: same

**File:** `stt/src/main/java/dev/barrycade/voicecore/stt/UtteranceAccumulator.kt`

- [ ] `handleSpeechStart()` force-timeout: use `SttErrorCode.PIPELINE_ILLEGAL_STATE.category`, replace `context` with `details`

**File:** `stt/src/main/java/dev/barrycade/voicecore/stt/SttJsonAdapter.kt`

- [ ] Update `buildErrorJson()` — remove `category` parameter, derive from `SttErrorCode`
- [ ] Add `details: List<String>` parameter, serialize as JSON array
- [ ] Update all callers of `buildErrorJson()` in `SpeechToText.kt` and elsewhere
- [ ] Update `buildErrorJson` callers in tests

- [ ] Run `./gradlew :stt:compileDebugKotlin` — must pass now

### 2.4 Wire `SttErrorListener` into `CaptureManager`

**File:** `stt/src/main/java/dev/barrycade/voicecore/stt/CaptureManager.kt`

- [ ] Add `private val sttErrorListener: SttErrorListener? = null` constructor parameter
- [ ] In `beginPcmCapture()` catch block: emit `SttError(CAPTURE_FAILED, ...)` via `sttErrorListener`
- [ ] In `restartCapture()` catch block: emit `SttError(CAPTURE_FAILED, ...)` via `sttErrorListener`
- [ ] Keep the `throw t` — caller must still handle the failure (stack unwinding)
- [ ] Update `SpeechToText.init` constructor call to pass `callbackDispatcher.getSttErrorListener()`
- [ ] Update `CaptureManager` test construction in test files (search for `CaptureManager(`)

**File:** `stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt`

- [ ] Add try/catch around `controller.start()` in `startProcessor()` to handle capture failures from `CaptureManager` propagation
- [ ] In catch: emit `SttError(CAPTURE_FAILED, ...)` and transition to safe state (IDLE pipeline stage, lifecycle reset)

- [ ] Run `./gradlew :stt:compileDebugKotlin`

### 2.5 Add structured error to `ProcessorController` catch-all

**File:** `stt/src/main/java/dev/barrycade/voicecore/stt/ProcessorController.kt`

- [ ] Add `private val sttErrorListener: SttErrorListener? = null` constructor parameter
- [ ] In `runProcessingLoop()` catch-all block: add `sttErrorListener?.onSttError(SttError(INTERNAL_EXCEPTION, ...))`
- [ ] Add `details` with `vadActiveMs` and `lastUtteranceDurationMs`
- [ ] Update construction sites of `ProcessorController` (in `SttProcessingController`) to forward listener

- [ ] Run `./gradlew :stt:compileDebugKotlin`

### 2.6 Wire `SttErrorListener` into `SttLifecycleStateMachine`

**File:** `stt/src/main/java/dev/barrycade/voicecore/stt/SttLifecycleStateMachine.kt`

- [ ] Add `private val sttErrorListener: SttErrorListener? = null` constructor parameter
- [ ] In `transitionTo()` `valid == false` path: emit `SttError(PIPELINE_ILLEGAL_STATE, ...)` with from/to in `details`
- [ ] Add KDoc note about re-entrancy guard: caller must ensure listener does not re-enter `transitionTo`

**File:** `stt/src/main/java/dev/barrycade/voicecore/stt/SttLifecycleController.kt`

- [ ] Inject `sttErrorListener` into `SttLifecycleStateMachine` constructor
- [ ] Add `sttErrorListener` parameter to `SttLifecycleController` constructor

**File:** `stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt`

- [ ] Update `lifecycleController` construction to pass `callbackDispatcher.getSttErrorListener()`

- [ ] Run `./gradlew :stt:compileDebugKotlin`

### 2.7 Remove deprecated `onError` path from `SttCallbackDispatcher`

**File:** `stt/src/main/java/dev/barrycade/voicecore/stt/SttCallbackDispatcher.kt`

- [ ] Remove `onError` backing field
- [ ] Remove `setOnErrorListener()` method
- [ ] Remove `errorSnapshot?.invoke(...)` line from `dispatchError()`
- [ ] Remove `onError = null` from `clearListeners()`
- [ ] Update KDoc

- [ ] Run `./gradlew :stt:compileDebugKotlin`

### 2.8 Update `AppErrorRouter` to use `details` from JSON

**File:** `app/src/main/java/dev/barrycade/voicecore/AppErrorRouter.kt`

- [ ] Extract `details` JSON array from error object
- [ ] Append details to `outputText`
- [ ] Include details in log output
- [ ] If category is `UNKNOWN`, try to derive UI action from code instead
- [ ] Update `ErrorUiAction` if needed

- [ ] Run `./gradlew :app:compileDebugKotlin`

### 2.9 Update tests

**File:** `stt/src/test/java/dev/barrycade/voicecore/stt/SttErrorCodeTest.kt`

- [ ] Add `everyErrorCodeHasCorrectCategory()` test (see 2.7 in plan)
- [ ] Update all `SttError(...)` test constructions to match new signature
- [ ] Remove tests for deleted fields (`lastRms`, `lastVadState`, `context`, etc.)
- [ ] Rename/update `noLegacyCodeNamesReferenced` if needed

**File:** `stt/src/test/java/dev/barrycade/voicecore/stt/SttCallbackDispatcherTest.kt`

- [ ] Remove tests that use `setOnErrorListener`
- [ ] Update `dispatchError(SttError(...))` calls to new signature

**File:** `stt/src/test/java/dev/barrycade/voicecore/stt/SttLifecycleStateTest.kt`

- [ ] Update `SttError(...)` construction in `applyTransition()` helper

**File:** `stt/src/test/java/dev/barrycade/voicecore/stt/SttStopPathTest.kt`

- [ ] Update `SttError(...)` construction

**File:** `stt/src/test/java/dev/barrycade/voicecore/stt/SttWarmupTest.kt`

- [ ] Update `SttError(...)` construction

**File:** `app/src/test/java/dev/barrycade/voicecore/NewApiSmokeTest.kt`

- [ ] Verify no changes needed (tests use JSON strings, not `SttError` directly)

### 2.10 (Optional) Add integration test

**File:** `stt/src/test/java/dev/barrycade/voicecore/stt/SttErrorDeliveryTest.kt` (NEW)

- [ ] Test: model load failure → JSON error with correct category + code + details
- [ ] Test: capture failure → JSON error with correct category + code
- [ ] Test: internal exception → JSON error with details

---

## Verification

- [ ] `./gradlew :stt:compileDebugKotlin` — success
- [ ] `./gradlew :app:compileDebugKotlin` — success
- [ ] `./gradlew :stt:testDebugUnitTest` — all pass
- [ ] `./gradlew :app:testDebugUnitTest` — all pass

---

## Post-completion

- [ ] Mark this checklist as complete (date: ________)
- [ ] Update `ERROR_SUBSYSTEM_REWORK_PLAN.md` to reference Phase 2 as complete
- [ ] Remove dead code audit items from any existing TODO lists

---

## Quick reference: key files and their roles

| File | Role in error subsystem |
|------|------------------------|
| `SttError.kt` | Error data class (simplified in Phase 2) |
| `SttErrorCode.kt` | Error code enum with compile-time category mapping |
| `SttErrorCategory.kt` | High-level category enum (preserved) |
| `SttErrorListener.kt` | Fun interface for error callbacks (preserved) |
| `ReturnCodeMapper.kt` | Legacy → new API code mapping (unchanged) |
| `SttCallbackDispatcher.kt` | Listener fan-out (remove onError) |
| `SttJsonAdapter.kt` | JSON boundary serialization |
| `AppErrorRouter.kt` | App-level error → UI action routing |
| `SpeechToText.kt` | Main entry point — catches and emits errors |
| `ModelManager.kt` | Whisper model errors |
| `CaptureManager.kt` | Audio capture errors (NEEDS FIX) |
| `ProcessorController.kt` | Processing loop errors (NEEDS FIX) |
| `SttLifecycleStateMachine.kt` | Lifecycle invariant enforcement (NEEDS FIX) |
| `UtteranceAccumulator.kt` | Force-timeout error emission |
