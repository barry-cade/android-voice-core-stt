package dev.barrycade.voicecore.vosk

import org.vosk.Model
import org.vosk.Recognizer
import java.io.IOException

/**
 * Manages a Vosk Model and Recognizer pair.
 *
 * Configures the endpointer from [VoskConfig] instead of hardcoded values.
 * The recognizer is created once and reused across capture sessions.
 *
 * @param config Configuration including model path and endpointer delays.
 */
class VoskEngine(config: VoskConfig) {

    private val model = Model(config.modelPath)
    private val recognizer: Recognizer

    init {
        try {
            recognizer = Recognizer(model, config.sampleRate)

            // Configure endpointer from config.
            val endpointerMode = when (config.endpointerMode.uppercase()) {
                "LONG" -> Recognizer.EndpointerMode.LONG
                else -> Recognizer.EndpointerMode.SHORT
            }
            recognizer.setEndpointerMode(endpointerMode)
            // Vosk's setEndpointerDelays expects float seconds.
            // Convert from milliseconds (config convention) to seconds.
            recognizer.setEndpointerDelays(
                config.postSpeechSilenceMs / 1000f,
                config.preSpeechPadMs / 1000f,
                config.maxDurationMs / 1000f
            )
        } catch (e: IOException) {
            throw RuntimeException("Failed to create Vosk recognizer", e)
        }
    }

    fun accept(pcm: FloatArray): Boolean {
        return recognizer.acceptWaveForm(pcm, pcm.size)
    }

    fun acceptShort(pcm: ShortArray): Boolean {
        return recognizer.acceptWaveForm(pcm, pcm.size)
    }

    fun result(): String {
        return recognizer.result
    }

    fun finalResult(): String {
        return recognizer.finalResult
    }

    fun partialResult(): String {
        return recognizer.partialResult
    }

    fun close() {
        recognizer.close()
        model.close()
    }
}

