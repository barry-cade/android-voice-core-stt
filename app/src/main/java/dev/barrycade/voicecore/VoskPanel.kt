package dev.barrycade.voicecore

import android.Manifest
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import dev.barrycade.voicecore.vosk.*

/**
 * Manages the Vosk engine UI and lifecycle.
 */
class VoskPanel(private val activity: MainActivity) {

    private val radioVoskMode: RadioGroup = activity.findViewById(R.id.radioVoskMode)
    private val btnVoskStop: Button = activity.findViewById(R.id.btnVoskStop)
    private val btnVoskStart: Button = activity.findViewById(R.id.btnVoskStart)
    private val btnVoskClear: Button = activity.findViewById(R.id.btnVoskClear)
    private val txtVoskWakeWord: TextView = activity.findViewById(R.id.txtVoskWakeWord)
    private val txtVoskMode: TextView = activity.findViewById(R.id.txtVoskMode)
    private val txtVoskOutput: TextView = activity.findViewById(R.id.txtVoskOutput)
    private val txtVoskFinal: TextView = activity.findViewById(R.id.txtVoskFinal)
    private val txtVoskConfigDisplay: TextView = activity.findViewById(R.id.txtVoskConfigDisplay)

    private var voskEngine: VoskEngine? = null
    var voskSessionManager: VoskSessionManager? = null
        private set
    private var voskHotWordCount: Int = 0
    private var voskReady: Boolean = false

    init {
        btnVoskStart.setOnClickListener {
            if (!voskReady) {
                txtVoskOutput.text = "Vosk not ready yet."
                return@setOnClickListener
            }
            if (activity.hasRecordAudioPermission()) {
                when (radioVoskMode.checkedRadioButtonId) {
                    R.id.radioVoskHotWord -> startVoskHotWordMode()
                    R.id.radioVoskWakeWord -> showWakeWordPlaceholder()
                    R.id.radioVoskNormal -> startVoskCommandMode()
                }
            } else {
                activity.requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        btnVoskStop.setOnClickListener {
            stopVoskTest()
        }

        btnVoskClear.setOnClickListener {
            txtVoskOutput.text = ""
            txtVoskFinal.text = activity.getString(R.string.vosk_final_hint)
            updateVoskClearButton()
        }

        updateVoskClearButton()
    }

    private fun initVoskListeners() {
        val sessionManager = voskSessionManager ?: return
        sessionManager.modeListener = { newMode ->
            activity.runOnUiThread {
                when (newMode) {
                    VoskMode.HOTWORD -> {
                        txtVoskMode.text = "Mode: HOTWORD"
                        setViewEnabled(radioVoskMode, false)
                        btnVoskStart.isEnabled = false
                        btnVoskStop.isEnabled = true
                    }
                    VoskMode.COMMAND -> {
                        txtVoskMode.text = "Mode: COMMAND"
                        setViewEnabled(radioVoskMode, false)
                        btnVoskStart.isEnabled = false
                        btnVoskStop.isEnabled = true
                    }
                    VoskMode.IDLE -> {
                        txtVoskMode.visibility = View.GONE
                        txtVoskWakeWord.visibility = View.GONE
                        setViewEnabled(radioVoskMode, true)
                        activity.findViewById<RadioButton>(R.id.radioVoskWakeWord).isEnabled = false
                        btnVoskStart.isEnabled = true
                        btnVoskStop.isEnabled = false
                        updateVoskClearButton()
                    }
                }
            }
        }
    }

    fun preloadVoskModelAsync() {
        Thread({
            try {
                val modelDir = copyAssetFolder(activity, "vosk-model-small-en-us-0.15")
                val voskConfig = buildVoskConfig(modelDir.path)
                val engine = VoskEngine(voskConfig)
                voskEngine = engine
                val sessionManager = VoskSessionManager(engine, voskConfig)
                voskSessionManager = sessionManager
                voskReady = true

                activity.runOnUiThread {
                    initVoskListeners()
                    val configJson = activity.assets.open("vosk_config.json").bufferedReader().use { it.readText() }
                    displayVoskConfig(configJson, voskConfig.modelPath)
                    // Note: Update txtOutput via activity if needed, or keep it in MainActivity
                }
            } catch (t: Throwable) {
                val detail = buildString {
                    appendLine("Vosk preload failed:")
                    appendLine("  ${t.javaClass.simpleName}: ${t.message}")
                    var cause = t.cause
                    var depth = 0
                    while (cause != null && depth < 5) {
                        appendLine("  Caused by: ${cause.javaClass.simpleName}: ${cause.message}")
                        cause = cause.cause
                        depth += 1
                    }
                }
                activity.runOnUiThread {
                    txtVoskOutput.text = detail
                }
            }
        }, "VoskPreloadThread").start()
    }

    private fun buildVoskConfig(modelPath: String): VoskConfig {
        val json = activity.assets.open("vosk_config.json").bufferedReader().use { it.readText() }
        val injected = buildString {
            append('{')
            append("\"modelPath\":\"")
            append(escapeJsonString(modelPath))
            append('"')
            append(',')
            append(json.trimStart().removePrefix("{").trimStart())
        }
        return VoskConfig.fromJson(injected)
    }

    private fun displayVoskConfig(configJson: String, modelPath: String) {
        txtVoskConfigDisplay.visibility = View.VISIBLE
        txtVoskConfigDisplay.text = buildString {
            appendLine("=== Active Vosk Config ===")
            appendLine("Config: vosk_config.json")
            appendLine("Model:  $modelPath")
            appendLine("")
            append(configJson)
        }
    }

    private fun startVoskHotWordMode() {
        val sessionManager = voskSessionManager ?: return
        if (sessionManager.mode != VoskMode.IDLE) return

        voskHotWordCount = 0
        txtVoskWakeWord.visibility = View.GONE
        txtVoskMode.visibility = View.VISIBLE
        txtVoskOutput.visibility = View.VISIBLE
        txtVoskOutput.text = "Listening for hot word..."

        sessionManager.partialListener = VoskPartialListener { text ->
            txtVoskOutput.text = "Partial: $text"
        }

        sessionManager.finalListener = VoskFinalListener { text ->
            txtVoskFinal.text = "Final: $text"
            txtVoskOutput.text = "[Utterance End]"
            if (voskSessionManager?.mode == VoskMode.COMMAND) {
                stopVoskTest()
            }
            txtVoskWakeWord.visibility = View.GONE
            updateVoskClearButton()
        }

        sessionManager.hotWordListener = VoskHotWordListener {
            voskHotWordCount += 1
            txtVoskWakeWord.visibility = View.VISIBLE
            txtVoskMode.text = "Mode: COMMAND (hot #$voskHotWordCount)"
            txtVoskWakeWord.text = "[HOT WORD DETECTED] Switching to command mode..."
        }

        sessionManager.errorListener = { message ->
            activity.runOnUiThread {
                txtVoskOutput.text = "Error: $message"
                // Logic handled by modeListener if it transitions to IDLE
            }
        }

        try {
            sessionManager.startHotWordMode()
        } catch (e: IllegalStateException) {
            txtVoskOutput.text = "Error: ${e.message}"
        }
    }

    private fun startVoskCommandMode() {
        val sessionManager = voskSessionManager ?: return
        if (sessionManager.mode != VoskMode.IDLE) return

        txtVoskWakeWord.visibility = View.GONE
        txtVoskMode.visibility = View.VISIBLE
        txtVoskOutput.visibility = View.VISIBLE
        txtVoskOutput.text = "Listening for speech..."

        sessionManager.partialListener = VoskPartialListener { text ->
            txtVoskOutput.text = "Partial: $text"
        }

        sessionManager.finalListener = VoskFinalListener { text ->
            txtVoskFinal.text = "Final: $text"
            txtVoskOutput.text = "[Utterance End]"
            stopVoskTest()
        }

        sessionManager.errorListener = { message ->
            activity.runOnUiThread {
                txtVoskOutput.text = "Error: $message"
            }
        }

        try {
            sessionManager.startCommandMode()
        } catch (e: IllegalStateException) {
            txtVoskOutput.text = "Error: ${e.message}"
        }
    }

    private fun showWakeWordPlaceholder() {
        txtVoskOutput.text = "Wake Word mode is not yet implemented.\nUse Hot Word for wake-word behaviour in the meantime."
    }

    fun stopVoskTest() {
        voskSessionManager?.stop()
        txtVoskOutput.text = "Vosk stopped."
    }

    private fun updateVoskClearButton() {
        btnVoskClear.isEnabled = isVoskContentAvailable()
    }

    private fun isVoskContentAvailable(): Boolean {
        val finalResult = txtVoskFinal.text.toString()
        return finalResult.isNotEmpty() && finalResult != activity.getString(R.string.vosk_final_hint)
    }
}
