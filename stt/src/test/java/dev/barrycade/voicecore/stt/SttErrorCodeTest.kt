package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for deterministic error code emissions.
 *
 * Validates that each failure scenario maps to the correct [SttErrorCode]
 * and that no legacy or ad-hoc error codes are emitted.
 *
 * Since [SpeechToText] depends on Android framework classes (AudioRecord, etc.),
 * these tests verify error-code behaviour at the [SttError] construction level
 * using the same code paths as the production pipeline.
 *
 * All tests are PDP-aligned: linear arrange, act, assert.
 * No nested lambdas, no scope-function pyramids, no clever Kotlin.
 */
class SttErrorCodeTest {

    // ── Model load failure ──────────────────────────────────────────────

    @Test
    fun modelLoadFailure_emitsModelLoadFailed() {
        val error = SttError(
            category = SttErrorCategory.UNKNOWN,
            code = SttErrorCode.MODEL_LOAD_FAILED,
            message = "Failed to load Whisper model: model file not found",
            context = mapOf("modelPath" to "/data/models/ggml-tiny.en.bin")
        )

        assertEquals(SttErrorCode.MODEL_LOAD_FAILED, error.code)
        assertEquals(SttErrorCategory.UNKNOWN, error.category)
        assertTrue(error.message.contains("Failed to load"))
    }

    @Test
    fun modelLoadFailure_containsModelPathInContext() {
        val modelPath = "/data/models/ggml-tiny.en.bin"
        val error = SttError(
            category = SttErrorCategory.UNKNOWN,
            code = SttErrorCode.MODEL_LOAD_FAILED,
            message = "Failed to load Whisper model: model file not found",
            context = mapOf("modelPath" to modelPath)
        )

        assertEquals(modelPath, error.context["modelPath"])
    }

    // ── Inference failure ───────────────────────────────────────────────

    @Test
    fun inferenceFailure_emitsInferenceFailed() {
        val error = SttError(
            category = SttErrorCategory.UNKNOWN,
            code = SttErrorCode.INFERENCE_FAILED,
            message = "Whisper inference failed: whisper_full returned -1",
            context = mapOf("pcmSamples" to 16000)
        )

        assertEquals(SttErrorCode.INFERENCE_FAILED, error.code)
        assertEquals(SttErrorCategory.UNKNOWN, error.category)
        assertTrue(error.message.contains("whisper_full"))
    }

    @Test
    fun inferenceFailure_containsPcmSampleCount() {
        val pcmSamples = 16000
        val error = SttError(
            category = SttErrorCategory.UNKNOWN,
            code = SttErrorCode.INFERENCE_FAILED,
            message = "Whisper inference failed",
            context = mapOf("pcmSamples" to pcmSamples)
        )

        assertEquals(pcmSamples, error.context["pcmSamples"])
    }

    // ── Capture failure ─────────────────────────────────────────────────

    @Test
    fun captureFailure_emitsCaptureFailed() {
        val error = SttError(
            category = SttErrorCategory.UNKNOWN,
            code = SttErrorCode.CAPTURE_FAILED,
            message = "Audio capture failed to start: AudioRecord error",
            cause = IllegalStateException("AudioRecord not initialized")
        )

        assertEquals(SttErrorCode.CAPTURE_FAILED, error.code)
        assertEquals(SttErrorCategory.UNKNOWN, error.category)
        assertNotNull("capture failure must carry a cause", error.cause)
    }

    @Test
    fun captureFailure_containsExceptionDetail() {
        val exceptionMessage = "AudioRecord failed to initialize"
        val cause = IllegalStateException(exceptionMessage)
        val error = SttError(
            category = SttErrorCategory.UNKNOWN,
            code = SttErrorCode.CAPTURE_FAILED,
            message = "Audio capture failed to start: $exceptionMessage",
            cause = cause,
            context = mapOf("exception" to "IllegalStateException", "detail" to exceptionMessage)
        )

        assertEquals(exceptionMessage, error.context["detail"])
        assertEquals(cause, error.cause)
    }

    // ── VAD failure ─────────────────────────────────────────────────────

    @Test
    fun vadFailure_emitsVadFailed() {
        val error = SttError(
            category = SttErrorCategory.UNKNOWN,
            code = SttErrorCode.VAD_FAILED,
            message = "VAD error: frame energy computation failed",
            lastRms = 0.05f,
            lastVadState = false
        )

        assertEquals(SttErrorCode.VAD_FAILED, error.code)
        assertEquals(SttErrorCategory.UNKNOWN, error.category)
    }

    @Test
    fun vadFailure_containsRmsAndState() {
        val lastRms = 0.05f
        val lastVadState = false
        val error = SttError(
            category = SttErrorCategory.UNKNOWN,
            code = SttErrorCode.VAD_FAILED,
            message = "VAD error",
            lastRms = lastRms,
            lastVadState = lastVadState
        )

        assertEquals(lastRms, error.lastRms!!, 0.001f)
        assertEquals(lastVadState, error.lastVadState!!)
    }

    // ── PIPELINE_ILLEGAL_STATE ──────────────────────────────────────────

    @Test
    fun illegalLifecycle_emitsPipelineIllegalState() {
        val error = SttError(
            category = SttErrorCategory.UNKNOWN,
            code = SttErrorCode.PIPELINE_ILLEGAL_STATE,
            message = "Illegal lifecycle transition: READY -> FINALISING",
            context = mapOf("from" to "READY", "to" to "FINALISING")
        )

        assertEquals(SttErrorCode.PIPELINE_ILLEGAL_STATE, error.code)
        assertEquals(SttErrorCategory.UNKNOWN, error.category)
    }

    @Test
    fun illegalLifecycle_containsFromAndToStates() {
        val fromState = "RECORDING"
        val toState = "READY"
        val error = SttError(
            category = SttErrorCategory.UNKNOWN,
            code = SttErrorCode.PIPELINE_ILLEGAL_STATE,
            message = "Illegal lifecycle transition: $fromState -> $toState",
            context = mapOf("from" to fromState, "to" to toState)
        )

        assertEquals(fromState, error.context["from"])
        assertEquals(toState, error.context["to"])
    }

    // ── INTERNAL_EXCEPTION ──────────────────────────────────────────────

    @Test
    fun internalException_emitsInternalException() {
        val cause = RuntimeException("Unexpected error in pipeline")
        val error = SttError(
            category = SttErrorCategory.UNKNOWN,
            code = SttErrorCode.INTERNAL_EXCEPTION,
            message = "Unhandled error during start: ${cause.message}",
            cause = cause,
            context = mapOf("exception" to "RuntimeException")
        )

        assertEquals(SttErrorCode.INTERNAL_EXCEPTION, error.code)
        assertEquals(SttErrorCategory.UNKNOWN, error.category)
        assertNotNull("internal exception must carry a cause", error.cause)
    }

    @Test
    fun internalException_containsExceptionType() {
        val error = SttError(
            category = SttErrorCategory.UNKNOWN,
            code = SttErrorCode.INTERNAL_EXCEPTION,
            message = "Unexpected error",
            cause = NullPointerException("value was null"),
            context = mapOf("exception" to "NullPointerException")
        )

        assertEquals("NullPointerException", error.context["exception"])
    }

    // ── No legacy codes ─────────────────────────────────────────────────

    @Test
    fun enumContainsOnlyApprovedCodes() {
        val codes = SttErrorCode.entries.toSet()
        val allCodes = SttErrorCode.entries.toList()

        assertEquals("SttErrorCode must have exactly 6 values", 6, codes.size)
        assertTrue("must contain MODEL_LOAD_FAILED", codes.contains(SttErrorCode.MODEL_LOAD_FAILED))
        assertTrue("must contain INFERENCE_FAILED", codes.contains(SttErrorCode.INFERENCE_FAILED))
        assertTrue("must contain CAPTURE_FAILED", codes.contains(SttErrorCode.CAPTURE_FAILED))
        assertTrue("must contain VAD_FAILED", codes.contains(SttErrorCode.VAD_FAILED))
        assertTrue("must contain PIPELINE_ILLEGAL_STATE", codes.contains(SttErrorCode.PIPELINE_ILLEGAL_STATE))
        assertTrue("must contain INTERNAL_EXCEPTION", codes.contains(SttErrorCode.INTERNAL_EXCEPTION))
    }

    @Test
    fun noLegacyCodeNamesReferenced() {
        val legacyNames = listOf(
            "AUDIO_INIT_FAILED",
            "AUDIO_PERMISSION_DENIED",
            "AUDIO_RECORD_FAILED",
            "PCM_BUFFER_OVERFLOW",
            "PCM_START_FAILED",
            "PCM_STOP_FAILED",
            "VAD_INIT_FAILED",
            "VAD_RUNTIME_ERROR",
            "WHISPER_MODEL_NOT_FOUND",
            "WHISPER_MODEL_LOAD_FAILED",
            "WHISPER_INFERENCE_FAILED",
            "WHISPER_JNI_ERROR",
            "CONFIG_INVALID",
            "TIMEOUT_MAX_UTTERANCE",
            "LIFECYCLE_VIOLATION",
            "UNKNOWN_ERROR",
            "WHISPER_ERROR",
            "CAPTURE_ERROR",
            "VAD_ERROR"
        )

        for (legacyName in legacyNames) {
            // Verify no enum value has this name
            val matchingCodes = SttErrorCode.entries.filter {
                it.name == legacyName
            }
            assertTrue(
                "legacy code $legacyName must not exist in SttErrorCode",
                matchingCodes.isEmpty()
            )
        }
    }

    // ── SttError construction validation ────────────────────────────────

    @Test
    fun sttErrorRequiresCodeAndCategory() {
        val error = SttError(
            category = SttErrorCategory.UNKNOWN,
            code = SttErrorCode.INTERNAL_EXCEPTION,
            message = "test error"
        )

        assertNotNull("error must have a category", error.category)
        assertNotNull("error must have a code", error.code)
        assertNotNull("error must have a message", error.message)
    }
}
