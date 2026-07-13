package dev.barrycade.voicecore.stt

/**
 * Owns inference submission and result/timing dispatch adaptation.
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

        val onResultCallback: (String) -> Unit = fun(text: String) {
            val decision = decideDispatch()
            if (!decision.shouldDispatch) {
                if (decision.dropReason != null) {
                    SttLogger.lifecycleW("inference result dropped: ${decision.dropReason}")
                }
                return
            }

            val whisperMs = System.currentTimeMillis() - request.pipelineStartMs
            val totalMs = System.currentTimeMillis() - request.pipelineStartMs

            val snapshot = SttTimingSnapshot(
                vadActiveMs = request.vadActiveMs,
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
