package dev.barrycade.voicecore

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import dev.barrycade.voicecore.vosk.VoskMode
import java.io.File

/**
 * Main entry point for the Voice Core STT application.
 *
 * Orchestrates high-level mode switching and permissions, delegating
 * specific engine logic and UI management to specialized panel handlers.
 */
class MainActivity : ComponentActivity() {

    private lateinit var btnModeWhisper: Button
    private lateinit var btnModeVosk: Button
    private lateinit var btnModeWuw: Button
    private lateinit var panelWhisper: View
    private lateinit var panelVosk: View
    private lateinit var panelWuw: View

    private lateinit var whisperPanel: WhisperPanel
    private lateinit var voskPanel: VoskPanel
    private lateinit var wuwPanel: WuwPanel

    internal lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnModeWhisper = findViewById(R.id.btnModeWhisper)
        btnModeVosk = findViewById(R.id.btnModeVosk)
        btnModeWuw = findViewById(R.id.btnModeWuw)
        panelWhisper = findViewById(R.id.panelWhisper)
        panelVosk = findViewById(R.id.panelVosk)
        panelWuw = findViewById(R.id.panelWuw)

        // Request permission launcher - simple re-tap strategy for now.
        requestPermissionLauncher = registerForActivityResult(RequestPermission()) { granted ->
            if (!granted) {
                // Global error feedback could go here if needed.
            }
        }

        // Initialize feature panels
        whisperPanel = WhisperPanel(this)
        voskPanel = VoskPanel(this)
        wuwPanel = WuwPanel(this)

        // Preload models in background
        whisperPanel.preloadModelAsync()
        voskPanel.preloadVoskModelAsync()

        // Mode toggle listeners
        btnModeWhisper.setOnClickListener { switchToMode("whisper") }
        btnModeVosk.setOnClickListener { switchToMode("vosk") }
        btnModeWuw.setOnClickListener { switchToMode("wuw") }

        // Start in Whisper mode
        switchToMode("whisper")
    }

    internal fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    internal fun getModelPath(): String {
        return File(filesDir, "model.bin").absolutePath
    }

    private fun switchToMode(mode: String) {
        // Teardown active modules before switching
        if (voskPanel.voskSessionManager?.mode != VoskMode.IDLE) {
            voskPanel.stopVoskTest()
        }
        if (whisperPanel.isRecording) {
            whisperPanel.stopRecording()
        }
        wuwPanel.stopWuwListening()
        wuwPanel.stopWuwTemplateRecording()

        val showWhisper = mode == "whisper"
        val showVosk = mode == "vosk"
        val showWuw = mode == "wuw"

        panelWhisper.visibility = if (showWhisper) View.VISIBLE else View.GONE
        panelVosk.visibility = if (showVosk) View.VISIBLE else View.GONE
        panelWuw.visibility = if (showWuw) View.VISIBLE else View.GONE

        btnModeWhisper.isEnabled = !showWhisper
        btnModeVosk.isEnabled = !showVosk
        btnModeWuw.isEnabled = !showWuw

        // Refresh template list in WUW when switching to it
        if (showWuw) {
            wuwPanel.refreshWuwTemplateList()
        }
    }
}
