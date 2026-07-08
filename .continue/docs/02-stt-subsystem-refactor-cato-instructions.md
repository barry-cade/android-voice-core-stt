# STT Refactor — Cato Step‑By‑Step Instructions
This document tells Cato *exactly* what to modify at each step.  
Each step is independent and safe to apply in isolation.

> **Usage:** Paste this document into a new Cato session, then say:
> **"We have completed step X. Please perform step Y."**

---

## Step 1 — Introduce Unified Return Codes (no behaviour change)

### Goal

Add a return‑code system without altering any existing behaviour.

### Instructions

1. Create a new enum: `SttReturnCode`.
2. Add the following initial values (expand later if needed):
   - `OK`
   - `NO_SPEECH`
   - `SILENCE_TIMEOUT`
   - `UTTERANCE_TOO_LONG`
   - `ERROR`
3. Modify `SessionResult` to include:
