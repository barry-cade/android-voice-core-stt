package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for deterministic error code emissions and compile-time category mapping.
 *
 * All tests are PDP-aligned: linear arrange, act, assert.
 */
class SttErrorCodeTest {

    // ── Model load failure ──────────────────────────────────────────────

    @Test
    fun modelLoadFailure_emitsModelLoadFailed() {
        val error = SttError(
            code = SttErrorCode.MODEL_LOAD_FAILED,
            message = "Failed to load Whisper model: model file not found",
            details = listOf("modelPath=/data/models/ggml-tiny.en.bin")
        )

        assertEquals(SttErrorCode.MODEL_LOAD_FAILED, error.code)
        assertEquals(SttErrorCategory.WHISPER_ERROR, error.category)
        assertTrue(error.message.contains("Failed to load"))
    }

    @Test
    fun modelLoadFailure_containsModelPathInDetails() {
        val modelPath = "/data/models/ggml-tiny.en.bin"
        val error = SttError(
            code = SttErrorCode.MODEL_LOAD_FAILED,
            message = "Failed to load Whisper model: model file not found",
            details = listOf("modelPath=$modelPath")
        )

        assertTrue(error.details.any { it.contains(modelPath) })
    }

    // ── Inference failure ───────────────────────────────────────────────

    @Test
    fun inferenceFailure_emitsInferenceFailed() {
        val error = SttError(
            code = SttErrorCode.INFERENCE_FAILED,
            message = "Whisper inference failed: whisper_full returned -1",
            details = listOf("pcmSamples=16000")
        )

        assertEquals(SttErrorCode.INFERENCE_FAILED, error.code)
        assertEquals(SttErrorCategory.WHISPER_ERROR, error.category)
        assertTrue(error.message.contains("whisper_full"))
    }

    @Test
    fun inferenceFailure_containsPcmSampleCount() {
        val pcmSamples = 16000
        val error = SttError(
            code = SttErrorCode.INFERENCE_FAILED,
            message = "Whisper inference failed",
            details = listOf("pcmSamples=$pcmSamples")
        )

        assertTrue(error.details.any { it.contains(pcmSamples.toString()) })
    }

    // ── Capture failure ─────────────────────────────────────────────────

    @Test
    fun captureFailure_emitsCaptureFailed() {
        val error = SttError(
            code = SttErrorCode.CAPTURE_FAILED,
            message = "Audio capture failed to start: AudioRecord error",
            cause = IllegalStateException("AudioRecord not initialized")
        )

        assertEquals(SttErrorCode.CAPTURE_FAILED, error.code)
        assertEquals(SttErrorCategory.CAPTURE_ERROR, error.category)
        assertNotNull("capture failure must carry a cause", error.cause)
    }

    @Test
    fun captureFailure_containsExceptionDetail() {
        val exceptionMessage = "AudioRecord failed to initialize"
        val cause = IllegalStateException(exceptionMessage)
        val error = SttError(
            code = SttErrorCode.CAPTURE_FAILED,
            message = "Audio capture failed to start: $exceptionMessage",
            cause = cause,
            details = listOf("exception=IllegalStateException", "detail=$exceptionMessage")
        )

        assertEquals(cause, error.cause)
        assertTrue(error.details.any { it.contains(exceptionMessage) })
    }

    // ── VAD failure ─────────────────────────────────────────────────────

    @Test
    fun vadFailure_emitsVadFailed() {
        val error = SttError(
            code = SttErrorCode.VAD_FAILED,
            message = "VAD error: frame energy computation failed",
            details = listOf("lastRms=0.05", "lastVadState=false")
        )

        assertEquals(SttErrorCode.VAD_FAILED, error.code)
        assertEquals(SttErrorCategory.VAD_ERROR, error.category)
    }

    @Test
    fun vadFailure_containsRmsAndState() {
        val lastRms = "0.05"
        val lastVadState = "false"
        val error = SttError(
            code = SttErrorCode.VAD_FAILED,
            message = "VAD error",
            details = listOf("lastRms=$lastRms", "lastVadState=$lastVadState")
        )

        assertTrue(error.details.any { it.contains("lastRms=$lastRms") })
        assertTrue(error.details.any { it.contains("lastVadState=$lastVadState") })
    }

    // ── PIPELINE_ILLEGAL_STATE ──────────────────────────────────────────

    @Test
    fun illegalLifecycle_emitsPipelineIllegalState() {
        val error = SttError(
            code = SttErrorCode.PIPELINE_ILLEGAL_STATE,
            message = "Illegal lifecycle transition: READY -> FINALISING",
            details = listOf("from=READY", "to=FINALISING")
        )

        assertEquals(SttErrorCode.PIPELINE_ILLEGAL_STATE, error.code)
        assertEquals(SttErrorCategory.UNKNOWN, error.category)
    }

    @Test
    fun illegalLifecycle_containsFromAndToStates() {
        val fromState = "RECORDING"
        val toState = "READY"
        val error = SttError(
            code = SttErrorCode.PIPELINE_ILLEGAL_STATE,
            message = "Illegal lifecycle transition: $fromState -> $toState",
            details = listOf("from=$fromState", "to=$toState")
        )

        assertTrue(error.details.any { it.contains("from=$fromState") })
        assertTrue(error.details.any { it.contains("to=$toState") })
    }

    // ── INTERNAL_EXCEPTION ──────────────────────────────────────────────

    @Test
    fun internalException_emitsInternalException() {
        val cause = RuntimeException("Unexpected error in pipeline")
        val error = SttError(
            code = SttErrorCode.INTERNAL_EXCEPTION,
            message = "Unhandled error during start: ${cause.message}",
            cause = cause,
            details = listOf("exception=RuntimeException")
        )

        assertEquals(SttErrorCode.INTERNAL_EXCEPTION, error.code)
        assertEquals(SttErrorCategory.UNKNOWN, error.category)
        assertNotNull("internal exception must carry a cause", error.cause)
    }

    @Test
    fun internalException_containsExceptionType() {
        val error = SttError(
            code = SttErrorCode.INTERNAL_EXCEPTION,
            message = "Unexpected error",
            cause = NullPointerException("value was null"),
            details = listOf("exception=NullPointerException")
        )

        assertTrue(error.details.any { it.contains("NullPointerException") })
    }

    // ── CONFIG_PARSE_FAILED ───────────────────────────────────────────

    @Test
    fun configParseFailed_emitsConfigParseFailed() {
        val error = SttError(
            code = SttErrorCode.CONFIG_PARSE_FAILED,
            message = "Invalid JSON config: missing modelPath",
            details = listOf("configJson={\"invalid\": true}")
        )

        assertEquals(SttErrorCode.CONFIG_PARSE_FAILED, error.code)
        assertEquals(SttErrorCategory.CONFIG_ERROR, error.category)
        assertTrue(error.message.contains("Invalid JSON config"))
    }

    // ── INFERENCE_TIMEOUT ─────────────────────────────────────────────

    @Test
    fun inferenceTimeout_emitsInferenceTimeout() {
        val error = SttError(
            code = SttErrorCode.INFERENCE_TIMEOUT,
            message = "Whisper inference timed out after 30000ms",
            details = listOf("timeoutMs=30000")
        )

        assertEquals(SttErrorCode.INFERENCE_TIMEOUT, error.code)
        assertEquals(SttErrorCategory.TIMEOUT, error.category)
        assertTrue(error.message.contains("timed out"))
    }

    // ── Category mapping test ───────────────────────────────────────────

    @Test
    fun everyErrorCodeHasCorrectCategory() {
        val expected = mapOf(
            SttErrorCode.MODEL_LOAD_FAILED to SttErrorCategory.WHISPER_ERROR,
            SttErrorCode.INFERENCE_FAILED to SttErrorCategory.WHISPER_ERROR,
            SttErrorCode.CAPTURE_FAILED to SttErrorCategory.CAPTURE_ERROR,
            SttErrorCode.VAD_FAILED to SttErrorCategory.VAD_ERROR,
            SttErrorCode.CONFIG_PARSE_FAILED to SttErrorCategory.CONFIG_ERROR,
            SttErrorCode.INFERENCE_TIMEOUT to SttErrorCategory.TIMEOUT,
            SttErrorCode.PIPELINE_ILLEGAL_STATE to SttErrorCategory.UNKNOWN,
            SttErrorCode.INTERNAL_EXCEPTION to SttErrorCategory.UNKNOWN
        )
        for ((code, expectedCategory) in expected) {
            assertEquals(
                "$code must map to $expectedCategory",
                expectedCategory, code.category
            )
        }
    }

    // ── No legacy codes ─────────────────────────────────────────────────

    @Test
    fun enumContainsOnlyApprovedCodes() {
        val codes = SttErrorCode.entries.toSet()
        val allCodes = SttErrorCode.entries.toList()

        assertEquals("SttErrorCode must have exactly 8 values", 8, codes.size)
        assertTrue("must contain MODEL_LOAD_FAILED", codes.contains(SttErrorCode.MODEL_LOAD_FAILED))
        assertTrue("must contain INFERENCE_FAILED", codes.contains(SttErrorCode.INFERENCE_FAILED))
        assertTrue("must contain CAPTURE_FAILED", codes.contains(SttErrorCode.CAPTURE_FAILED))
        assertTrue("must contain VAD_FAILED", codes.contains(SttErrorCode.VAD_FAILED))
        assertTrue("must contain CONFIG_PARSE_FAILED", codes.contains(SttErrorCode.CONFIG_PARSE_FAILED))
        assertTrue("must contain INFERENCE_TIMEOUT", codes.contains(SttErrorCode.INFERENCE_TIMEOUT))
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
            val matchingCodes = SttErrorCode.entries.filter { it.name == legacyName }
            assertTrue("legacy code $legacyName must not exist in SttErrorCode", matchingCodes.isEmpty())
        }
    }

    // ── SttError construction validation ────────────────────────────────

    @Test
    fun sttErrorRequiresCodeAndMessage() {
        val error = SttError(
            code = SttErrorCode.INTERNAL_EXCEPTION,
            message = "test error"
        )

        assertNotNull("error must have a code", error.code)
        assertNotNull("error must have a message", error.message)
    }
}
