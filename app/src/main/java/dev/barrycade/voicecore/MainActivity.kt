package dev.barrycade.voicecore

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.SeekBar
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.ActivityResultLauncher
import android.app.AlertDialog
import androidx.core.content.ContextCompat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioTrack
import android.media.AudioAttributes
import dev.barrycade.voicecore.stt.SpeechToText
import dev.barrycade.voicecore.vosk.VoskConfig
import dev.barrycade.voicecore.vosk.VoskEngine
import dev.barrycade.voicecore.vosk.VoskFinalListener
import dev.barrycade.voicecore.vosk.VoskPartialListener
import dev.barrycade.voicecore.vosk.VoskHotWordListener
import dev.barrycade.voicecore.vosk.VoskMode
import dev.barrycade.voicecore.vosk.VoskSessionManager
import dev.barrycade.voicecore.wuw.WakeWordSessionManager
import dev.barrycade.voicecore.wuw.WakeWordListener
import dev.barrycade.voicecore.wuw.TemplateStore
import dev.barrycade.voicecore.wuw.MfccExtractor
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
    private var voskHotWordCount: Int = 0

    /**
     * Prevents the radio group change listener from re-triggering
     * programmatic selection changes (e.g. when re-enabling after
     * a session ends).
     */
    private var voskReady: Boolean = false
    private lateinit var btnModeWuw: Button
    private lateinit var panelWuw: View
    private lateinit var txtWuwStatus: TextView
    private lateinit var txtWuwOutput: TextView
    private lateinit var txtWuwDetection: TextView
    private lateinit var txtWuwThreshold: TextView
    private lateinit var btnWuwRecord: Button
    private lateinit var btnWuwPlay: Button
    private lateinit var btnWuwMatch: Button
    private lateinit var btnWuwDelete: Button
    private lateinit var seekWuwThreshold: SeekBar
    private lateinit var radioWuwTemplates: RadioGroup
    private var wuwSessionManager: WakeWordSessionManager? = null
    private var wuwTemplateStore: TemplateStore? = null
    private var isRecordingWuwTemplate: Boolean = false
    private var isPlayingWuwTemplate: Boolean = false
    private var selectedWuwTemplate: String? = null
    /** Cached raw PCM for the selected template (for playback). */
    private var selectedWuwPcm: ShortArray? = null

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
        txtVoskWakeWord = findViewById(R.id.txtVoskWakeWord)
        txtVoskConfigDisplay = findViewById(R.id.txtVoskConfigDisplay)
        txtDiagnostics = findViewById(R.id.txtDiagnostics)
        txtConfigDisplay = findViewById(R.id.txtConfigDisplay)
        txtErrorBanner = findViewById(R.id.txtErrorBanner)
        radioGroupStrategy = findViewById(R.id.radioGroupStrategy)
        btnModeWuw = findViewById(R.id.btnModeWuw)
        panelWuw = findViewById(R.id.panelWuw)
        txtWuwStatus = findViewById(R.id.txtWuwStatus)
        txtWuwOutput = findViewById(R.id.txtWuwOutput)
        txtWuwDetection = findViewById(R.id.txtWuwDetection)
        txtWuwThreshold = findViewById(R.id.txtWuwThreshold)
        btnWuwRecord = findViewById(R.id.btnWuwRecord)
        btnWuwPlay = findViewById(R.id.btnWuwPlay)
        btnWuwMatch = findViewById(R.id.btnWuwMatch)
        btnWuwDelete = findViewById(R.id.btnWuwDelete)
        seekWuwThreshold = findViewById(R.id.seekWuwThreshold)
        radioWuwTemplates = findViewById(R.id.radioWuwTemplates)

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
        btnModeWuw.setOnClickListener { switchToMode("wuw") }
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
                    R.id.radioVoskHotWord -> startVoskHotWordMode()
                    R.id.radioVoskWakeWord -> showWakeWordPlaceholder()
                    R.id.radioVoskNormal -> startVoskCommandMode()
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

        // ── WUW button handlers ──────────────────────────────────────────────

        btnWuwRecord.setOnClickListener {
            if (hasRecordAudioPermission()) {
                startWuwTemplateRecording()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        btnWuwPlay.setOnClickListener {
            playSelectedWuwTemplate()
        }

        btnWuwMatch.setOnClickListener {
            if (hasRecordAudioPermission()) {
                startWuwListening()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        btnWuwDelete.setOnClickListener {
            deleteSelectedWuwTemplate()
        }

        radioWuwTemplates.setOnCheckedChangeListener { _, checkedId ->
            val radioButton = findViewById<android.widget.RadioButton>(checkedId)
            if (radioButton != null) {
                selectedWuwTemplate = radioButton.text.toString()
                selectedWuwPcm = null
                btnWuwPlay.isEnabled = true
                btnWuwMatch.isEnabled = true
                btnWuwDelete.isEnabled = true
            } else {
                selectedWuwTemplate = null
                selectedWuwPcm = null
                btnWuwPlay.isEnabled = false
                btnWuwMatch.isEnabled = false
                btnWuwDelete.isEnabled = false
            }
        }

        seekWuwThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val threshold = progress / 100f
                txtWuwThreshold.text = String.format("%.2f", threshold)
                wuwSessionManager?.setThreshold(threshold)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // Initialise the WUW template store.
        wuwTemplateStore = TemplateStore(this)
        refreshWuwTemplateList()

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
     * loads a JSON asset template, injects the runtime model path at the front
     * of the JSON, then parses into a [VoskConfig].
     *
     * Injecting the path into the raw JSON string before parsing ensures
     * [VoskConfig]'s init validation sees a non-blank modelPath.
     */
    private fun buildVoskConfig(modelPath: String): VoskConfig {
        val json = assets.open("vosk_config.json").bufferedReader().use { it.readText() }
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
            append(configJson)
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
                // Show the full exception chain in the Vosk output for debugging.
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
                postToUi {
                    txtVoskOutput.text = detail
                    txtOutput.text = "Vosk preload failed. See Vosk panel for details."
                }
            }
        }, "VoskPreloadThread").start()
    }

    /**
     * Start a Vosk hot-word session.
     *
     * Listens continuously for the hot word. On detection it
     * auto-switches to command mode for one utterance,
     * then returns to hot-word listening mode.
     */
    private fun startVoskHotWordMode() {
        val sessionManager = voskSessionManager
        if (sessionManager == null) {
            txtVoskOutput.text = "Vosk not initialised yet (preload may still be running)."
            return
        }

        if (sessionManager.mode != VoskMode.IDLE) {
            txtVoskOutput.text = "Vosk session already active."
            return
        }

        voskHotWordCount = 0
        txtVoskWakeWord.visibility = View.GONE
        txtVoskMode.visibility = View.VISIBLE
        txtVoskOutput.visibility = View.VISIBLE
        txtVoskOutput.text = "Listening for hot word..."

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
            // In hot-word mode, final results are logged but loop continues.
            // Reset indicator after command utterance.
            txtVoskWakeWord.visibility = View.GONE
            updateVoskClearButton()
        }

        val hotWordCallback = VoskHotWordListener {
            voskHotWordCount += 1
            txtVoskWakeWord.visibility = View.VISIBLE
            txtVoskMode.text = "Mode: COMMAND (hot #$voskHotWordCount)"
            txtVoskWakeWord.text = "[HOT WORD DETECTED] Switching to command mode..."
        }

        val modeChangeCallback: (VoskMode) -> Unit = { newMode ->
            when (newMode) {
                VoskMode.HOTWORD -> {
                    txtVoskMode.text = "Mode: HOTWORD"
                    radioVoskMode.isEnabled = false
                    btnVoskStart.isEnabled = false
                    btnVoskStop.isEnabled = true
                }
                VoskMode.COMMAND -> {
                    txtVoskMode.text = "Mode: COMMAND"
                    radioVoskMode.isEnabled = false
                    btnVoskStart.isEnabled = false
                    btnVoskStop.isEnabled = true
                }
                VoskMode.IDLE -> {
                    txtVoskMode.visibility = View.GONE
                    txtVoskWakeWord.visibility = View.GONE
                    radioVoskMode.isEnabled = true
                    btnVoskStart.isEnabled = true
                    btnVoskStop.isEnabled = false
                    updateVoskClearButton()
                }
            }
        }

        sessionManager.partialListener = partialCallback
        sessionManager.finalListener = finalCallback
        sessionManager.hotWordListener = hotWordCallback
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
            sessionManager.startHotWordMode()
        } catch (e: IllegalStateException) {
            txtVoskOutput.text = "Error: ${e.message}"
            radioVoskMode.isEnabled = true
            btnVoskStart.isEnabled = true
            btnVoskStop.isEnabled = false
        }
    }

    /**
     * Start a direct Vosk command-mode session (no hot word).
     * Useful for testing the recogniser without hot-word logic.
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

    /**
     * Show a placeholder message for true wake-word mode (not yet implemented).
     *
     * True wake-word mode will listen for a wake word while the system is
     * otherwise dormant, then activate on detection. This is distinct from
     * hot-word mode which continuously transcribes and flags the word.
     */
    private fun showWakeWordPlaceholder() {
        txtVoskOutput.text = "Wake Word mode is not yet implemented.\nUse Hot Word for wake-word behaviour in the meantime."
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
        val showStart = !isRecording
        btnStart.visibility = if (showStart) View.VISIBLE else View.GONE

        val showStop = activeStopType == "MANUAL"
        btnStop.visibility = if (showStop) View.VISIBLE else View.GONE

        btnClear.isEnabled = true
    }

    private fun updateVoskClearButton() {
        btnVoskClear.isEnabled = isVoskContentAvailable()
    }

    private fun isVoskContentAvailable(): Boolean {
        val finalResult = txtVoskFinal.text.toString()
        return finalResult.isNotEmpty() && finalResult != getString(R.string.vosk_final_hint)
    }

    private fun switchToMode(mode: String) {
        // Teardown whichever module is active before switching.
        if (voskSessionManager?.mode != VoskMode.IDLE) {
            stopVoskTest()
        }
        if (isRecording) {
            stopRecording()
        }
        stopWuwListening()
        stopWuwTemplateRecording()

        val showWhisper = mode == "whisper"
        val showVosk = mode == "vosk"
        val showWuw = mode == "wuw"

        panelWhisper.visibility = if (showWhisper) View.VISIBLE else View.GONE
        panelVosk.visibility = if (showVosk) View.VISIBLE else View.GONE
        panelWuw.visibility = if (showWuw) View.VISIBLE else View.GONE
        btnModeWhisper.isEnabled = !showWhisper
        btnModeVosk.isEnabled = !showVosk
        btnModeWuw.isEnabled = !showWuw
    }

    // ── WUW methods ──────────────────────────────────────────────────────────

    /**
     * Refresh the template list RadioGroup from the TemplateStore.
     */
    private fun refreshWuwTemplateList() {
        val store = wuwTemplateStore ?: return
        val templates = store.listTemplates()

        radioWuwTemplates.removeAllViews()

        if (templates.isEmpty()) {
            val emptyHint = android.widget.RadioButton(this)
            emptyHint.text = "No templates saved. Record one first."
            emptyHint.isEnabled = false
            emptyHint.setTextColor(0xFF4A148C.toInt())
            emptyHint.textSize = 11f
            radioWuwTemplates.addView(emptyHint)
            btnWuwPlay.isEnabled = false
            btnWuwMatch.isEnabled = false
            btnWuwDelete.isEnabled = false
            selectedWuwTemplate = null
        } else {
            for (t in templates) {
                val radio = android.widget.RadioButton(this)
                radio.text = t.name
                radio.textSize = 12f
                radioWuwTemplates.addView(radio)
            }
            // Select the first one by default.
            val firstRadio = radioWuwTemplates.getChildAt(0)
            if (firstRadio is android.widget.RadioButton) {
                firstRadio.isChecked = true
                selectedWuwTemplate = firstRadio.text.toString()
                btnWuwPlay.isEnabled = true
                btnWuwMatch.isEnabled = true
                btnWuwDelete.isEnabled = true
            }
        }
    }

    /**
     * Delete the currently selected template.
     */
    private fun deleteSelectedWuwTemplate() {
        val name = selectedWuwTemplate ?: return
        val store = wuwTemplateStore ?: return

        store.deleteTemplate(name)
        selectedWuwTemplate = null
        refreshWuwTemplateList()
        txtWuwOutput.text = "Deleted template '$name'."
    }

    /**
     * Trim leading and trailing silence from PCM data.
     *
     * Uses a simple energy-based VAD: samples below [silenceThreshold]
     * (relative to max amplitude) are considered silence.
     * Trims from the start until energy exceeds the threshold,
     * and from the end after the last energy exceedance.
     *
     * @param pcm Raw PCM samples (16-bit signed).
     * @param frameSize Samples per frame for energy calculation.
     * @param silenceThreshold Fraction of max amplitude treated as silence.
     * @return Trimmed PCM.
     */
    private fun trimSilence(
        pcm: ShortArray,
        frameSize: Int = 160,       // 10ms at 16kHz
        silenceThreshold: Float = 0.02f
    ): ShortArray {
        if (pcm.isEmpty()) return pcm

        val numFrames = pcm.size / frameSize
        if (numFrames < 3) return pcm

        // Compute RMS energy per frame.
        val energies = FloatArray(numFrames) { f ->
            var sumSq = 0f
            val start = f * frameSize
            val end = minOf(start + frameSize, pcm.size)
            for (i in start until end) {
                val norm = pcm[i] / 32768f
                sumSq += norm * norm
            }
            kotlin.math.sqrt(sumSq / (end - start).toFloat())
        }

        val maxEnergy = energies.maxOrNull() ?: return pcm
        if (maxEnergy < 1e-6f) return pcm

        val threshold = maxEnergy * silenceThreshold

        // Find first and last frames above threshold.
        var firstActive = -1
        var lastActive = -1
        for (i in energies.indices) {
            if (energies[i] >= threshold) {
                if (firstActive < 0) firstActive = i
                lastActive = i
            }
        }

        if (firstActive < 0 || lastActive < 0) {
            // All silence — return empty.
            return ShortArray(0)
        }

        val trimStart = firstActive * frameSize
        val trimEnd = minOf((lastActive + 1) * frameSize, pcm.size)

        return pcm.copyOfRange(trimStart, trimEnd)
    }

    /**
     * Start recording audio to save as a wake-word template.
     *
     * Captures PCM for 4 seconds, trims leading/trailing silence,
     * extracts MFCC, and saves as a named template.
     */
    private fun startWuwTemplateRecording() {
        if (isRecordingWuwTemplate) return

        isRecordingWuwTemplate = true
        btnWuwRecord.isEnabled = false
        btnWuwPlay.isEnabled = false
        txtWuwDetection.visibility = View.GONE
        txtWuwOutput.text = "Recording for 4 seconds... speak your wake word."

        val store = wuwTemplateStore ?: run {
            txtWuwOutput.text = "Template store not initialised."
            isRecordingWuwTemplate = false
            btnWuwRecord.isEnabled = true
            btnWuwPlay.isEnabled = true
            return
        }

        Thread({
            val sampleRate = 16000
            val durationMs = 4000
            val bufferSize = sampleRate * durationMs / 1000
            val minBufferBytes = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferBytes = maxOf(minBufferBytes, bufferSize * 2)

            val audioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
                )
            } catch (e: Exception) {
                postToUi {
                    txtWuwOutput.text = "Record failed: ${e.message}"
                    isRecordingWuwTemplate = false
                    btnWuwRecord.isEnabled = true
                    btnWuwPlay.isEnabled = true
                }
                return@Thread
            }

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                postToUi {
                    txtWuwOutput.text = "AudioRecord not initialised."
                    isRecordingWuwTemplate = false
                    btnWuwRecord.isEnabled = true
                    btnWuwPlay.isEnabled = true
                }
                return@Thread
            }

            // Update UI to show progress.
            postToUi { txtWuwOutput.text = "Recording... 0%" }

            audioRecord.startRecording()
            val pcmBuffer = ShortArray(bufferSize)
            var totalRead = 0
            var lastProgress = 0

            while (totalRead < bufferSize && isRecordingWuwTemplate) {
                val read = audioRecord.read(pcmBuffer, totalRead, bufferSize - totalRead)
                if (read > 0) {
                    totalRead += read
                    val progress = (totalRead * 100) / bufferSize
                    if (progress > lastProgress + 10) {
                        lastProgress = progress
                        val p = progress
                        postToUi { txtWuwOutput.text = "Recording... $p%" }
                    }
                }
            }

            audioRecord.stop()
            audioRecord.release()

            if (!isRecordingWuwTemplate) {
                postToUi {
                    txtWuwOutput.text = "Recording cancelled."
                    isRecordingWuwTemplate = false
                    btnWuwRecord.isEnabled = true
                    btnWuwPlay.isEnabled = true
                }
                return@Thread
            }

            val rawPcm = if (totalRead < pcmBuffer.size) {
                pcmBuffer.copyOf(totalRead)
            } else {
                pcmBuffer
            }

            // Trim silence.
            postToUi { txtWuwOutput.text = "Trimming silence..." }
            val trimmedPcm = trimSilence(rawPcm)

            if (trimmedPcm.size < sampleRate / 2) {
                // Less than 0.5s of speech — likely nothing was said.
                postToUi {
                    txtWuwOutput.text = "Too little speech detected. Try again with more audio."
                    isRecordingWuwTemplate = false
                    btnWuwRecord.isEnabled = true
                    btnWuwPlay.isEnabled = true
                }
                return@Thread
            }

            // Extract MFCC and save.
            postToUi { txtWuwOutput.text = "Extracting features..." }
            val mfccExtractor = MfccExtractor()
            val mfccFrames = mfccExtractor.extract(trimmedPcm)

            if (mfccFrames.isEmpty()) {
                postToUi {
                    txtWuwOutput.text = "Feature extraction failed. Try again."
                    isRecordingWuwTemplate = false
                    btnWuwRecord.isEnabled = true
                    btnWuwPlay.isEnabled = true
                }
                return@Thread
            }

            // Generate a unique name.
            // Use a timestamp-based name for uniqueness.
            val timestamp = java.text.SimpleDateFormat("MMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val baseName = "ww_$timestamp"
            val name = store.uniqueName(baseName)

            store.saveTemplate(name, mfccFrames)
            val rawSecs = "%.1f".format(rawPcm.size / sampleRate.toFloat())
            val speechSecs = "%.1f".format(trimmedPcm.size / sampleRate.toFloat())

            postToUi {
                isRecordingWuwTemplate = false
                btnWuwRecord.isEnabled = true
                btnWuwPlay.isEnabled = true
                refreshWuwTemplateList()
                txtWuwOutput.text = buildString {
                    appendLine("Template saved: '$name'")
                    appendLine("  Raw: ${rawSecs}s, Speech: ${speechSecs}s")
                    appendLine("  Frames: ${mfccFrames.size} x ${mfccFrames[0].size} coeffs")
                }
            }
            store.saveTemplate(name, mfccFrames, trimmedPcm)
        }, "WuwRecordThread").start()
    }

    /**
     * Stop template recording if in progress.
     */
    private fun stopWuwTemplateRecording() {
        isRecordingWuwTemplate = false
    }

    /**
     * Start listening for the wake word using the selected template.
     */
    private fun startWuwListening() {
        if (wuwSessionManager?.isListening == true) return

        val templateName = selectedWuwTemplate
        if (templateName == null) {
            txtWuwOutput.text = "No template selected. Select one from the list."
            return
        }

        val store = wuwTemplateStore
        if (store == null) {
            txtWuwOutput.text = "Template store not initialised."
            return
        }

        if (!store.hasTemplate(templateName)) {
            txtWuwOutput.text = "Template '$templateName' not found."
            refreshWuwTemplateList()
            return
        }

        val manager = WakeWordSessionManager(
            context = this,
            threshold = seekWuwThreshold.progress / 100f
        )

        // Load the template and set it on the engine.
        val template = store.loadTemplate(templateName)
        if (template.isEmpty()) {
            txtWuwOutput.text = "Failed to load template '$templateName'."
            manager.destroy()
            return
        }

        manager.setTemplateDirectly(template)

        manager.similarityListener = { similarity ->
            val target = seekWuwThreshold.progress / 100f
            postToUi {
                txtWuwOutput.text = String.format(
                    java.util.Locale.US,
                    "Listening using '%s'\nCurrent: %.2f | Target: %.2f",
                    templateName, similarity, target
                )
            }
        }

        manager.wakeWordListener = WakeWordListener {
            postToUi {
                txtWuwDetection.visibility = View.VISIBLE
                txtWuwDetection.text = "[WAKE WORD DETECTED]"
                txtWuwOutput.text = "Wake word detected from '$templateName'!"
                stopWuwListening()
            }
        }

        manager.errorListener = { message ->
            postToUi {
                txtWuwOutput.text = "Error: $message"
                updateWuwUiStopped()
            }
        }

        wuwSessionManager = manager
        manager.startListening(templateName)
        updateWuwUiListening()
        txtWuwOutput.text = "Listening for wake word using '$templateName'..."
        txtWuwDetection.visibility = View.GONE
    }

    /**
     * Stop listening for the wake word.
     */
    private fun stopWuwListening() {
        wuwSessionManager?.stopListening()
        wuwSessionManager?.destroy()
        wuwSessionManager = null
        updateWuwUiStopped()
    }

    private fun updateWuwUiListening() {
        txtWuwStatus.text = getString(R.string.vosk_status_active)
        txtWuwStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        btnWuwRecord.isEnabled = false
        btnWuwPlay.isEnabled = false
        btnWuwMatch.isEnabled = false
        btnWuwDelete.isEnabled = false
    }

    private fun updateWuwUiStopped() {
        txtWuwStatus.text = getString(R.string.vosk_status_idle)
        txtWuwStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        btnWuwRecord.isEnabled = true
        btnWuwPlay.isEnabled = selectedWuwTemplate != null
        btnWuwMatch.isEnabled = selectedWuwTemplate != null
        btnWuwDelete.isEnabled = selectedWuwTemplate != null
    }

    /**
     * Play back the raw PCM of the selected template via AudioTrack.
     */
    private fun playSelectedWuwTemplate() {
        if (isPlayingWuwTemplate) return

        val templateName = selectedWuwTemplate
        if (templateName == null) {
            txtWuwOutput.text = "No template selected."
            return
        }

        val store = wuwTemplateStore ?: return
        val pcm = store.loadPcm(templateName)
        if (pcm == null) {
            txtWuwOutput.text = "No audio saved for template '$templateName'."
            return
        }

        txtWuwOutput.text = "Playing template '$templateName'..."

        val sampleRate = 16000
        isPlayingWuwTemplate = true
        btnWuwPlay.isEnabled = false

        Thread({
            val minBufferBytes = AudioTrack.getMinBufferSize(
                sampleRate,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )

            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBufferBytes, pcm.size * 2))
                .build()

            audioTrack.play()
            audioTrack.write(pcm, 0, pcm.size)

            // Wait until the audio is actually finished playing before releasing resources.
            // HEAD position is in frames (1 short = 1 frame for mono).
            while (audioTrack.playbackHeadPosition < pcm.size && isPlayingWuwTemplate) {
                Thread.sleep(50)
            }

            audioTrack.stop()
            audioTrack.release()

            postToUi {
                isPlayingWuwTemplate = false
                btnWuwPlay.isEnabled = true
                txtWuwOutput.text = "Playback finished for '$templateName'."
            }
        }, "WuwPlayThread").start()
    }

    private fun getModelPath(): String {
        return File(filesDir, "model.bin").absolutePath
    }
}
