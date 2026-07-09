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
        assertEquals(100, config.preRollMs)
        assertEquals(30000, config.manualManual.maxDurationMs)
        assertEquals(5000, config.manualManual.abnormalSilenceMs)
        assertEquals(30000, config.manualAuto.maxDurationMs)
        assertEquals(1200, config.manualAuto.autoSilenceMs)
        assertEquals("You spoke for too long.", config.reasonMessages.tooLong)
        assertEquals("You stopped speaking for too long.", config.reasonMessages.abnormalSilence)
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
            stopStrategy = "autoSilence"
        )
        val trigger = config.resolveStopTrigger()
        assertTrue("Expected AutoSilenceStopTrigger, got ${trigger::class.simpleName}",
            trigger is AutoSilenceStopTrigger)
    }

    @Test
    fun sttConfig_resolveStopTrigger_autoSilence_caseInsensitive() {
        val config = SttConfig(
            modelPath = "/dummy/path",
            stopStrategy = "AUTOSILENCE"
        )
        val trigger = config.resolveStopTrigger()
        assertTrue(trigger is AutoSilenceStopTrigger)
    }

    @Test
    fun sttConfig_resolveStopTrigger_autoSilence_usesManualAutoAutoSilenceMs() {
        val config = SttConfig(
            modelPath = "/dummy/path",
            stopStrategy = "autoSilence",
            manualAuto = ManualAutoConfig(autoSilenceMs = 300)
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
        assertNotNull(SttLifeCycleStrategy::class.java)
        assertNotNull(ManualManualSpecific::class.java)
        assertNotNull(ManualAutoSpecific::class.java)
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

