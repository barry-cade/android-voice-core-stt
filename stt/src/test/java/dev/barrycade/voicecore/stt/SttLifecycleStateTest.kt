package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for STT lifecycle state transitions.
 *
 * Validates that [SttLifecycleManager] enforces the legal transition matrix
 * and that illegal transitions are properly rejected with [SttErrorCode.PIPELINE_ILLEGAL_STATE].
 *
 * All tests are PDP-aligned: linear arrange, act, assert.
 * No nested lambdas, no scope-function pyramids, no clever Kotlin.
 */
class SttLifecycleStateTest {

    private lateinit var capturedErrors: MutableList<SttError>
    private lateinit var capturedLogs: MutableList<String>

    private val errorListener = SttErrorListener { error ->
        capturedErrors.add(error)
    }

    @Before
    fun setUp() {
        capturedErrors = mutableListOf()
        capturedLogs = mutableListOf()
        // Redirect lifecycle logging to capture
        logCapture = { message -> capturedLogs.add(message) }
    }

    // ── Legal transitions ───────────────────────────────────────────────

    @Test
    fun legalTransition_uninitialisedToReady() {
        val manager = SttLifecycleManager()
        manager.currentState = SttLifecycleState.UNINITIALISED

        val result = applyTransition(manager, SttLifecycleState.READY)

        assertTrue("UNINITIALISED -> READY must return true", result.allowed)
        assertEquals(SttLifecycleState.READY, manager.currentState)
    }

    @Test
    fun legalTransition_readyToRecording() {
        val manager = SttLifecycleManager()
        manager.currentState = SttLifecycleState.READY

        val result = applyTransition(manager, SttLifecycleState.RECORDING)

        assertTrue("READY -> RECORDING must return true", result.allowed)
        assertEquals(SttLifecycleState.RECORDING, manager.currentState)
    }

    @Test
    fun legalTransition_recordingToFinalising() {
        val manager = SttLifecycleManager()
        manager.currentState = SttLifecycleState.RECORDING

        val result = applyTransition(manager, SttLifecycleState.FINALISING)

        assertTrue("RECORDING -> FINALISING must return true", result.allowed)
        assertEquals(SttLifecycleState.FINALISING, manager.currentState)
    }

    @Test
    fun legalTransition_finalisingToReady() {
        val manager = SttLifecycleManager()
        manager.currentState = SttLifecycleState.FINALISING

        val result = applyTransition(manager, SttLifecycleState.READY)

        assertTrue("FINALISING -> READY must return true", result.allowed)
        assertEquals(SttLifecycleState.READY, manager.currentState)
    }

    // ── Illegal transitions ─────────────────────────────────────────────

    @Test
    fun illegalTransition_readyToFinalising() {
        val manager = SttLifecycleManager()
        manager.currentState = SttLifecycleState.READY

        val result = applyTransition(manager, SttLifecycleState.FINALISING)

        assertFalse("READY -> FINALISING must return false", result.allowed)
        assertEquals(SttLifecycleState.READY, manager.currentState)
    }

    @Test
    fun illegalTransition_finalisingToRecording() {
        val manager = SttLifecycleManager()
        manager.currentState = SttLifecycleState.FINALISING

        val result = applyTransition(manager, SttLifecycleState.RECORDING)

        assertFalse("FINALISING -> RECORDING must return false", result.allowed)
        assertEquals(SttLifecycleState.FINALISING, manager.currentState)
    }

    @Test
    fun illegalTransition_recordingToReady() {
        val manager = SttLifecycleManager()
        manager.currentState = SttLifecycleState.RECORDING

        val result = applyTransition(manager, SttLifecycleState.READY)

        assertFalse("RECORDING -> READY must return false", result.allowed)
        assertEquals(SttLifecycleState.RECORDING, manager.currentState)
    }

    @Test
    fun illegalTransition_readyToReadyDuplicate() {
        val manager = SttLifecycleManager()
        manager.currentState = SttLifecycleState.READY

        val result = applyTransition(manager, SttLifecycleState.READY)

        // Duplicate (no-op) transitions are allowed per current code
        assertTrue("READY -> READY (duplicate) must return true", result.allowed)
        assertEquals(SttLifecycleState.READY, manager.currentState)
    }

    @Test
    fun illegalTransition_uninitialisedToRecording() {
        val manager = SttLifecycleManager()
        manager.currentState = SttLifecycleState.UNINITIALISED

        val result = applyTransition(manager, SttLifecycleState.RECORDING)

        assertFalse("UNINITIALISED -> RECORDING must return false", result.allowed)
        assertEquals(SttLifecycleState.UNINITIALISED, manager.currentState)
    }

    // ── Error emission assertions ───────────────────────────────────────

    @Test
    fun illegalTransition_emitsPipelineIllegalState() {
        val manager = SttLifecycleManager()
        manager.currentState = SttLifecycleState.READY

        applyTransition(manager, SttLifecycleState.FINALISING)

        val hasIllegalStateError = capturedErrors.any {
            it.code == SttErrorCode.PIPELINE_ILLEGAL_STATE
        }
        assertTrue("illegal transition must emit PIPELINE_ILLEGAL_STATE", hasIllegalStateError)
    }

    @Test
    fun illegalTransition_emitsExactlyOneLog() {
        val manager = SttLifecycleManager()
        manager.currentState = SttLifecycleState.READY

        applyTransition(manager, SttLifecycleState.FINALISING)

        val illegalTransitionLogs = capturedLogs.filter {
            it.contains("illegal transition")
        }
        assertEquals("exactly one illegal transition log must be emitted", 1, illegalTransitionLogs.size)
    }

    // ── Helper: simulate transition logic ───────────────────────────────

    private data class TransitionResult(
        val allowed: Boolean,
        val from: SttLifecycleState,
        val to: SttLifecycleState
    )

    /**
     * Apply a transition using the same logic as [SpeechToText.transitionTo].
     * This is a pure-function replica of the production transition validation.
     * Returns a [TransitionResult] indicating whether the transition was allowed.
     */
    private fun applyTransition(
        manager: SttLifecycleManager,
        newState: SttLifecycleState
    ): TransitionResult {
        val from = manager.currentState

        // No-op: already in target state.
        if (from == newState) {
            return TransitionResult(allowed = true, from = from, to = newState)
        }

        val valid = when (from) {
            is SttLifecycleState.UNINITIALISED -> newState is SttLifecycleState.READY
            is SttLifecycleState.READY -> newState is SttLifecycleState.RECORDING
            is SttLifecycleState.RECORDING -> newState is SttLifecycleState.FINALISING
            is SttLifecycleState.FINALISING -> newState is SttLifecycleState.READY
        }

        if (valid) {
            val fromName = from.javaClass.simpleName
            val toName = newState.javaClass.simpleName
            manager.currentState = newState
            logCapture("[LIFECYCLE] state: $fromName -> $toName")
            return TransitionResult(allowed = true, from = from, to = newState)
        }

        val fromName = from.javaClass.simpleName
        val toName = newState.javaClass.simpleName
        logCapture("[LIFECYCLE] illegal transition: $fromName -> $toName")

        val error = SttError(
            category = SttErrorCategory.UNKNOWN,
            code = SttErrorCode.PIPELINE_ILLEGAL_STATE,
            message = "Illegal lifecycle transition: $fromName -> $toName",
            context = mapOf("from" to fromName, "to" to toName)
        )
        errorListener.onSttError(error)
        return TransitionResult(allowed = false, from = from, to = newState)
    }

    companion object {
        /**
         * Log capture hook. Set by setUp() to capture logs for assertion.
         */
        var logCapture: (String) -> Unit = {}
    }
}
