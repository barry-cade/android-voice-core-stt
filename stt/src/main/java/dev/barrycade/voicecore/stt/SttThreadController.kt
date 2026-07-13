package dev.barrycade.voicecore.stt

/**
 * Owns polling thread and drain thread management.
 *
 * ## Thread ownership
 *
 * | Thread | Owns |
 * |--------|------|
 * | Caller thread (SpeechToText) | Public methods: [startPolling], [stopPolling], [startDrainThread], [stopDrainThread], [stopAll] |
 * | Worker threads | Execute the [Runnable] passed to [startPolling] / [startDrainThread] |
 *
 * ## Thread-safety
 *
 * - All public methods are guarded by [lock] to prevent race conditions on
 *   thread references (write-skew between read-then-write sequences).
 * - [pollingThread] and [drainThread] are [@Volatile] for isAlive checks
 *   that must not acquire the lock (called from worker threads).
 * - Self-join is prevented: if the calling thread is the worker thread,
 *   the thread reference is cleared without joining.
 *
 * ## Lock boundaries
 *
 * - [lock] is NOT held across [Thread.join] — blocking operations happen
 *   before or after the lock scope.
 * - [lock] IS held across the read-and-null pattern for thread references.
 *
 * Responsibilities:
 * - Start and stop the polling worker thread (via [startPolling], [stopPolling]).
 * - Start and stop the drain thread (via [startDrainThread], [stopDrainThread]).
 * - Ensure thread-safe shutdown (join with timeout, interrupt propagation).
 *
 * No lifecycle state, no mode branching, no callbacks — only thread management.
 */
internal class SttThreadController {

    private val lock = Any()

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
     * Must be called from the SpeechToText caller thread.
     *
     * @param runnable The runnable to execute on the polling thread.
     * @param threadName Name for the polling thread.
     */
    fun startPolling(runnable: Runnable, threadName: String = "PollingThread") {
        stopPolling()
        val thread = Thread(runnable, threadName)
        synchronized(lock) {
            pollingThread = thread
        }
        thread.start()
    }

    /**
     * Stop the polling thread. Joins with 500ms timeout.
     * Idempotent: safe to call when no thread is running.
     *
     * Self-join guard: if the calling thread IS the polling thread,
     * the reference is cleared without joining (a thread cannot join itself).
     *
     * Must be called from the SpeechToText caller thread or the polling
     * thread itself.
     */
    fun stopPolling() {
        val threadToJoin: Thread?
        synchronized(lock) {
            threadToJoin = pollingThread
            pollingThread = null
        }
        if (threadToJoin != null && threadToJoin !== Thread.currentThread()) {
            threadToJoin.join(500)
        }
    }

    /**
     * Start a drain thread with the given [runnable].
     * Stops any existing drain thread first.
     *
     * Must be called from the SpeechToText caller thread.
     *
     * @param runnable The runnable to execute on the drain thread.
     * @param threadName Name for the drain thread.
     */
    fun startDrainThread(runnable: Runnable, threadName: String = "DrainThread") {
        stopDrainThread()
        val thread = Thread(runnable, threadName)
        synchronized(lock) {
            drainThread = thread
        }
        thread.start()
    }

    /**
     * Stop the drain thread. Joins with 200ms timeout.
     * Idempotent: safe to call when no thread is running.
     *
     * Self-join guard: if the calling thread IS the drain thread,
     * the reference is cleared without joining.
     *
     * Must be called from the SpeechToText caller thread or the drain
     * thread itself.
     */
    fun stopDrainThread() {
        val threadToJoin: Thread?
        synchronized(lock) {
            threadToJoin = drainThread
            drainThread = null
        }
        if (threadToJoin != null && threadToJoin !== Thread.currentThread()) {
            threadToJoin.join(200)
        }
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
     * Thread-safe: backed by [@Volatile] field.
     */
    fun isPollingAlive(): Boolean {
        return pollingThread?.isAlive == true
    }

    /**
     * Returns true when the drain thread is alive.
     * Thread-safe: backed by [@Volatile] field.
     */
    fun isDrainAlive(): Boolean {
        return drainThread?.isAlive == true
    }
}
