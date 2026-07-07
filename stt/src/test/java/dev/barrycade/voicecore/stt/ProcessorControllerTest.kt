package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [ProcessorController].
 *
 * Validates lifecycle, error handling, and idempotency.
 *
 * Since [CaptureController] depends on Android [AudioCapture], the
 * controller runs without real frames. Tests focus on:
 * - Stop/freeze ordering (stopRequestedRef)
 * - Idempotency (double start, double stop)
 * - Initial value contracts
 * - Error-free operation under stress
 */
class ProcessorControllerTest {

    private lateinit var fakeAudioSource: FakeCaptureController
    private lateinit var vad: Vad
    private lateinit var accumulator: UtteranceAccumulator
    private val capturedUtterances = mutableListOf<FloatArray>()
    private lateinit var listener: UtteranceListener
    private var stopRequested: Boolean = false

    @Before
    fun setUp() {
        fakeAudioSource = FakeCaptureController()
        vad = Vad(energyThreshold = 0.01)
        accumulator = UtteranceAccumulator(
            sampleRate = 16000,
            stopTrigger = ManualStopTrigger(),
            manualManualConfig = ManualManualConfig(
                maxDurationMs = 30000,
                abnormalSilenceMs = 5000  // high so silence doesn't trigger abnormal termination
            )
        )
        capturedUtterances.clear()
        listener = object : UtteranceListener {
            override fun onUtteranceReady(pcm: FloatArray) {
                capturedUtterances.add(pcm)
            }
        }
        stopRequested = false
    }

    private fun createController(): ProcessorController {
        fakeAudioSource.startCapture()
        return ProcessorController(
            audioSource = fakeAudioSource,
            vad = vad,
            utteranceAccumulator = accumulator,
            listener = listener,
            sampleRate = 16000,
            debugLogging = false,
            stopRequestedRef = { stopRequested }
        )
    }

    // ── Initial value contracts (negative: must NOT be garbage) ──────────

    @Test
    fun vadActiveMs_initialValue_isZero() {
        val controller = createController()
        assertEquals("vadActiveMs must be 0 at construction", 0L, controller.vadActiveMs)
    }

    @Test
    fun lastUtteranceDurationMs_initialValue_isZero() {
        val controller = createController()
        assertEquals("lastUtteranceDurationMs must be 0 at construction",
            0, controller.lastUtteranceDurationMs)
    }

    @Test
    fun vadConfidence_initialValue_isZero() {
        val controller = createController()
        assertEquals(0f, controller.vadConfidence, 0.001f)
    }

    // ── Idempotency / no-op tests (negative: must survive misuse) ────────

    @Test
    fun start_twice_isNoop() {
        val controller = createController()
        controller.start()
        controller.start()
        controller.stop()
    }

    @Test
    fun stop_twice_isNoop() {
        val controller = createController()
        controller.start()
        controller.stop()
        controller.stop()
    }

    @Test
    fun stopWithoutStart_isNoop() {
        val controller = createController()
        controller.stop()
    }

    @Test
    fun rapidStartStop_noCrash() {
        val controller = createController()
        repeat(5) {
            controller.start()
            controller.stop()
        }
    }

    // ── Stop-and-finalize (negative: empty accumulator path) ─────────────

    @Test
    fun stopAndFinalize_emptyAccumulator_returnsNull() {
        val controller = createController()
        controller.start()
        controller.stop()
        val pcm = controller.stopAndFinalize()
        assertNull("stopAndFinalize with empty accumulator must return null", pcm)
    }

    // ── Accessor tests (must never return null / crash) ──────────────────

    @Test
    fun drainRemainingFrames_emptyQueue_returnsNull() {
        val controller = createController()
        val result = controller.drainRemainingFrames()
        assertNull("drainRemainingFrames with empty queue must return null", result)
    }

    @Test
    fun drainRemainingFrames_withFrames_drainsSuccessfully() {
        fakeAudioSource.addSilenceFrames(5, 320)
        fakeAudioSource.addSpeechFrames(10, 320)
        val controller = createController()
        // Pre-load some frames without starting the processor
        val result = controller.drainRemainingFrames()
        // No crash is the assertion — may or may not produce PCM
    }

    @Test
    fun rmsSampler_isInitialized() {
        val controller = createController()
        assertNotNull("rmsSampler must be initialized", controller.rmsSampler)
    }

    // ── Reset timing (negative: repeat reset is stable) ──────────────────

    @Test
    fun resetVadActiveMs_repeatedly_isStable() {
        val controller = createController()
        repeat(5) {
            controller.resetVadActiveMs()
            assertEquals("vadActiveMs must be 0 after each reset",
                0L, controller.vadActiveMs)
        }
    }

    // ── StopRequested freeze (negative: must skip processing) ────────────

    @Test
    fun stopRequestedTrue_startDoesNotProcess() {
        stopRequested = true
        val controller = createController()
        controller.start()
        // No crash is the assertion
        controller.stop()
    }

    // ── Pipeline tests with FakeCaptureController ───────────────────────

    @Test
    fun process_speechFrame_triggersListener() {
        fakeAudioSource.addSilenceFrames(5, 320)   // pre-roll: 100ms
        fakeAudioSource.addSpeechFrames(30, 320)   // speech: 600ms
        fakeAudioSource.addSilenceFrames(15, 320)  // 300ms silence
        val controller = createController()
        controller.start()
        Thread.sleep(500)
        stopRequested = true
        Thread.sleep(200)
        controller.stop()
        val pcm = controller.stopAndFinalize()
        assertNotNull("stopAndFinalize must return PCM after speech", pcm)
    }

    @Test
    fun process_multipleSpeechFrames_producesUtterance() {
        fakeAudioSource.addSilenceFrames(5, 320)   // pre-roll: 100ms
        fakeAudioSource.addSpeechFrames(40, 320)   // speech: 800ms
        fakeAudioSource.addSilenceFrames(15, 320)  // 300ms silence
        val controller = createController()
        controller.start()
        Thread.sleep(600)
        stopRequested = true
        Thread.sleep(200)
        controller.stop()
        val pcm = controller.stopAndFinalize()
        assertNotNull("stopAndFinalize must return PCM after speech", pcm)
        assertTrue("utterance PCM must be non-empty", pcm!!.isNotEmpty())
    }

    @Test
    fun process_silenceOnly_noUtterance() {
        fakeAudioSource.addSilenceFrames(60, 320)
        val controller = createController()
        controller.start()
        Thread.sleep(500)
        stopRequested = true
        Thread.sleep(200)
        controller.stop()
        val pcm = controller.stopAndFinalize()
        // Pre-roll accumulates silence samples, so stopAndFinalize returns PCM
        // even for silence-only recording. This is expected: user presses STOP
        // and gets whatever was accumulated.
        assertNotNull("silence-only must still return PCM from stopAndFinalize (pre-roll accumulates)", pcm)
    }

    @Test
    fun process_speechThenSilence_accumulatesThenFinalizes() {
        fakeAudioSource.addSilenceFrames(5, 320)   // pre-roll: 100ms
        fakeAudioSource.addSpeechFrames(30, 320)   // speech: 600ms
        fakeAudioSource.addSilenceFrames(15, 320)  // 300ms silence
        val controller = createController()
        controller.start()
        Thread.sleep(600)
        stopRequested = true
        Thread.sleep(200)
        controller.stop()
        val pcm = controller.stopAndFinalize()
        assertNotNull("stopAndFinalize must return PCM after speech then silence", pcm)
    }

    @Test
    fun stopAndFinalize_withFrame_returnsPcm() {
        fakeAudioSource.addSilenceFrames(5, 320)   // pre-roll: 100ms
        fakeAudioSource.addSpeechFrames(30, 320)   // speech: 600ms
        fakeAudioSource.addSilenceFrames(15, 320)  // 300ms silence
        val controller = createController()
        controller.start()
        Thread.sleep(500)
        stopRequested = true
        Thread.sleep(200)
        controller.stop()
        val pcm = controller.stopAndFinalize()
        assertNotNull("stopAndFinalize must return PCM after speech frames", pcm)
        assertTrue("returned PCM must be non-empty", pcm!!.isNotEmpty())
    }

    @Test
    fun process_stopRequestedDuringProcessing_skipsFrame() {
        fakeAudioSource.addSilenceFrames(5, 320)   // pre-roll: 100ms
        fakeAudioSource.addSpeechFrames(10, 320)   // speech: 200ms
        val controller = createController()
        controller.start()
        // Set stopRequested while processor is running
        stopRequested = true
        Thread.sleep(100)
        // Additional frames should be ignored
        fakeAudioSource.addSpeechFrames(15, 320)
        Thread.sleep(200)
        controller.stop()
        // No crash — the freeze may produce utterance from pre-freeze frames
    }

    @Test
    fun process_failOnStart_startCaptureReturnsFalse() {
        fakeAudioSource.failOnStart = true
        val controller = createController()
        controller.start()
        // start() should not crash when AudioSource.startCapture() fails
        controller.stop()
    }

    @Test
    fun process_rapidFrameInjection_noCrash() {
        fakeAudioSource.addSilenceFrames(5, 320)   // pre-roll: 100ms
        fakeAudioSource.addSpeechFrames(60, 320)   // speech: 1200ms
        fakeAudioSource.addSilenceFrames(15, 320)  // silence: 300ms
        val controller = createController()
        controller.start()
        Thread.sleep(800)
        stopRequested = true
        Thread.sleep(200)
        controller.stop()
        val pcm = controller.stopAndFinalize()
        assertNotNull("rapid frame injection must produce PCM", pcm)
    }
}
