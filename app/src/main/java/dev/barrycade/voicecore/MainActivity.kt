package dev.barrycade.voicecore

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import android.app.AlertDialog
import androidx.core.content.ContextCompat
import dev.barrycade.voicecore.stt.SpeechToText
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnClear: Button
    private lateinit var txtOutput: TextView
    private lateinit var txtErrorBanner: TextView

    private var stt: SpeechToText? = null
    private var isRecording = false
    private var sttAvailable = true
    private val debugLogging = true

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

        btnStart.setOnClickListener {
            if (!sttAvailable) return@setOnClickListener
            logInfo("STT_FLOW", "startRecording() called, sttAvailable=$sttAvailable")
            if (hasRecordAudioPermission()) startRecording()
            else requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        btnStop.setOnClickListener {
            if (!sttAvailable) return@setOnClickListener
            logInfo("STT_FLOW", "stopAndTranscribe() called, sttAvailable=$sttAvailable")
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
        if (!sttAvailable) return

        val modelPath = getModelPath()
        val runtimeConfig = try {
            AppSttConfigLoader.loadFromAssets(this)
        } catch (e: Exception) {
            handleConfigError(e)
            return
        }

        try {
            logInfo("STT_INIT", "Constructing STT with modelPath=$modelPath")
            stt = SpeechToText.create(
                energyThreshold = runtimeConfig.energyThreshold,
                silencePaddingMs = runtimeConfig.silencePaddingMs,
                preRollMs = runtimeConfig.preRollMs,
                maxUtteranceLengthMs = runtimeConfig.maxUtteranceLengthMs,
                stableChunkSizeMs = runtimeConfig.stableChunkSizeMs,
                motionModeEnergyThreshold = runtimeConfig.motionMode.energyThreshold,
                motionModeSilencePaddingMs = runtimeConfig.motionMode.silencePaddingMs,
                modelPath = modelPath
            ).also {
                it.setOnResultListener { result ->
                    runOnUiThread { txtOutput.text = result }
                }
                it.setOnErrorListener { t ->
                    runOnUiThread { txtOutput.text = "Error: ${t.message}" }
                }
                it.start()
            }

            sttAvailable = true
            logInfo("STT_STATE", "sttAvailable=$sttAvailable")
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
        sttAvailable = false
        Log.i("STT_STATE", "sttAvailable=$sttAvailable")
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
        if (!sttAvailable) return
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
        if (!sttAvailable) {
            btnStart.isEnabled = false
            btnStop.isEnabled = false
            btnClear.isEnabled = false
            return
        }
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