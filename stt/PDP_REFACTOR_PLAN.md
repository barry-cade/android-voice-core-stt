# STT PDP Refactor Plan

## 1) Objective

Refactor the STT module to align with PDP structural principles from [.continue/rules/mini-pdp.md](.continue/rules/mini-pdp.md), while preserving production behavior and enabling safe incremental rollout.

Scope:

- STT module internals and API edges
- lifecycle and threading model
- controller boundaries
- configuration flow
- deterministic data pipeline behavior

References:

- [stt/ARCHITECTURE_MAP.md](stt/ARCHITECTURE_MAP.md)
- [stt/CONCURRENCY_AUDIT_REPORT.md](stt/CONCURRENCY_AUDIT_REPORT.md)
- [stt/VISIBILITY_REFACTOR_PLAN.md](stt/VISIBILITY_REFACTOR_PLAN.md)

## 2) PDP principles translated to STT architecture

From [.continue/rules/mini-pdp.md](.continue/rules/mini-pdp.md), the refactor enforces:

1. Flat and explicit control flow

- no hidden control transitions
- no nested cleverness in orchestration

1. Construct -> configure -> start sequencing

- each runtime stage is explicit and linear

1. One write site per mutable field

- remove scattered writes and ambiguous ownership

1. Explicit boundaries at API edges

- typed config objects and typed state transitions

1. No invisible behavior

- no side effects hidden in callbacks or scope-function chains

## 3) Requested outcomes and concrete design targets

## 3.1 Preventive design

Target:

- Introduce preventive invariants and fail-fast guards before side effects.

Actions:

1. Add explicit precondition gate methods in [stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt):

- canInit
- canStart
- canStop
- canReset
- canDestroy

1. Introduce session epoch token to reject stale callbacks (prevents warm-start bleed).

2. Add invariant checks in capture and processing boundaries:

- single active session
- single active processor
- single active inference per epoch

1. Add guard-clause style for all lifecycle methods to remove nested conditionals.

## 3.2 Orthogonal controllers

Target:

- Each controller owns one concern, with no sideways ownership.

Controller responsibilities after refactor:

1. LifecycleController

- state transitions only

1. SessionController

- session ids and timing snapshots only

1. CaptureController

- audio hardware and queue ownership only

1. ProcessingController

- VAD + utterance accumulation only

1. InferenceController

- whisper execution and result mapping only

1. CallbackController

- callback dispatch policy only

Actions:

1. Remove orchestration leakage from non-orchestrators:

- no lifecycle writes from non-lifecycle controller
- no direct callback dispatch from low-level controllers

1. Keep [stt/src/main/java/dev/barrycade/voicecore/stt/SttModeController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttModeController.kt) as pure mode factory/selector.

2. Move inference submission wrapper out of SpeechToText helper into explicit InferenceController class.

## 3.3 Deterministic pipelines

Target:

- Deterministic, replayable stage transitions:
  Capture -> Process -> Finalize -> Infer -> Dispatch

Actions:

1. Introduce explicit pipeline stage enum and stage transition function.
2. Ensure each stage has a single entry and single exit path.
3. Prohibit re-entrant stage execution in same epoch.
4. Capture immutable per-epoch config snapshot at startSession.
5. Reject runtime strategy/config mutation while a session is active.

## 3.4 Explicit state transitions

Target:

- State graph is explicit and single-authority.

Actions:

1. Preserve [stt/src/main/java/dev/barrycade/voicecore/stt/SttLifecycleStateMachine.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttLifecycleStateMachine.kt) as only state transition authority.
2. Remove force-set transitions except documented teardown bypasses.
3. Add typed transition intent methods:

- requestInit
- requestReady
- requestStart
- requestFinalise
- requestStop
- requestReset
- requestDestroy

1. Add transition reason parameter for observability.

## 3.5 Explicit configuration injection

Target:

- No implicit config lookup or mutable global behavior.

Actions:

1. Build immutable RuntimeSessionConfig at session start from validated API config.
2. Inject RuntimeSessionConfig by constructor into all session-scoped controllers.
3. Remove mutable shared config reads from worker threads.
4. Keep adapters from legacy config model during compatibility window.

## 3.6 Removal of hidden coupling

Current hidden coupling examples:

- callback pointer mutated in one controller and consumed in another
- shared mutable flags crossing controller boundaries
- timing state read from multiple threads without ownership

Actions:

1. Replace callback pointer coupling with explicit event bus interface owned by orchestrator.
2. Replace shared mutable flags with typed command channel or atomic session gate.
3. Assign single owner per mutable state block and document owner in class header.

## 3.7 Removal of implicit behavior

Current implicit behavior examples:

- stop path transitions to READY before inference completion
- inference in-flight guard resets before callback completion
- mutable listeners changed concurrently with dispatch

Actions:

1. Move READY transition to explicit post-inference completion path.
2. Tie in-flight flags to completion callbacks, not submission.
3. Snapshot listeners before dispatch, with synchronization policy.
4. Make all lifecycle side effects explicit method calls, one action per line.

## 3.8 Improved lifecycle clarity

Target lifecycle (single happy path):
UNINITIALISED -> INITIALISED -> READY -> RECORDING -> FINALISING -> STOPPED -> READY

Actions:

1. Define lifecycle table in code comments and tests.
2. Add transition tests for each legal edge and illegal edge.
3. Ensure each public API method maps to exactly one transition intent.

## 3.9 Improved threading clarity

Target threading model:

1. API thread

- lifecycle command entry only

1. Capture thread

- hardware read only

1. Processing thread

- VAD and accumulator only

1. Inference executor thread

- whisper inference only

1. Callback delivery thread policy

- explicit and documented for each callback

Actions:

1. Add thread ownership comments and assertions at controller boundaries.
2. Add session epoch checks in every async callback.
3. Reduce lock scope around blocking joins and native calls.
4. Prevent self-join patterns in worker stop methods.

## 4) Step-by-step execution plan

## Phase 0: Baseline and freeze

1. Freeze behavior with focused tests:

- lifecycle transition tests
- warm-start regression tests
- callback ordering tests
- concurrent start/stop stress tests

1. Add architecture decision note summarizing target PDP model.

Exit criteria:

- baseline tests green and deterministic over repeated runs.

## Phase 1: Concurrency contract hardening

1. Introduce single lifecycle mutex in SpeechToText.
2. Enforce method-level serialization for lifecycle APIs.
3. Introduce AtomicLong sessionEpoch and stale-callback rejection.
4. Fix inference in-flight flag semantics.
5. Add self-join guards in processor/minimal polling stop paths.

Files primarily affected:

- [stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt)
- [stt/src/main/java/dev/barrycade/voicecore/stt/ProcessorController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/ProcessorController.kt)
- [stt/src/main/java/dev/barrycade/voicecore/stt/SttModeController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttModeController.kt)
- [stt/src/main/java/dev/barrycade/voicecore/stt/SttCallbackDispatcher.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttCallbackDispatcher.kt)

Exit criteria:

- no stale callback in rapid stop/start loop test.

## Phase 2: Explicit pipeline stages

1. Add pipeline stage model and transition function.
2. Refactor stop and finalize flow to stage-driven path.
3. Ensure READY transition happens only after inference completion or explicit empty-PCM path.

Files:

- [stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt)
- new stage model file in stt package

Exit criteria:

- stage transition logs are linear and deterministic.

## Phase 3: Orthogonal controller extraction

1. Introduce InferenceController for whisper submission/callback adaptation.
2. Move result timing assembly out of SpeechToText into InferenceController.
3. Keep SpeechToText as orchestrator only.

Files:

- [stt/src/main/java/dev/barrycade/voicecore/stt/ModelManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/ModelManager.kt)
- [stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt)
- new inference controller file

Exit criteria:

- SpeechToText orchestration methods become linear command sequencing.

## Phase 4: Explicit config injection

1. Build immutable RuntimeSessionConfig per session.
2. Inject RuntimeSessionConfig into processing/capture/inference paths once per session.
3. Remove cross-thread reads of mutable config fields.

Files:

- [stt/src/main/java/dev/barrycade/voicecore/stt/RuntimeSttConfig.kt](stt/src/main/java/dev/barrycade/voicecore/stt/RuntimeSttConfig.kt)
- [stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt)
- [stt/src/main/java/dev/barrycade/voicecore/stt/SttModeController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttModeController.kt)

Exit criteria:

- all worker threads consume immutable session config snapshots only.

## Phase 5: Hidden coupling removal

1. Replace mutable callback pointer with explicit listener registration contract owned by orchestrator.
2. Remove scattered mutable state writes by introducing dedicated setter methods with single write site.
3. Add owner comments for each shared mutable field.

Files:

- [stt/src/main/java/dev/barrycade/voicecore/stt/SttModeController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttModeController.kt)
- [stt/src/main/java/dev/barrycade/voicecore/stt/SttSessionController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttSessionController.kt)
- [stt/src/main/java/dev/barrycade/voicecore/stt/SttCallbackDispatcher.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttCallbackDispatcher.kt)

Exit criteria:

- no cross-controller hidden mutable references.

## Phase 6: Lifecycle clarity refactor

1. Add explicit transition-intent API in lifecycle controller.
2. Collapse duplicated transition calls and bypasses.
3. Document allowed transitions in one table, used by tests.

Files:

- [stt/src/main/java/dev/barrycade/voicecore/stt/SttLifecycleController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttLifecycleController.kt)
- [stt/src/main/java/dev/barrycade/voicecore/stt/SttLifecycleStateMachine.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttLifecycleStateMachine.kt)

Exit criteria:

- all lifecycle transitions occur through explicit intent methods.

## Phase 7: Threading clarity refactor

1. Define a Threading Contract section in code docs.
2. Add thread ownership assertions where feasible.
3. Reduce lock hold duration around joins/native calls.

Files:

- [stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt)
- [stt/src/main/java/dev/barrycade/voicecore/stt/CaptureManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/CaptureManager.kt)
- [stt/src/main/java/dev/barrycade/voicecore/stt/AudioCapture.kt](stt/src/main/java/dev/barrycade/voicecore/stt/AudioCapture.kt)

Exit criteria:

- no blocking operations under broad orchestration lock unless justified and documented.

## Phase 8: PDP code-shape cleanup

1. Flatten nested conditionals via guard clauses.
2. Replace nested lambdas with named callbacks where bodies are complex.
3. Ensure one action per line in lifecycle orchestration.
4. Ensure explicit function return types at internal/public boundaries.

Files:

- all touched Kotlin files above

Exit criteria:

- PDP lint/checklist passes manually on touched files.

## Phase 9: Compatibility and API migration alignment

1. Keep compatibility adapters from visibility plan while internals are refactored.
2. Ensure external behavior remains stable through adapter layers.
3. Update API smoke tests and migration docs.

References:

- [stt/VISIBILITY_REFACTOR_PLAN.md](stt/VISIBILITY_REFACTOR_PLAN.md)

Exit criteria:

- no public API regression in compatibility release.

## Phase 10: Verification and rollout

1. Run unit tests repeatedly to detect flakiness.
2. Add stress test matrix:

- rapid start-stop
- start-destroy races
- reset during callback windows
- back-to-back sessions with overlapping inference completion

1. Capture and publish before/after concurrency metrics.

Exit criteria:

- stable test matrix and no concurrency regressions across repeated runs.

## 5) Work package checklist

1. Preventive design

- guards, invariants, epoch checks

1. Orthogonal controllers

- extraction and responsibility cleanup

1. Deterministic pipeline

- stage model and linear transitions

1. Explicit state transitions

- intent-driven lifecycle methods

1. Explicit configuration injection

- immutable per-session snapshot

1. Hidden coupling removal

- owner-based mutable state and explicit interfaces

1. Implicit behavior removal

- no early READY, no implicit callback races

1. Lifecycle clarity

- single transition table and tests

1. Threading clarity

- documented ownership and synchronized access policy

## 6) Definition of done

Refactor is complete when:

1. Lifecycle flow is linear and stage-driven.
2. No stale callback can affect a new session.
3. All shared mutable state has explicit ownership and synchronization policy.
4. Public behavior remains backward compatible in transition release.
5. PDP structural checklist is satisfied on touched files.
