package dev.barrycade.voicecore.stt

/**
 * Lifecycle strategy for the STT session: determines how recording
 * starts and stops.
 *
 * Two strategies are supported:
 * - [MANUAL_MANUAL]: start on explicit caller request, stop on explicit caller request.
 * - [MANUAL_AUTO]: start on explicit caller request, stop automatically
 *   when silence exceeds the configured auto-silence threshold.
 *
 * No other values are valid. Any unrecognised value must be rejected
 * by [SttRunConfigValidator].
 */
enum class SttLifeCycleStrategy {
    MANUAL_MANUAL,
    MANUAL_AUTO
}
