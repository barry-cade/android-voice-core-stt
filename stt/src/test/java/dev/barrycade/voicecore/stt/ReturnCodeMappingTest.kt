package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests verifying that the unified [SttReturnCode] set contains all expected codes.
 *
 * The legacy mapping layer ([ReturnCodeMapper]) has been removed along with
 * legacy codes (OK, NO_SPEECH, SILENCE_TIMEOUT, UTTERANCE_TOO_LONG, ERROR).
 * These tests verify the direct usage of SttReturnCode values.
 */
class ReturnCodeMappingTest {

    @Test
    fun success_isPresent() {
        assertEquals(SttReturnCode.SUCCESS, SttReturnCode.SUCCESS)
    }

    @Test
    fun configNotSet_isPresent() {
        assertEquals(SttReturnCode.CONFIG_NOT_SET, SttReturnCode.CONFIG_NOT_SET)
    }

    @Test
    fun invalidConfig_isPresent() {
        assertEquals(SttReturnCode.INVALID_CONFIG, SttReturnCode.INVALID_CONFIG)
    }
    @Test
    fun maxDurationReached_isPresent() {
        assertEquals(SttReturnCode.MAX_DURATION_REACHED, SttReturnCode.MAX_DURATION_REACHED)
    }
    @Test
    fun autoSilenceTriggered_isPresent() {
        assertEquals(SttReturnCode.AUTO_SILENCE_TRIGGERED, SttReturnCode.AUTO_SILENCE_TRIGGERED)
    }
    @Test
    fun abnormalSilence_isPresent() {
        assertEquals(SttReturnCode.ABNORMAL_SILENCE, SttReturnCode.ABNORMAL_SILENCE)
    }

    @Test
    fun engineError_isPresent() {
        assertEquals(SttReturnCode.ENGINE_ERROR, SttReturnCode.ENGINE_ERROR)
    }
}

