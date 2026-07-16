package dev.barrycade.voicecore.stt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [VadGate].
 *
 * Covers energy-threshold classification, edge cases (empty frames,
 * boundary values), and custom threshold configuration.
 */
class VadGateTest {

    private val defaultGate = VadGate()

    @Test
    fun `allZeros_notSpeech`() {
        assertFalse(defaultGate.isSpeech(FloatArray(160) { 0f }))
    }

    @Test
    fun `allSilence_notSpeech`() {
        // Values below default 0.005 threshold
        assertFalse(defaultGate.isSpeech(FloatArray(160) { 0.001f }))
    }

    @Test
    fun `speechLevel_isSpeech`() {
        assertTrue(defaultGate.isSpeech(FloatArray(160) { 0.1f }))
    }

    @Test
    fun `aboveThreshold_isSpeech`() {
        // RMS ≈ 0.01 ≥ 0.005
        assertTrue(defaultGate.isSpeech(floatArrayOf(0.01f, -0.01f)))
    }

    @Test
    fun `emptyFrame_notSpeech`() {
        assertFalse(defaultGate.isSpeech(FloatArray(0)))
    }

    @Test
    fun `customThreshold_respected`() {
        val highThresholdGate = VadGate(energyThreshold = 0.1f)
        // 0.05f amplitude → RMS = 0.05 < 0.1
        assertFalse(highThresholdGate.isSpeech(FloatArray(160) { 0.05f }))
    }

    @Test
    fun `customThreshold_atBoundary_isSpeech`() {
        val gate = VadGate(energyThreshold = 0.01f)
        // RMS exactly 0.01
        assertTrue(gate.isSpeech(floatArrayOf(0.01f)))
    }

    @Test
    fun `singleSampleAboveThreshold_isSpeech`() {
        val gate = VadGate(energyThreshold = 0.005f)
        // RMS = sqrt(0.006^2) = 0.006 ≥ 0.005
        assertTrue(gate.isSpeech(floatArrayOf(0.006f)))
    }

    @Test
    fun `mixedSilenceAndSpeech_aboveThreshold`() {
        val gate = VadGate(energyThreshold = 0.005f)
        // RMS ≈ sqrt((0 + 0.01^2) / 2) = sqrt(0.0001/2) = 0.00707 ≥ 0.005
        assertTrue(gate.isSpeech(floatArrayOf(0f, 0.01f)))
    }

    @Test
    fun `veryLowThreshold_classifiesLowEnergyAsSpeech`() {
        val verySensitiveGate = VadGate(energyThreshold = 0.0001f)
        assertTrue(verySensitiveGate.isSpeech(FloatArray(160) { 0.0005f }))
    }
}
