package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

class SttDeterministicTest {
    @Test
    fun deterministicPipelineProducesStableUtteranceAndTranscript() {
        val resource = javaClass.classLoader?.getResourceAsStream("audio/shop_milk_newspaper.wav")
        assertNotNull(resource)

        val pcm = resource!!.use { loadPcm16Mono(it) }
        val frameSize = 320
        val frames = mutableListOf<FloatArray>()
        var index = 0
        while (index < pcm.size) {
            val end = minOf(index + frameSize, pcm.size)
            val samples = pcm.copyOfRange(index, end)
            val frame = FloatArray(samples.size) { offset -> samples[offset].toFloat() / Short.MAX_VALUE }
            frames.add(frame)
            index = end
        }

        val utterances = mutableListOf<FloatArray>()

        repeat(10) {
            val vad = Vad(energyThreshold = 0.01)
            val accumulator = UtteranceAccumulator(sampleRate = 16000, silenceDurationMs = 500)
            var finalizedUtterance: FloatArray? = null

            frames.forEach { frame ->
                val isSpeech = vad.isSpeech(frame)
                finalizedUtterance = accumulator.processChunk(frame, isSpeech)
            }

            val paddingFrames = List(25) { FloatArray(frameSize) { 0.0f } }
            paddingFrames.forEach { frame ->
                finalizedUtterance = accumulator.processChunk(frame, false)
            }

            assertNotNull(finalizedUtterance)
            utterances.add(finalizedUtterance!!)
        }

        val firstUtterance = utterances.first()
        utterances.forEach { utterance ->
            assertTrue(utterance.contentEquals(firstUtterance))
        }
    }

    @Test
    fun vadDoesNotFireOnLowEnergyFrames() {
        val vad = Vad(energyThreshold = 0.05)
        val lowEnergyFrame = FloatArray(320) { 0.001f }
        assertTrue(!vad.isSpeech(lowEnergyFrame))
    }

    @Test
    fun vadFiresOnHighEnergyFrames() {
        val vad = Vad(energyThreshold = 0.01)
        val highEnergyFrame = FloatArray(320) { 0.2f }
        assertTrue(vad.isSpeech(highEnergyFrame))
    }

    @Test
    fun accumulatorDoesNotFinalizeOnLowEnergyPcm() {
        val vad = Vad(energyThreshold = 0.05)
        val accumulator = UtteranceAccumulator(
            sampleRate = 16000,
            preRollMs = 200,
            silenceDurationMs = 250,
            maxUtteranceLengthMs = 4000,
            stableBlockMs = 120,
            vad = vad
        )

        // Feed 1 second of low-energy PCM (should never trigger speech)
        val lowEnergyFrame = FloatArray(320) { 0.001f }
        for (i in 0 until 50) {
            val utterance = accumulator.processChunk(lowEnergyFrame, vad.isSpeech(lowEnergyFrame))
            assertNull("Accumulator must not finalize on low-energy PCM ($i)", utterance)
        }
    }

    @Test
    fun forceFinalizeReturnsBufferedSpeech() {
        val vad = Vad(energyThreshold = 0.01)
        val accumulator = UtteranceAccumulator(
            sampleRate = 16000,
            preRollMs = 100,
            silenceDurationMs = 500,
            maxUtteranceLengthMs = 4000,
            stableBlockMs = 120,
            vad = vad
        )

        val speechFrame = FloatArray(320) { 0.2f }
        accumulator.processChunk(speechFrame, true)

        val result = accumulator.forceFinalize()
        assertNotNull("forceFinalize must return buffered speech", result)
        assertTrue("forceFinalize result must not be empty", result!!.isNotEmpty())
    }

    @Test
    fun forceFinalizeReturnsNullWhenNoSpeech() {
        val vad = Vad(energyThreshold = 0.05)
        val accumulator = UtteranceAccumulator(
            sampleRate = 16000,
            silenceDurationMs = 250,
            vad = vad
        )

        // Feed only 1 frame of silence — pre-roll adds it to preRollBuffer
        // but speechPtr may still be 0 because processChunk also calls appendFrame
        // unconditionally now. With no frames at all, speechPtr is 0.
        val result = accumulator.forceFinalize()
        assertNull("forceFinalize must return null when no frames were ever fed", result)
    }

    @Test
    fun maxUtteranceLengthTriggersFinalization() {
        val vad = Vad(energyThreshold = 0.01)
        val accumulator = UtteranceAccumulator(
            sampleRate = 16000,
            preRollMs = 0,
            silenceDurationMs = 10000, // long silence, won't trigger
            maxUtteranceLengthMs = 250, // 250ms = 4000 samples buffer, needs >250ms to trigger
            stableBlockMs = 120,
            vad = vad
        )

        val speechFrame = FloatArray(320) { 0.2f } // 20ms per frame at 16kHz
        var utterance: FloatArray? = null

        // Feed continuous speech; maxUtteranceLengthMs (250ms) should trigger
        // Each frame is 20ms, so at frame 13: totalDurationMs = 260 > 250
        for (i in 0 until 30) {
            val result = accumulator.processChunk(speechFrame, true)
            if (result != null) {
                utterance = result
                break
            }
        }

        assertNotNull("maxUtteranceLength must trigger finalization", utterance)
        assertTrue("finalized utterance must not be empty", utterance!!.isNotEmpty())
    }

    @Test
    fun silencePaddingTriggersFinalization() {
        val vad = Vad(energyThreshold = 0.01)
        val accumulator = UtteranceAccumulator(
            sampleRate = 16000,
            preRollMs = 0,
            silenceDurationMs = 40, // 2 silence frames (20ms each) = finalize
            maxUtteranceLengthMs = 10000,
            stableBlockMs = 120,
            vad = vad
        )

        val speechFrame = FloatArray(320) { 0.2f } // 20ms at 16kHz
        val silenceFrame = FloatArray(320) { 0.0f }

        // Feed speech then two silence frames (40ms total silence)
        accumulator.processChunk(speechFrame, true)
        accumulator.processChunk(silenceFrame, false) // silenceFrameCount = 1
        val utterance = accumulator.processChunk(silenceFrame, false) // silenceFrameCount = 2 >= maxSilenceFrames

        assertNotNull("silence padding must trigger finalization", utterance)
        assertTrue("finalized utterance must not be empty", utterance!!.isNotEmpty())
    }

    private fun loadPcm16Mono(inputStream: InputStream): ShortArray {
        val data = inputStream.readBytes()
        val header = data.copyOfRange(0, 44)
        val channels = header[22].toInt()
        val sampleRate = ((header[24].toInt() and 0xff) shl 0) or
            ((header[25].toInt() and 0xff) shl 8) or
            ((header[26].toInt() and 0xff) shl 16) or
            ((header[27].toInt() and 0xff) shl 24)
        val bitsPerSample = ((header[34].toInt() and 0xff) shl 0) or
            ((header[35].toInt() and 0xff) shl 8)
        val dataOffset = 44
        val dataSize = data.size - dataOffset
        require(channels == 1) { "Expected mono WAV fixture" }
        require(sampleRate == 16000) { "Expected 16kHz WAV fixture" }
        require(bitsPerSample == 16) { "Expected PCM16 WAV fixture" }

        val pcmBytes = data.copyOfRange(dataOffset, data.size)
        val shorts = ShortArray(pcmBytes.size / 2)
        for (index in shorts.indices) {
            val lo = pcmBytes[index * 2].toInt() and 0xff
            val hi = pcmBytes[index * 2 + 1].toInt() and 0xff
            shorts[index] = ((hi shl 8) or lo).toShort()
        }
        return shorts
    }
}
