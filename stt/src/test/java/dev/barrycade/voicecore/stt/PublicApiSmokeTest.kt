package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicApiSmokeTest {
    @Test
    fun sttConfigDefaults_areStable() {
        val config = SttConfig(modelPath = "/dummy/path")
        assertEquals(0.03f, config.energyThreshold, 0.001f)
        assertEquals(600, config.silencePaddingMs)
        assertEquals(100, config.preRollMs)
        assertEquals(7000, config.maxUtteranceLengthMs)
        assertEquals(500, config.stableChunkSizeMs)
        assertEquals(0.05f, config.motionModeEnergyThreshold, 0.001f)
        assertEquals(300, config.motionModeSilencePaddingMs)
        assertEquals(false, config.debugLoggingEnabled)
        assertEquals("/dummy/path", config.modelPath)
        assertEquals("manual", config.startStrategy)
        assertEquals("manual", config.stopStrategy)
    }

    @Test
    fun sttConfig_create_mapsDebugLoggingEnabled() {
        val config = SttConfig(modelPath = "/dummy/path", debugLoggingEnabled = true)
        assertEquals(true, config.debugLoggingEnabled)
    }

    @Test
    fun sttConfig_resolveStartTrigger_manual_returnsManualStartTrigger() {
        val config = SttConfig(modelPath = "/dummy/path", startStrategy = "manual")
        val trigger = config.resolveStartTrigger()
        assertTrue(trigger is ManualStartTrigger)
    }

    @Test
    fun sttConfig_resolveStopTrigger_manual_returnsManualStopTrigger() {
        val config = SttConfig(modelPath = "/dummy/path", stopStrategy = "manual")
        val trigger = config.resolveStopTrigger()
        assertTrue(trigger is ManualStopTrigger)
    }

    @Test
    fun sttConfig_resolveStartTrigger_invalid_throws() {
        val config = SttConfig(modelPath = "/dummy/path", startStrategy = "unknown")
        assertThrows(IllegalArgumentException::class.java) { config.resolveStartTrigger() }
    }

    @Test
    fun sttConfig_resolveStopTrigger_invalid_throws() {
        val config = SttConfig(modelPath = "/dummy/path", stopStrategy = "bogus")
        assertThrows(IllegalArgumentException::class.java) { config.resolveStopTrigger() }
    }

    @Test
    fun sttConfig_resolveStopTrigger_autoSilence_returnsAutoSilenceStopTrigger() {
        val config = SttConfig(
            modelPath = "/dummy/path",
            stopStrategy = "autoSilence",
            silencePaddingMs = 600
        )
        val trigger = config.resolveStopTrigger()
        assertTrue("Expected AutoSilenceStopTrigger, got ${trigger::class.simpleName}",
            trigger is AutoSilenceStopTrigger)
    }

    @Test
    fun sttConfig_resolveStopTrigger_autoSilence_caseInsensitive() {
        val config = SttConfig(
            modelPath = "/dummy/path",
            stopStrategy = "AUTOSILENCE",
            silencePaddingMs = 600
        )
        val trigger = config.resolveStopTrigger()
        assertTrue(trigger is AutoSilenceStopTrigger)
    }

    @Test
    fun sttConfig_resolveStopTrigger_autoSilence_usesSilencePaddingMs() {
        val config = SttConfig(
            modelPath = "/dummy/path",
            stopStrategy = "autoSilence",
            silencePaddingMs = 300
        )
        val trigger = config.resolveStopTrigger()
        assertTrue(trigger is AutoSilenceStopTrigger)
    }

    @Test
    fun speechToTextPublicMethods_exist() {
        val methods = SpeechToText::class.java.methods.map { it.name }.toSet()
        assertTrue(methods.contains("start"))
        assertTrue(methods.contains("stop"))
        assertTrue(methods.contains("stopAndTranscribe"))
        assertTrue(methods.contains("setOnResultListener"))
        assertTrue(methods.contains("setOnResultWithTimingListener"))
        assertTrue(methods.contains("setOnErrorListener"))
        assertTrue(methods.contains("destroy"))
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

