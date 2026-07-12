package dev.barrycade.voicecore

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
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
import dev.barrycade.voicecore.stt.SpeechToTextProvider
import dev.barrycade.voicecore.stt.SttReturnCode
import dev.barrycade.voicecore.stt.SttRunConfig
import dev.barrycade.voicecore.stt.StopStrategyConfig
import dev.barrycade.voicecore.stt.StartStrategyConfig
import java.io.File
import java.io.FileOutputStream

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
    private var activeStartStrategyType: String = "MANUAL"
    private var activeStopStrategyType: String = "MANUAL"

    // Guard: consecutive blank-audio hints.
    private var blankAudioCount: Int = 0
    private val blankAudioThreshold: Int = 3

    private var stt: SpeechToText? = null
    private var isRecording = false
    private val debugLogging = true

    private fun logInfo(tag: String, message: String) {
        if (debugLogging) Log.i(tag, message)
    }

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

        // Initialise the singleton STT instance once per app lifetime.
        // The model is NOT loaded here — only the SpeechToText object is created.
        // Call initStt() with a config to load the model and build scaffolding.
        stt = SpeechToTextProvider.get(applicationContext)

        radioGroupStrategy.setOnCheckedChangeListener { _, checkedId ->
            val newStopType: String = when (checkedId) {
                R.id.radioManualAuto -> "AUTO_SILENCE"
                else -> "MANUAL"
            }
            selectedStopType = newStopType
            loadAndApplyConfig(newStopType)
        }

        btnStart.setOnClickListener {
            logInfo("STT_FLOW", "Start button pressed")
            if (hasRecordAudioPermission()) startRecording()
            else requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        btnStop.setOnClickListener {
            logInfo("STT_FLOW", "Stop button pressed")
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

        loadAndApplyConfig("MANUAL")
        updateUi()
    }

    private fun loadAndApplyConfig(stopType: String) {
        val configFileName = when (stopType) {
            "AUTO_SILENCE" -> "stt_config_manual_auto.json"
            else -> "stt_config_manual_manual.json"
        }

        val modelPath = getModelPath()

        val runConfig: SttRunConfig = try {
            AppSttConfigLoader.loadSttRunConfig(
                context = this,
                configFileName = configFileName,
                modelPath = modelPath,
                language = "en"
            )
        } catch (e: Exception) {
            Log.e("STT_CONFIG", "Failed to load config", e)
            postToUi {
                txtConfigDisplay.visibility = View.VISIBLE
                txtConfigDisplay.text = "ERROR: Failed to load " + configFileName
            }
            return
        }

        val currentStt = stt
        if (currentStt != null) {
            val setConfigResult = currentStt.setConfig(runConfig)
            if (setConfigResult.code != SttReturnCode.SUCCESS) {
                postToUi {
                    txtConfigDisplay.visibility = View.VISIBLE
                    txtConfigDisplay.text = "Config error: " + setConfigResult.code
                }
                return
            }
        }

        displayConfig(runConfig)
    }

    private fun displayConfig(runConfig: SttRunConfig) {
        activeStartStrategyType = runConfig.startStrategy.type
        activeStopStrategyType = runConfig.stopStrategy.type
        txtConfigDisplay.visibility = View.VISIBLE
        txtConfigDisplay.text = buildString {
            appendLine("=== Active Config ===")
            appendLine("model:     " + runConfig.ttsEngineConfig.modelPath)
            appendLine("language:  " + runConfig.ttsEngineConfig.language)
            appendLine("drainMode: " + runConfig.drainMode)
            appendLine("start:     " + runConfig.startStrategy.type)
            appendLine("stop:      " + runConfig.stopStrategy.type)
            appendLine("--- VAD ---")
            appendLine("energyThreshold: " + runConfig.vadConfig.energyThreshold)
            appendLine("preRollMs:       " + runConfig.vadConfig.preRollMs)
            appendLine("stableChunkSizeMs: " + runConfig.vadConfig.stableChunkSizeMs)
            when (runConfig.stopStrategy.type) {
                "AUTO_SILENCE" -> {
                    appendLine("--- Auto-silence ---")
                    appendLine("silenceMs:     " + runConfig.stopStrategy.silenceMs)
                    appendLine("maxDurationMs: " + runConfig.stopStrategy.maxDurationMs)
                }
            }
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun startRecording() {
        val configFileName = when (selectedStopType) {
            "AUTO_SILENCE" -> "stt_config_manual_auto.json"
            else -> "stt_config_manual_manual.json"
        }

        val modelPath = getModelPath()

        val runConfig = try {
            AppSttConfigLoader.loadSttRunConfig(
                context = this,
                configFileName = configFileName,
                modelPath = modelPath,
                language = "en"
            )
        } catch (e: Exception) {
            handleConfigError(e)
            return
        }

        try {
            logInfo("STT_INIT", "Obtaining singleton STT instance")
            val speechToText = SpeechToTextProvider.get(applicationContext)

            // Set config on the singleton instance.
            val setConfigResult = speechToText.setConfig(runConfig)
            if (setConfigResult.code != SttReturnCode.SUCCESS) {
                postToUi { txtOutput.text = "Config error: " + setConfigResult.code }
                return
            }

            // Initialise STT (load model + warm-up + build scaffolding).
            // Idempotent: second call returns SUCCESS immediately.
            val initResult = speechToText.initStt(runConfig)
            if (initResult.code != SttReturnCode.SUCCESS) {
                postToUi { txtOutput.text = "Init error: " + initResult.code }
                return
            }

            speechToText.setOnResultWithTimingListener { text, code, timing ->
                postToUi {
                    val timingInfo = if (timing != null) {
                        val vadMs = timing.vadActiveMs
                        val utteranceMs = timing.utteranceDurationMs
                        val inferenceMs = timing.inferenceMs
                        val totalMs = timing.totalPipelineMs
                        "Timing: vad=${vadMs}ms, " +
                        "utterance=${utteranceMs}ms, " +
                        "inference=${inferenceMs}ms, " +
                        "total=${totalMs}ms"
                    } else ""
                    txtOutput.text = "[$code] $text"
                    txtDiagnostics.text = timingInfo
                    txtDiagnostics.visibility = android.view.View.VISIBLE

                    if (text == BLANK_AUDIO_MARKER || text == "") {
                        blankAudioCount += 1
                        if (blankAudioCount >= blankAudioThreshold) {
                            txtOutput.text = "No speech detected. Tap Stop to end the session."
                        }
                    } else {
                        blankAudioCount = 0
                    }
                }
            }

            speechToText.setOnErrorListener { t ->
                postToUi { txtOutput.text = "Error: " + t.message }
            }

            val sttResult = speechToText.startSession()
            logInfo("STT_FLOW", "startSession returned: " + sttResult.code)

            if (sttResult.code != SttReturnCode.SUCCESS) {
                postToUi { txtOutput.text = "Session error: " + sttResult.code }
                return
            }

            stt = speechToText
            displayConfig(runConfig)
            blankAudioCount = 0
            isRecording = true
            txtOutput.text = "Recording..."

            btnStop.isEnabled = true
            btnStart.isEnabled = false
        } catch (e: IllegalArgumentException) {
            handleConfigError(e)
        }
    }

    private fun handleConfigError(e: Exception) {
        Log.e("STT_CONFIG", "Invalid config", e)
        showErrorDialog("Invalid STT Configuration", e.message ?: "Unknown error")
        isRecording = false
        // Do NOT destroy the singleton STT — keep it for the next attempt.
        updateUi()
    }

    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun stopRecording() {
        val currentStt = stt
        if (currentStt == null) {
            postToUi { txtOutput.text = "Not yet started" }
            return
        }
        // Rule 2: Stop button must call stopAndTranscribe() every time.
        // No conditions, no gating, no "only if speech detected."
        txtOutput.text = "Processing..."
        val thread = Thread({
            try {
                Log.d("MainActivity", "STOP pressed -> using stopAndTranscribe()")
                currentStt.stopAndTranscribe()
                // Rule 3: Disable stop button only after stopAndTranscribe() returns.
                postToUi {
                    isRecording = false
                    // Rule 3: Stop button disabled only when session actually ended.
                    btnStop.isEnabled = false
                    btnStart.isEnabled = true
                }
            } catch (t: Throwable) {
                postToUi { txtOutput.text = "Error: " + t.message }
            }
        }, "StopAndTranscribeThread")
        thread.start()
    }

    private fun updateUi() {
        // Rule 6: UI must reflect strategy configuration.
        // Start button visibility.
        val showStart = !isRecording && activeStartStrategyType == "MANUAL"
        btnStart.visibility = if (showStart) View.VISIBLE else View.GONE

        // Stop button visibility.
        val showStop = activeStopStrategyType == "MANUAL"
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
