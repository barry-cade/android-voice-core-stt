package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        assertEquals("/dummy/path", config.modelPath)
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

