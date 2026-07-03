package dev.barrycade.voicecore.stt

import android.util.Log

/**
 * UtteranceAccumulator transforms incoming FloatArray frames into complete utterance buffers.
 * Every frame is continuously buffered into speechAccumulator regardless of VAD state.
 * VAD is used only to mark utterance start/end for automatic finalization (silence padding,
 * max duration). forceFinalize() returns all buffered PCM even if VAD never fired.
 */
internal class UtteranceAccumulator(
    private val sampleRate: Int = 16000,
    private val preRollMs: Int = 200,
    private val silenceDurationMs: Int = 500,
    private val maxUtteranceLengthMs: Int = 7000,
    private val stableBlockMs: Int = 500,
    private val vad: Vad = Vad()
) {
    constructor(config: RuntimeSttConfig, sampleRate: Int = 16000, vad: Vad = Vad(config)) : this(
        sampleRate = sampleRate,
        preRollMs = config.preRollMs,
        silenceDurationMs = config.silencePaddingMs,
        maxUtteranceLengthMs = config.maxUtteranceLengthMs,
        stableBlockMs = config.stableChunkSizeMs,
        vad = vad
    )

    private val preRollSamples = (sampleRate * preRollMs / 1000).coerceAtLeast(1)
    private val silenceFrameDurationMs = 20
    private val maxSilenceFrames = (silenceDurationMs / silenceFrameDurationMs).coerceAtLeast(1)
    private val stableBlockSamples = (sampleRate * stableBlockMs / 1000).coerceAtLeast(1)

    private val maxSamples = (sampleRate * maxUtteranceLengthMs / 1000).coerceAtLeast(1)
    private val speechAccumulator = FloatArray(maxSamples)
    private var speechPtr = 0
    private var speechActive = false
    private var silenceFrameCount = 0
    private var totalDurationMs = 0
    private val preRollBuffer = mutableListOf<Float>()

    fun processChunk(frame: FloatArray, isSpeechFrame: Boolean): FloatArray? {
        if (frame.isEmpty()) return null

        val frameDurationMs = frame.size * 1000 / sampleRate
        totalDurationMs += frameDurationMs

        // Always buffer every frame into speechAccumulator regardless of VAD state.
        // This guarantees forceFinalize() always returns PCM when frames have been fed.
        appendFrame(frame)

        // Sanity check: speechPtr must never go negative
        if (speechPtr < 0) {
            Log.e("ACCUM", "speechPtr went negative ($speechPtr), clamping to 0")
            speechPtr = 0
        }

        if (speechActive) {
            silenceFrameCount = if (isSpeechFrame) 0 else silenceFrameCount + 1
            if (silenceFrameCount >= maxSilenceFrames) {
                return finalizeUtterance()
            }
            if (totalDurationMs >= maxUtteranceLengthMs) {
                return finalizeUtterance()
            }
            return null
        } else {
            if (isSpeechFrame) {
                speechActive = true
                silenceFrameCount = 0
                totalDurationMs = 0
                return null
            } else {
                // Maintain pre-roll ring buffer for speech-start context
                preRollBuffer.addAll(frame.toList())
                if (preRollBuffer.size > preRollSamples) {
                    val excess = preRollBuffer.size - preRollSamples
                    repeat(excess) {
                        if (preRollBuffer.isNotEmpty()) {
                            preRollBuffer.removeAt(0)
                        } else {
                            Log.e("ACCUM", "preRollBuffer unexpectedly empty during trim")
                        }
                    }
                }
                return null
            }
        }
    }

    /**
     * Appends a frame into the continuous speechAccumulator, growing up to maxSamples.
     * If the buffer would overflow, it finalizes and discards the frame.
     */
    private fun appendFrame(frame: FloatArray) {
        for (sample in frame) {
            if (speechPtr >= speechAccumulator.size) {
                return
            }
            speechAccumulator[speechPtr] = sample
            speechPtr++
        }
    }

    fun processFrame(frame: FloatArray): FloatArray? = processChunk(frame, vad.isSpeech(frame))

    fun reset() {
        speechPtr = 0
        preRollBuffer.clear()
        speechActive = false
        silenceFrameCount = 0
        totalDurationMs = 0
    }

    /**
     * forceFinalize returns all buffered PCM, even if VAD never fired.
     * Returns null only when no frames have ever been buffered (speechPtr == 0).
     */
    fun forceFinalize(): FloatArray? {
        if (speechPtr == 0) return null
        return finalizeUtterance()
    }

    private fun finalizeUtterance(): FloatArray {
        // Guard against negative or zero speechPtr
        val safePtr = if (speechPtr <= 0) 0 else speechPtr
        val utterance = speechAccumulator.copyOf(safePtr)
        val paddedLength = if (utterance.size % stableBlockSamples == 0) {
            utterance.size
        } else {
            ((utterance.size / stableBlockSamples) + 1) * stableBlockSamples
        }

        val padded = FloatArray(paddedLength)
        utterance.copyInto(padded, 0)
        speechPtr = 0
        preRollBuffer.clear()
        speechActive = false
        silenceFrameCount = 0
        totalDurationMs = 0
        return padded
    }
}
