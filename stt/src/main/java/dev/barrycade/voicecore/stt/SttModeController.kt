package dev.barrycade.voicecore.stt

/**
 * Owns mode selection and controller instantiation.
 *
 * ## Thread ownership
 *
 * | Thread | Owns | Notes |
 * |--------|------|-------|
 * | SpeechToText caller thread | [selectController], [stopController], [startController], [clearControllers], [isManualMode] | Serialized via [SpeechToText.stateLock] |
 * | Worker threads (processor, minimal polling) | Read-only access via [selectedController], [isManualMode] | [@Volatile] on controller references and [manualMode] flag |
 *
 * All public methods are called from the [SpeechToText] caller thread
 * under [SpeechToText.stateLock]. Field reads from worker threads
 * (processor, minimal polling) are protected by [@Volatile] on the
 * mode-level flag and controller references.
 *
 * [selectController] is guarded by internal [lock] for the write path.
 * [clearControllers] similarly uses [lock].
 * Read paths ([selectedController], [isManualMode]) use [@Volatile]
 * fields and do not acquire [lock].
 *
 * No lifecycle, no threading, no callbacks, no processing components —
 * only mode branching and component construction.
 *
 * Processing components (VAD, accumulator, utterance handler) are owned
 * by [SttProcessingController] in Auto mode.
 */
internal class SttModeController {

    private val lock = Any()

    /** Mini controller for Manual mode. null when not in Manual mode. */
    @Volatile
    var minimalProcessorController: MinimalPollingController? = null
        private set

    /** Processor controller for Auto mode. null when not in Auto mode. */
    @Volatile
    var processorController: ProcessorController? = null
        private set

    /** True when Manual mode (ManualStart + ManualStop) is active. */
    @Volatile
    private var manualMode: Boolean = false

    /**
     * Returns true when Manual mode is active.
     */
    fun isManualMode(): Boolean = manualMode

    /**
     * Returns the currently active [PollingController], or null if not yet initialised.
     */
    fun selectedController(): PollingController? {
        return if (manualMode) minimalProcessorController else processorController
    }

    /**
     * Determine the mode from [config] and construct the appropriate controller.
     *
     * In Manual mode: constructs [MinimalPollingController] with optional [VadGate]
     * when [config.energyThreshold] is positive. The [VadGate] filters out silence
     * frames so only speech-level PCM enters the session buffer.
     *
     * In Auto mode: no controller is constructed here — [ProcessorController] is
     * owned by [SttProcessingController] and constructed separately.
     *
     * @param config The runtime config to use for component construction.
     * @param captureManager The session manager for PCM sourcing.
     * @param stopRequestedRef Supplier that returns true when stop has been requested.
     */
    fun selectController(
        config: RuntimeSttConfig,
        captureManager: SessionManager,
        stopRequestedRef: () -> Boolean
    ) {
        synchronized(lock) {
            manualMode = isManualModeByConfig(config)

            if (manualMode) {
                val vadGate = if (config.energyThreshold > 0f) {
                    VadGate(energyThreshold = config.energyThreshold)
                } else {
                    null
                }
                minimalProcessorController = MinimalPollingController(
                    audioSource = captureManager,
                    stopRequestedRef = stopRequestedRef,
                    vadGate = vadGate
                )
                SttLogger.lifecycle(
                    "SttModeController: Manual mode — MinimalPollingController constructed " +
                    "(vadGate=${vadGate != null}, energyThreshold=${config.energyThreshold})"
                )
            } else {
                SttLogger.lifecycle("SttModeController: Auto mode — processor owned by SttProcessingController")
            }
        }
    }

    /**
     * Stop the active controller.
     */
    fun stopController() {
        val mini = minimalProcessorController
        val proc = processorController
        mini?.stop()
        proc?.stop()
    }

    /**
     * Start the active controller.
     */
    fun startController() {
        selectedController()?.start()
    }

    /**
     * Clear all controller references for reset/destroy.
     */
    fun clearControllers() {
        synchronized(lock) {
            minimalProcessorController = null
            processorController = null
        }
    }

    private fun isManualModeByConfig(config: RuntimeSttConfig): Boolean {
        val startType = config.startStrategy::class.simpleName?.uppercase() ?: ""
        val stopType = config.stopStrategy::class.simpleName?.uppercase() ?: ""
        return startType == "MANUALSTART" && stopType == "MANUALSTOP"
    }
}

/**
 * Minimal polling controller for ManualStart + ManualStop mode.
 *
 * ## Thread ownership
 *
 * | Thread | Owns | Notes |
 * |--------|------|-------|
 * | Worker thread | Polling loop | Created in [start], joined in [stop] |
 * | Caller thread (SpeechToText) | [start], [stop] | Serialized via [SpeechToText.stateLock] |
 *
 * ## Self-join safety
 *
 * [stop] guards against self-join by checking `thread !== Thread.currentThread()`
 * before calling join(). If the caller IS the worker thread itself, the
 * reference is cleared without joining.
 *
 * Polls PCM frames from [AudioSource] in a loop, discarding them
 * from the AudioCapture queue to prevent unbounded growth.
 *
 * ## VAD gating (optional)
 *
 * When a [vadGate] is provided, the controller uses [AudioSource.pollFrameWithoutAppend]
 * to obtain raw frames, checks energy via [VadGate.isSpeech], and only calls
 * [AudioSource.appendFrameToSession] for frames above the energy threshold.
 * Silence frames are polled but discarded — they never enter the session buffer.
 *
 * When [vadGate] is null (default), the controller uses [AudioSource.pollFrame]
 * directly, preserving the original behaviour (all frames accumulated).
 *
 * VAD-related getters (vadActiveMs, vadConfidence, lastUtteranceDurationMs)
 * return null to indicate "not applicable" rather than "silence" or
 * "no speech". Callers should check [supportsVadMetrics] before using
 * VAD data.
 *
 * @param audioSource PCM frame source.
 * @param stopRequestedRef Supplier that returns true when stop has been requested.
 * @param vadGate Optional VAD gate for energy-based frame filtering. When non-null,
 *        only frames with speech-level RMS energy are accumulated. When null,
 *        all frames are accumulated (original behaviour).
 */
internal class MinimalPollingController(
    private val audioSource: AudioSource,
    private val stopRequestedRef: () -> Boolean,
    /**
     * Optional VAD gate for energy-based frame filtering.
     * Exposed as [vadGate] for the [SttCaptureController.finaliseAndStop] path —
     * the same gate is applied during the final drain to exclude silence frames
     * enqueued after the last poll.
     */
    val vadGate: VadGate? = null
) : PollingController {

    @Volatile
    private var isRunning: Boolean = false

    /** null — VAD is not used in Manual mode. */
    override val vadActiveMs: Long? = null

    /** null — accumulator is not used in Manual mode. */
    override val lastUtteranceDurationMs: Int? = null

    /** null — VAD is not used in Manual mode. */
    override val vadConfidence: Float? = null

    private val workerLock = Any()
    @Volatile
    private var workerThread: Thread? = null

    override fun supportsVadMetrics(): Boolean = false

    override fun start() {
        if (isRunning) return
        isRunning = true

        val useVadGate = vadGate != null

        val runnable = Runnable {
            while (isRunning) {
                if (stopRequestedRef()) {
                    try { Thread.sleep(10L) } catch (_: InterruptedException) { break }
                    continue
                }

                if (useVadGate) {
                    // VAD-gated path: poll without append, check energy, then conditionally append.
                    val rawFrame = audioSource.pollFrameWithoutAppend()
                    if (rawFrame == null) {
                        try { Thread.sleep(10L) } catch (_: InterruptedException) { break }
                        continue
                    }
                    if (vadGate!!.isSpeech(rawFrame)) {
                        audioSource.appendFrameToSession(rawFrame)
                    }
                } else {
                    // Original path: poll and append unconditionally.
                    val frame = audioSource.pollFrame()
                    if (frame == null) {
                        try { Thread.sleep(10L) } catch (_: InterruptedException) { break }
                    }
                }
            }
        }
        val thread = Thread(runnable, "MinimalPollingThread")
        synchronized(workerLock) {
            workerThread = thread
        }
        thread.start()
    }

    override fun stop() {
        if (!isRunning) return
        isRunning = false
        val threadToJoin: Thread?
        synchronized(workerLock) {
            threadToJoin = workerThread
            workerThread = null
        }
        if (threadToJoin != null && threadToJoin !== Thread.currentThread()) {
            threadToJoin.join(500)
        }
    }

    override fun resetVadActiveMs() {
        // No-op: VAD is not used in this controller.
    }

    override fun drainRemainingFrames(): FloatArray? = null
    override fun stopAndFinalize(): FloatArray? = null
    override val rmsSampler: RmsSampler
        get() = RmsSampler(16000, debugLogging = false) { _, _, _ -> }
}
