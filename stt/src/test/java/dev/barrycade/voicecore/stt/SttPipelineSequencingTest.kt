package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pipeline sequencing tests for stop-path stage/lifecycle ordering
 * with the new synchronous API.
 *
 * Uses the new three-method API: [init] and [transcribe] both return JSON.
 * Internal pipeline states can still be observed via [lifecycleController]
 * and [currentPipelineStageForTest].
 */
class SttPipelineSequencingTest {

    private class BlockingWhisperModel : WhisperModel {
        private val transcribeInProgress = CountDownLatch(1)
        private var releaseLatch: CountDownLatch? = null
        private val transcribeIndex = AtomicInteger(0)

        @Synchronized
        fun blockNextTranscribe(): CountDownLatch {
            val latch = CountDownLatch(1)
            releaseLatch = latch
            return latch
        }

        @Synchronized
        fun waitForInferenceStart() {
            transcribeInProgress.await(2, TimeUnit.SECONDS)
        }

        override fun loadModel(modelPath: String) {
            // No-op for JVM tests.
        }

        override fun transcribe(samples: ShortArray): String {
            transcribeInProgress.countDown()

            val release = synchronized(this) { releaseLatch }
            release?.await(2, TimeUnit.SECONDS)

            val index = transcribeIndex.incrementAndGet()
            return "transcript-$index"
        }

        override fun unloadModel() {
            // No-op for JVM tests.
        }
    }

    private lateinit var speechToText: SpeechToText
    private lateinit var captureManager: FakeCaptureManager
    private lateinit var blockingModel: BlockingWhisperModel

    @Before
    fun setUp() {
        captureManager = FakeCaptureManager()
        blockingModel = BlockingWhisperModel()

        speechToText = SpeechToText(
            whisperModel = blockingModel,
            captureManager = captureManager
        )
    }

    private fun buildConfigJson(): String {
        return """{"modelPath":"/dummy/model.bin","language":"en","debugLoggingEnabled":false,"energyThreshold":0.03,"preRollMs":100,"stableChunkSizeMs":500,"drainMode":"DRAIN_FROM_NEXT_FRAME","startType":"MANUAL","stopType":"MANUAL","warmupEnabled":false,"warmupDurationMs":0}"""
    }

    private fun initSafely(): Boolean {
        val json = buildConfigJson()
        val result = speechToText.init(json)
        return result.contains("\"type\":\"init\"")
    }

    // -- Stop path sequencing -----------------------------------------------

    @Test
    fun stopNonEmpty_transitionsReadyOnlyAfterInferenceCompletes() {
        // Arrange: release latch so transcribe can finish
        blockingModel.blockNextTranscribe()
        captureManager.addSpeechFrames(8)
        assertTrue("init should succeed", initSafely())

        captureManager.addSpeechFrames(8)

        // Act: transcribe in a background thread since it blocks
        val transcribeResult = Array<String?>(1) { null }
        val thread = Thread {
            transcribeResult[0] = speechToText.transcribe()
        }
        thread.start()

        // Wait for inference to start
        blockingModel.waitForInferenceStart()

        // Assert: pipeline is INFERENCING while waiting
        assertEquals(
            "pipeline stage must be INFERENCING while inference is blocked",
            SttPipelineStage.INFERENCING,
            speechToText.currentPipelineStageForTest()
        )
        assertTrue(
            "lifecycle must be FINALISING while inference is blocked",
            speechToText.lifecycleController.currentState is SttLifecycleState.FINALISING
        )

        // Release inference
        blockingModel.blockNextTranscribe().countDown()
        thread.join(3000)

        // Assert: pipeline resets after inference
        waitForReadyState()
        assertTrue(
            "lifecycle must become READY after inference completes",
            speechToText.lifecycleController.currentState is SttLifecycleState.READY
        )
        assertEquals(
            "pipeline stage must return to IDLE after stop completion",
            SttPipelineStage.IDLE,
            speechToText.currentPipelineStageForTest()
        )

        // Assert: result contains transcript
        val result = transcribeResult[0]
        assertNotNull("transcribe should return a result", result)
        assertTrue("result should contain transcript", result?.contains("transcript") == true)
    }

    @Test
    fun stopEmpty_transitionsReadyImmediately() {
        assertTrue("init should succeed", initSafely())

        val result = speechToText.transcribe()

        assertTrue(
            "empty stop path should reset lifecycle to READY immediately",
            speechToText.lifecycleController.currentState is SttLifecycleState.READY
        )
        assertEquals(
            "empty stop path should set stage to IDLE",
            SttPipelineStage.IDLE,
            speechToText.currentPipelineStageForTest()
        )

        // Empty PCM returns silence
        assertTrue("empty transcribe should return silence", result.contains("\"code\":\"SILENCE\""))
    }

    // -- Stale callback rejection (via transcribe result) ------------------

    @Test
    fun nonStaleResultDelivered_whenEpochUnchanged() {
        blockingModel.blockNextTranscribe()
        captureManager.addSpeechFrames(8)
        assertTrue("init should succeed", initSafely())

        // Run transcribe in background, wait for result
        val result = speechToText.transcribe()

        // Result should be delivered (non-stale) — contains transcript
        assertTrue("result should contain success", result.contains("\"code\":\"SUCCESS\"") || result.contains("\"text\""))
    }

    // -- Helpers ------------------------------------------------------------

    private fun waitForReadyState() {
        repeat(100) {
            if (speechToText.lifecycleController.currentState is SttLifecycleState.READY) {
                return
            }
            Thread.sleep(10)
        }
    }
}
