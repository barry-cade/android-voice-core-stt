package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke test for the public API types.
 *
 * Verifies that the new [SttRunConfig]-based API types are accessible
 * and can be constructed with valid values.
 */
class PublicApiSmokeTest {

    @Test
    fun sttRunConfig_constructsManually() {
        val config = SttRunConfig(
            ttsEngineConfig = TtsEngineConfig(
                modelPath = "/dummy/path",
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
        assertEquals("/dummy/path", config.ttsEngineConfig.modelPath)
    }

    @Test
    fun sttRunConfigValidator_acceptsValidConfig() {
        val config = SttRunConfig(
            ttsEngineConfig = TtsEngineConfig(
                modelPath = "/dummy/path",
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
            stopStrategy = StopStrategyConfig(
                type = "AUTO_SILENCE",
                silenceMs = 1200,
                maxDurationMs = 30000
            )
        )
        val result = SttRunConfigValidator.validate(config)
        assertNull("Valid config must pass validation", result)
    }

    @Test
    fun sttRunConfigValidator_rejectsInvalidConfig() {
        val config = SttRunConfig(
            ttsEngineConfig = TtsEngineConfig(
                modelPath = "",
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
        val result = SttRunConfigValidator.validate(config)
        assertNotNull("Config with blank modelPath must be rejected", result)
    }

    @Test
    fun speechToTextPublicMethods_exist() {
        val methods = SpeechToText::class.java.methods.map { it.name }.toSet()
        assertTrue(methods.contains("startSession"))
        assertTrue(methods.contains("stop"))
        assertTrue(methods.contains("stopAndTranscribe"))
        assertTrue(methods.contains("setOnResultListener"))
        assertTrue(methods.contains("setOnResultWithTimingListener"))
        assertTrue(methods.contains("setOnErrorListener"))
        assertTrue(methods.contains("destroy"))
        assertTrue("setConfig must exist as a public method", methods.contains("setConfig"))
        assertTrue("startSession must exist as a public method", methods.contains("startSession"))
    }

    @Test
    fun sessionResult_isPublic() {
        val resultClass = SessionResult::class.java
        assertNotNull("SessionResult must be a public class", resultClass)
        val constructors = resultClass.constructors
        assertTrue("SessionResult must have at least one constructor", constructors.isNotEmpty())
    }

    @Test
    fun sttRunConfigTypes_arePublic() {
        // Verify the new config types are accessible from the stt package
        assertNotNull(SttRunConfig::class.java)
        assertNotNull(TtsEngineConfig::class.java)
        assertNotNull(VadConfig::class.java)
        assertNotNull(StartStrategyConfig::class.java)
        assertNotNull(StopStrategyConfig::class.java)
    }

    @Test
    fun audioCapturePublicMethods_exist() {
        val methods = AudioCapture::class.java.methods.map { it.name }.toSet()
        assertTrue(methods.contains("start"))
        assertTrue(methods.contains("stop"))
    }

    @Test
    fun whisperBridgeApi_isLoadAndTranscribeOnlyForPublicEntryPoints() {
        val bridgeClass = Class.forName(
            "dev.barrycade.voicecore.stt.WhisperBridge",
            false,
            javaClass.classLoader
        )
        val methods = bridgeClass.methods.map { it.name }.toSet()
        assertTrue(methods.contains("loadModel"))
        assertTrue(methods.contains("transcribe"))
        assertNotNull(bridgeClass)
    }
}