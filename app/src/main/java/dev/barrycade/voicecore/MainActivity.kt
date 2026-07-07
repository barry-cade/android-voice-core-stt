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
import dev.barrycade.voicecore.stt.SttConfig
import dev.barrycade.voicecore.stt.SttErrorListener
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnClear: Button
    private lateinit var txtOutput: TextView
    private lateinit var txtErrorBanner: TextView
    private lateinit var txtDiagnostics: TextView
    private lateinit var txtConfigDisplay: TextView

    private var stt: SpeechToText? = null
    private var isRecording = false
    private val debugLogging = true

    // ── Debug toggle state (no UI controls; set breakpoint to change) ────
    private var debugForceAudioInitFailure = false
    private var debugForceWhisperLoadFailure = false
    private var debugForceTimeout = false

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
        txtErrorBanner = findViewById(R.id.txtErrorBanner)
        txtDiagnostics = findViewById(R.id.txtDiagnostics)
        txtConfigDisplay = findViewById(R.id.txtConfigDisplay)

        btnStart.setOnClickListener {
            logInfo("STT_FLOW", "Start button pressed")
            updateUi()
            if (hasRecordAudioPermission()) startRecording()
            else requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        btnStop.setOnClickListener {
            logInfo("STT_FLOW", "Stop button pressed")
            stopAndTranscribe()
        }

        btnClear.setOnClickListener {
            txtOutput.text = ""
        }

        // Permission launcher — initialised after view bindings so the
        // callback can safely reference lateinit views.
        requestPermissionLauncher = registerForActivityResult(
            RequestPermission()
        ) { granted ->
            if (granted) {
                updateUi()
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

    private fun startRecording() {
        val modelPath = getModelPath()
        val runtimeConfig = try {
            AppSttConfigLoader.loadFromAssets(this)
        } catch (e: Exception) {
            handleConfigError(e)
            return
        }

        try {
            logInfo("STT_INIT", "Constructing STT with modelPath=$modelPath")
            val speechToText = SpeechToText.create(
                SttConfig(
                    energyThreshold = runtimeConfig.energyThreshold,
                    silencePaddingMs = runtimeConfig.silencePaddingMs,
                    preRollMs = runtimeConfig.preRollMs,
                    maxUtteranceLengthMs = runtimeConfig.maxUtteranceLengthMs,
                    stableChunkSizeMs = runtimeConfig.stableChunkSizeMs,
                    motionModeEnergyThreshold = runtimeConfig.motionMode.energyThreshold,
                    motionModeSilencePaddingMs = runtimeConfig.motionMode.silencePaddingMs,
                    modelPath = modelPath,
                    startStrategy = runtimeConfig.startStrategy,
                    stopStrategy = runtimeConfig.stopStrategy
                )
            )

            // ── Apply test hooks via public API ───────────────────────────
            speechToText.setDebugOptions(
                forceAudioInitFailure = debugForceAudioInitFailure,
                forceWhisperLoadFailure = debugForceWhisperLoadFailure,
                forceTimeout = debugForceTimeout
            )

            // ── Result callback ───────────────────────────────────────────
            speechToText.setOnResultListener { result ->
                postToUi { txtOutput.text = result }
            }

            // ── Timing callback ───────────────────────────────────────────
            val timingListener: (Long, Long, Long, Long) -> Unit = { pcmMs, vadActiveMs, whisperMs, totalMs ->
                postToUi {
                    txtDiagnostics.visibility = android.view.View.VISIBLE
                    txtDiagnostics.text = buildString {
                        appendLine("=== Timing Diagnostics ===")
                        appendLine("PCM duration:    ${pcmMs}ms")
                        appendLine("VAD active:      ${vadActiveMs}ms")
                        appendLine("Whisper inf:     ${whisperMs}ms")
                        appendLine("Total duration:  ${totalMs}ms")
                    }
                }
            }
            speechToText.onTimingListener = timingListener

            // ── Structured error callback ─────────────────────────────────
            val errorListener = SttErrorListener { error ->
                postToUi {
                    val keySet = setOf("pcmMs", "vadActiveMs", "whisperMs", "totalMs")
                    val timingCtx = error.context.filterKeys { it in keySet }
                    val timingMs = error.timingSnapshotMs
                    val rms = error.lastRms
                    val vadState = error.lastVadState
                    val errorContext = error.context
                    txtDiagnostics.visibility = android.view.View.VISIBLE
                    txtDiagnostics.text = buildString {
                        appendLine("=== Error ===")
                        appendLine("Category: ${error.category}")
                        appendLine("Code:    ${error.code}")
                        appendLine("Message: ${error.message}")
                        if (rms != null) {
                            appendLine("Last RMS: $rms")
                        }
                        if (vadState != null) {
                            appendLine("VAD speech: $vadState")
                        }
                        if (timingMs != null) {
                            appendLine("Timing:")
                            timingMs.forEach { (key, value) ->
                                appendLine("  $key = ${value}ms")
                            }
                        }
                        if (errorContext.isNotEmpty()) {
                            appendLine("Context:")
                            errorContext.forEach { (key, value) ->
                                appendLine("  $key = $value")
                            }
                                }
                        if (timingCtx.isNotEmpty()) {
                            appendLine("Timing at error:")
                            timingCtx.forEach { (key, value) ->
                                appendLine("  $key = $value")
                            }
                        }
                    }
                    txtOutput.text = "Error: ${error.category} - ${error.code} - ${error.message}"
                }
            }
            speechToText.setSttErrorListener(errorListener)

            // ── Legacy error callback (backwards compat) ──────────────────
            speechToText.setOnErrorListener { t ->
                postToUi {
                    if (txtDiagnostics.text.isNullOrBlank()) {
                        txtDiagnostics.visibility = android.view.View.VISIBLE
                        txtDiagnostics.text = "Error: ${t.message}"
                    }
                    txtOutput.text = "Error: ${t.message}"
                }
            }

            // ── Start immediately — queued-start handles waiting for READY ─
            speechToText.start()
            stt = speechToText

            // ── Show active config ────────────────────────────────────────
            txtConfigDisplay.visibility = android.view.View.VISIBLE
            txtConfigDisplay.text = buildString {
                appendLine("=== Active Config ===")
                appendLine("energyThreshold:        ${runtimeConfig.energyThreshold}")
                appendLine("silencePaddingMs:       ${runtimeConfig.silencePaddingMs}")
                appendLine("preRollMs:              ${runtimeConfig.preRollMs}")
                appendLine("maxUtteranceLengthMs:   ${runtimeConfig.maxUtteranceLengthMs}")
                appendLine("stableChunkSizeMs:      ${runtimeConfig.stableChunkSizeMs}")
                appendLine("motionMode.energyThreshold:   ${runtimeConfig.motionMode.energyThreshold}")
                appendLine("motionMode.silencePaddingMs:  ${runtimeConfig.motionMode.silencePaddingMs}")
                appendLine("startStrategy:          ${runtimeConfig.startStrategy}")
                appendLine("stopStrategy:           ${runtimeConfig.stopStrategy}")
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
        stt = null
        txtErrorBanner.visibility = android.view.View.VISIBLE
        updateUi()
    }

    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun stopAndTranscribe() {
        val currentStt = stt
        if (currentStt == null) {
            postToUi { txtOutput.text = "Not yet started" }
            return
        }

        isRecording = false
        txtOutput.text = "Processing..."
        updateUi()

        val runnable = Runnable {
            runStopAndTranscribe(currentStt)
        }
        val thread = Thread(runnable, "StopAndTranscribeThread")
        thread.start()
    }

    private fun runStopAndTranscribe(stt: SpeechToText) {
        try {
            Log.d("MainActivity", "STOP pressed → using deterministic stopAndTranscribe()")
            stt.stopAndTranscribe()
        } catch (t: Throwable) {
            postToUi { txtOutput.text = "Error: ${t.message}" }
        }
    }

    private fun updateUi() {
        btnStart.isEnabled = !isRecording
        btnStop.isEnabled = isRecording
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