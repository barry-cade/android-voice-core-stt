package dev.barrycade.voicecore.stt

import android.util.Log
import java.util.Collections
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SpeechToText accumulates microphone audio and transcribes the full utterance once
 * when recording stops. This removes the sliding-window streaming pipeline while
 * keeping the Whisper bridge and capture path intact for future replacement.
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
            highPassCutoffHz: Int,
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
                highPassCutoffHz = highPassCutoffHz,
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
    private var nativeSession: NativeSession? = null

    private val audioQueue: BlockingQueue<ShortArray> = LinkedBlockingQueue()
    private var inferenceWorker: ExecutorService? = null

    private val transcriptBuilder = StringBuilder()
    private val transcriptLock = Any()
    private val audioBufferLock = Any()
    private val accumulatedSamples = Collections.synchronizedList(mutableListOf<Short>())

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
                nativeSession = NativeSession(debug = true).apply { loadModel(modelPath) }

                isRunning.set(true)
                startInferenceWorker()

                val capture = AudioCapture(
                    sampleRate = 16000,
                    requestedBufferSizeInBytes = 32000
                ).apply {
                    setOnAudioFrameListener { frame ->
                        if (!audioQueue.offer(frame)) {
                            Log.w(TAG, "Audio FIFO overflow")
                        }
                    }
                    start()
                }
                audioCapture = capture

                sttProcessor = SttProcessor(
                    audioCapture = capture,
                    vad = Vad(config),
                    utteranceAccumulator = UtteranceAccumulator(config),
                    listener = object : UtteranceListener {
                        override fun onUtteranceReady(pcm: FloatArray) {
                            if (debugVad) Log.i("STT_PCM", "Final PCM size=${pcm.size}")
                            Thread {
                                val session = nativeSession
                                val samples = pcm.toShortArray()
                                if (debugVad) Log.i("STT_WHISPER", "Calling Whisper with PCM size=${pcm.size}")
                                val text = session?.transcribe(samples)?.trim().orEmpty()
                                if (text.isNotBlank()) {
                                    Log.d(TAG, "Whisper utterance transcript: $text")
                                    onResult?.invoke(text)
                                }
                            }.start()
                        }
                    },
                    calibrationLogger = if (debugVad) VadCalibrationLogger() else null
                ).apply { start() }

                Log.d(TAG, "Single-pass transcription pipeline started")
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
                sttProcessor = null

                audioCapture?.stop()
                audioCapture = null

                inferenceWorker?.shutdown()
                val finished = inferenceWorker?.awaitTermination(20, TimeUnit.SECONDS) ?: true
                if (!finished) {
                    Log.w(TAG, "Inference worker timeout during drain")
                    inferenceWorker?.shutdownNow()
                }
                inferenceWorker = null

                val finalText = transcribeAccumulatedSamples()
                synchronized(transcriptLock) {
                    transcriptBuilder.setLength(0)
                    transcriptBuilder.append(finalText)
                }
                onResult?.invoke(finalText)
            } catch (t: Throwable) {
                dispatchError(t)
            } finally {
                stopInternal()
            }
        }
    }

    fun stop() = stopAndTranscribe()
    fun transcribeRecorded() = stopAndTranscribe()

    private fun resetInternalState() {
        audioQueue.clear()
        synchronized(audioBufferLock) {
            accumulatedSamples.clear()
        }
        synchronized(transcriptLock) {
            transcriptBuilder.setLength(0)
        }
    }

    private fun startInferenceWorker() {
        inferenceWorker = Executors.newSingleThreadExecutor()
        inferenceWorker?.execute {
            while (isRunning.get() || audioQueue.isNotEmpty()) {
                try {
                    val frame = audioQueue.poll(100, TimeUnit.MILLISECONDS)
                    if (frame != null) {
                        appendAudioFrame(frame)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (t: Throwable) {
                    dispatchError(t)
                }
            }
            Log.d(TAG, "Inference worker finished")
        }
    }

    private fun appendAudioFrame(frame: ShortArray) {
        synchronized(audioBufferLock) {
            accumulatedSamples.addAll(frame.toList())
        }
    }

    private fun transcribeAccumulatedSamples(): String {
        val samples = synchronized(audioBufferLock) {
            accumulatedSamples.toList()
        }

        if (samples.isEmpty()) return ""

        val pcm = ShortArray(samples.size)
        for (index in samples.indices) {
            pcm[index] = samples[index]
        }

        return nativeSession?.transcribe(pcm)?.trim().orEmpty()
    }

    private fun stopInternal() {
        isRunning.set(false)
        sttProcessor?.stop()
        sttProcessor = null
        audioCapture?.stop()
        audioCapture = null
        inferenceWorker?.shutdownNow()
        inferenceWorker = null
        val session = nativeSession
        nativeSession = null
        Thread { try { session?.close() } catch(_: Exception) {} }.start()
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

    private class NativeSession(private val debug: Boolean) {
        fun loadModel(path: String) {
            if (debug) Log.d("Whisper", "Loading: $path")
            WhisperBridge.loadModel(path)
        }

        fun transcribe(pcm: ShortArray): String = WhisperBridge.transcribe(pcm)

        fun close() {
            if (debug) Log.d("Whisper", "Unloading model")
            WhisperBridge.unloadModel()
        }
    }
}
