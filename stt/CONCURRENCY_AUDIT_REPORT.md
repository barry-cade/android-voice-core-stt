# STT Module Concurrency Audit Report

## 1) Audit scope

Code reviewed:

- [stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt)
- [stt/src/main/java/dev/barrycade/voicecore/stt/CaptureManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/CaptureManager.kt)
- [stt/src/main/java/dev/barrycade/voicecore/stt/AudioCapture.kt](stt/src/main/java/dev/barrycade/voicecore/stt/AudioCapture.kt)
- [stt/src/main/java/dev/barrycade/voicecore/stt/ProcessorController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/ProcessorController.kt)
- [stt/src/main/java/dev/barrycade/voicecore/stt/SttModeController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttModeController.kt)
- [stt/src/main/java/dev/barrycade/voicecore/stt/ModelManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/ModelManager.kt)
- [stt/src/main/java/dev/barrycade/voicecore/stt/SttCallbackDispatcher.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttCallbackDispatcher.kt)
- [stt/src/main/java/dev/barrycade/voicecore/stt/SttSessionController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttSessionController.kt)
- [stt/src/main/java/dev/barrycade/voicecore/stt/SttLifecycleStateMachine.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttLifecycleStateMachine.kt)
- [stt/src/main/java/dev/barrycade/voicecore/stt/SttEvents.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttEvents.kt)
- [stt/src/main/java/dev/barrycade/voicecore/stt/Vad.kt](stt/src/main/java/dev/barrycade/voicecore/stt/Vad.kt)
- [stt/src/main/java/dev/barrycade/voicecore/stt/RmsSampler.kt](stt/src/main/java/dev/barrycade/voicecore/stt/RmsSampler.kt)
- [stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToTextProvider.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToTextProvider.kt)
- [stt/src/main/java/dev/barrycade/voicecore/stt/SttThreadController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttThreadController.kt)
- [stt/src/main/cpp/whisper_bridge.cpp](stt/src/main/cpp/whisper_bridge.cpp)

## 2) All threads

1. Caller thread(s)

- Public lifecycle and config calls in [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L168), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L182), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L247), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L293), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L364), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L382)

1. Audio capture worker

- Audio thread creation in [AudioCapture.kt](stt/src/main/java/dev/barrycade/voicecore/stt/AudioCapture.kt#L103)
- Loop in [AudioCapture.kt](stt/src/main/java/dev/barrycade/voicecore/stt/AudioCapture.kt#L115)

1. Capture drain worker

- Drain thread creation in [CaptureManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/CaptureManager.kt#L218) and [CaptureManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/CaptureManager.kt#L271)

1. Processor worker

- Processor thread creation in [ProcessorController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/ProcessorController.kt#L64)
- Processing loop in [ProcessorController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/ProcessorController.kt#L79)

1. Minimal polling worker

- Manual mode polling thread in [SttModeController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttModeController.kt#L269)

1. Whisper executor worker

- Single-thread executor in [ModelManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/ModelManager.kt#L32)
- Inference submission in [ModelManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/ModelManager.kt#L222)

1. Native critical section

- JNI global mutex in [whisper_bridge.cpp](stt/src/main/cpp/whisper_bridge.cpp#L13)
- Lock use in [whisper_bridge.cpp](stt/src/main/cpp/whisper_bridge.cpp#L25), [whisper_bridge.cpp](stt/src/main/cpp/whisper_bridge.cpp#L85), [whisper_bridge.cpp](stt/src/main/cpp/whisper_bridge.cpp#L153)

1. Optional helper threads via SttThreadController

- Polling helper thread start in [SttThreadController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttThreadController.kt#L30)
- Drain helper thread start in [SttThreadController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttThreadController.kt#L53)

## 3) Shared mutable state inventory

## 3.1 SpeechToText

Shared state:

- isInferencing: [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L86)
- isInitialised: [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L92)
- runConfig: [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L98)
- config: [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L101)
- stopRequested volatile flag: [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L466)
- onUtteranceReadyCallback wiring: [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L232)

Guarding:

- Partial guard via stateLock: [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L89), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L263), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L296), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L365), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L383)
- Not fully guarded for all reads and writes

## 3.2 CaptureManager

Shared state:

- sessionBuffer mutable list: [CaptureManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/CaptureManager.kt#L69)
- draining volatile: [CaptureManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/CaptureManager.kt#L76)
- drainThread ref: [CaptureManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/CaptureManager.kt#L80)
- captureStarted: [CaptureManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/CaptureManager.kt#L86)
- sttActive volatile: [CaptureManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/CaptureManager.kt#L99)
- currentDrainMode: [CaptureManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/CaptureManager.kt#L107)

Guarding:

- No lock around sessionBuffer; relies on handoff protocol assumptions

## 3.3 AudioCapture

Shared state:

- isRunning volatile: [AudioCapture.kt](stt/src/main/java/dev/barrycade/voicecore/stt/AudioCapture.kt#L29)
- audioRecord and workerThread refs: [AudioCapture.kt](stt/src/main/java/dev/barrycade/voicecore/stt/AudioCapture.kt#L32), [AudioCapture.kt](stt/src/main/java/dev/barrycade/voicecore/stt/AudioCapture.kt#L33)
- frameQueue: [AudioCapture.kt](stt/src/main/java/dev/barrycade/voicecore/stt/AudioCapture.kt#L37)

Guarding:

- start and stop synchronized on stateLock: [AudioCapture.kt](stt/src/main/java/dev/barrycade/voicecore/stt/AudioCapture.kt#L52), [AudioCapture.kt](stt/src/main/java/dev/barrycade/voicecore/stt/AudioCapture.kt#L151)

## 3.4 ProcessorController and SttModeController

Shared state:

- Processor running and metrics: [ProcessorController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/ProcessorController.kt#L24), [ProcessorController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/ProcessorController.kt#L28), [ProcessorController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/ProcessorController.kt#L33), [ProcessorController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/ProcessorController.kt#L47)
- Mode controller refs and callback pointer: [SttModeController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttModeController.kt#L16), [SttModeController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttModeController.kt#L20), [SttModeController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttModeController.kt#L24), [SttModeController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttModeController.kt#L28), [SttModeController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttModeController.kt#L209)

Guarding:

- No explicit synchronization for mode-controller fields

## 3.5 ModelManager and callback dispatcher

Shared state:

- model readiness and failure flags: [ModelManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/ModelManager.kt#L42), [ModelManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/ModelManager.kt#L50)
- executorsShutdown non-volatile: [ModelManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/ModelManager.kt#L57)
- readyListener and forceWhisperLoadFailure non-volatile: [ModelManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/ModelManager.kt#L25), [ModelManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/ModelManager.kt#L62)
- callback listener refs non-volatile in dispatcher: [SttCallbackDispatcher.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttCallbackDispatcher.kt#L25), [SttCallbackDispatcher.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttCallbackDispatcher.kt#L28), [SttCallbackDispatcher.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttCallbackDispatcher.kt#L31), [SttCallbackDispatcher.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttCallbackDispatcher.kt#L34), [SttCallbackDispatcher.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttCallbackDispatcher.kt#L44)

## 3.6 Session timing and strategy/event shared state

Shared state:

- SttSessionController mutable timing fields, unsynchronized: [SttSessionController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttSessionController.kt#L18), [SttSessionController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttSessionController.kt#L22), [SttSessionController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttSessionController.kt#L26), [SttSessionController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttSessionController.kt#L32), [SttSessionController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttSessionController.kt#L36), [SttSessionController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttSessionController.kt#L40)
- Event flags are atomic and correctly synchronized: [SttEvents.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttEvents.kt#L26), [SttEvents.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttEvents.kt#L37)

## 3.7 SttThreadController helper state

Shared state:

- pollingThread and drainThread refs: [SttThreadController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttThreadController.kt#L17), [SttThreadController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttThreadController.kt#L21)

Guarding:

- references are volatile, but no stop signal contract is enforced for supplied runnables

## 4) Findings by category

## 4.1 Race conditions

Severity: High

1. Callback listener race in SttCallbackDispatcher

- Writes in setter methods and clearListeners can race with dispatch reads
- Evidence: [SttCallbackDispatcher.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttCallbackDispatcher.kt#L49), [SttCallbackDispatcher.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttCallbackDispatcher.kt#L54), [SttCallbackDispatcher.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttCallbackDispatcher.kt#L59), [SttCallbackDispatcher.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttCallbackDispatcher.kt#L64), [SttCallbackDispatcher.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttCallbackDispatcher.kt#L83), [SttCallbackDispatcher.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttCallbackDispatcher.kt#L93), [SttCallbackDispatcher.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttCallbackDispatcher.kt#L120)

1. Mode callback pointer race

- onUtteranceReadyCallback written on caller thread, read on processor thread without volatile or lock
- Evidence: [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L232), [SttModeController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttModeController.kt#L209), [SttModeController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttModeController.kt#L213)

1. Session timing field races

- SttSessionController values are mutable and unsynchronized, read and written across caller and worker callbacks
- Evidence: [SttSessionController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttSessionController.kt#L18), [SttSessionController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttSessionController.kt#L130), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L480), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L494)

1. SpeechToText mutable config state is only partially synchronized

- runConfig, config, isInitialised mutated outside a single global lock discipline
- Evidence: [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L98), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L101), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L92), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L168), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L182), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L247)

1. ModelManager cross-thread flags and listeners are not fully synchronized

- readyListener, forceWhisperLoadFailure, executorsShutdown are plain vars used across threads
- Evidence: [ModelManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/ModelManager.kt#L25), [ModelManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/ModelManager.kt#L62), [ModelManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/ModelManager.kt#L57), [ModelManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/ModelManager.kt#L73), [ModelManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/ModelManager.kt#L277)

Severity: Medium

1. CaptureManager sessionBuffer assumes single-writer handoff but has no explicit protection

- Handoff relies on sequencing rather than lock ownership
- Evidence: [CaptureManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/CaptureManager.kt#L69), [CaptureManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/CaptureManager.kt#L203), [CaptureManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/CaptureManager.kt#L367), [CaptureManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/CaptureManager.kt#L294)

## 4.2 Warm-start hazards

Severity: High

1. Session reset to READY before inference completion

- stopAndTranscribe submits async inference, then immediately transitions STOPPED to READY
- A new session can start while previous inference callback is still pending
- Evidence: [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L342), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L353)

1. No session generation gating for callbacks

- Inference result callback has no utterance or session epoch guard
- Old session results can arrive during new recording
- Evidence: [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L490), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L501)

Severity: Medium

1. isInferencing guard resets too early

- Guard resets in finally immediately after submit, not after inference completion callback
- Allows multiple in-flight queue submissions despite guard intent
- Evidence: [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L475), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L481), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L483)

1. initStt reconfiguration can race if callers violate serialization

- Methods explicitly documented as not thread-safe; concurrent init and start can produce inconsistent wiring
- Evidence: [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L38), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L39), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L210), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L232)

## 4.3 Thread-start ordering issues

Severity: Medium

1. isRunning becomes true after starting processor thread

- startProcessor starts controller thread before setting isRunning true
- handleUtteranceReady drops events when isRunning is false
- Evidence: [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L458), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L460), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L474)

1. Capture activation and drain handoff depend on fragile ordering

- beginPcmCapture enables draining state before drain thread decisions and processor handoff
- Multiple code paths mutate draining without a single owner lock
- Evidence: [CaptureManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/CaptureManager.kt#L139), [CaptureManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/CaptureManager.kt#L159), [CaptureManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/CaptureManager.kt#L352)

## 4.4 Unsafe access patterns

Severity: High

1. Lifecycle lock held around blocking thread joins and native unload call

- stateLock encloses stopController and capture shutdown path, which perform joins and can block
- increases lock hold time and amplifies contention risk
- Evidence: [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L296), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L306), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L382), [CaptureManager.kt](stt/src/main/java/dev/barrycade/voicecore/stt/CaptureManager.kt#L294), [AudioCapture.kt](stt/src/main/java/dev/barrycade/voicecore/stt/AudioCapture.kt#L165)

Severity: Medium

1. Claimed thread-safe classes still rely on single-thread use assumptions

- RmsSampler writes non-volatile counters without lock
- Vad writes some non-volatile fields read by other threads in strategy checks
- Evidence: [RmsSampler.kt](stt/src/main/java/dev/barrycade/voicecore/stt/RmsSampler.kt#L20), [RmsSampler.kt](stt/src/main/java/dev/barrycade/voicecore/stt/RmsSampler.kt#L45), [Vad.kt](stt/src/main/java/dev/barrycade/voicecore/stt/Vad.kt#L31), [Vad.kt](stt/src/main/java/dev/barrycade/voicecore/stt/Vad.kt#L65)

## 4.5 Missing synchronization

Severity: High

1. Public lifecycle methods in SpeechToText are not globally synchronized

- explicit documentation says caller must serialize externally
- internal locks cover only sections, not full API surface
- Evidence: [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L38), [SpeechToText.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SpeechToText.kt#L39)

1. Callback dispatcher listener fields have no volatile or lock discipline

- Evidence: [SttCallbackDispatcher.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttCallbackDispatcher.kt#L25), [SttCallbackDispatcher.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttCallbackDispatcher.kt#L28), [SttCallbackDispatcher.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttCallbackDispatcher.kt#L31), [SttCallbackDispatcher.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttCallbackDispatcher.kt#L34), [SttCallbackDispatcher.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttCallbackDispatcher.kt#L44)

1. Session timing fields are mutable and unsynchronized across threads

- Evidence: [SttSessionController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttSessionController.kt#L18), [SttSessionController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttSessionController.kt#L32), [SttSessionController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttSessionController.kt#L130)

## 4.6 Potential deadlocks

Severity: Medium

1. Self-join risk in ProcessorController.stop

- stop unconditionally joins workerThread without checking current thread
- if stop is ever invoked from workerThread path, join can block forever
- Evidence: [ProcessorController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/ProcessorController.kt#L147), [ProcessorController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/ProcessorController.kt#L149)

Severity: Low

1. No confirmed lock cycle between Kotlin and native mutex

- native mutex serializes load, transcribe, unload
- can cause long blocking on shutdown but no direct cycle found in reviewed paths
- Evidence: [whisper_bridge.cpp](stt/src/main/cpp/whisper_bridge.cpp#L25), [whisper_bridge.cpp](stt/src/main/cpp/whisper_bridge.cpp#L85), [whisper_bridge.cpp](stt/src/main/cpp/whisper_bridge.cpp#L153)

Severity: Low

1. Join-without-stop contract in SttThreadController can stall callers when used

- stopPolling and stopDrainThread join for fixed durations but do not enforce runnable cancellation
- this is not currently on the active SpeechToText runtime path, but remains a module-level hazard if reused
- Evidence: [SttThreadController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttThreadController.kt#L41), [SttThreadController.kt](stt/src/main/java/dev/barrycade/voicecore/stt/SttThreadController.kt#L64)

## 5) Recommended fixes

## 5.1 Priority 0, correctness and race elimination

1. Add a single lifecycle mutex in SpeechToText and use it consistently for all public lifecycle methods

- serialize setConfig, initStt, startSession, stopAndTranscribe, resetForNextSession, destroy end-to-end
- remove mixed partially-locked model

1. Add session generation token

- maintain AtomicLong sessionEpoch
- increment on each start and reset
- capture epoch in submitInferenceAndDispatch
- drop callback results whose epoch no longer matches current session

1. Make isInferencing represent real in-flight inference

- set true before submit
- clear only inside onResult callback and in error completion path

1. Fix start ordering in startProcessor

- set running state before launching controller thread, or gate callback acceptance on lifecycle state plus epoch, not only isRunning

1. Harden callback dispatcher synchronization

- mark listener refs volatile and snapshot local refs before invoking
- or guard setter/getter/dispatch with a dedicated lock

## 5.2 Priority 1, state ownership hardening

1. Make SttSessionController thread-safe

- either confine all access to lifecycle mutex
- or add internal lock and synchronize all reads/writes

1. Make ModelManager cross-thread vars safe

- executorsShutdown and forceWhisperLoadFailure should be volatile or atomic
- readyListener should be volatile or accessed under lock

1. Define strict ownership of CaptureManager.sessionBuffer

- enforce with lock around buffer writes and finalize/reset operations
- or prove confinement by code and add assertions

1. Protect mode-controller callback and references

- onUtteranceReadyCallback should be volatile
- mutable references read across threads should be volatile or lock-protected

## 5.3 Priority 2, deadlock and shutdown resilience

1. Prevent self-join in ProcessorController.stop and MinimalPollingController.stop

- if currentThread equals workerThread, skip join and just clear state

1. Reduce lock hold time under stateLock

- avoid calling long operations while lock is held
- move join-heavy and native unload steps outside critical section when safe

1. Add bounded cancellation hooks for worker loops

- prefer interrupt-aware stop signaling over sleep polling

## 6) Concrete patch plan

1. Concurrency contract

- Introduce one private lifecycle lock in SpeechToText
- wrap all lifecycle/config mutators with same lock

1. Callback and session safety

- Add sessionEpoch and attach to every inference submission
- Reject stale callbacks by epoch mismatch

1. Dispatcher safety

- Convert listener fields to volatile
- Read into locals before invoke
- ensure clearListeners uses same lock path

1. Manager and controller safety

- Add volatile to ModelManager cross-thread flags
- Add self-join guards in processor and minimal controllers
- mark SttModeController callback ref volatile

1. Capture safety

- Add lock around sessionBuffer mutations and drain handoff state, or migrate to thread-safe buffer strategy

1. Validation

- Add multi-thread stress tests for concurrent start, stop, reset, destroy
- Add tests for stale callback rejection across fast stop/start loops
- Add test for self-join prevention path

## 7) Residual risk after fixes

After implementing the above, expected residual concurrency risk is low and mostly limited to external callback behavior by app code.

Remaining guidance:

- keep user callbacks non-blocking
- avoid calling lifecycle methods from callbacks without explicit documented support
- keep native inference timeout and cancellation strategy monitored in production
