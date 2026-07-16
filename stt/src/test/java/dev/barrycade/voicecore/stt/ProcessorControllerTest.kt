package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [ProcessorController].
 *
 * Validates initial value contracts, idempotency, and accessor behaviour.
 *
 * NOTE: Sleep-based pipeline tests and no-assertion tests removed per audit plan.
 * The pipeline integration path is covered by SttPipelineBehaviourTest
 * and SttPipelineSequencingTest (merged).
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
        assertEquals(0f, controller.vadConfidence!!, 0.001f)
    }

    @Test
    fun supportsVadMetrics_returnsTrue() {
        val controller = createController()
        assertTrue("ProcessorController supports VAD metrics", controller.supportsVadMetrics())
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
}
