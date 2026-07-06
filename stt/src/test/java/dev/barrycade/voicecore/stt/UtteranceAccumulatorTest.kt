package dev.barrycade.voicecore.stt

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceAccumulatorTest {
    @Test
    fun emitsUtteranceAfterSilence() {
        val accumulator = UtteranceAccumulator(
            sampleRate = 16000,
            silenceDurationMs = 40  // 2 frames of 10ms silence to trigger
        )
        val speechFrame = FloatArray(160) { 0.2f }  // 10ms at 16kHz
        val silenceFrame = FloatArray(160) { 0.0f }

        // Pre-roll is 100ms = 10 frames of 10ms. Pass pre-roll first.
        for (i in 0 until 10) {
            accumulator.processFrame(speechFrame)
        }

        // Now speech is active. Feed enough speech to exceed minimum utterance length (700ms).
        // At 10ms per frame, need 70 frames = 700ms.
        for (i in 0 until 70) {
            accumulator.processFrame(speechFrame)
        }

        // Two silence frames (40ms total) should trigger finalization:
        // Frame 1: silenceFrameCount = 1 (< maxSilenceFrames = 2), returns null
        // Frame 2: silenceFrameCount = 2 >= 2, and minimumMet (700ms) is satisfied
        accumulator.processFrame(silenceFrame)
        val finalized = accumulator.processFrame(silenceFrame)
        assertNotNull(finalized)
        assertTrue(finalized!!.isNotEmpty())
    }

    @Test
    fun doesNotEmitUtteranceOnSilenceOnly() {
        val accumulator = UtteranceAccumulator(
            sampleRate = 16000,
            silenceDurationMs = 500
        )
        val silenceFrame = FloatArray(160) { 0.0f }

        // Even after pre-roll (100ms = 10 frames at 10ms), silence should not trigger VAD
        for (i in 0 until 100) {
            val result = accumulator.processFrame(silenceFrame)
            assertNull("Must not emit utterance on silence only (frame $i)", result)
        }
    }

    @Test
    fun forceFinalizeReturnsBufferedContent() {
        val accumulator = UtteranceAccumulator(
            sampleRate = 16000,
            silenceDurationMs = 500,
            maxUtteranceLengthMs = 4000,
            stableBlockMs = 120
        )
        val speechFrame = FloatArray(160) { 0.2f }

        // Pre-roll is 100ms = 10 frames of 10ms. Pass pre-roll first, then send speech.
        for (i in 0 until 10) {
            accumulator.processFrame(speechFrame)
        }
        accumulator.processFrame(speechFrame)
        accumulator.processFrame(speechFrame)

        val result = accumulator.forceFinalize()
        assertNotNull("forceFinalize must return buffered content", result)
        assertTrue("forceFinalize result must not be empty", result!!.isNotEmpty())
    }

    @Test
    fun forceFinalizeReturnsNullWhenEmpty() {
        val accumulator = UtteranceAccumulator(
            sampleRate = 16000,
            silenceDurationMs = 500
        )
        val result = accumulator.forceFinalize()
        assertNull("forceFinalize must return null when no frames were ever fed", result)
    }
}
