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
 * A. User presses STOP → finalizeUtterance() → FrameResult.NormalFinalize
 * B. Silence >= abnormalSilenceMs -> FrameResult.AbnormalTerminateWithPcm(SttReturnCode.SILENCE_TIMEOUT, pcm)
 * C. Duration >= maxDurationMs -> FrameResult.AbnormalTerminateWithPcm(SttReturnCode.UTTERANCE_TOO_LONG, pcm)
 *
 * ### Manual/auto mode (stopStrategy = "autoSilence")
 * A. Silence >= autoSilenceMs -> normalize -> FrameResult.AutoStop(pcm)
 * B. Duration >= maxDurationMs -> FrameResult.AbnormalTerminateWithPcm(SttReturnCode.UTTERANCE_TOO_LONG, pcm)
 * C. No abnormal silence rule in this mode.
 *
 * ## FrameResult
 *
 * Each call to [processChunk] returns a [FrameResult] sealed type that
 * encodes the next action for the caller (ProcessorController):
 *
 * - [FrameResult.Continue]: keep processing frames
 * - [FrameResult.NormalFinalize]: PCM ready for transcription (manual STOP)
 * - [FrameResult.AutoStop]: PCM ready, caller must stop the loop (auto-silence)
 * - [FrameResult.AbnormalTerminateWithPcm]: PCM preserved, caller must run inference
 * - [FrameResult.AbnormalTerminate]: PCM was empty or discarded, do NOT call Whisper
 *
 * ## No hysteresis, no minimum speech duration, no timing guards
 *
 * SilenceFrameCount is the sole threshold. Speech -> silence -> threshold -> finalize.
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
    // ── Mode-specific timing fields (flat, no wrapper types) ─────────────
    private val manualManualMaxDurationMs: Int = 30000,
    private val manualManualAbnormalSilenceMs: Int = 5000,
    private val manualAutoMaxDurationMs: Int = 30000,
    private val manualAutoAutoSilenceMs: Int = 1200,
    private val debugLoggingEnabled: Boolean = false
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
        manualManualMaxDurationMs = config.manualManualMaxDurationMs,
        manualManualAbnormalSilenceMs = config.manualManualAbnormalSilenceMs,
        manualAutoMaxDurationMs = config.manualAutoMaxDurationMs,
        manualAutoAutoSilenceMs = config.manualAutoAutoSilenceMs,
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

    /**
     * Max utterance length is mode-specific:
     * - Manual/manual: uses [manualManualMaxDurationMs]
     * - Manual/auto:   uses [manualAutoMaxDurationMs]
     */
    private val effectiveMaxUtteranceLengthMs: Int by lazy {
        when (stopTrigger) {
            is ManualStopTrigger -> manualManualMaxDurationMs
            is AutoSilenceStopTrigger -> manualAutoMaxDurationMs
            else -> manualManualMaxDurationMs
        }
    }

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
     * Incremented every frame -- used for the max-duration termination check.
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
            // ══════════════════════════════════════════════════════════════
            // CORRECT ORDER -- do not reorder
            // ══════════════════════════════════════════════════════════════
            //
            // 1. Update silence counter (reset BEFORE termination checks)
            // 2. Update duration
            // 3. Compute threshold frames once for this frame
            // 4. Run termination checks (abnormal silence, auto-silence, max duration)
            // 5. Continue
            // ══════════════════════════════════════════════════════════════

            // ── 1. Update silence counter ────────────────────────────────
            if (isSpeechFrame) {
                silenceFrameCount = 0
                SttLogger.pcm("[SILENCE] speech-active reset")
            } else {
                silenceFrameCount++
                SttLogger.pcm("[SILENCE] silenceFrameCount=$silenceFrameCount frameDurationMs=$frameDurationMs")
            }

            // ── 2. Update duration ───────────────────────────────────────
            durationMs += frameDurationMs

            // ── 3. Compute threshold frames once per frame ───────────────
            val abnormalSilenceFrames =
                manualManualAbnormalSilenceMs / frameDurationMs
            val autoSilenceFrames =
                manualAutoAutoSilenceMs / frameDurationMs

            // ── 4. Termination checks (correct order) ────────────────────
            // 4a. Manual/manual: abnormal silence
            if (stopTrigger is ManualStopTrigger &&
                silenceFrameCount >= abnormalSilenceFrames) {
                return handleAbnormalSilence()
            }

            // 4b. Manual/auto: auto-silence
            if (stopTrigger is AutoSilenceStopTrigger &&
                silenceFrameCount >= autoSilenceFrames) {
                return handleAutoSilenceFinalize()
            }

            // 4c. Max duration (both modes)
            if (durationMs >= effectiveMaxUtteranceLengthMs) {
                return handleMaxUtteranceTimeout()
            }

            // ── 5. Debug logging ─────────────────────────────────────────
            if (debugLoggingEnabled) {
                android.util.Log.i("STT",
                    "[DEBUG] speech=$isSpeechFrame silenceFrames=$silenceFrameCount " +
                    "durationMs=$durationMs frameDurationMs=$frameDurationMs")
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
            SttLogger.pcm("[FALLBACK] speech: energy=$frameEnergy >= threshold=$energyThreshold")
            silenceFrameCount = 0
            return handleSpeechStart()
        }

        if (forceSpeech) {
            SttLogger.pcm("[FALLBACK] force speech: energy=$frameEnergy but PCM has non-zero content")
            silenceFrameCount = 0
            return handleSpeechStart()
        }

        SttLogger.pcm("[FALLBACK] silence: energy=$frameEnergy < threshold=$energyThreshold hasNonZeroPCM=$frameHasNonZeroPCM")
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
            SttLogger.pcm("[PREROLL] preRollMs=$preRollMs complete")
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
            return handleMaxUtteranceTimeout()
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
     * Handle auto-silence finalization (manual/auto mode):
     * Returns [FrameResult.AutoStop] with accumulated PCM.
     */
    private fun handleAutoSilenceFinalize(): FrameResult {
        SttLogger.pcm("[AUTOSILENCE] auto-silence threshold reached: threshold=${manualAutoAutoSilenceMs}ms, durationMs=$durationMs")
        return FrameResult.AutoStop(finalizeUtterancePcm())
    }

    /**
     * Handle max utterance timeout: finalize PCM, return [FrameResult.AbnormalTerminateWithPcm].
     * PCM is preserved so inference can still produce a transcript for whatever
     * was captured before the timeout.
     */
    private fun handleMaxUtteranceTimeout(): FrameResult {
        SttLogger.pcm("max utterance exceeded: durationMs=$durationMs, limit=$effectiveMaxUtteranceLengthMs")

        val pcm = finalizeUtterancePcm()

        SttLogger.pcm("[TIMEOUT] abnormal termination with PCM: size=${pcm.size}, code=UTTERANCE_TOO_LONG")
        return FrameResult.AbnormalTerminateWithPcm(SttReturnCode.UTTERANCE_TOO_LONG, pcm)
    }

    /**
     * Handle abnormal silence (manual/manual mode): finalize PCM,
     * return [FrameResult.AbnormalTerminateWithPcm].
     * PCM is preserved so inference can still produce a transcript for
     * whatever was captured before silence timeout.
     */
    private fun handleAbnormalSilence(): FrameResult {
        SttLogger.pcm("[FINALISE] manual silence fallback: abnormalSilenceMs=${manualManualAbnormalSilenceMs}")

        val pcm = finalizeUtterancePcm()

        SttLogger.pcm("[ABNORMAL_SILENCE] abnormal termination with PCM: size=${pcm.size}, code=SILENCE_TIMEOUT")
        return FrameResult.AbnormalTerminateWithPcm(SttReturnCode.SILENCE_TIMEOUT, pcm)
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

    private fun finalizeUtterancePcm(): FloatArray {
        val utterance = speechAccumulator.toFloatArray()
        val utterDurationMs = utterance.size * 1000 / sampleRate
        lastUtteranceDurationMs = utterDurationMs

        // ── Debug: PCM amplitude stats ───────────────────────────────────────────
        val maxAmp = utterance.maxOrNull() ?: 0f
        val minAmp = utterance.minOrNull() ?: 0f
        val avgAmp = if (utterance.isNotEmpty()) utterance.average() else 0.0
        SttLogger.pcm("[DEBUG] PCM stats: max=$maxAmp min=$minAmp avg=$avgAmp")

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
