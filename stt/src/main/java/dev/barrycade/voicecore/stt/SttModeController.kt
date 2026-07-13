package dev.barrycade.voicecore.stt

/**
 * Owns mode selection and controller instantiation.
 *
 * Responsibilities:
 * - Determine whether the active config is Manual mode (ManualStart + ManualStop).
 * - Construct and store the appropriate [PollingController] based on mode.
 * - Provide access to the selected controller.
 *
 * No lifecycle, no threading, no callbacks, no processing components —
 * only mode branching and component construction.
 *
 * Processing components (VAD, accumulator, utterance handler) are owned
 * by [SttProcessingController] in Auto mode.
 */
internal class SttModeController {

    /** Mini controller for Manual mode. null when not in Manual mode. */
    var minimalProcessorController: MinimalPollingController? = null
        private set

    /** Processor controller for Auto mode. null when not in Auto mode. */
    var processorController: ProcessorController? = null
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
        manualMode = isManualModeByConfig(config)

        if (manualMode) {
            minimalProcessorController = MinimalPollingController(
                audioSource = captureManager,
                stopRequestedRef = stopRequestedRef
            )
            SttLogger.lifecycle("SttModeController: Manual mode — MinimalPollingController constructed")
        } else {
            SttLogger.lifecycle("SttModeController: Auto mode — processor owned by SttProcessingController")
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
     * Clear all controller references for reset/destroy.
     */
    fun clearControllers() {
        minimalProcessorController = null
        processorController = null
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
        val thread = workerThread
        if (thread != null && thread !== Thread.currentThread()) {
            thread.join(500)
        }
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
