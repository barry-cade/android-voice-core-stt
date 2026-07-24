package dev.barrycade.voicecore

import android.Manifest
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import dev.barrycade.voicecore.stt.SpeechToText
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Manages the Whisper STT UI and lifecycle.
 */
class WhisperPanel(private val activity: MainActivity) {

    private val btnStart: Button = activity.findViewById(R.id.btnStart)
    private val btnStop: Button = activity.findViewById(R.id.btnStop)
    private val btnClear: Button = activity.findViewById(R.id.btnClear)
    private val radioGroupStrategy: RadioGroup = activity.findViewById(R.id.radioGroupStrategy)
    private val txtOutput: TextView = activity.findViewById(R.id.txtOutput)
    private val txtDiagnostics: TextView = activity.findViewById(R.id.txtDiagnostics)
    private val txtConfigDisplay: TextView = activity.findViewById(R.id.txtConfigDisplay)
    private val txtErrorBanner: TextView = activity.findViewById(R.id.txtErrorBanner)

    private val stt: SpeechToText = SpeechToText()

    private var selectedStopType: String = "MANUAL"
    private var activeStopType: String = "MANUAL"
    private var configDefaultsMessage: String? = null
    private var blankAudioCount: Int = 0
    private val blankAudioThreshold: Int = 3

    var isRecording: Boolean = false
        private set

    private var isWhisperReady: Boolean = false

    companion object {
        private const val BLANK_AUDIO_MARKER = "[BLANK_AUDIO]"
        private const val CONFIG_MANUAL_MANUAL = "stt_config_manual_manual.json"
        private const val CONFIG_MANUAL_AUTO = "stt_config_manual_auto.json"

        fun configAssetForStopType(stopType: String): String {
            return when (stopType) {
                "AUTO_SILENCE" -> CONFIG_MANUAL_AUTO
                else -> CONFIG_MANUAL_MANUAL
            }
        }
    }

    init {
        stt.setOnMessageListener(createMessageListener())

        radioGroupStrategy.setOnCheckedChangeListener { _, checkedId ->
            val newStopType: String = when (checkedId) {
                R.id.radioManualAuto -> "AUTO_SILENCE"
                else -> "MANUAL"
            }
            selectedStopType = newStopType
            displayConfigForStopType(newStopType)
            if (!isRecording) {
                val configJson = buildConfigJsonForStopType(newStopType)
                stt.configure(configJson)
            }
        }

        btnStart.setOnClickListener {
            AppLogger.log(AppLogCode.START_BUTTON_PRESSED)
            if (activity.hasRecordAudioPermission()) startRecording()
            else activity.requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        btnStop.setOnClickListener {
            AppLogger.log(AppLogCode.STOP_BUTTON_PRESSED)
            stopRecording()
        }

        btnClear.setOnClickListener {
            txtOutput.text = ""
            updateUi()
        }

        displayConfigForStopType("MANUAL")
        updateUi()
    }

    fun preloadModelAsync() {
        val modelFile = File(activity.filesDir, "model.bin")
        if (!modelFile.exists()) {
            activity.runOnUiThread { txtOutput.text = "Copying speech model… (one-time setup)" }
        }

        Thread({
            try {
                if (!modelFile.exists()) {
                    modelFile.parentFile?.mkdirs()
                    activity.assets.open("models/ggml-tiny.en.bin").use { input ->
                        FileOutputStream(modelFile).use { output -> input.copyTo(output) }
                    }
                }

                val configJson = buildConfigJsonForStopType(selectedStopType)
                val result = stt.loadModelOnly(configJson)
                activity.runOnUiThread {
                    if (result.contains("\"type\":\"error\"")) {
                        val message = JSONObject(result).optString("message", "Unknown error")
                        AppLogger.log(AppLogCode.PRELOAD_FAILED, "STT error: $message")
                        txtOutput.text = "Model preload error: $message"
                        isWhisperReady = false
                    } else {
                        txtOutput.text = "Model loaded. Tap Start to record."
                        isWhisperReady = true
                    }
                    updateUi()
                }
            } catch (t: Throwable) {
                AppLogger.log(AppLogCode.PRELOAD_FAILED, t.message ?: "Unknown error")
                activity.runOnUiThread {
                    txtOutput.text = "Model preload failed: ${t.message}"
                }
            }
        }, "ModelPreloadThread").start()
    }

    private fun displayConfigForStopType(stopType: String) {
        activeStopType = stopType
        val assetName = configAssetForStopType(stopType)
        val configJson = try {
            activity.assets.open(assetName).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            "Error loading $assetName"
        }

        txtConfigDisplay.visibility = View.VISIBLE
        txtConfigDisplay.text = buildString {
            appendLine("=== Active Config ===")
            appendLine("Config: $assetName")
            appendLine("Model:  ${activity.getModelPath()}")
            appendLine("")
            append(configJson)
            val defaultsMsg = configDefaultsMessage
            if (defaultsMsg != null) {
                appendLine("")
                appendLine("--- Config Defaults Applied ---")
                try {
                    val obj = JSONObject(defaultsMsg)
                    val fields = obj.optJSONArray("fields")
                    val defaults = obj.optJSONObject("defaults")
                    if (fields != null && defaults != null) {
                        for (i in 0 until fields.length()) {
                            val field = fields.optString(i, "")
                            val value = defaults.opt(field)
                            appendLine("  $field = $value (default)")
                        }
                    }
                } catch (_: Exception) {
                    appendLine("  (parse error)")
                }
            }
        }
    }

    private fun createMessageListener(): (String) -> Unit {
        return { json ->
            activity.runOnUiThread {
                try {
                    val obj = JSONObject(json)
                    val type = obj.optString("type", "")
                    when (type) {
                        "result" -> {
                            onResultReceived(obj)
                            val code = obj.optString("code", "")
                            if (activeStopType == "AUTO_SILENCE" || code == "SESSION_TIMEOUT") {
                                isRecording = false
                                updateUi()
                            }
                        }
                        "config" -> {
                            val code = obj.optString("code", "")
                            when (code) {
                                "DEFAULTS_USED" -> {
                                    configDefaultsMessage = json
                                    displayConfigForStopType(activeStopType)
                                }
                            }
                        }
                        "error" -> onErrorReceived(obj)
                    }
                } catch (_: Exception) {
                    txtOutput.text = "Malformed message: $json"
                }
            }
        }
    }

    private fun onResultReceived(obj: JSONObject) {
        val text = obj.optString("text", "")
        val code = obj.optString("code", "")
        val timing = obj.optJSONObject("timing")
        val timingInfo = if (timing != null) {
            val vadActiveMs = timing.optLong("vadActiveMs", 0)
            val utteranceMs = timing.optLong("utteranceMs", 0)
            val inferenceMs = timing.optLong("inferenceMs", 0)
            buildString {
                appendLine("Timing (ms):")
                if (vadActiveMs > 0 || utteranceMs > 0) {
                    appendLine("  vad    = $vadActiveMs")
                    appendLine("  speech = $utteranceMs")
                }
                appendLine("  infer  = $inferenceMs")
                if (utteranceMs > 0 && text.trim().isNotEmpty()) {
                    val speechSecs = utteranceMs / 1000.0
                    val textLen = text.trim().length
                    val ratio = "%.1f".format(textLen / speechSecs)
                    appendLine("  chars/s= $ratio ($textLen chars in ${"%.1f".format(speechSecs)}s)")
                }
            }
        } else ""

        txtOutput.text = "[$code] $text"
        txtDiagnostics.text = timingInfo
        txtDiagnostics.visibility = View.VISIBLE
        updateUi()

        if (text == BLANK_AUDIO_MARKER || text == "") {
            blankAudioCount += 1
            if (blankAudioCount >= blankAudioThreshold) {
                txtOutput.text = "No speech detected. Tap Stop to end the session."
                AppLogger.log(AppLogCode.BLANK_AUDIO_THRESHOLD, blankAudioCount)
            }
        } else {
            blankAudioCount = 0
        }
    }

    private fun onErrorReceived(obj: JSONObject) {
        val action = AppErrorRouter.route(obj)
        if (action.logCode != null) {
            AppLogger.log(action.logCode, *action.logArgs)
        }
        txtErrorBanner.visibility = if (action.showBanner) View.VISIBLE else View.GONE
        action.bannerText?.let { txtErrorBanner.text = it }
        action.outputText?.let { txtOutput.text = it }
        txtDiagnostics.visibility = View.GONE
        txtDiagnostics.text = ""
        updateUi()
    }

    private fun buildConfigJsonForStopType(stopType: String): String {
        val assetName = configAssetForStopType(stopType)
        val template = try {
            activity.assets.open(assetName).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to load config asset: $assetName", e)
        }

        val modelPath = activity.getModelPath()
        val sb = StringBuilder()
        sb.append("{\"modelPath\":\"")
        sb.append(escapeJsonString(modelPath))
        sb.append("\",")
        sb.append(template.trimStart().removePrefix("{").trimStart())
        return sb.toString()
    }

    fun startRecording() {
        if (!isWhisperReady) {
            txtOutput.text = "Whisper model not ready yet."
            return
        }
        try {
            val result = stt.init(buildConfigJsonForStopType(selectedStopType))
            if (result.contains("\"type\":\"error\"")) {
                val message = JSONObject(result).optString("message", "Unknown error")
                AppLogger.log(AppLogCode.SESSION_ERROR, "Session error: $message")
                activity.runOnUiThread { txtOutput.text = "Session error: $message" }
                return
            }

            blankAudioCount = 0
            isRecording = true
            txtOutput.text = "Recording..."
            updateUi()
        } catch (e: IllegalArgumentException) {
            AppLogger.log(AppLogCode.CONFIG_INVALID, e.message ?: "Unknown error")
            showErrorDialog(activity, "Invalid STT Configuration", e.message ?: "Unknown error")
            isRecording = false
            updateUi()
        } catch (e: Exception) {
            AppLogger.log(AppLogCode.INTERNAL_ERROR, e.message ?: "Unknown error")
            showErrorDialog(activity, "STT Error", e.message ?: "Unknown error")
            isRecording = false
            updateUi()
        }
    }

    fun stopRecording() {
        txtOutput.text = "Processing..."
        btnStop.isEnabled = false

        Thread({
            try {
                AppLogger.log(AppLogCode.STOP_USING_STOP_AND_TRANSCRIBE)
                val result = stt.transcribe()
                activity.runOnUiThread {
                    try {
                        val obj = JSONObject(result)
                        val type = obj.optString("type", "")
                        when (type) {
                            "result" -> {
                                onResultReceived(obj)
                            }
                            "error" -> {
                                onErrorReceived(obj)
                            }
                            else -> {
                                txtOutput.text = result
                            }
                        }
                    } catch (_: Exception) {
                        txtOutput.text = result
                    }
                    isRecording = false
                    updateUi()
                }
            } catch (t: Throwable) {
                AppLogger.log(AppLogCode.STOP_FAILED, t.message)
                activity.runOnUiThread { txtOutput.text = "Error: " + t.message }
            }
        }, "TranscribeThread").start()
    }

    fun updateUi() {
        btnStart.isEnabled = !isRecording && isWhisperReady
        btnStop.isEnabled = isRecording
        btnStop.visibility = View.VISIBLE

        setViewEnabled(radioGroupStrategy, !isRecording)

        val output = txtOutput.text.toString()
        btnClear.isEnabled = output.isNotEmpty() && output != "Say something..." && output != "Model loaded. Tap Start to record."
    }
}
