package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [SttInferenceController].
 */
class SttInferenceControllerTest {

    @Test
    fun submit_dispatchesTimingAndResult_whenDecisionAllows() {
        val fakeModel = FakeWhisperModel()
        fakeModel.transcriptResult = "hello"

        val dispatcher = SttCallbackDispatcher()
        val modelManager = ModelManager(
            modelPath = "/dummy/model.bin",
            sttErrorListener = null,
            readyListener = null,
            whisperModel = fakeModel
        )
        val controller = SttInferenceController(modelManager, dispatcher)

        val resultCalls = AtomicInteger(0)
        val timingCalls = AtomicInteger(0)
        val completeLatch = CountDownLatch(1)

        dispatcher.setOnResultListener {
            resultCalls.incrementAndGet()
        }
        dispatcher.onTimingListener = { _, _, _, _ ->
            timingCalls.incrementAndGet()
        }

        val request = SttInferenceController.InferenceRequest(
            pcm = floatArrayOf(0.1f, -0.1f, 0.2f),
            code = SttReturnCode.SUCCESS,
            vadActiveMs = 10L,
            utteranceMs = 20L,
            captureMs = 30L,
            preRollMs = 40L,
            autoSilenceMs = 50L,
            pipelineStartMs = System.currentTimeMillis(),
            sessionEpochAtSubmission = 1L
        )

        val accepted = controller.submit(
            request = request,
            decideDispatch = { SttInferenceController.DispatchDecision(shouldDispatch = true) },
            onPostDispatch = {},
            onComplete = { completeLatch.countDown() }
        )

        assertTrue("submission should be accepted", accepted)
        assertTrue("onComplete should fire", completeLatch.await(1, TimeUnit.SECONDS))
        assertEquals("result listener should fire once", 1, resultCalls.get())
        assertEquals("timing listener should fire once", 1, timingCalls.get())

        modelManager.shutdown()
    }

    @Test
    fun submit_dropsResult_whenDecisionRejects() {
        val fakeModel = FakeWhisperModel()
        fakeModel.transcriptResult = "hello"

        val dispatcher = SttCallbackDispatcher()
        val modelManager = ModelManager(
            modelPath = "/dummy/model.bin",
            sttErrorListener = null,
            readyListener = null,
            whisperModel = fakeModel
        )
        val controller = SttInferenceController(modelManager, dispatcher)

        val resultCalls = AtomicInteger(0)
        val timingCalls = AtomicInteger(0)
        val completeLatch = CountDownLatch(1)

        dispatcher.setOnResultListener {
            resultCalls.incrementAndGet()
        }
        dispatcher.onTimingListener = { _, _, _, _ ->
            timingCalls.incrementAndGet()
        }

        val request = SttInferenceController.InferenceRequest(
            pcm = floatArrayOf(0.1f, -0.1f, 0.2f),
            code = SttReturnCode.SUCCESS,
            vadActiveMs = 10L,
            utteranceMs = 20L,
            captureMs = 30L,
            preRollMs = 40L,
            autoSilenceMs = 50L,
            pipelineStartMs = System.currentTimeMillis(),
            sessionEpochAtSubmission = 1L
        )

        val accepted = controller.submit(
            request = request,
            decideDispatch = {
                SttInferenceController.DispatchDecision(
                    shouldDispatch = false,
                    dropReason = "stale submission"
                )
            },
            onPostDispatch = {},
            onComplete = { completeLatch.countDown() }
        )

        assertTrue("submission should be accepted", accepted)
        assertTrue("onComplete should fire", completeLatch.await(1, TimeUnit.SECONDS))
        assertEquals("result listener should not fire", 0, resultCalls.get())
        assertEquals("timing listener should not fire", 0, timingCalls.get())

        modelManager.shutdown()
    }

    @Test
    fun submit_rejectedAfterShutdown_returnsFalse() {
        val fakeModel = FakeWhisperModel()
        val dispatcher = SttCallbackDispatcher()
        val modelManager = ModelManager(
            modelPath = "/dummy/model.bin",
            sttErrorListener = null,
            readyListener = null,
            whisperModel = fakeModel
        )
        val controller = SttInferenceController(modelManager, dispatcher)

        modelManager.shutdown()

        val request = SttInferenceController.InferenceRequest(
            pcm = floatArrayOf(0.1f),
            code = SttReturnCode.SUCCESS,
            vadActiveMs = 1L,
            utteranceMs = 1L,
            captureMs = 1L,
            preRollMs = 1L,
            autoSilenceMs = 1L,
            pipelineStartMs = System.currentTimeMillis(),
            sessionEpochAtSubmission = 1L
        )

        val accepted = controller.submit(
            request = request,
            decideDispatch = { SttInferenceController.DispatchDecision(shouldDispatch = true) },
            onPostDispatch = {},
            onComplete = {}
        )

        assertFalse("submission should be rejected after shutdown", accepted)
    }
}
