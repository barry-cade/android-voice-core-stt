package dev.barrycade.voicecore.wuw

/**
 * Callback interface for wake-word detection events.
 *
 * All callbacks are delivered on the thread that calls [processPcm].
 * The caller is responsible for dispatching to the main thread if needed.
 */
fun interface WakeWordListener {
    /** Fired when the wake word is detected (similarity >= threshold). */
    fun onWakeWordDetected()
}

/**
 * Engine that processes live PCM audio and fires a callback when a
 * wake word is detected via MFCC + DTW template matching.
 *
 * The engine:
 * 1. Accumulates PCM frames into a rolling buffer.
 * 2. Periodically converts the buffer to MFCC features.
 * 3. Computes DTW distance between the live features and the reference template.
 * 4. Converts DTW distance to a similarity score.
 * 5. Fires [WakeWordListener.onWakeWordDetected] if similarity >= threshold.
 *
 * The engine is stateful — it holds a rolling PCM buffer. Call [reset]
 * to clear accumulated audio (e.g. after detection or mode switch).
 */
class WakeWordEngine(
    private val mfccExtractor: MfccExtractor = MfccExtractor()
) {
    /** Similarity threshold in [0, 1]. Higher = stricter. Default 0.7. */
    @Volatile
    var threshold: Float = 0.7f

    /** Minimum number of MFCC frames to attempt a match. */
    var minFramesForMatch: Int = 10

    /** Maximum number of MFCC frames to consider (rolling window). */
    var maxFramesForMatch: Int = 50

    /** Callback fired on wake-word detection. */
    var listener: WakeWordListener? = null

    /** Callback fired for every similarity score calculation. */
    var similarityListener: ((Float) -> Unit)? = null

    /** Reference MFCC template. Null until set via [setTemplate]. */
    private var template: List<FloatArray>? = null

    /** Rolling PCM buffer. */
    private val pcmBuffer = mutableListOf<Short>()

    /** Number of processed frames since last reset. Used for periodic checks. */
    private var processedFrames: Int = 0

    /** Check every N frames to avoid excessive computation. */
    private val checkIntervalFrames: Int = 5

    /**
     * Set the reference MFCC template for matching.
     *
     * @param mfccFrames Reference wake-word MFCC frames.
     */
    fun setTemplate(mfccFrames: List<FloatArray>) {
        template = mfccFrames
    }

    /**
     * Set the similarity threshold.
     *
     * @param value Threshold in [0, 1]. Higher = stricter.
     */
    @JvmName("configureThreshold")
    fun setThreshold(value: Float) {
        threshold = value.coerceIn(0f, 1f)
    }

    /**
     * Set the wake-word detection listener.
     *
     * @param listener Callback for detection events.
     */
    @JvmName("configureListener")
    fun setListener(listener: WakeWordListener) {
        this.listener = listener
    }

    /**
     * Process incoming PCM samples.
     *
     * Accumulates samples and periodically runs MFCC extraction + DTW matching
     * against the reference template. Fires [WakeWordListener.onWakeWordDetected]
     * if similarity meets the threshold.
     *
     * @param pcm Short array of PCM samples (16 kHz, mono).
     */
    fun processPcm(pcm: ShortArray) {
        val templ = template
        if (templ == null) {
            return
        }

        // Accumulate.
        for (sample in pcm) {
            pcmBuffer.add(sample)
        }

        processedFrames += 1
        if (processedFrames < checkIntervalFrames) {
            return
        }
        processedFrames = 0

        // Ensure we have enough audio for at least minFramesForMatch frames.
        val requiredSamples = mfccExtractor.frameSize + (minFramesForMatch - 1) * mfccExtractor.frameStride
        if (pcmBuffer.size < requiredSamples) {
            return
        }

        // Convert rolling buffer to ShortArray for extraction.
        val pcmArray = ShortArray(pcmBuffer.size) { pcmBuffer[it] }

        // Extract MFCC from the buffer.
        val liveFrames = mfccExtractor.extract(pcmArray)

        if (liveFrames.size < minFramesForMatch) {
            return
        }

        // Use only the most recent frames, up to maxFramesForMatch.
        val recentFrames = if (liveFrames.size > maxFramesForMatch) {
            liveFrames.subList(liveFrames.size - maxFramesForMatch, liveFrames.size)
        } else {
            liveFrames
        }

        // Compute DTW distance.
        val distance = mfccExtractor.dtwDistance(templ, recentFrames)

        // Convert distance to similarity score.
        // Lower DTW distance = better match.
        // Normalise by number of frames to make it length-invariant.
        val avgDistance = distance / maxOf(templ.size, recentFrames.size).toFloat()

        // Map average distance to similarity in [0, 1].
        // distance=0 → similarity=1.0, distance grows → similarity drops.
        val similarity = 1f / (1f + avgDistance)

        similarityListener?.invoke(similarity)

        if (similarity >= threshold) {
            listener?.onWakeWordDetected()
            // Clear buffer after detection to avoid re-triggering.
            pcmBuffer.clear()
        }
    }

    /**
     * Reset the engine state.
     *
     * Clears the PCM rolling buffer and frame counter.
     * Does NOT clear the template or listener.
     */
    fun reset() {
        pcmBuffer.clear()
        processedFrames = 0
    }

    /**
     * Release resources held by this engine.
     *
     * Clears the template, listener, and buffer.
     * After calling this, the engine should not be reused.
     */
    fun destroy() {
        pcmBuffer.clear()
        template = null
        listener = null
        processedFrames = 0
    }
}
