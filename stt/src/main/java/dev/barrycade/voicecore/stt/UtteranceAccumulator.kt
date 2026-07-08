package dev.barrycade.voicecore.stt

/**
 * UtteranceAccumulator transforms incoming FloatArray frames into complete utterance buffers.
 *
 * ## Architecture
 *
 * The accumulator follows a simple linear flow:
 *
 *   preRoll → (speech detection) → speech accumulation → silence accumulation → finalize
 *
 * ## Termination Rules
 *
 * ### Manual/manual mode (stopStrategy = "manual")
 * A. User presses STOP → finalizeUtterance() → normal transcription
 * B. Silence ≥ abnormalSilenceMs → abnormal termination → return reasonMessages.abnormalSilence
 * C. Duration ≥ maxDurationMs → abnormal termination → return reasonMessages.tooLong
 *
 * ### Manual/auto mode (stopStrategy = "autoSilence")
 * A. Silence ≥ autoSilenceMs → normalize → finalizeUtterance() → normal transcription
 * B. Duration ≥ maxDurationMs → abnormal termination → return reasonMessages.tooLong
 * C. No abnormal silence rule in this mode.
 *
 * ## Abnormal termination
 *
 * When maxDuration or abnormalSilence triggers, the accumulator:
 * 1. Discards accumulated PCM
 * 2. Signals [terminationReason] with the reason message
 * 3. Returns null from processChunk (no PCM delivered)
 *
 * The caller (SpeechToText/ProcessorController) must NOT call Whisper when
 * terminationReason is non-null.
 *
 * ## No hysteresis, no minimum speech duration, no timing guards
 *
 * SilenceFrameCount is the sole threshold. Speech → silence → threshold → finalize.
 * No minimum speech duration, no trailing silence padding, no stable-block alignment.
 *
 * Testing hook: internal [forceTimeout] flag causes immediate max-utterance finalization
 * the next time speech is detected.
 */
internal class UtteranceAccumulator(
    private val sampleRate: Int = 16000,
    private val preRollMs: Int = 100,
    private val vad: Vad = Vad(),
    internal var stopTrigger: StopTriggerStrategy? = null,
    // ── Mode-specific config blocks (resolve timing per-mode) ─────────────
    private val manualManualConfig: ManualManualConfig = ManualManualConfig(),
    private val manualAutoConfig: ManualAutoConfig = ManualAutoConfig(),
    private val reasonMessages: ReasonMessages = ReasonMessages()
) {
    constructor(
        config: RuntimeSttConfig,
        sampleRate: Int = 16000,
        vad: Vad = Vad(config),
        stopTrigger: StopTriggerStrategy? = null
    ) : this(
        sampleRate = sampleRate,
        preRollMs = config.preRollMs,
        vad = vad,
        stopTrigger = stopTrigger,
        manualManualConfig = config.manualManual,
        manualAutoConfig = config.manualAuto,
        reasonMessages = config.reasonMessages
    )

    companion object {
        /** Fixed pre-roll window before speech is accepted. */
        private const val PRE_ROLL_MS: Int = 100
    }

    /** Error listener forwarded from SpeechToText for structured error reporting. */
    internal var sttErrorListener: SttErrorListener? = null

    /** Testing hook: when true, simulates max-utterance timeout on first speech frame. */
    internal var forceTimeout: Boolean = false

    /**
     * Set to true when maxDurationMs is exceeded.
     * ProcessorController checks this after each processChunk() and stops the loop.
     */
    @Volatile
    internal var timeoutFired: Boolean = false

    /**
     * When non-null, the accumulator has terminated abnormally and the caller
     * must NOT call Whisper. Instead, the caller should return [terminationReason]
     * as the error/status message and discard any accumulated PCM.
     */
    @Volatile
    internal var terminationReason: String? = null

    /**
     * Set to true when auto-silence has fired (manual/auto mode) and the
     * accumulator has returned PCM. The caller must stop the processing loop
     * after dispatching the PCM to Whisper.
     */
    @Volatile
    internal var autoStopFired: Boolean = false

    /**
     * Callback invoked when a new utterance starts (PRE_ROLL → SPEECH transition).
     * Fires before any accumulation for the utterance begins.
     * SttProcessor uses this to reset per-utterance timing counters.
     */
    internal var onSpeechStart: (() -> Unit)? = null

    /**
     * Max utterance length is mode-specific:
     * - Manual/manual: uses [manualManualConfig.maxDurationMs]
     * - Manual/auto:   uses [manualAutoConfig.maxDurationMs]
     */
    private val effectiveMaxUtteranceLengthMs: Int by lazy {
        when (stopTrigger) {
            is ManualStopTrigger -> manualManualConfig.maxDurationMs
            is AutoSilenceStopTrigger -> manualAutoConfig.maxDurationMs
            else -> manualManualConfig.maxDurationMs
        }
    }

    /**
     * Compute the abnormal silence threshold in frames for the given frame duration.
     * Used for manual/manual mode: [manualManualConfig.abnormalSilenceMs] / [frameDurationMs].
     */
    private fun abnormalSilenceFramesFor(frameDurationMs: Int): Int =
        (manualManualConfig.abnormalSilenceMs / frameDurationMs).coerceAtLeast(1)

    /**
     * Compute the auto-silence threshold in frames for the given frame duration.
     * Used for manual/auto mode: [manualAutoConfig.autoSilenceMs] / [frameDurationMs].
     */
    private fun autoSilenceFramesFor(frameDurationMs: Int): Int =
        (manualAutoConfig.autoSilenceMs / frameDurationMs).coerceAtLeast(1)

    // Buffer that accumulates PCM for the current utterance.
    private val speechAccumulator = mutableListOf<Float>()
    private var speechActive = false

    /** Tracks whether pre-roll window has completed. */
    private var preRollComplete: Boolean = false

    /** Frames accumulated during pre-roll before speech is first detected. */
    private var preRollFrameCount: Int = 0

    private var silenceFrameCount = 0

    /**
     * Total accumulated duration since utterance start, including both speech and silence ms.
     * Incremented every frame — used for the max-duration termination check.
     * Duration starts counting when speech is first detected (speechActive = true).
     */
    private var durationMs = 0

    /** Captured utterance duration at last finalization (ms). 0 if no utterance has completed. */
    internal var lastUtteranceDurationMs: Int = 0
        private set

    fun processChunk(frame: FloatArray, isSpeechFrame: Boolean): FloatArray? {
        if (frame.isEmpty()) return null

        // If abnormal termination already occurred, discard all frames.
        if (terminationReason != null) return null

        val frameDurationMs = frame.size * 1000 / sampleRate

        // ── 1. Duration tracking — runs every frame ────────────────────
        durationMs += frameDurationMs

        // ── PCM non-zero verification ────────────────────────────────────
        val hasNonZero = frame.any { it != 0.0f }
        if (!hasNonZero) {
            SttLogger.pcm("[PCM] all-zero frame, size=${frame.size}")
        }

        // ── Speech detection logging ─────────────────────────────────────
        if (isSpeechFrame) {
            SttLogger.pcm("[VAD] speech frame, energy=${vad.lastFrameEnergy}")
        }

        if (!preRollComplete) {
            return handlePreRollFrame(frame, frameDurationMs)
        }

        appendSamples(frame)

        if (speechActive) {
            // ── 2. Silence tracking ──────────────────────────────────────
            if (isSpeechFrame) {
                silenceFrameCount = 0
                SttLogger.pcm("[SILENCE] speech-active reset")
            } else {
                silenceFrameCount++
                SttLogger.pcm("[SILENCE] silenceFrameCount=$silenceFrameCount, frameDurationMs=$frameDurationMs")
            }

            // ── 3. Termination checks (every frame, in order) ────────────
            // 3a. Manual/manual: abnormal silence check
            if (stopTrigger is ManualStopTrigger &&
                silenceFrameCount >= abnormalSilenceFramesFor(frameDurationMs)) {
                return handleAbnormalSilence()
            }

            // 3b. Manual/auto: auto-silence check
            if (stopTrigger is AutoSilenceStopTrigger &&
                silenceFrameCount >= autoSilenceFramesFor(frameDurationMs)) {
                return handleAutoSilenceFinalize()
            }

            // 3c. Max duration (both modes)
            if (durationMs >= effectiveMaxUtteranceLengthMs) {
                return handleMaxUtteranceTimeout()
            }

            return null
        }

        // ── Pre-speech path (no speech detected yet, pre-roll complete) ──
        if (isSpeechFrame) {
            // VAD detected speech — reset silence counter and start utterance.
            silenceFrameCount = 0
            return handleSpeechStart()
        }

        // ── Pre-speech speech detection fallback ─────────────────────────
        // When VAD does not fire speech frames, detect speech using frame
        // energy against a low threshold (0.001f, matching typical Whisper
        // energy levels). Also detect speech using a PCM non-zero heuristic
        // for debugging — if PCM has content but energy reads zero, speech
        // is happening.
        val frameEnergy = vad.lastFrameEnergy
        val energyThreshold = 0.001f
        val energyDetected = frameEnergy >= energyThreshold

        // Temporary force-speech heuristic: PCM has non-zero samples but
        // energy is zero — indicates the VAD energy field isn't populated.
        val frameHasNonZeroPCM = frame.any { it != 0.0f }
        val forceSpeech = (frameEnergy == 0.0f && frameHasNonZeroPCM)

        if (energyDetected) {
            SttLogger.pcm("[FALLBACK] speech: energy=$frameEnergy >= threshold=$energyThreshold")
            silenceFrameCount = 0
            return handleSpeechStart()
        }

        if (forceSpeech) {
            SttLogger.pcm("[FALLBACK] force speech: energy=$frameEnergy but PCM has non-zero content")
            silenceFrameCount = 0
            return handleSpeechStart()
        }

        SttLogger.pcm("[FALLBACK] silence: energy=$frameEnergy < threshold=$energyThreshold, hasNonZeroPCM=$frameHasNonZeroPCM")
        return null
    }

    /**
     * Process a frame during pre-roll. PCM is saved but speech detection
     * is delayed until pre-roll completes.
     */
    private fun handlePreRollFrame(frame: FloatArray, frameDurationMs: Int): FloatArray? {
        preRollFrameCount += 1
        appendSamples(frame)

        val preRollFrameTarget = (PRE_ROLL_MS / frameDurationMs).coerceAtLeast(1)
        if (preRollFrameCount >= preRollFrameTarget) {
            preRollComplete = true
            SttLogger.pcm("[PREROLL] preRollMs=$PRE_ROLL_MS complete")
        }
        return null
    }

    /**
     * Process the first speech frame after silence.
     * Starts a new utterance. May trigger forceTimeout testing hook.
     */
    private fun handleSpeechStart(): FloatArray? {
        speechActive = true
        silenceFrameCount = 0
        durationMs = 0
        SttLogger.pcm("[SPEECH] speechActive=true, durationMs=0")

        onSpeechStart?.invoke()

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
            timeoutFired = true
            return finalizeUtterance()
        }

        return null
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
     * Handle auto-silence finalization (manual/auto mode):
     * Returns the accumulated PCM for normal transcription.
     * Sets autoStopFired so the caller knows to stop the processing loop.
     */
    private fun handleAutoSilenceFinalize(): FloatArray? {
        SttLogger.pcm("[AUTOSILENCE] auto-silence threshold reached: threshold=${manualAutoConfig.autoSilenceMs}ms, durationMs=$durationMs")
        autoStopFired = true
        return finalizeUtterance()
    }

    /**
     * Handle max utterance timeout: discard PCM, set terminationReason.
     * Returns null so no PCM is delivered — Whisper must NOT be called.
     * This is NOT an error; it is a user-facing termination message.
     */
    private fun handleMaxUtteranceTimeout(): FloatArray? {
        SttLogger.pcm("max utterance exceeded: durationMs=$durationMs, limit=$effectiveMaxUtteranceLengthMs")

        speechAccumulator.clear()
        speechActive = false
        silenceFrameCount = 0
        durationMs = 0
        preRollComplete = false
        preRollFrameCount = 0

        terminationReason = reasonMessages.tooLong
        timeoutFired = true

        SttLogger.pcm("[TIMEOUT] abnormal termination: ${reasonMessages.tooLong}")
        return null
    }

    /**
     * Handle abnormal silence (manual/manual mode): discard PCM, set terminationReason.
     * Returns null so no PCM is delivered — Whisper must NOT be called.
     * This is NOT an error; it is a user-facing termination message.
     */
    private fun handleAbnormalSilence(): FloatArray? {
        SttLogger.pcm("[FINALISE] manual silence fallback: abnormalSilenceMs=${manualManualConfig.abnormalSilenceMs}")

        speechAccumulator.clear()
        speechActive = false
        silenceFrameCount = 0
        durationMs = 0
        preRollComplete = false
        preRollFrameCount = 0

        terminationReason = reasonMessages.abnormalSilence

        SttLogger.pcm("[ABNORMAL_SILENCE] abnormal termination: ${reasonMessages.abnormalSilence}")
        return null
    }

    fun processFrame(frame: FloatArray): FloatArray? = processChunk(frame, vad.isSpeech(frame))

    fun reset() {
        speechAccumulator.clear()
        speechActive = false
        silenceFrameCount = 0
        durationMs = 0
        preRollComplete = false
        preRollFrameCount = 0
        timeoutFired = false
        terminationReason = null
        autoStopFired = false
    }

    /**
     * forceFinalize returns all buffered PCM, even if VAD never fired.
     * Returns null when no frames have ever been buffered or when
     * terminationReason is set (abnormal termination — Whisper must NOT be called).
     */
    fun forceFinalize(): FloatArray? {
        if (terminationReason != null) return null
        if (speechAccumulator.isEmpty()) return null
        return finalizeUtterance()
    }

    /**
     * finaliseUtterance finalises the current utterance and returns the PCM buffer.
     * Called only from the deterministic Stop path, after stopRequested has been set.
     * Returns null if terminationReason is set (abnormal termination) or no PCM accumulated.
     */
    fun finaliseUtterance(): FloatArray? {
        if (terminationReason != null) {
            SttLogger.pcm("[FINALISE] abnormal termination active, returning null")
            return null
        }
        val pcm = forceFinalize()
        if (pcm != null) {
            SttLogger.pcm("[PCM] final pcm size=${pcm.size}")
        }
        return pcm
    }

    /**
     * resetForNextUtterance clears all state for the next utterance cycle.
     * Used in Streaming Mode after an utterance has been transcribed and dispatched.
     * Delegates to [reset] then logs the stream reset.
     */
    fun resetForNextUtterance() {
        reset()
        SttLogger.pcm("[STREAM] accumulator reset")
    }

    internal fun currentDurationMs(): Int = durationMs

    private fun finalizeUtterance(): FloatArray {
        val utterance = speechAccumulator.toFloatArray()
        val utterDurationMs = utterance.size * 1000 / sampleRate
        lastUtteranceDurationMs = utterDurationMs

        speechAccumulator.clear()
        speechActive = false
        silenceFrameCount = 0
        durationMs = 0
        preRollComplete = false
        preRollFrameCount = 0

        SttLogger.pcm("[FINALISE] utterance finalized: ${utterance.size} samples, ${utterDurationMs}ms")
        return utterance
    }
}
