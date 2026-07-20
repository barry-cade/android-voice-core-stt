package dev.barrycade.voicecore.vosk

import org.vosk.Model
import org.vosk.Recognizer
import java.io.IOException

class VoskEngine(modelPath: String) {

    private val model = Model(modelPath)
    private val recognizer: Recognizer

    init {
        try {
            recognizer = Recognizer(model, 16000.0f)
            // Longer endpointer delays to avoid premature utterance end.
            recognizer.setEndpointerMode(Recognizer.EndpointerMode.LONG)
            // Very long silence timeout: 15s initial, 5s mid, 30s max.
            recognizer.setEndpointerDelays(15.0f, 5.0f, 30.0f)
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
