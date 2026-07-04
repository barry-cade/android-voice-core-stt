package dev.barrycade.voicecore.stt

import java.util.concurrent.atomic.AtomicBoolean

/**
 * SttProcessor polls FloatArray frames from AudioCapture and routes them to the VAD and
 * utterance accumulator. It does not call Whisper; it only emits finalized utterances.
 */
internal class SttProcessor(
    private val audioCapture: AudioCapture,
    private val vad: Vad,
    private val utteranceAccumulator: UtteranceAccumulator,
    private val listener: UtteranceListener,
    private val calibrationLogger: VadCalibrationLogger? = null
) {
        private val isRunning = AtomicBoolean(false)
    private var workerThread: Thread? = null

        /** Accumulated VAD active time in milliseconds. */
    @Volatile
    internal var vadActiveMs: Long = 0L
        private set

    /**
     * Reset per-utterance VAD active time to 0.
     * Called when UtteranceAccumulator detects a SILENCE → SPEECH transition,
     * and by SpeechToText.stopAndTranscribe() for manual invocation.
     * Guarantees per-utterance timing, not cumulative.
     */
    internal fun resetVadActiveMs() {
        vadActiveMs = 0L
    }

    fun start() {
        if (isRunning.getAndSet(true)) return

        workerThread = Thread({
            while (isRunning.get()) {
                try {
                                        val frame = audioCapture.frameQueue.poll()
                    if (frame == null) {
                        Thread.sleep(10L)
                        continue
                    }
                                        SttLogger.pcmD("dequeue frame for VAD, size=${frame.size}")

                    val isSpeechFrame = vad.isSpeech(frame)
                    val rms = computeRms(frame)
                    calibrationLogger?.logFrame(frame, isSpeechFrame, rms, 0)

                                        // ── Timing: accumulate VAD active duration ────────────
                    if (isSpeechFrame) {
                        SttLogger.vadD("speechFrame: rmsAboveThreshold=true, lastEnergy=${vad.lastFrameEnergy}")
                        val frameDurationMs = (frame.size * 1000L) / 16000L
                        vadActiveMs += frameDurationMs
                    }

                    val utterance = utteranceAccumulator.processChunk(frame, isSpeechFrame)
                    if (utterance != null) {
                        SttLogger.pcmD("Utterance finalized with ${utterance.size} samples")
                        calibrationLogger?.logUtteranceFinalized(utterance.size, utterance.size * 1000 / 16000)
                        listener.onUtteranceReady(utterance)
                    }
                                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (t: Throwable) {
                                        SttLogger.error("code=UNKNOWN_ERROR, message=\"${t.message}\"")
                    val error = SttError(
                        category = SttErrorCategory.UNKNOWN,
                        code = SttErrorCode.UNKNOWN_ERROR,
                        message = "SttProcessor worker thread error: ${t.message}",
                        lastVadState = vad.lastFrameEnergy > 0f,
                        lastRms = vad.lastFrameEnergy,
                        cause = t,
                        context = mapOf("exception" to t::class.java.simpleName, "detail" to (t.message ?: ""))
                    )
                    sttErrorListener?.onSttError(error)
                }
            }
        }, "SttProcessorThread").apply { start() }
    }

        fun stop() {
        if (!isRunning.getAndSet(false)) return
        workerThread?.join(500)
        workerThread = null
    }

    fun forceFinalize(): FloatArray? {
        val utterance = utteranceAccumulator.forceFinalize()
        if (utterance != null) {
            calibrationLogger?.logUtteranceFinalized(utterance.size, utterance.size * 1000 / 16000)
        }
        return utterance
    }

        private fun computeRms(frame: FloatArray): Double {
        if (frame.isEmpty()) return 0.0
        var sumSquares = 0.0
        for (sample in frame) {
            sumSquares += sample * sample
        }
        return kotlin.math.sqrt(sumSquares / frame.size)
    }

    companion object {
        var sttErrorListener: SttErrorListener? = null
    }
}
