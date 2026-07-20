---
name: Environment & Tool Context
alwaysApply: true
description: Project environment details - IDE, OS, shell, project paths, and quick Gradle commands.
---

# Environment & Tool Context

## IDE & Extension

- **IDE:** VS Code
- **Extension:** Continue.dev (v0.x)
- **OS:** Windows (PowerShell)
- **Shell:** Non-interactive, stateless — each command runs in a fresh context
- **`cd` between commands does NOT persist** — always use absolute paths or full commands

## Human Operator Role

- The human (home-) can run terminal commands, verify builds, and resolve tool failures.
- When tools fail, report the failure clearly and suggest what the human should check/do.
- The human can also provide environment details (SDK paths, Java version, etc.) that the agent cannot detect.

## Project Quick Reference

- **Root:** `C:\Users\home-\git\android-voice-core-stt\`
- **Gradle:** `gradlew.bat` (Windows)
- **Modules:** `app/` (demo harness), `stt/` (library)
- **Public API package:** `dev.barrycade.voicecore.stt`
- **JNI package:** `dev.barrycade.voicecore.stt` (must align with native code)
- **STM library module:** `:stt` in Gradle
- **App module:** `:app` in Gradle
- **Test task:** `./gradlew.bat :stt:test` (or specific test class via `--tests`)

## Useful Quick Commands (ask human to run these when needed)

**Note:** See `quirks.md` for shell syntax requirements.

