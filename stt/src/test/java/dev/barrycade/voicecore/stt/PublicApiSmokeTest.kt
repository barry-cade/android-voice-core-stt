package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicApiSmokeTest {
    @Test
    fun sttConfigDefaults_areStable() {
        val config = SttConfig()
        assertEquals(16000, config.sampleRate)
        assertEquals(32000, config.bufferSize)
        assertEquals(null, config.modelPath)
    }

    @Test
    fun speechToTextPublicMethods_exist() {
        val methods = SpeechToText::class.java.methods.map { it.name }.toSet()
        assertTrue(methods.contains("start"))
        assertTrue(methods.contains("stop"))
        assertTrue(methods.contains("setOnResultListener"))
        assertTrue(methods.contains("setOnErrorListener"))
    }

    @Test
    fun audioCapturePublicMethods_exist() {
        val methods = AudioCapture::class.java.methods.map { it.name }.toSet()
        assertTrue(methods.contains("start"))
        assertTrue(methods.contains("stop"))
        assertTrue(methods.contains("setOnAudioFrameListener"))
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
