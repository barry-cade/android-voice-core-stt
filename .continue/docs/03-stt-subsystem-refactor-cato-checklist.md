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

### 3. Route strategy behaviour through configs

- man/auto uses silence thresholds from `ManAutoConfig`.
- man/man uses max‑duration from `ManManConfig`.
- Shared values come from `SharedSttConfig`.
- Remove hard‑coded values.
- Behaviour remains identical.

### 4. Replace human messages with return codes

- Remove all human‑readable strings from STT output.
- Replace them with the correct `SttReturnCode`.
- Caller becomes responsible for messaging.

### 5. Collapse STT output contract

- Reduce STT output to:
