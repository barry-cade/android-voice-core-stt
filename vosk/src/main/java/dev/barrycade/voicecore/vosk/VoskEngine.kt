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
        } catch (e: IOException) {
            throw RuntimeException("Failed to create Vosk recognizer", e)
        }
    }

    fun accept(pcm: FloatArray): Boolean {
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
