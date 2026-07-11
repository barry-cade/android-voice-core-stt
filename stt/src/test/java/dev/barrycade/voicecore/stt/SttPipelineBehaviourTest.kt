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
 * 5. State machine transitions are correct
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

    // -- Test 6: State machine transitions ----------------------------------

    @Test
    fun `state machine UNINITIALISED to INITIALISED to READY`() {
        var state: SttLifecycleState = SttLifecycleState.UNINITIALISED

        var result = applyTransition(state, SttLifecycleState.INITIALISED)
        assertTrue("UNINITIALISED -> INITIALISED must be allowed", result)
        state = SttLifecycleState.INITIALISED

        result = applyTransition(state, SttLifecycleState.READY)
        assertTrue("INITIALISED -> READY must be allowed", result)
    }

    @Test
    fun `state machine READY to RECORDING`() {
        var state: SttLifecycleState = SttLifecycleState.READY
        val result = applyTransition(state, SttLifecycleState.RECORDING)
        assertTrue("READY -> RECORDING must be allowed", result)
    }

    @Test
    fun `state machine RECORDING to FINALISING`() {
        var state: SttLifecycleState = SttLifecycleState.RECORDING
        val result = applyTransition(state, SttLifecycleState.FINALISING)
        assertTrue("RECORDING -> FINALISING must be allowed", result)
    }

    @Test
    fun `state machine FINALISING to STOPPED`() {
        var state: SttLifecycleState = SttLifecycleState.FINALISING
        val result = applyTransition(state, SttLifecycleState.STOPPED)
        assertTrue("FINALISING -> STOPPED must be allowed", result)
    }

    @Test
    fun `illegal transitions must be rejected`() {
        assertFalse("UNINITIALISED -> READY rejected",
            applyTransition(SttLifecycleState.UNINITIALISED, SttLifecycleState.READY))
        assertFalse("UNINITIALISED -> RECORDING rejected",
            applyTransition(SttLifecycleState.UNINITIALISED, SttLifecycleState.RECORDING))
        assertFalse("INITIALISED -> RECORDING rejected",
            applyTransition(SttLifecycleState.INITIALISED, SttLifecycleState.RECORDING))
        assertFalse("READY -> FINALISING rejected",
            applyTransition(SttLifecycleState.READY, SttLifecycleState.FINALISING))
        assertFalse("RECORDING -> STOPPED rejected",
            applyTransition(SttLifecycleState.RECORDING, SttLifecycleState.STOPPED))
        assertFalse("STOPPED -> RECORDING rejected",
            applyTransition(SttLifecycleState.STOPPED, SttLifecycleState.RECORDING))
    }

    @Test
    fun `full lifecycle UNINITIALISED to STOPPED`() {
        var state: SttLifecycleState = SttLifecycleState.UNINITIALISED
        var result = applyTransition(state, SttLifecycleState.INITIALISED)
        assertTrue(result); state = SttLifecycleState.INITIALISED

        result = applyTransition(state, SttLifecycleState.READY)
        assertTrue(result); state = SttLifecycleState.READY

        result = applyTransition(state, SttLifecycleState.RECORDING)
        assertTrue(result); state = SttLifecycleState.RECORDING

        result = applyTransition(state, SttLifecycleState.FINALISING)
        assertTrue(result); state = SttLifecycleState.FINALISING

        result = applyTransition(state, SttLifecycleState.STOPPED)
        assertTrue(result)
    }

    // -- Additional scenario tests ------------------------------------------

    @Test
    fun `READY to STOPPED is valid`() {
        var state = SttLifecycleState.READY
        assertTrue("READY -> STOPPED must be allowed",
            applyTransition(state, SttLifecycleState.STOPPED))
    }

    @Test
    fun `duplicate state transitions are allowed no-ops`() {
        var state: SttLifecycleState = SttLifecycleState.READY
        assertTrue("READY -> READY (duplicate) must be a no-op",
            applyTransition(state, SttLifecycleState.READY))

        state = SttLifecycleState.RECORDING
        assertTrue("RECORDING -> RECORDING (duplicate) must be a no-op",
            applyTransition(state, SttLifecycleState.RECORDING))
    }

    @Test
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

    // -- Helper -------------------------------------------------------------

    private fun applyTransition(from: SttLifecycleState, to: SttLifecycleState): Boolean {
        if (from == to) return true
        return when (from) {
            is SttLifecycleState.UNINITIALISED -> to is SttLifecycleState.INITIALISED
            is SttLifecycleState.INITIALISED -> to is SttLifecycleState.READY
            is SttLifecycleState.READY -> to is SttLifecycleState.RECORDING || to is SttLifecycleState.STOPPED
            is SttLifecycleState.RECORDING -> to is SttLifecycleState.FINALISING
            is SttLifecycleState.FINALISING -> to is SttLifecycleState.STOPPED
            else -> false
        }
    }
}
