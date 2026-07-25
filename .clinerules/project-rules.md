# Project rules

## Kotlin Mini‑PDP Structural Rules

- No nested lambdas unless absolutely necessary.
- Avoid scope functions inside scope functions.
- Avoid trailing lambdas inside lambdas; extract to named variables.
- Prefer linear initialisation: construct → configure → start.
- Avoid clever Kotlin features unless they improve clarity.
- One action per line.
- No invisible behaviour; prefer explicit interface implementations.
- Explicit return types on all public/internal functions.
- No nested conditionals; use guard clauses and early returns.
- Prefer early return over state accumulation.
- One write site per mutable field; prefer `val` + copy.

## Deterministic Engineering Rules

- Follow existing project patterns unless a new abstraction clearly improves design.
- Identify root cause first; apply smallest justified fix.
- Verify fixes using Gradle tasks.
- Be concise, practical, and deterministic.
