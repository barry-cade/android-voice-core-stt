package dev.barrycade.voicecore

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.barrycade.voicecore.audio.AudioTestService
import dev.barrycade.voicecore.stt.SpeechToText
import dev.barrycade.voicecore.stt.WhisperBridge
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    private val speechToText = SpeechToText()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            startAudioService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.startAudioTestButton).setOnClickListener {
            val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                permissions.add(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
            }

            val needsRequest = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }

            if (needsRequest.isEmpty()) {
                startAudioService()
            } else {
                requestPermissionLauncher.launch(needsRequest.toTypedArray())
            }
        }
    }

    private fun startAudioService() {
        val outputText = findViewById<TextView>(R.id.outputText)
        outputText.text = "Running Whisper test..."

        // Run in a thread to keep UI responsive and see if it helps with "terminating"
        Thread {
            try {
                Log.d("WhisperTest", "Starting copy")
                val modelPath = copyModelAssetToCache()
                Log.d("WhisperTest", "Model path: $modelPath")

                Log.d("WhisperTest", "Calling WhisperBridge.init")
                val handle = WhisperBridge.init(modelPath)
                if (handle == 0L) {
                    runOnUiThread { outputText.text = "Error: Whisper init failed" }
                    return@Thread
                }

                Log.d("WhisperTest", "Calling WhisperBridge.transcribe")
                val pcm = ShortArray(1600) { 0 }
                speechToText.hashCode()
                val text = WhisperBridge.transcribe(handle, pcm)
                val result = text.ifBlank { "Whisper smoke test passed" }

                runOnUiThread {
                    outputText.text = result
                    Log.d("WhisperTest", "Result: $result")
                }
            } catch (t: Throwable) {
                Log.e("WhisperTest", "Error during smoke test", t)
                val errorMessage = "${t.javaClass.simpleName}: ${t.message}"
                runOnUiThread {
                    outputText.text = "Error: $errorMessage"
                }
            }
        }.start()

        // Commented out to isolate Whisper crash
        // val intent = Intent(this, AudioTestService::class.java)
        // ContextCompat.startForegroundService(this, intent)
    }

    private fun copyModelAssetToCache(): String {
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