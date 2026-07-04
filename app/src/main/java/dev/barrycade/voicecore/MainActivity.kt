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
import android.app.AlertDialog
import androidx.core.content.ContextCompat
import dev.barrycade.voicecore.stt.SpeechToText
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
    private lateinit var txtDebugTitle: TextView
    private lateinit var layoutDebugToggles: View
    private lateinit var btnForceAudioFail: Button
    private lateinit var btnForceWhisperFail: Button
    private lateinit var btnForceTimeout: Button

    private var stt: SpeechToText? = null
    private var isRecording = false
    private val debugLogging = true

    // ── Debug toggle state ───────────────────────────────────────────────
    private var debugForceAudioInitFailure = false
    private var debugForceWhisperLoadFailure = false
    private var debugForceTimeout = false

    private fun logInfo(tag: String, message: String) {
        if (debugLogging) Log.i(tag, message)
    }

    private val requestPermissionLauncher = registerForActivityResult(
        RequestPermission()
    ) { granted ->
        if (granted) startRecording()
        else txtOutput.text = "Microphone permission is required"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnClear = findViewById(R.id.btnClear)
        txtOutput = findViewById(R.id.txtOutput)
        txtErrorBanner = findViewById(R.id.txtErrorBanner)
        txtDiagnostics = findViewById(R.id.txtDiagnostics)
        txtDebugTitle = findViewById(R.id.txtDebugTitle)
        layoutDebugToggles = findViewById(R.id.layoutDebugToggles)
        btnForceAudioFail = findViewById(R.id.btnForceAudioFail)
        btnForceWhisperFail = findViewById(R.id.btnForceWhisperFail)
        btnForceTimeout = findViewById(R.id.btnForceTimeout)

        // ── Debug toggle click handlers ────────────────────────────────
        btnForceAudioFail.setOnClickListener {
            debugForceAudioInitFailure = !debugForceAudioInitFailure
            btnForceAudioFail.isSelected = debugForceAudioInitFailure
            btnForceAudioFail.setBackgroundColor(
                if (debugForceAudioInitFailure) 0xFFFFCDD2.toInt() else android.graphics.Color.TRANSPARENT
            )
            logInfo("STT_DEBUG", "forceAudioInitFailure=$debugForceAudioInitFailure")
        }

        btnForceWhisperFail.setOnClickListener {
            debugForceWhisperLoadFailure = !debugForceWhisperLoadFailure
            btnForceWhisperFail.isSelected = debugForceWhisperLoadFailure
            btnForceWhisperFail.setBackgroundColor(
                if (debugForceWhisperLoadFailure) 0xFFFFCDD2.toInt() else android.graphics.Color.TRANSPARENT
            )
            logInfo("STT_DEBUG", "forceWhisperLoadFailure=$debugForceWhisperLoadFailure")
        }

        btnForceTimeout.setOnClickListener {
            debugForceTimeout = !debugForceTimeout
            btnForceTimeout.isSelected = debugForceTimeout
            btnForceTimeout.setBackgroundColor(
                if (debugForceTimeout) 0xFFFFCDD2.toInt() else android.graphics.Color.TRANSPARENT
            )
            logInfo("STT_DEBUG", "forceTimeout=$debugForceTimeout")
        }

        // Show debug section
        txtDebugTitle.visibility = android.view.View.VISIBLE
        layoutDebugToggles.visibility = android.view.View.VISIBLE

        btnStart.setOnClickListener {
            logInfo("STT_FLOW", "startRecording() called")
            if (hasRecordAudioPermission()) startRecording()
            else requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        btnStop.setOnClickListener {
            logInfo("STT_FLOW", "stopAndTranscribe() called")
            if (isRecording) stopAndTranscribe()
        }

        btnClear.setOnClickListener {
            txtOutput.text = ""
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
                energyThreshold = runtimeConfig.energyThreshold,
                silencePaddingMs = runtimeConfig.silencePaddingMs,
                preRollMs = runtimeConfig.preRollMs,
                maxUtteranceLengthMs = runtimeConfig.maxUtteranceLengthMs,
                stableChunkSizeMs = runtimeConfig.stableChunkSizeMs,
                motionModeEnergyThreshold = runtimeConfig.motionMode.energyThreshold,
                motionModeSilencePaddingMs = runtimeConfig.motionMode.silencePaddingMs,
                modelPath = modelPath
                )

            // ── Apply test hooks via public API ───────────────────────────
            speechToText.setDebugOptions(
                forceAudioInitFailure = debugForceAudioInitFailure,
                forceWhisperLoadFailure = debugForceWhisperLoadFailure,
                forceTimeout = debugForceTimeout
            )

            // ── Result callback ───────────────────────────────────────────
            speechToText.setOnResultListener { result ->
                runOnUiThread { txtOutput.text = result }
            }

            // ── Timing callback ───────────────────────────────────────────
            val timingListener: (Long, Long, Long, Long) -> Unit = { pcmMs, vadActiveMs, whisperMs, totalMs ->
                runOnUiThread {
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
                runOnUiThread {
                    val keySet = setOf("pcmMs", "vadActiveMs", "whisperMs", "totalMs")
                    val timingCtx = error.context.filterKeys { it in keySet }
                    txtDiagnostics.visibility = android.view.View.VISIBLE
                    txtDiagnostics.text = buildString {
                        appendLine("=== Error ===")
                        appendLine("Code:    ${error.code}")
                        appendLine("Message: ${error.message}")
                        if (error.context.isNotEmpty()) {
                            appendLine("Context:")
                            error.context.forEach { (key, value) ->
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
                    txtOutput.text = "Error: ${error.code} - ${error.message}"
                }
            }
            speechToText.setSttErrorListener(errorListener)

            // ── Legacy error callback (backwards compat) ──────────────────
            speechToText.setOnErrorListener { t ->
                runOnUiThread {
                    if (txtDiagnostics.text.isNullOrBlank()) {
                        txtDiagnostics.visibility = android.view.View.VISIBLE
                        txtDiagnostics.text = "Error: ${t.message}"
                    }
                    txtOutput.text = "Error: ${t.message}"
                }
            }

            speechToText.start()
            stt = speechToText

            isRecording = true
            txtOutput.text = "Recording..."
            updateUi()

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
        isRecording = false
        txtOutput.text = "Processing..."
        updateUi()

        Thread {
            try {
                Log.d("MainActivity", "STOP pressed → using deterministic stopAndTranscribe()")
                stt?.stopAndTranscribe()
                if (stt == null) {
                    runOnUiThread { txtOutput.text = "Not yet started" }
                }
            } catch (t: Throwable) {
                runOnUiThread { txtOutput.text = "Error: ${t.message}" }
            }
        }.start()
    }

    private fun updateUi() {
        btnStart.isEnabled = !isRecording
        btnStop.isEnabled = isRecording
        btnClear.isEnabled = !isRecording
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