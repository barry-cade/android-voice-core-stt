package dev.barrycade.voicecore.stt

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic test for [UtteranceAccumulator] with pre-roll and
 * silence-based finalization.
 */
class SttDeterministicTest {

    @Test
    fun deterministicUtterance_WithVad_EmitsNonEmptyBuffer() {
        val frameSize = 320
        val vad = Vad(energyThreshold = 0.01)
        val frames = run {
            val speechFrames = mutableListOf<FloatArray>()
            for (i in 0 until 30) {
                speechFrames.add(FloatArray(frameSize) { 0.3f })
            }
            speechFrames
        }

        val accumulator = UtteranceAccumulator(
            sampleRate = 16000,
            stopTrigger = ManualStopTrigger(),
            manualManualMaxDurationMs = 10000,
            manualManualAbnormalSilenceMs = 5000
        )

        val silenceFrame = FloatArray(frameSize) { 0.0f }
        for (i in 0 until 5) {
            accumulator.processChunk(silenceFrame, false)
        }

        frames.forEach { frame ->
            val isSpeech = vad.isSpeech(frame)
            accumulator.processChunk(frame, isSpeech)
        }

        val finalizedPcm = accumulator.forceFinalize()
        assertNotNull(finalizedPcm)
        assertTrue("finalized utterance must not be empty", finalizedPcm!!.isNotEmpty())
    }

    @Test
    fun deterministicUtterance_WithCustomPreRoll_EmitsNonEmptyBuffer() {
        val vad = Vad(energyThreshold = 0.05)
        val accumulator = UtteranceAccumulator(
            sampleRate = 16000,
            preRollMs = 100,
            stopTrigger = ManualStopTrigger(),
            manualManualMaxDurationMs = 4000,
            manualManualAbnormalSilenceMs = 5000
        )

        val speechFrame = FloatArray(320) { 0.2f }
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
    fun forceFinalize_WithOnlyPreRoll_ReturnsNonEmptyBuffer() {
        val accumulator = UtteranceAccumulator(
            sampleRate = 16000,
            stopTrigger = ManualStopTrigger(),
            manualManualMaxDurationMs = 4000,
            manualManualAbnormalSilenceMs = 5000
        )

        val speechFrame = FloatArray(320) { 0.2f }
        for (i in 0 until 15) {
            accumulator.processChunk(speechFrame, true)
        }

        val result = accumulator.forceFinalize()
        assertNotNull("forceFinalize must return buffered speech", result)
        assertTrue("forceFinalize result must not be empty", result!!.isNotEmpty())
    }

    @Test
    fun deterministicUtterance_WithSilenceFinalization_ReturnsAbnormalTerminate() {
        val accumulator = UtteranceAccumulator(
            sampleRate = 16000,
            stopTrigger = ManualStopTrigger(),
            manualManualMaxDurationMs = 10000,
            manualManualAbnormalSilenceMs = 40
        )

        val speechFrame = FloatArray(320) { 0.2f }
        val silenceFrame = FloatArray(320) { 0.0f }

        for (i in 0 until 5) {
            accumulator.processChunk(speechFrame, false)
        }
        for (i in 0 until 35) {
            accumulator.processChunk(speechFrame, true)
        }

        accumulator.processChunk(silenceFrame, false)
        val result = accumulator.processChunk(silenceFrame, false)

        assertTrue("Abnormal silence must return AbnormalTerminate",
            result is FrameResult.AbnormalTerminate)
        val terminate = result as FrameResult.AbnormalTerminate
        assertTrue("Code must be SILENCE_TIMEOUT",
            terminate.code == SttReturnCode.SILENCE_TIMEOUT)
    }
}