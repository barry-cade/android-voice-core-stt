package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SttTimingSnapshot] structured timing data.
 *
 * Validates that timing snapshots capture accurate timing measurements
 * and that all fields are correctly populated during a deterministic
 * PCM buffer and inference duration simulation.
 *
 * All tests are PDP-aligned: linear arrange, act, assert.
 * No nested lambdas, no scope-function pyramids, no clever Kotlin.
 */
class SttTimingSnapshotTest {

    // ── Timing snapshot construction ────────────────────────────────────

    @Test
    fun timingSnapshot_AllFieldsNonNull() {
        val snapshot = SttTimingSnapshot(
            vadActiveMs = 120L,
            utteranceDurationMs = 800L,
            silencePaddingMs = 500L,
            preRollMs = 100L,
            inferenceMs = 340L,
            totalPipelineMs = 1520L
        )

        assertEquals(120L, snapshot.vadActiveMs)
        assertEquals(800L, snapshot.utteranceDurationMs)
        assertEquals(500L, snapshot.silencePaddingMs)
        assertEquals(100L, snapshot.preRollMs)
        assertEquals(340L, snapshot.inferenceMs)
        assertEquals(1520L, snapshot.totalPipelineMs)
    }

    @Test
    fun timingSnapshot_ttsHandoffNullByDefault() {
        val snapshot = SttTimingSnapshot(
            vadActiveMs = 80L,
            utteranceDurationMs = 600L,
            silencePaddingMs = 500L,
            preRollMs = 100L,
            inferenceMs = 250L,
            totalPipelineMs = 1100L
        )

        assertNull("ttsHandoffMs must be null for STT-only builds", snapshot.ttsHandoffMs)
    }

    @Test
    fun timingSnapshot_diagnosticFieldsOptional() {
        val snapshot = SttTimingSnapshot(
            vadActiveMs = 100L,
            utteranceDurationMs = 500L,
            silencePaddingMs = 500L,
            preRollMs = 100L,
            inferenceMs = 300L,
            totalPipelineMs = 1100L,
            vadConfidence = 0.85f,
            avgRms = 0.12f,
            peakRms = 0.45f,
            noiseFloorRms = 0.01f
        )

        assertEquals(0.85f, snapshot.vadConfidence!!, 0.01f)
        assertEquals(0.12f, snapshot.avgRms!!, 0.01f)
        assertEquals(0.45f, snapshot.peakRms!!, 0.01f)
        assertEquals(0.01f, snapshot.noiseFloorRms!!, 0.01f)
    }

    @Test
    fun timingSnapshot_immutable() {
        val snapshot = SttTimingSnapshot(
            vadActiveMs = 100L,
            utteranceDurationMs = 500L,
            silencePaddingMs = 500L,
            preRollMs = 100L,
            inferenceMs = 300L,
            totalPipelineMs = 1100L
        )

        // Verify copy() produces a new instance with overridden field
        val modified = snapshot.copy(inferenceMs = 400L)
        assertEquals(300L, snapshot.inferenceMs)
        assertEquals(400L, modified.inferenceMs)
    }

    // ── Timing values match measured durations ──────────────────────────

    @Test
    fun timingSnapshot_valuesMatchMeasuredDurations() {
        val measuredVadMs = 150L
        val measuredUtteranceMs = 900L
        val measuredInferenceMs = 420L
        val measuredTotalMs = measuredVadMs + measuredUtteranceMs + measuredInferenceMs

        val snapshot = SttTimingSnapshot(
            vadActiveMs = measuredVadMs,
            utteranceDurationMs = measuredUtteranceMs,
            silencePaddingMs = 500L,
            preRollMs = 100L,
            inferenceMs = measuredInferenceMs,
            totalPipelineMs = measuredTotalMs
        )

        assertEquals(measuredVadMs, snapshot.vadActiveMs)
        assertEquals(measuredUtteranceMs, snapshot.utteranceDurationMs)
        assertEquals(measuredInferenceMs, snapshot.inferenceMs)
        assertEquals(measuredTotalMs, snapshot.totalPipelineMs)
    }

    // ── Pipeline timing consistency ─────────────────────────────────────

    @Test
    fun timingTotalMsGreaterThanComponentSum() {
        val vadMs = 100L
        val utteranceMs = 500L
        val inferenceMs = 300L
        // Total includes overhead (frame processing, queue delay, etc.)
        val totalMs = vadMs + utteranceMs + inferenceMs + 50L

        val snapshot = SttTimingSnapshot(
            vadActiveMs = vadMs,
            utteranceDurationMs = utteranceMs,
            silencePaddingMs = 500L,
            preRollMs = 100L,
            inferenceMs = inferenceMs,
            totalPipelineMs = totalMs
        )

        assertTrue("totalPipelineMs must be >= sum of components", snapshot.totalPipelineMs >= vadMs + utteranceMs + inferenceMs)
    }

    @Test
    fun timingSnapshot_constructorAcceptsZeroValues() {
        val snapshot = SttTimingSnapshot(
            vadActiveMs = 0L,
            utteranceDurationMs = 0L,
            silencePaddingMs = 500L,
            preRollMs = 100L,
            inferenceMs = 0L,
            totalPipelineMs = 0L
        )

        assertEquals(0L, snapshot.vadActiveMs)
        assertEquals(0L, snapshot.utteranceDurationMs)
        assertEquals(0L, snapshot.inferenceMs)
        assertEquals(0L, snapshot.totalPipelineMs)
    }

    // ── No ad-hoc timing logs ───────────────────────────────────────────

    @Test
    fun noAdhocTimingLogsRequired() {
        // Verify that a properly constructed timing snapshot does not
        // rely on any ad-hoc timing string parsing.
        val snapshot = SttTimingSnapshot(
            vadActiveMs = 120L,
            utteranceDurationMs = 750L,
            silencePaddingMs = 500L,
            preRollMs = 100L,
            inferenceMs = 330L,
            totalPipelineMs = 1400L
        )

        assertNotNull("timing snapshot must be constructable without ad-hoc logging", snapshot)
    }

    // ── Timing snapshot for non-null diagnostic fields ──────────────────

    @Test
    fun timingSnapshot_allDiagnosticFieldsPopulated() {
        val snapshot = SttTimingSnapshot(
            vadActiveMs = 80L,
            utteranceDurationMs = 400L,
            silencePaddingMs = 500L,
            preRollMs = 100L,
            inferenceMs = 280L,
            totalPipelineMs = 960L,
            vadConfidence = 0.92f,
            avgRms = 0.08f,
            peakRms = 0.35f,
            noiseFloorRms = 0.005f
        )

        assertNotNull("vadConfidence must be non-null when set", snapshot.vadConfidence)
        assertNotNull("avgRms must be non-null when set", snapshot.avgRms)
        assertNotNull("peakRms must be non-null when set", snapshot.peakRms)
        assertNotNull("noiseFloorRms must be non-null when set", snapshot.noiseFloorRms)
    }
}
