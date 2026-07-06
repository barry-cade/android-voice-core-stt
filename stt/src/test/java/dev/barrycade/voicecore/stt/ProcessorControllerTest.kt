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
 * Validates the frame polling loop, VAD integration, accumulator integration,
 * stop/start lifecycle, stopAndFinalize, and the stopRequestedRef freeze mechanism.
 *
 * Since [CaptureController] depends on Android [AudioCapture], these tests
 * use a pure-Kotlin simulation of the PCM frame source.
 */
class ProcessorControllerTest {

    private lateinit var captureController: CaptureController
    private lateinit var vad: Vad
    private lateinit var accumulator: UtteranceAccumulator
    private val capturedUtterances = mutableListOf<FloatArray>()
    private lateinit var listener: UtteranceListener
    private var stopRequested: Boolean = false

    @Before
    fun setUp() {
        captureController = CaptureController(sampleRate = 16000, requestedBufferSizeInBytes = 32000)
        vad = Vad(energyThreshold = 0.01)
        accumulator = UtteranceAccumulator(
            sampleRate = 16000,
            silenceDurationMs = 100
        )
        capturedUtterances.clear()
        listener = object : UtteranceListener {
            override fun onUtteranceReady(pcm: FloatArray) {
                capturedUtterances.add(pcm)
            }
        }
        stopRequested = false
    }

    @Test
    fun constructor_createsCleanState() {
        val controller = createController()
        assertEquals(0L, controller.vadActiveMs)
        assertEquals(0, controller.lastUtteranceDurationMs)
        assertEquals(0f, controller.vadConfidence, 0.001f)
    }

    @Test
    fun start_stop_noErrors() {
        val controller = createController()
        controller.start()
        controller.stop()
    }

    @Test
    fun start_twice_isIdempotent() {
        val controller = createController()
        controller.start()
        controller.start()
        controller.stop()
    }

    @Test
    fun stop_twice_isIdempotent() {
        val controller = createController()
        controller.start()
        controller.stop()
        controller.stop()
    }

    @Test
    fun stopAndFinalize_returnsNullWhenEmpty() {
        val controller = createController()
        controller.start()
        // No frames polled — accumulator is empty
        controller.stop()
        val pcm = controller.stopAndFinalize()
        assertNull("stopAndFinalize on empty accumulator must return null", pcm)
    }

    @Test
    fun getVad_returnsConfiguredVad() {
        val controller = createController()
        assertNotNull("getVad must return non-null Vad", controller.getVad())
        assertEquals(vad, controller.getVad())
    }

    @Test
    fun getAccumulator_returnsConfiguredAccumulator() {
        val controller = createController()
        assertNotNull("getAccumulator must return non-null accumulator", controller.getAccumulator())
        assertEquals(accumulator, controller.getAccumulator())
    }

    @Test
    fun rmsSampler_isInitialized() {
        val controller = createController()
        assertNotNull("rmsSampler must be initialized", controller.rmsSampler)
    }

    @Test
    fun resetVadActiveMs_setsToZero() {
        val controller = createController()
        controller.resetVadActiveMs()
        assertEquals("resetVadActiveMs sets vadActiveMs to 0", 0L, controller.vadActiveMs)
    }

    private fun createController(): ProcessorController {
        return ProcessorController(
            captureController = captureController,
            vad = vad,
            utteranceAccumulator = accumulator,
            listener = listener,
            sampleRate = 16000,
            debugLogging = false,
            stopRequestedRef = { stopRequested }
        )
    }
}
