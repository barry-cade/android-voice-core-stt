# Demo App — android-voice-core-stt

This is the demo Android application for the **android-voice-core-stt** project.  
It showcases how to integrate and use the STT module (`stt-core`) including:

- PCM audio capture  
- Deterministic VAD  
- Whisper tiny_en transcription  
- Command window behaviour  
- Real‑time logging and configuration  

The demo app is intended as a reference implementation and a testing tool.

## Features

### Live VAD Logging

Displays real‑time VAD output including:

- RMS energy values  
- Speech/silence classification  
- Silence frame counts  
- Early‑close events  

### Whisper Transcription

Runs offline Whisper tiny_en inference and prints:

- Full transcription text  
- Segment breakdown  
- Inference duration  

### Config Editor

Allows editing of STT configuration values:

- Energy threshold  
- Silence padding  
- Pre‑roll duration  
- Max utterance length  
- Motion‑mode overrides  

### PCM Capture Monitoring

Shows:

- Buffer sizes  
- Frame counts  
- Capture start/stop events  

## How to Run

1. Open the project in Android Studio.
2. Select the `app` module.
3. Build and install on a physical Android device.
4. Grant microphone permission when prompted.
5. Use the UI buttons:
   - **Start** — begins PCM capture and VAD monitoring.
   - **Stop** — ends capture and runs Whisper transcription.
6. View logs in Logcat using the tag prefix:  
   `STT_*`

## Folder Structure

app/

- src/main/java/…  
  Demo activity, STT integration, logging  
- src/main/res/…  
  Layouts and UI resources  
- build.gradle  
  Module configuration  

## Integration Example

The demo shows how to:

- Instantiate the STT engine  
- Provide a configuration object  
- Start and stop recording  
- Receive transcription results  
- Handle lifecycle events  

Use this app as a reference when integrating `stt-core` into your own Android project.

## Requirements

- Android 8.0+ (API 26+)  
- Microphone permission  
- Device with sufficient CPU for Whisper tiny_en  

## Notes

- The demo app is intentionally simple and focused on clarity.  
- It is not intended as a production UI.  
- All STT logic lives in the `stt-core` module; the app only demonstrates usage.

## License

MIT (or the license defined in the root project)
