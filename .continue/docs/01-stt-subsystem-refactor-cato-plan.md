# STT Subsystem Refactor Plan

Intent + Ordered Steps for Incremental Implementation

## Intent

We are restructuring the STT subsystem so that:

- Each strategy (man/man, man/auto) has its own config and its own return‑code behaviour, while sharing common STT settings.
- The STT module returns only a return code + transcript data.
  - No human‑readable messages.
  - The caller is responsible for interpreting codes and presenting UI messages.
- The STT module becomes deterministic and minimal, with the coordination layer wrapping the pure STT engine (WhisperBridge).
- Changes are applied incrementally, in a safe order, without destabilising the existing Android pipeline.

This document defines the exact steps Cato must follow.

## High‑Level Architecture

``` text
UI → Coordination Layer → Pure STT (WhisperBridge)
```

- **UI:** triggers STT and displays messages
- **Coordination Layer:** Android‑specific orchestration (AudioCapture, VAD, timing, accumulation)
- **Pure STT:** WhisperBridge (`loadModel`, `transcribe`, `free`)

The refactor focuses on the coordination layer and its contract with the caller.

## Ordered Plan (Cato Execution Steps)

Each step is safe to apply independently.
Cato should perform one step per session, based on what Mike reports as "already done".

### Step 1 — Introduce Unified Return Codes (no behaviour change)

Create a `SttReturnCode` enum and integrate it into `SessionResult`.

Examples (not exhaustive):

- `OK`
- `NO_SPEECH`
- `SILENCE_TIMEOUT`
- `UTTERANCE_TOO_LONG`
- `ERROR`

> **Important:**
> This step does not change any logic.
> Existing behaviour remains identical.
> Only the output contract changes.

### Step 2 — Split Configs (shared + per‑strategy)

Introduce:

- `SharedSttConfig`
- `ManManConfig`
- `ManAutoConfig`

Move existing threshold values into the correct config objects.

> No functional changes yet.
> Only structural separation.

### Step 3 — Route Strategy Behaviour Through Configs

Update the coordination layer so that:

- man/auto uses its silence thresholds
- man/man uses its max‑duration rules
- both use shared config values
- hard‑coded values are removed

Behaviour remains the same, but now driven by config.

### Step 4 — Replace Human‑Readable Messages With Return Codes

Remove all human‑readable strings from STT output such as:

- "You stopped speaking for 5 seconds"
- "Utterance too long"
- "No speech detected"

Replace them with the appropriate `SttReturnCode`.

Caller becomes fully responsible for messaging.

### Step 5 — Collapse STT Output Contract

Simplify STT output to:

```kotlin
SessionResult(
    code: SttReturnCode,
    transcript: String?
)
```

Remove any extra fields, hints, or UI‑oriented metadata.

This makes STT deterministic and minimal.

### Step 6 — (Optional Later) Isolate WhisperBridge as Pure STT Module

Extract:

- `loadModel()`
- `transcribe()`
- `free()`

into a standalone module.

Coordination layer continues to call it exactly as before.

This step is optional and should only be done once the previous steps are stable.

## Summary

This plan ensures:

- deterministic STT behaviour
- clean separation of concerns
- strategy‑specific configuration
- caller‑owned messaging
- minimal STT output contract
- safe incremental evolution
