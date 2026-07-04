package dev.barrycade.voicecore.stt

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SpeechToText captures microphone audio, runs VAD-driven utterance detection,
 * and transcribes finalized utterances via Whisper. Transcription is triggered
 * only by the UtteranceAccumulator (VAD-driven path). stopAndTranscribe() forces
 * accumulator finalization and returns that result — no raw PCM fallback path.
 *
 * Whisper lifecycle (loadModel / unloadModel) is serialized through a dedicated
 * single-thread executor to prevent race conditions between teardown and re-init.
 *
 * Lifecycle state machine:
 *   UNINITIALISED → READY → RECORDING → INFERENCING → RECORDING → ... → DESTROYED
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
    private var onError: ((Throwable) -> Unit)? = null
    private var sttErrorListener: SttErrorListener? = null
    private val debugVad = true

    private val isRunning = AtomicBoolean(false)
    private val stateLock = Any()

    private val lifecycleManager = SttLifecycleManager(
        errorListener = SttErrorListener { error ->
            sttErrorListener?.onSttError(error)
            onError?.let { listener ->
                listener(RuntimeException("STT error: ${error.code} - ${error.message}"))
            }
        }
    )

    init {
        // Fail-fast: config validation
        try {
            config.validate()
        } catch (e: IllegalArgumentException) {
            SttLogger.configE("validation failed: ${e.message}")
            val error = SttError(
                code = SttErrorCode.CONFIG_INVALID,
                message = "Configuration validation failed: ${e.message}",
                context = mapOf("config" to config.toString(), "reason" to (e.message ?: ""))
            )
            sttErrorListener?.onSttError(error)
            throw e
        }
        SttLogger.config("Validated STT config: $config")
    }

    private var audioCapture: AudioCapture? = null
    private var sttProcessor: SttProcessor? = null

    /**
     * Dedicated single-thread executor for Whisper lifecycle operations.
     * All loadModel() and unloadModel() calls are serialized through this executor.
     * unloadModel() must fully complete before any subsequent loadModel() begins.
     * The executor is shut down only when the entire STT engine is destroyed.
     */
    private val whisperExecutor: ExecutorService = Executors.newSingleThreadExecutor()
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
        synchronized(stateLock) {
            // Fail-fast: lifecycle validation
            try {
                lifecycleManager.transitionTo(SttLifecycleState.READY)
            } catch (e: IllegalStateException) {
                // Lifecycle violation already reported via error listener in transitionTo
                return
            }

            if (isRunning.get()) return

            try {
                resetInternalState()
                resetTiming()
                dumpConfig()

                // ── Testing hook: forceWhisperLoadFailure ─────────────────
                if (forceWhisperLoadFailure) {
                    SttLogger.error("forcedFailure: WHISPER_MODEL_LOAD_FAILED")
                    val error = SttError(
                        code = SttErrorCode.WHISPER_MODEL_LOAD_FAILED,
                        message = "Forced test failure: Whisper model load",
                        context = mapOf("forcedFailure" to "forceWhisperLoadFailure")
                    )
                    sttErrorListener?.onSttError(error)
                    dispatchError(RuntimeException("Forced test failure: Whisper model load"))
                    return
                }

                // Fail-fast: Load model on the dedicated Whisper executor
                val loadFuture = whisperExecutor.submit<java.lang.Void> {
                    try {
                        SttLogger.whisper("loadModel: $modelPath")
                        NativeSession.loadModel(modelPath, debug = true)
                    } catch (t: Throwable) {
                        SttLogger.whisperE("loadModel failed: ${t.message}")
                        val error = SttError(
                            code = SttErrorCode.WHISPER_MODEL_LOAD_FAILED,
                            message = "Failed to load Whisper model: ${t.message}",
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
                        code = SttErrorCode.WHISPER_MODEL_LOAD_FAILED,
                        message = "Whisper model load failed or timed out: ${e.message}",
                        context = mapOf("modelPath" to modelPath, "exception" to e::class.java.simpleName)
                    )
                    sttErrorListener?.onSttError(error)
                    dispatchError(e)
                    return
                }

                isRunning.set(true)

                // ── Testing hook: forceAudioInitFailure ───────────────────
                if (forceAudioInitFailure) {
                    SttLogger.error("forcedFailure: AUDIO_INIT_FAILED")
                    val error = SttError(
                        code = SttErrorCode.AUDIO_INIT_FAILED,
                        message = "Forced test failure: AudioCapture init",
                        context = mapOf("forcedFailure" to "forceAudioInitFailure")
                    )
                    sttErrorListener?.onSttError(error)
                    isRunning.set(false)
                    stopInternal()
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
                        code = SttErrorCode.AUDIO_RECORD_FAILED,
                        message = "Audio capture failed to start: ${e.message}",
                        context = mapOf("exception" to e::class.java.simpleName, "detail" to (e.message ?: ""))
                    )
                    sttErrorListener?.onSttError(error)
                    isRunning.set(false)
                    stopInternal()
                    dispatchError(e)
                    return
                }
                audioCapture = capture
                lifecycleManager.transitionTo(SttLifecycleState.RECORDING)

                // ── Timing: PCM capture start ─────────────────────────────
                timingPcmStartMs = System.currentTimeMillis()

                sttProcessor = SttProcessor(
                    audioCapture = capture,
                    vad = Vad(config).apply {
                        debugLogging = config.debugLoggingEnabled
                    },
                    utteranceAccumulator = UtteranceAccumulator(config).apply {
                        sttErrorListener = this@SpeechToText.sttErrorListener
                        // ── Testing hook: forceTimeout ────────────────────
                        if (this@SpeechToText.forceTimeout) {
                            this.forceTimeout = true
                        }
                    },
                    listener = object : UtteranceListener {
                        override fun onUtteranceReady(pcm: FloatArray) {
                            // Transition: RECORDING → INFERENCING
                            try {
                                lifecycleManager.transitionTo(SttLifecycleState.INFERENCING)
                            } catch (_: IllegalStateException) {
                                return
                            }

                            if (debugVad) SttLogger.pcmD("Final PCM size=${pcm.size}")
                            val samples = pcm.toShortArray()
                            val inferenceStartMs = System.currentTimeMillis()
                            if (debugVad) SttLogger.whisperD("inferenceStart: pcmMs=${pcm.size * 1000 / 16000}")

                            val text = try {
                                val result = NativeSession.transcribe(samples)?.trim().orEmpty()
                                val whisperMs = System.currentTimeMillis() - inferenceStartMs
                                SttLogger.whisper("inferenceEnd: timeMs=$whisperMs, text=\"$result\"")
                                result
                            } catch (t: Throwable) {
                                SttLogger.whisperE("inference failed: ${t.message}")
                                val error = SttError(
                                    code = SttErrorCode.WHISPER_INFERENCE_FAILED,
                                    message = "Whisper inference failed: ${t.message}",
                                    context = mapOf("pcmSamples" to samples.size, "exception" to t::class.java.simpleName)
                                )
                                sttErrorListener?.onSttError(error)
                                ""
                            }

                            // Transition back: INFERENCING → RECORDING
                            try {
                                lifecycleManager.transitionTo(SttLifecycleState.RECORDING)
                            } catch (_: IllegalStateException) { }

                            if (text.isNotBlank()) {
                                // ── Timing: compute and log diagnostic ─────
                                val whisperMs = System.currentTimeMillis() - inferenceStartMs
                                timingPcmTotalMs = System.currentTimeMillis() - timingPcmStartMs
                                timingVadActiveMs = sttProcessor?.vadActiveMs ?: 0L
                                timingTotalMs = System.currentTimeMillis() - timingUtteranceStartMs
                                val timing = SttTiming(
                                    pcmMs = timingPcmTotalMs,
                                    vadActiveMs = timingVadActiveMs,
                                    whisperMs = whisperMs,
                                    totalMs = timingTotalMs
                                )
                                SttLogger.pcm("[DIAG] timing: pcmMs=${timing.pcmMs}, vadActiveMs=${timing.vadActiveMs}, whisperMs=${timing.whisperMs}, totalMs=${timing.totalMs}")

                                lastTranscribedText = text
                                onTimingCallback?.invoke(timing)
                                onTimingListener?.invoke(timing.pcmMs, timing.vadActiveMs, timing.whisperMs, timing.totalMs)
                                onResult?.invoke(text)
                            }
                        }
                    },
                    calibrationLogger = if (debugVad) VadCalibrationLogger() else null
                ).apply { start() }

                // ── Timing: utterance start marker ────────────────────────
                timingUtteranceStartMs = System.currentTimeMillis()

                SttLogger.lifecycle("VAD-driven transcription pipeline started, state=${lifecycleManager.currentState.javaClass.simpleName}")
            } catch (t: Throwable) {
                SttLogger.error("code=UNKNOWN_ERROR, message=\"${t.message}\"")
                val error = SttError(
                    code = SttErrorCode.UNKNOWN_ERROR,
                    message = "Unhandled error during start: ${t.message}",
                    context = mapOf("exception" to t::class.java.simpleName)
                )
                sttErrorListener?.onSttError(error)
                stopInternal()
                dispatchError(t)
            }
        }
    }

    fun stopAndTranscribe() {
        synchronized(stateLock) {
            if (!isRunning.get()) return
            isRunning.set(false)

            try {
                val captureDurationMs = timingPcmStartMs.let { if (it > 0) System.currentTimeMillis() - it else 0L }

                sttProcessor?.stop()
                val pcm = sttProcessor?.forceFinalize()
                val processorVadMs = sttProcessor?.vadActiveMs ?: 0L
                sttProcessor = null

                audioCapture?.stop()
                audioCapture = null

                // Transition: RECORDING → READY
                try {
                    lifecycleManager.transitionTo(SttLifecycleState.READY)
                } catch (_: IllegalStateException) { }

                if (pcm != null && pcm.isNotEmpty()) {
                    SttLogger.pcmD("final pcm size=${pcm.size}")
                    val inferenceStartMs = System.currentTimeMillis()
                    val samples = pcm.toShortArray()
                    val text = NativeSession.transcribe(samples)?.trim().orEmpty()
                    val whisperMs = System.currentTimeMillis() - inferenceStartMs

                    if (text.isNotBlank()) {
                        timingPcmTotalMs = captureDurationMs
                        timingVadActiveMs = processorVadMs
                        timingTotalMs = System.currentTimeMillis() - timingUtteranceStartMs
                        val timing = SttTiming(
                            pcmMs = timingPcmTotalMs,
                            vadActiveMs = timingVadActiveMs,
                            whisperMs = whisperMs,
                            totalMs = timingTotalMs
                        )
                        SttLogger.pcm("[DIAG] timing: pcmMs=${timing.pcmMs}, vadActiveMs=${timing.vadActiveMs}, whisperMs=${timing.whisperMs}, totalMs=${timing.totalMs}")

                        lastTranscribedText = text
                        onTimingCallback?.invoke(timing)
                        onTimingListener?.invoke(timing.pcmMs, timing.vadActiveMs, timing.whisperMs, timing.totalMs)
                        onResult?.invoke(text)
                    }
                } else {
                    SttLogger.pcmW("no pcm available from accumulator")
                }
            } catch (t: Throwable) {
                dispatchError(t)
            } finally {
                stopInternal()
            }
        }
    }

    fun stop() = stopAndTranscribe()

    private fun resetInternalState() {
        lastTranscribedText = null
    }

    private fun stopInternal() {
        isRunning.set(false)
        sttProcessor?.stop()
        sttProcessor = null
        audioCapture?.stop()
        audioCapture = null

        // Unload model on the dedicated Whisper executor — serialized and
        // guaranteed to complete before any future loadModel() call.
        whisperExecutor.submit {
            NativeSession.unloadModel()
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
     *   - transition to DESTROYED
     */
    fun destroy() {
        synchronized(stateLock) {
            stopInternal()
            lifecycleManager.transitionToDestroyed()
        }
        whisperExecutor.shutdown()
        try {
            whisperExecutor.awaitTermination(10, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            whisperExecutor.shutdownNow()
        }
        SttLogger.lifecycle("destroy: resources released, state=DESTROYED")
    }

    private fun dispatchError(t: Throwable) {
        onError?.invoke(t)

        // Include timing fields in error context when available
        val timingCtx = mutableMapOf<String, Any?>(
            "exception" to t::class.java.simpleName
        )
        if (timingPcmTotalMs > 0) timingCtx["pcmMs"] = timingPcmTotalMs
        if (timingVadActiveMs > 0) timingCtx["vadActiveMs"] = timingVadActiveMs
        if (timingTotalMs > 0) timingCtx["totalMs"] = timingTotalMs

        val error = SttError(
            code = SttErrorCode.UNKNOWN_ERROR,
            message = t.message ?: "Unknown error",
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
        fun loadModel(path: String, debug: Boolean) {
            if (debug) SttLogger.whisperD("Loading model: $path")
            WhisperBridge.loadModel(path)
        }

        fun transcribe(pcm: ShortArray): String = WhisperBridge.transcribe(pcm)

        fun unloadModel() {
            SttLogger.whisperD("Unloading model")
            WhisperBridge.unloadModel()
        }
    }
}
