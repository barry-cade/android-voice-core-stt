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
import dev.barrycade.voicecore.vosk.VoskConfig
import dev.barrycade.voicecore.vosk.VoskEngine
import dev.barrycade.voicecore.vosk.VoskFinalListener
import dev.barrycade.voicecore.vosk.VoskPartialListener
import dev.barrycade.voicecore.vosk.VoskWakeWordListener
import dev.barrycade.voicecore.vosk.VoskMode
import dev.barrycade.voicecore.vosk.VoskSessionManager
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnClear: Button
    private lateinit var radioVoskMode: RadioGroup
    private lateinit var btnVoskStop: Button
    private lateinit var btnVoskStart: Button
    private lateinit var btnVoskClear: Button
    private lateinit var btnModeWhisper: Button
    private lateinit var btnModeVosk: Button
    private lateinit var panelWhisper: View
    private lateinit var panelVosk: View
    private lateinit var txtOutput: TextView
    private lateinit var txtVoskOutput: TextView
    private lateinit var txtVoskFinal: TextView
    private lateinit var txtVoskMode: TextView
    private lateinit var txtVoskStatus: TextView
    private lateinit var txtWhisperStatus: TextView
    private lateinit var txtVoskWakeWord: TextView
    private lateinit var txtVoskConfigDisplay: TextView
    private lateinit var txtDiagnostics: TextView
    private lateinit var txtConfigDisplay: TextView
    private lateinit var txtErrorBanner: TextView
    private lateinit var radioGroupStrategy: RadioGroup

    private var selectedStopType: String = "MANUAL"

    // Track strategy from active config for UI visibility.
    private var activeStopType: String = "MANUAL"

    // Last DEFAULTS_USED feedback from the STT module, shown in Active Config.
    private var configDefaultsMessage: String? = null

    // Guard: consecutive blank-audio hints.
    private var blankAudioCount: Int = 0
    private val blankAudioThreshold: Int = 3

    private var isRecording = false
    private var voskEngine: VoskEngine? = null
    private var voskSessionManager: VoskSessionManager? = null
    private var voskWakeWordCount: Int = 0

    /**
     * Prevents the radio group change listener from re-triggering
     * programmatic selection changes (e.g. when re-enabling after
     * a session ends).
     */
    private var voskReady: Boolean = false

    private fun postToUi(action: () -> Unit) {
        runOnUiThread(action)
    }

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

    /** STT engine instance, created once and reused. */
    private val stt: SpeechToText = SpeechToText()

    companion object {
        private const val BLANK_AUDIO_MARKER = "[BLANK_AUDIO]"
        private const val CONFIG_MANUAL_MANUAL = "stt_config_manual_manual.json"
        private const val CONFIG_MANUAL_AUTO = "stt_config_manual_auto.json"

        private fun configAssetForStopType(stopType: String): String {
            return when (stopType) {
                "AUTO_SILENCE" -> CONFIG_MANUAL_AUTO
                else -> CONFIG_MANUAL_MANUAL
            }
        }

        /**
         * Recursively copy an asset folder tree to internal storage.
         */
        fun copyAssetFolder(context: Context, assetFolder: String): File {
            val outDir = File(context.filesDir, assetFolder)
            copyAssetTree(context, assetFolder, outDir)
            return outDir
        }

        private fun copyAssetTree(context: Context, assetPath: String, outDir: File) {
            if (!outDir.exists()) outDir.mkdirs()

            val assetManager = context.assets
            val entries = assetManager.list(assetPath) ?: return

            for (entry in entries) {
                val childAssetPath = "$assetPath/$entry"
                val childOutFile = File(outDir, entry)
                // Try opening as a file — if it throws, it's a directory.
                try {
                    assetManager.open(childAssetPath).use { inStream ->
                        FileOutputStream(childOutFile).use { outStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                } catch (_: Exception) {
                    copyAssetTree(context, childAssetPath, childOutFile)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnClear = findViewById(R.id.btnClear)
        radioVoskMode = findViewById(R.id.radioVoskMode)
        btnVoskStop = findViewById(R.id.btnVoskStop)
        btnVoskClear = findViewById(R.id.btnVoskClear)
        btnVoskStart = findViewById(R.id.btnVoskStart)
        btnModeWhisper = findViewById(R.id.btnModeWhisper)
        btnModeVosk = findViewById(R.id.btnModeVosk)
        panelWhisper = findViewById(R.id.panelWhisper)
        panelVosk = findViewById(R.id.panelVosk)
        txtOutput = findViewById(R.id.txtOutput)
        txtVoskOutput = findViewById(R.id.txtVoskOutput)
        txtVoskFinal = findViewById(R.id.txtVoskFinal)
        txtVoskMode = findViewById(R.id.txtVoskMode)
        txtVoskStatus = findViewById(R.id.txtVoskStatus)
        txtWhisperStatus = findViewById(R.id.txtWhisperStatus)
        txtVoskWakeWord = findViewById(R.id.txtVoskWakeWord)
        txtVoskConfigDisplay = findViewById(R.id.txtVoskConfigDisplay)
        txtDiagnostics = findViewById(R.id.txtDiagnostics)
        txtConfigDisplay = findViewById(R.id.txtConfigDisplay)
        txtErrorBanner = findViewById(R.id.txtErrorBanner)
        radioGroupStrategy = findViewById(R.id.radioGroupStrategy)

        // Register the JSON message listener before loadModel.
        // Required for auto-silence results — when the UtteranceAccumulator
        // detects silence and triggers inference internally, the result is
        // delivered through this listener (not via transcribe() return value).
        stt.setOnMessageListener(createMessageListener())

        // ── Preload STT model at startup ─────────────────────────────────────
        preloadModelAsync()

        // ── Preload Vosk model at startup ────────────────────────────────────
        preloadVoskModelAsync()

        // ── Mode toggle ──────────────────────────────────────────────────────
        // Initial state: Whisper visible, Whisper button disabled.
        btnModeWhisper.setOnClickListener { switchToMode("whisper") }
        btnModeVosk.setOnClickListener { switchToMode("vosk") }
        switchToMode("whisper")

        radioGroupStrategy.setOnCheckedChangeListener { _, checkedId ->
            val newStopType: String = when (checkedId) {
                R.id.radioManualAuto -> "AUTO_SILENCE"
                else -> "MANUAL"
            }
            selectedStopType = newStopType
            displayConfigForStopType(newStopType)
            // Reconfigure the STT pipeline with the new stop strategy.
            // Only when not recording — active sessions complete with
            // their original config.
            if (!isRecording) {
                val configJson = buildConfigJsonForStopType(newStopType)
                stt.configure(configJson)
            }
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
            updateUi()
        }

        btnVoskStart.setOnClickListener {
            if (hasRecordAudioPermission()) {
                when (radioVoskMode.checkedRadioButtonId) {
                    R.id.radioVoskWakeWord -> startVoskWakeWordMode()
                    R.id.radioVoskCmdMode -> startVoskCommandMode()
                }
            } else {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        radioVoskMode.setOnCheckedChangeListener { _, _ ->
            // State change only, no auto-start.
        }

        btnVoskStop.setOnClickListener {
            stopVoskTest()
        }

        btnVoskClear.setOnClickListener {
            txtVoskOutput.text = ""
            txtVoskFinal.text = getString(R.string.vosk_final_hint)
            updateVoskClearButton()
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

        val assetName = configAssetForStopType(stopType)
        val configJson = try {
            assets.open(assetName).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            "Error loading $assetName"
        }

        txtConfigDisplay.visibility = View.VISIBLE
        txtConfigDisplay.text = buildString {
            appendLine("=== Active Config ===")
            appendLine("Config: $assetName")
            appendLine("Model:  ${getModelPath()}")
            appendLine("")
            append(configJson)
            // Append DEFAULTS_USED feedback if available.
            val defaultsMsg = configDefaultsMessage
            if (defaultsMsg != null) {
                appendLine("")
                appendLine("--- Config Defaults Applied ---")
                try {
                    val obj = JSONObject(defaultsMsg)
                    val fields = obj.optJSONArray("fields")
                    val defaults = obj.optJSONObject("defaults")
                    if (fields != null && defaults != null) {
                        for (i in 0 until fields.length()) {
                            val field = fields.optString(i, "")
                            val value = defaults.opt(field)
                            appendLine("  $field = $value (default)")
                        }
                    }
                } catch (_: Exception) {
                    appendLine("  (parse error)")
                }
            }
        }
    }

    /**
     * Build the JSON message listener that processes STT callbacks.
     */
    private fun createMessageListener(): (String) -> Unit {
        return { json ->
            postToUi {
                try {
                    val obj = JSONObject(json)
                    val type = obj.optString("type", "")
                    when (type) {
                        "result" -> {
                            onResultReceived(obj)
                            // If auto-silence finished or session timeout fired,
                            // reset the UI state (Stop disabled, Start enabled).
                            val code = obj.optString("code", "")
                            if (activeStopType == "AUTO_SILENCE" || code == "SESSION_TIMEOUT") {
                                isRecording = false
                                updateUi()
                            }
                        }
                        "config" -> {
                            val code = obj.optString("code", "")
                            when (code) {
                                "DEFAULTS_USED" -> {
                                    configDefaultsMessage = json
                                    displayConfigForStopType(activeStopType)
                                }
                            }
                        }
                        "error" -> onErrorReceived(obj)
                    }
                } catch (_: Exception) {
                    txtOutput.text = "Malformed message: $json"
                }
            }
        }
    }

    /**
     * Handle a "result" message from the STT module.
     */
    private fun onResultReceived(obj: JSONObject) {
        val text = obj.optString("text", "")
        val code = obj.optString("code", "")
        val timing = obj.optJSONObject("timing")
        val timingInfo = if (timing != null) {
            val vadActiveMs = timing.optLong("vadActiveMs", 0)
            val utteranceMs = timing.optLong("utteranceMs", 0)
            val inferenceMs = timing.optLong("inferenceMs", 0)
            buildString {
                appendLine("Timing (ms):")
                if (vadActiveMs > 0 || utteranceMs > 0) {
                    appendLine("  vad    = $vadActiveMs")
                    appendLine("  speech = $utteranceMs")
                }
                appendLine("  infer  = $inferenceMs")
                if (utteranceMs > 0 && text.trim().isNotEmpty()) {
                    val speechSecs = utteranceMs / 1000.0
                    val textLen = text.trim().length
                    val ratio = "%.1f".format(textLen / speechSecs)
                    appendLine("  chars/s= $ratio ($textLen chars in ${"%.1f".format(speechSecs)}s)")
                }
            }
        } else ""

        txtOutput.text = "[$code] $text"
        txtDiagnostics.text = timingInfo
        txtDiagnostics.visibility = View.VISIBLE

        updateUi()

        if (text == BLANK_AUDIO_MARKER || text == "") {
            blankAudioCount += 1
            if (blankAudioCount >= blankAudioThreshold) {
                txtOutput.text = "No speech detected. Tap Stop to end the session."
                AppLogger.log(AppLogCode.BLANK_AUDIO_THRESHOLD, blankAudioCount)
            }
        } else {
            blankAudioCount = 0
        }
    }

    /**
     * Handle an "error" message from the STT module.
     */
    private fun onErrorReceived(obj: JSONObject) {
        val action = AppErrorRouter.route(obj)
        if (action.logCode != null) {
            AppLogger.log(action.logCode, *action.logArgs)
        }
        txtErrorBanner.visibility = if (action.showBanner) View.VISIBLE else View.GONE
        action.bannerText?.let { txtErrorBanner.text = it }
        action.outputText?.let { txtOutput.text = it }
        txtDiagnostics.visibility = View.GONE
        txtDiagnostics.text = ""
        updateUi()
    }

    /**
     * Build a full config JSON string for the given [stopType] by loading
     * the asset template and injecting the model path.
     */
    private fun buildConfigJsonForStopType(stopType: String): String {
        val assetName = configAssetForStopType(stopType)
        val template = try {
            assets.open(assetName).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to load config asset: $assetName", e)
        }

        val modelPath = getModelPath()
        val sb = StringBuilder()
        sb.append("{\"modelPath\":\"")
        sb.append(escapeJsonString(modelPath))
        sb.append("\",")
        sb.append(template.trimStart().removePrefix("{").trimStart())
        return sb.toString()
    }

    /**
     * Preload the STT model in the background at app startup.
     * Ensures the model file is copied from assets and the
     * engine is loaded before the user presses Start.
     *
     * Uses [loadModelOnly] to load the model without starting
     * audio capture. The Start button calls [init] to begin
     * a capture session.
     */
    private fun preloadModelAsync() {
        val modelFile = File(filesDir, "model.bin")
        if (!modelFile.exists()) {
            postToUi { txtOutput.text = "Copying speech model… (one-time setup)" }
        }

        Thread({
            try {
                // Copy model from assets if not already present (first launch).
                if (!modelFile.exists()) {
                    modelFile.parentFile?.mkdirs()
                    assets.open("models/ggml-tiny.en.bin").use { input ->
                        FileOutputStream(modelFile).use { output -> input.copyTo(output) }
                    }
                }

                val configJson = buildConfigJsonForStopType(selectedStopType)
                val result = stt.loadModelOnly(configJson)
                postToUi {
                    if (result.contains("\"type\":\"error\"")) {
                        val message = JSONObject(result).optString("message", "Unknown error")
                        AppLogger.log(AppLogCode.PRELOAD_FAILED, "STT error: $message")
                        txtOutput.text = "Model preload error: $message"
                    } else {
                        txtOutput.text = "Model loaded. Tap Start to record."
                    }
                }
            } catch (t: Throwable) {
                AppLogger.log(AppLogCode.PRELOAD_FAILED, t.message ?: "Unknown error")
                postToUi {
                    txtOutput.text = "Model preload failed: ${t.message}"
                }
            }
        }, "ModelPreloadThread").start()
    }

    /**
     * Load the Vosk config template from assets and inject the model path.
     *
     * Follows the same pattern as [buildConfigJsonForStopType] for Whisper:
     * loads a JSON asset template, parses it, and replaces the model path
     * with the runtime location of the model directory.
     */
    private fun buildVoskConfig(modelPath: String): VoskConfig {
        val json = assets.open("vosk_config.json").bufferedReader().use { it.readText() }
        val template = VoskConfig.fromJson(json)
        return template.copy(modelPath = modelPath)
    }

    /**
     * Display the active Vosk config in the Vosk panel.
     *
     * Follows the same pattern as [displayConfigForStopType] for Whisper:
     * shows the config asset contents with the injected model path.
     */
    private fun displayVoskConfig(configJson: String, modelPath: String) {
        txtVoskConfigDisplay.visibility = View.VISIBLE
        txtVoskConfigDisplay.text = buildString {
            appendLine("=== Active Vosk Config ===")
            appendLine("Config: vosk_config.json")
            appendLine("Model:  $modelPath")
            appendLine("")
            append(VoskConfig.fromJson(configJson).toString())
        }
    }

    /**
     * Copy Vosk model from assets to internal storage and initialise VoskEngine.
     */
    private fun preloadVoskModelAsync() {
        Thread({
            try {
                val modelDir = copyAssetFolder(this, "vosk-model-small-en-us-0.15")
                val voskConfig = buildVoskConfig(modelDir.path)
                val engine = VoskEngine(voskConfig)
                voskEngine = engine
                voskSessionManager = VoskSessionManager(engine, voskConfig)
                voskReady = true

                // Display active Vosk config on the UI.
                val configJson = assets.open("vosk_config.json").bufferedReader().use { it.readText() }
                postToUi {
                    displayVoskConfig(configJson, voskConfig.modelPath)
                    txtOutput.text = "Vosk model loaded. STT and Vosk ready."
                }
            } catch (t: Throwable) {
                postToUi {
                    txtOutput.text = "Vosk preload failed: ${t.message}"
                }
            }
        }, "VoskPreloadThread").start()
    }

    /**
     * Start a Vosk wake-word session using VoskSessionManager.
     *
     * The manager continuously listens for the wake word ("zip").
     * On detection it auto-switches to command mode for one utterance,
     * then returns to wake-word mode.
     */
    private fun startVoskWakeWordMode() {
        val sessionManager = voskSessionManager
        if (sessionManager == null) {
            txtVoskOutput.text = "Vosk not initialised yet (preload may still be running)."
            return
        }

        if (sessionManager.mode != VoskMode.IDLE) {
            txtVoskOutput.text = "Vosk session already active."
            return
        }

        voskWakeWordCount = 0
        txtVoskWakeWord.visibility = View.GONE
        txtVoskMode.visibility = View.VISIBLE
        txtVoskOutput.visibility = View.VISIBLE
        txtVoskOutput.text = "Listening for wake word..."

        val partialCallback = VoskPartialListener { text ->
            txtVoskOutput.text = "Partial: $text"
        }

        val finalCallback = VoskFinalListener { text ->
            txtVoskFinal.text = "Final: $text"
            txtVoskOutput.text = "[Utterance End]"
            // In COMMAND mode, we want to stop definitively after one utterance.
            if (voskSessionManager?.mode == VoskMode.COMMAND) {
                stopVoskTest()
            }
            // In wake-word mode, final results are logged but loop continues.
            // Reset indicator after command utterance.
            txtVoskWakeWord.visibility = View.GONE
            updateVoskClearButton()
        }

        val wakeWordCallback = VoskWakeWordListener {
            voskWakeWordCount += 1
            txtVoskWakeWord.visibility = View.VISIBLE
            txtVoskMode.text = "Mode: COMMAND (wake #$voskWakeWordCount)"
            txtVoskWakeWord.text = "[WAKE WORD DETECTED] Switching to command mode..."
        }

        val modeChangeCallback: (VoskMode) -> Unit = { newMode ->
            when (newMode) {
                VoskMode.WAKEWORD -> {
                    txtVoskMode.text = "Mode: WAKEWORD (say \"zip\")"
                    txtVoskStatus.text = getString(R.string.vosk_status_active)
                    txtVoskStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                    radioVoskMode.isEnabled = false
                    btnVoskStart.isEnabled = false
                    btnVoskStop.isEnabled = true
                }
                VoskMode.COMMAND -> {
                    txtVoskMode.text = "Mode: COMMAND"
                    txtVoskStatus.text = getString(R.string.vosk_status_active)
                    txtVoskStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                    radioVoskMode.isEnabled = false
                    btnVoskStart.isEnabled = false
                    btnVoskStop.isEnabled = true
                }
                VoskMode.IDLE -> {
                    txtVoskMode.visibility = View.GONE
                    txtVoskWakeWord.visibility = View.GONE
                    txtVoskStatus.text = getString(R.string.vosk_status_idle)
                    txtVoskStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
                    radioVoskMode.isEnabled = true
                    btnVoskStart.isEnabled = true
                    btnVoskStop.isEnabled = false
                    updateVoskClearButton()
                }
            }
        }

        sessionManager.partialListener = partialCallback
        sessionManager.finalListener = finalCallback
        sessionManager.wakeWordListener = wakeWordCallback
        sessionManager.modeListener = modeChangeCallback

        sessionManager.errorListener = { message ->
            txtVoskOutput.text = "Error: $message"
            radioVoskMode.isEnabled = true
            btnVoskStart.isEnabled = true
            btnVoskStop.isEnabled = false
            txtVoskMode.visibility = View.GONE
            txtVoskWakeWord.visibility = View.GONE
        }

        try {
            sessionManager.startWakeWordMode()
        } catch (e: IllegalStateException) {
            txtVoskOutput.text = "Error: ${e.message}"
            radioVoskMode.isEnabled = true
            btnVoskStart.isEnabled = true
            btnVoskStop.isEnabled = false
        }
    }

    /**
     * Start a direct Vosk command-mode session (no wake word).
     * Useful for testing the recogniser without wake-word logic.
     */
    private fun startVoskCommandMode() {
        val sessionManager = voskSessionManager
        if (sessionManager == null) {
            txtVoskOutput.text = "Vosk not initialised yet (preload may still be running)."
            return
        }

        if (sessionManager.mode != VoskMode.IDLE) {
            txtVoskOutput.text = "Vosk session already active."
            return
        }

        txtVoskWakeWord.visibility = View.GONE
        txtVoskMode.visibility = View.VISIBLE
        txtVoskOutput.visibility = View.VISIBLE
        txtVoskOutput.text = "Listening for speech..."

        val partialCallback = VoskPartialListener { text ->
            txtVoskOutput.text = "Partial: $text"
        }

        val finalCallback = VoskFinalListener { text ->
            txtVoskFinal.text = "Final: $text"
            txtVoskOutput.text = "[Utterance End]"
            stopVoskTest()
        }

        sessionManager.partialListener = partialCallback
        sessionManager.finalListener = finalCallback

        sessionManager.errorListener = { message ->
            txtVoskOutput.text = "Error: $message"
            radioVoskMode.isEnabled = true
            btnVoskStart.isEnabled = true
            btnVoskStop.isEnabled = false
            txtVoskMode.visibility = View.GONE
        }

        try {
            sessionManager.startCommandMode()
        } catch (e: IllegalStateException) {
            txtVoskOutput.text = "Error: ${e.message}"
            radioVoskMode.isEnabled = true
            btnVoskStart.isEnabled = true
            btnVoskStop.isEnabled = false
        }
    }

    private fun stopVoskTest() {
        voskSessionManager?.stop()
        // UI is handled by modeChangeCallback via IDLE state
        txtVoskOutput.text = "Vosk stopped."
    }

    private fun escapeJsonString(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startRecording() {
        try {
            // The model was already loaded at startup via preloadModelAsync().
            // Calling init() after loadModelOnly() starts a new capture session
            // without re-loading the model.
            val result = stt.init(buildConfigJsonForStopType(selectedStopType))
            if (result.contains("\"type\":\"error\"")) {
                val message = JSONObject(result).optString("message", "Unknown error")
                AppLogger.log(AppLogCode.SESSION_ERROR, "Session error: $message")
                postToUi { txtOutput.text = "Session error: $message" }
                return
            }

            blankAudioCount = 0
            isRecording = true
            txtOutput.text = "Recording..."
            updateUi()

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
        btnStop.isEnabled = false

        val thread = Thread({
            try {
                AppLogger.log(AppLogCode.STOP_USING_STOP_AND_TRANSCRIBE)
                val result = stt.transcribe()
                postToUi {
                    try {
                        val obj = JSONObject(result)
                        val type = obj.optString("type", "")
                        when (type) {
                            "result" -> {
                                onResultReceived(obj)
                            }
                            "error" -> {
                                onErrorReceived(obj)
                            }
                            else -> {
                                txtOutput.text = result
                            }
                        }
                    } catch (_: Exception) {
                        txtOutput.text = result
                    }
                    isRecording = false
                    updateUi()
                }
            } catch (t: Throwable) {
                AppLogger.log(AppLogCode.STOP_FAILED, t.message)
                postToUi { txtOutput.text = "Error: " + t.message }
            }
        }, "TranscribeThread")
        thread.start()
    }

    private fun updateUi() {
        if (isRecording) {
            btnStart.isEnabled = false
            btnStop.isEnabled = true
            btnClear.isEnabled = false
            radioGroupStrategy.isEnabled = false
            for (i in 0 until radioGroupStrategy.childCount) {
                radioGroupStrategy.getChildAt(i).isEnabled = false
            }
            txtWhisperStatus.text = getString(R.string.vosk_status_active)
            txtWhisperStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        } else {
            btnStart.isEnabled = true
            btnStop.isEnabled = false
            btnClear.isEnabled = isWhisperContentAvailable()
            radioGroupStrategy.isEnabled = true
            for (i in 0 until radioGroupStrategy.childCount) {
                radioGroupStrategy.getChildAt(i).isEnabled = true
            }
            txtWhisperStatus.text = getString(R.string.vosk_status_idle)
            txtWhisperStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }

        btnStart.visibility = View.VISIBLE
        btnStop.visibility = View.VISIBLE
    }

    private fun isWhisperContentAvailable(): Boolean {
        val text = txtOutput.text.toString()
        return text.isNotEmpty() && text != "Say something..." && text != "Microphone permission is required"
    }

    private fun updateVoskClearButton() {
        btnVoskClear.isEnabled = isVoskContentAvailable()
    }

    private fun isVoskContentAvailable(): Boolean {
        val finalResult = txtVoskFinal.text.toString()
        return finalResult.isNotEmpty() && finalResult != getString(R.string.vosk_final_hint)
    }

    private fun switchToMode(mode: String) {
        val isWhisper = mode == "whisper"

        if (isWhisper) {
            // Teardown Vosk when switching to Whisper
            if (voskSessionManager?.mode != VoskMode.IDLE) {
                stopVoskTest()
            }
        } else {
            // Teardown Whisper when switching to Vosk
            if (isRecording) {
                stopRecording()
            }
        }

        panelWhisper.visibility = if (isWhisper) View.VISIBLE else View.GONE
        panelVosk.visibility = if (isWhisper) View.GONE else View.VISIBLE
        btnModeWhisper.isEnabled = !isWhisper
        btnModeVosk.isEnabled = isWhisper
    }

    private fun getModelPath(): String {
        return File(filesDir, "model.bin").absolutePath
    }
}
