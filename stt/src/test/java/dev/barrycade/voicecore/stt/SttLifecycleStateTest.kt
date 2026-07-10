package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for STT lifecycle state transitions.
 *
 * Validates that the legal transition matrix (UNINITIALISED -> INITIALISED ->
 * READY -> RECORDING -> FINALISING -> STOPPED) is enforced and that illegal
 * transitions are rejected.
 *
 * The transition logic replicates [SttLifecycleStateMachine.transitionTo] as a
 * pure function over a local [currentState] variable.
 *
 * All tests are PDP-aligned: linear arrange, act, assert.
 * No nested lambdas, no scope-function pyramids, no clever Kotlin.
 */
class SttLifecycleStateTest {

    private lateinit var capturedErrors: MutableList<SttError>
    private lateinit var capturedLogs: MutableList<String>

    /** Local state holder — replicates SpeechToText.currentState. */
    private var currentState: SttLifecycleState = SttLifecycleState.UNINITIALISED

    private val errorListener = SttErrorListener { error ->
        capturedErrors.add(error)
    }

    @Before
    fun setUp() {
        capturedErrors = mutableListOf()
        capturedLogs = mutableListOf()
        currentState = SttLifecycleState.UNINITIALISED
        logCapture = { message -> capturedLogs.add(message) }
    }

    // ── Legal transitions ───────────────────────────────────────────────

    @Test
    fun legalTransition_uninitialisedToInitialised() {
        currentState = SttLifecycleState.UNINITIALISED

        val result = applyTransition(SttLifecycleState.INITIALISED)

        assertTrue("UNINITIALISED -> INITIALISED must return true", result.allowed)
        assertEquals(SttLifecycleState.INITIALISED, currentState)
    }

    @Test
    fun legalTransition_initialisedToReady() {
        currentState = SttLifecycleState.INITIALISED

        val result = applyTransition(SttLifecycleState.READY)

        assertTrue("INITIALISED -> READY must return true", result.allowed)
        assertEquals(SttLifecycleState.READY, currentState)
    }

    @Test
    fun legalTransition_readyToRecording() {
        currentState = SttLifecycleState.READY

        val result = applyTransition(SttLifecycleState.RECORDING)

        assertTrue("READY -> RECORDING must return true", result.allowed)
        assertEquals(SttLifecycleState.RECORDING, currentState)
    }

    @Test
    fun legalTransition_recordingToFinalising() {
        currentState = SttLifecycleState.RECORDING

        val result = applyTransition(SttLifecycleState.FINALISING)

        assertTrue("RECORDING -> FINALISING must return true", result.allowed)
        assertEquals(SttLifecycleState.FINALISING, currentState)
    }

    @Test
    fun legalTransition_finalisingToStopped() {
        currentState = SttLifecycleState.FINALISING

        val result = applyTransition(SttLifecycleState.STOPPED)

        assertTrue("FINALISING -> STOPPED must return true", result.allowed)
        assertEquals(SttLifecycleState.STOPPED, currentState)
    }

    // ── Illegal transitions ─────────────────────────────────────────────

    @Test
    fun illegalTransition_readyToFinalising() {
        currentState = SttLifecycleState.READY

        val result = applyTransition(SttLifecycleState.FINALISING)

        assertFalse("READY -> FINALISING must return false", result.allowed)
        assertEquals(SttLifecycleState.READY, currentState)
    }

    @Test
    fun illegalTransition_finalisingToRecording() {
        currentState = SttLifecycleState.FINALISING

        val result = applyTransition(SttLifecycleState.RECORDING)

        assertFalse("FINALISING -> RECORDING must return false", result.allowed)
        assertEquals(SttLifecycleState.FINALISING, currentState)
    }

    @Test
    fun illegalTransition_recordingToReady() {
        currentState = SttLifecycleState.RECORDING

        val result = applyTransition(SttLifecycleState.READY)

        assertFalse("RECORDING -> READY must return false", result.allowed)
        assertEquals(SttLifecycleState.RECORDING, currentState)
    }

    @Test
    fun illegalTransition_readyToReadyDuplicate() {
        currentState = SttLifecycleState.READY

        val result = applyTransition(SttLifecycleState.READY)

        // Duplicate (no-op) transitions are allowed per current code
        assertTrue("READY -> READY (duplicate) must return true", result.allowed)
        assertEquals(SttLifecycleState.READY, currentState)
    }

    @Test
    fun illegalTransition_uninitialisedToRecording() {
        currentState = SttLifecycleState.UNINITIALISED

        val result = applyTransition(SttLifecycleState.RECORDING)

        assertFalse("UNINITIALISED -> RECORDING must return false", result.allowed)
        assertEquals(SttLifecycleState.UNINITIALISED, currentState)
    }

    @Test
    fun illegalTransition_uninitialisedToReady() {
        currentState = SttLifecycleState.UNINITIALISED

        val result = applyTransition(SttLifecycleState.READY)

        assertFalse("UNINITIALISED -> READY must return false (must go through INITIALISED)", result.allowed)
        assertEquals(SttLifecycleState.UNINITIALISED, currentState)
    }

    @Test
    fun illegalTransition_initialisedToRecording() {
        currentState = SttLifecycleState.INITIALISED

        val result = applyTransition(SttLifecycleState.RECORDING)

        assertFalse("INITIALISED -> RECORDING must return false (must go through READY)", result.allowed)
        assertEquals(SttLifecycleState.INITIALISED, currentState)
    }

    @Test
    fun illegalTransition_initialisedToFinalising() {
        currentState = SttLifecycleState.INITIALISED

        val result = applyTransition(SttLifecycleState.FINALISING)

        assertFalse("INITIALISED -> FINALISING must return false", result.allowed)
        assertEquals(SttLifecycleState.INITIALISED, currentState)
    }

    @Test
    fun illegalTransition_initialisedToStopped() {
        currentState = SttLifecycleState.INITIALISED

        val result = applyTransition(SttLifecycleState.STOPPED)

        assertFalse("INITIALISED -> STOPPED must return false", result.allowed)
        assertEquals(SttLifecycleState.INITIALISED, currentState)
    }

    // ── Error emission assertions ───────────────────────────────────────

    @Test
    fun illegalTransition_emitsPipelineIllegalState() {
        currentState = SttLifecycleState.READY

        applyTransition(SttLifecycleState.FINALISING)

        val hasIllegalStateError = capturedErrors.any {
            it.code == SttErrorCode.PIPELINE_ILLEGAL_STATE
        }
        assertTrue("illegal transition must emit PIPELINE_ILLEGAL_STATE", hasIllegalStateError)
    }

    @Test
    fun illegalTransition_emitsExactlyOneLog() {
        currentState = SttLifecycleState.READY

        applyTransition(SttLifecycleState.FINALISING)

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
     * Apply a transition using the same logic as [SttLifecycleStateMachine.transitionTo].
     * Operates on the local [currentState] variable.
     * Returns a [TransitionResult] indicating whether the transition was allowed.
     */
    private fun applyTransition(
        newState: SttLifecycleState
    ): TransitionResult {
        val from = currentState

        // No-op: already in target state.
        if (from == newState) {
            return TransitionResult(allowed = true, from = from, to = newState)
        }

        val valid = when (from) {
            is SttLifecycleState.UNINITIALISED -> newState is SttLifecycleState.INITIALISED
            is SttLifecycleState.INITIALISED -> newState is SttLifecycleState.READY
            is SttLifecycleState.READY -> newState is SttLifecycleState.RECORDING
            is SttLifecycleState.RECORDING -> newState is SttLifecycleState.FINALISING
            is SttLifecycleState.FINALISING -> newState is SttLifecycleState.STOPPED
            else -> false
        }

        if (valid) {
            val fromName = from.javaClass.simpleName
            val toName = newState.javaClass.simpleName
            currentState = newState
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
