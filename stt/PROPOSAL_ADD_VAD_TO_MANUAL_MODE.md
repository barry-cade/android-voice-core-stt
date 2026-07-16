# Proposal: Add VAD Gating to Manual Mode

**Audience:** HAL (senior engineer)  
**Author:** CATO  
**Date:** 2025-01-22  
**Status:** DRAFT for review

---

## 1. Problem

In ManualStart+ManualStop mode, **all PCM frames from START to STOP are accumulated into the session buffer and passed to Whisper**, regardless of whether they contain speech or ambient silence. There is **no energy threshold, no VAD gating, and no silence trimming** in the manual-mode code path.

### The user-visible failure

When a user:

1. Presses START and speaks for ~3 seconds
2. Pauses or stops speaking (e.g., thinking, reading a document)
3. Waits N seconds before pressing STOP

The PCM buffer sent to `whisper_full()` contains:

``` text
[ speech (3s) | ambient noise (N seconds) ]
```

Whisper's internal model may:

- **Return empty string** (`""`) — if `no_speech_thresh` (default 0.6) classifies the overall audio as non-speech
- **Hallucinate words** — if the noise floor produces acoustic features that match speech patterns
- **Return correct text but with tail artifacts** — if the model tries to transcribe the noise

### Why this matters

In manual mode, the user explicitly delimits the utterance with START/STOP buttons. They expect the full audio between those events to be transcribed as-is. However, in practice:

- **Whisper's `no_speech_thresh` consumes silence conservatively**, so long pauses before STOP can cause Whisper to return `""` (blank audio).
- **The client-side `[BLANK_AUDIO]` heuristic** in `MainActivity.kt` (3+ consecutive blanks shows a UI hint) is a symptom, not a fix — it detects the problem but doesn't solve it.

---

## 2. Current Architecture

### Manual-mode data path

``` text
AudioRecord → FloatArray frames
  → ConcurrentLinkedQueue<FloatArray> (AudioCapture.frameQueue)
  → MinimalPollingController.pollFrame()
    → CaptureManager.appendFrameToSession()  ← ALWAYS appends, no filter
    → CaptureManager.sessionBuffer (MutableList<Float>)

transcribe():
  → CaptureManager.finalize()
    → drain remaining queue frames → snapshotAndClearSessionBuffer()
    → FloatArray (ALL raw PCM from START to STOP)

  → SttInferenceController.submit()
    → toShortArray() → WhisperBridge.transcribe()
```

### Key observation

`CaptureManager.pollFrame()` (line ~322) calls `appendFrameToSession(frame)` unconditionally:

```kotlin
override fun pollFrame(): FloatArray? {
    // ... stop drain thread logic ...
    val frame = audioCapture.frameQueue.poll()
    if (frame != null) {
        appendFrameToSession(frame)  // ← NO filter applied
    }
    return frame
}
```

And the drain in `finalize()` (line ~282) also appends unconditionally:

```kotlin
while (true) {
    val frame = audioCapture.frameQueue.poll() ?: break
    appendFrameToSession(frame)  // ← NO filter applied
}
```

---

## 3. Proposed Change

### Goal

Add energy-based VAD gating to manual mode's frame accumulation path so that **silence frames are excluded from the session buffer**. Speech frames pass through unchanged.

### Design

#### 3a. Create a lightweight `VadGate` class

Separate from the full `Vad` used in auto-mode. A simple RMS-energy gate with:

- **Configurable `energyThreshold`** (Float, default `0.005f` → matches `Vad`'s default)
- **Single-method API:** `isSpeech(frame: FloatArray): Boolean`
- **No hysteresis** (unlike `Vad` which lowers threshold by 30% after speech detection)
- **No frame tracking** (no `speechDurationMs`, `silenceMs`, `vadConfidence`)
- **Stateless** — pure RMS calculation per frame

```kotlin
internal class VadGate(private val energyThreshold: Float = 0.005f) {
    fun isSpeech(frame: FloatArray): Boolean {
        if (frame.isEmpty()) return false
        var sumSquares = 0.0
        for (sample in frame) {
            val n = sample.toDouble()
            sumSquares += n * n
        }
        val rms = sqrt(sumSquares / frame.size)
        return rms >= energyThreshold
    }
}
```

**Why not use the existing `Vad` class?**

| Aspect | `Vad` (auto-mode) | `VadGate` (proposed) |
| -------- | ------------------ | --------------------- |
| Hysteresis | Yes — 30% threshold drop | No — flat threshold |
| Stateful | Tracks `speechDurationMs`, `silenceMs`, confidence | Stateless — pure RMS per frame |
| Complexity | 80+ lines with confidence/model logic | <20 lines |
| Test surface | Full unit test suite | Simple single-method test |

The existing `Vad` is designed for the auto-mode `UtteranceAccumulator` which needs hysteresis to prevent flickering at speech boundaries. In manual mode, the user explicitly delimits the utterance with START/STOP, so hysteresis is unnecessary and would bleed unwanted noise frames.

#### 3b. Inject `VadGate` into `MinimalPollingController`

Add an optional `vadGate` parameter:

```kotlin
internal class MinimalPollingController(
    private val audioSource: AudioSource,
    private val stopRequestedRef: () -> Boolean,
    private val vadGate: VadGate? = null    // NEW
) : PollingController {
```

Modify `start()` to apply the gate before accumulating:

```kotlin
val frame = audioSource.pollFrame()
if (frame != null) {
    // VAD gate: only accumulate if frame contains speech energy
    if (vadGate == null || vadGate.isSpeech(frame)) {
        // Frame is already appended to session by pollFrame()
    }
    // If not speech, frame was polled and discarded — no accumulation
}
```

**Wait — there's a subtlety.** `CaptureManager.pollFrame()` appends the frame to the session buffer as a side effect. For VAD gating to work, we need to either:

- **Option A:** Let `pollFrame()` always append (current behaviour), then remove silence frames from the buffer separately. This is racy and wasteful.
- **Option B:** Move the gating into `CaptureManager.pollFrame()` or `appendFrameToSession()`. This pollutes `CaptureManager` with VAD logic.
- **Option C:** Have `MinimalPollingController` call a separate method that polls without appending, and only append when VAD passes.

**Recommendation: Option C.** Add a `pollFrameWithoutAppend()` method to `AudioSource`/`SessionManager`:

```kotlin
// SessionManager.kt
fun pollFrameWithoutAppend(): FloatArray?
```

In `CaptureManager`:

```kotlin
override fun pollFrameWithoutAppend(): FloatArray? {
    return audioCapture.frameQueue.poll()
}
```

Then in `MinimalPollingController`:

```kotlin
val rawFrame = audioSource.pollFrameWithoutAppend()
if (rawFrame != null) {
    if (vadGate == null || vadGate.isSpeech(rawFrame)) {
        audioSource.appendFrameToSession(rawFrame)  // new method needed
    }
}
```

This approach keeps VAD logic entirely in `MinimalPollingController` and doesn't touch `CaptureManager`'s internals.

#### 3c. Wire `VadGate` from config `energyThreshold`

In `SttModeController.selectController()`, pass the energy threshold from config:

```kotlin
if (manualMode) {
    val energyThreshold = if (config.energyThreshold > 0f) {
        config.energyThreshold
    } else {
        0.005f  // default
    }
    minimalProcessorController = MinimalPollingController(
        audioSource = captureManager,
        stopRequestedRef = stopRequestedRef,
        vadGate = VadGate(energyThreshold = energyThreshold)
    )
}
```

This means the `energyThreshold` field from the JSON config **finally becomes active in manual mode**, as users would intuitively expect.

#### 3d. `finalize()` drain path

`CaptureManager.finalize()` drains remaining frames from the queue unconditionally (lines ~282-285). This path runs AFTER `MinimalPollingController.stop()` has already joined, so any frames remaining in the queue were enqueued during the brief window between the last poll and `finalize()`.

**These should also be VAD-gated.** Add a `vadGate` parameter to `finalize()`:

```kotlin
override fun finalize(vadGate: VadGate? = null): FloatArray {
    // ... join drain thread ...

    // Drain remaining frames, applying VAD gate if provided
    while (true) {
        val frame = audioCapture.frameQueue.poll() ?: break
        if (vadGate == null || vadGate.isSpeech(frame)) {
            appendFrameToSession(frame)
        }
    }

    val result = snapshotAndClearSessionBuffer()
    audioCapture.stop()
    return result
}
```

---

## 4. Impact Analysis

### What changes

| Layer | Change | Size |
| ------- | -------- | ------ |
| New file: `VadGate.kt` | ~20 lines | Small |
| `MinimalPollingController.kt` | Add `vadGate` param, modify `start()` | ~5 lines |
| `SttModeController.kt` | Pass energy threshold as VadGate | ~3 lines |
| `CaptureManager.kt` | Add `pollFrameWithoutAppend()`, `appendFrameToSession()` public, `finalize(vadGate)` | ~10 lines |
| `SessionManager.kt` | Add `pollFrameWithoutAppend()`, `appendFrameToSession()` to interface | ~2 lines |
| `AudioSource.kt` | Add `pollFrameWithoutAppend()` | ~1 line |
| New test: `VadGateTest.kt` | Unit tests for VadGate | ~50 lines |
| Modify: `SttPipelineSequencingTest.kt` | Update for new `finalize()` signature | ~5 lines |

### What does NOT change

- **Public API** — `SpeechToText`, `SttConfig`, JSON schema unchanged
- **Auto-mode** — untouched, existing `Vad` + `UtteranceAccumulator` unchanged
- **Lifecycle** — no change to `SttLifecycleController`, `SttPipelineState`
- **Lock model** — `CaptureManager.stateLock` and `sessionBufferLock` unchanged
- **Thread model** — `MinimalPollingController` worker thread unchanged

### Behaviour before/after

| Scenario | Before (current) | After (proposed) |
| ---------- | ----------------- | ------------------ |
| User speaks, presses STOP quickly | Full audio to Whisper | Same |
| User speaks, pauses 5s, presses STOP | ~5s of noise appended → Whisper may blank or hallucinate | Noise frames excluded → only speech to Whisper |
| User presses START, never speaks, presses STOP | All noise frames → Whisper returns `""` → `[BLANK_AUDIO]` UI | All frames suppressed → empty session buffer → empty PCM → early return without inference |
| Ambient noise above threshold (e.g., fan, traffic) | Accumulated as "speech" → Whisper may hallucinate | Noise accumulated → same as before — this is a threshold tuning concern |

### Risk: Empty PCM on STOP

If `VadGate` suppresses all frames (user pressed START in a silent room and immediately pressed STOP), `finalize()` returns an empty `FloatArray`. The existing code already handles this:

```kotlin
// SpeechToText.kt, transcribe():
if (finalPcm.isEmpty()) {
    SttLogger.pcm("no PCM accumulated -- transitioning to STOPPED then READY")
    transitionPipelineToIdleLocked("transcribe empty pcm")
    currentSessionEpoch = 0L
    lifecycleController.onStop()
    lifecycleController.onReset()
    return
}
```

No Whisper inference is attempted — the pipeline resets cleanly.

### Risk: First speech frame dropped

If the `AudioCapture` frame is 4000 samples (250ms at 16kHz), and speech starts in the middle of a frame, the RMS energy of the full frame might be below threshold. This is a **known limitation** shared with the auto-mode `Vad` — it uses the same `energyThreshold` semantic. The user would naturally press START then begin speaking, so the first partial frame is unlikely to contain significant speech anyway.

---

## 5. Testing

### Unit tests for `VadGate`

| Test | Assertion |
| ------ | ----------- |
| `allZeros_notSpeech` | `isSpeech(FloatArray(160) { 0f })` → `false` |
| `allSilence_notSpeech` | `isSpeech(FloatArray(160) { 0.001f })` → `false` (below 0.005 threshold) |
| `speechLevel_isSpeech` | `isSpeech(FloatArray(160) { 0.1f })` → `true` |
| `aboveThreshold_isSpeech` | `isSpeech(floatArrayOf(0.01f, -0.01f))` → `true` (RMS ≈ 0.01 ≥ 0.005) |
| `emptyFrame_notSpeech` | `isSpeech(FloatArray(0))` → `false` |
| `customThreshold_respected` | `VadGate(0.1f).isSpeech(FloatArray(160) { 0.05f })` → `false` |

### Integration tests (existing, should pass unchanged)

- `ManualStop` still responds to event
- `MinimalPollingController` still polls and stops
- `CaptureManager.finalize()` still returns `FloatArray`
- `SpeechToText.transcribe()` still dispatches results

### Edge case tests

- Empty session (all frames suppressed) → empty PCM → pipeline resets cleanly
- Mixed speech/silence frames → only speech frames in buffer → correct whisper output
- VAD gate disabled (`null`) → existing behaviour unchanged (backward compat)

---

## 6. Alternatives Considered

### Alternative A: Use existing `Vad` class

We could reuse the existing `Vad` class instead of creating `VadGate`. However:

- `Vad` maintains state (`speechDurationMs`, `silenceMs`, confidence) that is meaningless in manual mode
- `Vad` has hysteresis (30% threshold drop after speech detection) that would bleed silence frames once speech is detected
- The `Vad` constructor takes `RuntimeSttConfig`, pulling in dependencies unnecessary for manual mode

**Verdict:** Rejected — `VadGate` is simpler and more correct.

### Alternative B: Gate in `CaptureManager`

We could add the VAD check inside `CaptureManager.appendFrameToSession()` or `pollFrame()`. This would avoid the `pollFrameWithoutAppend()` / `appendFrameToSession()` interface changes.

However:

- `CaptureManager` would need a `VadGate` reference, coupling capture logic to VAD
- The `finalize()` drain path would also need the gate, requiring the gate to be stored as state
- This muddies `CaptureManager`'s current clean responsibility: raw PCM accumulation

**Verdict:** Rejected — VAD gating is a consumption-side concern, not a capture concern.

### Alternative C: Post-hoc silence trimming in `finalize()`

We could trim silence from the tail of the accumulated PCM buffer after the session ends, rather than gating frame-by-frame. This would be simpler but:

- Would not reduce the memory footprint of the session buffer (which is the secondary benefit)
- Requires RMS scanning of the concatenated buffer (more CPU per frame than frame-level gating)
- Cannot distinguish mid-utterance silence (e.g., pauses between words) from end-utterance silence — would incorrectly trim natural speech gaps

**Verdict:** Rejected — frame-level gating is more correct and more efficient.

---

## 7. Summary

| Aspect | Assessment |
| -------- | ----------- |
| **Problem severity** | Medium — causes blank audio hallucinations in manual mode with long pauses before STOP |
| **Change scope** | Small — one new class (~20 lines), minor modifications to 4 existing files |
| **Risk** | Low — the empty PCM path is already handled; auto-mode is untouched |
| **Backward compatibility** | Full — `VadGate(null)` = existing behaviour |
| **Config alignment** | `energyThreshold` now actually works in manual mode (it was parsed and ignored) |

### Approval request

Please review and approve this approach. The implementation is ~50 lines of production code and can be completed in a single session.
