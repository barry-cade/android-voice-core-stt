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
            stopTrigger = ManualStopTrigger(),
            manualManualConfig = ManualManualConfig(
                maxDurationMs = 30000,
                abnormalSilenceMs = 40  // 4 frames of 10ms = finalize
            )
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

        // Four silence frames (40ms total) should trigger abnormal silence fallback.
        // Each frame is 10ms, so abnormalSilenceFramesFor(10) = 40 / 10 = 4.
        // Frame 1: silenceFrameCount = 1 (< 4), returns null
        // Frame 2: silenceFrameCount = 2 (< 4), returns null
        // Frame 3: silenceFrameCount = 3 (< 4), returns null
        // Frame 4: silenceFrameCount = 4 >= 4, triggers handleAbnormalSilence
        for (i in 0 until 3) {
            accumulator.processFrame(silenceFrame)
        }

        // After handleAbnormalSilence, terminationReason is set and processFrame returns null
        val finalized = accumulator.processFrame(silenceFrame)
        assertNull("Abnormal silence must return null (no PCM)", finalized)
        assertNotNull("terminationReason must be set", accumulator.terminationReason)
    }

    @Test
    fun doesNotEmitUtteranceOnSilenceOnly() {
        val accumulator = UtteranceAccumulator(
            sampleRate = 16000,
            stopTrigger = ManualStopTrigger(),
            manualManualConfig = ManualManualConfig(
                maxDurationMs = 30000,
                abnormalSilenceMs = 5000
            )
        )
        val silenceFrame = FloatArray(160) { 0.0f }

        // Even after pre-roll (100ms = 10 frames at 10ms), silence should not trigger
        for (i in 0 until 100) {
            val result = accumulator.processFrame(silenceFrame)
            assertNull("Must not emit utterance on silence only (frame $i)", result)
        }
    }

    @Test
    fun forceFinalizeReturnsBufferedContent() {
        val accumulator = UtteranceAccumulator(
            sampleRate = 16000,
            stopTrigger = ManualStopTrigger(),
            manualManualConfig = ManualManualConfig(
                maxDurationMs = 4000,
                abnormalSilenceMs = 5000
            )
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
            stopTrigger = ManualStopTrigger(),
            manualManualConfig = ManualManualConfig()
        )
        val result = accumulator.forceFinalize()
        assertNull("forceFinalize must return null when no frames were ever fed", result)
    }
}
