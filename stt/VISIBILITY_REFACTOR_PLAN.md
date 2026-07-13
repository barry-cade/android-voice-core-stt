# STT Visibility Refactor Plan

## 1) Goal

Reduce accidental public API exposure in the stt module while preserving compatibility and testability.

This plan addresses all requested outcomes:

- mark implementation types internal
- seal strategy types
- hide JNI bridge details
- hide capture hardware abstractions
- expose only intended API surface
- preserve backward compatibility
- preserve test doubles

## 2) Current accidental public exposure inventory

The current effective public top-level surface (default Kotlin visibility) is larger than intended.

Evidence locations:

- [stt/build.gradle.kts](stt/build.gradle.kts#L20)
- [stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L42)
- [stt/src/main/java/dev/barrycade/voicecore/stt/WhisperBridge.kt](stt/src/main/java/dev/barrycade/voicecore/stt/WhisperBridge.kt#L5)
- [stt/src/main/java/dev/barrycade/voicecore/stt/AudioCapture.kt](stt/src/main/java/dev/barrycade/voicecore/stt/AudioCapture.kt#L22)

### 2.1 Public symbols currently exposed

1. SpeechToText
2. SpeechToTextProvider
3. SttRunConfig
4. StartStrategyConfig
5. StopStrategyConfig
6. TtsEngineConfig
7. VadConfig
8. DrainMode
9. SessionResult
10. SttReturnCode
11. SttError
12. SttErrorCode
13. SttErrorCategory
14. SttErrorListener
15. SttReadyListener
16. SttTimingSnapshot
17. SttLifecycleState
18. AudioCapture
19. WhisperBridge

### 2.2 Why this is accidental or overly broad

- Build-time API guard currently whitelists broad surface, including JNI and hardware-facing types, instead of enforcing a minimal facade: [stt/build.gradle.kts](stt/build.gradle.kts#L20).
- JNI bridge is directly public with external functions: [stt/src/main/java/dev/barrycade/voicecore/stt/WhisperBridge.kt](stt/src/main/java/dev/barrycade/voicecore/stt/WhisperBridge.kt#L24).
- Hardware capture class is directly public and app-consumable: [stt/src/main/java/dev/barrycade/voicecore/stt/AudioCapture.kt](stt/src/main/java/dev/barrycade/voicecore/stt/AudioCapture.kt#L22).
- Strategy config is stringly typed and open-ended at API boundary via StartStrategyConfig and StopStrategyConfig.

## 3) Target API surface

### 3.1 Intended long-term public surface (strict)

1. SpeechToText
2. SpeechToTextProvider
3. SttConfig (new, stable facade config)
4. SessionResult
5. SttReturnCode
6. SttError
7. SttErrorCode
8. SttErrorCategory
9. SttErrorListener
10. SttTimingSnapshot

Notes:

- JNI bridge and capture hardware abstractions are not part of this public surface.
- Strategy representations are sealed and non-stringly in the public contract.

### 3.2 Transitional compatibility policy

- Keep existing public types as deprecated adapters for one compatibility window.
- Remove deprecated adapters in next major release.
- Preserve runtime behavior and test seams throughout.

## 4) Complete list of planned changes

## 4.1 API governance and build checks

1. Update expected API list in [stt/build.gradle.kts](stt/build.gradle.kts#L20):

- remove AudioCapture, WhisperBridge, SttRunConfig, StartStrategyConfig, StopStrategyConfig, TtsEngineConfig, VadConfig, DrainMode, SttReadyListener, SttLifecycleState from long-term list
- add SttConfig as primary config facade

1. Replace direct WhisperBridge signature assertions in [stt/build.gradle.kts](stt/build.gradle.kts#L74) with assertions against internal bridge implementation wiring.

2. Add API check for deprecation window:

- allow legacy compatibility types only when annotated Deprecated with planned removal version.

## 4.2 Public config refactor and strategy sealing

1. Add new public facade type:

- create stt/src/main/java/dev/barrycade/voicecore/stt/SttConfig.kt
- include sealed strategy specifications:
  - sealed interface StartTrigger
  - sealed interface StopTrigger
  - data object Manual variants
  - data classes for VadStart, WakeWordStart, AutoSilenceStop, DurationStop

1. Add adapter mapper:

- create internal mapper file to convert SttConfig into RuntimeSttConfig

1. Keep current config DTOs during compatibility window:

- SttRunConfig, StartStrategyConfig, StopStrategyConfig, TtsEngineConfig, VadConfig, DrainMode remain public but Deprecated
- provide conversion helpers to SttConfig
- document exact migration path

1. Internals sealing:

- change [stt/src/main/java/dev/barrycade/voicecore/stt/StartStrategy.kt](stt/src/main/java/dev/barrycade/voicecore/stt/StartStrategy.kt#L13) to internal sealed interface
- change [stt/src/main/java/dev/barrycade/voicecore/stt/StopStrategy.kt](stt/src/main/java/dev/barrycade/voicecore/stt/StopStrategy.kt#L13) to internal sealed interface
- keep existing internal implementations in same package

## 4.3 Hide JNI bridge

1. Internalize bridge implementation:

- change [stt/src/main/java/dev/barrycade/voicecore/stt/WhisperBridge.kt](stt/src/main/java/dev/barrycade/voicecore/stt/WhisperBridge.kt#L5) from public object to internal object

1. Preserve compatibility window:

- add public deprecated compatibility facade (for example LegacyWhisperBridge) forwarding to internal bridge
- keep package-level binary continuity only during transition window

1. Keep JNI package/signature alignment in C++ unchanged:

- [stt/src/main/cpp/whisper_bridge.cpp](stt/src/main/cpp/whisper_bridge.cpp)

## 4.4 Hide capture hardware abstractions

1. Internalize hardware capture class:

- change [stt/src/main/java/dev/barrycade/voicecore/stt/AudioCapture.kt](stt/src/main/java/dev/barrycade/voicecore/stt/AudioCapture.kt#L22) to internal class

1. Preserve compatibility window:

- add deprecated wrapper facade for direct capture use only if required by downstream consumers

1. Keep internal capture seams unchanged:

- SessionManager and AudioSource stay internal contracts
- CaptureManager remains internal owner of capture lifecycle

## 4.5 SpeechToText API migration layer

1. Add new preferred entrypoints that consume SttConfig.

2. Keep existing methods accepting SttRunConfig during compatibility window:

- [stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L168)
- [stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L182)

1. Mark old config methods Deprecated with replacement guidance.

## 4.6 Public error and result surface

1. Keep public for now and long-term:

- SessionResult
- SttReturnCode
- SttError, SttErrorCode, SttErrorCategory, SttErrorListener
- SttTimingSnapshot

1. Remove SttReadyListener from public surface unless explicitly required by API contract.

2. Internalize SttLifecycleState unless explicitly required by app contracts.

## 4.7 Test doubles and testability preservation

1. Keep SpeechToText internal constructor injection unchanged for tests:

- [stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L42)

1. Keep internal interfaces used by doubles unchanged:

- WhisperModel
- SessionManager
- AudioSource

1. Preserve existing fake doubles in test sources:

- [stt/src/test/java/dev/barrycade/voicecore/stt/FakeWhisperModel.kt](stt/src/test/java/dev/barrycade/voicecore/stt/FakeWhisperModel.kt)
- [stt/src/test/java/dev/barrycade/voicecore/stt/FakeCaptureManager.kt](stt/src/test/java/dev/barrycade/voicecore/stt/FakeCaptureManager.kt)
- [stt/src/test/java/dev/barrycade/voicecore/stt/FakeAudioCapture.kt](stt/src/test/java/dev/barrycade/voicecore/stt/FakeAudioCapture.kt)

1. Update public API smoke tests to assert:

- new public surface present
- legacy surface marked deprecated during compatibility window
- no direct hardware or JNI exposure after compatibility window ends

## 5) Symbol-by-symbol disposition matrix

1. SpeechToText: keep public
2. SpeechToTextProvider: keep public
3. SttRunConfig: deprecate then remove
4. StartStrategyConfig: deprecate then remove
5. StopStrategyConfig: deprecate then remove
6. TtsEngineConfig: deprecate then remove
7. VadConfig: deprecate then remove
8. DrainMode: deprecate then remove
9. SessionResult: keep public
10. SttReturnCode: keep public
11. SttError: keep public
12. SttErrorCode: keep public
13. SttErrorCategory: keep public
14. SttErrorListener: keep public
15. SttReadyListener: deprecate then remove or fold into SpeechToText callback model
16. SttTimingSnapshot: keep public
17. SttLifecycleState: internalize unless strong consumer requirement exists
18. AudioCapture: deprecate then internalize
19. WhisperBridge: deprecate then internalize

## 6) Backward compatibility strategy

## 6.1 Non-breaking release

1. Add SttConfig and new sealed strategy model.
2. Keep old config and bridge/capture APIs public but Deprecated.
3. Keep runtime behavior and method signatures stable.
4. Provide deterministic adapters old -> new config model.

## 6.2 Major release cleanup

1. Internalize AudioCapture and WhisperBridge.
2. Remove old stringly config DTOs.
3. Shrink checkSttApiSurface to strict intended API.

## 7) Downstream impact list

Known current app imports depending on broad surface:

- [app/src/main/java/dev/barrycade/voicecore/AppSttConfigLoader.kt](app/src/main/java/dev/barrycade/voicecore/AppSttConfigLoader.kt)
- [app/src/main/java/dev/barrycade/voicecore/MainActivity.kt](app/src/main/java/dev/barrycade/voicecore/MainActivity.kt)
- [app/src/main/java/dev/barrycade/voicecore/audio/AudioTestService.kt](app/src/main/java/dev/barrycade/voicecore/audio/AudioTestService.kt)

Planned app migration:

1. Move loader output to SttConfig.
2. Remove direct AudioCapture usage from production path.
3. Route diagnostics through explicit debug-only internal hooks.

## 8) Execution order

1. Introduce SttConfig and sealed strategy model with adapters.
2. Add deprecation annotations to old public config and bridge/capture types.
3. Update app and tests to new API.
4. Tighten checkSttApiSurface expectations.
5. In major release, internalize deprecated bridge/capture/config types.

## 9) Verification checklist

1. Compile and test stt module.
2. Run public API smoke tests with deprecation assertions.
3. Run app module compile to validate migration.
4. Confirm no unintended public top-level symbols remain after cleanup phase.
