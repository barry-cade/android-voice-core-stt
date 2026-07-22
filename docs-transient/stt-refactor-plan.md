# STT Refactor Plan: SpeechToText Public API

**Version:** 1.0  
**Author:** CATO (DeepSeek)  
**Date:** 2025-01  
**Goal:** Refactor `SpeechToText` to expose exactly three public methods:
`init(configJson)`, `configure(configJson)`, `transcribe(): String`

---

## 1. Current State Summary

### 1.1 Public Surface (to be removed)

The current `SpeechToText` (and its companion) exposes too much:

| Method | Visibility | Fate |
|--------|-----------|------|
| `init(configJson): SttError?` | public | **Keep** — but return `String` (JSON), not `SttError?` |
| `transcribe()` | public | **Keep** — but return `String`, not `Unit` |
| `reconfigure(configJson): SttError?` | public | **Rename** to `configure()` |
| `loadModel(context, configJson): SttError?` | companion public | **Remove** — merge into `init()` |
| `startSession()` | companion public | **Remove** — internal only |
| `setOnMessageListener(listener)` | companion public | **Remove** — no listeners in new API |
| `resetForTest()` | internal | **Keep** |
| `processStart()` | internal | **Remove** — not needed in new design |
| `currentPipelineStageForTest()` | internal | **Keep** for testability |

### 1.2 Internal Architecture Worth Preserving

The _internal_ pipeline is solid:

- `WhisperBridge` / `WhisperModel` — model loading, warm-up, inference
- `Vad`, `VadGate`, `VadConfig` — VAD speech detection
- `UtteranceAccumulator` — silence-based utterance boundary detection
- `CaptureManager` — AudioRecord PCM capture + session buffer
- `SttProcessingController` — Auto-mode orchestrator (VAD + accumulator + processor)
- `MinimalPollingController` — Manual-mode PCM polling loop
- `SttModeController` — mode selection
- `SttJsonAdapter` — JSON ↔ internal types (boundary adapter)
- `SttCallbackDispatcher` — outgoing JSON dispatch
- `SttLifecycleController` — state machine (UNINITIALISED → READY → RECORDING → FINALISING → STOPPED)
- `RmsSampler`, `AudioPreProcessor`, `AudioSource`, `StopStrategy`, `StartStrategy` — stable internal utilities

These stay. The refactor is a **facade change** — the guts remain.

---

## 2. Target Architecture

### 2.1 New Public API Shape

```kotlin
class SpeechToText {
    fun init(configJson: String): String
    fun configure(configJson: String): String
    fun transcribe(): String
}
```

All three return JSON strings. No listeners. No callbacks. No object-level API.

### 2.2 Internal Changes Summary

| Component | Change |
|-----------|--------|
| `SpeechToText.kt` | Rewrite public methods; flatten companion; remove listener pattern |
| `SttSessionConfig` | Add `language` field (currently lost in flattening) |
| `RuntimeSttConfig` | Add `language` field (init-only, carried through) |
| `SttJsonAdapter` | Add `language` to output serialisation; add `partialsEnabled`, `autoReturn`, `sttMode`, `grammar` fields |
| `SttConfig` | Add `language`, `partialsEnabled`, `autoReturn`, `sttMode`, `grammar` (all optional/non-breaking) |
| `SttCallbackDispatcher` | Remove listener pattern; change to direct return-value path |
| `CaptureManager` | Make capture always-on; no explicit start/stop from caller |
| Removed classes | `SttReadyListener`, `SttErrorListener`, `SttCallbackDispatcher` (replaced by direct JSON return) |

### 2.3 Always-On Pipeline Design

The key internal shift: the pipeline is **always running** after `init()`.

```
init() → model load + warm-up → start audio capture → enter wake-word mode
         ↓
configure() → update config in-place → no restart
         ↓
transcribe() → end current utterance → run inference → return JSON → re-enter listen mode
```

This means:
- `CaptureManager` starts in `init()` and never stops (until JVM shutdown hook)
- VAD + accumulator run continuously
- Wake-word detection runs continuously when configured
- `transcribe()` finalises the _current_ utterance, runs inference, and returns

No `startSession()` / `stopSession()` — those become internal pipeline transitions.

### 2.4 Threading Model (unchanged)

| Thread | Responsibility |
|--------|---------------|
| Caller thread | `init()`, `configure()`, `transcribe()` — serialised by `stateLock` |
| Audio capture (T1) | AudioRecord read → PCM frame enqueue |
| Capture drain (T2) | Warm-up buffering into session buffer |
| Processor (T3) | VAD, utterance accumulation, PCM polling |
| Whisper executor (T4) | Model load/unload/inference |

`transcribe()` blocks the caller thread up to `silenceTimeoutMs` waiting for utterance completion.

---

## 3. Step-by-Step Plan

### Phase 1: JSON Config Schema Update

**Files:** `SttConfig.kt`, `SttJsonAdapter.kt`, `RuntimeSttConfig.kt`, `SttSessionConfig.kt`

**What:**

1. Add these fields to `SttConfig` (all optional with defaults):
   - `language: String` — init-only, default `"en"`
   - `grammar: String?` — context/grammar hint, default `null`
   - `sttMode: String` — `"WAKE_WORD"`, `"COMMAND"`, `"ALWAYS_ON"`, `"PUSH_TO_TALK"`, default `"ALWAYS_ON"`
   - `partialsEnabled: Boolean` — enable partial results, default `false`
   - `autoReturn: Boolean` — auto-return transcript on silence, default `false`

2. Update `SttJsonAdapter.parseConfig()` to parse new fields.

3. Update `RuntimeSttConfig` to carry new fields.

4. Update `SttSessionConfig` to carry `language`.

5. Update `SttJsonAdapter.buildConfigJson()` to serialise all new fields.

6. Remove `TtsEngineConfig.kt` if unused (legacy nesting).

---

### Phase 2: Listen-and-Return Pipeline Mode

**Files:** `SpeechToText.kt`, `CaptureManager.kt`, `SessionManager.kt`

**What:**

1. Change `CaptureManager` to support always-on capture:
   - `start()` called from `init()` — never stopped until JVM shutdown
   - Internal session buffer resets on each utterance boundary (not on start/stop)

2. Remove `startSession()` and `endSession()` from public path:
   - Internal `SessionController.beginSession()` / `endSession()` map to utterance boundaries
   - Not full capture lifecycle

3. `transcribe()` becomes:
   ```
   synchronized(stateLock) {
       if pipeline not running → return empty JSON
       finalise current utterance
       run inference
       return JSON result
       reset for next utterance (re-enter listen mode)
   }
   ```

4. The old `handleUtteranceReady()` auto-mode path also feeds into the same inference path, but the result is returned synchronously from `transcribe()`.

**Key simplification:** No more listener-based result delivery. `transcribe()` **returns** the JSON.

---

### Phase 3: Remove Listener Infrastructure

**Files:** `SttCallbackDispatcher.kt`, `SttReadyListener.kt`, `SttErrorListener.kt`, `SpeechToText.kt`

**What:**

1. Replace `SttCallbackDispatcher` with direct JSON construction in `SpeechToText`:
   - `dispatchMessage()` → build JSON string and return it
   - `dispatchError()` → build JSON error string and return it

2. Remove `SttReadyListener` — no longer needed.

3. Remove `SttErrorListener` interface — replace with a simple `(SttError) -> String` transform.

4. Update all internal callers that dispatch through the callback dispatcher.

---

### Phase 4: `reconfigure()` → `configure()`

**Files:** `SpeechToText.kt`, `MainActivity.kt` (app module)

**What:**

1. Rename `reconfigure()` to `configure()`.

2. Change return type from `SttError?` to `String` (JSON success/error).

3. Simplify the internal path:
   - Parse new config JSON
   - Apply runtime-safe fields in-place (grammar, wake-word, silence timeout, thresholds, mode, partials flag, auto-return)
   - Do NOT tear down and rebuild pipeline (current `reconfigure` calls `loadModel()` which is heavy)

**The current `reconfigure()` rebuilds the pipeline via `loadModel()` — this is wrong for the new API.** `configure()` must be lightweight.

---

### Phase 5: JVM Shutdown Hook for Cleanup

**Files:** `SpeechToText.kt`

**What:**

1. Register a shutdown hook in `init()` that:
   - Stops audio capture
   - Unloads Whisper model
   - Cleans up buffers

2. No public `shutdown()` method — cleanup is automatic.

3. Use `Runtime.getRuntime().addShutdownHook()`.

---

### Phase 6: `init()` Returns JSON String

**Files:** `SpeechToText.kt`

**What:**

1. Change `init()` return type from `SttError?` to `String`.

2. On success, return: `{"type":"init","status":"ok"}`
3. On failure, return: `{"type":"error","category":"...","code":"...","message":"..."}`

4. Merge `loadModel()` + `startSession()` into a single `init()` call:
   - Load model
   - Start audio capture
   - Enter configured listening mode
   - Return success JSON

---

### Phase 7: `transcribe()` Returns JSON String

**Files:** `SpeechToText.kt`, `SttJsonAdapter.kt`

**What:**

1. Change `transcribe()` from `Unit` to `String`.

2. Return shape:
   - Success: `{"type":"result","text":"...","code":"SUCCESS","timing":{...}}`
   - Silence (no utterance): `{"type":"result","text":"","code":"SILENCE"}`
   - Error: `{"type":"error","code":"...","message":"..."}`

3. Update `SttJsonAdapter.buildResultJson()` to include `"SILENCE"` code.

4. The blocking behaviour:
   - `transcribe()` waits up to `silenceTimeoutMs` for utterance completion
   - If no utterance within timeout, returns `{"type":"result","text":"","code":"SILENCE"}`
   - Never blocks indefinitely
   - Never throws on silence

---

### Phase 8: Test Update

**Files:** `SpeechToTextNewApiTest.kt` (and other test files as needed)

**What:**

1. Update all tests to match new return types (`String` instead of `SttError?`).

2. Remove tests for removed public methods (`setOnMessageListener`, `startSession`, `loadModel`).

3. Add tests for:
   - `transcribe()` returns JSON string
   - `configure()` lightweight path (no pipeline rebuild)
   - `init()` always-on capture
   - Silence timeout returns empty JSON

4. Keep existing internal pipeline tests (VAD, accumulator, etc.) — they shouldn't change.

---

### Phase 9: Clean Up Dead Code

**Files:** Various

**What:**

1. Remove `SttReadyListener.kt` — unused.

2. Remove `SttErrorListener.kt` — unused (replace with direct error handling).

3. Evaluate `SttCallbackDispatcher.kt` — likely removable or reducible to a utility.

4. Remove `TtsEngineConfig.kt` if it's legacy nesting baggage.

5. Remove `SttLifecycleState.kt`, `SttLifecycleStateMachine.kt`, `SttLifecycleController.kt` — **evaluate carefully**. The lifecycle state machine may still be useful internally to prevent invalid state transitions. If kept, it becomes internal-only (already is).

6. Remove `SttEvents.kt` — used for start/stop strategy events; evaluate if needed post-refactor.

7. Review `SttSessionController.kt` — may need simplification since session = utterance boundary now.

---

### Phase 10: Update App Module (`MainActivity.kt`)

**Files:** `app/src/main/java/.../MainActivity.kt`

**What:**

1. Replace `SpeechToText.loadModel(context, json)` with `SpeechToText.init(json)`.

2. Replace `SpeechToText.reconfigure(json)` with `SpeechToText.configure(json)`.

3. Replace `SpeechToText.setOnMessageListener(listener)` with direct `transcribe()` return value use.

4. Remove `SpeechToText.startSession()` calls.

---

## 4. Config Schema (New Fields)

New flat JSON config accepted by both `init()` and `configure()`:

| Key | Type | Init Only | Runtime | Default | Purpose |
|-----|------|-----------|---------|---------|---------|
| `modelPath` | string | yes | no | — | Absolute path to Whisper model |
| `language` | string | yes | no | `"en"` | Language code |
| `energyThreshold` | float | yes (VAD init) | yes | `0.03` | VAD energy threshold |
| `silenceTimeoutMs` | int | no | yes | `1200` | Silence that ends utterance |
| `preRollMs` | int | yes | no | `100` | Pre-roll before utterance |
| `stableChunkSizeMs` | int | yes | no | `500` | Stable speech chunk |
| `drainMode` | string | yes | no | `DRAIN_FROM_NEXT_FRAME` | PCM drain mode |
| `startType` | string | yes | no | `MANUAL` | Start trigger |
| `stopType` | string | no | yes | `MANUAL` | Stop trigger |
| `silenceMs` | int | no | yes | `1200` | Auto-silence threshold |
| `maxDurationMs` | int | no | yes | `30000` | Max utterance duration |
| `warmupEnabled` | bool | yes | no | `false` | Warm-up on load |
| `warmupDurationMs` | int | yes | no | `0` | Warm-up duration |
| `bufferSizeSamples` | int | yes | no | `4000` | AudioRecord buffer |
| `highPassCutoffHz` | int | yes | no | `0` | HPF cutoff |
| `zcrEnabled` | bool | yes | no | `false` | ZCR noise filter |
| `sessionTimeoutMs` | int | yes | no | `0` | Session safety timeout |
| **`sttMode`** | string | no | **yes** | `ALWAYS_ON` | Listening mode |
| **`grammar`** | string? | no | **yes** | `null` | Context/grammar hint |
| **`partialsEnabled`** | bool | no | **yes** | `false` | Enable partials |
| **`autoReturn`** | bool | no | **yes** | `false` | Auto-return on silence |

**`sttMode` values:**
- `ALWAYS_ON` — always listening, transcribe() returns whatever is spoken
- `WAKE_WORD` — listening for wake word, transcribe() waits for wake+utterance
- `COMMAND` — push-to-talk, transcribe() waits for utterance
- `PUSH_TO_TALK` — same as COMMAND (alias)

---

## 5. Risk Areas

### 5.1 Blocking `transcribe()`

The current `transcribe()` returns `Unit` and delivers results via listener. Changing to synchronous blocking return is the biggest behavioural change.

**Mitigation:** The internal pipeline already supports blocking. `UtteranceAccumulator` already has timeout behaviour. The `transcribe()` method just needs to block on a `CountDownLatch` or similar, with the silence timeout as the max wait.

### 5.2 `reconfigure()` → `configure()` Lightweight Path

Current `reconfigure()` calls `loadModel()` which tears down and rebuilds the pipeline. The new `configure()` must apply changes in-place without restarting capture.

**Mitigation:** Split config keys into "requires restart" (init-only) and "runtime-safe". Only runtime-safe keys change in `configure()`. If an init-only key changes, return an error JSON telling the caller to call `init()` again.

### 5.3 Listener Removal

The `SttCallbackDispatcher` is wired through `SttErrorListener` and `SttReadyListener`. Removing these interfaces means changing every error path.

**Mitigation:** Replace the listener pattern with a simple `(SttError) -> String` error-to-JSON lambda passed through construction. Keep `SttError` as internal — it's a good typed error representation. Just don't expose it publicly.

### 5.4 `MainActivity.kt` App Module Impact

The demo app currently uses listeners and manual start/stop. This will need rewriting for the new API.

**Mitigation:** Phase 10 handles this last. The demo app is for testing — it can change freely.

---

## 6. Order of Execution

```
Phase 1: JSON Config Schema Update (add new fields, language init-only)
    ↓
Phase 2: Listen-and-Return Pipeline (always-on capture, transcribe returns JSON)
    ↓
Phase 3: Remove Listener Infrastructure (no more callback dispatcher)
    ↓
Phase 4: reconfigure() → configure() (lightweight in-place config)
    ↓
Phase 5: JVM Shutdown Hook (automatic cleanup)
    ↓
Phase 6: init() Returns JSON String
    ↓
Phase 7: transcribe() Returns JSON String (final return type change)
    ↓
Phase 8: Test Update
    ↓
Phase 9: Clean Up Dead Code
    ↓
Phase 10: Update App Module (MainActivity.kt)
```

Phases 1-5 can be done without changing `SpeechToText.kt`'s public signature.
Phases 6-7 change the return types and are the breaking changes.
Phases 8-10 are cleanup and integration.

---

## 7. Key Design Decisions

### 7.1 Why Always-On Capture?

The spec says "run continuously" and "no start/stop". Always-on capture means:
- No audible click from AudioRecord start/stop between utterances
- VAD and wake-word detection run continuously
- `transcribe()` just freezes the current buffer, runs inference, and returns
- Lower latency for back-to-back utterances

### 7.2 Why JSON Return Instead of Listener?

The Moto brain sends messages and expects replies. A synchronous `transcribe()` that returns JSON maps directly to this pattern:
- No threading complexity in the caller
- No listener registration timing issues
- Easier to reason about: call → wait → result

### 7.3 Why `configure()` Instead of `reconfigure()`?

The spec calls it `configure()`. It's shorter, cleaner, and implies "apply config" rather than "rebuild pipeline". The behavioural change from rebuild-to-apply is intentional.

---

## Files to Modify (Complete List)

| File | Phase | Change |
|------|-------|--------|
| `stt/.../SttConfig.kt` | 1 | Add new fields |
| `stt/.../SttJsonAdapter.kt` | 1, 6, 7 | Parse/serialise new fields |
| `stt/.../RuntimeSttConfig.kt` | 1 | Add language + new fields |
| `stt/.../SttSessionConfig.kt` | 1 | Add language |
| `stt/.../SpeechToText.kt` | 2, 3, 4, 5, 6, 7 | Rewrite public methods |
| `stt/.../CaptureManager.kt` | 2 | Always-on support |
| `stt/.../SessionManager.kt` | 2 | Interface update if needed |
| `stt/.../SttCallbackDispatcher.kt` | 3 | Remove or reduce |
| `stt/.../SttReadyListener.kt` | 3 | Delete |
| `stt/.../SttErrorListener.kt` | 3 | Delete |
| `stt/.../SttLifecycleController.kt` | 3 | Evaluate — may keep internal |
| `stt/.../SttLifecycleState.kt` | 3 | Evaluate — may keep internal |
| `stt/.../SttLifecycleStateMachine.kt` | 3 | Evaluate — may keep internal |
| `stt/.../SttEvents.kt` | 3 | Evaluate |
| `stt/.../SttSessionController.kt` | 2 | Simplify for utterance-boundary |
| `app/.../MainActivity.kt` | 10 | Update for new API |
| `stt/.../test/.../SpeechToTextNewApiTest.kt` | 8 | Update tests |
| `stt/.../test/.../SttErrorDeliveryTest.kt` | 8 | Update tests |
| `stt/.../test/.../StrategyCombinationTest.kt` | 8 | Update tests |
| `stt/.../TtsEngineConfig.kt` | 9 | Delete (legacy nesting) |
