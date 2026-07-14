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

        // Register the JSON message listener before init.
        // The listener is buffered by the companion and wired once the
        // singleton is created inside init().
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
                                val captureMs = timing.optLong("captureMs", 0)
                                val inferenceMs = timing.optLong("inferenceMs", 0)
                                val totalMs = timing.optLong("totalMs", 0)
                                "Timing: capture=${captureMs}ms, " +
                                "inference=${inferenceMs}ms, " +
                                "total=${totalMs}ms"
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

        val modelPath = getModelPath()

        txtConfigDisplay.visibility = View.VISIBLE
        txtConfigDisplay.text = buildString {
            appendLine("=== Active Config ===")
            appendLine("model:     $modelPath")
            appendLine("language:  en")
            appendLine("drainMode: DRAIN_FROM_HEAD")
            appendLine("start:     MANUAL")
            appendLine("stop:      $stopType")
            appendLine("--- VAD ---")
            appendLine("energyThreshold: 0.03")
            appendLine("preRollMs:       0")
            appendLine("stableChunkSizeMs: 500")
            if (stopType == "AUTO_SILENCE") {
                appendLine("--- Auto-silence ---")
                appendLine("silenceMs:     1200")
                appendLine("maxDurationMs: 30000")
            }
        }
    }

    private fun buildConfigJson(
        modelPath: String,
        language: String,
        stopType: String
    ): String {
        val root = JSONObject()
        root.put("modelPath", modelPath)
        root.put("language", language)
        root.put("debugLoggingEnabled", true)
        root.put("energyThreshold", 0.03)
        root.put("preRollMs", 0)
        root.put("stableChunkSizeMs", 500)
        root.put("drainMode", "DRAIN_FROM_HEAD")
        root.put("startType", "MANUAL")
        root.put("stopType", stopType)
        root.put("warmupEnabled", true)
        root.put("warmupDurationMs", 3000)

        if (stopType == "AUTO_SILENCE") {
            root.put("silenceMs", 1200)
            root.put("maxDurationMs", 30000)
        }

        return root.toString()
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startRecording() {
        val modelPath = getModelPath()
        val configJson = buildConfigJson(modelPath, "en", selectedStopType)

        try {
            AppLogger.log(AppLogCode.OBTAINING_STT_INSTANCE)

            // The message listener was already registered in onCreate().
            // init() creates the singleton if needed, wires the buffer listener,
            // and returns the init result.
            val initResultJson = SpeechToText.init(this, configJson)
            val initObj = JSONObject(initResultJson)
            if (initObj.optString("type") == "error") {
                val errorCode = initObj.optString("code", "")
                val message = initObj.optString("message", "")
                AppLogger.log(AppLogCode.INIT_FAILED, "$errorCode: $message")
                postToUi { txtOutput.text = "Init error: $message" }
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
