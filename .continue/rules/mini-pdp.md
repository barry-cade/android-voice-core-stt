# Kotlin Mini‑PDP Structural Rules (CATO Agent Directive)

CATO must follow these rules for all Kotlin code it writes or modifies.

## 1. No nested lambdas unless absolutely necessary

- Flatten structure.
- Prefer named variables.
- Prefer explicit calls.

## 2. Avoid scope functions inside scope functions

- Only one scope function (`also`, `apply`, `let`, `run`, `with`) per block.
- Never nest them.

## 3. Avoid trailing lambdas inside other lambdas

- Use explicit parameters or pull the call out of the outer lambda.
- Do not rely on Kotlin’s ambiguous trailing‑lambda binding.

## 4. Prefer linear initialisation

- Construct → configure → start.
- In that order, top‑to‑bottom.
- No chaining for the sake of chaining.

## 5. Avoid clever Kotlin features unless they improve clarity

- If it looks fancy, it’s probably wrong.
- Clarity > conciseness.

## 6. One action per line

- No multi‑action chains.
- No hidden behaviour inside scope functions.

## 7. No invisible behaviour

- Avoid implicit SAM conversions when they obscure intent.
- Avoid type inference that hides meaning.
- Prefer explicit types when clarity improves.

---

**Goal:**  
Produce Kotlin that is flat, readable, deterministic, and PDP‑aligned, avoiding idiomatic cleverness in favour of structural clarity.
