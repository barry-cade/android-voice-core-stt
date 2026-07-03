package dev.barrycade.voicecore.stt

import android.util.Log
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
 */
class SpeechToText internal constructor(
    private val config: RuntimeSttConfig,
    private val modelPath: String
) {
    companion object {
        private const val TAG = "STT_STREAM"

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

    init {
        config.validate()
        Log.i("STT_CONFIG", "Validated STT config: $config")
    }

    private var onResult: ((String) -> Unit)? = null
    private var onError: ((Throwable) -> Unit)? = null
    private val debugVad = true

    private val isRunning = AtomicBoolean(false)
    private val stateLock = Any()

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

    fun start() {
        synchronized(stateLock) {
            if (isRunning.get()) return

            try {
                resetInternalState()
                dumpConfig()

                // Load model on the dedicated Whisper executor — ensures any
                // previous unloadModel() has completed before we proceed.
                val loadFuture = whisperExecutor.submit<java.lang.Void> {
                    NativeSession.loadModel(modelPath, debug = true)
                    null
                }
                loadFuture.get(30, TimeUnit.SECONDS)

                isRunning.set(true)

                val capture = AudioCapture(
                    sampleRate = 16000,
                    requestedBufferSizeInBytes = 32000
                ).apply {
                    start()
                }
                audioCapture = capture

                sttProcessor = SttProcessor(
                    audioCapture = capture,
                    vad = Vad(config).apply {
                        debugLogging = config.debugLoggingEnabled
                    },
                    utteranceAccumulator = UtteranceAccumulator(config),
                    listener = object : UtteranceListener {
                        override fun onUtteranceReady(pcm: FloatArray) {
                            if (debugVad) Log.i("STT_PCM", "Final PCM size=${pcm.size}")
                            val samples = pcm.toShortArray()
                            if (debugVad) Log.i("STT_WHISPER", "Calling Whisper with PCM size=${pcm.size}")
                            val text = NativeSession.transcribe(samples)?.trim().orEmpty()
                            if (text.isNotBlank()) {
                                Log.d(TAG, "Whisper utterance transcript: $text")
                                lastTranscribedText = text
                                onResult?.invoke(text)
                            }
                        }
                    },
                    calibrationLogger = if (debugVad) VadCalibrationLogger() else null
                ).apply { start() }

                Log.d(TAG, "VAD-driven transcription pipeline started")
            } catch (t: Throwable) {
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

                if (pcm != null && pcm.isNotEmpty()) {
                    Log.d(TAG, "final pcm size=${pcm.size}")
                    val samples = pcm.toShortArray()
                    val text = NativeSession.transcribe(samples)?.trim().orEmpty()
                    if (text.isNotBlank()) {
                        lastTranscribedText = text
                        onResult?.invoke(text)
                    }
                } else {
                    Log.w(TAG, "no pcm available from accumulator")
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
     */
    fun destroy() {
        synchronized(stateLock) {
            stopInternal()
        }
        whisperExecutor.shutdown()
        try {
            whisperExecutor.awaitTermination(10, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            whisperExecutor.shutdownNow()
        }
    }

    private fun dispatchError(t: Throwable) = onError?.invoke(t)

    fun dumpConfig() {
        Log.i("STT_CONFIG", "Active config: $config")
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
        private val TAG = "NativeSession"

        fun loadModel(path: String, debug: Boolean) {
            if (debug) Log.d(TAG, "Loading model: $path")
            WhisperBridge.loadModel(path)
        }

        fun transcribe(pcm: ShortArray): String = WhisperBridge.transcribe(pcm)

        fun unloadModel() {
            Log.d(TAG, "Unloading model")
            WhisperBridge.unloadModel()
        }
    }
}
