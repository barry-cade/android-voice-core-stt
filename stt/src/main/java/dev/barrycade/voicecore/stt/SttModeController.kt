package dev.barrycade.voicecore.stt

/**
 * Owns mode selection and controller instantiation.
 *
 * Responsibilities:
 * - Determine whether the active config is Manual mode (ManualStart + ManualStop).
 * - Construct and store the appropriate [PollingController] based on mode.
 * - Provide access to the selected controller and related components (VAD, accumulator).
 *
 * No lifecycle, no threading, no callbacks — only mode branching and component construction.
 */
internal class SttModeController {

    /** Mini controller for Manual mode. null when not in Manual mode. */
    var minimalProcessorController: MinimalPollingController? = null
        private set

    /** Processor controller for Auto mode. null when not in Auto mode. */
    var processorController: ProcessorController? = null
        private set

    /** Active VAD instance. null until initialised. Only used in Auto mode. */
    var activeVad: Vad? = null
        private set

    /** Utterance accumulator. null until initialised. Only used in Auto mode. */
    var utteranceAccumulator: UtteranceAccumulator? = null
        private set

    /** Utterance handler. null until initialised. Only used in Auto mode. */
    var utteranceHandler: UtteranceListener? = null
        private set

    /** True when Manual mode (ManualStart + ManualStop) is active. */
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
     * In Manual mode: constructs [MinimalPollingController] only (no VAD, no accumulator).
     * In Auto mode: constructs VAD, [UtteranceAccumulator], [UtteranceHandler], and [ProcessorController].
     *
     * @param config The runtime config to use for component construction.
     * @param captureManager The session manager for PCM sourcing.
     * @param stopRequestedRef Supplier that returns true when stop has been requested.
     * @param sttErrorListener Error listener to forward to accumulator.
     * @param forceTimeout When true, UtteranceAccumulator force-timeout is enabled.
     */
    fun selectController(
        config: RuntimeSttConfig,
        captureManager: SessionManager,
        stopRequestedRef: () -> Boolean,
        sttErrorListener: SttErrorListener?,
        forceTimeout: Boolean = false
    ) {
        manualMode = isManualModeByConfig(config)

        if (manualMode) {
            minimalProcessorController = MinimalPollingController(
                audioSource = captureManager,
                stopRequestedRef = stopRequestedRef
            )
            SttLogger.lifecycle("SttModeController: Manual mode — MinimalPollingController constructed")
        } else {
            val vad = Vad(config)
            vad.debugLogging = config.debugLoggingEnabled
            activeVad = vad

            val accumulator = UtteranceAccumulator(config)
            accumulator.sttErrorListener = sttErrorListener
            if (forceTimeout) {
                accumulator.forceTimeout = true
            }
            utteranceAccumulator = accumulator

            val handler = UtteranceHandlerImpl()
            utteranceHandler = handler

            accumulator.onSpeechStart = {
                processorController?.resetVadActiveMs()
            }

            val processor = ProcessorController(
                audioSource = captureManager,
                vad = vad,
                utteranceAccumulator = accumulator,
                listener = handler,
                sampleRate = 16000,
                debugLogging = config.debugLoggingEnabled,
                stopRequestedRef = stopRequestedRef
            )
            processorController = processor

            SttLogger.lifecycle("SttModeController: Auto mode — full scaffolding constructed")
        }
    }

    /**
     * Select drain mode based on the active run config.
     *
     * @param runConfig The active run config.
     * @return The [DrainMode] from the run config.
     */
    fun selectDrainMode(runConfig: SttRunConfig?): DrainMode {
        return runConfig?.drainMode ?: DrainMode.DRAIN_FROM_NEXT_FRAME
    }

    /**
     * Stop the active controller.
     */
    fun stopController() {
        minimalProcessorController?.stop()
        processorController?.stop()
    }

    /**
     * Start the active controller.
     */
    fun startController() {
        selectedController()?.start()
    }

    /**
     * Drain remaining frames from the active controller.
     * Only applicable for ProcessorController (Auto mode).
     *
     * @return Finalized PCM if available, null otherwise.
     */
    fun drainRemainingFrames(): FloatArray? {
        return processorController?.drainRemainingFrames()
    }

    /**
     * Stop and finalize the active controller.
     * Only applicable for ProcessorController (Auto mode).
     *
     * @return Finalized PCM if available, null otherwise.
     */
    fun stopAndFinalize(): FloatArray? {
        return processorController?.stopAndFinalize()
    }

    /**
     * Reset VAD active ms on the active processor controller (Auto mode).
     */
    fun resetVadActiveMs() {
        processorController?.resetVadActiveMs()
    }

    /**
     * Get vadActiveMs from the active controller.
     */
    fun vadActiveMs(): Long {
        return processorController?.vadActiveMs ?: 0L
    }

    /**
     * Get lastUtteranceDurationMs from the active controller.
     */
    fun lastUtteranceDurationMs(): Int {
        return processorController?.lastUtteranceDurationMs ?: 0
    }

    /**
     * Clear all controller references for reset/destroy.
     */
    fun clearControllers() {
        minimalProcessorController = null
        processorController = null
        activeVad = null
        utteranceAccumulator = null
        utteranceHandler = null
    }

    private fun isManualModeByConfig(config: RuntimeSttConfig): Boolean {
        val startType = config.startStrategy::class.simpleName?.uppercase() ?: ""
        val stopType = config.stopStrategy::class.simpleName?.uppercase() ?: ""
        return startType == "MANUALSTART" && stopType == "MANUALSTOP"
    }

    /**
     * Returns true when [runConfig] has ManualStart + ManualStop strategy types.
     * Used by SpeechToText to query mode before initStt has constructed controllers.
     */
    fun isManualMode(stoppedConfig: SttRunConfig?): Boolean {
        if (stoppedConfig == null) return false
        val startType = stoppedConfig.startStrategy.type.uppercase()
        val stopType = stoppedConfig.stopStrategy.type.uppercase()
        return startType == "MANUAL" && stopType == "MANUAL"
    }

    /**
     * Internal utterance handler implementation.
     * Delegates to a callback that SpeechToText wires up.
     */
    internal var onUtteranceReadyCallback: ((pcm: FloatArray, code: SttReturnCode) -> Unit)? = null

    private inner class UtteranceHandlerImpl : UtteranceListener {
        override fun onUtteranceReady(pcm: FloatArray, code: SttReturnCode) {
            onUtteranceReadyCallback?.invoke(pcm, code)
        }
    }
}

/**
 * Minimal polling controller for ManualStart + ManualStop mode.
 *
 * Polls PCM frames from [AudioSource] in a loop, discarding them
 * from the AudioCapture queue to prevent unbounded growth. No VAD,
 * no accumulator, no utterance lifecycle — frames are buffered into
 * the session by [CaptureManager.pollFrame] and returned via
 * [CaptureManager.finalize] when stop is requested.
 *
 * VAD-related getters (vadActiveMs, vadConfidence, lastUtteranceDurationMs)
 * return null to indicate "not applicable" rather than "silence" or
 * "no speech". Callers should check [supportsVadMetrics] before using
 * VAD data.
 */
internal class MinimalPollingController(
    private val audioSource: AudioSource,
    private val stopRequestedRef: () -> Boolean
) : PollingController {

    @Volatile
    private var isRunning: Boolean = false

    /** null — VAD is not used in Manual mode. */
    override val vadActiveMs: Long? = null

    /** null — accumulator is not used in Manual mode. */
    override val lastUtteranceDurationMs: Int? = null

    /** null — VAD is not used in Manual mode. */
    override val vadConfidence: Float? = null

    private var workerThread: Thread? = null

    override fun supportsVadMetrics(): Boolean = false

    override fun start() {
        if (isRunning) return
        isRunning = true

        val runnable = Runnable {
            while (isRunning) {
                if (stopRequestedRef()) {
                    try { Thread.sleep(10L) } catch (_: InterruptedException) { break }
                    continue
                }
                val frame = audioSource.pollFrame()
                if (frame == null) {
                    try { Thread.sleep(10L) } catch (_: InterruptedException) { break }
                }
            }
        }
        val thread = Thread(runnable, "MinimalPollingThread")
        workerThread = thread
        thread.start()
    }

    override fun stop() {
        if (!isRunning) return
        isRunning = false
        workerThread?.join(500)
        workerThread = null
    }

    override fun resetVadActiveMs() {
        // No-op: VAD is not used in this controller.
    }

    override fun drainRemainingFrames(): FloatArray? = null
    override fun stopAndFinalize(): FloatArray? = null
    override val rmsSampler: RmsSampler
        get() = RmsSampler(16000, debugLogging = false) { _, _, _ -> }
}
