# Session Handover

## Current State

Pre-Vosk voyage. Workspace is clean and configured:

**Rule files:** All 5 `.continue/rules/*.md` files have YAML frontmatter. `environment.md` has temp dir, correct STT/Vosk module labels, test tasks for both modules. `session-handover.md` rule file defines the END/SART protocol.

**Temp dir:** `C:\Users\home-\git\android-voice-core-stt\temp\` exists for transient files.

**Vosk module:** `:vosk` is defined in `settings.gradle.kts`. No implementation explored yet.

**STT module:** Production-grade pipeline (PCM → VAD → accumulator → Whisper → transcript). Stable.

## Next Steps

1. Implement/extend the Vosk recogniser module (`:vosk`).
2. Keep Vosk standalone — PCM → recogniser → text. No STT dependencies.
3. Maintain clean separate public API for Vosk.

## Key Decisions

- Two independent subsystems — STT (Whisper) and Vosk are separate.
- No auto-commits — stage only, Mike reviews.

## Relevant Files

- `.continue/rules/persona.md`
- `.continue/rules/environment.md`
- `.continue/rules/mini-pdp.md`
- `.continue/rules/session-handover.md`
- `settings.gradle.kts`
- `stt/README.md`
- `README.md`