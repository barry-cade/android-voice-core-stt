STT Operational Goal (for Cato)
The purpose of the STT system is to reliably capture user speech and convert it into text.
The system must guarantee that:

When the user presses Start, the system will begin recording as soon as it is READY.

If the system is not READY when Start is pressed, the Start request must be remembered and executed automatically once READY is reached.

Once recording begins, PCM must flow continuously until STOP.

When the user presses STOP, the system must produce a transcription of the recorded speech.

STOP must never produce [BLANK_AUDIO] unless the user pressed STOP before any recording occurred.

The system must never enter RECORDING before READY.

The system must never lose a Start request.

The system must never require the user to press Start twice unless explicitly configured to do so.

This operational goal overrides any default behaviour in the behavioural contract.
Queued‑start is not optional — it is required to satisfy the user experience.

⭐ Why this matters
Once Cato has:

Behavioural Contract (rules)

Operational Goal (purpose)

…it can finally reason about:

why queued‑start must fire

why Start must not be ignored

why STOP must not produce blank audio

why READY must trigger recording if Start was requested

why the UI should not need to press Start twice

why warm‑up must not interfere with user audio

why lifecycle must be strict but not obstructive

This is the missing context that will stop the endless directive loop.