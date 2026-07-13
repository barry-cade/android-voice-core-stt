package dev.barrycade.voicecore.stt

import android.content.Context

/**
 * Singleton provider for [SpeechToText].
 *
 * Ensures the Whisper model is loaded exactly once per app lifetime.
 * Callers obtain the single instance via [get] and then call [SpeechToText.initStt]
 * to initialise the STT scaffolding, followed by [SpeechToText.startSession].
 *
 * Usage:
 * ```
 * val stt = SpeechToTextProvider.get(applicationContext)
 * stt.initStt(runConfig)
 * stt.startSession()
 * ```
 *
 * @see SpeechToText
 */
object SpeechToTextProvider {

    @Volatile
    private var instance: SpeechToText? = null

    /**
     * Returns the singleton [SpeechToText] instance, creating it on first access.
     *
     * The model is NOT loaded during construction. Call [SpeechToText.initStt]
     * with a valid [SttConfig] to initialise the model and STT scaffolding.
     *
     * @param context Application context (obtain via [Context.getApplicationContext]).
     * @return The shared [SpeechToText] instance.
     */
    fun get(context: Context): SpeechToText {
        val current = instance
        if (current != null) {
            return current
        }

        synchronized(this) {
            val current2 = instance
            if (current2 != null) {
                return current2
            }

            val stt = SpeechToText(context.applicationContext)
            instance = stt
            return stt
        }
    }

    /**
     * Reset the singleton for testing only.
     *
     * Destroys the current instance and clears the reference.
     * The next call to [get] will create a fresh instance.
     */
    internal fun resetForTest() {
        synchronized(this) {
            instance?.destroy()
            instance = null
        }
    }
}
