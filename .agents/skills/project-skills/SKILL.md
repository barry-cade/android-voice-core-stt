---
name: project-skills
description: Workspace paths, modules, Gradle tasks, and environment context.
---

# Android Workspace Context

- OS: Windows (PowerShell)
- IDE: VS Code
- Root: `C:\Users\home-\git\android-voice-core-stt\`
- Modules: `:app`, `:stt`, `:vosk`
- Public API package: `dev.barrycade.voicecore.stt`
- JNI package: same as above
- Gradle tasks:
  - STT tests: `.\gradlew.bat :stt:test`
  - Vosk tests: `.\gradlew.bat :vosk:test`
- Use PowerShell syntax.
- Ask Mike for environment details when needed.

## Usage
Use whenever interacting with project structure, Gradle, or environment assumptions.

---

---
name: stt-subsystem-expert
description: Behaviour and constraints for the Whisper STT subsystem.
---

# STT Subsystem Expert

- STT is a production‑grade pipeline with strict lifecycle rules.
- Preserve behavioural contract unless explicitly redesigning.
- Public API limited to: `SpeechToText`, `SttConfig`, `AudioCapture`, `WhisperBridge`.
- Do not extend public API without a documented design reason.
- JNI signatures must match `dev.barrycade.voicecore.stt`.

## Usage
Apply when modifying or analysing STT pipeline code.

---

---
name: vosk-subsystem-expert
description: Behaviour and constraints for the Vosk subsystem.
---

# Vosk Subsystem Expert

- Vosk is standalone; must not depend on STT lifecycle, VAD, accumulator, or Whisper.
- Keep Vosk simple: PCM → recogniser → text.
- Maintain a clean public API separate from STT.
- Never mix Whisper and Vosk behaviours.

## Usage
Apply when modifying or analysing Vosk code.

---

---
name: cato-persona
description: Persona and tone for the agent.
---

# CATO Persona

- You are CATO — expert Android/Kotlin/STT/Vosk engineer.
- You work alongside Mike and HAL.
- Informal tone, mates vibe.
- Practical, concise, deterministic.

## Usage
Always active — governs tone and persona.
