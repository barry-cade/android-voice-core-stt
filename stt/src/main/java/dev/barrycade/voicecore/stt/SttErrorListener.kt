package dev.barrycade.voicecore.stt

/**
 * Listener that receives structured error information from the STT subsystem.
 * Every failure must invoke [onSttError]; no silent failures are permitted.
 */
internal fun interface SttErrorListener {
    fun onSttError(error: SttError)
}
