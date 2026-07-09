package dev.barrycade.voicecore

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.ActivityResultLauncher
import android.app.AlertDialog
import androidx.core.content.ContextCompat
import dev.barrycade.voicecore.stt.SpeechToText
import dev.barrycade.voicecore.stt.SessionResult
import dev.barrycade.voicecore.stt.SttReturnCode
import java.io.File
import java.io.FileOutputStream

/**
 * Demo app for the STT module.
 *
 * Uses only the new [SttRunConfig]-based API path:
 * 1. [AppSttConfigLoader.loadSttRunConfig] loads config from JSON asset.
 * 2. [SpeechToText.setConfig] validates and stores the config.
 * 3. [SpeechToText.startSession] starts the session.
 * 4. [SpeechToText.stopAndTranscribe] stops manually (if MANUAL_MANUAL strategy).
 */
class MainActivity : ComponentActivity() {
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnClear: Button
    private lateinit var txtOutput: TextView
    private lateinit var txtDiagnostics: TextView
    private lateinit var txtConfigDisplay: TextView

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

        // Permission launcher
        requestPermissionLauncher = registerForActivityResult(
            RequestPermission()
        ) { granted ->
            if (granted) {
                startRecording()
            } else {
                txtOutput.text = "Microphone permission is required"
            }
        }

        updateUi()
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start recording using the new [SttRunConfig]-based API.
     *
     * Loads config via [AppSttConfigLoader.loadSttRunConfig], sets it via
     * [SpeechToText.setConfig], and starts a session via
     * [SpeechToText.startSession].
     */
    private fun startRecording() {
        val modelPath = getModelPath()

        val runConfig = try {
            AppSttConfigLoader.loadSttRunConfig(
                context = this,
                modelPath = modelPath,
                language = "en"
            )
        } catch (e: Exception) {
            handleConfigError(e)
            return
        }

        try {
            logInfo("STT_INIT", "Constructing STT with SttRunConfig")

            // ── Create STT via public factory ────────────────────────────
            val speechToText = SpeechToText.create(modelPath)

            // ── Set the new config via the wrapper API ────────────────────
            val setConfigResult = speechToText.setConfig(runConfig)
            if (setConfigResult.code != SttReturnCode.SUCCESS) {
                postToUi {
                    txtOutput.text = "Config error: ${setConfigResult.code}"
                }
                return
            }

            // ── Result callback ───────────────────────────────────────────
            speechToText.setOnResultListener { result ->
                postToUi {
                    isRecording = false
                    txtOutput.text = result
                    updateUi()
                }
            }

            // ── Error callback ────────────────────────────────────────────
            speechToText.setOnErrorListener { t ->
                postToUi {
                    txtOutput.text = "Error: ${t.message}"
                }
            }

            // ── Start via the new wrapper API ─────────────────────────────
            val result = speechToText.startSession()
            logInfo("STT_FLOW", "startSession returned: ${result.code}")

            if (result.code != SttReturnCode.SUCCESS) {
                postToUi {
                    txtOutput.text = "Session error: ${result.code}"
                }
                return
            }

            stt = speechToText

            // ── Show active config ────────────────────────────────────────
            txtConfigDisplay.visibility = View.VISIBLE
            txtConfigDisplay.text = buildString {
                appendLine("=== Config ===")
                appendLine("strategy:  ${runConfig.ttsLifeCycleStrategy}")
                appendLine("model:     ${runConfig.ttsEngineConfig.modelPath}")
                appendLine("language:  ${runConfig.ttsEngineConfig.language}")
                appendLine("preRollMs: ${runConfig.ttsEngineConfig.preRollMs}")
                appendLine("stableChunkSizeMs: ${runConfig.ttsEngineConfig.stableChunkSizeMs}")
                when (val specific = runConfig.strategySpecific) {
                    is dev.barrycade.voicecore.stt.ManualManualSpecific -> {
                        appendLine("mode: MANUAL_MANUAL")
                        appendLine("energyThreshold: ${specific.energyThreshold}")
                        appendLine("maxDurationMs:   ${specific.maxDurationMs}")
                        appendLine("abnormalSilenceMs: ${specific.abnormalSilenceMs}")
                    }
                    is dev.barrycade.voicecore.stt.ManualAutoSpecific -> {
                        appendLine("mode: MANUAL_AUTO")
                        appendLine("energyThreshold: ${specific.energyThreshold}")
                        appendLine("maxDurationMs:   ${specific.maxDurationMs}")
                        appendLine("autoSilenceMs:   ${specific.autoSilenceMs}")
                    }
                }
            }

            isRecording = true
            txtOutput.text = "Recording..."
            updateUi()
        } catch (e: IllegalArgumentException) {
            handleConfigError(e)
        }
    }

    private fun handleConfigError(e: Exception) {
        Log.e("STT_CONFIG", "Invalid STT configuration: ${e.message}", e)
        showErrorDialog(
            title = "Invalid STT Configuration",
            message = "The STT tuning values are invalid:\n${e.message}"
        )
        isRecording = false
        stt?.destroy()
        stt = null
        updateUi()
    }

    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
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
                postToUi { txtOutput.text = "Error: ${t.message}" }
            }
        }, "StopAndTranscribeThread")
        thread.start()
    }

    /**
     * Update button states according to current recording state.
     */
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
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return targetFile.absolutePath
    }
}
