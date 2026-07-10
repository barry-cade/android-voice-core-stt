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
import dev.barrycade.voicecore.stt.ManualAutoSpecific
import dev.barrycade.voicecore.stt.ManualManualSpecific
import dev.barrycade.voicecore.stt.SpeechToText
import dev.barrycade.voicecore.stt.SessionResult
import dev.barrycade.voicecore.stt.SttLifeCycleStrategy
import dev.barrycade.voicecore.stt.SttReturnCode
import dev.barrycade.voicecore.stt.SttRunConfig
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

    private var selectedStrategy: SttLifeCycleStrategy = SttLifeCycleStrategy.MANUAL_MANUAL

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

        radioGroupStrategy.setOnCheckedChangeListener { _, checkedId ->
            val newStrategy: SttLifeCycleStrategy = when (checkedId) {
                R.id.radioManualAuto -> SttLifeCycleStrategy.MANUAL_AUTO
                else -> SttLifeCycleStrategy.MANUAL_MANUAL
            }
            selectedStrategy = newStrategy
            loadAndApplyConfig(newStrategy)
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

        loadAndApplyConfig(SttLifeCycleStrategy.MANUAL_MANUAL)
        updateUi()
    }

    private fun loadAndApplyConfig(strategy: SttLifeCycleStrategy) {
        val configFileName = when (strategy) {
            SttLifeCycleStrategy.MANUAL_MANUAL -> "stt_config_manual_manual.json"
            SttLifeCycleStrategy.MANUAL_AUTO -> "stt_config_manual_auto.json"
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
        txtConfigDisplay.visibility = View.VISIBLE
        txtConfigDisplay.text = buildString {
            appendLine("=== Active Config ===")
            appendLine("strategy:  " + runConfig.ttsLifeCycleStrategy)
            appendLine("model:     " + runConfig.ttsEngineConfig.modelPath)
            appendLine("language:  " + runConfig.ttsEngineConfig.language)
            appendLine("preRollMs: " + runConfig.ttsEngineConfig.preRollMs)
            appendLine("stableChunkSizeMs: " + runConfig.ttsEngineConfig.stableChunkSizeMs)
            when (val specific = runConfig.strategySpecific) {
                is ManualManualSpecific -> {
                    appendLine("mode: MANUAL_MANUAL")
                    appendLine("energyThreshold: " + specific.energyThreshold)
                    appendLine("maxDurationMs:   " + specific.maxDurationMs)
                    appendLine("abnormalSilenceMs: " + specific.abnormalSilenceMs)
                    appendLine("drainMode:      " + specific.drainMode)
                }
                is ManualAutoSpecific -> {
                    appendLine("mode: MANUAL_AUTO")
                    appendLine("energyThreshold: " + specific.energyThreshold)
                    appendLine("maxDurationMs:   " + specific.maxDurationMs)
                    appendLine("autoSilenceMs:   " + specific.autoSilenceMs)
                }
            }
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun startRecording() {
        val configFileName = when (selectedStrategy) {
            SttLifeCycleStrategy.MANUAL_MANUAL -> "stt_config_manual_manual.json"
            SttLifeCycleStrategy.MANUAL_AUTO -> "stt_config_manual_auto.json"
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
            logInfo("STT_INIT", "Constructing STT with SttRunConfig")
            val speechToText = SpeechToText.create(modelPath)

            val setConfigResult = speechToText.setConfig(runConfig)
            if (setConfigResult.code != SttReturnCode.SUCCESS) {
                postToUi { txtOutput.text = "Config error: " + setConfigResult.code }
                return
            }

                                                                                                speechToText.setOnResultWithTimingListener { text, code, timing ->
                postToUi {
                    isRecording = false
                    val timingInfo = if (timing != null) {
                        "Timing: vad=${timing.vadActiveMs}ms, " +
                        "utterance=${timing.utteranceDurationMs}ms, " +
                        "inference=${timing.inferenceMs}ms, " +
                        "total=${timing.totalPipelineMs}ms"
                    } else ""
                    txtOutput.text = "[$code] $text"
                    txtDiagnostics.text = timingInfo
                    txtDiagnostics.visibility = android.view.View.VISIBLE
                    updateUi()
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
            isRecording = true
            txtOutput.text = "Recording..."
            updateUi()
        } catch (e: IllegalArgumentException) {
            handleConfigError(e)
        }
    }

    private fun handleConfigError(e: Exception) {
        Log.e("STT_CONFIG", "Invalid config", e)
        showErrorDialog("Invalid STT Configuration", e.message ?: "Unknown error")
        isRecording = false
        stt?.destroy()
        stt = null
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
        isRecording = false
        txtOutput.text = "Processing..."
        updateUi()
        val thread = Thread({
            try {
                Log.d("MainActivity", "STOP pressed -> using stopAndTranscribe()")
                currentStt.stopAndTranscribe()
            } catch (t: Throwable) {
                postToUi { txtOutput.text = "Error: " + t.message }
            }
        }, "StopAndTranscribeThread")
        thread.start()
    }

    private fun updateUi() {
        btnStart.isEnabled = !isRecording
        btnStop.isEnabled = isRecording
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
