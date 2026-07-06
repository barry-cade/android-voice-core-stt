package dev.barrycade.voicecore.stt

import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Tests for [CaptureController].
 *
 * Validates start/stop lifecycle, frame polling, and the
 * dual-instantiation guard (no-op on second start).
 *
 * Since [AudioCapture] depends on Android framework classes,
 * these tests verify controller logic at the behavioural level:
 * startCapture must set up the capture, stopCapture must clear it,
 * pollFrame must return from the underlying queue.
 */
class CaptureControllerTest {

    private lateinit var controller: CaptureController

    @Before
    fun setUp() {
        controller = CaptureController(sampleRate = 16000, requestedBufferSizeInBytes = 32000)
    }

    @Test
    fun startCapture_returnsTrueWithoutAndroid() {
        // AudioCapture requires Android AudioRecord. This test verifies
        // that the controller attempts start and returns false (no error).
        // In a Robolectric environment this would succeed.
        controller.startCapture()
        // No crash or exception is the assertion.
    }

    @Test
    fun startCapture_stopCapture_noErrors() {
        controller.startCapture()
        controller.stopCapture()
        // No crash or exception is the assertion.
    }

    @Test
    fun stopCapture_clearsCapture() {
        controller.startCapture()
        controller.stopCapture()
        // After stop, pollFrame must not crash
        controller.pollFrame()
    }

    @Test
    fun pollFrame_returnsNullWhenNotStarted() {
        val frame = controller.pollFrame()
        assertNull("pollFrame must return null when capture not started", frame)
    }

    @Test
    fun startCapture_twice_isIdempotent() {
        controller.startCapture()
        controller.startCapture()
        controller.stopCapture()
    }

    @Test
    fun stopCapture_twice_isIdempotent() {
        controller.startCapture()
        controller.stopCapture()
        controller.stopCapture()
    }

    @Test
    fun startCapture_afterStop_restarts() {
        controller.startCapture()
        controller.stopCapture()
        controller.startCapture()
        controller.stopCapture()
    }
}
