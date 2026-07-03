package dev.barrycade.voicecore.stt

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceAccumulatorTest {
    @Test
    fun emitsUtteranceAfterSilence() {
        val accumulator = UtteranceAccumulator(sampleRate = 16000, silenceDurationMs = 20)
        val speechFrame = FloatArray(160) { 0.2f }
        val silenceFrame = FloatArray(160) { 0.0f }

        assertTrue(accumulator.processFrame(speechFrame) == null)
        assertTrue(accumulator.processFrame(speechFrame) == null)
        val finalized = accumulator.processFrame(silenceFrame)
        assertNotNull(finalized)
        assertTrue(finalized!!.isNotEmpty())
    }

    @Test
    fun doesNotEmitUtteranceOnSilenceOnly() {
        val accumulator = UtteranceAccumulator(sampleRate = 16000, silenceDurationMs = 20)
        val silenceFrame = FloatArray(160) { 0.0f }

        for (i in 0 until 100) {
            val result = accumulator.processFrame(silenceFrame)
            assertNull("Must not emit utterance on silence only (frame $i)", result)
        }
    }

    @Test
    fun forceFinalizeReturnsBufferedContent() {
        val accumulator = UtteranceAccumulator(
            sampleRate = 16000,
            preRollMs = 50,
            silenceDurationMs = 500,
            maxUtteranceLengthMs = 4000,
            stableBlockMs = 120
        )
        val speechFrame = FloatArray(160) { 0.2f }

        // Feed some speech frames
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
