package dev.barrycade.voicecore.stt

/**
 * Deterministic stage-state holder for STT runtime pipeline transitions.
 *
 * Callers must serialize access externally.
 */
internal class SttPipelineState {

    private var stage: SttPipelineStage = SttPipelineStage.IDLE

    val currentStage: SttPipelineStage
        get() = stage

    fun transitionTo(newStage: SttPipelineStage, reason: String): Boolean {
        val oldStage = stage
        if (oldStage == newStage) {
            return true
        }

        val valid = when (oldStage) {
            SttPipelineStage.IDLE -> newStage == SttPipelineStage.CAPTURING

            SttPipelineStage.CAPTURING -> newStage == SttPipelineStage.INFERENCING ||
                    newStage == SttPipelineStage.FINALISING ||
                    newStage == SttPipelineStage.IDLE

            SttPipelineStage.FINALISING -> newStage == SttPipelineStage.INFERENCING ||
                    newStage == SttPipelineStage.IDLE

            SttPipelineStage.INFERENCING -> newStage == SttPipelineStage.DISPATCHING ||
                    newStage == SttPipelineStage.CAPTURING ||
                    newStage == SttPipelineStage.IDLE

            SttPipelineStage.DISPATCHING -> newStage == SttPipelineStage.CAPTURING ||
                    newStage == SttPipelineStage.IDLE
        }

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
