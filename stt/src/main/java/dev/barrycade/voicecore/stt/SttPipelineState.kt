package dev.barrycade.voicecore.stt

/**
 * Deterministic stage-state holder for STT runtime pipeline transitions.
 *
 * Callers must serialize access externally.
 */
internal class SttPipelineState {

    private val lock = Any()

    private var stage: SttPipelineStage = SttPipelineStage.IDLE

    val currentStage: SttPipelineStage
        get() = synchronized(lock) {
            stage
        }

    fun transitionTo(newStage: SttPipelineStage, reason: String): Boolean {
        synchronized(lock) {
            val oldStage = stage
            if (oldStage == newStage) {
                return true
            }

            val valid = isAllowedTransition(oldStage, newStage)

            if (!valid) {
                SttLogger.lifecycleW(
                    "pipeline stage transition blocked: $oldStage -> $newStage (reason=$reason)"
                )
                return false
            }

            stage = newStage
            SttLogger.lifecycle("pipeline stage: $oldStage -> $newStage (reason=$reason)")
            return true
        }
    }

    private fun isAllowedTransition(from: SttPipelineStage, to: SttPipelineStage): Boolean {
        return when (from) {
            SttPipelineStage.IDLE -> to == SttPipelineStage.CAPTURING

            SttPipelineStage.CAPTURING -> to == SttPipelineStage.INFERENCING ||
                    to == SttPipelineStage.FINALISING ||
                    to == SttPipelineStage.IDLE

            SttPipelineStage.FINALISING -> to == SttPipelineStage.INFERENCING ||
                    to == SttPipelineStage.IDLE

            SttPipelineStage.INFERENCING -> to == SttPipelineStage.DISPATCHING ||
                    to == SttPipelineStage.CAPTURING ||
                    to == SttPipelineStage.IDLE

            SttPipelineStage.DISPATCHING -> to == SttPipelineStage.CAPTURING ||
                    to == SttPipelineStage.IDLE
        }
    }
}
