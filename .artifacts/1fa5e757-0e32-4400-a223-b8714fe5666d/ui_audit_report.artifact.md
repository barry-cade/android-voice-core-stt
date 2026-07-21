# UI/UX Audit Report: Android Voice Core

This report provides an analysis of the current `MainActivity` UI and offers suggestions for improvement, focusing on user experience, modern Android standards (Material 3), and functional clarity.

## Current State Analysis

The current UI serves primarily as a **developer's debug dashboard**. While functional for testing the underlying STT engines (Whisper and Vosk), it lacks the polish and hierarchy expected of a user-facing application.

### Key Observations

- **Information Overload**: The "Active Config" JSON block and "Timing Diagnostics" dominate the screen, pushing the primary output (transcribed text) to the bottom or off-screen.
- **Utilitarian Styling**: Uses default `android.widget.Button` and `TextView` with minimal styling. The theme is a basic `Material.Light.NoActionBar`.
- **Navigation via Buttons**: The "Whisper" and "Vosk" modes are switched using standard buttons at the top, which don't provide a clear "tabbed" context.
- **Fixed Layout**: The use of nested `LinearLayout` with weights can lead to a cramped experience on smaller devices and doesn't easily adapt to modern edge-to-edge designs.
- **Lack of Visual Feedback**: There is no active recording indicator (e.g., a waveform or pulsating mic icon) beyond a simple "Recording..." text change.

---

## Suggestions for Improvement

### 1. Re-prioritize Content (Hierarchy)
- **Top**: Navigation (Tabs).
- **Center**: Transcription Output. This should be the largest and most readable element.
- **Bottom**: Controls (Start/Stop/Clear).
- **Secondary**: Move JSON Config and Timing Diagnostics to a "Developer Settings" screen or an expandable `BottomSheet`.

### 2. Modernize with Material 3
- **Tabs**: Implement `TabLayout` or a Compose `PrimaryTabRow` for switching between engines.
- **Buttons**: Use `MaterialButton` with proper iconography (e.g., a microphone for Start, a square for Stop).
- **Colors & Surfaces**: Use a unified color palette from the Material 3 color system (Primary, Secondary, Surface). Replace the harsh yellow background of the config with a `Card` or a subtle `Surface` color.

### 3. Improve User Feedback
- **Active State**: Add a visual pulse or a small waveform view while recording to show the mic is "hot."
- **Status Messaging**: Use `Snackbars` for transient messages like "Model loaded" or "Permission denied."
- **Clearer Mode Indicators**: Make it very obvious which engine is active and which strategy (Manual/Auto) is selected.

### 4. Technical Recommendations
- **Jetpack Compose**: The current `MainActivity` is becoming quite large (nearly 500 lines) due to imperative UI handling. Migrating to Compose would drastically simplify state management and allow for much richer animations and layouts.
- **Edge-to-Edge**: The app currently doesn't use the full screen height (status/nav bars are separate). Implementing edge-to-edge support would make it feel more integrated with the OS.

---

## Visual Summary (Proposed Layout)

```mermaid
graph TD
    A[Tab Row: Whisper | Vosk] --> B[Transcription View: Large Text Area]
    B --> C[Control Bar: Start / Stop / Clear]
    C --> D[Expandable: Diagnostics & Config]
```

> [!TIP]
> **Quick Win**: If staying with XML, wrapping the output text in a `MaterialCardView` and moving the config to a "Debug" button that opens a dialog would immediately improve the "user" experience.
