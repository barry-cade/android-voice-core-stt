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
            // Parameters: t_start_max, t_end, t_max
            // (vosk-android 0.3.75 API — 3 parameters)
            // The 5 logged values (rule1-5) are internally derived:
            //   rule1 = t_start_max, rule2 = t_end,
            //   rule3 = t_end*1.5, rule4 = t_end*2, rule5 = t_max
            recognizer.setEndpointerDelays(
                config.preSpeechStartMaxMs / 1000f,
                config.postSpeechSilenceMs / 1000f,
                config.maxUtteranceMs / 1000f
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

