# Implementation Plan - VOSK Final Result Box Refinement

This plan details the UI changes to the VOSK panel to align the "Final Result" display with the "Partial" feedback box styling, while using the "Active Config" color scheme.

## User Review Required

> [!IMPORTANT]
> This is a pure UI refinement in the `:app` module. No changes to the underlying STT or VOSK logic are necessary.

## Proposed Changes

### [Component] VOSK UI Layout

#### [MODIFY] [activity_main.xml](file:///C:/Users/home-/git/android-voice-core-stt/app/src/main/res/layout/activity_main.xml)
- **Reorder Views**: Move `txtVoskFinal` to be below `txtVoskOutput`.
- **Apply Styling to `txtVoskFinal`**:
    - **Background**: `#FFF9C4` (Yellow, matching the Whisper Config box).
    - **Text Color**: `#33691E` (Dark Green, matching the Whisper Config box).
    - **Font**: `monospace` (Matching the Partial box).
    - **Size**: `11sp` (Matching the Partial box).
    - **Padding**: `8dp`.
    - **Margin Top**: `8dp` (To separate it from the Partial box).
- **Placeholder Text**: Update the initial text to a placeholder like "Final: (awaiting result...)" instead of the current hint.

#### [MODIFY] [MainActivity.kt](file:///C:/Users/home-/git/android-voice-core-stt/app/src/main/java/dev/barrycade/voicecore/MainActivity.kt)
- Ensure the `VoskFinalListener` updates the text in a format consistent with the "Live" box (e.g., prefixing with "Final: ").

## Verification Plan

### Manual Verification
1. **Visual Check**: Open the VOSK tab and verify that:
    - The "Live" box (blue) is at the top.
    - The "Final" box (yellow) is below it.
    - Both boxes use the same monospace font and small text size.
2. **Functional Check**: Perform a VOSK session and verify that results are correctly routed to the new yellow box with the "Final: " prefix.
3. **Clear Check**: Verify that the "CLEAR" button wipes both boxes and resets the placeholder.
