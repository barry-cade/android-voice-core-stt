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

    fun setOnResultListener(listener: (String) -> Unit) {
        onResult = listener
    }

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
                dumpConfig()

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

                sttProcessor = SttProcessor(
                    audioCapture = capture,
                    vad = Vad(config).apply {
                        debugLogging = config.debugLoggingEnabled
                    },
                    utteranceAccumulator = UtteranceAccumulator(config),
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
                            if (debugVad) SttLogger.whisperD("inferenceStart: pcmMs=${pcm.size * 1000 / 16000}")

                            val text = try {
                                val result = NativeSession.transcribe(samples)?.trim().orEmpty()
                                SttLogger.whisper("inferenceEnd: timeMs=${pcm.size * 1000 / 16000}, text=\"$result\"")
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
                                lastTranscribedText = text
                                onResult?.invoke(text)
                            }
                        }
                    },
                    calibrationLogger = if (debugVad) VadCalibrationLogger() else null
                ).apply { start() }

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
                sttProcessor?.stop()
                val pcm = sttProcessor?.forceFinalize()
                sttProcessor = null

                audioCapture?.stop()
                audioCapture = null

                // Transition: RECORDING → READY
                try {
                    lifecycleManager.transitionTo(SttLifecycleState.READY)
                } catch (_: IllegalStateException) { }

                if (pcm != null && pcm.isNotEmpty()) {
                    SttLogger.pcmD("final pcm size=${pcm.size}")
                    val samples = pcm.toShortArray()
                    val text = NativeSession.transcribe(samples)?.trim().orEmpty()
                    if (text.isNotBlank()) {
                        lastTranscribedText = text
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
        val error = SttError(
            code = SttErrorCode.UNKNOWN_ERROR,
            message = t.message ?: "Unknown error",
            context = mapOf("exception" to t::class.java.simpleName)
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
