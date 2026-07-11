package dev.barrycade.voicecore.stt

/**
 * Fake [WhisperModel] for unit tests.
 *
 * Simulates model lifecycle without JNI or native libraries.
 * Tracks calls and returns deterministic values.
 */
internal class FakeWhisperModel : WhisperModel {

    /** True after [loadModel] is called, false after [unloadModel]. */
    var isLoaded: Boolean = false
        private set

    /** Number of times [loadModel] was called. */
    var loadCount: Int = 0
        private set

    /** Number of times [transcribe] was called. */
    var transcribeCount: Int = 0
        private set

    /** Number of times [unloadModel] was called. */
    var unloadCount: Int = 0
        private set

    /** Number of times [warmup] was called. */
    var warmupCount: Int = 0
        private set

    /** Last [durationMs] passed to [warmup]. */
    var lastWarmupDurationMs: Int = 0
        private set

    /** The last model path passed to [loadModel]. */
    var lastModelPath: String? = null
        private set

    /** The last PCM samples passed to [transcribe]. */
    var lastTranscribeInput: ShortArray? = null
        private set

    /** When true, [loadModel] throws [RuntimeException]. */
    var failOnLoad: Boolean = false

    /** When true, [transcribe] throws [RuntimeException]. */
    var failOnTranscribe: Boolean = false

    /** When true, [unloadModel] throws [RuntimeException]. */
    var failOnUnload: Boolean = false

    /** Return value from [transcribe]. */
    var transcriptResult: String = "test transcript"

    override fun loadModel(modelPath: String) {
        if (failOnLoad) throw RuntimeException("Fake: loadModel failed")
        loadCount++
        lastModelPath = modelPath
        isLoaded = true
    }

    override fun transcribe(samples: ShortArray): String {
        if (failOnTranscribe) throw RuntimeException("Fake: transcribe failed")
        transcribeCount++
        lastTranscribeInput = samples
        return transcriptResult
    }

    override fun unloadModel() {
        if (failOnUnload) throw RuntimeException("Fake: unloadModel failed")
        unloadCount++
        isLoaded = false
    }

    override fun warmup(durationMs: Int) {
        warmupCount++
        lastWarmupDurationMs = durationMs
    }

    /** Reset all tracking state. */
    fun reset() {
        isLoaded = false
        loadCount = 0
        transcribeCount = 0
        unloadCount = 0
        lastModelPath = null
        lastTranscribeInput = null
        transcriptResult = "test transcript"
    }
}
