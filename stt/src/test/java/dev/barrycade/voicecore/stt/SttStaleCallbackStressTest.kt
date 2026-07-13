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
    private lateinit var model: BlockingWhisperModel
    private val resultCount = AtomicInteger(0)

    @Before
    fun setUp() {
        captureManager = FakeCaptureManager()
        model = BlockingWhisperModel()
        resultCount.set(0)

        speechToText = SpeechToText(
            context = null,
            whisperModel = model,
            captureManager = captureManager
        )
        speechToText.setOnResultListener { _ ->
            resultCount.incrementAndGet()
        }

        val config = validManualStopConfig()
        speechToText.setConfig(config)
        speechToText.initStt(config)
    }

    @Test
    fun staleResultDropped_afterResetBeforeInferenceCompletes() {
        val control = model.blockNextTranscribe()

        captureManager.addSpeechFrames(8)
        val startResult = startSessionEventually()
        assertEquals(SttReturnCode.SUCCESS, startResult.code)

        speechToText.stopAndTranscribe()
        assertTrue("inference should start", control.startedLatch.await(1, TimeUnit.SECONDS))

        speechToText.resetForNextSession()
        control.releaseLatch.countDown()
        assertTrue("blocked transcribe should finish", control.finishedLatch.await(1, TimeUnit.SECONDS))

        waitForExecutorSettle()
        assertEquals("stale callback should be dropped", 0, resultCount.get())
    }

    @Test
    fun staleResultStress_rapidResetAcrossMultipleSessions() {
        repeat(15) {
            val control = model.blockNextTranscribe()

            captureManager.addSpeechFrames(8)
            val startResult = startSessionEventually()
            assertEquals("startSession should eventually succeed", SttReturnCode.SUCCESS, startResult.code)

            speechToText.stopAndTranscribe()
            assertTrue("inference should start", control.startedLatch.await(1, TimeUnit.SECONDS))

            speechToText.resetForNextSession()
            control.releaseLatch.countDown()
            assertTrue("blocked transcribe should finish", control.finishedLatch.await(1, TimeUnit.SECONDS))

            waitForExecutorSettle()
        }

        assertEquals("no stale callbacks should leak through", 0, resultCount.get())
    }

    @Test
    fun nonStaleResultDelivered_whenEpochUnchanged() {
        val control = model.blockNextTranscribe()

        captureManager.addSpeechFrames(8)
        val startResult = startSessionEventually()
        assertEquals(SttReturnCode.SUCCESS, startResult.code)

        speechToText.stopAndTranscribe()
        assertTrue("inference should start", control.startedLatch.await(1, TimeUnit.SECONDS))

        control.releaseLatch.countDown()
        assertTrue("blocked transcribe should finish", control.finishedLatch.await(1, TimeUnit.SECONDS))

        waitForResultCount(target = 1)
        assertEquals("non-stale callback should be delivered", 1, resultCount.get())
    }

    private fun validManualStopConfig(): SttRunConfig {
        return SttRunConfig(
            ttsEngineConfig = TtsEngineConfig(
                modelPath = "/dummy/model.bin",
                language = "en",
                debugLoggingEnabled = false
            ),
            vadConfig = VadConfig(
                energyThreshold = 0.03f,
                preRollMs = 100,
                stableChunkSizeMs = 500
            ),
            drainMode = DrainMode.DRAIN_FROM_NEXT_FRAME,
            startStrategy = StartStrategyConfig(type = "MANUAL"),
            stopStrategy = StopStrategyConfig(type = "MANUAL")
        )
    }

    private fun startSessionEventually(): SessionResult {
        var last = SessionResult(SttReturnCode.ENGINE_ERROR, null)
        repeat(40) {
            last = speechToText.startSession()
            if (last.code == SttReturnCode.SUCCESS) {
                return last
            }
            Thread.sleep(10)
        }
        return last
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
