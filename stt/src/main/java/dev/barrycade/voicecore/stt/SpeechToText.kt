package dev.barrycade.voicecore.stt

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
    
/**
 * SpeechToText captures microphone audio, runs VAD-driven utterance detection,
 * and transcribes finalized utterances via Whisper. Transcription is triggered
 * by the UtteranceAccumulator (VAD-driven path) in both Manual and Streaming Modes.
 *
 * Manual Mode (default):
 *   stopAndTranscribe() forces accumulator finalization and returns that result.
 *   Lifecycle: READY → RECORDING → FINALISING → READY
 *
 * Streaming Mode (opt-in via start(streamingEnabled=true)):
 *   Utterances are segmented automatically using VAD and transcribed continuously.
 *   Stop is not required. Model stays loaded until destroy().
 *   Lifecycle: READY → RECORDING → RECORDING → ...
 *
 * Whisper lifecycle (loadModel / unloadModel) is serialized through a dedicated
 * single-thread executor to prevent race conditions between teardown and re-init.
 *
 * All failures produce structured [SttError] objects delivered via [SttErrorListener].
 * No silent failures. No swallowed exceptions. No fallback behaviour.
 */
class SpeechToText internal constructor(
    private val config: RuntimeSttConfig,
    private val modelPath: String
) {
    companion object {
        fun create(
            energyThreshold: Float,
            silencePaddingMs: Int,
            preRollMs: Int,
            maxUtteranceLengthMs: Int,
            stableChunkSizeMs: Int,
            motionModeEnergyThreshold: Float,
            motionModeSilencePaddingMs: Int,
            modelPath: String
        ): SpeechToText {
            val config = RuntimeSttConfig(
                energyThreshold = energyThreshold,
                silencePaddingMs = silencePaddingMs,
                preRollMs = preRollMs,
                maxUtteranceLengthMs = maxUtteranceLengthMs,
                stableChunkSizeMs = stableChunkSizeMs,
                motionMode = MotionModeConfig(
                    energyThreshold = motionModeEnergyThreshold,
                    silencePaddingMs = motionModeSilencePaddingMs
                )
            )
            return SpeechToText(config, modelPath)
        }
    }

    // ── Testing hooks (internal) ─────────────────────────────────────────
    /**
     * When true, AudioCapture init will fail with AUDIO_INIT_FAILED.
     */
    internal var forceAudioInitFailure: Boolean = false

    /**
     * When true, Whisper model load will fail with WHISPER_MODEL_LOAD_FAILED.
     */
    internal var forceWhisperLoadFailure: Boolean = false

    /**
     * When true, UtteranceAccumulator will simulate max-utterance timeout.
     */
    internal var forceTimeout: Boolean = false

    private var onResult: ((String) -> Unit)? = null
    private var onResultWithTiming: ((text: String, timing: SttTimingSnapshot?) -> Unit)? = null
    private var onError: ((Throwable) -> Unit)? = null
    private var sttErrorListener: SttErrorListener? = null
    private val debugVad = true

    private val isRunning = AtomicBoolean(false)
    private val isInferencing = AtomicBoolean(false)
    private val stateLock = Any()

    private val lifecycleManager = SttLifecycleManager()

    init {
        // Fail-fast: config validation
        try {
            config.validate()
        } catch (e: IllegalArgumentException) {
            SttLogger.configE("validation failed: ${e.message}")
            val error = SttError(
                category = SttErrorCategory.UNKNOWN,
                code = SttErrorCode.INTERNAL_EXCEPTION,
                message = "Configuration validation failed: ${e.message}",
                cause = e,
                context = mapOf("config" to config.toString(), "reason" to (e.message ?: ""))
            )
            sttErrorListener?.onSttError(error)
            throw e
        }
        SttLogger.config("Validated STT config: $config")
    }

    /** Tracks whether Whisper warm-up has been performed in this session. */
    private var warmupPerformed: Boolean = false

    /**
     * Cancellation flag for all Whisper executor tasks.
     * Set true by any Stop path. Tasks check this as their first action.
     */
    @Volatile
    private var whisperCancelled = false

    /**
     * Stop-requested flag for deterministic freeze of PCM/VAD/accumulator.
     *
     * Semantics:
     * - false => normal recording. PCM ingestion, VAD processing, and accumulator
     *   updates proceed normally.
     * - true  => Stop has been requested. No new PCM/VAD/accumulator work may
     *   proceed. Frames are dropped, VAD is skipped, accumulator is frozen.
     *
     * Reset to false when transitioning from READY -> RECORDING in start().
     * Set to true at the very beginning of stopAndTranscribe().
     */
    @Volatile
    private var stopRequested: Boolean = false

    /**
     * Streaming mode flag. When true, utterances are segmented automatically
     * using VAD and transcribed continuously without requiring Stop.
     * When false, Manual Mode is used (current behaviour).
     *
     * Semantics:
     * - false → Manual Mode (stopAndTranscribe() required)
     * - true  → Streaming Mode (continuous multi-utterance)
     *
     * Reset to false in resetInternalState().
     */
    private var streamingEnabled: Boolean = false

    private var audioCapture: AudioCapture? = null
    private var sttProcessor: SttProcessor? = null

    /**
     * Dedicated single-thread executor for Whisper lifecycle operations.
     * All loadModel() and unloadModel() calls are serialized through this executor.
     * unloadModel() must fully complete before any subsequent loadModel() begins.
     * The executor is shut down via shutdownExecutors() when the engine is destroyed
     * or a fatal error occurs.
     */
    private val whisperExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Idempotency guard for executor shutdown.
     * Once true, shutdownExecutors() becomes a no-op.
     */
    private var executorsShutdown: Boolean = false

    private var lastTranscribedText: String? = null

    // ── Timing accumulator ───────────────────────────────────────────────
    private var timingPcmStartMs: Long = 0L
    private var timingPcmTotalMs: Long = 0L
    private var timingVadActiveMs: Long = 0L
    private var timingUtteranceStartMs: Long = 0L
    private var timingTotalMs: Long = 0L

    private fun resetTiming() {
        timingPcmStartMs = 0L
        timingPcmTotalMs = 0L
        timingVadActiveMs = 0L
        timingUtteranceStartMs = 0L
        timingTotalMs = 0L
    }

    fun setOnResultListener(listener: (String) -> Unit) {
        onResult = listener
    }

    /**
     * Register a result listener that also receives the [SttTimingSnapshot] for the utterance.
     * The timing field is null when timing was not captured (e.g. during initial setup).
     */
    fun setOnResultWithTimingListener(listener: (text: String, timing: SttTimingSnapshot?) -> Unit) {
        onResultWithTiming = listener
    }

    /**
     * Register an internal listener for timing diagnostics after each inference.
     * Not part of the public API. The demo app accesses this via the internal callback.
     */
    internal var onTimingCallback: ((SttTiming) -> Unit)? = null

    /**
     * Register a listener for timing diagnostics after each inference.
     * [SttTiming] is public for consumption but remains internal by design —
     * this method provides the bridge for the demo app.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    var onTimingListener: ((pcmMs: Long, vadActiveMs: Long, whisperMs: Long, totalMs: Long) -> Unit)? = null

    fun setOnErrorListener(listener: (Throwable) -> Unit) {
        onError = listener
    }

    /**
     * Register a structured error listener for [SttError] events.
     * Every failure in the STT subsystem is delivered here.
     */
    fun setSttErrorListener(listener: SttErrorListener) {
        sttErrorListener = listener
    }

    /**
     * Configure debug/test failure injection flags.
     * Used by the demo app to force deterministic failures for testing.
     *
     * @param forceAudioInitFailure When true, AudioCapture init will fail with AUDIO_INIT_FAILED.
     * @param forceWhisperLoadFailure When true, Whisper model load will fail with WHISPER_MODEL_LOAD_FAILED.
     * @param forceTimeout When true, UtteranceAccumulator simulates max-utterance timeout.
     */
    fun setDebugOptions(
        forceAudioInitFailure: Boolean = false,
        forceWhisperLoadFailure: Boolean = false,
        forceTimeout: Boolean = false
    ) {
        this.forceAudioInitFailure = forceAudioInitFailure
        this.forceWhisperLoadFailure = forceWhisperLoadFailure
        this.forceTimeout = forceTimeout
    }

    fun start() {
        start(streamingEnabled = false)
    }

    /**
     * Start recording with the specified mode.
     *
     * @param streamingEnabled When true, uses Streaming Mode (continuous multi-utterance).
     *                         When false, uses Manual Mode (requires stopAndTranscribe()).
     */
    fun start(streamingEnabled: Boolean) {
        synchronized(stateLock) {
            // Fail-fast: verify STT is not already running
            if (isRunning.get()) return

            try {
                resetInternalState()
                this.streamingEnabled = streamingEnabled
                if (streamingEnabled) {
                    SttLogger.pcm("[STREAM] streamingEnabled=true")
                }
                resetTiming()
                dumpConfig()

                // ── Testing hook: forceWhisperLoadFailure ─────────────────
                if (forceWhisperLoadFailure) {
                    SttLogger.error("forcedFailure: MODEL_LOAD_FAILED")
                    val error = SttError(
                        category = SttErrorCategory.UNKNOWN,
                        code = SttErrorCode.MODEL_LOAD_FAILED,
                        message = "Forced test failure: Whisper model load",
                        context = mapOf("forcedFailure" to "forceWhisperLoadFailure")
                    )
                    sttErrorListener?.onSttError(error)
                    shutdownExecutors()
                    dispatchError(RuntimeException("Forced test failure: Whisper model load"))
                    return
                }

                // ── Phase A: Model load (synchronous) ─────────────────────────────
                // Model load is allowed to block the UI thread. It runs on the
                // dedicated whisperExecutor and is awaited with a 30-second timeout.
                // Warm-up is NOT inside this task — it is submitted separately below.
                val loadFuture = whisperExecutor.submit<java.lang.Void> {
                    try {
                        SttLogger.whisper("loadModel: $modelPath")
                        NativeSession.loadModel(modelPath, debug = true)
                    } catch (t: Throwable) {
                        SttLogger.whisperE("loadModel failed: ${t.message}")
                        val error = SttError(
                            category = SttErrorCategory.UNKNOWN,
                            code = SttErrorCode.MODEL_LOAD_FAILED,
                            message = "Failed to load Whisper model: ${t.message}",
                            cause = t,
                            context = mapOf("modelPath" to modelPath, "exception" to t::class.java.simpleName)
                        )
                        sttErrorListener?.onSttError(error)
                        throw RuntimeException("Whisper model load failed", t)
                    }
                    null
                }
                try {
                    loadFuture.get(30, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    SttLogger.whisperE("model load timed out or failed: ${e.message}")
                    val error = SttError(
                        category = SttErrorCategory.UNKNOWN,
                        code = SttErrorCode.MODEL_LOAD_FAILED,
                        message = "Whisper model load failed or timed out: ${e.message}",
                        cause = e,
                        context = mapOf("modelPath" to modelPath, "exception" to e::class.java.simpleName)
                    )
                    sttErrorListener?.onSttError(error)
                    shutdownExecutors()
                    dispatchError(e)
                    return
                }

                // ── READY is entered immediately after model load ───────────
                // Warm-up has NOT happened yet. It runs asynchronously below.
                if (!transitionTo(SttLifecycleState.READY)) return

                // ── Phase B: Warm-up (asynchronous, after READY) ────────────
                // Warm-up runs on whisperExecutor in the background.
                // It is NOT awaited. It does NOT block READY or RECORDING.
                // PCM frames flow immediately because AudioCapture starts now.
                whisperExecutor.submit {
                    performWarmup()
                }

                isRunning.set(true)

                // ── Testing hook: forceAudioInitFailure ───────────────────
                if (forceAudioInitFailure) {
                    SttLogger.error("forcedFailure: CAPTURE_FAILED")
                    val error = SttError(
                        category = SttErrorCategory.UNKNOWN,
                        code = SttErrorCode.CAPTURE_FAILED,
                        message = "Forced test failure: AudioCapture init",
                        context = mapOf("forcedFailure" to "forceAudioInitFailure")
                    )
                    sttErrorListener?.onSttError(error)
                    isRunning.set(false)
                    stopInternal()
                    shutdownExecutors()
                    dispatchError(RuntimeException("Forced test failure: AudioCapture init"))
                    return
                }

                // Fail-fast: AudioCapture creation
                val capture = try {
                    AudioCapture(
                        sampleRate = 16000,
                        requestedBufferSizeInBytes = 32000
                    ).apply {
                        start()
                    }
                } catch (e: Exception) {
                    SttLogger.pcmE("AudioCapture start failed: ${e.message}")
                    val error = SttError(
                        category = SttErrorCategory.UNKNOWN,
                        code = SttErrorCode.CAPTURE_FAILED,
                        message = "Audio capture failed to start: ${e.message}",
                        cause = e,
                        context = mapOf("exception" to e::class.java.simpleName, "detail" to (e.message ?: ""))
                    )
                    sttErrorListener?.onSttError(error)
                    isRunning.set(false)
                    stopInternal()
                    shutdownExecutors()
                    dispatchError(e)
                    return
                }
                audioCapture = capture
                if (!transitionTo(SttLifecycleState.RECORDING)) {
                    capture.stop()
                    isRunning.set(false)
                    stopInternal()
                    shutdownExecutors()
                    return
                }

                // ── Timing: PCM capture start ─────────────────────────────
                timingPcmStartMs = System.currentTimeMillis()

                val accumulator = UtteranceAccumulator(config).apply {
                    sttErrorListener = this@SpeechToText.sttErrorListener
                    // ── Testing hook: forceTimeout ────────────────────
                    if (this@SpeechToText.forceTimeout) {
                        this.forceTimeout = true
                    }
                    // ── Reset per-utterance VAD timing on speech start ─
                    onSpeechStart = { sttProcessor?.resetVadActiveMs() }
                }

                sttProcessor = SttProcessor(
                    audioCapture = capture,
                    vad = Vad(config).apply {
                        debugLogging = config.debugLoggingEnabled
                    },
                    utteranceAccumulator = accumulator,
                    listener = object : UtteranceListener {
                        override fun onUtteranceReady(pcm: FloatArray) {
                            // Guard: no inference after stop, error, or teardown
                            if (!isRunning.get()) return

                            val isStreaming = this@SpeechToText.streamingEnabled

                            // In streaming mode, log utterance start/end
                            if (isStreaming) {
                                SttLogger.pcm("[STREAM] utterance end")
                                SttLogger.pcm("[STREAM] inference triggered")
                            }

                            // Guard: only one inference at a time
                            if (!isInferencing.compareAndSet(false, true)) {
                                SttLogger.whisperW("inference skipped: another inference already in progress")
                                return
                            }

                            try {
                                // Remains in RECORDING during inference (FINALISING is for user-initiated stop)
                                // No state transition — behavioural compatibility with VAD-driven pipeline.

                                if (debugVad) SttLogger.pcmD("Final PCM size=${pcm.size}")

                                // In streaming mode, log utterance start before inference
                                if (isStreaming) {
                                    SttLogger.pcm("[STREAM] utterance start")
                                }

                                val samples = pcm.toShortArray()
                                val inferenceStartMs = System.currentTimeMillis()

                                if (debugVad) SttLogger.whisperD("inferenceStart: pcmMs=${pcm.size * 1000 / 16000}")

                                val text = try {
                                    val result = NativeSession.transcribe(samples).trim()
                                    val whisperMs = System.currentTimeMillis() - inferenceStartMs
                                    SttLogger.whisper("inferenceEnd: timeMs=$whisperMs, text=\"$result\"")
                                    result
                                } catch (t: Throwable) {
                                    SttLogger.whisperE("inference failed: ${t.message}")
                                    val error = SttError(
                                        category = SttErrorCategory.UNKNOWN,
                                        code = SttErrorCode.INFERENCE_FAILED,
                                        message = "Whisper inference failed: ${t.message}",
                                        cause = t,
                                        context = mapOf("pcmSamples" to samples.size, "exception" to t::class.java.simpleName)
                                    )
                                    sttErrorListener?.onSttError(error)
                                    ""
                                }

                                // Remains in RECORDING — only user-initiated stop triggers FINALISING.

                                if (text.isNotBlank()) {
                                    // ── Timing: build snapshot ──────────────
                                    val whisperMs = System.currentTimeMillis() - inferenceStartMs
                                    val currentVadMs = sttProcessor?.vadActiveMs ?: 0L
                                    val currentUtteranceMs = (sttProcessor?.lastUtteranceDurationMs ?: 0).toLong()
                                    val pipelineMs = System.currentTimeMillis() - timingUtteranceStartMs
                                    val timingSnapshot = SttTiming(
                                        vadActiveMs = currentVadMs.toInt(),
                                        utteranceMs = currentUtteranceMs.toInt(),
                                        inferenceMs = whisperMs.toInt(),
                                        totalMs = pipelineMs.toInt()
                                    )
                                    SttLogger.pcm("[DIAG] timing: $timingSnapshot")

                                    lastTranscribedText = text
                                    onTimingCallback?.invoke(timingSnapshot)
                                    onTimingListener?.invoke(timingPcmTotalMs, currentVadMs, whisperMs, pipelineMs)
                                    dispatchResult(text, null)
                                }

                                // In streaming mode, reset the accumulator for the next utterance
                                if (isStreaming) {
                                    accumulator.resetForNextUtterance()
                                }
                            } finally {
                                isInferencing.set(false)
                            }
                        }
                    },
                    calibrationLogger = if (debugVad) VadCalibrationLogger() else null,
                    debugLogging = config.debugLoggingEnabled,
                    stopRequestedRef = { this@SpeechToText.stopRequested }
                ).apply { start() }

                // ── Timing: utterance start marker ────────────────────────
                timingUtteranceStartMs = System.currentTimeMillis()

                SttLogger.lifecycle("VAD-driven transcription pipeline started")
            } catch (t: Throwable) {
                SttLogger.error("code=INTERNAL_EXCEPTION, message=\"${t.message}\"")
                val error = SttError(
                    category = SttErrorCategory.UNKNOWN,
                    code = SttErrorCode.INTERNAL_EXCEPTION,
                    message = "Unhandled error during start: ${t.message}",
                    cause = t,
                    context = mapOf("exception" to t::class.java.simpleName)
                )
                sttErrorListener?.onSttError(error)
                stopInternal()
                shutdownExecutors()
                dispatchError(t)
            }
        }
    }

    fun stopAndTranscribe() {
        synchronized(stateLock) {
            // Streaming Mode does not support stopAndTranscribe
            if (streamingEnabled) {
                SttLogger.pcm("[STREAM] stopAndTranscribe called in Streaming Mode — no-op")
                return
            }

            if (!isRunning.get()) return
            isRunning.set(false)

            try {
                // Step 1: Transition RECORDING → FINALISING
                if (!transitionTo(SttLifecycleState.FINALISING)) return

                // Step 2: Set stopRequested to freeze PCM/VAD/accumulator
                stopRequested = true
                SttLogger.pcm("[STOP] stopRequested=true")

                // Step 3: Stop the processor thread (freezes PCM/VAD/accumulator)
                sttProcessor?.stop()
                val processorVadMs = sttProcessor?.vadActiveMs ?: 0L
                val processorUtteranceMs = (sttProcessor?.lastUtteranceDurationMs ?: 0).toLong()

                // Step 4: Finalise PCM exactly once
                val pcm = sttProcessor?.finaliseUtterance()
                sttProcessor = null

                // Step 5: Stop audio capture
                audioCapture?.stop()
                audioCapture = null

                // Step 6: Whisper inference
                val captureDurationMs = timingPcmStartMs.let { if (it > 0) System.currentTimeMillis() - it else 0L }

                if (pcm != null) {
                    SttLogger.whisper("[WHISPER] stop inference started")
                    val inferenceStartMs = System.currentTimeMillis()
                    val samples = pcm.toShortArray()
                    val text = NativeSession.transcribe(samples).trim()
                    val whisperMs = System.currentTimeMillis() - inferenceStartMs

                    if (text.isNotBlank()) {
                        val pipelineMs = System.currentTimeMillis() - timingUtteranceStartMs
                        val timingSnapshot = SttTiming(
                            vadActiveMs = processorVadMs.toInt(),
                            utteranceMs = processorUtteranceMs.toInt(),
                            inferenceMs = whisperMs.toInt(),
                            totalMs = pipelineMs.toInt()
                        )
                        SttLogger.pcm("[DIAG] timing: $timingSnapshot")

                        lastTranscribedText = text
                        onTimingCallback?.invoke(timingSnapshot)
                        onTimingListener?.invoke(captureDurationMs, processorVadMs, whisperMs, pipelineMs)
                        dispatchResult(text, null)
                    }
                } else {
                    SttLogger.pcmW("no pcm available from accumulator")
                }

                // Step 7: Model stays loaded — no unload between Manual Mode utterances.
                //         Unload only on destroy() or session end.

                // Step 8: Transition FINALISING → READY
                transitionTo(SttLifecycleState.READY)
            } catch (t: Throwable) {
                dispatchError(t)
            } finally {
                stopInternal()
            }
        }
    }

    fun stop() = stopAndTranscribe()

    // ── State machine ────────────────────────────────────────────────────

    /**
     * Validate and apply a lifecycle state transition.
     *
     * Legal transitions:
     *   UNINITIALISED → READY
     *   READY         → RECORDING
     *   RECORDING     → FINALISING
     *   FINALISING    → READY
     *
     * @return true if the transition was applied, false if it was illegal
     *         (the pipeline is stopped and an error is emitted).
     */
    private fun transitionTo(newState: SttLifecycleState): Boolean {
        val from = lifecycleManager.currentState

        // No-op: already in target state.
        if (from == newState) return true

        val valid = when (from) {
            is SttLifecycleState.UNINITIALISED -> newState is SttLifecycleState.READY
            is SttLifecycleState.READY -> newState is SttLifecycleState.RECORDING
            is SttLifecycleState.RECORDING -> newState is SttLifecycleState.FINALISING
            is SttLifecycleState.FINALISING -> newState is SttLifecycleState.READY
        }

        if (valid) {
            val fromName = from.javaClass.simpleName
            val toName = newState.javaClass.simpleName
            SttLogger.lifecycle("state: $fromName → $toName")
            lifecycleManager.currentState = newState
            return true
        }

        val fromName = from.javaClass.simpleName
        val toName = newState.javaClass.simpleName
        SttLogger.lifecycleE("illegal transition: $fromName → $toName")
        val error = SttError(
            category = SttErrorCategory.UNKNOWN,
            code = SttErrorCode.PIPELINE_ILLEGAL_STATE,
            message = "Illegal lifecycle transition: $fromName → $toName",
            context = mapOf("from" to fromName, "to" to toName)
        )
        sttErrorListener?.onSttError(error)
        onError?.let { listener ->
            listener(RuntimeException("Illegal lifecycle transition: $fromName → $toName"))
        }
        // Stop the pipeline cleanly on illegal transition
        stopInternal()
        return false
    }

    // ── Private helpers ─────────────────────────────────────────────────

    private fun dispatchResult(text: String, timing: SttTimingSnapshot?) {
        lastTranscribedText = text
        onResultWithTiming?.invoke(text, timing)
        onResult?.invoke(text)
    }

    private fun resetInternalState() {
        lastTranscribedText = null
        whisperCancelled = false
        stopRequested = false
        streamingEnabled = false
    }

    private fun stopInternal() {
        whisperCancelled = true
        isRunning.set(false)
        isInferencing.set(false)
        sttProcessor?.stop()
        sttProcessor = null
        audioCapture?.stop()
        audioCapture = null
    }

    /**
     * Shuts down all worker executors deterministically.
     *
     * Order of operations:
     *   1. Stop audio capture cleanly
     *   2. Stop PCM/VAD processing cleanly
     *   3. Cancel Whisper executor tasks
     *   4. Unload Whisper model
     *   5. Call shutdown() on each executor
     *   6. Wait for termination with a bounded 5-second timeout
     *   7. Log one line per executor with status
     *
     * Idempotent: multiple calls are safe. The executorsShutdown flag
     * prevents duplicate shutdown and duplicate logging.
     *
     * Status values:
     *   TERMINATED  — executor shut down within timeout
     *   TIMEOUT     — executor did not terminate within timeout
     *   INTERRUPTED — current thread was interrupted while waiting
     */
    private fun shutdownExecutors() {
        if (executorsShutdown) return
        executorsShutdown = true

        // Step 1: Stop audio capture cleanly
        audioCapture?.stop()
        audioCapture = null

        // Step 2: Stop PCM/VAD processing cleanly
        sttProcessor?.stop()
        sttProcessor = null

        // Step 3: Cancel whisper tasks
        whisperCancelled = true
        isRunning.set(false)
        isInferencing.set(false)

        // Step 4: Unload model before shutting down executor
        NativeSession.unloadModel()

        // Step 5-7: Shutdown whisper executor with bounded timeout
        whisperExecutor.shutdown()
        try {
            val terminated = whisperExecutor.awaitTermination(5, TimeUnit.SECONDS)
            if (terminated) {
                SttLogger.pcm("[EXECUTOR] shutdown: whisperExecutor status=TERMINATED")
            } else {
                whisperExecutor.shutdownNow()
                SttLogger.pcm("[EXECUTOR] shutdown: whisperExecutor status=TIMEOUT")
            }
        } catch (e: InterruptedException) {
            whisperExecutor.shutdownNow()
            Thread.currentThread().interrupt()
            SttLogger.pcm("[EXECUTOR] shutdown: whisperExecutor status=INTERRUPTED")
        }
    }

    /**
     * Run Whisper warm-up once per model load.
     * Must only be called via whisperExecutor.submit { performWarmup() }.
     * Must never run on the main thread or inside any lifecycle transition.
     * Must never be inside a Future that is .get()-ed.
     * Guarded by warmupPerformed flag: runs exactly once until model is unloaded.
     */
    private fun performWarmup() {
        if (warmupPerformed) return
        warmupPerformed = true

        val warmupStartMs = System.currentTimeMillis()
        try {
            NativeSession.warmup()
            if (whisperCancelled) return
            val warmupMs = System.currentTimeMillis() - warmupStartMs
            SttLogger.whisper("warmUpMs=$warmupMs")
        } catch (t: Throwable) {
            if (whisperCancelled) return
            SttLogger.whisperE("warmup failed: ${t.message}")
            val error = SttError(
                category = SttErrorCategory.UNKNOWN,
                code = SttErrorCode.INFERENCE_FAILED,
                message = "Whisper warm-up failed: ${t.message}",
                cause = t,
                context = mapOf("exception" to t::class.java.simpleName)
            )
            sttErrorListener?.onSttError(error)
        }
    }

    /**
     * Releases all resources. Must be called when the STT engine is no longer needed.
     * Shuts down the Whisper lifecycle executor.
     *
     * Destroy behaviour:
     *   - stop PCM capture
     *   - stop inference
     *   - release AudioRecord
     *   - unload Whisper model
     *   - clear buffers
     *   - reset pipeline state to UNINITIALISED
     */
    fun destroy() {
        synchronized(stateLock) {
            stopInternal()
            NativeSession.unloadModel()
            lifecycleManager.currentState = SttLifecycleState.UNINITIALISED
        }
        shutdownExecutors()
        SttLogger.lifecycle("destroy: resources released, state=UNINITIALISED")
    }

    private fun dispatchError(t: Throwable) {
        onError?.invoke(t)

        // Build partial timing snapshot if available
        val vadMs = sttProcessor?.vadActiveMs ?: 0L
        val utterMs = (sttProcessor?.lastUtteranceDurationMs ?: 0).toLong()
        val totalMs = if (timingUtteranceStartMs > 0) System.currentTimeMillis() - timingUtteranceStartMs else 0L
        val partialTiming: Map<String, Long>? = if (vadMs > 0 || utterMs > 0) {
            mapOf(
                "vadActiveMs" to vadMs,
                "utteranceDurationMs" to utterMs,
                "silencePaddingMs" to config.silencePaddingMs.toLong(),
                "preRollMs" to config.preRollMs.toLong(),
                "totalPipelineMs" to totalMs
            )
        } else {
            null
        }

        val sampler = sttProcessor?.rmsSampler

        val timingCtx = mutableMapOf<String, Any?>(
            "exception" to t::class.java.simpleName
        )
        if (timingPcmTotalMs > 0) timingCtx["pcmMs"] = timingPcmTotalMs
        if (totalMs > 0) timingCtx["totalMs"] = totalMs

        val error = SttError(
            category = SttErrorCategory.UNKNOWN,
            code = SttErrorCode.INTERNAL_EXCEPTION,
            message = t.message ?: "Unknown error",
            cause = t,
            timingSnapshotMs = partialTiming,
            vadConfidence = sttProcessor?.vadConfidence,
            avgRms = sampler?.avgRms,
            peakRms = sampler?.peakRms,
            noiseFloorRms = sampler?.noiseFloorRms,
            context = timingCtx
        )
        sttErrorListener?.onSttError(error)
    }

    fun dumpConfig() {
        SttLogger.config("Active config: $config")
    }

    private fun FloatArray.toShortArray(): ShortArray {
        val shorts = ShortArray(size)
        for (index in indices) {
            val clamped = kotlin.math.max(-1.0f, kotlin.math.min(1.0f, this[index]))
            shorts[index] = (clamped * Short.MAX_VALUE).toInt().toShort()
        }
        return shorts
    }

    /**
     * NativeSession encapsulates all Whisper JNI calls. Load/unload are performed
     * on the dedicated whisperExecutor to ensure deterministic lifecycle sequencing.
     * Transcribe is thread-safe (C++ mutex in whisper_bridge.cpp).
     */
    private object NativeSession {
        /** Tiny PCM buffer for warm-up and health check (200ms of silence at 16kHz). */
        private val warmupPcm: ShortArray = ShortArray(3200)

        fun loadModel(path: String, debug: Boolean) {
            if (debug) SttLogger.whisperD("Loading model: $path")
            WhisperBridge.loadModel(path)
        }

        fun transcribe(pcm: ShortArray): String = WhisperBridge.transcribe(pcm)

        fun unloadModel() {
            SttLogger.whisperD("Unloading model")
            WhisperBridge.unloadModel()
        }

        /**
         * Run a lightweight warm-up inference. Synchronous, low-overhead.
         * Must only be called once per session after model load.
         */
        fun warmup() {
            WhisperBridge.transcribe(warmupPcm)
        }
    }
}
