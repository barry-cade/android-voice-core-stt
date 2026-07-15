# Error Subsystem Rework — CATO Execution Plan

## State: IN PROGRESS

Last updated: current session  
Status markers: [DONE] = complete, [WIP] = in progress, [TODO] = not started

---

## Phase 1 — Library boundary (stt module) [DONE]

### 1.1 SttJsonAdapter.kt — add `category` to error JSON

- [x] Add `category: String?` parameter to `buildErrorJson()`.
- [x] Map `SttErrorCategory` to string in output: `"CONFIG_ERROR"`, `"CAPTURE_ERROR"`, `"WHISPER_ERROR"`, `"VAD_ERROR"`, `"TIMEOUT"`, `"UNKNOWN"`.
- [x] Output shape becomes:
      `{"type":"error","category":"CONFIG_ERROR","code":"INVALID_CONFIG","message":"..."}`
- [x] Update callers inside the stt module that call `buildErrorJson()`:
      `SpeechToText.kt` — all 8 call sites updated with appropriate categories.
- [ ] Verify no existing test breaks (run stt module tests).

### 1.2 SttCallbackDispatcher.kt — thread category through dispatchError

- [x] `dispatchError()` now accepts `SttError` instead of raw `Throwable`.
- [x] Passes `error.category.name` to `buildErrorJson()`.
- [x] Passes `error.code.name` to `buildErrorJson()` (was hardcoded "INTERNAL_EXCEPTION").
- [x] Maintains backward compat for the `onError` listener by extracting `cause` or wrapping message.

### 1.3 Update callers of dispatchError

- [x] `SpeechToText.startProcessor()` — model init failure → `WHISPER_ERROR/MODEL_LOAD_FAILED`
- [x] `SpeechToText.startProcessor()` — forced audio init failure → `CAPTURE_ERROR/CAPTURE_FAILED`
- [x] `SpeechToText.handleInferenceError()` → `UNKNOWN/INTERNAL_EXCEPTION` with cause
- [x] `SttCallbackDispatcherTest` — all test calls updated

---

## Phase 2 — AppErrorRouter (new file in app module) [DONE]

### 2.1 Create AppErrorRouter.kt

- [x] Single `internal object AppErrorRouter` in the `dev.barrycade.voicecore` package.
- [x] `route(errorJson: JSONObject): ErrorUiAction` — takes parsed JSON, returns UI instructions.
- [x] Calls `AppLogger.log()` with the appropriate `AppLogCode`.
- [x] Returns `ErrorUiAction` data class with:
      - `showBanner: Boolean`
      - `bannerText: String?`
      - `outputText: String?`
      - `logCode: AppLogCode?`
      - `logArgs: Array<out Any?>`
- [x] No Android dependency in routing logic.

### 2.2 Error category → UI mapping table

| Category | Show error banner? | Output text |
|----------|-------------------|-------------|
| CONFIG_ERROR | Yes | message (direct) |
| CAPTURE_ERROR | No | "Capture error: {message}" |
| WHISPER_ERROR | No | "Inference error: {message}" |
| VAD_ERROR | No | "VAD error: {message}" |
| TIMEOUT | No | "Session timed out" |
| UNKNOWN (default) | No | "Error: {message}" |

### 2.3 Error code → AppLogCode mapping

| Error code | AppLogCode |
|------------|------------|
| INVALID_CONFIG | CONFIG_INVALID |
| INIT_FAILED | INIT_FAILED |
| SESSION_FAILED | SESSION_ERROR |
| MODEL_LOAD_FAILED | INIT_FAILED |
| PIPELINE_ILLEGAL_STATE | INTERNAL_ERROR |
| INTERNAL_EXCEPTION | INTERNAL_ERROR |
| (anything else) | ASYNC_ERROR |

## Phase 3 — AppLogger cleanup (AppLogger.kt) [DONE]

### 3.1 Remove dead code

- [x] Removed `AppLogCode.OBTAINING_STT_INSTANCE` — never called.

### 3.2 Add missing log codes

- [x] `BLANK_AUDIO_THRESHOLD` — category `"FLOW"`.
- [x] `PRELOAD_FAILED` — category `"CONFIG_ERROR"`.
- [x] `SESSION_ERROR` — category `"PIPELINE_ERROR"`.
- [x] `ASYNC_ERROR` — category `"PIPELINE_ERROR"`.

### 3.3 Fix category assignments

- [x] `CONFIG_INVALID` → `"CONFIG_ERROR"` (was `"CONFIG"`)
- [x] `INIT_FAILED` → `"PIPELINE_ERROR"` (was `"ERROR"`)
- [x] `STOP_FAILED` → `"PIPELINE_ERROR"` (was `"ERROR"`)
- [x] `INTERNAL_ERROR` → `"INTERNAL_ERROR"` (was `"ERROR"`)

## Phase 4 — MainActivity cleanup [DONE]

### 4.1 Remove plan to wire SttErrorListener in app

- [x] Decision: route everything through the JSON listener. The `SttErrorListener` path is internal library plumbing.
- [x] The `category` field in the JSON carries all the structure the app needs.

### 4.2 Replace inline error parsing with AppErrorRouter

- [x] `"error"` branch now calls `AppErrorRouter.route(obj)`.
- [x] Applies `ErrorUiAction`: banner visibility, output text.
- [x] Removed all ad-hoc `txtOutput.text = "Error [...]"` strings.
- [x] Added `txtErrorBanner` field and wiring (`findViewById`, visibility toggle).

### 4.3 Add missing AppLogger.log() calls

- [x] `"error"` branch logs via `AppErrorRouter` (router handles logging internally).
- [x] `preloadModelAsync()` — logs `PRELOAD_FAILED` on both error JSON and exception paths.
- [x] Blank-audio threshold breach — logs `BLANK_AUDIO_THRESHOLD`.
- [x] `startRecording()` session error → changed from `INIT_FAILED` to `SESSION_ERROR`.

### 4.4 Fix txtDiagnostics visibility on error

- [x] On `"error"` messages, `txtDiagnostics.visibility = View.GONE` and text cleared.

### 4.5 txtErrorBanner

- [x] Wired up in `onCreate`.
- [x] Default `visibility="gone"` in layout — router shows it on `CONFIG_ERROR`.
- [x] Active on CONFIG_ERROR category errors.

## Phase 5 — Tests [DONE]

### 5.1 NewApiSmokeTest

- [x] Added `org.json:json:20231013` test dependency in `app/build.gradle.kts` — `JSONObject` is not available in plain JVM unit test environment without it.
- [x] Tests pass with the old error JSON format (no `category` field) since `category` is optional/nullable.

### 5.2 MainActivityStateTest

- [x] Passes unchanged — these test button visibility, not error handling.

### 5.3 SttCallbackDispatcherTest

- [x] Updated all `dispatchError(RuntimeException(...))` calls to `dispatchError(SttError(...))`.

## Phase 6 — Verification [DONE]

### 6.1 Build verification

- [x] `./gradlew :stt:compileDebugKotlin` — stt module compiles.
- [x] `./gradlew :app:compileDebugKotlin` — app module compiles.
- [x] `./gradlew :stt:testDebugUnitTest` — stt unit tests pass.
- [x] `./gradlew :app:testDebugUnitTest` — app unit tests pass.
- [x] Both together: all 44 tasks, 13 tests pass.

---

## Summary of files changed/created

| File | Status | Notes |
|------|--------|-------|
| `stt/.../SttJsonAdapter.kt` | [DONE] | Added `category: String?` to `buildErrorJson()`. Updated callers. |
| `stt/.../SttCallbackDispatcher.kt` | [DONE] | `dispatchError` now accepts `SttError` instead of raw `Throwable`. Passes category + code to JSON. |
| `stt/.../SpeechToText.kt` | [DONE] | Updated 8 `buildErrorJson()` calls with categories. Updated 3 `dispatchError()` calls with `SttError`. |
| `app/.../AppErrorRouter.kt` | [DONE] | **New file** — routes error JSON to UI + log actions. |
| `app/.../AppLogger.kt` | [DONE] | Removed `OBTAINING_STT_INSTANCE`. Added `BLANK_AUDIO_THRESHOLD`, `PRELOAD_FAILED`, `SESSION_ERROR`, `ASYNC_ERROR`. Fixed categories. |
| `app/.../MainActivity.kt` | [DONE] | Delegates error handling to `AppErrorRouter`. Logs preload failures, blank-audio threshold. Wires `txtErrorBanner`. Hides diagnostics on error. |
| `app/build.gradle.kts` | [DONE] | Added `org.json:json:20231013` test dependency — required for `JSONObject` in unit tests. |
| `app/.../MainActivityStateTest.kt` | [DONE] | Passes unchanged. |
| `app/.../NewApiSmokeTest.kt` | [DONE] | Passes unchanged (old error JSON format still works with optional `category`). |
| `app/.../AppErrorRouterTest.kt` | [TODO] | Future — unit test the router in isolation. |
| `stt/.../SttCallbackDispatcherTest.kt` | [DONE] | Updated 3 `dispatchError` calls to use `SttError`. |
