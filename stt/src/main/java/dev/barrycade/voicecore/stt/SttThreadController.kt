package dev.barrycade.voicecore.stt

/**
 * Owns polling thread and drain thread management.
 *
 * Responsibilities:
 * - Start and stop the polling worker thread (via [startPolling], [stopPolling]).
 * - Start and stop the drain thread (via [startDrainThread], [stopDrainThread]).
 * - Ensure thread-safe shutdown (join with timeout, interrupt propagation).
 *
 * No lifecycle state, no mode branching, no callbacks — only thread management.
 */
internal class SttThreadController {

    /** Worker thread for the polling loop. null when not running. */
    @Volatile
    private var pollingThread: Thread? = null

    /** Drain thread reference. null when not draining. */
    @Volatile
    private var drainThread: Thread? = null

    /**
     * Start a polling thread with the given [runnable].
     * Stops any existing polling thread first.
     *
     * @param runnable The runnable to execute on the polling thread.
     * @param threadName Name for the polling thread.
     */
    fun startPolling(runnable: Runnable, threadName: String = "PollingThread") {
        stopPolling()
        val thread = Thread(runnable, threadName)
        pollingThread = thread
        thread.start()
    }

    /**
     * Stop the polling thread. Joins with 500ms timeout.
     * Idempotent: safe to call when no thread is running.
     */
    fun stopPolling() {
        pollingThread?.join(500)
        pollingThread = null
    }

    /**
     * Start a drain thread with the given [runnable].
     * Stops any existing drain thread first.
     *
     * @param runnable The runnable to execute on the drain thread.
     * @param threadName Name for the drain thread.
     */
    fun startDrainThread(runnable: Runnable, threadName: String = "DrainThread") {
        stopDrainThread()
        val thread = Thread(runnable, threadName)
        drainThread = thread
        thread.start()
    }

    /**
     * Stop the drain thread. Joins with 200ms timeout.
     * Idempotent: safe to call when no thread is running.
     */
    fun stopDrainThread() {
        drainThread?.join(200)
        drainThread = null
    }

    /**
     * Stop all threads (both polling and drain).
     * Idempotent: safe to call multiple times.
     */
    fun stopAll() {
        stopPolling()
        stopDrainThread()
    }

    /**
     * Returns true when the polling thread is alive.
     */
    fun isPollingAlive(): Boolean {
        return pollingThread?.isAlive == true
    }

    /**
     * Returns true when the drain thread is alive.
     */
    fun isDrainAlive(): Boolean {
        return drainThread?.isAlive == true
    }
}
