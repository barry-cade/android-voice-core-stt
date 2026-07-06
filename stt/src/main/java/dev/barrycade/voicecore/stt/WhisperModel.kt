package dev.barrycade.voicecore.stt

/**
 * Interface for Whisper model lifecycle and transcription.
 *
 * Extracted from [WhisperBridge] to enable testability:
 * production uses [JniWhisperModel], tests use [FakeWhisperModel].
 */
internal interface WhisperModel {
    fun loadModel(modelPath: String)
    fun transcribe(samples: ShortArray): String
    fun unloadModel()
}
