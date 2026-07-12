package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that [WhisperModel.warmup] is invoked correctly by the pipeline.
 *
 * Warmup now happens during [SpeechToText.initStt], not in the constructor.
 * Tests validate that warmup is called exactly once on the first [initStt]
 * call and is NOT called on subsequent calls.
 *
 * Uses [FakeWhisperModel] which tracks calls to [warmup].
 */
class WarmupInvocationTest {

    private fun createConfig(warmupEnabled: Boolean, warmupDurationMs: Int): SttRunConfig {
        return SttRunConfig(
            ttsEngineConfig = TtsEngineConfig(
            modelPath = "/test/model.bin",
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
            stopStrategy = StopStrategyConfig(type = "MANUAL"),
            warmupEnabled = warmupEnabled,
            warmupDurationMs = warmupDurationMs
        )
    }

    @Test
    fun warmup_calledOnceDuringInitStt() {
        val fakeModel = FakeWhisperModel()
        val stt = SpeechToText(
            context = null,
            whisperModel = fakeModel,
            captureManager = FakeCaptureManager()
        )
        val runConfig = createConfig(warmupEnabled = true, warmupDurationMs = 500)

        stt.setConfig(runConfig)
        stt.initStt(runConfig)
        assertEquals("warmup must be called exactly once during initStt",
            1, fakeModel.warmupCount)
        assertEquals("warmup duration must match config",
            500, fakeModel.lastWarmupDurationMs)
    }

    @Test
    fun warmup_notCalledWhenDisabled() {
        val fakeModel = FakeWhisperModel()
        val stt = SpeechToText(
            context = null,
            whisperModel = fakeModel,
            captureManager = FakeCaptureManager()
        )
        val runConfig = createConfig(warmupEnabled = false, warmupDurationMs = 500)

        stt.setConfig(runConfig)
        stt.initStt(runConfig)
        assertEquals("warmup must NOT be called when disabled",
            0, fakeModel.warmupCount)
    }

    @Test
    fun warmup_durationZeroCausesNoError() {
        val fakeModel = FakeWhisperModel()
        val stt = SpeechToText(
            context = null,
            whisperModel = fakeModel,
            captureManager = FakeCaptureManager()
        )
        val runConfig = createConfig(warmupEnabled = true, warmupDurationMs = 0)

        stt.setConfig(runConfig)
        stt.initStt(runConfig)

        assertEquals("warmup must be called with duration=0",
            1, fakeModel.warmupCount)
        assertEquals("warmup duration must be 0",
            0, fakeModel.lastWarmupDurationMs)
    }

    @Test
    fun warmup_notCalledOnSecondInitStt() {
        val fakeModel = FakeWhisperModel()
        val stt = SpeechToText(
            context = null,
            whisperModel = fakeModel,
            captureManager = FakeCaptureManager()
        )
        val runConfig = createConfig(warmupEnabled = true, warmupDurationMs = 500)

        stt.setConfig(runConfig)
        stt.initStt(runConfig)

        assertEquals("warmup must be called once on first initStt",
            1, fakeModel.warmupCount)

        // Second initStt — warmup must NOT be called.
        stt.initStt(runConfig)

        assertEquals("warmup must NOT be called on second initStt",
            1, fakeModel.warmupCount)
    }

    @Test
    fun warmup_enabledAndDurationPassedCorrectly() {
        val fakeModel = FakeWhisperModel()
        val stt = SpeechToText(
            context = null,
            whisperModel = fakeModel,
            captureManager = FakeCaptureManager()
        )
        val runConfig = createConfig(warmupEnabled = true, warmupDurationMs = 2000)

        stt.setConfig(runConfig)
        stt.initStt(runConfig)

        assertEquals("warmup must be called exactly once",
            1, fakeModel.warmupCount)
        assertEquals("warmup duration must be 2000",
            2000, fakeModel.lastWarmupDurationMs)
    }

    /** Reusable SpeechToText reference. Tests that interact with it may
     * encounter UnsatisfiedLinkError from WhisperBridge. Those are caught
     * and ignored — the tests are validating warmup invocation order, not
     * the Whisper pipeline. */
    private var speechToText: SpeechToText? = null
}

