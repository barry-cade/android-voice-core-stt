package dev.barrycade.voicecore.stt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Tests for [DrainMode] behaviour via [FakeCaptureManager].
 *
 * Validates that:
 * - [DrainMode.DRAIN_FROM_HEAD] includes pre-existing queue frames in the session buffer
 * - [DrainMode.DRAIN_FROM_NEXT_FRAME] discards pre-existing queue frames
 *
 * Uses [FakeCaptureManager] because [CaptureManager] depends on Android
 * [android.media.AudioRecord] which is not available in pure JVM tests.
 */
class DrainModeTest {

    private lateinit var captureManager: FakeCaptureManager

    @Before
    fun setUp() {
        captureManager = FakeCaptureManager()
    }

    @Test
    fun drainFromHead_includesPreExistingQueueFrames() {
        // Given: frames are queued before begin() is called.
        val preFrame1 = floatArrayOf(0.1f, 0.2f, 0.3f)
        val preFrame2 = floatArrayOf(0.4f, 0.5f, 0.6f)
        captureManager.addFrame(preFrame1)
        captureManager.addFrame(preFrame2)

        // When: begin(DRAIN_FROM_HEAD) starts a session.
        captureManager.begin(DrainMode.DRAIN_FROM_HEAD)

        // Then: finalize returns all pre-existing frames.
        val pcm = captureManager.finalize()
        val expected = preFrame1 + preFrame2
        assertArrayEquals(
            "DRAIN_FROM_HEAD must include pre-existing queue frames",
            expected, pcm, 0.001f
        )
    }

    @Test
    fun drainFromNextFrame_excludesPreExistingQueueFrames() {
        // Given: frames are queued before begin() is called.
        val preFrame1 = floatArrayOf(0.1f, 0.2f, 0.3f)
        val preFrame2 = floatArrayOf(0.4f, 0.5f, 0.6f)
        captureManager.addFrame(preFrame1)
        captureManager.addFrame(preFrame2)

        // When: begin(DRAIN_FROM_NEXT_FRAME) starts a session.
        captureManager.begin(DrainMode.DRAIN_FROM_NEXT_FRAME)

        // Then: finalize returns empty — pre-existing frames are discarded.
        val pcm = captureManager.finalize()
        assertEquals(
            "DRAIN_FROM_NEXT_FRAME must discard pre-existing queue frames",
            0, pcm.size
        )
    }

    @Test
    fun drainFromHead_includesBothPreAndPostBeginFrames() {
        // Given: pre-existing frames in the queue.
        val preFrame = floatArrayOf(0.1f, 0.2f, 0.3f)
        captureManager.addFrame(preFrame)

        // When: begin(DRAIN_FROM_HEAD) starts a session and a new frame arrives.
        captureManager.begin(DrainMode.DRAIN_FROM_HEAD)
        val postFrame = floatArrayOf(0.7f, 0.8f, 0.9f)
        captureManager.addFrame(postFrame)

        // Then: finalize returns both pre and post frames.
        val pcm = captureManager.finalize()
        assertEquals(
            "DRAIN_FROM_HEAD must include both pre and post begin frames",
            6, pcm.size
        )
        assertArrayEquals(
            "DRAIN_FROM_HEAD must concatenate pre and post frames in order",
            preFrame + postFrame, pcm, 0.001f
        )
    }

    @Test
    fun drainFromNextFrame_onlyIncludesPostBeginFrames() {
        // Given: pre-existing frames in the queue.
        captureManager.addFrame(floatArrayOf(0.1f, 0.2f, 0.3f))

        // When: begin(DRAIN_FROM_NEXT_FRAME) starts a session and a new frame arrives.
        captureManager.begin(DrainMode.DRAIN_FROM_NEXT_FRAME)
        val postFrame = floatArrayOf(0.7f, 0.8f, 0.9f)
        captureManager.addFrame(postFrame)

        // Then: finalize includes only the post-begin frame.
        val pcm = captureManager.finalize()
        assertArrayEquals(
            "DRAIN_FROM_NEXT_FRAME must include only post-begin frames",
            postFrame, pcm, 0.001f
        )
    }

    @Test
    fun drainFromHead_emptyQueue_returnsNoPreFrames() {
        // Given: empty queue before begin().
        // When: begin(DRAIN_FROM_HEAD) starts a session.
        captureManager.begin(DrainMode.DRAIN_FROM_HEAD)

        // Then: finalize returns empty (no pre or post frames).
        val pcm = captureManager.finalize()
        assertEquals(
            "DRAIN_FROM_HEAD with empty queue must return empty",
            0, pcm.size
        )
    }

    @Test
    fun drainFromNextFrame_emptyQueue_returnsNoPreFrames() {
        // Given: empty queue before begin().
        // When: begin(DRAIN_FROM_NEXT_FRAME) starts a session.
        captureManager.begin(DrainMode.DRAIN_FROM_NEXT_FRAME)

        // Then: finalize returns empty.
        val pcm = captureManager.finalize()
        assertEquals(
            "DRAIN_FROM_NEXT_FRAME with empty queue must return empty",
            0, pcm.size
        )
    }
}

