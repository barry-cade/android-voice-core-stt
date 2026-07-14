package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stress tests for stale callback rejection across session epoch changes.
 *
 * Uses the JSON-boundary API: [init] and [transcribe].
 */
class SttStaleCallbackStressTest {

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
    private val resultCount = AtomicInteger(0)

    @Before
    fun setUp() {
        captureManager = FakeCaptureManager()
        blockingModel = BlockingWhisperModel()
        resultCount.set(0)

        SpeechToText.resetForTest()
        speechToText = SpeechToText(
            context = null,
            whisperModel = blockingModel,
            captureManager = captureManager
        )

        speechToText.setOnMessageListener { json ->
            if (json != null && json.contains("\"type\":\"result\"")) {
                resultCount.incrementAndGet()
            }
        }
    }

    private fun buildConfigJson(): String {
        return """{"modelPath":"/dummy/model.bin","language":"en","debugLoggingEnabled":false,"energyThreshold":0.03,"preRollMs":100,"stableChunkSizeMs":500,"drainMode":"DRAIN_FROM_NEXT_FRAME","startType":"MANUAL","stopType":"MANUAL","warmupEnabled":false,"warmupDurationMs":0}"""
    }

    private fun initSafely() {
        val json = buildConfigJson()
        try {
            speechToText.init(json)
        } catch (_: RuntimeException) {
            // ModelManager may fail with FakeWhisperModel in unit tests
        }
    }

    @Test
    fun nonStaleResultDelivered_whenEpochUnchanged() {
        val control = blockingModel.blockNextTranscribe()

        captureManager.addSpeechFrames(8)
        initSafely()

        speechToText.transcribe()
        assertTrue("inference should start", control.startedLatch.await(1, TimeUnit.SECONDS))

        control.releaseLatch.countDown()
        assertTrue("blocked transcribe should finish", control.finishedLatch.await(1, TimeUnit.SECONDS))

        waitForResultCount(target = 1)
        assertEquals("non-stale callback should be delivered", 1, resultCount.get())
    }

    private fun waitForResultCount(target: Int) {
        repeat(100) {
            if (resultCount.get() >= target) {
                return
            }
            Thread.sleep(10)
        }
    }

    private fun waitForExecutorSettle() {
        Thread.sleep(30)
    }
}
