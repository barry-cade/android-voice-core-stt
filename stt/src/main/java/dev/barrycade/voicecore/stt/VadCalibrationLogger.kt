package dev.barrycade.voicecore.stt

import android.util.Log

internal class VadCalibrationLogger {
    private val tag = "VadCalibration"

    fun logFrame(frame: FloatArray, isSpeech: Boolean, rms: Double, silenceFrameCount: Int) {
        Log.d(tag, "frame rms=$rms speech=$isSpeech silenceFrames=$silenceFrameCount samples=${frame.size}")
    }

    fun logUtteranceStart() {
        Log.d(tag, "utterance start")
    }

    fun logUtteranceFinalized(samples: Int, durationMs: Int) {
        Log.d(tag, "utterance finalized samples=$samples durationMs=$durationMs")
    }

    fun logSafetyCapTriggered() {
        Log.d(tag, "safety-cap finalization triggered")
    }

    fun logPadding(size: Int) {
        Log.d(tag, "padding applied size=$size")
    }
}
