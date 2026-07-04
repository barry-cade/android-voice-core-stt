package dev.barrycade.voicecore.stt

/**
 * UtteranceAccumulator transforms incoming FloatArray frames into complete utterance buffers.
 * Uses a simple append-only buffer: every frame is appended to speechAccumulator regardless
 * of VAD state. No trimming, no index math, no list removals. VAD is used only to mark
 * utterance start and end (silence padding, max duration). forceFinalize() returns all
 * buffered PCM even if VAD never fired.
 *
 * Pre-roll is disabled (no separate preRollBuffer with trimming). When VAD fires, the
 * utterance starts from whatever has been accumulated so far.
 *
 * Testing hook: internal [forceTimeout] flag causes immediate max-utterance finalization
 * the next time speech is detected.
 */
internal class UtteranceAccumulator(
    private val sampleRate: Int = 16000,
    /** Pre-roll is accepted but unused — accumulator is append-only from the start. */
    @Suppress("UNUSED_PARAMETER")
    private val preRollMs: Int = 200,
    private val silenceDurationMs: Int = 500,
    private val maxUtteranceLengthMs: Int = 7000,
    private val stableBlockMs: Int = 500,
    private val vad: Vad = Vad()
) {
    constructor(config: RuntimeSttConfig, sampleRate: Int = 16000, vad: Vad = Vad(config)) : this(
        sampleRate = sampleRate,
        preRollMs = config.preRollMs,
        silenceDurationMs = config.silencePaddingMs,
        maxUtteranceLengthMs = config.maxUtteranceLengthMs,
        stableBlockMs = config.stableChunkSizeMs,
        vad = vad
    )

    /** Error listener forwarded from SpeechToText for structured error reporting. */
    internal var sttErrorListener: SttErrorListener? = null

    /** Testing hook: when true, simulates max-utterance timeout on first speech frame. */
    internal var forceTimeout: Boolean = false

    /**
     * Callback invoked when a new utterance starts (SILENCE → SPEECH transition).
     * Fires before any accumulation for the utterance begins.
     * SttProcessor uses this to reset per-utterance timing counters.
     */
    internal var onSpeechStart: (() -> Unit)? = null

    private val silenceFrameDurationMs = 20
    private val maxSilenceFrames = (silenceDurationMs / silenceFrameDurationMs).coerceAtLeast(1)
    private val stableBlockSamples = (sampleRate * stableBlockMs / 1000).coerceAtLeast(1)

    // Append-only buffer — never shrinks or trims during an utterance.
    private val speechAccumulator = mutableListOf<Float>()
    private var speechActive = false
    private var silenceFrameCount = 0
    private var totalDurationMs = 0

    /** Captured utterance duration at last finalization (ms). 0 if no utterance has completed. */
    internal var lastUtteranceDurationMs: Int = 0
        private set

    fun processChunk(frame: FloatArray, isSpeechFrame: Boolean): FloatArray? {
        if (frame.isEmpty()) return null

        val frameDurationMs = frame.size * 1000 / sampleRate
        totalDurationMs += frameDurationMs

        // Append every frame unconditionally — append-only, no removals.
        for (sample in frame) {
            speechAccumulator.add(sample)
        }

        if (speechActive) {
            silenceFrameCount = if (isSpeechFrame) 0 else silenceFrameCount + 1
            if (silenceFrameCount >= maxSilenceFrames) {
                return finalizeUtterance()
            }
            if (totalDurationMs >= maxUtteranceLengthMs) {
                SttLogger.pcm("max utterance exceeded: durationMs=$totalDurationMs, limit=$maxUtteranceLengthMs")
                val error = SttError(
                    category = SttErrorCategory.TIMEOUT,
                    code = SttErrorCode.TIMEOUT_MAX_UTTERANCE,
                    message = "Max utterance length exceeded: ${totalDurationMs}ms > ${maxUtteranceLengthMs}ms",
                    lastRms = vad.lastFrameEnergy,
                    lastVadState = true,
                    context = mapOf(
                        "totalDurationMs" to totalDurationMs,
                        "maxUtteranceLengthMs" to maxUtteranceLengthMs,
                        "bufferSize" to speechAccumulator.size
                    )
                )
                sttErrorListener?.onSttError(error)
                return finalizeUtterance()
            }
            return null
        } else {
            if (isSpeechFrame) {
                speechActive = true
                silenceFrameCount = 0
                totalDurationMs = 0

                // ── Notify SttProcessor to reset per-utterance counters ──
                onSpeechStart?.invoke()

                // ── Testing hook: forceTimeout on first speech frame ──────
                if (forceTimeout) {
                    SttLogger.error("forcedFailure: TIMEOUT_MAX_UTTERANCE")
                    val error = SttError(
                        category = SttErrorCategory.TIMEOUT,
                        code = SttErrorCode.TIMEOUT_MAX_UTTERANCE,
                        message = "Forced test failure: max utterance timeout",
                        lastRms = vad.lastFrameEnergy,
                        lastVadState = true,
                        context = mapOf("forcedFailure" to "forceTimeout")
                    )
                    sttErrorListener?.onSttError(error)
                    return finalizeUtterance()
                }
            }
            return null
        }
    }

    fun processFrame(frame: FloatArray): FloatArray? = processChunk(frame, vad.isSpeech(frame))

    fun reset() {
        speechAccumulator.clear()
        speechActive = false
        silenceFrameCount = 0
        totalDurationMs = 0
    }

    /**
     * forceFinalize returns all buffered PCM, even if VAD never fired.
     * Returns null only when no frames have ever been buffered.
     */
    fun forceFinalize(): FloatArray? {
        if (speechAccumulator.isEmpty()) return null
        return finalizeUtterance()
    }

    internal fun currentDurationMs(): Int = totalDurationMs

    /**
     * Returns the total duration of the current utterance in milliseconds.
     * Same as [currentDurationMs] but named for clarity at finalization time.
     */
    internal fun utteranceDurationMs(): Int = totalDurationMs

    private fun finalizeUtterance(): FloatArray {
        if (speechAccumulator.isEmpty()) {
            SttLogger.pcmW("finalizeUtterance called with empty buffer, returning null")
            return FloatArray(0)
        }

        lastUtteranceDurationMs = totalDurationMs

        val utterance = speechAccumulator.toFloatArray()
        val paddedLength = if (utterance.size % stableBlockSamples == 0) {
            utterance.size
        } else {
            ((utterance.size / stableBlockSamples) + 1) * stableBlockSamples
        }

        val padded = FloatArray(paddedLength)
        utterance.copyInto(padded, 0)
        speechAccumulator.clear()
        speechActive = false
        silenceFrameCount = 0
        totalDurationMs = 0
        return padded
    }
}
