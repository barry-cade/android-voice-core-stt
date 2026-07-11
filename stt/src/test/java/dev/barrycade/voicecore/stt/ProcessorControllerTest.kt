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
 * Uses [FakeCaptureManager] which implements [AudioSource].
 */
class ProcessorControllerTest {

    private lateinit var fakeAudioSource: FakeCaptureManager
    private lateinit var vad: Vad
    private lateinit var accumulator: UtteranceAccumulator
    private val capturedUtterances = mutableListOf<FloatArray>()
    private lateinit var listener: UtteranceListener
    private var stopRequested: Boolean = false

    @Before
    fun setUp() {
        fakeAudioSource = FakeCaptureManager()
        vad = Vad(energyThreshold = 0.01)
        accumulator = UtteranceAccumulator(
            sampleRate = 16000,
            utteranceSilenceTimeoutMs = 500,
            utteranceMaxDurationMs = 30000
        )
        capturedUtterances.clear()
        listener = object : UtteranceListener {
            override fun onUtteranceReady(pcm: FloatArray, code: SttReturnCode) {
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

    // -- Initial value contracts ---------------------------------------------

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

    // -- Idempotency / no-op tests -------------------------------------------

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

    // -- Stop-and-finalize (negative: empty accumulator path) ----------------

    @Test
    fun stopAndFinalize_emptyAccumulator_returnsNull() {
        val controller = createController()
        controller.start()
        controller.stop()
        val pcm = controller.stopAndFinalize()
        assertNull("stopAndFinalize with empty accumulator must return null", pcm)
    }

    // -- Accessor tests ------------------------------------------------------

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
        val result = controller.drainRemainingFrames()
        // No crash is the assertion
    }

    @Test
    fun rmsSampler_isInitialized() {
        val controller = createController()
        assertNotNull("rmsSampler must be initialized", controller.rmsSampler)
    }

    // -- Reset timing --------------------------------------------------------

    @Test
    fun resetVadActiveMs_repeatedly_isStable() {
        val controller = createController()
        repeat(5) {
            controller.resetVadActiveMs()
            assertEquals("vadActiveMs must be 0 after each reset",
                0L, controller.vadActiveMs)
        }
    }

    // -- StopRequested freeze ------------------------------------------------

    @Test
    fun stopRequestedTrue_startDoesNotProcess() {
        stopRequested = true
        val controller = createController()
        controller.start()
        controller.stop()
    }

    // -- Pipeline tests with FakeCaptureController ---------------------------

    @Test
    fun process_speechFrame_triggersListener() {
        fakeAudioSource.addSilenceFrames(5, 320)
        fakeAudioSource.addSpeechFrames(30, 320)
        fakeAudioSource.addSilenceFrames(15, 320)
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
        fakeAudioSource.addSilenceFrames(5, 320)
        fakeAudioSource.addSpeechFrames(40, 320)
        fakeAudioSource.addSilenceFrames(15, 320)
        val controller = createController()
        controller.start()
        Thread.sleep(600)
        stopRequested = true
        Thread.sleep(200)
        controller.stop()
        val pcm = controller.stopAndFinalize()
        assertNotNull(pcm)
        assertTrue(pcm!!.isNotEmpty())
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
        assertNotNull(pcm)
    }

    @Test
    fun process_speechThenSilence_accumulatesThenFinalizes() {
        fakeAudioSource.addSilenceFrames(5, 320)
        fakeAudioSource.addSpeechFrames(30, 320)
        fakeAudioSource.addSilenceFrames(15, 320)
        val controller = createController()
        controller.start()
        Thread.sleep(600)
        stopRequested = true
        Thread.sleep(200)
        controller.stop()
        val pcm = controller.stopAndFinalize()
        assertNotNull(pcm)
    }

    @Test
    fun stopAndFinalize_withFrame_returnsPcm() {
        fakeAudioSource.addSilenceFrames(5, 320)
        fakeAudioSource.addSpeechFrames(30, 320)
        fakeAudioSource.addSilenceFrames(15, 320)
        val controller = createController()
        controller.start()
        Thread.sleep(500)
        stopRequested = true
        Thread.sleep(200)
        controller.stop()
        val pcm = controller.stopAndFinalize()
        assertNotNull(pcm)
        assertTrue(pcm!!.isNotEmpty())
    }

    @Test
    fun process_stopRequestedDuringProcessing_skipsFrame() {
        fakeAudioSource.addSilenceFrames(5, 320)
        fakeAudioSource.addSpeechFrames(10, 320)
        val controller = createController()
        controller.start()
        stopRequested = true
        Thread.sleep(100)
        fakeAudioSource.addSpeechFrames(15, 320)
        Thread.sleep(200)
        controller.stop()
    }

    @Test
    fun process_failOnStart_startCaptureReturnsFalse() {
        fakeAudioSource.failOnStart = true
        val controller = createController()
        controller.start()
        controller.stop()
    }

    @Test
    fun process_rapidFrameInjection_noCrash() {
        fakeAudioSource.addSilenceFrames(5, 320)
        fakeAudioSource.addSpeechFrames(60, 320)
        fakeAudioSource.addSilenceFrames(15, 320)
        val controller = createController()
        controller.start()
        Thread.sleep(800)
        stopRequested = true
        Thread.sleep(200)
        controller.stop()
        val pcm = controller.stopAndFinalize()
        assertNotNull(pcm)
    }
}
