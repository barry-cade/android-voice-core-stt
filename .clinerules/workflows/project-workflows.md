---
name: project-workflows
description: Workspace paths, modules, Gradle tasks, and environment context.
---

# Project Workflows

## Refactor STT Pipeline

1. Analyse current STT structure.
2. Identify lifecycle or PDP violations.
3. Propose minimal deterministic fixes.
4. Apply changes file‑by‑file.
5. Verify with `.\gradlew.bat :stt:test`.

## Add Logging to Modules

1. Locate lifecycle points.
2. Insert PDP‑aligned logging.
3. Ensure no nested lambdas or hidden behaviour.
4. Verify compilation.

## Debug STT Failure

1. Identify symptoms.
2. Locate root cause using targeted grep.
3. Propose smallest justified fix.
4. Verify with Gradle tests.
