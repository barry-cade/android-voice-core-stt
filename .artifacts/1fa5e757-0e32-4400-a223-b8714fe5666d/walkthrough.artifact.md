# Walkthrough: VOSK Definitive Lifecycle & Status Tracking

I have updated the VOSK demo logic to enforce a strict "Button-Driven" lifecycle, ensuring that both Wake Word and Command modes are definitively started and stopped.

## Changes Made

### 1. Explicit Lifecycle Management
- **One-Shot Command Mode**: In "Cmd Mode", the session now definitively stops after a single utterance is captured. The UI returns to "IDLE" and re-enables the Start button automatically.
- **Persistent Wake Word Loop**: In "Wake Word" mode, the loop continues (Wake Word -> Command -> Wake Word) until you explicitly press the **STOP** button.
- **Definitive Stop**: Pressing **STOP** now completely tears down the VOSK engine and resets all UI indicators to a clean, idle state.

### 2. Visual Status Indicator
- Added a **Status: IDLE / ACTIVE** indicator at the top of the VOSK panel.
- **Active State**: The indicator turns **RED** and says "Status: ACTIVE" when the microphone is being used.
- **Idle State**: The indicator turns **GREY** and says "Status: IDLE" when the engine is dormant.

### 3. Engine Teardown on Tab Switch
- Refined the switching logic to ensure that if you are in the middle of a VOSK session and switch to Whisper, the VOSK engine is immediately terminated to free up the microphone.

### 4. Technical Improvements
- **Centralized State**: Centralized the VOSK UI state updates within the `modeListener` callback. This ensures the UI is always perfectly in sync with the underlying `VoskSessionManager` state.
- **Resource Management**: Fixed a missing notification in `VoskSessionManager` to ensure the UI is notified when the engine stops naturally or via error.

## Verification Results

### Manual Verification
- Verified that switching to the VOSK tab starts in the **IDLE** state.
- Verified that "Cmd Mode" stops definitively after one result.
- Verified that the "STOP" button correctly kills the "Wake Word" loop.
- Verified the new "Status" indicator correctly reflects the engine state.

> [!TIP]
> The VOSK engine is now much more predictable for calibration: Select your mode, press Start, and you'll see a clear visual confirmation that the system is live.

render_diffs(file:///C:/Users/home-/git/android-voice-core-stt/app/src/main/res/layout/activity_main.xml)
render_diffs(file:///C:/Users/home-/git/android-voice-core-stt/app/src/main/java/dev/barrycade/voicecore/MainActivity.kt)
render_diffs(file:///C:/Users/home-/git/android-voice-core-stt/vosk/src/main/java/dev/barrycade/voicecore/vosk/VoskSessionManager.kt)
