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
import dev.barrycade.voicecore.vosk.VoskEngine
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnClear: Button
    private lateinit var btnVoskTest: Button
    private lateinit var btnVoskStop: Button
    private lateinit var btnModeWhisper: Button
    private lateinit var btnModeVosk: Button
    private lateinit var panelWhisper: View
    private lateinit var panelVosk: View
    private lateinit var txtOutput: TextView
    private lateinit var txtVoskOutput: TextView
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
    private var isVoskActive = false
    private var voskEngine: VoskEngine? = null

    private fun postToUi(action: () -> Unit) {
        runOnUiThread(action)
    }

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

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
        btnVoskTest = findViewById(R.id.btnVoskTest)
        btnVoskStop = findViewById(R.id.btnVoskStop)
        btnModeWhisper = findViewById(R.id.btnModeWhisper)
        btnModeVosk = findViewById(R.id.btnModeVosk)
        panelWhisper = findViewById(R.id.panelWhisper)
        panelVosk = findViewById(R.id.panelVosk)
        txtOutput = findViewById(R.id.txtOutput)
        txtVoskOutput = findViewById(R.id.txtVoskOutput)
        txtDiagnostics = findViewById(R.id.txtDiagnostics)
        txtConfigDisplay = findViewById(R.id.txtConfigDisplay)
        txtErrorBanner = findViewById(R.id.txtErrorBanner)
        radioGroupStrategy = findViewById(R.id.radioGroupStrategy)

        // Register the JSON message listener before loadModel.
        // The listener is buffered by the companion and wired once the
        // singleton is created inside loadModel().
        SpeechToText.setOnMessageListener(createMessageListener())

        // ── Preload STT model at startup ─────────────────────────────────────
        preloadModelAsync()

        // ── Preload Vosk model at startup ────────────────────────────────────
        preloadVoskModelAsync()

        // ── Mode toggle ──────────────────────────────────────────────────────
        btnModeWhisper.setOnClickListener { switchToMode("whisper") }
        btnModeVosk.setOnClickListener { switchToMode("vosk") }

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
                SpeechToText.reconfigure(configJson)
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
        }

        btnVoskTest.setOnClickListener {
            if (hasRecordAudioPermission()) startVoskTest()
            else requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        btnVoskStop.setOnClickListener {
            stopVoskTest()
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
                        "result" -> onResultReceived(obj)
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
     * singleton is initialised before the user presses Start.
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
                val error = SpeechToText.loadModel(this, configJson)
                postToUi {
                    if (error != null) {
                        AppLogger.log(AppLogCode.PRELOAD_FAILED, "STT error: ${error.message}")
                        txtOutput.text = "Model preload error: ${error.message}"
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
     * Copy Vosk model from assets to internal storage and initialise VoskEngine.
     */
    private fun preloadVoskModelAsync() {
        Thread({
            try {
                val modelDir = copyAssetFolder(this, "vosk-model-small-en-gb-0.15")
                voskEngine = VoskEngine(modelDir.path)
                postToUi {
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
     * Start a standalone Vosk test capture.
     * Runs its own AudioRecord, feeds PCM to VoskEngine, displays results.
     */
    private fun startVoskTest() {
        val engine = voskEngine
        if (engine == null) {
            txtVoskOutput.text = "Vosk engine not ready yet (preload may still be running)."
            return
        }

        isVoskActive = true
        btnVoskTest.isEnabled = false
        btnVoskStop.isEnabled = true
        txtVoskOutput.visibility = View.VISIBLE
        txtVoskOutput.text = "Vosk test running... buffer: ${AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)} bytes"

        val voskThread = Thread({
            val sampleRate = 16000
            val bufferSizeSamples = 4000
            val minBufferBytes = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferBytes = maxOf(minBufferBytes, bufferSizeSamples * 2)

            val audioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
                )
            } catch (e: Exception) {
                postToUi { txtVoskOutput.text = "AudioRecord failed: ${e.message}" }
                return@Thread
            }

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                postToUi { txtVoskOutput.text = "AudioRecord not initialized." }
                return@Thread
            }

            audioRecord.startRecording()

            val shortBuffer = ShortArray(bufferSizeSamples)

            var frameCount = 0

            while (isVoskActive) {
                val readCount = audioRecord.read(shortBuffer, 0, shortBuffer.size)
                if (readCount <= 0) continue

                frameCount++

                try {
                    val pcmChunk = if (readCount < shortBuffer.size) {
                        shortBuffer.copyOf(readCount)
                    } else {
                        shortBuffer
                    }
                    val utteranceEnd = engine.acceptShort(pcmChunk)

                    val displayText: String
                    if (utteranceEnd) {
                        // Utterance ended — show final result and continue.
                        // Vosk resets internally, but we keep capturing.
                        val finalText = engine.finalResult()
                        displayText = "frames: $frameCount\n[END] $finalText"
                    } else {
                        val partial = engine.partialResult()
                        displayText = "frames: $frameCount\nPartial: $partial"
                    }
                    postToUi { txtVoskOutput.text = displayText }
                } catch (t: Throwable) {
                    postToUi { txtVoskOutput.text = "Vosk error: ${t.message}" }
                    break
                }
            }

            audioRecord.stop()
            audioRecord.release()
        }, "VoskCaptureThread")

        voskThread.start()
    }

    /**
     * Stop the Vosk test capture cleanly.
     */
    private fun stopVoskTest() {
        isVoskActive = false
        btnVoskTest.isEnabled = true
        btnVoskStop.isEnabled = false
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
            // The message listener was already registered in onCreate().
            // startSession() starts the capture — the singleton and model
            // were already loaded at app startup via loadModel().
            val sessionError = SpeechToText.startSession()
            if (sessionError != null) {
                val message = sessionError.message
                AppLogger.log(AppLogCode.SESSION_ERROR, "${sessionError.code.name}: $message")
                postToUi { txtOutput.text = "Session error: $message" }
                return
            }

            blankAudioCount = 0
            isRecording = true
            txtOutput.text = "Recording..."
            btnStop.isEnabled = true
            btnStart.isEnabled = false

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
        val thread = Thread({
            try {
                AppLogger.log(AppLogCode.STOP_USING_STOP_AND_TRANSCRIBE)
                SpeechToText.transcribe()
                postToUi {
                    isRecording = false
                    btnStop.isEnabled = false
                    btnStart.isEnabled = true
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

    private fun switchToMode(mode: String) {
        val isWhisper = mode == "whisper"
        panelWhisper.visibility = if (isWhisper) View.VISIBLE else View.GONE
        panelVosk.visibility = if (isWhisper) View.GONE else View.VISIBLE
        btnModeWhisper.isEnabled = !isWhisper
        btnModeVosk.isEnabled = isWhisper
    }

    private fun getModelPath(): String {
        return File(filesDir, "model.bin").absolutePath
    }
}
