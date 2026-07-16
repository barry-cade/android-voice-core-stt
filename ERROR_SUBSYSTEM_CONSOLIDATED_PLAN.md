# Error Subsystem — Consolidated Execution Plan

**Audit ref:** CATO gold-standard audit (current session)  
**Previous plans superseded:** `ERROR_SUBSYSTEM_REWORK_PLAN.md`, `ERROR_SUBSYSTEM_PHASE2_PLAN.md`, `ERROR_SUBSYSTEM_CHECKLIST.md`  
**Owner:** Single developer — no backward compatibility required  
**Principle:** One error delivery path. Every failure reaches the structured listener.

---

## Architecture Decision Record

### ADR-1: Single error delivery path

All errors — synchronous and asynchronous — go through `SttCallbackDispatcher.dispatchError(SttError)`. No direct calls to `SttJsonAdapter.buildErrorJson()` from business logic. The return values of `loadModel()` and `startSession()` become `SttError?` (null on success, non-null on error) instead of raw JSON strings.

**Rationale:** Eliminates the dual-path asymmetry (Critical 1). The `SttErrorListener` always receives the error. The JSON message listener receives it via `dispatchError()` fan-out. The JSON is always consistent.

### ADR-2: `SttError` as return type from lifecycle methods

`loadModel()`, `startSession()`, and `init()` return `SttError?` instead of `String`. The JSON serialisation is the `SttCallbackDispatcher`'s job — the lifecycle methods just report success/failure.

**Rationale:** Removes the temptation to construct ad-hoc error JSON. Makes the API type-safe. The caller checks `if (error != null)` instead of string-matching for `"type":"error"`.

### ADR-3: `ModelManager` receives listener at construction, always non-null in production

`SttErrorListener` becomes a required constructor parameter of `ModelManager` (not nullable). `SpeechToText` reconstructs `ModelManager` in `loadModel()` once the listener is registered, just as it already does for `CaptureManager`.

**Rationale:** Eliminates the silent-swallow path (Critical 2). Makes the dependency explicit.

### ADR-4: `buildErrorJson` takes `SttErrorCode`, not `String`

`SttJsonAdapter.buildErrorJson(code: SttErrorCode, ...)` — derives `category` from `code.category`, derives the JSON `"code"` string from `code.name`. Makes it impossible to pass an invalid code.

**Rationale:** Eliminates ad-hoc codes like `"INIT_FAILED"` and `"SESSION_FAILED"`. Forces all error paths through the enum. Simplifies `AppErrorRouter` mapping.

### ADR-5: `CaptureManager` emits error and does NOT re-throw

The `throw t` in `beginPcmCapture()` and `restartCapture()` becomes a return of the error state to the caller. `CaptureManager` communicates failure through the listener, not through exceptions.

**Rationale:** Eliminates the crash path (Critical 3). The caller (`SttCaptureController` → `SpeechToText`) checks the result rather than catching an exception. The current re-throw pattern creates an unhandled exception path.

### ADR-6: `UtteranceAccumulator.sttErrorListener` becomes constructor parameter

Replace `internal var sttErrorListener: SttErrorListener? = null` with a constructor parameter.

**Rationale:** Eliminates the mutable-null-default pattern (Notable 3). Makes the dependency explicit at construction time.

---

## Execution Phases

### Phase 0: Baseline verification

```
./gradlew :stt:compileDebugKotlin :app:compileDebugKotlin
./gradlew :stt:testDebugUnitTest :app:testDebugUnitTest
```

Confirm all tasks pass before any changes.

---

### Phase 1 — `SttJsonAdapter` type strengthening

**Files:** `SttJsonAdapter.kt`

**Goal:** Make `buildErrorJson()` take `SttErrorCode` instead of `String`, deriving `category` and `code` name from the enum.

**Changes:**

1. Change signature:
```kotlin
fun buildErrorJson(
    code: SttErrorCode,
    message: String,
    details: List<String> = emptyList()
): String
```

Body derives:
- `"code"` from `code.name`
- `"category"` from `code.category.name`
- Serialises `details` array (already done)

2. Remove the separate `category: String?` parameter.

3. Update all callers:
   - `SttCallbackDispatcher.dispatchError()` — already has `SttError`, uses `error.code`
   - `SpeechToText.loadModel()` — 1 direct call → remove, replace with `dispatchError()`
   - `SpeechToText.startSession()` — 5 direct calls → remove, replace with `dispatchError()`
   - `SpeechToText` companion — 1 direct call → remove, replace with `dispatchError()`

**Test impact:** `SttJsonAdapter` is an `internal object` with no direct tests in the test suite. The `ReturnCodeMappingTest` uses `buildResultJson` and `buildDebugJson` — verify no impact.

**Dependency:** Must be done before Phase 3 (which changes all callers).

---

### Phase 2 — `SttError` return type for lifecycle methods

**Files:** `SpeechToText.kt`, `SttError.kt` (add factory), `SttReturnCode.kt` (add `FAILURE` code)

**Goal:** `loadModel()`, `startSession()`, and `init()` return `SttError?` instead of `String`. The caller checks the return value. No more raw JSON string returns for error signalling.

**Changes in `SttError.kt`:**

Add a factory:
```kotlin
internal fun SttError.Companion.success(): SttError? = null
```

(This is a convention — `SttError?` with `null` meaning success.)

**Changes in `SpeechToText.kt`:**

```kotlin
fun loadModel(configJson: String): SttError? {
    // ... parse config, catch → dispatchError(SttError(CONFIG_PARSE_FAILED, ...)), return error

    synchronized(stateLock) {
        // ... existing guards, but return SttError? instead of JSON string
        if (sessionConfig != null || lifecycleController.currentState is SttLifecycleState.READY) {
            return null  // success
        }

        modelManager.updateModelPath(sessionCfg.modelPath)
        if (!modelManager.loadModelIfNeeded()) {
            sessionConfig = null
            val error = SttError(
                code = SttErrorCode.MODEL_LOAD_FAILED,
                message = "Model load failed"
            )
            callbackDispatcher.dispatchError(error)
            return error
        }

        // ... warmup, capture manager reconstruction, scaffolding ...

        lifecycleController.onReady()
        return null  // success
    }
}

fun startSession(): SttError? {
    synchronized(stateLock) {
        val cfg = sessionConfig
        if (cfg == null) {
            val error = SttError(
                code = SttErrorCode.CONFIG_PARSE_FAILED,
                message = "loadModel() must be called before startSession()"
            )
            callbackDispatcher.dispatchError(error)
            return error
        }
        // ... all other guard clauses -> dispatchError + return error ...

        captureController.startCapture(modeController.isManualMode())
        // ... start processor ...
        return null  // success
    }
}
```

**Changes in `SttReturnCode.kt`:**

Add `FAILURE` for the case where `dispatchError` already sent the structured error but the return value still needs to indicate failure. The caller checks: `val error = stt.loadModel(json); if (error != null) { /* error already dispatched */ }`.

Alternatively: keep returning `String` from the **public API** but have the method construct the string inside `dispatchError` — no, that reintroduces the dual path.

**Better approach:** Change the return type to `SttError?` everywhere. The JSON string was a convenience for the app; but the app now uses `setOnMessageListener`. The companion object methods are also internal or app-facing. The app's `stt.init()` / `stt.loadModel()` calls can just discard the returned `SttError?` since they get the error via the message listener.

**Update companion object:**

```kotlin
fun loadModel(context: Context, configJson: String): SttError? {
    val stt = instance ?: synchronized(this) { ... }
    return stt.loadModel(configJson)
}
```

**Update `init()` in SpeechToText:**

```kotlin
fun init(configJson: String): SttError? {
    val loadError = loadModel(configJson)
    if (loadError != null) return loadError
    return startSession()
}
```

Remove `hasJsonError()` and `hasJsonCode()` — no longer needed.

**Update `MainActivity.kt` (app module):**

Change `stt.init(...)` / `stt.loadModel(...)` / `stt.startSession()` call sites to handle `SttError?` return. The error is already dispatched via the message listener, so the app just checks `if (error != null) return` (or similar).

**Dependency:** Phase 1 must be complete (no more `buildErrorJson(String, ...)` calls in `SpeechToText`).

---

### Phase 3 — `ModelManager` listener reconstruction

**Files:** `SpeechToText.kt`, `ModelManager.kt`

**Goal:** `ModelManager` always has a non-null `SttErrorListener` in production. The initial construction with `null` is eliminated.

**Changes:**

1. **`ModelManager.kt`:** Change `sttErrorListener` from `SttErrorListener?` to `SttErrorListener`. It is required at construction.

```kotlin
internal class ModelManager(
    private var modelPath: String,
    private val sttErrorListener: SttErrorListener,  // non-null
    private val whisperModel: WhisperModel = WhisperBridge
)
```

All `sttErrorListener?.onSttError(...)` calls become `sttErrorListener.onSttError(...)`.

2. **`SpeechToText.kt` init block:** Remove `ModelManager` construction from `init`. Delay it until `loadModel()`.

```kotlin
// In init block:
// modelManager = ModelManager(modelPath = "", sttErrorListener = null, ...)
// REMOVED — moved to loadModel()

// Keep declaration only:
private var modelManager: ModelManager? = null
```

3. **`SpeechToText.kt` `loadModel()`:** Construct `ModelManager` inside `loadModel()` after we have the listener:

```kotlin
fun loadModel(configJson: String): SttError? {
    // ... parse config (dispatchError on failure) ...

    synchronized(stateLock) {
        // ... idempotency guard ...

        val sessionCfg = SttSessionConfig.from(sttConfig)

        // CONSTRUCT ModelManager HERE with the real listener
        val listener = callbackDispatcher.getSttErrorListener()
            ?: return SttError(SttErrorCode.INTERNAL_EXCEPTION, "No error listener registered")
        modelManager = ModelManager(
            modelPath = sessionCfg.modelPath,
            sttErrorListener = listener,
            whisperModel = whisperModel
        )

        if (!modelManager!!.loadModelIfNeeded()) {
            // dispatchError already called inside loadModelIfNeeded
            return SttError(SttErrorCode.MODEL_LOAD_FAILED, "Model load failed")
        }

        // ... rest of loadModel ...
    }
}
```

4. **Update all references to `modelManager`** to use `modelManager!!` or safe-call `modelManager?.let { ... }`.

The `modelManager` is guaranteed non-null after `loadModel()` succeeds. All methods that use it (`startProcessor()`, `transcribe()`, `startSession()`) check `sessionConfig != null` first, which is only set after `modelManager` is constructed.

**Alternate (less invasive):** Keep the init-block construction but reconstruct `ModelManager` in `loadModel()` when the listener becomes available:

```kotlin
// In loadModel():
if (modelManager.sttErrorListener == null) {
    val listener = callbackDispatcher.getSttErrorListener()
    if (listener != null) {
        modelManager = ModelManager(
            modelPath = sessionCfg.modelPath,
            sttErrorListener = listener,
            whisperModel = whisperModel
        )
    }
}
```

This is less invasive but retains the null-listener construction for a brief window. Given virginal codebase, **prefer the full reconstruction**.

---

### Phase 4 — `CaptureManager` error handling: emit without re-throw

**Files:** `CaptureManager.kt`, `SttCaptureController.kt`, `SpeechToText.kt`, `SessionManager.kt`

**Goal:** `CaptureManager.beginPcmCapture()` and `restartCapture()` emit `SttError(CAPTURE_FAILED, ...)` through the listener and return failure state, rather than re-throwing.

**Option A (preferred):** Return `Boolean` from `beginPcmCapture()` and `restartCapture()`.

```kotlin
// CaptureManager.kt
override fun beginPcmCapture(): Boolean {
    // ...
    try {
        audioCapture.start()
        return true
    } catch (t: Throwable) {
        synchronized(stateLock) { captureStarted = false }
        sttErrorListener?.onSttError(SttError(
            code = SttErrorCode.CAPTURE_FAILED,
            message = "AudioCapture failed to start: ${t.message}",
            cause = t
        ))
        return false
    }
}
```

Update `SessionManager` interface:
```kotlin
fun beginPcmCapture(): Boolean  // true = success, false = failure
fun restartCapture(): Boolean   // same
```

Propagation:
```
CaptureManager.beginPcmCapture() --false--> SttCaptureController.startCapture()
  --false--> SpeechToText.startSession() -- dispatchError + return error
```

**Option B (less invasive):** Keep the `throw t` but catch it in `SpeechToText.startSession()` and `startProcessor()`.

Given ADR-5, **use Option A**. The interface change is small (`SessionManager` is `internal`) and the benefit (no exception-based control flow) aligns with the Kotlin Mini-PDP rules.

**Changes in `SttCaptureController.kt`:**

```kotlin
fun startCapture(manualMode: Boolean): Boolean {
    val pcmStarted = sessionManager.beginPcmCapture()
    if (!pcmStarted) return false
    if (!manualMode) {
        sessionManager.beginSttProcessing()
    }
    return true
}
```

**Changes in `SpeechToText.kt`:**

```kotlin
fun startSession(): SttError? {
    // ... guard clauses ...

    val captureStarted = captureController.startCapture(modeController.isManualMode())
    if (!captureStarted) {
        val error = SttError(
            code = SttErrorCode.CAPTURE_FAILED,
            message = "Failed to start audio capture"
        )
        callbackDispatcher.dispatchError(error)
        transitionPipelineToIdleLocked("capture start failed")
        currentSessionEpoch = 0L
        lifecycleController.onStop()
        lifecycleController.onReset()
        return error
    }

    // ... rest of startSession ...
}
```

---

### Phase 5 — `UtteranceAccumulator` constructor parameter

**Files:** `UtteranceAccumulator.kt`, `SttProcessingController.kt`

**Goal:** Move `sttErrorListener` from a mutable `var` to a constructor parameter.

**Changes in `UtteranceAccumulator.kt`:**

```kotlin
internal class UtteranceAccumulator(
    private val sampleRate: Int = 16000,
    private val preRollMs: Int = 100,
    private val vad: Vad = Vad(),
    private val utteranceMaxDurationMs: Int = 30000,
    private val utteranceSilenceTimeoutMs: Int = 5000,
    private val debugLoggingEnabled: Boolean = false,
    private val sttErrorListener: SttErrorListener? = null  // NEW
) {
    // REMOVE: internal var sttErrorListener: SttErrorListener? = null

    // ... rest of class unchanged ...
}
```

Update the secondary constructor that takes `RuntimeSttConfig` — add `sttErrorListener` parameter to both constructors (or keep the secondary constructor simple and have `SttProcessingController` use the primary constructor directly).

**Changes in `SttProcessingController.kt`:**

Replace:
```kotlin
utteranceAccumulator = UtteranceAccumulator(config)
utteranceAccumulator.sttErrorListener = sttErrorListener
```
With:
```kotlin
utteranceAccumulator = UtteranceAccumulator(
    config = config,
    sttErrorListener = sttErrorListener
)
```

---

### Phase 6 — `SttLifecycleStateMachine` / `SttLifecycleController` listener wiring

**Files:** `SttLifecycleStateMachine.kt`, `SttLifecycleController.kt`, `SpeechToText.kt`

**Goal:** Verify the listener is wired correctly (it already is — confirmed by audit). But ensure `SttLifecycleController` forwards the listener to `SttLifecycleStateMachine` as a constructor parameter (it does: `SttLifecycleStateMachine(sttErrorListener = sttErrorListener)`).

Minor improvement: make `SttErrorListener` a required (non-null) parameter on `SttLifecycleStateMachine` and `SttLifecycleController` in production.

**Changes:**

`SttLifecycleStateMachine`:
```kotlin
internal class SttLifecycleStateMachine(
    private val sttErrorListener: SttErrorListener  // non-null
)
```

`SttLifecycleController`:
```kotlin
internal class SttLifecycleController(
    private val sttErrorListener: SttErrorListener  // non-null
)
```

**Test impact:** Tests that construct `SttLifecycleStateMachine` or `SttLifecycleController` must provide a listener. Use `SttErrorListener { }` (SAM conversion with empty lambda).

---

### Phase 7 — `AppErrorRouter` details extraction

**Files:** `AppErrorRouter.kt`, `ErrorUiAction.kt`

**Goal:** Extract `details` from the error JSON and include in output/log.

**Changes in `AppErrorRouter.kt`:**

```kotlin
fun route(errorJson: JSONObject): ErrorUiAction {
    val code = errorJson.optString("code", "UNKNOWN")
    val message = errorJson.optString("message", "Unknown error")
    val category = errorJson.optString("category", "UNKNOWN")

    val details = if (errorJson.has("details")) {
        val arr = errorJson.getJSONArray("details")
        (0 until arr.length()).map { arr.getString(it) }
    } else {
        emptyList()
    }

    val logCode = logCodeForErrorCode(code)
    val (showBanner, outputText) = uiForCategory(category, code, message)

    val outputWithDetails = if (details.isNotEmpty()) {
        "$outputText\n\nDetails:\n${details.joinToString("\n")}"
    } else {
        outputText
    }

    return ErrorUiAction(
        showBanner = showBanner,
        bannerText = if (showBanner) "STT configuration error: $message\nCheck config and restart the app." else null,
        outputText = outputWithDetails,
        logCode = logCode,
        logArgs = arrayOf("$code: $message", *details.toTypedArray())
    )
}
```

Remove the `// If category is UNKNOWN, try to derive UI action from code` comment — the `uiForCategory` fallthrough to `else` already handles this.

---

### Phase 8 — Test updates

**Files:** Multiple test files

**Changes:**

1. **`SttErrorCodeTest.kt`:** Already has `everyErrorCodeHasCorrectCategory()`. No changes needed except verifying the enum now has 8 codes (not 6 as the old checklist said).

2. **`SttCallbackDispatcherTest.kt`:** Update `dispatchError(SttError(...))` calls if `SttError` signature changed (it hasn't — still `code`, `message`, `details`, etc.). The test already uses the new signature.

3. **`SttLifecycleStateTest.kt`:** Update `SttLifecycleStateMachine` construction — requires non-null `SttErrorListener`.

4. **`SttStopPathTest.kt`:** Same — update `SttLifecycleStateMachine` construction.

5. **`SttWarmupTest.kt`:** Update `SttError(...)` construction if needed.

6. **`CaptureManagerTest.kt`:** Update `CaptureManager()` construction — may need to pass `sttErrorListener` parameter.

7. **`ModelManagerTest.kt`:** Update `ModelManager()` construction — `sttErrorListener` is now required (non-null). Tests already pass a listener via `object : SttErrorListener { ... }`.

8. **`SpeechToTextTest.kt` / `SpeechToTextNewApiTest.kt`:** Update return value handling from `loadModel()` / `startSession()` / `init()` — now returns `SttError?` instead of `String`.

9. **`NewApiSmokeTest.kt` (app):** Update if API changed. Since the app uses `setOnMessageListener`, the return value from `init()` is ignored — but update the test to suppress the unused-value warning.

10. **`ProcessorControllerTest.kt`:** Update `ProcessorController` construction — may need to pass `sttErrorListener` parameter. Tests use `FakeAudioSource`.

11. **`SttInferenceControllerTest.kt`:** Update `ModelManager` construction — `sttErrorListener` is now non-null.

---

### Phase 9 — Integration test (`SttErrorDeliveryTest.kt`)

**File:** NEW — `stt/src/test/java/dev/barrycade/voicecore/stt/SttErrorDeliveryTest.kt`

**Goal:** Verify the full error pipeline end-to-end using fake implementations.

```kotlin
class SttErrorDeliveryTest {

    @Test
    fun `model load failure produces correct JSON error via message listener`() {
        val stt = SpeechToText(
            context = null,
            whisperModel = FakeWhisperModel().apply { failOnLoad = true },
            captureManager = FakeCaptureManager()
        )

        val capturedMessages = mutableListOf<String>()
        stt.setOnMessageListener { capturedMessages.add(it) }

        val json = """{"modelPath":"/bad/path","energyThreshold":0.03,"preRollMs":100,"stableChunkSizeMs":500,"drainMode":"DRAIN_FROM_NEXT_FRAME"}"""
        val error = stt.loadModel(json)

        assertNotNull("loadModel must return an error on model load failure", error)
        assertEquals(SttErrorCode.MODEL_LOAD_FAILED, error.code)

        // Verify JSON message was dispatched
        assertTrue("message listener must have received the error", capturedMessages.isNotEmpty())
        val lastMessage = capturedMessages.last()
        assertTrue("message must contain error type", lastMessage.contains("\"type\":\"error\""))
        assertTrue("message must contain WHISPER_ERROR category", lastMessage.contains("\"category\":\"WHISPER_ERROR\""))
        assertTrue("message must contain MODEL_LOAD_FAILED code", lastMessage.contains("\"code\":\"MODEL_LOAD_FAILED\""))
    }

    @Test
    fun `config parse failure produces correct JSON error`() {
        // Similar — invalid JSON triggers CONFIG_PARSE_FAILED
    }

    @Test
    fun `capture failure during startSession produces correct JSON error`() {
        // FakeCaptureManager with failOnStart = true
    }
}
```

---

### Phase 10 — Cleanup and final verification

1. Remove `hasJsonError()` and `hasJsonCode()` from `SpeechToText.kt` — no longer needed.

2. Remove `INIT_FAILED` and `SESSION_FAILED` references from `AppErrorRouter.logCodeForErrorCode()` — these codes no longer exist in the JSON output (they've been replaced by enum values).

3. Update `AppLogCode` mapping in `AppErrorRouter` to use only `SttErrorCode.name` values:
   - `"MODEL_LOAD_FAILED"` → `AppLogCode.INIT_FAILED`
   - `"INFERENCE_FAILED"` → `AppLogCode.ASYNC_ERROR`
   - `"CAPTURE_FAILED"` → `AppLogCode.SESSION_ERROR`
   - `"VAD_FAILED"` → `AppLogCode.INTERNAL_ERROR`
   - `"CONFIG_PARSE_FAILED"` → `AppLogCode.CONFIG_INVALID`
   - `"INFERENCE_TIMEOUT"` → `AppLogCode.ASYNC_ERROR`
   - `"PIPELINE_ILLEGAL_STATE"` → `AppLogCode.INTERNAL_ERROR`
   - `"INTERNAL_EXCEPTION"` → `AppLogCode.INTERNAL_ERROR`

4. Run full verification:
```
./gradlew :stt:compileDebugKotlin :app:compileDebugKotlin
./gradlew :stt:testDebugUnitTest :app:testDebugUnitTest
```

---

## Files changed (summary)

| File | Phase | Change |
|------|-------|--------|
| `stt/.../SttJsonAdapter.kt` | 1 | `buildErrorJson` takes `SttErrorCode` not `String`; removes `category` param; derives internally |
| `stt/.../SpeechToText.kt` | 2, 3, 4 | Return type `SttError?`; `ModelManager` reconstructed with listener; `startSession` handles capture failure gracefully |
| `stt/.../SttReturnCode.kt` | 2 | May need `FAILURE` code |
| `stt/.../SessionManager.kt` | 4 | `beginPcmCapture()`, `restartCapture()` return `Boolean` |
| `stt/.../CaptureManager.kt` | 4 | No re-throw; return false on failure |
| `stt/.../SttCaptureController.kt` | 4 | `startCapture()` returns `Boolean` |
| `stt/.../ModelManager.kt` | 3 | `sttErrorListener` non-null |
| `stt/.../UtteranceAccumulator.kt` | 5 | `sttErrorListener` as constructor param |
| `stt/.../SttProcessingController.kt` | 5 | Constructor-style listener wiring |
| `stt/.../SttLifecycleStateMachine.kt` | 6 | `sttErrorListener` non-null |
| `stt/.../SttLifecycleController.kt` | 6 | `sttErrorListener` non-null |
| `app/.../AppErrorRouter.kt` | 7, 10 | Extract `details`; update log code map |
| `app/.../MainActivity.kt` | 2 | Handle `SttError?` return from lifecycle methods |
| `stt/.../SttErrorCodeTest.kt` | 8 | Verify 8 codes, category mapping (already done) |
| `stt/.../SttCallbackDispatcherTest.kt` | 8 | Update `SttError` construction |
| `stt/.../SttLifecycleStateTest.kt` | 8 | Non-null listener |
| `stt/.../SttStopPathTest.kt` | 8 | Non-null listener |
| `stt/.../SttWarmupTest.kt` | 8 | Update `SttError` construction |
| `stt/.../CaptureManagerTest.kt` | 8 | Update construction |
| `stt/.../ModelManagerTest.kt` | 8 | Non-null listener |
| `stt/.../ProcessorControllerTest.kt` | 8 | Update construction |
| `stt/.../SttInferenceControllerTest.kt` | 8 | Non-null listener |
| `stt/.../SpeechToTextTest.kt` | 8 | `SttError?` return value |
| `app/.../NewApiSmokeTest.kt` | 8 | `SttError?` return value |
| `stt/.../SttErrorDeliveryTest.kt` | 9 | **NEW** — integration test |
| `ERROR_SUBSYSTEM_REWORK_PLAN.md` | — | Mark superseded |
| `ERROR_SUBSYSTEM_PHASE2_PLAN.md` | — | Mark superseded |
| `ERROR_SUBSYSTEM_CHECKLIST.md` | — | Mark superseded |
| `ERROR_SUBSYSTEM_CONSOLIDATED_PLAN.md` | — | THIS FILE |

---

## Risk table

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Test breakage from `SttError?` return type | High | Medium | Fix tests in Phase 8; all use fake implementations |
| Missed caller of `buildErrorJson(String, ...)` | Medium | Low | Compiler error on type mismatch — grep for remaining callers |
| `ModelManager` null-safety issues | Medium | Medium | Use `modelManager!!` only where `sessionConfig != null` guarantees construction; add `requireNotNull` |
| `AppErrorRouter` receives new codes it doesn't map | Low | Low | Default to `ASYNC_ERROR` in `logCodeForErrorCode()` |
| Phase interdependency deadlock | Low | Low | Phases are linear; no circular dependencies |

---

## Execution order (recommended)

```
Phase 0: Baseline verification
Phase 1: SttJsonAdapter type strengthening
Phase 2: SttError? return type (changes all callers)
Phase 3: ModelManager listener reconstruction
Phase 4: CaptureManager no-rethrow
Phase 5: UtteranceAccumulator constructor param
Phase 6: SttLifecycleStateMachine non-null listener
Phase 7: AppErrorRouter details extraction
Phase 9: Integration test (before Phase 8 to validate)
Phase 8: Test updates
Phase 10: Cleanup + final verification
```

**Why Phase 9 before Phase 8:** The integration test validates the new behaviour. If it passes, the unit tests are cosmetic. If it fails, we haven't wasted time updating unit tests for a broken design.

---

## Diff strategy

Each phase is a self-contained commit (if using Git) with the following message format:

```
ERROR-[PhaseNumber]: [One-line description]

[Detailed description of changes, referencing audit findings]

Files:
- path/to/file.kt: change description
- path/to/file.kt: change description
```

Phase 10 (cleanup) can be squashed into Phase 0 or left as a separate "final polish" commit.
