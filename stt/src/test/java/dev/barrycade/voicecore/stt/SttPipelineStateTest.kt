package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for deterministic stage transition rules in [SttPipelineState].
 */
class SttPipelineStateTest {

    @Test
    fun legalTransition_idleToCapturing_allowed() {
        val state = SttPipelineState()

        val result = state.transitionTo(SttPipelineStage.CAPTURING, "test")

        assertTrue("IDLE -> CAPTURING should be legal", result)
        assertEquals(SttPipelineStage.CAPTURING, state.currentStage)
    }

    @Test
    fun legalTransition_capturingToFinalising_allowed() {
        val state = SttPipelineState()
        state.transitionTo(SttPipelineStage.CAPTURING, "setup")

        val result = state.transitionTo(SttPipelineStage.FINALISING, "test")

        assertTrue("CAPTURING -> FINALISING should be legal", result)
        assertEquals(SttPipelineStage.FINALISING, state.currentStage)
    }

    @Test
    fun legalTransition_finalisingToInferencing_allowed() {
        val state = SttPipelineState()
        state.transitionTo(SttPipelineStage.CAPTURING, "setup")
        state.transitionTo(SttPipelineStage.FINALISING, "setup")

        val result = state.transitionTo(SttPipelineStage.INFERENCING, "test")

        assertTrue("FINALISING -> INFERENCING should be legal", result)
        assertEquals(SttPipelineStage.INFERENCING, state.currentStage)
    }

    @Test
    fun legalTransition_inferencingToDispatching_allowed() {
        val state = SttPipelineState()
        state.transitionTo(SttPipelineStage.CAPTURING, "setup")
        state.transitionTo(SttPipelineStage.INFERENCING, "setup")

        val result = state.transitionTo(SttPipelineStage.DISPATCHING, "test")

        assertTrue("INFERENCING -> DISPATCHING should be legal", result)
        assertEquals(SttPipelineStage.DISPATCHING, state.currentStage)
    }

    @Test
    fun legalTransition_dispatchingToCapturing_allowed() {
        val state = SttPipelineState()
        state.transitionTo(SttPipelineStage.CAPTURING, "setup")
        state.transitionTo(SttPipelineStage.INFERENCING, "setup")
        state.transitionTo(SttPipelineStage.DISPATCHING, "setup")

        val result = state.transitionTo(SttPipelineStage.CAPTURING, "test")

        assertTrue("DISPATCHING -> CAPTURING should be legal", result)
        assertEquals(SttPipelineStage.CAPTURING, state.currentStage)
    }

    @Test
    fun illegalTransition_idleToInferencing_blocked() {
        val state = SttPipelineState()

        val result = state.transitionTo(SttPipelineStage.INFERENCING, "test")

        assertFalse("IDLE -> INFERENCING should be illegal", result)
        assertEquals(SttPipelineStage.IDLE, state.currentStage)
    }

    @Test
    fun illegalTransition_finalisingToDispatching_blocked() {
        val state = SttPipelineState()
        state.transitionTo(SttPipelineStage.CAPTURING, "setup")
        state.transitionTo(SttPipelineStage.FINALISING, "setup")

        val result = state.transitionTo(SttPipelineStage.DISPATCHING, "test")

        assertFalse("FINALISING -> DISPATCHING should be illegal", result)
        assertEquals(SttPipelineStage.FINALISING, state.currentStage)
    }

    @Test
    fun illegalTransition_dispatchingToFinalising_blocked() {
        val state = SttPipelineState()
        state.transitionTo(SttPipelineStage.CAPTURING, "setup")
        state.transitionTo(SttPipelineStage.INFERENCING, "setup")
        state.transitionTo(SttPipelineStage.DISPATCHING, "setup")

        val result = state.transitionTo(SttPipelineStage.FINALISING, "test")

        assertFalse("DISPATCHING -> FINALISING should be illegal", result)
        assertEquals(SttPipelineStage.DISPATCHING, state.currentStage)
    }

    @Test
    fun duplicateTransition_isNoopAllowed() {
        val state = SttPipelineState()
        state.transitionTo(SttPipelineStage.CAPTURING, "setup")

        val result = state.transitionTo(SttPipelineStage.CAPTURING, "test")

        assertTrue("duplicate transition should be allowed", result)
        assertEquals(SttPipelineStage.CAPTURING, state.currentStage)
    }
}
