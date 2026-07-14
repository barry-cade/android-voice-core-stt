# STT JSON Boundary Refactor — Plan

## 1. Motivation

The STT module currently exposes a wide public API surface to the app: multiple classes, enums, sealed classes, data types, return codes, triggers, and lifecycle concepts. Many of these are internal pipeline details that leaked outward as the module evolved organically.

The original architectural intent was simpler: the app sends configuration, tells STT when to transcribe, and receives results back. Everything else is STT's internal business.

This refactor restores that boundary by moving to a pure JSON message interface.

## 2. Current State

**Public API surface of `stt` module (14 types visible to app):**

| Type | Kind | App imports it? |
|---|---|---|
| `SpeechToText` | `class` | ✅ Yes |
| `SpeechToTextProvider` | `object` | ✅ Yes |
| `SttConfig` | `data class` | ✅ Yes |
| `SttReturnCode` | `enum class` | ✅ Yes |
| `DrainMode` | `enum class` | ✅ Yes |
| `StartTrigger` | `sealed class` | ✅ Yes |
| `StopTrigger` | `sealed class` | ✅ Yes |
| `SessionResult` | `data class` | No |
| `SttError` | `data class` | No |
| `SttErrorCategory` | `enum class` | No |
| `SttErrorCode` | `enum class` | No |
| `SttErrorListener` | `fun interface` | No |
| `SttTimingSnapshot` | `data class` | No |

**Current app imports:** 7 types from the STT module.

## 3. Target State

**Public API surface of `stt` module (2 entry points + JSON):**

| Type | Kind | Notes |
|---|---|---|
| `SpeechToText` | `class` | Accepts JSON strings in, sends JSON strings out |
| *(JSON strings)* | `String` | The only data type crossing the boundary |

All other types become `internal`. The app knows only how to send JSON in and receive JSON out.

## 4. Guiding Principle

> The app should know nothing about STT except how to send JSON in and receive JSON out.

- The app never imports STT enums, sealed classes, or data classes
- The app never constructs STT config objects
- The app never handles STT return codes, timing objects, or error categories
- STT is fully encapsulated behind a JSON message boundary

## 5. Phased Approach

### Phase 1: Define JSON schemas

Document the JSON contracts that define the boundary. No code changes.

Three schemas:

- **Input config** — what the app sends to `init(configJson)`
- **Output result** — what STT sends to the message callback on success
- **Output error** — what STT sends to the message callback on failure

These become the stable contract between app and STT.

### Phase 2: Add JSON parsing internally

Add internal utilities to the STT module:

- Parse incoming JSON → internal `SttConfig` for pipeline use
- Serialize internal results → outgoing JSON strings
- Serialize internal errors → outgoing JSON strings

These are pure internal helpers. No new public types.

### Phase 3: Add JSON-based public API methods

Add new methods to `SpeechToText`:

```kotlin
fun init(configJson: String): String
fun transcribe()
fun setOnMessageListener(l: (String) -> Unit)
```

These sit alongside the existing API. Old methods still work.

### Phase 4: Deprecate old public API

Annotate existing public methods with `@Deprecated`. Old callers get compile-time warnings but continue to work.

Deprecated methods:

- `setConfig(SttConfig)`
- `initStt(SttConfig)`
- `startSession()`
- `stopAndTranscribe()`
- `resetForNextSession()`
- `destroy()`
- `setDebugOptions()`
- `setOnResultListener()`
- `setOnResultWithTimingListener()`
- `setOnErrorListener()`
- `setSttErrorListener()`

### Phase 5: Make internal types `internal`

Add `internal` modifier to all types that should not cross the boundary:

- `SttConfig`
- `SttReturnCode`
- `DrainMode`
- `SessionResult`
- `SttError`
- `SttErrorCategory`
- `SttErrorCode`
- `SttErrorListener`
- `SttTimingSnapshot`
- `SpeechToTextProvider`
- `StartTrigger` (and subtypes)
- `StopTrigger` (and subtypes)

This step will break the app — must happen simultaneously with updating the app to the new JSON API.

### Phase 6: Remove old public API

Delete deprecated methods from `SpeechToText`. Remove `AppSttConfigLoader` from the app module. The app calls only:

```kotlin
speechToText.init(configJson)
speechToText.transcribe()
```

Results and errors arrive via the JSON message callback.

### Phase 7: Cleanup

- Remove unused files (`AppSttConfigLoader.kt`, config JSON assets if no longer needed)
- Update `README.md`
- Update architecture documentation
- Remove obsolete design constraints

## 6. JSON Schemas (draft)

### Input config (sent by app to `init`)

```json
{
  "modelPath": "/path/to/ggml-tiny.en.bin",
  "language": "en",
  "debugLoggingEnabled": true,
  "energyThreshold": 0.03,
  "preRollMs": 0,
  "stableChunkSizeMs": 500,
  "drainMode": "DRAIN_FROM_HEAD",
  "startType": "MANUAL",
  "stopType": "MANUAL",
  "warmupEnabled": true,
  "warmupDurationMs": 3000
}
```

### Output result (STT → app)

```json
{
  "type": "result",
  "text": "transcribed text here",
  "code": "SUCCESS",
  "timing": {
    "vadActiveMs": 1200,
    "inferenceMs": 450,
    "totalMs": 5200
  }
}
```

### Output error (STT → app)

```json
{
  "type": "error",
  "code": "MODEL_LOAD_FAILED",
  "message": "File not found at /data/app/model.bin"
}
```

## 7. Checklist

| Phase | Description | Code change? | Breaks app? |
|---|---|---|---|
| 1 | Define JSON schemas | No | No |
| 2 | Add JSON parsing internally | Additive | No |
| 3 | Add JSON-based public API | Additive | No |
| 4 | Deprecate old public API | Additive | No |
| 5 | Make internal types `internal` | Breaking | ✅ Yes |
| 6 | Remove old public API | Breaking | ✅ Yes |
| 7 | Cleanup and documentation | Yes | No |

Phases 5 and 6 must be done together with app-side migration.

## 8. Risk Mitigation

- **Phases 2–4 are additive** — no existing code breaks, no behavioural changes
- **Internal tests unaffected** — tests in the `stt` package access internal types directly
- **Old API continues working** through Phase 4, giving the app time to migrate
- **JSON schemas are versioned implicitly** — adding new fields is backward-compatible

## 9. Summary

| Aspect | Before | After |
|---|---|---|
| Public types | 14 | 1 (`SpeechToText`) |
| App imports from STT | 7 types | 1 type |
| Boundary type | Kotlin objects | JSON strings |
| Internal refactor safety | Low | High |
| Caller mental model | Complex | "init, transcribe, receive messages" |

The JSON boundary is not a constraint — it is a shield. It protects the app from STT internals, protects STT from external coupling, and protects the architecture from drift.
