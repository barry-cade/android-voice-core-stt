package dev.barrycade.voicecore.stt

import android.os.Process

/**
 * CaptureManager owns the microphone PCM queue.
 *
 * ## Thread ownership
 *
 * | Thread | Owns | Notes |
 * |--------|------|-------|
 * | AudioCaptureThread (T1) | [AudioRecord] reads, PCM frame enqueue | Produces into [AudioCapture.frameQueue] |
 * | DrainThread (T2) | Warm-up PCM buffering into session buffer | Stopped by [pollFrame] first call or [finalize]/[reset] |
 * | Processor thread (T3) | [pollFrame] polling and session buffering | Takes over from drain thread on first poll |
 * | SpeechToText caller thread | Lifecycle methods: [begin], [finalize], [reset], [shutdown], [restartCapture] | Serialized via [stateLock] |
 *
 * ## Lock boundaries
 *
 * - [stateLock] guards: [captureStarted], [draining], [sttActive], [drainThread],
 *   [currentDrainMode]. Short-duration; never held across blocking operations.
 * - [sessionBufferLock] guards: [sessionBuffer] (mutable list of accumulated PCM samples).
 *   Never held together with [stateLock] to avoid nested-lock risk.
 *
 * ## Self-join safety
 *
 * Drain thread joins in [finalize], [reset], [shutdown], and [pollFrame] all guard
 * against self-join via `thread != null` check (the join target is always a different
 * thread since the drain thread never calls these methods on CaptureManager).
 *
 * AudioCapture is created at construction but NOT started. Capture begins
 * lazily when [begin] is called. This ensures no PCM frames are buffered
 * before the caller explicitly starts a session.
 *
 * Pre-wired at construction: [AudioCapture] is created and ready.
 * When [begin] is called, AudioCapture starts and enqueues FloatArray frames
 * into a [ConcurrentLinkedQueue]. The capture thread runs independently of
 * STT engine state.
 *
 * ## Session model
 *
 * A session is delimited by [begin] and [finalize]:
 * - [begin]: starts draining the queue into an internal session buffer.
 *   The [DrainMode] parameter determines whether the queue is drained from
 *   head (existing frames included) or from the next frame onward.
 *   During warm-up, a lightweight drain thread copies frames. When the
 *   processor is active, [pollFrame] both buffers the frame AND returns it.
 * - [finalize]: concatenates all buffered frames + drains remaining queue
 *   into a single raw PCM FloatArray. No VAD, no accumulator.
 * - [reset]: clears the session buffer and queue for the next session.
 * - [shutdown]: stops the AudioCapture thread and releases AudioRecord.
 *
 * ## Threading
 *
 * - AudioCaptureThread (T1): reads AudioRecord → enqueues FloatArray frames.
 * - DrainThread (warm-up only): copies frames from queue into session buffer.
 * - Processor thread (active session): calls [pollFrame] which buffers + returns.
 *
 * ## Invariant compliance
 *
 * - Capture begins lazily in [begin] (not in the constructor).
 * - Queue accepts frames before STT readiness (invariant #2).
 * - Session buffer preserves all frames since [begin] (invariant #4).
 * - Capture stops only in [shutdown] (invariant #6).
 * - No dependency on STT engine state (invariant #7).
 *
 * @param sampleRate Audio sample rate in Hz (default 16000).
 * @param bufferSizeBytes Requested buffer size in bytes (default 32000).
 * @param bufferSizeSamples Size of the AudioRecord read buffer in samples.
 *        Controls read chunk size for AudioCapture. Must be >= 1024 and <= 16000.
 *        Default 4000 (0.25s at 16kHz). Passed through to AudioCapture.
 */
internal class CaptureManager(
    private val sampleRate: Int = 16000,
    private val bufferSizeBytes: Int = 32000,
    private val bufferSizeSamples: Int = 4000,
    private val sttErrorListener: SttErrorListener? = null,
    private val debugLoggingEnabled: Boolean = false
) : SessionManager {

    private val stateLock = Any()
    private val sessionBufferLock = Any()

    /** Underlying AudioCapture, created and started in the constructor. */
    private val audioCapture: AudioCapture = AudioCapture(
        sampleRate = sampleRate,
        requestedBufferSizeInBytes = bufferSizeBytes,
        bufferSizeSamples = bufferSizeSamples,
        debugLoggingEnabled = debugLoggingEnabled
    )

    /**
     * Session buffer: accumulates all FloatArray samples received since
        * [begin] was called. Access is serialised via [sessionBufferLock].
     */
    private val sessionBuffer = mutableListOf<Float>()

    /**
     * True while the drain thread should be copying frames.
     * Set to true in [begin], set to false when processor takes over
     * or when [finalize]/[reset] is called.
     */
    @Volatile
    private var draining: Boolean = false

    /** Lightweight thread that copies frames during warm-up. */
    private var drainThread: Thread? = null

    /**
     * True after AudioCapture has been started.
     * Capture is NOT started in the constructor. It starts lazily in [begin].
     */
    private var captureStarted: Boolean = false

    /**
     * True when STT processing is allowed to consume PCM frames.
     * Set to false in [beginPcmCapture] to ignore early PCM frames
     * that arrive before the STT pipeline is ready (model loading,
     * drain thread setup). Set to true in [beginSttProcessing] once
     * the drain thread or processor is active and frames should be
     * buffered into the session.
     *
     * This prevents duplicate utterances caused by PCM accumulating
     * during the gap between capture start and STT readiness.
     */
    @Volatile
    private var sttActive: Boolean = false

    /**
     * Stores the [DrainMode] from the most recent [begin] call.
     * Used by [beginSttProcessing] to dispatch to the correct
     * drain-thread strategy.
     */
    private var currentDrainMode: DrainMode = DrainMode.DRAIN_FROM_NEXT_FRAME

    init {
        SttLogger.pcm("CaptureManager initialised — capture NOT started")
    }

    // ── Session lifecycle ─────────────────────────────────────────────────

    /**
     * Begin a session: start PCM capture and begin accumulating frames.
     *
     * Delegates to [beginPcmCapture] and [beginSttProcessing] in sequence.
     * The [mode] parameter determines how the initial buffer is handled
     * during STT processing.
     *
     * Safe to call multiple times — subsequent calls reset the buffer.
     * Must be called from a single thread (caller serialises).
     */
    override fun begin(mode: DrainMode) {
        synchronized(stateLock) {
            currentDrainMode = mode
        }
        beginPcmCapture()
        beginSttProcessing()
    }

    /**
     * Start PCM capture synchronously. Capture must begin before any
     * frames can be buffered into the session.
     *
     * Clears the session buffer, sets the draining flag, and starts
     * AudioCapture synchronously. Capture begins immediately so frames
     * are available before the drain thread starts.
     */
    override fun beginPcmCapture(): Boolean {
        clearSessionBuffer()

        var shouldStartCapture = false
        synchronized(stateLock) {
            sttActive = false
            draining = true
            if (!captureStarted) {
                captureStarted = true
                shouldStartCapture = true
            }
        }

        if (!shouldStartCapture) return true

        // Start AudioCapture synchronously — capture begins immediately.
        SttLogger.pcm("beginPcmCapture() — starting AudioRecord synchronously (bufferSizeSamples=$bufferSizeSamples)")
        return try {
            audioCapture.start()
            SttLogger.pcm("beginPcmCapture() — AudioCapture started")
            true
        } catch (t: Throwable) {
            synchronized(stateLock) {
                captureStarted = false
            }
            sttErrorListener?.onSttError(SttError(
                code = SttErrorCode.CAPTURE_FAILED,
                message = "AudioCapture failed to start: ${t.message}",
                cause = t
            ))
            false
        }
    }

    /**
     * Start STT processing (drain thread / processor hand-off).
     *
     * Uses [currentDrainMode] to dispatch to the correct drain-thread strategy.
     */
    override fun beginSttProcessing() {
        val mode = synchronized(stateLock) {
            sttActive = true
            currentDrainMode
        }
        when (mode) {
            DrainMode.DRAIN_FROM_NEXT_FRAME -> startDrainThreadFromNextFrame()
            DrainMode.DRAIN_FROM_HEAD -> startDrainThreadFromHead()
        }
    }

    /**
     * Mark PCM capture as active for STT processing without starting
     * the drain thread or any STT processing components.
     *
     * Sets [sttActive] to true so that frames buffered via [pollFrame]
     * or [finalize] are accepted by the system.
     *
     * This is used in ManualStart + ManualStop mode where no drain thread
     * is started, but PCM frames must still be buffered for transcription
     * when stop is requested.
     */
    override fun activatePcmCapture() {
        synchronized(stateLock) {
            sttActive = true
        }
        SttLogger.pcm("activatePcmCapture() — PCM capture marked active, drain thread NOT started")
    }

    /**
     * Start drain thread from next frame onward.
     * Does NOT drain any frames already queued before [begin] was called.
     */
    private fun startDrainThreadFromNextFrame() {
        val drainRunnable = Runnable {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            while (draining) {
                // Only buffer frames when STT is active. Frames arriving
                // between beginPcmCapture() and beginSttProcessing() are
                // silently dropped to prevent duplicate utterance processing.
                if (!sttActive) {
                    try {
                        Thread.sleep(5)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    continue
                }
                val frame = audioCapture.frameQueue.poll()
                if (frame != null) {
                    appendFrameToSession(frame)
                } else {
                    try {
                        Thread.sleep(5)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
        val thread = Thread(drainRunnable, "CaptureManagerDrain")
        synchronized(stateLock) {
            drainThread = thread
        }
        thread.start()
        SttLogger.pcm("startDrainThreadFromNextFrame() — drain thread started")
    }

    /**
     * Start drain thread from head: drain any frames already queued before
     * [begin] was called, then continue draining new frames.
     */
    private fun startDrainThreadFromHead() {
        val drainRunnable = Runnable {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            // First, drain any frames already in the queue.
            // Only process frames if STT is active.
            while (sttActive) {
                val frame = audioCapture.frameQueue.poll()
                if (frame != null) {
                    appendFrameToSession(frame)
                } else {
                    break
                }
            }

            // Then continue draining new frames.
            while (draining) {
                if (!sttActive) {
                    try {
                        Thread.sleep(5)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    continue
                }
                val frame = audioCapture.frameQueue.poll()
                if (frame != null) {
                    appendFrameToSession(frame)
                } else {
                    try {
                        Thread.sleep(5)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
        val thread = Thread(drainRunnable, "CaptureManagerDrain")
        synchronized(stateLock) {
            drainThread = thread
        }
        thread.start()
        SttLogger.pcm("startDrainThreadFromHead() — drain thread started, existing queue drained")
    }

    /**
     * Finalize the session: stop buffering, stop AudioCapture, and return all
     * accumulated PCM.
     *
     * Stops the drain thread (if running), drains any remaining frames from
     * the AudioCapture queue into the session buffer, stops AudioCapture
     * (microphone off), and returns the raw concatenation as a single FloatArray.
     *
     * If a [vadGate] is provided, only frames with speech-level energy are
     * accumulated into the session buffer during the final drain. This is used
     * in ManualStart+ManualStop mode to exclude ambient silence frames that
     * were enqueued after the last poll but before [finalize] is called.
     *
     * After this call, [restartCapture] must be called before a new session
     * can be started.
     *
     * Returns an empty FloatArray if no frames were accumulated since [begin].
     * Idempotent: after the first call, subsequent calls return empty array.
     */
    override fun finalize(vadGate: VadGate?): FloatArray {
        val threadToJoin = synchronized(stateLock) {
            draining = false
            sttActive = false
            val thread = drainThread
            drainThread = null
            thread
        }

        threadToJoin?.let { thread ->
            try {
                thread.join(200)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        // Drain remaining frames from the queue into the buffer.
        // Apply VAD gate if provided (manual mode with gating enabled).
        while (true) {
            val frame = audioCapture.frameQueue.poll() ?: break
            if (vadGate == null || vadGate.isSpeech(frame)) {
                appendFrameToSession(frame)
            }
        }

        val result = snapshotAndClearSessionBuffer()

        // Stop AudioCapture — microphone off until next session.
        audioCapture.stop()
        synchronized(stateLock) {
            captureStarted = false
        }
        SttLogger.pcm("finalize() — returned ${result.size} raw PCM samples, AudioCapture stopped")
        return result
    }

    /**
     * Restart AudioCapture after a prior [finalize] stopped it.
     * utterance. After this call, [begin] starts a new session.
     *
     * Idempotent: safe to call multiple times; no-op if capture is already running.
     */
    override fun restartCapture(): Boolean {
        synchronized(stateLock) {
            if (captureStarted) {
                SttLogger.pcm("restartCapture() — already running, skipping")
                return true
            }
            captureStarted = true
        }

        return try {
            audioCapture.start()
            SttLogger.pcm("restartCapture() — AudioCapture restarted")
            true
        } catch (t: Throwable) {
            synchronized(stateLock) {
                captureStarted = false
            }
            sttErrorListener?.onSttError(SttError(
                code = SttErrorCode.CAPTURE_FAILED,
                message = "AudioCapture restart failed: ${t.message}",
                cause = t
            ))
            false
        }
    }

    /**
     * Poll the next frame from the queue, buffering it into the session.
     *
     * Called by the processor thread during active recording. Each frame is
     * appended to the session buffer AND returned for VAD processing.
     *
     * Stops the drain thread (if running) on first call — from this point on,
     * the processor thread handles both buffering and VAD.
     *
     * @return The next PCM frame, or null if the queue is empty.
     */
    override fun pollFrame(): FloatArray? {
        // On first call, stop the drain thread — processor takes over.
        val threadToJoin = synchronized(stateLock) {
            if (!draining) {
                null
            } else {
                draining = false
                val thread = drainThread
                drainThread = null
                thread
            }
        }

        threadToJoin?.let { thread ->
            try {
                thread.join(200)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            SttLogger.pcm("pollFrame() — drain thread stopped, processor taking over")
        }

        val frame = audioCapture.frameQueue.poll()
        if (frame != null) {
            appendFrameToSession(frame)
        }
        return frame
    }

    /**
     * Poll the next frame from the queue WITHOUT appending to the session buffer.
     *
     * Used by [MinimalPollingController] when VAD gating is active. The controller
     * calls this method to obtain the raw frame, checks energy via [VadGate],
     * and only calls [appendFrameToSession] if the frame contains speech.
     *
     * Does NOT stop the drain thread — the drain thread is only used in auto-mode
     * and [pollFrameWithoutAppend] is only called in manual mode where no drain
     * thread is running.
     *
     * @return The next PCM frame, or null if the queue is empty.
     */
    override fun pollFrameWithoutAppend(): FloatArray? {
        return audioCapture.frameQueue.poll()
    }

    /**
     * Append a pre-polled PCM frame to the session buffer.
     *
     * Used together with [pollFrameWithoutAppend] for VAD-gated accumulation.
     * Must be called after [pollFrameWithoutAppend] returns a non-null frame
     * that has passed VAD gating.
     *
     * Thread-safe: serialized via [sessionBufferLock].
     */
    override fun appendFrameToSession(frame: FloatArray) {
        synchronized(sessionBufferLock) {
            for (sample in frame) {
                sessionBuffer.add(sample)
            }
        }
    }

    /**
     * Stop the drain thread (if running) and clear session state.
     *
     * Does NOT stop AudioCapture — only clears the session buffer and
     * discards any pending queue frames. Capture continues running.
     */
    override fun reset() {
        val threadToJoin = synchronized(stateLock) {
            draining = false
            sttActive = false
            val thread = drainThread
            drainThread = null
            thread
        }

        threadToJoin?.let { thread ->
            try {
                thread.join(200)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        clearSessionBuffer()
        audioCapture.frameQueue.clear()
        SttLogger.pcm("reset() — session buffer and queue cleared, capture continues")
    }

    /**
     * Shut down the capture manager permanently.
     *
     * Stops the drain thread, clears session state, and stops AudioCapture.
     * After this call, the CaptureManager cannot be used for new sessions.
     * Called on permanent teardown.
     */
    override fun shutdown() {
        val threadToJoin = synchronized(stateLock) {
            draining = false
            sttActive = false
            val thread = drainThread
            drainThread = null
            thread
        }

        threadToJoin?.let { thread ->
            try {
                thread.join(200)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        clearSessionBuffer()
        audioCapture.stop()
        synchronized(stateLock) {
            captureStarted = false
        }
        SttLogger.pcm("shutdown() — AudioCapture stopped")
    }

    // ── AudioSource implementation ────────────────────────────────────────

    /**
     * Capture is already started in the constructor.
     * Returns true if capture was successfully initialised.
     */
    override fun startCapture(): Boolean {
        return synchronized(stateLock) {
            captureStarted
        }
    }

    /**
     * No-op: CaptureManager owns its lifecycle.
     * Call [shutdown] to stop capture permanently.
     */
    override fun stopCapture() {
        // No-op. Capture lifecycle is managed by CaptureManager.
    }

    /**
     * Clear all pending frames from the AudioCapture queue.
     * Does NOT clear the session buffer — use [reset] for that.
     */
    override fun clearQueue() {
        audioCapture.frameQueue.clear()
    }

    private fun clearSessionBuffer() {
        synchronized(sessionBufferLock) {
            sessionBuffer.clear()
        }
    }

    private fun snapshotAndClearSessionBuffer(): FloatArray {
        synchronized(sessionBufferLock) {
            val snapshot = sessionBuffer.toFloatArray()
            sessionBuffer.clear()
            return snapshot
        }
    }
}
