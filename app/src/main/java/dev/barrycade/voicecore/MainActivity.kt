package dev.barrycade.voicecore

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.ActivityResultLauncher
import android.app.AlertDialog
import androidx.core.content.ContextCompat
import dev.barrycade.voicecore.stt.SpeechToText
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnClear: Button
    private lateinit var txtOutput: TextView
    private lateinit var txtDiagnostics: TextView
    private lateinit var txtConfigDisplay: TextView
    private lateinit var radioGroupStrategy: RadioGroup

    private var selectedStopType: String = "MANUAL"

    // Track strategy from active config for UI visibility.
    private var activeStopType: String = "MANUAL"

    // Guard: consecutive blank-audio hints.
    private var blankAudioCount: Int = 0
    private val blankAudioThreshold: Int = 3

    private var isRecording = false

    private fun postToUi(action: () -> Unit) {
        runOnUiThread(action)
    }

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

    companion object {
        private const val BLANK_AUDIO_MARKER = "[BLANK_AUDIO]"
        private const val CONFIG_MANUAL_MANUAL = "stt_config_manual_manual.json"
        private const val CONFIG_MANUAL_AUTO = "stt_config_manual_auto.json"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnClear = findViewById(R.id.btnClear)
        txtOutput = findViewById(R.id.txtOutput)
        txtDiagnostics = findViewById(R.id.txtDiagnostics)
        txtConfigDisplay = findViewById(R.id.txtConfigDisplay)
        radioGroupStrategy = findViewById(R.id.radioGroupStrategy)

        // Register the JSON message listener before loadModel.
        // The listener is buffered by the companion and wired once the
        // singleton is created inside loadModel().
        SpeechToText.setOnMessageListener { json ->
            postToUi {
                try {
                    val obj = JSONObject(json)
                    val type = obj.optString("type", "")
                    when (type) {
                        "result" -> {
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
                                    if (utteranceMs > 0 && text.trim().length > 0) {
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

                            if (text == BLANK_AUDIO_MARKER || text == "") {
                                blankAudioCount += 1
                                if (blankAudioCount >= blankAudioThreshold) {
                                    txtOutput.text =
                                        "No speech detected. Tap Stop to end the session."
                                }
                            } else {
                                blankAudioCount = 0
                            }
                        }
                        "error" -> {
                            val errorCode = obj.optString("code", "")
                            val message = obj.optString("message", "")
                            txtOutput.text = "Error [$errorCode]: $message"
                        }
                    }
                } catch (_: Exception) {
                    txtOutput.text = "Malformed message: $json"
                }
            }
        }

        // ── Preload model at startup ──────────────────────────────────────────
        preloadModelAsync()

        radioGroupStrategy.setOnCheckedChangeListener { _, checkedId ->
            val newStopType: String = when (checkedId) {
                R.id.radioManualAuto -> "AUTO_SILENCE"
                else -> "MANUAL"
            }
            selectedStopType = newStopType
            displayConfigForStopType(newStopType)
        }

        btnStart.setOnClickListener {
            AppLogger.log(AppLogCode.START_BUTTON_PRESSED)
            if (hasRecordAudioPermission()) startRecording()
            else requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        btnStop.setOnClickListener {
            AppLogger.log(AppLogCode.STOP_BUTTON_PRESSED)
            stopRecording()
        }

        btnClear.setOnClickListener {
            txtOutput.text = ""
        }

        requestPermissionLauncher = registerForActivityResult(
            RequestPermission()
        ) { granted ->
            if (granted) {
                startRecording()
            } else {
                txtOutput.text = "Microphone permission is required"
            }
        }

        displayConfigForStopType("MANUAL")
        updateUi()
    }

    private fun displayConfigForStopType(stopType: String) {
        activeStopType = stopType

        val assetName = configAssetForStopType(stopType)
        val configJson = try {
            assets.open(assetName).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            "Error loading $assetName"
        }

        txtConfigDisplay.visibility = View.VISIBLE
        txtConfigDisplay.text = buildString {
            appendLine("=== Active Config ===")
            appendLine("Config: $assetName")
            appendLine("Model:  ${getModelPath()}")
            appendLine("")
            append(configJson)
        }
    }

    private fun configAssetForStopType(stopType: String): String {
        return when (stopType) {
            "AUTO_SILENCE" -> CONFIG_MANUAL_AUTO
            else -> CONFIG_MANUAL_MANUAL
        }
    }

    /**
     * Preload the STT model in the background at app startup.
     * Ensures the model file is copied from assets and the
     * singleton is initialised before the user presses Start.
     */
    private fun preloadModelAsync() {
        Thread({
            try {
                val configJson = loadConfigForStopType(selectedStopType)
                val resultJson = SpeechToText.loadModel(this, configJson)
                postToUi {
                    if (resultJson.contains("\"type\":\"error\"")) {
                        txtOutput.text = "Model preload error"
                    } else {
                        txtOutput.text = "Model loaded. Tap Start to record."
                    }
                }
            } catch (t: Throwable) {
                postToUi {
                    txtOutput.text = "Model preload failed: ${t.message}"
                }
            }
        }, "ModelPreloadThread").start()
    }

    private fun loadConfigForStopType(stopType: String): String {
        val assetName = configAssetForStopType(stopType)
        val template = try {
            assets.open(assetName).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to load config asset: $assetName", e)
        }

        val modelPath = getModelPath()
        // Inject modelPath into the JSON — the asset file doesn't carry it
        // since it's a runtime/environment concern, not a behavioural config knob.
        val sb = StringBuilder()
        sb.append("{\"modelPath\":\"")
        sb.append(escapeJsonString(modelPath))
        sb.append("\",")
        // Strip the opening { from the template and append the rest
        sb.append(template.trimStart().removePrefix("{").trimStart())
        return sb.toString()
    }

    private fun escapeJsonString(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startRecording() {
        try {
            // The message listener was already registered in onCreate().
            // startSession() starts the capture — the singleton and model
            // were already loaded at app startup via loadModel().
            val sessionResultJson = SpeechToText.startSession()
            val sessionObj = JSONObject(sessionResultJson)
            if (sessionObj.optString("type") == "error") {
                val errorCode = sessionObj.optString("code", "")
                val message = sessionObj.optString("message", "")
                AppLogger.log(AppLogCode.INIT_FAILED, "$errorCode: $message")
                postToUi { txtOutput.text = "Session error: $message" }
                return
            }

            blankAudioCount = 0
            isRecording = true
            txtOutput.text = "Recording..."
            btnStop.isEnabled = true
            btnStart.isEnabled = false

            txtConfigDisplay.visibility = View.VISIBLE
        } catch (e: IllegalArgumentException) {
            AppLogger.log(AppLogCode.CONFIG_INVALID, e.message ?: "Unknown error")
            showErrorDialog("Invalid STT Configuration", e.message ?: "Unknown error")
            isRecording = false
            updateUi()
        } catch (e: Exception) {
            AppLogger.log(AppLogCode.INTERNAL_ERROR, e.message ?: "Unknown error")
            showErrorDialog("STT Error", e.message ?: "Unknown error")
            isRecording = false
            updateUi()
        }
    }

    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun stopRecording() {
        txtOutput.text = "Processing..."
        val thread = Thread({
            try {
                AppLogger.log(AppLogCode.STOP_USING_STOP_AND_TRANSCRIBE)
                SpeechToText.transcribe()
                postToUi {
                    isRecording = false
                    btnStop.isEnabled = false
                    btnStart.isEnabled = true
                }
            } catch (t: Throwable) {
                AppLogger.log(AppLogCode.STOP_FAILED, t.message)
                postToUi { txtOutput.text = "Error: " + t.message }
            }
        }, "TranscribeThread")
        thread.start()
    }

    private fun updateUi() {
        val showStart = !isRecording
        btnStart.visibility = if (showStart) View.VISIBLE else View.GONE

        val showStop = activeStopType == "MANUAL"
        btnStop.visibility = if (showStop) View.VISIBLE else View.GONE

        btnClear.isEnabled = true
    }

    private fun getModelPath(): String {
        val targetFile = File(filesDir, "model.bin")
        if (!targetFile.exists()) {
            targetFile.parentFile?.mkdirs()
            assets.open("models/ggml-tiny.en.bin").use { input ->
                FileOutputStream(targetFile).use { output -> input.copyTo(output) }
            }
        }
        return targetFile.absolutePath
    }
}
