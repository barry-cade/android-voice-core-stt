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
            utteranceSilenceTimeoutMs = 40  // 4 frames of 10ms = finalize
        )
        val speechFrame = FloatArray(160) { 0.2f }  // 10ms at 16kHz
        val silenceFrame = FloatArray(160) { 0.0f }

        // Pre-roll is 100ms = 10 frames of 10ms. Pass pre-roll first.
        for (i in 0 until 10) {
            accumulator.processFrame(speechFrame)
        }

        // Now speech is active. Feed speech frames to accumulate PCM.
        for (i in 0 until 35) {
            accumulator.processFrame(speechFrame)
        }

        // Four silence frames (40ms total) should trigger silence timeout.
        for (i in 0 until 3) {
            accumulator.processFrame(silenceFrame)
        }

        // After handleUtteranceReady, processFrame returns UtteranceReady
        val result = accumulator.processFrame(silenceFrame)
        assertTrue("Silence timeout must return UtteranceReady",
            result is FrameResult.UtteranceReady)
        val ready = result as FrameResult.UtteranceReady
        assertTrue("PCM must not be empty", ready.pcm.isNotEmpty())
    }

    @Test
    fun doesNotEmitUtteranceOnSilenceOnly() {
        val accumulator = UtteranceAccumulator(
            sampleRate = 16000,
            utteranceSilenceTimeoutMs = 5000
        )
        val silenceFrame = FloatArray(160) { 0.0f }

        for (i in 0 until 100) {
            val result = accumulator.processFrame(silenceFrame)
            assertTrue("Must return Continue on silence only (frame $i)",
                result is FrameResult.Continue)
        }
    }

    @Test
    fun forceFinalizeReturnsBufferedContent() {
        val accumulator = UtteranceAccumulator(
            sampleRate = 16000,
            utteranceSilenceTimeoutMs = 5000,
            utteranceMaxDurationMs = 4000
        )
        val speechFrame = FloatArray(160) { 0.2f }

        for (i in 0 until 10) {
            accumulator.processFrame(speechFrame)
        }
        accumulator.processFrame(speechFrame)
        accumulator.processFrame(speechFrame)

        val result = accumulator.forceFinalize()
        assertNotNull(result)
        assertTrue(result!!.isNotEmpty())
    }

    @Test
    fun forceFinalizeReturnsNullWhenEmpty() {
        val accumulator = UtteranceAccumulator(sampleRate = 16000)
        val result = accumulator.forceFinalize()
        assertNull("forceFinalize must return null when no frames were ever fed", result)
    }
}
