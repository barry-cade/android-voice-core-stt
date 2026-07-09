package dev.barrycade.voicecore.stt

/**
 * Deterministic mapping from legacy pipeline [SttReturnCode] values to the
 * new API codes used by [SessionResult] returned from [SpeechToText.startSession].
 *
 * This mapping is the single point of translation between the internal
 * pipeline's return codes and the external API's return codes.
 * It must not be replicated anywhere else in the new wrapper path.
 *
 * ## Mapping table
 *
 * | Legacy code       | New code              |
 * |-------------------|-----------------------|
 * | [SttReturnCode.OK] | [SttReturnCode.SUCCESS] |
 * | [SttReturnCode.SILENCE_TIMEOUT] | [SttReturnCode.ABNORMAL_SILENCE] |
 * | [SttReturnCode.UTTERANCE_TOO_LONG] | [SttReturnCode.MAX_DURATION_REACHED] |
 * | [SttReturnCode.ERROR] | [SttReturnCode.ENGINE_ERROR] |
 * | [SttReturnCode.NO_SPEECH] | [SttReturnCode.SUCCESS] (transcript is null) |
 *
 * Any unrecognised code is mapped to [SttReturnCode.ENGINE_ERROR] as a safe default.
 */
internal object ReturnCodeMapper {

    /**
     * Map a legacy pipeline [SttReturnCode] to the corresponding new API code.
     */
    fun map(legacyCode: SttReturnCode): SttReturnCode {
        return when (legacyCode) {
            SttReturnCode.OK -> SttReturnCode.SUCCESS
            SttReturnCode.NO_SPEECH -> SttReturnCode.SUCCESS
            SttReturnCode.SILENCE_TIMEOUT -> SttReturnCode.ABNORMAL_SILENCE
            SttReturnCode.UTTERANCE_TOO_LONG -> SttReturnCode.MAX_DURATION_REACHED
            SttReturnCode.ERROR -> SttReturnCode.ENGINE_ERROR
            // Pass through codes that are already in the new set.
            SttReturnCode.SUCCESS -> SttReturnCode.SUCCESS
            SttReturnCode.CONFIG_NOT_SET -> SttReturnCode.CONFIG_NOT_SET
            SttReturnCode.INVALID_CONFIG -> SttReturnCode.INVALID_CONFIG
            SttReturnCode.MAX_DURATION_REACHED -> SttReturnCode.MAX_DURATION_REACHED
            SttReturnCode.AUTO_SILENCE_TRIGGERED -> SttReturnCode.AUTO_SILENCE_TRIGGERED
            SttReturnCode.ABNORMAL_SILENCE -> SttReturnCode.ABNORMAL_SILENCE
            SttReturnCode.ENGINE_ERROR -> SttReturnCode.ENGINE_ERROR
        }
    }
}
