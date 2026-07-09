package dev.barrycade.voicecore.stt

/**
 * Internal migration status constants.
 *
 * Reflects the current stage of the API migration from legacy [SttConfig] to new [SttRunConfig].
 * These flags are used internally for tracking and will guide future removal decisions.
 *
 * No behavioural branching depends on these values.
 */
internal object MigrationStatus {
    const val newApiAvailable = true
    const val legacyApiDeprecated = true
    const val legacyRemovalPlanned = true
}
