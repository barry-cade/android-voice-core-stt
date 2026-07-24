# Implementation Plan - WUW Match Feedback Improvements

This plan addresses the lack of visual feedback during the WUW "Match" mode. Currently, the UI only updates if a successful match occurs, leaving the user unsure if the system is actually hearing them or how close they are to the threshold.

## User Review Required

> [!IMPORTANT]
> **Library Impact**: This requires adding a "similarity update" callback to the `:wuw` module components (`WakeWordEngine` and `WakeWordSessionManager`). This is a non-breaking additive change.

## Proposed Changes

### [Component] Wake-Word Engine (:wuw)

#### [MODIFY] [WakeWordEngine.kt](file:///C:/Users/home-/git/android-voice-core-stt/wuw/src/main/java/dev/barrycade/voicecore/wuw/WakeWordEngine.kt)
- Add a new functional interface `SimilarityListener` (or use a lambda) to report the computed similarity score.
- Update `processPcm` to invoke this listener every time a DTW distance is calculated, regardless of whether it meets the threshold.

#### [MODIFY] [WakeWordSessionManager.kt](file:///C:/Users/home-/git/android-voice-core-stt/wuw/src/main/java/dev/barrycade/voicecore/wuw/WakeWordSessionManager.kt)
- Add an `onSimilarityUpdate: ((Float) -> Unit)?` property to the manager.
- Wire this property down to the `WakeWordEngine`.

---

### [Component] Demo App (:app)

#### [MODIFY] [MainActivity.kt](file:///C:/Users/home-/git/android-voice-core-stt/app/src/main/java/dev/barrycade/voicecore/MainActivity.kt)
- **Live Score Tracking**: Update `startWuwListening` to register a similarity update listener.
- **UI Feedback**:
    - Update `txtWuwOutput` to display the "Live Score" (e.g., `Current: 0.45 | Target: 0.70`).
    - This provides immediate visual proof that the system is processing audio and shows the user how close their speech is to the saved template.

## Verification Plan

### Manual Verification
1. **Match Mode**: Select a template and press "Match."
2. **Visual Proof**: Speak and confirm that the "Current Score" in the output box fluctuates in real-time.
3. **Threshold Calibration**: Adjust the threshold slider and observe how it affects the "Target" value and the ease of triggering a match.
4. **Detection**: Confirm that the `[WAKE WORD DETECTED]` message still appears correctly when the live score exceeds the threshold.
