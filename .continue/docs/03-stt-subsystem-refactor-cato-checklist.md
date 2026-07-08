# STT Refactor — Cato Checklist

## Intent

- Separate configs per strategy (man/man, man/auto) + shared config.
- STT returns only (`returnCode` + `transcript`).
- Caller handles all human messaging.
- Apply changes incrementally and safely.

## Checklist Steps (Cato executes one per session)

### 1. Add unified return codes (no behaviour change) ✅

- Create `SttReturnCode` enum. ✅
- Add `code` field to `SessionResult`. ✅
- Map existing outcomes to codes. ✅
- Do not change logic yet. ✅
- Committed: `1d76516`

### 2. Split configs ✅

- Create `SharedSttConfig`, `ManManConfig`, `ManAutoConfig`. ✅
- Move existing threshold values into the correct config objects. ✅
- No functional changes. ✅
- Committed: `1d76516`

### 3. Route strategy behaviour through configs ✅

- man/auto uses silence thresholds from `ManAutoConfig`. ✅
- man/man uses max‑duration from `ManManConfig`. ✅
- Shared values come from `SharedSttConfig`. ✅
- Remove hard‑coded values. ✅
- Behaviour remains identical. ✅
- Committed: `1807dc7`
- Pre-roll duration now driven from `SharedSttConfig.preRollMs` instead of hardcoded `PRE_ROLL_MS = 100`
- All termination checks already route through mode-specific configs
- VAD energy threshold already comes from `SharedSttConfig.energyThreshold`
- `debugLoggingEnabled` already comes from `SharedSttConfig`

### 4. Replace human messages with return codes ✅

- Remove all human‑readable strings from STT output. ✅
- Replace them with the correct `SttReturnCode`. ✅
- Caller becomes responsible for messaging. ✅
- Committed: `d52eff1`
- `reasonMessages` removed from `RuntimeSttConfig` (internal pipeline)
- `FrameResult.AbnormalTerminate` now carries only `code` (no `reason`)
- `SessionResult.Reason` now carries only `code` (no `message`)
- `ProcessorController.onAbnormalTermination` passes `code` only
- `SttConfig.reasonMessages` retained as public API for callers
- Tests assert on `SttReturnCode` instead of human strings

### 5. Collapse STT output contract ✅

- Reduce STT output to `SessionResult(code, transcript?)`. ✅
- Remove sealed class hierarchy. ✅
- Remove extra fields, hints, UI‑oriented metadata. ✅
- STT output is now deterministic and minimal. ✅
- Committed: `927a934`
- `SessionResult` is now a single `data class SessionResult(code, transcript?)`
- `Transcribe` and `Reason` subclasses removed
- `shutdownPipeline` split into two clean overloads:
  - `shutdownPipeline(pcm, code)` — runs inference, dispatches result
  - `shutdownPipeline(code)` — cleanup only, no inference
- PCM is no longer carried by `SessionResult`; it's consumed inline
