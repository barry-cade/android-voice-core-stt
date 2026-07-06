STT Behavioural Contract (PDP‑Aligned)
The authoritative behavioural specification for the Speech‑To‑Text pipeline.

1. System Purpose
The STT system converts live microphone audio into text using Whisper.
It must behave deterministically, with predictable lifecycle transitions, stable timing, and no hidden concurrency.

2. Lifecycle States
The STT system has four valid states:

UNINITIALISED
Model not loaded

Warm‑up not performed

No capture allowed

No inference allowed

READY
Model loaded

Warm‑up completed

System prepared to begin recording

Capture may begin only in this state

RECORDING
Microphone active

PCM frames flowing

VAD accumulating utterance

STOP may be invoked

FINALISING
Capture stopped

PCM finalised

Whisper inference running

Transition back to READY after inference

Illegal transitions must never occur.

1. Warm‑Up Behaviour
Warm‑up is a mandatory, one‑time operation per model load.

Warm‑up must:

run before the system enters READY

run without starting microphone capture

run without producing user‑visible output

run without blocking the UI thread

produce a dummy inference ([BLANK_AUDIO]) only for timing calibration

set isReady=true only after completion

Warm‑up must never:

start PCM capture

enter RECORDING

interfere with STOP

interfere with VAD

interfere with user audio

1. Start Behaviour
start() means begin recording now.

Start must:

only succeed when the system is in READY

transition READY → RECORDING

start microphone capture

begin PCM flow

activate VAD

prepare utterance accumulation

Start must never:

run during UNINITIALISED

run during FINALISING

run during warm‑up

run during model load

implicitly queue itself unless explicitly designed

implicitly auto‑start unless explicitly designed

If start() is called early, the system must:

ignore the call

log the reason

remain stable

1. STOP Behaviour
STOP means finalise the current utterance.

STOP must:

stop microphone capture

flush PCM accumulator

apply stabilisation (pre‑roll, trailing silence, minimum length)

produce final PCM for inference

run Whisper inference

return text

transition RECORDING → FINALISING → READY

STOP must never:

run during UNINITIALISED

run during READY

run during warm‑up

run during model load

run without PCM (unless STOP was invoked before any recording)

1. PCM Flow Behaviour
PCM must only flow during RECORDING.

PCM must never flow:

during UNINITIALISED

during READY

during warm‑up

during FINALISING

PCM must be:

continuous

timestamp‑aligned

thread‑safe

non‑blocking

1. VAD Behaviour
VAD must:

operate only on PCM frames during RECORDING

accumulate utterance until STOP

apply stabilisation rules

produce deterministic final PCM

VAD must never:

run during warm‑up

run during model load

run during READY

run during FINALISING

1. Concurrency Guarantees
The system must guarantee:

model load is async

warm‑up is async

inference is async

capture is threaded

lifecycle transitions are atomic

no UI‑thread blocking

no deadlocks

no race conditions between READY and RECORDING

1. User Experience Contract
From the user’s perspective:

They press Start

If STT is READY → recording begins immediately

If STT is not READY → Start is ignored

When STT becomes READY → user must press Start again (unless queued‑start is enabled)

They speak

They press STOP

They receive text

No clipping.
No freezes.
No [BLANK_AUDIO] unless STOP was invoked before any recording.

1. Deterministic Timing
The system must guarantee:

warm‑up duration is measured

inference duration is measured

PCM frame timing is stable

STOP finalisation timing is stable

no main‑thread stalls

End of Behavioural Contract
