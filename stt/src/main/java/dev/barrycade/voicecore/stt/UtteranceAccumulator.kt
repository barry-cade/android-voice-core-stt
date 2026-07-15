package dev.barrycade.voicecore.stt

/**
 * UtteranceAccumulator transforms incoming FloatArray frames into complete utterance buffers.
 *
 * ## Architecture
 *
 * The accumulator follows a simple linear flow:
 *
 *   preRoll → (speech detection) → speech accumulation → silence accumulation → utterance ready
 *
 * ## Responsibility
 *
 * The accumulator manages **utterance boundaries only** — it decides when a complete
 * utterance has been accumulated. Capture (session) boundaries are the exclusive
 * responsibility of [StopStrategy].
 *
 * The accumulator NEVER:
 * - Checks strategy types (manual vs auto)
 * - Drives capture stop
 * - Calls sessionManager.end()
 * - Produces result types other than [FrameResult.Continue] and
 *   [FrameResult.UtteranceReady]
 *
 * ## FrameResult
 *
 * Each call to [processChunk] returns a [FrameResult] sealed type:
 *
 * - [FrameResult.Continue]: keep processing frames
 * - [FrameResult.UtteranceReady]: complete utterance buffer ready for transcription
 *   (caller should transcribe but NOT stop the session — [StopStrategy] decides that)
 *
 * ## No hysteresis, no minimum speech duration
 *
 * SilenceFrameCount is the sole threshold for utterance finalization.
 * Speech -> silence -> threshold -> finalize.
 * No minimum speech duration, no trailing silence padding, no stable-block alignment.
 *
 * Testing hook: internal [forceTimeout] flag causes immediate utterance finalization
 * the next time speech is detected.
 */
internal class UtteranceAccumulator(
    private val sampleRate: Int = 16000,
    private val preRollMs: Int = 100,
    private val vad: Vad = Vad(),
    private val utteranceMaxDurationMs: Int = 30000,
    private val utteranceSilenceTimeoutMs: Int = 5000,
    private val debugLoggingEnabled: Boolean = false
) {
    constructor(
        config: RuntimeSttConfig,
        sampleRate: Int = 16000,
        vad: Vad = Vad(config)
    ) : this(
        sampleRate = sampleRate,
        preRollMs = config.preRollMs,
        vad = vad,
        utteranceMaxDurationMs = config.autoMaxDurationMs,
        utteranceSilenceTimeoutMs = config.stableChunkSizeMs,
        debugLoggingEnabled = config.debugLoggingEnabled
    )

    /** Error listener forwarded from SpeechToText for structured error reporting. */
    internal var sttErrorListener: SttErrorListener? = null

    /** Testing hook: when true, simulates max-utterance timeout on first speech frame. */
    internal var forceTimeout: Boolean = false

    /**
     * Callback invoked when a new utterance starts (PRE_ROLL -> SPEECH transition).
     * Fires before any accumulation for the utterance begins.
     * SttProcessor uses this to reset per-utterance timing counters.
     */
    internal var onSpeechStart: (() -> Unit)? = null

    // Buffer that accumulates PCM for the current utterance.
    private val speechAccumulator = mutableListOf<Float>()
    private var speechActive = false

    /** Tracks whether pre-roll window has completed. */
    private var preRollComplete: Boolean = false

    /** Frames accumulated during pre-roll before speech is first detected. */
    private var preRollFrameCount: Int = 0

    private var silenceFrameCount = 0

    /**
     * Total accumulated duration since utterance start (ms).
     * Includes both speech and silence. Incremented every frame.
     * Duration starts counting when speech is first detected (speechActive = true).
     */
    private var durationMs = 0

    /** Captured utterance duration at last finalization (ms). 0 if no utterance has completed. */
    internal var lastUtteranceDurationMs: Int = 0
        private set

    fun processChunk(frame: FloatArray, isSpeechFrame: Boolean): FrameResult {
        if (frame.isEmpty()) return FrameResult.Continue

        val frameDurationMs = (frame.size * 1000) / sampleRate

        // ── PCM non-zero verification ────────────────────────────────────
        val hasNonZero = frame.any { it != 0.0f }
        if (!hasNonZero) {
            SttLogger.pcm("all-zero frame, size=${frame.size}")
        }

        // ── Speech detection logging ─────────────────────────────────────
        if (isSpeechFrame) {
            SttLogger.pcm("speech frame, energy=${vad.lastFrameEnergy}")
        }

        if (!preRollComplete) {
            return handlePreRollFrame(frame, frameDurationMs)
        }

        appendSamples(frame)

        if (speechActive) {
            // ── 1. Update silence counter ────────────────────────────────
            if (isSpeechFrame) {
                silenceFrameCount = 0
                SttLogger.pcm("speech-active reset")
            } else {
                silenceFrameCount++
                SttLogger.pcm("silenceFrameCount=$silenceFrameCount frameDurationMs=$frameDurationMs")
            }

            // ── 2. Update duration ───────────────────────────────────────
            durationMs += frameDurationMs

            // ── 3. Utterance boundary checks ─────────────────────────────
            val silenceTimeoutFrames = utteranceSilenceTimeoutMs / frameDurationMs

            // Check silence timeout within utterance.
            if (silenceFrameCount >= silenceTimeoutFrames) {
                return handleUtteranceReady()
            }

            // Check max utterance duration (safety limit).
            if (durationMs >= utteranceMaxDurationMs) {
                return handleUtteranceReady()
            }

            // ── 4. Debug logging ─────────────────────────────────────────
            if (debugLoggingEnabled) {
                SttLogger.pcmD("speech=$isSpeechFrame silenceFrames=$silenceFrameCount durationMs=$durationMs frameDurationMs=$frameDurationMs")
            }

            return FrameResult.Continue
        }

        // ── Pre-speech path (no speech detected yet, pre-roll complete) ──
        if (isSpeechFrame) {
            silenceFrameCount = 0
            return handleSpeechStart()
        }

        // ── Pre-speech speech detection fallback ─────────────────────────
        val frameEnergy = vad.lastFrameEnergy
        val energyThreshold = 0.001f
        val energyDetected = frameEnergy >= energyThreshold
        val frameHasNonZeroPCM = frame.any { it != 0.0f }
        val forceSpeech = (frameEnergy == 0.0f && frameHasNonZeroPCM)

        if (energyDetected) {
            SttLogger.pcm("speech: energy=$frameEnergy >= threshold=$energyThreshold")
            silenceFrameCount = 0
            return handleSpeechStart()
        }

        if (forceSpeech) {
            SttLogger.pcm("force speech: energy=$frameEnergy but PCM has non-zero content")
            silenceFrameCount = 0
            return handleSpeechStart()
        }

        SttLogger.pcm("silence: energy=$frameEnergy < threshold=$energyThreshold hasNonZeroPCM=$frameHasNonZeroPCM")
        return FrameResult.Continue
    }

    /**
     * Process a frame during pre-roll. PCM is saved but speech detection
     * is delayed until pre-roll completes.
     */
    private fun handlePreRollFrame(frame: FloatArray, frameDurationMs: Int): FrameResult {
        preRollFrameCount += 1
        appendSamples(frame)

        val preRollFrameTarget = (preRollMs / frameDurationMs).coerceAtLeast(1)
        if (preRollFrameCount >= preRollFrameTarget) {
            preRollComplete = true
            SttLogger.pcm("preRollMs=$preRollMs complete")
        }
        return FrameResult.Continue
    }

    /**
     * Process the first speech frame after silence.
     * Starts a new utterance. May trigger forceTimeout testing hook.
     */
    private fun handleSpeechStart(): FrameResult {
        speechActive = true
        silenceFrameCount = 0
        durationMs = 0
        SttLogger.pcm("speechActive=true, durationMs=0")

        onSpeechStart?.invoke()

        if (forceTimeout) {
            SttLogger.error("forcedFailure: PIPELINE_ILLEGAL_STATE")
            val error = SttError(
                code = SttErrorCode.PIPELINE_ILLEGAL_STATE,
                message = "Forced test failure: max utterance timeout",
                details = listOf(
                    "forcedFailure=forceTimeout",
                    "lastRms=${vad.lastFrameEnergy}",
                    "lastVadState=true"
                )
            )
            sttErrorListener?.onSttError(error)
            return handleUtteranceReady()
        }
        return FrameResult.Continue
    }

    /**
     * Append frame samples to the accumulator.
     */
    private fun appendSamples(frame: FloatArray) {
        for (sample in frame) {
            speechAccumulator.add(sample)
        }
    }

    /**
     * Handle utterance ready: finalize PCM, return [FrameResult.UtteranceReady].
     */
    private fun handleUtteranceReady(): FrameResult {
        val pcm = finalizeUtterancePcm()
        SttLogger.pcm("utterance ready: size=${pcm.size}, durationMs=$durationMs")
        return FrameResult.UtteranceReady(pcm)
    }

    fun processFrame(frame: FloatArray): FrameResult = processChunk(frame, vad.isSpeech(frame))

    fun reset() {
        speechAccumulator.clear()
        speechActive = false
        silenceFrameCount = 0
        durationMs = 0
        preRollComplete = false
        preRollFrameCount = 0
    }

    /**
     * forceFinalize returns all buffered PCM, even if VAD never fired.
     * Returns null when no frames have ever been buffered.
     */
    fun forceFinalize(): FloatArray? {
        if (speechAccumulator.isEmpty()) return null
        return finalizeUtterancePcm()
    }

    /**
     * finaliseUtterance finalises the current utterance and returns the PCM buffer.
     * Called only from the deterministic Stop path, after stopRequested has been set.
     */
    fun finaliseUtterance(): FloatArray? {
        val pcm = forceFinalize()
        if (pcm != null) {
            SttLogger.pcm("final pcm size=${pcm.size}")
        }
        return pcm
    }

    /**
     * resetForNextUtterance clears all state for the next utterance cycle.
     * Delegates to [reset] then logs the stream reset.
     */
    fun resetForNextUtterance() {
        reset()
        SttLogger.pcm("accumulator reset")
    }

    internal fun currentDurationMs(): Int = durationMs

    private fun finalizeUtterancePcm(): FloatArray {
        val utterance = speechAccumulator.toFloatArray()
        val utterDurationMs = utterance.size * 1000 / sampleRate
        lastUtteranceDurationMs = utterDurationMs

        // ── Debug: PCM amplitude stats ───────────────────────────────────────────
        val maxAmp = utterance.maxOrNull() ?: 0f
        val minAmp = utterance.minOrNull() ?: 0f
        val avgAmp = if (utterance.isNotEmpty()) utterance.average() else 0.0
        SttLogger.pcm("PCM stats: max=$maxAmp min=$minAmp avg=$avgAmp")

        speechAccumulator.clear()
        speechActive = false
        silenceFrameCount = 0
        durationMs = 0
        preRollComplete = false
        preRollFrameCount = 0

        SttLogger.pcm("utterance finalized: ${utterance.size} samples, ${utterDurationMs}ms")
        return utterance
    }
}
