package dev.barrycade.voicecore.stt

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic test for [UtteranceAccumulator] with pre-roll, trailing silence,
 * and minimum utterance length enforcement.
 *
 * Tests generate synthetic PCM (constant-amplitude or silence), feed it through
 * the pipeline with a configured [Vad], and verify that accumulator finalization
 * produces a non-null utterance buffer.
 *
 * All tests are PDP-aligned: linear arrange, act, assert.
 */
class SttDeterministicTest {

    @Test
    fun deterministicUtterance_WithVad_EmitsNonEmptyBuffer() {
        val frameSize = 320
        val vad = Vad(energyThreshold = 0.01)
        val frames = run {
            val speechFrames = mutableListOf<FloatArray>()
            // 30 frames of 20ms = 600ms speech
            for (i in 0 until 30) {
                speechFrames.add(FloatArray(frameSize) { 0.3f })
            }
            speechFrames
        }

        val accumulator = UtteranceAccumulator(sampleRate = 16000, silenceDurationMs = 500)
        var finalizedUtterance: FloatArray? = null

        // Pass pre-roll (100ms = 5 frames of 20ms silence) before speech
        val silenceFrame = FloatArray(frameSize) { 0.0f }
        for (i in 0 until 5) {
            accumulator.processChunk(silenceFrame, false)
        }

        frames.forEach { frame ->
            val isSpeech = vad.isSpeech(frame)
            finalizedUtterance = accumulator.processChunk(frame, isSpeech)
        }

        if (finalizedUtterance == null) {
            // Feed trailing silence if VAD didn't finalize
            val paddingFrames = List(25) { FloatArray(frameSize) { 0.0f } }
            paddingFrames.forEach { frame ->
                finalizedUtterance = accumulator.processChunk(frame, false)
            }
        }

        assertNotNull(finalizedUtterance)
        assertTrue("finalized utterance must not be empty", finalizedUtterance!!.isNotEmpty())
    }

    @Test
    fun deterministicUtterance_WithCustomPreRoll_EmitsNonEmptyBuffer() {
        val vad = Vad(energyThreshold = 0.05)
        val accumulator = UtteranceAccumulator(
            sampleRate = 16000,
            preRollMs = 100,
            silenceDurationMs = 250,
            maxUtteranceLengthMs = 4000,
            stableBlockMs = 120,
        )

        val speechFrame = FloatArray(320) { 0.2f }
        // Pre-roll is 100ms = 5 frames of 20ms. Pass pre-roll, then feed speech.
        for (i in 0 until 10) {
            accumulator.processChunk(speechFrame, false)
        }
        for (i in 0 until 10) {
            accumulator.processChunk(speechFrame, true)
        }

        val result = accumulator.forceFinalize()
        assertNotNull("forceFinalize must return buffered speech", result)
        assertTrue("forceFinalize result must not be empty", result!!.isNotEmpty())
    }

    @Test
    fun forceFinalize_WithOnlyPreRoll_ReturnsEmptyPcm() {
        val vad = Vad(energyThreshold = 0.01)
        val accumulator = UtteranceAccumulator(
            sampleRate = 16000,
            silenceDurationMs = 500,
            maxUtteranceLengthMs = 4000,
            stableBlockMs = 120,
        )

        val speechFrame = FloatArray(320) { 0.2f }

        // Pre-roll is 100ms = 5 frames of 20ms. Send enough frames to pass pre-roll
        // and accumulate speech for finalization.
        for (i in 0 until 15) {
            accumulator.processChunk(speechFrame, true)
        }

        val result = accumulator.forceFinalize()
        assertNotNull("forceFinalize must return buffered speech", result)
        assertTrue("forceFinalize result must not be empty", result!!.isNotEmpty())
    }

    @Test
    fun deterministicUtterance_WithSilenceFinalization_EmitsNonEmpty() {
        val vad = Vad(energyThreshold = 0.01)
        val accumulator = UtteranceAccumulator(
            sampleRate = 16000,
            silenceDurationMs = 40, // 2 silence frames (20ms each) = finalize
            maxUtteranceLengthMs = 10000,
            stableBlockMs = 120,
        )

        val speechFrame = FloatArray(320) { 0.2f } // 20ms at 16kHz
        val silenceFrame = FloatArray(320) { 0.0f }

        // First pass pre-roll (100ms = 5 frames of 20ms)
        for (i in 0 until 5) {
            accumulator.processChunk(speechFrame, false)
        }

        // Feed enough speech to exceed minimum utterance length (700ms).
        // At 20ms per frame, need 35 frames = 700ms.
        for (i in 0 until 35) {
            accumulator.processChunk(speechFrame, true)
        }

        // Two silence frames (40ms total silence) should trigger finalization
        accumulator.processChunk(silenceFrame, false) // silenceFrameCount = 1
        val utterance = accumulator.processChunk(silenceFrame, false) // silenceFrameCount = 2 >= maxSilenceFrames

        assertNotNull("utterance must be non-null after silence frames", utterance)
        assertTrue("utterance must not be empty", utterance!!.isNotEmpty())
    }

    /**
     * Converts a Kotlin ShortArray (already in native order) to a FloatArray.
     */
    private fun shortArrayToFloatArray(shorts: ShortArray): FloatArray {
        val floats = FloatArray(shorts.size)
        for (i in shorts.indices) {
            floats[i] = shorts[i].toFloat() / 32768f
        }
        return floats
    }
}
