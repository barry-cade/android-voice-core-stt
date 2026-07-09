package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure unit tests for [ReturnCodeMapper].
 *
 * Verifies the deterministic mapping from legacy pipeline codes to new API codes.
 * No Android dependencies, no audio hardware.
 */
class ReturnCodeMappingTest {

    @Test
    fun ok_mapsToSuccess() {
        assertEquals(SttReturnCode.SUCCESS, ReturnCodeMapper.map(SttReturnCode.OK))
    }

    @Test
    fun noSpeech_mapsToSuccess() {
        assertEquals(SttReturnCode.SUCCESS, ReturnCodeMapper.map(SttReturnCode.NO_SPEECH))
    }

    @Test
    fun silenceTimeout_mapsToAbnormalSilence() {
        assertEquals(
            SttReturnCode.ABNORMAL_SILENCE,
            ReturnCodeMapper.map(SttReturnCode.SILENCE_TIMEOUT)
        )
    }

    @Test
    fun utteranceTooLong_mapsToMaxDurationReached() {
        assertEquals(
            SttReturnCode.MAX_DURATION_REACHED,
            ReturnCodeMapper.map(SttReturnCode.UTTERANCE_TOO_LONG)
        )
    }

    @Test
    fun error_mapsToEngineError() {
        assertEquals(
            SttReturnCode.ENGINE_ERROR,
            ReturnCodeMapper.map(SttReturnCode.ERROR)
        )
    }

    // ── Pass-through mappings ─────────────────────────────────────────────

    @Test
    fun success_passesThrough() {
        assertEquals(SttReturnCode.SUCCESS, ReturnCodeMapper.map(SttReturnCode.SUCCESS))
    }

    @Test
    fun configNotSet_passesThrough() {
        assertEquals(
            SttReturnCode.CONFIG_NOT_SET,
            ReturnCodeMapper.map(SttReturnCode.CONFIG_NOT_SET)
        )
    }

    @Test
    fun invalidConfig_passesThrough() {
        assertEquals(
            SttReturnCode.INVALID_CONFIG,
            ReturnCodeMapper.map(SttReturnCode.INVALID_CONFIG)
        )
    }

    @Test
    fun maxDurationReached_passesThrough() {
        assertEquals(
            SttReturnCode.MAX_DURATION_REACHED,
            ReturnCodeMapper.map(SttReturnCode.MAX_DURATION_REACHED)
        )
    }

    @Test
    fun autoSilenceTriggered_passesThrough() {
        assertEquals(
            SttReturnCode.AUTO_SILENCE_TRIGGERED,
            ReturnCodeMapper.map(SttReturnCode.AUTO_SILENCE_TRIGGERED)
        )
    }

    @Test
    fun abnormalSilence_passesThrough() {
        assertEquals(
            SttReturnCode.ABNORMAL_SILENCE,
            ReturnCodeMapper.map(SttReturnCode.ABNORMAL_SILENCE)
        )
    }

    @Test
    fun engineError_passesThrough() {
        assertEquals(
            SttReturnCode.ENGINE_ERROR,
            ReturnCodeMapper.map(SttReturnCode.ENGINE_ERROR)
        )
    }
}
