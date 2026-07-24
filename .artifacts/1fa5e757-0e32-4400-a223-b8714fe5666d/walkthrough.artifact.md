# Walkthrough - WUW Live Match Feedback

I have updated the WUW Match mode to provide real-time feedback on how closely your voice matches the saved template.

## Changes Made

### 1. Live Similarity Tracking (:wuw)
- **Engine Update**: Updated `WakeWordEngine` to calculate and report a similarity score for every audio chunk processed, not just when a match occurs.
- **Manager Update**: Updated `WakeWordSessionManager` to expose this live score via a new `similarityListener`.

### 2. UI Feedback (:app)
- **Real-Time Display**: In "Match" mode, the output box now displays a live status line:
  `Current: 0.45 | Target: 0.70`
- **Immediate Proof**: This confirms the microphone is active and the engine is "hearing" you, even if the score isn't high enough to trigger a detection yet.
- **Easier Calibration**: You can now see exactly what score your voice is achieving, allowing you to precisely adjust the **Threshold** slider to find the perfect balance between sensitivity and false triggers.

## Verification Results

### Manual Verification
- Verified that `WakeWordEngine` correctly invokes the new listener during PCM processing.
- Verified that `MainActivity` updates the text display on the main thread for every similarity update.

> [!TIP]
> Try speaking different words or using different tones of voice. You'll see the "Current" score jump up when you get closer to the original recording's acoustic pattern.

render_diffs(file:///C:/Users/home-/git/android-voice-core-stt/wuw/src/main/java/dev/barrycade/voicecore/wuw/WakeWordEngine.kt)
render_diffs(file:///C:/Users/home-/git/android-voice-core-stt/wuw/src/main/java/dev/barrycade/voicecore/wuw/WakeWordSessionManager.kt)
render_diffs(file:///C:/Users/home-/git/android-voice-core-stt/app/src/main/java/dev/barrycade/voicecore/MainActivity.kt)
