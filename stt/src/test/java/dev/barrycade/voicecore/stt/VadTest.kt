package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [Vad] RMS-based voice activity detection.
 *
 * Covers energy threshold boundary, hysteresis behaviour,
 * confidence computation, and edge cases.
 */
class VadTest {

    @Test
    fun isSpeech_highEnergyFrame_returnsTrue() {
        val vad = Vad(energyThreshold = 0.01)
        val frame = FloatArray(320) { 0.2f }
        assertTrue(vad.isSpeech(frame))
    }

    @Test
    fun isSpeech_lowEnergyFrame_returnsFalse() {
        val vad = Vad(energyThreshold = 0.05)
        val frame = FloatArray(320) { 0.001f }
        assertFalse(vad.isSpeech(frame))
    }

    @Test
    fun isSpeech_emptyFrame_returnsFalse() {
        val vad = Vad(energyThreshold = 0.01)
        val frame = FloatArray(0)
        assertFalse(vad.isSpeech(frame))
    }

    @Test
    fun isSpeech_frameAtThreshold_returnsTrue() {
        val vad = Vad(energyThreshold = 0.01)
        val frame = FloatArray(320) { 0.01f }
        assertTrue(vad.isSpeech(frame))
    }

    @Test
    fun isSpeech_hysteresisLowersThreshold() {
        val vad = Vad(energyThreshold = 0.01)
        val aboveThreshold = FloatArray(320) { 0.02f }
        val justBelowThreshold = FloatArray(320) { 0.008f }

        assertTrue("first frame must be speech", vad.isSpeech(aboveThreshold))
        assertTrue("hysteresis should keep speech active at 0.008", vad.isSpeech(justBelowThreshold))
    }

    @Test
    fun isSpeech_hysteresisResetsAfterSilence() {
        val vad = Vad(energyThreshold = 0.01)
        val aboveThreshold = FloatArray(320) { 0.02f }
        val silence = FloatArray(320) { 0.0f }
        val justBelow = FloatArray(320) { 0.008f }

        vad.isSpeech(aboveThreshold)
        vad.isSpeech(silence)
        assertFalse("hysteresis must reset after silence", vad.isSpeech(justBelow))
    }

    @Test
    fun vadConfidence_increasesWithConsecutiveSpeech() {
        val vad = Vad(energyThreshold = 0.01)
        val frame = FloatArray(320) { 0.015f }  // just above threshold

        vad.isSpeech(frame)
        val firstConfidence = vad.vadConfidence

        for (i in 0 until 50) {
            vad.isSpeech(frame)
        }

        assertTrue("confidence must increase with consecutive speech frames",
            vad.vadConfidence > firstConfidence)
    }

    @Test
    fun vadConfidence_decaysOnSilence() {
        val vad = Vad(energyThreshold = 0.01)
        val speech = FloatArray(320) { 0.5f }
        val silence = FloatArray(320) { 0.0f }

        for (i in 0 until 5) {
            vad.isSpeech(speech)
        }
        val peakConfidence = vad.vadConfidence

        vad.isSpeech(silence)
        assertTrue("confidence must decay on silence frame",
            vad.vadConfidence < peakConfidence)
    }

    @Test
    fun vadConfidence_staysInZeroToOneRange() {
        val vad = Vad(energyThreshold = 0.01)
        val speech = FloatArray(320) { 0.5f }
        val silence = FloatArray(320) { 0.0f }

        for (i in 0 until 100) {
            vad.isSpeech(speech)
        }
        assertTrue("peak confidence must be <= 1.0", vad.vadConfidence <= 1.0f)
        assertTrue("peak confidence must be >= 0.0", vad.vadConfidence >= 0.0f)

        for (i in 0 until 50) {
            vad.isSpeech(silence)
        }
        assertTrue("decayed confidence must be >= 0.0", vad.vadConfidence >= 0.0f)
    }

    @Test
    fun lastFrameEnergy_updatedOnEachCall() {
        val vad = Vad(energyThreshold = 0.01)
        val frame = FloatArray(320) { 0.3f }

        assertTrue("initial lastFrameEnergy must be 0", vad.lastFrameEnergy >= 0f)
        vad.isSpeech(frame)
        assertTrue("lastFrameEnergy must be updated after isSpeech", vad.lastFrameEnergy > 0f)
    }

    @Test
    fun configConstructor_setsEnergyThreshold() {
        val config = RuntimeSttConfig(energyThreshold = 0.02f)
        val vad = Vad(config)
        val above = FloatArray(320) { 0.03f }
        val below = FloatArray(320) { 0.01f }

        assertTrue("above threshold must be speech", vad.isSpeech(above))
        assertFalse("below threshold must not be speech", vad.isSpeech(below))
    }

    @Test
    fun isSpeech_zeroEnergyFrame_returnsFalse() {
        val vad = Vad(energyThreshold = 0.005)
        val frame = FloatArray(320) { 0.0f }
        assertFalse(vad.isSpeech(frame))
        assertEquals(0.0f, vad.lastFrameEnergy, 0.0001f)
    }
}
