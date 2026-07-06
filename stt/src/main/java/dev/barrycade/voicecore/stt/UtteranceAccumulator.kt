package dev.barrycade.voicecore.stt

/**
 * UtteranceAccumulator transforms incoming FloatArray frames into complete utterance buffers.
 *
 * Three deterministic latency-stabilisation rules are enforced for short commands:
 *
 * 1. Pre-roll (100ms):
 *    PCM is kept and appended to the utterance buffer. Only speech detection
 *    is disabled during pre-roll. This ensures STOP always has real audio to
 *    finalize, even with large (1-second) AudioCapture frames.
 *
 * 2. Trailing silence clamp (250ms):
 *    After STOP or VAD finalisation, 250ms of synthetic silence is appended.
 *    Always present, always identical, always mel-light.
 *
 * 3. Minimum utterance length (700ms):
 *    If utterance duration < 700ms after trailing silence, pad with silence
 *    until total duration = 700ms. Mel-light padding appended after trailing silence.
 *
 * These rules guarantee a deterministic mel-shape for short commands regardless
 * of STOP timing.
 *
 * Testing hook: internal [forceTimeout] flag causes immediate max-utterance finalization
 * the next time speech is detected.
 */
internal class UtteranceAccumulator(
    private val sampleRate: Int = 16000,
    private val preRollMs: Int = 100,
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

    companion object {
        /** Fixed pre-roll window before speech is accepted. */
        private const val PRE_ROLL_MS: Int = 100

        /** Fixed trailing silence appended after STOP or VAD finalisation. */
        private const val TRAILING_SILENCE_MS: Int = 250

        /** Fixed minimum utterance length (pre-roll + speech + trailing silence). */
        private const val MIN_UTTERANCE_LENGTH_MS: Int = 700
    }

    /** Error listener forwarded from SpeechToText for structured error reporting. */
    internal var sttErrorListener: SttErrorListener? = null

    /** Testing hook: when true, simulates max-utterance timeout on first speech frame. */
    internal var forceTimeout: Boolean = false

    /**
     * Callback invoked when a new utterance starts (PRE_ROLL → SPEECH transition).
     * Fires before any accumulation for the utterance begins.
     * SttProcessor uses this to reset per-utterance timing counters.
     */
    internal var onSpeechStart: (() -> Unit)? = null

    private val silenceFrameDurationMs = 20
    private val maxSilenceFrames = (silenceDurationMs / silenceFrameDurationMs).coerceAtLeast(1)
    private val stableBlockSamples = (sampleRate * stableBlockMs / 1000).coerceAtLeast(1)

    /** Pre-roll samples at 16kHz. */
    private val preRollSamples: Int = (sampleRate * PRE_ROLL_MS / 1000).coerceAtLeast(1)

    /** Trailing silence samples at 16kHz. */
    private val trailingSilenceSamples: Int = (sampleRate * TRAILING_SILENCE_MS / 1000).coerceAtLeast(1)

    /** Minimum utterance samples at 16kHz (total PCM duration). */
    private val minUtteranceSamples: Int = (sampleRate * MIN_UTTERANCE_LENGTH_MS / 1000).coerceAtLeast(1)

    // Buffer that accumulates PCM for the current utterance.
    // Includes pre-roll frames — pre-roll no longer discards PCM.
    private val speechAccumulator = mutableListOf<Float>()
    private var speechActive = false

    /** Tracks whether pre-roll window has completed. */
    private var preRollComplete: Boolean = false

    /** Frames accumulated during pre-roll before speech is first detected. */
    private var preRollFrameCount: Int = 0

    private var silenceFrameCount = 0
    private var totalDurationMs = 0

    /** Captured utterance duration at last finalization (ms). 0 if no utterance has completed. */
    internal var lastUtteranceDurationMs: Int = 0
        private set

    /**
     * Returns a fixed-length array of silence samples (all zeros).
     * Mel-light: zero amplitude, no energy, minimal mel spectrum contribution.
     */

    fun processChunk(frame: FloatArray, isSpeechFrame: Boolean): FloatArray? {
        if (frame.isEmpty()) return null

        val frameDurationMs = frame.size * 1000 / sampleRate
        totalDurationMs += frameDurationMs

        // ── Pre-roll: delay speech detection, but KEEP the PCM ──────────
        // Pre-roll frames are appended to the accumulator so STOP always has
        // real audio to finalize. Only the "speech started" transition is
        // delayed until pre-roll completes.
        if (!preRollComplete) {
            preRollFrameCount += 1
            // Keep the PCM — append every sample unconditionally
            for (sample in frame) {
                speechAccumulator.add(sample)
            }
            val preRollFrameTarget = (PRE_ROLL_MS / frameDurationMs).coerceAtLeast(1)
            if (preRollFrameCount >= preRollFrameTarget) {
                preRollComplete = true
                SttLogger.pcm("[PREROLL] preRollMs=$PRE_ROLL_MS complete")
            }
            // Return null — speech detection is delayed, but PCM is preserved.
            return null
        }

        // Append every frame unconditionally — append-only, no removals.
        for (sample in frame) {
            speechAccumulator.add(sample)
        }

        if (speechActive) {
            silenceFrameCount = if (isSpeechFrame) 0 else silenceFrameCount + 1

            // ── Minimum length guard: prevent early VAD finalisation ─────
            // VAD must not finalise silence until minimum utterance length
            // (700ms) is satisfied. Override early finalisation and continue
            // accumulating PCM until the minimum is met.
            val canFinalise = (silenceFrameCount >= maxSilenceFrames)
            val minimumMet = (speechAccumulator.size * 1000 / sampleRate) >= MIN_UTTERANCE_LENGTH_MS

            if (canFinalise && minimumMet) {
                return finalizeUtterance()
            }

            if (totalDurationMs >= maxUtteranceLengthMs) {
                SttLogger.pcm("max utterance exceeded: durationMs=$totalDurationMs, limit=$maxUtteranceLengthMs")
                val error = SttError(
                    category = SttErrorCategory.UNKNOWN,
                    code = SttErrorCode.INTERNAL_EXCEPTION,
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
                    SttLogger.error("forcedFailure: PIPELINE_ILLEGAL_STATE")
                    val error = SttError(
                        category = SttErrorCategory.UNKNOWN,
                        code = SttErrorCode.PIPELINE_ILLEGAL_STATE,
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
        preRollComplete = false
        preRollFrameCount = 0
    }

    /**
     * forceFinalize returns all buffered PCM, even if VAD never fired.
     * Returns null only when no frames have ever been buffered.
     */
    fun forceFinalize(): FloatArray? {
        if (speechAccumulator.isEmpty()) return null
        return finalizeUtterance()
    }

    /**
     * finaliseUtterance finalises the current utterance and returns the PCM buffer.
     * Called only from the deterministic Stop path, after stopRequested has been set.
     * Logs the final PCM size for diagnostic clarity.
     */
    fun finaliseUtterance(): FloatArray? {
        val pcm = forceFinalize()
        if (pcm != null) {
            SttLogger.pcm("[PCM] final pcm size=${pcm.size}")
        }
        return pcm
    }

    /**
     * resetForNextUtterance clears all state for the next utterance cycle.
     * Used in Streaming Mode after an utterance has been transcribed and dispatched.
     * Must not leak any PCM between utterances.
     */
    fun resetForNextUtterance() {
        speechAccumulator.clear()
        speechActive = false
        silenceFrameCount = 0
        totalDurationMs = 0
        preRollComplete = false
        preRollFrameCount = 0
        SttLogger.pcm("[STREAM] accumulator reset")
    }

    internal fun currentDurationMs(): Int = totalDurationMs

    /**
     * Returns the total duration of the current utterance in milliseconds.
     * Same as [currentDurationMs] but named for clarity at finalization time.
     */
    internal fun utteranceDurationMs(): Int = totalDurationMs

    /**
     * Applies deterministic latency-stabilisation to the utterance PCM:
     *
     * 1. Append trailing silence (250ms) — always present, always identical.
     * 2. Enforce minimum utterance length (700ms) — pad if shorter.
     * 3. Stable-block pad to align with whisper chunk sizing.
     *
     * Logs all three metrics for runtime verification.
     */
    private fun applyLatencyStabilisation(utterance: FloatArray): FloatArray {
        val originalSampleCount = utterance.size
        val originalDurationMs = originalSampleCount * 1000 / sampleRate

        // Rule 2: Append trailing silence (250ms)
        val withTrailingSilence = FloatArray(originalSampleCount + trailingSilenceSamples)
        utterance.copyInto(withTrailingSilence, 0)
        // Remaining elements are already 0.0f (default float value = silence)
        val withTrailingDurationMs = withTrailingSilence.size * 1000 / sampleRate

        SttLogger.pcm("[STABILISE] preRollMs=$PRE_ROLL_MS trailingSilenceMs=$TRAILING_SILENCE_MS utteranceLengthMs=$withTrailingDurationMs")

        // Rule 3: Enforce minimum utterance length (700ms)
        var result = withTrailingSilence
        var resultDurationMs = withTrailingDurationMs
        if (resultDurationMs < MIN_UTTERANCE_LENGTH_MS) {
            val paddingSamples = minUtteranceSamples - result.size
            if (paddingSamples > 0) {
                val padded = FloatArray(result.size + paddingSamples)
                result.copyInto(padded, 0)
                // Remaining elements are 0.0f (mel-light silence padding)
                result = padded
                resultDurationMs = result.size * 1000 / sampleRate
                SttLogger.pcm("[STABILISE] clamped utteranceLengthMs=$resultDurationMs")
            }
        }

        lastUtteranceDurationMs = resultDurationMs

        // Stable-block pad (existing behaviour)
        val paddedLength = if (result.size % stableBlockSamples == 0) {
            result.size
        } else {
            ((result.size / stableBlockSamples) + 1) * stableBlockSamples
        }

        val finalPadded = FloatArray(paddedLength)
        result.copyInto(finalPadded, 0)
        return finalPadded
    }

    private fun finalizeUtterance(): FloatArray {
        if (speechAccumulator.isEmpty()) {
            SttLogger.pcmW("finalizeUtterance called with empty buffer, returning null")
            return FloatArray(0)
        }

        val utterance = speechAccumulator.toFloatArray()
        speechAccumulator.clear()
        speechActive = false
        silenceFrameCount = 0
        totalDurationMs = 0
        preRollComplete = false
        preRollFrameCount = 0

        val stabilised = applyLatencyStabilisation(utterance)
        return stabilised
    }
}
