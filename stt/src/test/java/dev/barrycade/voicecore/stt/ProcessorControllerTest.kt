package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // ── Getters (negative: must never return null) ───────────────────────

    @Test
    fun getVad_returnsNonNull() {
        val controller = createController()
        assertNotNull("getVad must return non-null Vad", controller.getVad())
    }

    @Test
    fun getAccumulator_returnsNonNull() {
        val controller = createController()
        assertNotNull("getAccumulator must return non-null accumulator",
            controller.getAccumulator())
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
}
