package dev.barrycade.voicecore.stt

internal interface UtteranceListener {
    fun onUtteranceReady(pcm: FloatArray, code: SttReturnCode)
}

