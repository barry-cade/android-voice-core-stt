# Plan: Add Noise Resilience to STT Module

**Context:** Phone mounted on a WAVE ROVER pan/tilt chassis picks up motor rumble (low-frequency) and servo whine (high-frequency). We need optional, configurable noise resilience that preserves the JSON boundary and does not touch the public API.

**Principle:** All noise resilience is opt-in via JSON config fields. The STT module's public API (`SpeechToText` companion methods) is unchanged. Non-robot projects set nothing and get the same pure energy VAD as today.

---

## Stage 1 — Add JSON config fields ✅

**Goal:** Extend the schema so the app can declare noise resilience settings at init time.

**Files:**
- [x] `SttConfig.kt` — add `highPassCutoffHz: Int = 0`, `zcrEnabled: Boolean = false` + validation
- [x] `RuntimeSttConfig.kt` — propagate fields from `SttConfig`
- [x] `SttJsonAdapter.kt` — parse fields from JSON payload

**Test:** Default values unchanged. Valid/invalid ranges verified. No behavioural change — fields are parsed but not used.

**Risk:** None.

---

## Stage 2 — Build the pre-processing stage ✅

**Goal:** New `AudioPreProcessor` class that applies HPF and ZCR on individual PCM frames.

**Files:**
- [x] New `AudioPreProcessor.kt` (internal)
  - [x] 1st-order IIR high-pass filter (in-place, no allocation, pre-allocated state)
  - [x] Zero-crossing rate computation for servo whine rejection
  - [x] `process(frame: FloatArray): Boolean` — returns true if frame should be rejected

**Test:** Unit tests with known waveforms (50 Hz sine → filtered, 400 Hz sine → passes, white noise → ZCR rejects).

**Risk:** Low — new file, dead code until Stage 3.

---

## Stage 3 — Wire processor into the pipeline ✅

**Goal:** Pre-processor runs on every frame before VAD classification.

**Files:**
- [x] `ProcessorController.kt` — add `preProcessor` parameter, call `process()` before `vad.isSpeech()` in both `runProcessingLoop()` and `drainRemainingFrames()`
- [x] `SttProcessingController.kt` — construct `AudioPreProcessor` from config, pass to `ProcessorController`
- [x] `SttModeController.kt` — construct `AudioPreProcessor` for Manual mode, pass to `MinimalPollingController`; add `preProcessor` parameter to `MinimalPollingController` and apply in VAD-gated path (pre-process before VadGate check)

**Test:** Integration test: 50 Hz sine → VAD does not trigger with HPF on. Regression: standard speech frames → VAD triggers normally. Default config (HPF=0, ZCR=false) → identical behaviour to before.

**Risk:** Moderate — modifies the hot path. Gated on non-default config; revertable by removing two lines.

---

## Stage 5 — Documentation ✅

**Files:**

- [x] `stt/ARCHITECTURE.md` — add "Noise Resilience" section (6) with HPF, ZCR, pipeline integration diagram, config feedback subsection; add `AudioPreProcessor` to internal type inventory; update JSON schema to include `highPassCutoffHz` and `zcrEnabled`; renumber TOC and section headers
- [x] `transient/PLAN-ADD-NOISE-RESILIENCE.md` — mark all stages complete

**Risk:** None.

---

## Key principles

1. Public API (`SpeechToText`) never changes — no `setMotionMode()`, no new methods
2. JSON boundary is preserved — all noise settings arrive via the config JSON string
3. Default values (HPF=0, ZCR=false) produce identical behaviour to today
4. The app layer still manages session timing relative to movement commands — the STT module owns pre-processing, not motion awareness
5. Each stage is independently revertable
