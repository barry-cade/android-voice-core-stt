# Error Subsystem Phase 2 — Semantic Filling Plan

**Status:** Proposed  
**Audit finding reference:** CATO error subsystem audit (previous session)  
**Prerequisite:** `ERROR_SUBSYSTEM_REWORK_PLAN.md` (Phase 1 — mechanical changes) is fully [DONE]  

---

## Problem statement

Phase 1 added the mechanical structure (SttError type, SttErrorCategory enum, category field in JSON, AppErrorRouter). However, the semantic content is hollow:

- Every production `SttError` construction site sets `category = UNKNOWN` — the category dimension is **dead on arrival**.
- `CaptureManager` throws raw `Throwable` — no `SttError` emitted.
- `ProcessorController.runProcessingLoop` catch-all silently stops — no `SttError` emitted.
- `SttError` declares 7 optional diagnostic fields that nothing populates.
- `context: Map<String, Any?>` has no schema — consumers can't rely on any key.

This plan fixes all five gaps. Since the project is virginal (single developer, no backward compatibility required), we can refactor freely.

---

## Design decisions

### Decision 1: Keep `SttErrorCategory` but populate it correctly

We keep the category dimension because it separates **concern** (which subsystem failed?) from **specific failure mode** (what exactly went wrong?). The caller (`AppErrorRouter`) needs category to decide UI treatment (banner vs. inline text), and code to decide log severity. Both are valuable.

**Rule:** Every `SttError(code = X)` must set `category = theCategoryThatMapsToX`.  
**Enforcement:** Add a `SttErrorCode.category` property on the enum itself — a compile-time mapping.

### Decision 2: Remove dead diagnostic fields from `SttError`

The fields `lastRms`, `lastVadState`, `vadConfidence`, `avgRms`, `peakRms`, `noiseFloorRms`, `motionModeActive`, `timingSnapshotMs` are removed. They are never populated in production code and create the illusion of rich context. If we want VAD diagnostics in errors, we add them back with actual population code at the point of need.

`utteranceId` is kept — it's plausibly useful for multi-utterance sessions and is trivial to maintain.

### Decision 3: Replace `context: Map<String, Any?>` with typed fields

Instead of a bag-of-anything, we use **two dedicated typed fields** that cover the vast majority of use cases:

- `details: List<String>` — human-readable bullet points (e.g., "modelPath=/data/models/ggml.bin", "pcmSamples=16000")
- `cause: Throwable?` — already present, preserved

This replaces `context` entirely. No more stringly-typed key lookup. A consumer can display `details` directly in a log or diagnostic panel.

### Decision 4: CaptureManager and ProcessorController emit SttError through injected listener

Both classes already have access to `SttErrorListener` (or could accept one). We inject it explicitly and emit structured errors on failure paths.

### Decision 5: SttLifecycleStateMachine emits SttError on illegal transitions

The state machine accepts an optional `SttErrorListener`. When an illegal transition is detected, it emits `PIPELINE_ILLEGAL_STATE` with the from/to states in `details`.

---

## Phase 2 task breakdown

### 2.1 Add `SttErrorCode.category` compile-time mapping

**File:** `stt/.../SttErrorCode.kt`

Add a `category` property to each enum value. This is the single source of truth:

```kotlin
internal enum class SttErrorCode(val category: SttErrorCategory) {
    MODEL_LOAD_FAILED(SttErrorCategory.WHISPER_ERROR),
    INFERENCE_FAILED(SttErrorCategory.WHISPER_ERROR),
    CAPTURE_FAILED(SttErrorCategory.CAPTURE_ERROR),
    VAD_FAILED(SttErrorCategory.VAD_ERROR),
    PIPELINE_ILLEGAL_STATE(SttErrorCategory.UNKNOWN),     // internal invariant violation
    INTERNAL_EXCEPTION(SttErrorCategory.UNKNOWN)           // unknown/unexpected
}
```

Now no `SttError(...)` construction site can forget to set category — they can use `code.category` directly.

**Replaces:** The test `SttErrorCodeTest` has no test for category mapping yet (add one).

**Dependency:** All `SttError(...)` construction sites need updating to use `code.category` instead of hardcoding `UNKNOWN`.

### 2.2 Simplify `SttError` — remove dead fields

**File:** `stt/.../SttError.kt`

Remove these fields:

- `timingSnapshotMs`
- `lastRms`
- `lastVadState`
- `vadConfidence`
- `avgRms`
- `peakRms`
- `noiseFloorRms`
- `motionModeActive`

Replace `context: Map<String, Any?>` with:

- `details: List<String>` — empty by default

Keep everything else:

- `category` → use `code.category` at call sites
- `code`
- `message`
- `utteranceId`
- `cause`

Resulting signature:

```kotlin
internal data class SttError(
    val code: SttErrorCode,
    val message: String,
    val utteranceId: Int? = null,
    val cause: Throwable? = null,
    val details: List<String> = emptyList()
)
```

The `category` is accessible via `error.code.category` — no need to store it redundantly on the data class.

### 2.3 Update all `SttError(...)` construction sites

**Files:**

| File | Count | Changes |
|------|-------|---------|
| `stt/.../SpeechToText.kt` | ~5 sites | Use `code.category`, replace `context` with `details` |
| `stt/.../ModelManager.kt` | ~4 sites | Same |
| `stt/.../UtteranceAccumulator.kt` | 1 site | Same |
| `stt/.../ProcessorController.kt` | 1 site (NEW catch-all) | Emit `SttError(CAPTURE_FAILED/INTERNAL_EXCEPTION, ...)` |
| `stt/.../CaptureManager.kt` | 2 sites (NEW) | Replace raw `throw t` with `SttError` emission + rethrow or abort |
| `stt/.../SttLifecycleStateMachine.kt` | 1 site (NEW) | Emit `SttError(PIPELINE_ILLEGAL_STATE, ...)` on illegal transition |

**Dependency:** 2.1 + 2.2 must be done first.

### 2.4 Wire `SttErrorListener` into `CaptureManager`

**File:** `stt/.../CaptureManager.kt`

Add constructor parameter:

```kotlin
internal class CaptureManager(
    ...
    private val sttErrorListener: SttErrorListener? = null
) : SessionManager
```

Replace the `throw t` in `beginPcmCapture()` and `restartCapture()`:

```kotlin
catch (t: Throwable) {
    synchronized(stateLock) { captureStarted = false }
    sttErrorListener?.onSttError(SttError(
        code = SttErrorCode.CAPTURE_FAILED,
        message = "AudioCapture failed to start: ${t.message}",
        cause = t
    ))
    // Re-throw to propagate to caller — caller should handle gracefully
    throw t
}
```

**Propagation path:**

- `CaptureManager` → throws → `SttCaptureController.startCapture()` → throws → `SpeechToText.startProcessor()` → needs try/catch around `controller.start()` → emits `SttError` and transitions to safe state.

**Dependency:** 2.2 must be done first (new `SttError` signature).

### 2.5 Add structured error to `ProcessorController.runProcessingLoop` catch-all

**File:** `stt/.../ProcessorController.kt`

The catch-all currently logs and breaks. Add:

```kotlin
catch (t: Throwable) {
    SttLogger.error("code=INTERNAL_EXCEPTION, message=\"${t.message}\"")
    SttLogger.error("code=INTERNAL_EXCEPTION, trace=${t.stackTraceToString()}")
    // NEW: emit structured error
    sttErrorListener?.onSttError(SttError(
        code = SttErrorCode.INTERNAL_EXCEPTION,
        message = "Processing loop failed: ${t.message}",
        cause = t,
        details = listOf("vadActiveMs=${vadActiveMs}", "lastUtteranceMs=${lastUtteranceDurationMs}")
    ))
    isRunning.set(false)
    break
}
```

**Requires:** `ProcessorController` needs an `sttErrorListener` constructor parameter.

### 2.6 Wire `SttErrorListener` into `SttLifecycleStateMachine`

**File:** `stt/.../SttLifecycleStateMachine.kt`

Add optional constructor parameter:

```kotlin
internal class SttLifecycleStateMachine(
    private val sttErrorListener: SttErrorListener? = null
)
```

In `transitionTo()`, when the transition is invalid, emit:

```kotlin
if (!valid) {
    SttLogger.lifecycleE(
        "illegal transition: ${from.javaClass.simpleName} -> ${newState.javaClass.simpleName}"
    )
    sttErrorListener?.onSttError(SttError(
        code = SttErrorCode.PIPELINE_ILLEGAL_STATE,
        message = "Illegal lifecycle transition: ${from.javaClass.simpleName} -> ${newState.javaClass.simpleName}",
        details = listOf("from=${from::class.simpleName}", "to=${newState::class.simpleName}")
    ))
    return false
}
```

**Threading note:** `transitionTo` is already `synchronized(lock)`, so the error listener callback is invoked under that lock. Ensure `sttErrorListener` does not re-enter the state machine (the current `SttErrorListener` implementations in `SttCallbackDispatcher` do not).

**Propagation path:** `SttLifecycleStateMachine` → `SttLifecycleController` → `SpeechToText.lifecycleController` — the listener is injected at construction time in `SpeechToText.init`.

### 2.7 Add compile-time enforcement test

**File:** `stt/.../SttErrorCodeTest.kt`

Add a test that verifies every `SttErrorCode` has a non-`UNKNOWN` category (except `PIPELINE_ILLEGAL_STATE` and `INTERNAL_EXCEPTION` which are intentionally `UNKNOWN`):

```kotlin
@Test
fun everyErrorCodeHasCorrectCategory() {
    val expected = mapOf(
        SttErrorCode.MODEL_LOAD_FAILED to SttErrorCategory.WHISPER_ERROR,
        SttErrorCode.INFERENCE_FAILED to SttErrorCategory.WHISPER_ERROR,
        SttErrorCode.CAPTURE_FAILED to SttErrorCategory.CAPTURE_ERROR,
        SttErrorCode.VAD_FAILED to SttErrorCategory.VAD_ERROR,
        SttErrorCode.PIPELINE_ILLEGAL_STATE to SttErrorCategory.UNKNOWN,
        SttErrorCode.INTERNAL_EXCEPTION to SttErrorCategory.UNKNOWN
    )
    for ((code, expectedCategory) in expected) {
        assertEquals(
            "$code must map to $expectedCategory",
            expectedCategory, code.category
        )
    }
}
```

### 2.8 Add integration test for error delivery

**File:** `stt/.../SttErrorDeliveryTest.kt` (NEW)

Test that a forced model load failure produces a correctly-formed JSON error string on the message listener. This should exercise the full pipeline from `SpeechToText.loadModel()` → `SttError` construction → `dispatchError` → `buildErrorJson`.

(Details depend on test infrastructure — `Robolectric` or `mockk` for Android dependency isolation.)

### 2.9 Remove deprecated `onError` path from `SttCallbackDispatcher`

**File:** `stt/.../SttCallbackDispatcher.kt`

The generic `onError: ((Throwable) -> Unit)?` path converts structured `SttError` back into a raw `Throwable`, losing all structured context. Since no backward compatibility is required:

- Remove `onError` field, setter, and its invocation in `dispatchError()`
- Remove `setOnErrorListener()` method
- Update any test that calls `setOnErrorListener`

The structured `SttErrorListener` path (`setSttErrorListener`) does everything `onError` did, and more.

### 2.10 Update `AppErrorRouter` to use `details` from JSON

**File:** `app/.../AppErrorRouter.kt`

`AppErrorRouter.route()` currently receives the JSON error object and extracts `category`, `code`, `message`. After Phase 2, the JSON will carry a `details` array. Add extraction:

```kotlin
val details = if (errorJson.has("details")) {
    val arr = errorJson.getJSONArray("details")
    (0 until arr.length()).map { arr.getString(it) }
} else {
    emptyList()
}
```

Include `details` in the `outputText` and log output.

### 2.11 Update `SttJsonAdapter` to serialize new fields

**File:** `stt/.../SttJsonAdapter.kt`

In `buildErrorJson()`:

- Category is now accessed from `code` parameter — `category` can be derived from `SttErrorCode`.
- Add `details` array serialization.

If we keep the `category` parameter on `buildErrorJson()`, it becomes redundant with `code` (since code has a compile-time category mapping). **Recommendation:** remove the separate `category` parameter and derive it from `code`:

```kotlin
fun buildErrorJson(code: SttErrorCode, message: String, details: List<String> = emptyList()): String
```

This makes it impossible to pass a mismatched category/code pair.

### 2.12 Update `AppErrorRouter` mapping table

Currently routes on `category` string. After Phase 2, the JSON error still carries `category` (derived from `code.category`), so the router's `uiForCategory` logic continues to work unchanged — but now `category` will actually carry meaningful values like `"WHISPER_ERROR"`, `"CAPTURE_ERROR"`, `"VAD_ERROR"`, `"TIMEOUT"`, `"CONFIG_ERROR"` instead of always `"UNKNOWN"`.

Update the router's `uiForCategory` to actually route on the new meaningful categories (it was written for this, but never got exercised).

---

## Migration path

Since backward compatibility is not required, we make these changes in one pass:

1. Simplify `SttError` (2.2)
2. Add `SttErrorCode.category` (2.1)
3. Update all `SttError(...)` construction sites (2.3)
4. Wire error emission into `CaptureManager` (2.4), `ProcessorController` (2.5), `SttLifecycleStateMachine` (2.6)
5. Update `SttJsonAdapter` + `SttCallbackDispatcher` + remove dead code (2.9, 2.11)
6. Update `AppErrorRouter` (2.10, 2.12)
7. Add tests (2.7, 2.8)
8. Run `./gradlew :stt:testDebugUnitTest :app:testDebugUnitTest` — all tests pass

---

## Files changed (summary)

| File | Phase 2 change |
|------|---------------|
| `stt/.../SttError.kt` | Remove 8 fields, replace `context` with `details` |
| `stt/.../SttErrorCode.kt` | Add `category` property to each enum value |
| `stt/.../SttErrorCategory.kt` | No change (preserved) |
| `stt/.../SttErrorListener.kt` | No change (preserved) |
| `stt/.../SpeechToText.kt` | Update all `SttError(...)` sites; add try/catch around `controller.start()` |
| `stt/.../ModelManager.kt` | Update all `SttError(...)` sites |
| `stt/.../UtteranceAccumulator.kt` | Update `SttError(...)` in `handleSpeechStart()` |
| `stt/.../CaptureManager.kt` | Add `sttErrorListener` param; replace `throw t` with structured error + rethrow |
| `stt/.../ProcessorController.kt` | Add `sttErrorListener` param; emit SttError in catch-all |
| `stt/.../SttLifecycleStateMachine.kt` | Add `sttErrorListener` param; emit SttError on illegal transition |
| `stt/.../SttLifecycleController.kt` | Inject `sttErrorListener` into `SttLifecycleStateMachine` constructor |
| `stt/.../SttCallbackDispatcher.kt` | Remove `onError` field, setter, and invocation |
| `stt/.../SttJsonAdapter.kt` | Derive category from code; add details serialization |
| `stt/.../SttCaptureController.kt` | No change (propagates from CaptureManager) |
| `stt/.../SttModeController.kt` | No change |
| `stt/.../SttProcessingController.kt` | No change forward listener |
| `app/.../AppErrorRouter.kt` | Extract details from JSON; update uiForCategory |
| `stt/.../SttErrorCodeTest.kt` | Add category-mapping test |
| `stt/.../SttErrorDeliveryTest.kt` | NEW — integration test |
| `ERROR_SUBSYSTEM_REWORK_PLAN.md` | Mark Phase 1 complete; reference Phase 2 |
