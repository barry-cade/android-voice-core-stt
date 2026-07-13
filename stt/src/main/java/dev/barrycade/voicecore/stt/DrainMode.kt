package dev.barrycade.voicecore.stt

/**
 * Drain mode for [CaptureManager.begin] — determines how the initial PCM
 * buffer is handled when a capture session starts.
 *
 * TWO values are supported:
 * - [DRAIN_FROM_NEXT_FRAME]: discard any PCM queued before [begin] was called.
 *   Capture starts fresh — no pre-begin audio leaks into the session buffer.
 * - [DRAIN_FROM_HEAD]: include all PCM queued since the last [reset] or
 *   session end. Useful for always-on strategies where pre-begin audio
 *   (e.g. VAD pre-roll) must be preserved.
 *
 * No other values are valid. Every [CaptureStrategy] MUST declare its
 * [DrainMode] explicitly.
 */
@Deprecated("Will be internalized in a future release. Use SttConfig instead.")
enum class DrainMode {
    DRAIN_FROM_NEXT_FRAME,
    DRAIN_FROM_HEAD
}
