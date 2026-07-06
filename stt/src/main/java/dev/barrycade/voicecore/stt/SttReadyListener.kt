package dev.barrycade.voicecore.stt

/**
 * Callback interface notified when the STT model has completed loading and warm-up.
 *
 * After [onSttReady] is invoked, [SpeechToText.start] may be called safely.
 * The callback is invoked exactly once per model load, on the model executor
 * thread (not the main/UI thread).
 *
 * @see SpeechToText
 * @see ModelManager.isReady
 */
fun interface SttReadyListener {
    fun onSttReady()
}
