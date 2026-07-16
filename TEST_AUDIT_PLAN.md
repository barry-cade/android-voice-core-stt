# STT Test Suite — Gold Standard Audit Plan

## Objective

Audit all **31 unit test files** + **1 androidTest file** in `stt/src/test/` and `app/src/test/` for:

1. **Redundancy** — tests that overlap in coverage
2. **Pseudo-tests** — tests changed only to pass, not to verify intent
3. **Backward-compatibility tests** — tests for legacy types or behaviours that no longer exist and have zero requirement for backward compat
4. **Structural violations** — tests that violate the PDP rules (nested conditionals, scope function pyramids, trailing lambdas, etc.)
5. **Gap analysis** — areas of production code with no meaningful test coverage

The project has **zero backward-compatibility requirement**. Any test preserving legacy behaviour must be flagged for removal or rewrite.

---

## Plan of Attack

### Phase 1 — Read all test files and source files (✅ DONE)

All test and key source files have been read into context.

### Phase 2 — Categorise each test file

Each file will be classified as one or more of:

| Label | Meaning |
|-------|---------|
| **REDUNDANT** | Overlaps significantly with another test file |
| **LEGACY-BC** | Tests backward-compat or legacy types no longer needed |
| **PSEUDO** | Tests pass but don't meaningfully assert intent |
| **FAKE-TEST** | Tests the test double (fake), not production code |
| **OK-REFACTOR** | Valid intent but could be cleaner/merged |
| **OK** | Clean, intent-verified, PDP-aligned |

### Phase 3 — Execute changes

For each flagged file, either:

- Delete entirely, or
- Merge into another file, or
- Rewrite to remove redundancy/legacy/PDP violations

### Phase 4 — Verify build

Run `./gradlew :stt:test` after all changes.

---

## Audit Results

### Test files requiring action

#### 1. `CaptureManagerTest.kt` — **FAKE-TEST**

- Tests `FakeCaptureManager` (a test double), not production `CaptureManager`.
- The fake is used as a test helper; testing its internals (session buffer, pollFrame buffering) provides zero value about production behaviour.
- The real `CaptureManager` is tested indirectly via `SpeechToText` integration tests (that's the stated intention in the test's own doc comment).
- **Action: DELETE.** These tests validate the fake itself, not production code.

#### 2. `DeterministicCaptureTest.kt` — **REDUNDANT + FAKE-TEST**

- Duplicates `CaptureManagerTest.kt` — tests the same `FakeCaptureManager.begin()` semantics.
- Also tests the fake, not production code.
- **Action: DELETE.** Completely redundant with CaptureManagerTest.

#### 3. `DrainModeTest.kt` — **FAKE-TEST**

- Tests `DrainMode` behaviour through `FakeCaptureManager`.
- The drain mode logic is trivial (clear queue vs preserve queue) — testing this through a fake is testing the fake's implementation of the drain logic.
- **Action: DELETE.** The drain mode semantics are implicitly covered by any session test that uses real behaviour.

#### 4. `PublicApiSmokeTest.kt` — **DELETED (empty file)**

- Already empty — the file comment says "File deleted — tested deprecated types removed in legacy-type sweep."
- **Action: No change needed** (verify deleted from disk).

#### 5. `ReturnCodeMappingTest.kt` — **LEGACY-BC + PSEUDO**

- Tests that `SttReturnCode` enum values equal themselves (`SUCCESS == SUCCESS`).
- Comment says "The legacy mapping layer has been removed" — yet this test was kept.
- Each test is: `assertEquals(SttReturnCode.SUCCESS, SttReturnCode.SUCCESS)` — this verifies nothing.
- Tautological assertions.
- **Action: DELETE.** Zero assertion value. The enum exists; Kotlin compiler verifies enum entries exist.

#### 6. `SttErrorCodeTest.kt` — **PSEUDO (partially)**

- Constructs `SttError` objects and checks their fields.
- Most tests are: create an error → assert fields match what you just set.
- Tests like `enumContainsOnlyApprovedCodes` and `noLegacyCodeNamesReferenced` are **backward-compatibility tests** — they verify that old legacy codes are NOT present, which only matters if you're protecting against someone adding them back. Zero backward-compat requirement means these are noise.
- `everyErrorCodeHasCorrectCategory` is the **single meaningful test** in this file (validates the compile-time mapping).
- The `sttErrorRequiresCodeAndMessage` and `modelLoadFailure_containsModelPathInDetails` etc. are all tautological (test that data classes store what they're given).
- **Action:** DELETE entire file. Keep only `everyErrorCodeHasCorrectCategory` if it's not already covered elsewhere. The category mapping is already baked into the enum's `category` property and verified by the compiler.

#### 7. `SttLifecycleStateTest.kt` — **REDUNDANT with SttPipelineBehaviourTest + SttStopPathTest**

- Replicates the exact transition logic from `SttLifecycleStateMachine.transitionTo` as a local `applyTransition` function.
- Tests its own local copy of the transition matrix, not the production state machine.
- The real `SttLifecycleStateMachine` is never instantiated.
- **This is the worst offender** — it's testing a hand-rolled replica of the production logic, not the production code itself.
- Duplicates `SttPipelineBehaviourTest` (which tests the same transitions in lines like `state machine UNINITIALISED to INITIALISED to READY`) and `SttStopPathTest`.
- The log capture via `companion object` is a clever hack that tests logging infrastructure, not behaviour.
- **Action: DELETE.** Replace with a single, focused test that instantiates `SttLifecycleStateMachine` and tests it directly (if not already covered by `SttPipelineBehaviourTest`).

#### 8. `SttStopPathTest.kt` — **REDUNDANT with SttLifecycleStateTest + PSEUDO**

- Same pattern: replicates production transition logic locally.
- Tests a local copy, not `SttLifecycleStateMachine`.
- Tests "no warmup during stop" by checking log strings — testing log format, not behaviour.
- `stopPath_modelUnloadOccursAfterStopped` verifies state variable is STOPPED (circular).
- `stopPath_pcmFinalisationOccursBeforeInference` sets `pcmFinalised = true` then asserts it's true.
- **Action: DELETE.** The lifecycle transitions it tests are already covered by a real `SttLifecycleStateMachine` test or should be.

#### 9. `SttPipelineBehaviourTest.kt` — **REDUNDANT overlap with SttDeterministicTest + SttLifecycleStateTest**

- Has its own `applyTransition` that duplicates `SttLifecycleStateTest.applyTransition` and `SttStopPathTest.applyTransition`.
- Tests like `state machine UNINITIALISED to INITIALISED to READY` overlap with `SttLifecycleStateTest.legalTransition_uninitialisedToInitialised`.
- Tests like `READY to STOPPED is valid` are in the lifecycle state test too.
- The accumulator tests (`full pipeline start warmup...`) overlap with `SttDeterministicTest` and `UtteranceAccumulatorTest`.
- **Action:** REMOVE the state machine transition tests (they're duplicated or should use the real state machine). Keep the accumulator pipeline tests (pre-roll → speech → silence → UtteranceReady) as they test real `UtteranceAccumulator` behaviour.

#### 10. `SttWarmupTest.kt` — **PSEUDO + LEGACY-BC**

- Simulates warmup logic in pure Kotlin (never calls the real warmup path through `SpeechToText` or `FakeWhisperModel`).
- Tests `performWarmup()` which is a hand-rolled replica, not production code.
- Tests like `warmup_neverRunsIfModelLoadFails` set `modelLoadSucceeded = false` then never call warmup, then assert warmup wasn't called. This is testing a flag that the test itself set.
- Warmup invocation is already tested by `WarmupInvocationTest.kt` which uses the real `FakeWhisperModel` with `warmupCount`.
- **Action: DELETE.** `WarmupInvocationTest.kt` covers the real warmup contract.

#### 11. `ManualManualStrategyTest.kt` — **REDUNDANT with StrategyCombinationTest**

- Tests `ManualStart` and `ManualStop` behaviour.
- `StrategyCombinationTest` already tests MANUAL/MANUAL strategy combination (section 1: `manualManual_startOnlyOnEvent`).
- The `manualStop_ignoresVadSilence`, `manualStop_ignoresElapsedDuration` etc. are all subsets of what `StrategyCombinationTest` covers.
- **Action: DELETE.** Fully covered by `StrategyCombinationTest`.

#### 12. `ProcessorControllerTest.kt` — **REDUNDANT (partially)**

- Tests `ProcessorController` but only through its public accessors (`vadActiveMs`, `lastUtteranceDurationMs`, `vadConfidence`).
- The "pipeline tests" at the bottom (`process_speechFrame_triggersListener`, etc.) use `Thread.sleep()` for timing — these are flaky by design.
- `drainRemainingFrames_withFrames_drainsSuccessfully` has no assertion (comment says "No crash is the assertion").
- `rapidStartStop_noCrash` and `stop_twice_isNoop` test idempotency contracts that should be in a single, focused idempotency test.
- **Action:** CONDENSE. Remove sleep-based timing tests (they're flaky). Remove no-assertion tests. Keep only the accessor/value tests if they provide unique coverage not in other files.

#### 13. `SttDeterministicTest.kt` — **REDUNDANT with UtteranceAccumulatorTest + SttPipelineBehaviourTest**

- Tests `UtteranceAccumulator` with VAD, pre-roll, and silence finalization.
- Duplicates `UtteranceAccumulatorTest.emitsUtteranceAfterSilence` and `SttPipelineBehaviourTest.full pipeline...`.
- **Action: DELETE.** Covered by `UtteranceAccumulatorTest` and condensed `SttPipelineBehaviourTest`.

#### 14. `SttDeterministicPipelineTest.kt` — **OK but check overlap**

- Uses the real JSON-boundary API with a `BlockingWhisperModel`.
- Tests `stopNonEmpty_transitionsReadyOnlyAfterInferenceCompletes` — validates lifecycle/pipeline sequencing.
- `stopEmpty_transitionsReadyImmediately` — validates empty stop path.
- **Potential overlap:** `SttStaleCallbackStressTest` also uses `BlockingWhisperModel` with similar setup.
- **Action:** MERGE with `SttStaleCallbackStressTest` into a single `SttPipelineSequencingTest.kt`.

#### 15. `SttStaleCallbackStressTest.kt` — **OK but overlap**

- Same `BlockingWhisperModel` pattern as `SttDeterministicPipelineTest`.
- Tests stale callback rejection via session epochs.
- The `nonStaleResultDelivered_whenEpochUnchanged` test is a weaker version of `SttDeterministicPipelineTest.stopNonEmpty_transitionsReadyOnlyAfterInferenceCompletes`.
- **Action:** MERGE with `SttDeterministicPipelineTest` into `SttPipelineSequencingTest.kt`.

#### 16. `SpeechToTextTest.kt` — **REDUNDANT with SpeechToTextNewApiTest.kt**

- Minimal overlap but `SpeechToTextNewApiTest` is the more comprehensive JSON-boundary test.
- `constructor_createsInstance` — verifies `assertNotNull(speechToText)` after constructor. Minimal value.
- `init_returnsNullOnSuccess` / `init_withInvalidConfigJson_returnsError` — dups `SpeechToTextNewApiTest`.
- `transcribe_doesNotThrow`, `processStart_doesNotThrow`, `processStart_twice_isIdempotent` — no meaningful assertions ("doesn't crash" in a try/catch that swallows RuntimeExceptions).
- **Action: DELETE.** The meaningful JSON-boundary tests are in `SpeechToTextNewApiTest`. The "doesn't crash" tests add no signal.

#### 17. `SttSessionControllerTest.kt` — **OK**

- Only 3 tests, all meaningful.
- Concurrent stress test, zero-value check, and start/stop reflection check.
- **Action:** Keep.

#### 18. `SttCallbackDispatcherTest.kt` — **OK**

- `clearListeners_preventsFurtherDispatch` — meaningful.
- `concurrentRegisterClearAndDispatch_doesNotThrow` — meaningful stress test.
- **Action:** Keep.

#### 19. `SttInferenceControllerTest.kt` — **OK**

- Tests the 3 dispatch decisions: allow, reject, shutdown-reject.
- **Action:** Keep.

#### 20. `SttPipelineStateTest.kt` — **PSEUDO (test of its own correctness)**

- Tests `SttPipelineState.transitionTo()` — the production class.
- These are valid if they test the production class. However, the transition rules are simple maps.
- **Issues:** `legalTransition_inferencingToDispatching_allowed` transitions: IDLE → CAPTURING → INFERENCING → DISPATCHING, but the actual legal path is IDLE → CAPTURING → **FINALISING** → INFERENCING → DISPATCHING. The test skips FINALISING.
- This means the test's "setup" path doesn't match reality: `transitionTo(INFERENCING)` from `CAPTURING` skips FINALISING.
- Wait — looking at `SttPipelineState.isAllowedTransition`, CAPTURING -> INFERENCING IS allowed (line: `SttPipelineStage.CAPTURING -> to == SttPipelineStage.INFERENCING`). So the production code allows CAPTURING → INFERENCING directly. The test is correct about the production code.
- **Action:** Keep (tests real production class). Worth noting the unusual transition path (missing FINALISING) is production code's design.

#### 21. `SttTimingSnapshotTest.kt` — **PSEUDO**

- Tests that a data class holds values passed to its constructor.
- `timingSnapshot_AllFieldsNonNull` — tautological (assert that constructor args equal themselves).
- `timingSnapshot_immutable` — tests `copy()` on a data class. The Kotlin compiler guarantees this.
- `timingTotalMsGreaterThanComponentSum` — tests that `50L + 100L + 500L + 300L` is less than `50L + 100L + 500L + 300L + 50L`. Tautological.
- `noAdhocTimingLogsRequired` — constructs an object, asserts `assertNotNull(snapshot)`. What is this testing?
- `timingSnapshot_constructorAcceptsZeroValues` — tests that 0 == 0.
- **Action: DELETE.** Zero assertion value. `SttTimingSnapshot` is a plain data class; its contract is verified by the compiler.

#### 22. `RuntimeSttConfigTest.kt` — **OK**

- Tests validation bounds for `RuntimeSttConfig`.
- `from_sttConfig_populatesCorrectly` and `from_sttConfig_autoSilence_populatesCorrectly` test the `from(SttConfig)` factory.
- **Action:** Keep.

#### 23. `VadTest.kt` — **OK**

- Tests the real `Vad` class with real frames.
- Covers energy threshold, hysteresis, confidence, edge cases.
- **Action:** Keep.

#### 24. `UtteranceAccumulatorTest.kt` — **OK (but thin)**

- Only 4 tests. Covers silence timeout, silence-only, forceFinalize.
- Some overlap with `SttDeterministicTest` (which we're deleting) and `SttPipelineBehaviourTest`.
- **Action:** Keep. Consider expanding if gaps exist, but not required for this audit.

#### 25. `WarmupInvocationTest.kt` — **OK**

- Tests that `FakeWhisperModel.warmup()` is called with correct arguments during `SpeechToText.init()`.
- Covers enabled/disabled/duration-zero/second-init scenarios.
- **Action:** Keep.

#### 26. `SttErrorDeliveryTest.kt` — **OK (partially)**

- Tests that errors are delivered to BOTH the return value AND the message listener.
- Validates JSON structure of error messages.
- `errorDeliveredToBothReturnAndListener` is a strong test.
- The `errorJson_containsAllRequiredFields` uses regex to verify JSON fields — brittle but meaningful.
- **Action:** Keep. Could be simplified but has real coverage value.

#### 27. `ModelManagerTest.kt` — **OK**

- Tests `ModelManager` with `FakeWhisperModel`.
- Covers load, unload, reload, shutdown, failure modes.
- Uses `waitForReady()` polling — flaky but acceptable for unit tests with short timeouts.
- **Action:** Keep.

#### 28. `StrategyCombinationTest.kt` — **OK**

- Comprehensive strategy combination tests.
- Tests all 4 start/stop combinations.
- **Action:** Keep. (Already absorbs `ManualManualStrategyTest` after deletion.)

#### 29. `FakeAudioCapture.kt`, `FakeCaptureManager.kt`, `FakeWhisperModel.kt` — **Test infrastructure**

- Not tests themselves; used by other tests.
- **Action:** Keep.

#### 30. `app/src/test/.../MainActivityStateTest.kt` — **OK but minimal**

- Tests UI button visibility logic.
- **Action:** Keep.

#### 31. `app/src/test/.../NewApiSmokeTest.kt` — **OK but minimal**

- Tests JSON config construction.
- **Action:** Keep.

#### 32. `stt/src/androidTest/.../CaptureManagerOwnershipStressTest.kt` — **OK**

- Android-instrumented concurrency test for real `CaptureManager`.
- **Action:** Keep.

---

## Summary of Changes

| File | Verdict | Action |
|------|---------|--------|
| CaptureManagerTest.kt | FAKE-TEST | DELETE |
| DeterministicCaptureTest.kt | FAKE-TEST + REDUNDANT | DELETE |
| DrainModeTest.kt | FAKE-TEST | DELETE |
| PublicApiSmokeTest.kt | EMPTY | Already deleted (verify) |
| ReturnCodeMappingTest.kt | LEGACY-BC + PSEUDO | DELETE |
| SttErrorCodeTest.kt | PSEUDO + LEGACY-BC | DELETE |
| SttLifecycleStateTest.kt | REDUNDANT + FAKE-REPLICA | DELETE |
| SttStopPathTest.kt | REDUNDANT + PSEUDO | DELETE |
| SttPipelineBehaviourTest.kt | PARTIAL REDUNDANT | REMOVE state machine tests; keep accumulator pipeline tests |
| SttWarmupTest.kt | PSEUDO + LEGACY-BC | DELETE |
| ManualManualStrategyTest.kt | REDUNDANT | DELETE |
| ProcessorControllerTest.kt | PARTIAL REDUNDANT | CONDENSE: remove sleep-based and no-assertion tests |
| SttDeterministicTest.kt | REDUNDANT | DELETE |
| SttDeterministicPipelineTest.kt | OK (overlap) | MERGE with SttStaleCallbackStressTest |
| SttStaleCallbackStressTest.kt | OK (overlap) | MERGE with SttDeterministicPipelineTest |
| SpeechToTextTest.kt | REDUNDANT | DELETE |
| SttTimingSnapshotTest.kt | PSEUDO | DELETE |
| SttSessionControllerTest.kt | OK | Keep |
| SttCallbackDispatcherTest.kt | OK | Keep |
| SttInferenceControllerTest.kt | OK | Keep |
| SttPipelineStateTest.kt | OK | Keep |
| RuntimeSttConfigTest.kt | OK | Keep |
| VadTest.kt | OK | Keep |
| UtteranceAccumulatorTest.kt | OK | Keep (thin but valid) |
| WarmupInvocationTest.kt | OK | Keep |
| SttErrorDeliveryTest.kt | OK | Keep |
| ModelManagerTest.kt | OK | Keep |
| StrategyCombinationTest.kt | OK | Keep |
| MainActivityStateTest.kt | OK | Keep (app module) |
| NewApiSmokeTest.kt | OK | Keep (app module) |
| CaptureManagerOwnershipStressTest.kt | OK | Keep (androidTest) |

### Counting

- **DELETE:** 12 files
- **CONDENSE/MERGE:** 4 files → ~2 files
- **KEEP:** ~14 files + 3 test infra files + 2 app files + 1 androidTest
- **Net reduction:** From **31 test files** to approximately **17 test files** (plus 3 infra files)

### Phase 3 — Execution Order

1. Delete files with no dependencies (pure deletions)
2. Condense ProcessorControllerTest.kt
3. Merge SttDeterministicPipelineTest + SttStaleCallbackStressTest
4. Trim SttPipelineBehaviourTest.kt
5. Verify deleted PublicApiSmokeTest.kt
6. Run `./gradlew :stt:test` to verify
