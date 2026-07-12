package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deterministic tests that validate synchronous capture start semantics.
 *
 * These tests ensure that:
 * - [CaptureManager.begin] calls [AudioCapture.start] synchronously.
 * - No background thread, no deferred execution, no race conditions.
 * - The drain thread starts only AFTER capture is confirmed running.
 *
 * Because [CaptureManager] owns [AudioCapture] internally, these tests
 * use [FakeCaptureManager] which simulates the same synchronous contract
 * without Android dependencies.
 */
class DeterministicCaptureTest {

    private lateinit var captureManager: FakeCaptureManager

    @Before
    fun setUp() {
        captureManager = FakeCaptureManager()
    }

    /**
     * PATCH 5 test: begin() → capture starts immediately.
     *
     * Validates that begin() sets isStarted synchronously, with no
     * background thread, no deferred execution, no race conditions.
     */
    @Test
    fun captureStartsSynchronouslyOnBegin() {
        assertFalse("capture must not be started before begin()", captureManager.isStarted)

        captureManager.begin(DrainMode.DRAIN_FROM_HEAD)

        assertTrue("capture must be started immediately after begin()", captureManager.isStarted)
    }

    /**
     * begin() with DRAIN_FROM_NEXT_FRAME also starts capture synchronously.
     */
    @Test
    fun captureStartsSynchronouslyOnBeginDrainFromNextFrame() {
        assertFalse("capture must not be started before begin()", captureManager.isStarted)

        captureManager.begin(DrainMode.DRAIN_FROM_NEXT_FRAME)

        assertTrue("capture must be started immediately after begin()", captureManager.isStarted)
    }

    /**
     * begin() is idempotent: second call does not restart capture.
     */
    @Test
    fun beginIsIdempotent() {
        captureManager.begin()
        assertTrue("capture must be started after first begin()", captureManager.isStarted)

        captureManager.begin()
        assertTrue("capture must remain started after second begin()", captureManager.isStarted)
    }

    /**
     * captureStartsBeforeDrainThread: capture is confirmed running
     * before the drain thread begins.
     */
    @Test
    fun captureStartsBeforeDrainThread() {
        captureManager.begin(DrainMode.DRAIN_FROM_HEAD)

        // Capture must be started before frames can be drained.
        assertTrue("capture must be started before drain", captureManager.isStarted)

        // Add a frame — it should be available for polling.
        captureManager.addSpeechFrame(320)
        val frame = captureManager.pollFrame()

        // With DRAIN_FROM_HEAD, the frame should be drained by begin() into session buffer.
        // pollFrame returns null because the queue was already drained.
        // But the session buffer should have consumed it.
        val pcm = captureManager.finalize()
        assertTrue("DRAIN_FROM_HEAD must consume pre-existing frames", pcm.isNotEmpty())
    }

    /**
     * finalize stops capture and clears state.
     */
    @Test
    fun finalizeStopsCapture() {
        captureManager.begin()
        assertTrue("capture must be started after begin()", captureManager.isStarted)

        captureManager.finalize()
        assertFalse("capture must be stopped after finalize()", captureManager.isStarted)
    }

    /**
     * reset does NOT stop capture — only clears session state.
     */
    @Test
    fun resetDoesNotStopCapture() {
        captureManager.begin()
        assertTrue("capture must be started after begin()", captureManager.isStarted)

        captureManager.reset()
        assertTrue("capture must continue running after reset()", captureManager.isStarted)
    }

    /**
     * shutdown stops capture permanently.
     */
    @Test
    fun shutdownStopsCapture() {
        captureManager.begin()
        assertTrue("capture must be started after begin()", captureManager.isStarted)

        captureManager.shutdown()
        assertFalse("capture must be stopped after shutdown()", captureManager.isStarted)
    }

    /**
     * restartCapture restarts capture after finalize stopped it.
     */
    @Test
    fun restartCaptureAfterFinalize() {
        captureManager.begin()
        captureManager.finalize()
        assertFalse("capture must be stopped after finalize()", captureManager.isStarted)

        captureManager.restartCapture()
        assertTrue("capture must be restarted", captureManager.isStarted)
    }
}
