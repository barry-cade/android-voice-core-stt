package dev.barrycade.voicecore.stt

import android.util.Log

/**
 * UtteranceAccumulator transforms incoming FloatArray frames into complete utterance buffers.
 * Uses a simple append-only buffer: every frame is appended to speechAccumulator regardless
 * of VAD state. No trimming, no index math, no list removals. VAD is used only to mark
 * utterance start and end (silence padding, max duration). forceFinalize() returns all
 * buffered PCM even if VAD never fired.
 *
 * Pre-roll is disabled (no separate preRollBuffer with trimming). When VAD fires, the
 * utterance starts from whatever has been accumulated so far.
 */
internal class UtteranceAccumulator(
    private val sampleRate: Int = 16000,
    /** Pre-roll is accepted but unused — accumulator is append-only from the start. */
    @Suppress("UNUSED_PARAMETER")
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

    companion object {
        private const val TAG = "ACCUM"
    }

    private val silenceFrameDurationMs = 20
    private val maxSilenceFrames = (silenceDurationMs / silenceFrameDurationMs).coerceAtLeast(1)
    private val stableBlockSamples = (sampleRate * stableBlockMs / 1000).coerceAtLeast(1)

    // Append-only buffer — never shrinks or trims during an utterance.
    private val speechAccumulator = mutableListOf<Float>()
    private var speechActive = false
    private var silenceFrameCount = 0
    private var totalDurationMs = 0

    fun processChunk(frame: FloatArray, isSpeechFrame: Boolean): FloatArray? {
        if (frame.isEmpty()) return null

        val frameDurationMs = frame.size * 1000 / sampleRate
        totalDurationMs += frameDurationMs

        // Append every frame unconditionally — append-only, no removals.
        for (sample in frame) {
            speechAccumulator.add(sample)
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
            }
            return null
        }
    }

    fun processFrame(frame: FloatArray): FloatArray? = processChunk(frame, vad.isSpeech(frame))

    fun reset() {
        speechAccumulator.clear()
        speechActive = false
        silenceFrameCount = 0
        totalDurationMs = 0
    }

    /**
     * forceFinalize returns all buffered PCM, even if VAD never fired.
     * Returns null only when no frames have ever been buffered.
     */
    fun forceFinalize(): FloatArray? {
        if (speechAccumulator.isEmpty()) return null
        return finalizeUtterance()
    }

    private fun finalizeUtterance(): FloatArray {
        if (speechAccumulator.isEmpty()) {
            Log.w(TAG, "finalizeUtterance called with empty buffer, returning null")
            return FloatArray(0)
        }

        val utterance = speechAccumulator.toFloatArray()
        val paddedLength = if (utterance.size % stableBlockSamples == 0) {
            utterance.size
        } else {
            ((utterance.size / stableBlockSamples) + 1) * stableBlockSamples
        }

        val padded = FloatArray(paddedLength)
        utterance.copyInto(padded, 0)
        speechAccumulator.clear()
        speechActive = false
        silenceFrameCount = 0
        totalDurationMs = 0
        return padded
    }
}
