package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests that [WhisperModel.warmup] is invoked correctly by the pipeline.
 *
 * Warmup now happens during [SpeechToText.init], not in the constructor.
 * Tests validate that warmup is called exactly once on the first [init]
 * call and is NOT called on subsequent calls.
 *
 * Uses [FakeWhisperModel] which tracks calls to [warmup].
 */
class WarmupInvocationTest {

    private fun buildConfigJson(
        warmupEnabled: Boolean,
        warmupDurationMs: Int
    ): String {
        val sb = StringBuilder()
        sb.append("""{"modelPath":"/test/model.bin","language":"en","debugLoggingEnabled":false,"energyThreshold":0.03,"preRollMs":100,"stableChunkSizeMs":500,"drainMode":"DRAIN_FROM_NEXT_FRAME","startType":"MANUAL","stopType":"MANUAL",""")
        sb.append("\"warmupEnabled\":").append(warmupEnabled).append(",")
        sb.append("\"warmupDurationMs\":").append(warmupDurationMs)
        sb.append("}")
        return sb.toString()
    }

    private fun initSafely(stt: SpeechToText, json: String) {
        try {
            stt.init(json)
        } catch (_: RuntimeException) {
            // ModelManager may fail with FakeWhisperModel in unit tests
        }
    }

    @Test
    fun warmup_calledOnceDuringInit() {
        val fakeModel = FakeWhisperModel()
        SpeechToText.resetForTest()
        val stt = SpeechToText(
            context = null,
            whisperModel = fakeModel,
            captureManager = FakeCaptureManager()
        )
        val json = buildConfigJson(warmupEnabled = true, warmupDurationMs = 500)

        initSafely(stt, json)
        assertEquals("warmup must be called exactly once during init",
            1, fakeModel.warmupCount)
        assertEquals("warmup duration must match config",
            500, fakeModel.lastWarmupDurationMs)
    }

    @Test
    fun warmup_notCalledWhenDisabled() {
        val fakeModel = FakeWhisperModel()
        SpeechToText.resetForTest()
        val stt = SpeechToText(
            context = null,
            whisperModel = fakeModel,
            captureManager = FakeCaptureManager()
        )
        val json = buildConfigJson(warmupEnabled = false, warmupDurationMs = 500)

        initSafely(stt, json)
        assertEquals("warmup must NOT be called when disabled",
            0, fakeModel.warmupCount)
    }

    @Test
    fun warmup_durationZeroCausesNoError() {
        val fakeModel = FakeWhisperModel()
        SpeechToText.resetForTest()
        val stt = SpeechToText(
            context = null,
            whisperModel = fakeModel,
            captureManager = FakeCaptureManager()
        )
        val json = buildConfigJson(warmupEnabled = true, warmupDurationMs = 0)

        initSafely(stt, json)

        assertEquals("warmup must be called with duration=0",
            1, fakeModel.warmupCount)
        assertEquals("warmup duration must be 0",
            0, fakeModel.lastWarmupDurationMs)
    }

    @Test
    fun warmup_notCalledOnSecondInit() {
        val fakeModel = FakeWhisperModel()
        SpeechToText.resetForTest()
        val stt = SpeechToText(
            context = null,
            whisperModel = fakeModel,
            captureManager = FakeCaptureManager()
        )
        val json = buildConfigJson(warmupEnabled = true, warmupDurationMs = 500)

        initSafely(stt, json)

        assertEquals("warmup must be called once on first init",
            1, fakeModel.warmupCount)

        // Second init — warmup must NOT be called.
        initSafely(stt, json)

        assertEquals("warmup must NOT be called on second init",
            1, fakeModel.warmupCount)
    }

    @Test
    fun warmup_enabledAndDurationPassedCorrectly() {
        val fakeModel = FakeWhisperModel()
        SpeechToText.resetForTest()
        val stt = SpeechToText(
            context = null,
            whisperModel = fakeModel,
            captureManager = FakeCaptureManager()
        )
        val json = buildConfigJson(warmupEnabled = true, warmupDurationMs = 2000)

        initSafely(stt, json)

        assertEquals("warmup must be called exactly once",
            1, fakeModel.warmupCount)
        assertEquals("warmup duration must be 2000",
            2000, fakeModel.lastWarmupDurationMs)
    }
}

