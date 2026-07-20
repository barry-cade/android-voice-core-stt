---
name: Kotlin Mini-PDP Structural Rules
alwaysApply: true
description: Structural rules for writing Kotlin code - flat, readable, deterministic, no clever idioms.
---

# Kotlin Mini‑PDP Structural Rules (CATO Agent Directive)

CATO must follow these rules for all Kotlin code it writes or modifies.

## 1. No nested lambdas unless absolutely necessary

- Flatten structure.
- Prefer named variables.
- Prefer explicit calls.

## 2. Avoid scope functions inside scope functions

- Only one scope function (also, apply, let, run, with) per block.
- Never nest them.

## 3. Avoid trailing lambdas as arguments to functions called within lambdas

- When a lambda body calls a function that takes a trailing lambda,
  extract that call into a named variable first.
- Trailing lambdas passed directly to a function within a lambda body
  create ambiguous binding that is hard to read.
- Prefer:
    val callback = { result -> handleResult(result) }
    registerCallback(callback)
  instead of:
    registerCallback { result ->
        handleResult(result)
    }

## 4. Prefer linear initialisation

- Construct → configure → start.
- In that order, top‑to‑bottom.
- No chaining for the sake of chaining.

## 5. Avoid clever Kotlin features unless they improve clarity

- If it looks fancy, it's probably wrong.
- Clarity > conciseness.

## 6. One action per line

- No multi‑action chains.
- No hidden behaviour inside scope functions.

## 7. No invisible behaviour

### 7a. Explicit interface implementation over SAM conversion

- Prefer `object : Interface { override fun method() { ... } }`.
- Only use SAM conversion (`Interface { ... }`) for functional interfaces
  where the callback body is a single expression.
- Never use SAM conversion when the body contains branching or nested calls.

### 7b. Explicit types at API boundaries

- Use explicit return types on all public and internal functions.
- Use explicit parameter types on all function declarations.
- Local `val` type inference is fine.

## 8. No nested conditionals

- Never place an `if` inside another `if`.
- Flatten conditional logic using guard clauses or early returns.
- Prefer:
    if (!condition) return
    // main logic
  instead of:
    if (condition) {
        if (otherCondition) {
            ...
        }
    }
- Nested conditionals hide intent and violate PDP linearity.

## 9. Prefer early return over state accumulation

- Do not thread a mutable variable through multiple branches.
- Return as soon as a result is determined.
- Prefer:
    if (condition) return simpleResult()
    return computeResult()
  instead of:
    var result = defaultValue
    if (condition) result = simpleResult()
    else result = computeResult()
    return result

## 10. One write site per mutable field

- Each `var` should be written in exactly one place (its initialiser).
- If a field must be reassigned, the reassignment must be in a single,
  dedicated method, not scattered across the class.
- Prefer `val` + copy where possible.
- Exception: `@Volatile` concurrency flags may be written in tight,
  guarded locations (e.g. synchronized blocks or atomic CAS calls).
- Exception: Direct assignment to bypass lifecycle validation on full
  teardown paths (e.g. destroy()) — document why the bypass is necessary.

---

**Goal:**  
Produce Kotlin that is flat, readable, deterministic, and PDP‑aligned, avoiding idiomatic cleverness in favour of structural clarity.
