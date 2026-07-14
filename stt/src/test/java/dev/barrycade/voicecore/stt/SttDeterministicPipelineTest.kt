package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic pipeline tests for stop-path stage/lifecycle sequencing.
 *
 * Uses the JSON-boundary API: [init] and [transcribe].
 */
class SttDeterministicPipelineTest {

    private data class BlockingTranscribeControl(
        val startedLatch: CountDownLatch,
        val releaseLatch: CountDownLatch,
        val finishedLatch: CountDownLatch
    )

    private class BlockingWhisperModel : WhisperModel {
        private var control: BlockingTranscribeControl? = null
        private val transcribeIndex = AtomicInteger(0)

        @Synchronized
        fun blockNextTranscribe(): BlockingTranscribeControl {
            val created = BlockingTranscribeControl(
                startedLatch = CountDownLatch(1),
                releaseLatch = CountDownLatch(1),
                finishedLatch = CountDownLatch(1)
            )
            control = created
            return created
        }

        override fun loadModel(modelPath: String) {
            // No-op for JVM tests.
        }

        override fun transcribe(samples: ShortArray): String {
            val current = synchronized(this) {
                val snapshot = control
                control = null
                snapshot
            }

            current?.startedLatch?.countDown()
            current?.releaseLatch?.await(2, TimeUnit.SECONDS)
            current?.finishedLatch?.countDown()

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
        SpeechToText.resetForTest()
        speechToText = SpeechToText(
            context = null,
            whisperModel = blockingModel,
            captureManager = captureManager
        )
    }

            private fun buildConfigJson(): String {
        return """{"modelPath":"/dummy/model.bin","language":"en","debugLoggingEnabled":false,"energyThreshold":0.03,"preRollMs":100,"stableChunkSizeMs":500,"drainMode":"DRAIN_FROM_NEXT_FRAME","startType":"MANUAL","stopType":"MANUAL","warmupEnabled":false,"warmupDurationMs":0}"""
    }

    private fun initSafely(): String {
        val json = buildConfigJson()
        return try {
            speechToText.init(json)
        } catch (_: RuntimeException) {
            // ModelManager may fail with FakeWhisperModel in unit tests
            """{"type":"result","code":"SUCCESS","text":""}"""
        }
    }

    @Test
    fun stopNonEmpty_transitionsReadyOnlyAfterInferenceCompletes() {
        val control = blockingModel.blockNextTranscribe()

        // Use internal processStart to bypass strategy and start capture
        captureManager.addSpeechFrames(8)
        initSafely()

        // Manually add speech frames before transcribing
        captureManager.addSpeechFrames(8)
        speechToText.transcribe()
        assertTrue("inference should start", control.startedLatch.await(1, TimeUnit.SECONDS))

        assertTrue(
            "lifecycle must remain FINALISING while inference is blocked",
            speechToText.lifecycleController.currentState is SttLifecycleState.FINALISING
        )
        assertEquals(
            "pipeline stage must be INFERENCING while inference is blocked",
            SttPipelineStage.INFERENCING,
            speechToText.currentPipelineStageForTest()
        )

        control.releaseLatch.countDown()
        assertTrue("blocked transcribe should finish", control.finishedLatch.await(1, TimeUnit.SECONDS))

        waitForReadyState(speechToText)
        assertTrue(
            "lifecycle must become READY after inference completes",
            speechToText.lifecycleController.currentState is SttLifecycleState.READY
        )
        assertEquals(
            "pipeline stage must return to IDLE after stop completion",
            SttPipelineStage.IDLE,
            speechToText.currentPipelineStageForTest()
        )
    }

        @Test
    fun stopEmpty_transitionsReadyImmediately() {
        initSafely()
        speechToText.transcribe()

        assertTrue(
            "empty stop path should reset lifecycle to READY immediately",
            speechToText.lifecycleController.currentState is SttLifecycleState.READY
        )
        assertEquals(
            "empty stop path should set stage to IDLE",
            SttPipelineStage.IDLE,
            speechToText.currentPipelineStageForTest()
        )
    }

    private fun waitForReadyState(stt: SpeechToText) {
        repeat(100) {
            if (stt.lifecycleController.currentState is SttLifecycleState.READY) {
                return
            }
            Thread.sleep(10)
        }
    }

}
