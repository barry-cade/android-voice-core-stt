package dev.barrycade.voicecore.stt

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Owns inference submission and result/timing dispatch adaptation.
 *
 * ## Thread ownership
 *
 * | Thread | Owns | Notes |
 * |--------|------|-------|
 * | SpeechToText caller thread | [submit] call | Serialized via [SpeechToText.stateLock] |
 * | Whisper executor thread (T4) | [onResultCallback] invocation | Inside [ModelManager.submitInference] runnable |
 * | Caller's callback thread | [decideDispatch], [onPostDispatch], [onComplete] | Whichever thread runs the Whisper executor task |
 *
 * [submit] accepts lambda callbacks ([decideDispatch], [onPostDispatch], [onComplete])
 * that are invoked on the Whisper executor thread. These callbacks must NOT acquire
 * [SpeechToText.stateLock] if they are called from within a [submit] call that already
 * holds the lock — deadlock risk. See [SpeechToText.submitInferenceAndDispatch] for
 * the specific lock protocol.
 *
 * Responsibilities:
 * - Convert FloatArray PCM to ShortArray for model submission.
 * - Submit inference through [ModelManager].
 * - Build [SttTimingSnapshot] from timing inputs.
 * - Dispatch timing and result callbacks through [SttCallbackDispatcher].
 *
 * No lifecycle transitions, no session-epoch ownership, no mode branching.
 */
internal class SttInferenceController(
    private val modelManager: ModelManager,
    private val callbackDispatcher: SttCallbackDispatcher
) {

    internal data class InferenceRequest(
        val pcm: FloatArray,
        val code: SttReturnCode,
        val vadActiveMs: Long,
        val utteranceMs: Long,
        val captureMs: Long,
        val preRollMs: Long,
        val autoSilenceMs: Long,
        val pipelineStartMs: Long,
        val sessionEpochAtSubmission: Long
    )

    internal data class DispatchDecision(
        val shouldDispatch: Boolean,
        val dropReason: String? = null
    )

    fun submit(
        request: InferenceRequest,
        decideDispatch: () -> DispatchDecision,
        onPostDispatch: () -> Unit,
        onComplete: () -> Unit
    ): Boolean {
        val shortPcm = request.pcm.toShortArray()

        val onResultCallback: (Long, String) -> Unit = fun(inferenceStartMs: Long, text: String) {
            val decision = decideDispatch()
            if (!decision.shouldDispatch) {
                if (decision.dropReason != null) {
                    SttLogger.lifecycleW("inference result dropped: ${decision.dropReason}")
                }
                return
            }

            val now = System.currentTimeMillis()
            val whisperMs = now - inferenceStartMs
            val totalMs = now - request.pipelineStartMs

            val snapshot = SttTimingSnapshot(
                vadActiveMs = request.vadActiveMs,
                captureMs = request.captureMs,
                utteranceDurationMs = request.utteranceMs,
                silencePaddingMs = request.autoSilenceMs,
                preRollMs = request.preRollMs,
                inferenceMs = whisperMs,
                totalPipelineMs = totalMs
            )

            callbackDispatcher.dispatchTiming(request.captureMs, request.vadActiveMs, whisperMs, totalMs)
            callbackDispatcher.dispatchResult(text, request.code, snapshot)

            onPostDispatch()
        }

                return modelManager.submitInference(
            pcm = shortPcm,
            onResult = onResultCallback,
            onComplete = onComplete
        )
    }

    /**
     * Submit an inference request and block until the result is available.
     *
     * This is the synchronous version of [submit]. It creates a one-shot
     * result callback that captures the transcribed text, builds the JSON
     * result string, and releases a latch.
     *
     * The calling thread blocks until the inference completes or a timeout
     * expires. This method returns the JSON result string directly.
     *
     * ## When to use
     *
     * Use this method from [SpeechToText.transcribe] when the caller expects
     * a synchronous blocking return (the new public API).
     *
     * ## Thread safety
     *
     * - Call from [SpeechToText.stateLock] to serialise with session state.
     * - The latch is created BEFORE submission, so there is no race between
     *   the callback firing and the latch being available.
     * - The latch is NOT held across [stateLock].
     *
     * @param request The inference request with PCM and timing.
     * @param decideDispatch Decision function that determines whether the
     *        result should be dispatched (epoch check, stage transition).
     * @return JSON result string, or JSON silence/error string on failure.
     */
    fun submitAndAwait(
        request: InferenceRequest,
        decideDispatch: () -> DispatchDecision,
        onPostDispatch: () -> Unit,
        onComplete: () -> Unit,
        timeoutMs: Long = 30_000L
    ): String {
        val latch = CountDownLatch(1)
        val resultRef = java.util.concurrent.atomic.AtomicReference<String?>(null)

        val shortPcm = request.pcm.toShortArray()

        val onResultCallback: (Long, String) -> Unit = fun(inferenceStartMs: Long, text: String) {
            val decision = decideDispatch()
            if (!decision.shouldDispatch) {
                if (decision.dropReason != null) {
                    SttLogger.lifecycleW("inference result dropped: ${decision.dropReason}")
                }
                resultRef.set(buildSilenceJson())
                latch.countDown()
                return
            }

            val now = System.currentTimeMillis()
            val whisperMs = now - inferenceStartMs
            val totalMs = now - request.pipelineStartMs

            val snapshot = SttTimingSnapshot(
                vadActiveMs = request.vadActiveMs,
                captureMs = request.captureMs,
                utteranceDurationMs = request.utteranceMs,
                silencePaddingMs = request.autoSilenceMs,
                preRollMs = request.preRollMs,
                inferenceMs = whisperMs,
                totalPipelineMs = totalMs
            )

            // Build JSON directly instead of dispatching through callbackDispatcher.
            // The new public API returns JSON strings — no listener needed.
            val resultJson = SttJsonAdapter.buildResultJson(text, request.code, snapshot)
            resultRef.set(resultJson)

            onPostDispatch()
            latch.countDown()
        }

        val submitted = modelManager.submitInference(
            pcm = shortPcm,
            onResult = onResultCallback,
            onComplete = onComplete
        )

        if (!submitted) {
            return buildSilenceJson()
        }

        val released = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (!released) {
            return buildTimeoutJson()
        }

        return resultRef.get() ?: buildSilenceJson()
    }

    /**
     * Build a silence result JSON string.
     */
    private fun buildSilenceJson(): String {
        return """{"type":"result","text":"","code":"SILENCE"}"""
    }

    /**
     * Build a timeout result JSON string.
     */
    private fun buildTimeoutJson(): String {
        return """{"type":"result","text":"","code":"TIMEOUT"}"""
    }

    private fun FloatArray.toShortArray(): ShortArray {
        val shorts = ShortArray(size)
        for (i in indices) {
            shorts[i] = (kotlin.math.max(-1f, kotlin.math.min(1f, this[i])) * Short.MAX_VALUE)
                .toInt()
                .toShort()
        }
                        return shorts
    }
}

