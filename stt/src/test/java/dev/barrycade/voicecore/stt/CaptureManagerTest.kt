package dev.barrycade.voicecore.stt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [FakeCaptureManager] — the test double for [CaptureManager].
 *
 * Validates:
 * - Session lifecycle (begin, finalize, reset, shutdown)
 * - Dual-mode frame capture (drain thread / pollFrame buffering)
 * - AudioSource interface compliance
 * - Frame accumulation correctness
 *
 * Since [CaptureManager] depends on Android [AudioCapture], the production
 * class is tested indirectly through [SpeechToText] integration tests.
 * This test validates the fake that replaces it.
 */
class CaptureManagerTest {

    private lateinit var captureManager: FakeCaptureManager

    @Before
    fun setUp() {
        captureManager = FakeCaptureManager()
    }

    // ── Session lifecycle ─────────────────────────────────────────────────

    @Test
    fun begin_clearsSessionBuffer() {
        // begin should clear the session buffer from a previous session.
        captureManager.begin()
        // Directly add to session buffer via pollFrame.
        captureManager.isStarted = true
        captureManager.addSpeechFrame(320)
        captureManager.pollFrame()
        // Now begin() again — should clear the buffer.
        captureManager.begin()
        // Add another frame and poll.
        captureManager.addSpeechFrame(320)
        captureManager.pollFrame()
        val pcm = captureManager.finalize()
        // Only the frame after the second begin should be in the buffer.
        assertEquals("begin must clear session buffer, got size=" + pcm.size, 320, pcm.size)
    }

    @Test
    fun begin_startsSessionActive() {
        assertFalse(captureManager.sessionActive)
        captureManager.begin()
        assertTrue(captureManager.sessionActive)
    }

    @Test
    fun finalize_returnsAllAccumulatedFrames() {
        captureManager.begin()
        captureManager.addSpeechFrame(320)
        captureManager.addSpeechFrame(320)
        captureManager.addSpeechFrame(320)
        val pcm = captureManager.finalize()
        assertNotNull("finalize must return PCM", pcm)
        assertEquals("PCM size must be 3 frames * 320 samples", 960, pcm.size)
    }

    @Test
    fun finalize_clearsBuffer() {
        captureManager.begin()
        captureManager.addSpeechFrame(320)
        captureManager.finalize()
        val pcm = captureManager.finalize()
        assertEquals("second finalize must return empty array", 0, pcm.size)
    }

    @Test
    fun finalize_drainsRemainingQueueFrames() {
        captureManager.begin()
        captureManager.addSpeechFrame(320)
        // Add frames after begin (these go to queue)
        captureManager.addSpeechFrame(320)
        captureManager.addSpeechFrame(320)
        // Without drain thread, these should be returned by finalize
        val pcm = captureManager.finalize()
        // begin starts drain thread (simulated by pollFrame buffering).
        // Since we're using FakeCaptureManager, frames added after begin
        // are only in the queue, not in the session buffer.
        // Finalize should drain them.
        assertTrue("finalize must drain queue frames", pcm.isNotEmpty())
    }

    @Test
    fun finalize_withNoFrames_returnsEmpty() {
        captureManager.begin()
        val pcm = captureManager.finalize()
        assertEquals("finalize with no frames must return empty array", 0, pcm.size)
    }

    @Test
    fun reset_clearsSessionAndQueue() {
        captureManager.begin()
        captureManager.addSpeechFrame(320)
        captureManager.addSpeechFrame(320)
        captureManager.reset()
        val pcm = captureManager.finalize()
        assertEquals("reset must clear everything", 0, pcm.size)
    }

    @Test
    fun reset_idempotent() {
        captureManager.reset()
        captureManager.reset()
        captureManager.reset()
        // No crash is the assertion
    }

    @Test
    fun shutdown_clearsEverything() {
        captureManager.begin()
        captureManager.addSpeechFrame(320)
        captureManager.addSpeechFrame(320)
        captureManager.isStarted = true
        captureManager.pollFrame()
        captureManager.shutdown()
        assertFalse(captureManager.sessionActive)
        val pcm = captureManager.finalize()
        assertEquals("shutdown must clear buffer", 0, pcm.size)
    }

    @Test
    fun shutdown_idempotent() {
        captureManager.shutdown()
        captureManager.shutdown()
        // No crash is the assertion
    }

    // ── pollFrame buffering ──────────────────────────────────────────────

    @Test
    fun pollFrame_buffersFrameIntoSession() {
        captureManager.isStarted = true
        captureManager.begin()
        captureManager.addSpeechFrame(320)
        val frame = captureManager.pollFrame()
        assertNotNull("pollFrame must return frame", frame)
        // Frame should be in session buffer
        val pcm = captureManager.finalize()
        assertEquals("pollFrame must buffer into session", 320, pcm.size)
    }

    @Test
    fun pollFrame_withoutSession_doesNotBuffer() {
        captureManager.isStarted = true
        captureManager.addSpeechFrame(320)
        val frame = captureManager.pollFrame()
        assertNotNull("pollFrame must return frame", frame)
        val pcm = captureManager.finalize()
        assertEquals("pollFrame without session must not buffer", 0, pcm.size)
    }

    @Test
    fun pollFrame_multipleCalls_buffersAll() {
        captureManager.isStarted = true
        captureManager.begin()
        captureManager.addSpeechFrame(320)
        captureManager.addSpeechFrame(320)
        captureManager.addSpeechFrame(320)
        captureManager.pollFrame()
        captureManager.pollFrame()
        captureManager.pollFrame()
        val pcm = captureManager.finalize()
        assertEquals("pollFrame must buffer all frames", 960, pcm.size)
    }

    // ── AudioSource interface ───────────────────────────────────────────

    @Test
    fun startCapture_returnsTrue() {
        assertTrue(captureManager.startCapture())
    }

    @Test
    fun startCapture_failOnStart_returnsFalse() {
        captureManager.failOnStart = true
        assertFalse(captureManager.startCapture())
    }

    @Test
    fun stopCapture_setsIsStartedFalse() {
        captureManager.startCapture()
        assertTrue(captureManager.isStarted)
        captureManager.stopCapture()
        assertFalse(captureManager.isStarted)
    }

    @Test
    fun pollFrame_returnsNullWhenNotStarted() {
        val frame = captureManager.pollFrame()
        assertNull("pollFrame must return null when not started", frame)
    }

    @Test
    fun clearQueue_discardsPendingFrames() {
        captureManager.addSpeechFrame(320)
        captureManager.addSpeechFrame(320)
        captureManager.clearQueue()
        captureManager.isStarted = true
        val frame = captureManager.pollFrame()
        assertNull("clearQueue must discard frames", frame)
    }

    @Test
    fun startCapture_twice_isIdempotent() {
        captureManager.startCapture()
        captureManager.startCapture()
        captureManager.stopCapture()
    }

    @Test
    fun stopCapture_twice_isIdempotent() {
        captureManager.startCapture()
        captureManager.stopCapture()
        captureManager.stopCapture()
    }

    // ── PCM correctness ─────────────────────────────────────────────────

    @Test
    fun pcm_containsConcatenatedFrames() {
        captureManager.begin()
        val frame1 = floatArrayOf(0.1f, 0.2f, 0.3f)
        val frame2 = floatArrayOf(0.4f, 0.5f, 0.6f)
        captureManager.addFrame(frame1)
        captureManager.addFrame(frame2)
        captureManager.isStarted = true
        captureManager.pollFrame()
        captureManager.pollFrame()
        val pcm = captureManager.finalize()
        assertArrayEquals("PCM must be concatenated frames", floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f), pcm, 0.001f)
    }
}
