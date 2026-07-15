package dev.barrycade.voicecore.stt

/**
 * ReturnCodeMapper has been removed.
 *
 * The `SttReturnCode` enum previously contained legacy pipeline codes (OK, NO_SPEECH,
 * SILENCE_TIMEOUT, UTTERANCE_TOO_LONG, ERROR) alongside new API codes (SUCCESS, etc.),
 * requiring a mapping layer between them. The legacy codes have been removed from
 * `SttReturnCode`, eliminating the need for this mapper.
 *
 * All session outcomes now use the unified set of return codes directly.
 * See [SttReturnCode] for the complete list.
 *
 * @deprecated This type is retained only to avoid breaking compilation in any
 *             remaining references. It will be removed in a future cleanup pass.
 */
@Deprecated("Legacy return code mapping removed. Use SttReturnCode directly.")
internal object ReturnCodeMapper {
    @Deprecated("Legacy mapping removed. Use SttReturnCode directly.")
    fun map(legacyCode: SttReturnCode): SttReturnCode {
        // Legacy codes no longer exist — just pass through.
        return legacyCode
    }
}

