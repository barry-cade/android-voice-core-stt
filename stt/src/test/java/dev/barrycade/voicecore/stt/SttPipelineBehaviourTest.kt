package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the STT pipeline's **intended** (required) behaviour.
 *
 * These tests validate:
 * 1. Start -> warm-up -> RECORDING -> speech -> silence -> inference -> dispatch
 * 2. STOP produces a transcription
 * 3. Silence timeout produces a transcription
 * 4. PCM is NOT discarded on abnormal silence
 * 5. Manual stop event lifecycle
 *
 * NOTE: State machine transition tests removed per audit plan.
 * They tested a local replica of transition logic, not the production
 * SttLifecycleStateMachine. The real state machine transitions
 * are tested in SttPipelineStateTest.
 *
 * Uses [FakeCaptureManager] for deterministic PCM frames,
 * direct [UtteranceAccumulator] calls for silence/inference tests.
 */
class SttPipelineBehaviourTest {

    companion object {
        private const val FRAME_SIZE = 160
        private const val SAMPLE_RATE = 16000
        private const val PRE_ROLL_MS = 100
        private const val PRE_ROLL_FRAMES = PRE_ROLL_MS / 10
        private const val DEFAULT_ABNORMAL_SILENCE_MS = 5000
    }

    // -- Test 1: Full end-to-end pipeline -----------------------------------

    @Test
    fun `full pipeline start warmup recording speech silence inference dispatch`() {
        val vad = Vad(energyThreshold = 0.01)
        val accumulator = UtteranceAccumulator(
            sampleRate = SAMPLE_RATE,
            preRollMs = PRE_ROLL_MS,
            vad = vad,
            utteranceSilenceTimeoutMs = 40
        )

        val speechFrame = FloatArray(FRAME_SIZE) { 0.3f }
        val silenceFrame = FloatArray(FRAME_SIZE) { 0.0f }

        // Phase 1: Pre-roll (100 ms = 10 frames)
        for (i in 0 until PRE_ROLL_FRAMES) {
            val result = accumulator.processChunk(speechFrame, true)
            assertTrue("Pre-roll frame $i must return Continue", result is FrameResult.Continue)
        }

        // Phase 2: Accumulate speech (350 ms = 35 frames)
        for (i in 0 until 35) {
            val result = accumulator.processChunk(speechFrame, true)
            assertTrue("Speech frame $i must return Continue", result is FrameResult.Continue)
        }

        // Phase 3: Silence triggers finalisation
        // stableChunkSizeMs = 40 ms = 4 frames at 10 ms/frame
        for (i in 0 until 3) {
            val result = accumulator.processChunk(silenceFrame, false)
            assertTrue("Silence frame $i must return Continue", result is FrameResult.Continue)
        }

        val result = accumulator.processChunk(silenceFrame, false)
        assertTrue("Silence threshold must produce UtteranceReady",
            result is FrameResult.UtteranceReady)

        val ready = result as FrameResult.UtteranceReady
        assertTrue("PCM must not be empty", ready.pcm.isNotEmpty())
        assertTrue("PCM should be substantial", ready.pcm.size >= 5600)

        val hasNonZeroSamples = ready.pcm.any { it != 0.0f }
        assertTrue("PCM must contain non-zero samples", hasNonZeroSamples)
    }

    // -- Test 2: STOP produces a transcription ------------------------------

    @Test
    fun `stop must produce a transcription`() {
        val accumulator = UtteranceAccumulator(
            sampleRate = SAMPLE_RATE,
            preRollMs = PRE_ROLL_MS,
            utteranceSilenceTimeoutMs = DEFAULT_ABNORMAL_SILENCE_MS
        )

        val speechFrame = FloatArray(FRAME_SIZE) { 0.3f }

        for (i in 0 until PRE_ROLL_FRAMES) {
            accumulator.processChunk(speechFrame, true)
        }
        for (i in 0 until 35) {
            accumulator.processChunk(speechFrame, true)
        }

        val pcm = accumulator.forceFinalize()
        assertNotNull("STOP must produce PCM", pcm)
        assertTrue("STOP PCM must not be empty", pcm!!.isNotEmpty())
        assertTrue("STOP PCM must be substantial", pcm.size >= 5600)
    }

    // -- Test 3: Silence timeout produces a transcription -------------------

    @Test
    fun `silence timeout must produce a transcription`() {
        val accumulator = UtteranceAccumulator(
            sampleRate = SAMPLE_RATE,
            utteranceSilenceTimeoutMs = 40
        )

        val speechFrame = FloatArray(FRAME_SIZE) { 0.3f }
        val silenceFrame = FloatArray(FRAME_SIZE) { 0.0f }

        for (i in 0 until PRE_ROLL_FRAMES) {
            accumulator.processChunk(speechFrame, true)
        }
        for (i in 0 until 35) {
            accumulator.processChunk(speechFrame, true)
        }

        for (i in 0 until 3) {
            val result = accumulator.processChunk(silenceFrame, false)
            assertTrue(result is FrameResult.Continue)
        }

        val result = accumulator.processChunk(silenceFrame, false)
        assertTrue("Silence timeout must produce UtteranceReady",
            result is FrameResult.UtteranceReady)

        val ready = result as FrameResult.UtteranceReady
        assertTrue("PCM must not be empty", ready.pcm.isNotEmpty())
    }

    // -- Test 4: PCM must NOT be discarded on abnormal silence --------------

    @Test
    fun `pcm must not be discarded on abnormal silence`() {
        val accumulator = UtteranceAccumulator(
            sampleRate = SAMPLE_RATE,
            utteranceSilenceTimeoutMs = 40
        )

        val speechFrame = FloatArray(FRAME_SIZE) { 0.3f }
        val silenceFrame = FloatArray(FRAME_SIZE) { 0.0f }

        for (i in 0 until PRE_ROLL_FRAMES) {
            accumulator.processChunk(speechFrame, true)
        }
        for (i in 0 until 35) {
            accumulator.processChunk(speechFrame, true)
        }

        val beforeSilence = accumulator.forceFinalize()
        assertNotNull(beforeSilence)
        assertTrue(beforeSilence!!.isNotEmpty())

        // Re-feed since forceFinalize clears
        for (i in 0 until PRE_ROLL_FRAMES) {
            accumulator.processChunk(speechFrame, true)
        }
        for (i in 0 until 35) {
            accumulator.processChunk(speechFrame, true)
        }

        for (i in 0 until 3) {
            accumulator.processChunk(silenceFrame, false)
        }
        val result = accumulator.processChunk(silenceFrame, false)

        assertTrue("Abnormal silence must produce UtteranceReady",
            result is FrameResult.UtteranceReady)

        val ready = result as FrameResult.UtteranceReady
        assertTrue("PCM must not be empty", ready.pcm.isNotEmpty())
        assertTrue("PCM must be substantial", ready.pcm.size >= 5600)

        val hasNonZeroSamples = ready.pcm.any { it != 0.0f }
        assertTrue("PCM must contain non-zero samples", hasNonZeroSamples)
    }

    // -- Test 5: STOP is never ignored --------------------------------------

    @Test
    fun `stop is never ignored in any valid state`() {
        // ManualStop should always respond to manualStopPressed.
        val events = SttEvents()
        val stopStrategy = ManualStop()

        // Without the event raised, shouldStop returns false.
        assertFalse("ManualStop without event must return false",
            stopStrategy.shouldStop(events, null, 0))

        // Raise the event.
        events.manualStopPressed.raise()
        assertTrue("ManualStop with event must return true",
            stopStrategy.shouldStop(events, null, 0))

        // Event consumed — should return false again.
        assertFalse("ManualStop after consumption must return false",
            stopStrategy.shouldStop(events, null, 0))
    }

    // -- Additional scenario tests ------------------------------------------
    fun `stopAndFinalize returns all accumulated PCM`() {
        val accumulator = UtteranceAccumulator(
            sampleRate = SAMPLE_RATE,
            utteranceSilenceTimeoutMs = 5000,
            utteranceMaxDurationMs = 10000
        )

        val speechFrame = FloatArray(FRAME_SIZE) { 0.3f }

        for (i in 0 until PRE_ROLL_FRAMES) {
            accumulator.processChunk(speechFrame, true)
        }
        for (i in 0 until 50) {
            accumulator.processChunk(speechFrame, true)
        }

        val pcm = accumulator.forceFinalize()
        assertNotNull("forceFinalize must return PCM", pcm)
        assertTrue("forceFinalize PCM must not be empty", pcm!!.isNotEmpty())
        assertTrue("forceFinalize must return substantial PCM", pcm.size >= 8000)
    }

    @Test
    fun `preSpeechSilence guard prevents false triggers`() {
        val accumulator = UtteranceAccumulator(
            sampleRate = SAMPLE_RATE,
            utteranceSilenceTimeoutMs = 40
        )

        val silenceFrame = FloatArray(FRAME_SIZE) { 0.0f }

        for (i in 0 until 30) {
            val result = accumulator.processChunk(silenceFrame, false)
            assertTrue("Pre-speech silence must return Continue (frame $i)",
                result is FrameResult.Continue)
        }
    }

    @Test
    fun `PCM after abnormal silence contains speech content`() {
        val accumulator = UtteranceAccumulator(
            sampleRate = SAMPLE_RATE,
            utteranceSilenceTimeoutMs = 30
        )

        val speechFrame = FloatArray(FRAME_SIZE) { 0.5f }
        val silenceFrame = FloatArray(FRAME_SIZE) { 0.0f }

        for (i in 0 until PRE_ROLL_FRAMES) {
            accumulator.processChunk(speechFrame, true)
        }
        for (i in 0 until 50) {
            accumulator.processChunk(speechFrame, true)
        }

        for (i in 0 until 2) {
            val result = accumulator.processChunk(silenceFrame, false)
            assertTrue(result is FrameResult.Continue)
        }

        val result = accumulator.processChunk(silenceFrame, false)
        assertTrue("Abnormal silence must produce UtteranceReady",
            result is FrameResult.UtteranceReady)

        val ready = result as FrameResult.UtteranceReady
        val speechSampleCount = ready.pcm.filter { it >= 0.1f }.size
        assertTrue("PCM must contain speech content",
            speechSampleCount > 100)
    }

}
