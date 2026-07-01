package dev.barrycade.voicecore.stt

import android.util.Log
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

                    val isSpeechFrame = vad.isSpeech(frame)
                    val rms = computeRms(frame)
                    calibrationLogger?.logFrame(frame, isSpeechFrame, rms, 0)
                    val utterance = utteranceAccumulator.processChunk(frame, isSpeechFrame)
                    if (utterance != null) {
                        Log.d("SttProcessor", "Utterance finalized with ${utterance.size} samples")
                        calibrationLogger?.logUtteranceFinalized(utterance.size, utterance.size * 1000 / 16000)
                        listener.onUtteranceReady(utterance)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }, "SttProcessorThread").apply { start() }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        workerThread?.join(500)
        workerThread = null
    }

    private fun computeRms(frame: FloatArray): Double {
        if (frame.isEmpty()) return 0.0
        var sumSquares = 0.0
        for (sample in frame) {
            sumSquares += sample * sample
        }
        return kotlin.math.sqrt(sumSquares / frame.size)
    }
}
