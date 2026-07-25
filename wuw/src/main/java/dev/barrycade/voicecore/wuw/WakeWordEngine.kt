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

    /** Fixed-size sliding PCM buffer (2 seconds @ 16kHz). */
    private val maxBufferSize = 32000
    private var pcmBuffer = ShortArray(maxBufferSize)
    private var currentBufferSize = 0

    /** Number of processed frames since last reset. Used for periodic checks. */
    private var processedFrames: Int = 0

    /** Check every N frames to avoid excessive computation. */
    @Volatile
    var checkIntervalFrames: Int = 5

    /** K constant for exponential similarity mapping: similarity = e^(-k * avgDistance). */
    @Volatile
    var similarityK: Float = 0.5f

    /**
     * Delegated access to MFCC pre-emphasis alpha.
     * Changing this affects both recording and live matching.
     */
    var preEmphasisAlpha: Float
        get() = mfccExtractor.preEmphasisAlpha
        set(value) { mfccExtractor.preEmphasisAlpha = value }

    /**
     * Set the reference MFCC template for matching.
     * Normalizes the template frames for volume-invariant matching.
     *
     * @param mfccFrames Reference wake-word MFCC frames.
     */
    fun setTemplate(mfccFrames: List<FloatArray>) {
        template = mfccExtractor.normalizeFrames(mfccFrames)
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
     * Apply multiple calibration parameters at once from the UI.
     * Fields that are not relevant (e.g., threshold is set separately) are unchanged.
     */
    fun applyCalibration(
        minFrames: Int,
        maxFrames: Int,
        checkInterval: Int,
        similarityKValue: Float
    ) {
        minFramesForMatch = minFrames
        maxFramesForMatch = maxFrames
        checkIntervalFrames = checkInterval
        similarityK = similarityKValue
    }

    /**
     * Process incoming PCM samples using a sliding window.
     *
     * @param pcm Short array of PCM samples (16 kHz, mono).
     */
    fun processPcm(pcm: ShortArray) {
        val templ = template ?: return

        // Manage sliding window: shift old samples if new ones won't fit
        if (currentBufferSize + pcm.size > maxBufferSize) {
            val keep = maxBufferSize - pcm.size
            if (keep > 0) {
                System.arraycopy(pcmBuffer, currentBufferSize - keep, pcmBuffer, 0, keep)
                currentBufferSize = keep
            } else {
                currentBufferSize = 0
            }
        }

        // Add new samples
        System.arraycopy(pcm, 0, pcmBuffer, currentBufferSize, pcm.size)
        currentBufferSize += pcm.size

        processedFrames += 1
        if (processedFrames < checkIntervalFrames) return
        processedFrames = 0

        // Ensure enough audio for extraction
        if (currentBufferSize < mfccExtractor.frameSize) return

        // Extract and Normalize MFCC from the sliding window
        val windowPcm = pcmBuffer.copyOfRange(0, currentBufferSize)
        val rawLiveFrames = mfccExtractor.extract(windowPcm)
        if (rawLiveFrames.size < minFramesForMatch) return

        // Take only the most recent frames
        val recentRawFrames = if (rawLiveFrames.size > maxFramesForMatch) {
            rawLiveFrames.subList(rawLiveFrames.size - maxFramesForMatch, rawLiveFrames.size)
        } else {
            rawLiveFrames
        }

        val liveFrames = mfccExtractor.normalizeFrames(recentRawFrames)

        // Compute DTW distance
        val distance = mfccExtractor.dtwDistance(templ, liveFrames)

        // Normalize distance by path length
        val avgDistance = distance / maxOf(templ.size, liveFrames.size).toFloat()

        // Exponential mapping for better visual feedback: similarity = e^(-k * d)
        // k=0.5 provides a good spread for normalized Euclidean distances.
        val similarity = kotlin.math.exp(-similarityK * avgDistance)

        similarityListener?.invoke(similarity)

        if (similarity >= threshold) {
            listener?.onWakeWordDetected()
            reset() // Clear buffer to prevent double triggers
        }
    }

    /**
     * Reset the engine state.
     *
     * Clears the PCM sliding window and frame counter.
     */
    fun reset() {
        currentBufferSize = 0
        processedFrames = 0
    }

    /**
     * Release resources held by this engine.
     *
     * Clears the template, listener, and buffer.
     */
    fun destroy() {
        reset()
        template = null
        listener = null
    }
}
