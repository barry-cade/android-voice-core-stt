package dev.barrycade.voicecore

import android.Manifest
import android.app.AlertDialog
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.barrycade.voicecore.wuw.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages the Wake Word (WUW) engine UI and lifecycle.
 */
class WuwPanel(private val activity: MainActivity) {

    private val txtWuwStatus: TextView = activity.findViewById(R.id.txtWuwStatus)
    private val txtWuwOutput: TextView = activity.findViewById(R.id.txtWuwOutput)
    private val txtWuwDetection: TextView = activity.findViewById(R.id.txtWuwDetection)
    private val txtWuwThreshold: TextView = activity.findViewById(R.id.txtWuwThreshold)
    private val btnWuwRecord: Button = activity.findViewById(R.id.btnWuwRecord)
    private val btnWuwPlay: Button = activity.findViewById(R.id.btnWuwPlay)
    private val btnWuwMatch: Button = activity.findViewById(R.id.btnWuwMatch)
    private val btnWuwDelete: Button = activity.findViewById(R.id.btnWuwDelete)
    private val seekWuwThreshold: SeekBar = activity.findViewById(R.id.seekWuwThreshold)
    private val radioWuwTemplates: RadioGroup = activity.findViewById(R.id.radioWuwTemplates)
    private val progressWuwSimilarity: ProgressBar = activity.findViewById(R.id.progressWuwSimilarity)
    private val viewWuwWaveform: WuwWaveformView = activity.findViewById(R.id.viewWuwWaveform)
    private val txtWuwSimilarityHistory: TextView = activity.findViewById(R.id.txtWuwSimilarityHistory)

    // Rolling similarity history (max 20 entries)
    private val similarityHistory = mutableListOf<Float>()

    /** Add a value to the rolling history, capped at 20. */
    private fun addSimilarity(value: Float) {
        similarityHistory.add(value)
        if (similarityHistory.size > 20) {
            similarityHistory.removeAt(0)
        }
    }

    /** Format the history summary text. */
    private fun formatSimilarityHistory(): String {
        val history = similarityHistory
        if (history.isEmpty()) return ""
        val min = history.minOrNull() ?: 0f
        val max = history.maxOrNull() ?: 0f
        val avg = history.average().toFloat()
        val last = history.last()
        return String.format(Locale.US, "Last: %.2f | Min: %.2f | Avg: %.2f | Max: %.2f | Samples: %d",
            last, min, avg, max, history.size)
    }

    // Calibration UI
    private val chkWuwCalibration = activity.findViewById<CheckBox>(R.id.chkWuwCalibration)
    private val panelWuwCalibration = activity.findViewById<View>(R.id.panelWuwCalibration)
    private val seekWuwRecDuration = activity.findViewById<SeekBar>(R.id.seekWuwRecDuration)
    private val txtWuwRecDuration = activity.findViewById<TextView>(R.id.txtWuwRecDuration)
    private var currentWuwRecDurationMs: Int = 4000
    private val seekWuwMinFrames = activity.findViewById<SeekBar>(R.id.seekWuwMinFrames)
    private val txtWuwMinFrames = activity.findViewById<TextView>(R.id.txtWuwMinFrames)
    private val seekWuwMaxFrames = activity.findViewById<SeekBar>(R.id.seekWuwMaxFrames)
    private val txtWuwMaxFrames = activity.findViewById<TextView>(R.id.txtWuwMaxFrames)
    private val seekWuwCheckInterval = activity.findViewById<SeekBar>(R.id.seekWuwCheckInterval)
    private val txtWuwCheckInterval = activity.findViewById<TextView>(R.id.txtWuwCheckInterval)
    private val seekWuwSimilarityK = activity.findViewById<SeekBar>(R.id.seekWuwSimilarityK)
    private val txtWuwSimilarityK = activity.findViewById<TextView>(R.id.txtWuwSimilarityK)
    private val seekWuwPreEmphasis = activity.findViewById<SeekBar>(R.id.seekWuwPreEmphasis)
    private val txtWuwPreEmphasis = activity.findViewById<TextView>(R.id.txtWuwPreEmphasis)
    private val radioWuwNumCoeffs = activity.findViewById<RadioGroup>(R.id.radioWuwNumCoeffs)
    private val seekWuwFrameDuration = activity.findViewById<SeekBar>(R.id.seekWuwFrameDuration)
    private val txtWuwFrameDuration = activity.findViewById<TextView>(R.id.txtWuwFrameDuration)
    private val seekWuwFrameStride = activity.findViewById<SeekBar>(R.id.seekWuwFrameStride)
    private val txtWuwFrameStride = activity.findViewById<TextView>(R.id.txtWuwFrameStride)

    var wuwSessionManager: WakeWordSessionManager? = null
        private set
    private var wuwTemplateStore: TemplateStore? = null
    private var isRecordingWuwTemplate: Boolean = false
    private var isPlayingWuwTemplate: Boolean = false
    private var selectedWuwTemplate: String? = null

    init {
        btnWuwRecord.setOnClickListener {
            if (activity.hasRecordAudioPermission()) {
                startWuwTemplateRecording()
            } else {
                activity.requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        btnWuwPlay.setOnClickListener {
            playSelectedWuwTemplate()
        }

        btnWuwMatch.setOnClickListener {
            if (wuwSessionManager?.isListening == true) {
                stopWuwListening()
            } else {
                if (activity.hasRecordAudioPermission()) {
                    startWuwListening()
                } else {
                    activity.requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        btnWuwDelete.setOnClickListener {
            deleteSelectedWuwTemplate()
        }

        radioWuwTemplates.setOnCheckedChangeListener { _, checkedId ->
            val radioButton = activity.findViewById<RadioButton>(checkedId)
            if (radioButton != null) {
                selectedWuwTemplate = radioButton.text.toString()
                btnWuwPlay.isEnabled = true
                btnWuwMatch.isEnabled = true
                btnWuwDelete.isEnabled = true
            } else {
                selectedWuwTemplate = null
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

        // Calibration toggle
        chkWuwCalibration.setOnCheckedChangeListener { _, isChecked ->
            panelWuwCalibration.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Rec duration (0-14 -> 1000-8000 step 500)
        seekWuwRecDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                currentWuwRecDurationMs = 1000 + progress * 500
                txtWuwRecDuration.text = "$currentWuwRecDurationMs"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // Min frames (0-27 -> 3-30)
        seekWuwMinFrames.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                txtWuwMinFrames.text = "${3 + progress}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                wuwSessionManager?.getEngine()?.let { engine ->
                    engine.minFramesForMatch = txtWuwMinFrames.text.toString().toInt()
                }
            }
        })

        // Max frames (0-100 -> 20-120)
        seekWuwMaxFrames.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                txtWuwMaxFrames.text = "${20 + progress}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                wuwSessionManager?.getEngine()?.let { engine ->
                    engine.maxFramesForMatch = txtWuwMaxFrames.text.toString().toInt()
                }
            }
        })

        // Check interval (0-19 -> 1-20)
        seekWuwCheckInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                txtWuwCheckInterval.text = "${1 + progress}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                wuwSessionManager?.getEngine()?.let { engine ->
                    engine.checkIntervalFrames = txtWuwCheckInterval.text.toString().toInt()
                }
            }
        })

        // Similarity K (0-19 -> 0.1-2.0 step 0.1)
        seekWuwSimilarityK.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                txtWuwSimilarityK.text = String.format("%.1f", 0.1f + progress * 0.1f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                wuwSessionManager?.getEngine()?.let { engine ->
                    engine.similarityK = 0.1f + seekWuwSimilarityK.progress * 0.1f
                }
            }
        })

        // Pre-emphasis (0-99 -> 0.00-0.99 step 0.01)
        seekWuwPreEmphasis.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                txtWuwPreEmphasis.text = String.format("%.2f", progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val alpha = seekWuwPreEmphasis.progress / 100f
                wuwSessionManager?.getMfccExtractor()?.let { extractor ->
                    extractor.preEmphasisAlpha = alpha
                }
            }
        })

        // MFCC coefficients radio group
        radioWuwNumCoeffs.setOnCheckedChangeListener { _, checkedId ->
            val coeffs = when (checkedId) {
                R.id.radioWuwCoeff8 -> 8
                R.id.radioWuwCoeff13 -> 13
                R.id.radioWuwCoeff20 -> 20
                else -> 13
            }
            wuwSessionManager?.getMfccExtractor()?.let { extractor ->
                extractor.numCoefficients = coeffs
                extractor.rebuildDerived()
            }
        }

        // Frame duration (0-7 -> 15-50 step 5)
        seekWuwFrameDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                txtWuwFrameDuration.text = "${15 + progress * 5}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val durationMs = 15 + seekWuwFrameDuration.progress * 5
                wuwSessionManager?.getMfccExtractor()?.let { extractor ->
                    extractor.frameDurationMs = durationMs
                    extractor.rebuildDerived()
                }
            }
        })

        // Frame stride (0-3 -> 5-20 step 5)
        seekWuwFrameStride.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                txtWuwFrameStride.text = "${5 + progress * 5}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val strideMs = 5 + seekWuwFrameStride.progress * 5
                wuwSessionManager?.getMfccExtractor()?.let { extractor ->
                    extractor.frameStrideMs = strideMs
                    extractor.rebuildDerived()
                }
            }
        })

        wuwTemplateStore = TemplateStore(activity)
        refreshWuwTemplateList()
    }

    fun refreshWuwTemplateList() {
        val store = wuwTemplateStore ?: return
        val templates = store.listTemplates()

        radioWuwTemplates.removeAllViews()

        if (templates.isEmpty()) {
            val emptyHint = RadioButton(activity)
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
                val radio = RadioButton(activity)
                radio.text = t.name
                radio.textSize = 12f
                radioWuwTemplates.addView(radio)
            }
            val firstRadio = radioWuwTemplates.getChildAt(0)
            if (firstRadio is RadioButton) {
                firstRadio.isChecked = true
                selectedWuwTemplate = firstRadio.text.toString()
                btnWuwPlay.isEnabled = true
                btnWuwMatch.isEnabled = true
                btnWuwDelete.isEnabled = true
            }
        }
    }

    private fun deleteSelectedWuwTemplate() {
        val name = selectedWuwTemplate ?: return
        val store = wuwTemplateStore ?: return
        store.deleteTemplate(name)
        selectedWuwTemplate = null
        refreshWuwTemplateList()
        txtWuwOutput.text = "Deleted template '$name'."
    }

    private fun trimSilence(pcm: ShortArray, frameSize: Int = 160, silenceThreshold: Float = 0.02f): ShortArray {
        if (pcm.isEmpty()) return pcm
        val numFrames = pcm.size / frameSize
        if (numFrames < 3) return pcm
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
        var firstActive = -1
        var lastActive = -1
        for (i in energies.indices) {
            if (energies[i] >= threshold) {
                if (firstActive < 0) firstActive = i
                lastActive = i
            }
        }
        if (firstActive < 0 || lastActive < 0) return ShortArray(0)
        val trimStart = firstActive * frameSize
        val trimEnd = minOf((lastActive + 1) * frameSize, pcm.size)
        return pcm.copyOfRange(trimStart, trimEnd)
    }

    fun startWuwTemplateRecording() {
        if (isRecordingWuwTemplate) {
            isRecordingWuwTemplate = false
            return
        }
        isRecordingWuwTemplate = true
        btnWuwRecord.text = "Stop"
        btnWuwPlay.isEnabled = false
        btnWuwMatch.isEnabled = false
        btnWuwDelete.isEnabled = false
        setViewEnabled(radioWuwTemplates, false)
        seekWuwThreshold.isEnabled = false
        txtWuwDetection.visibility = View.GONE
        txtWuwOutput.text = "Recording... speak your wake word (tap Stop when done)."

        if (wuwTemplateStore == null) {
            txtWuwOutput.text = "Template store not initialised."
            isRecordingWuwTemplate = false
            btnWuwRecord.text = "Record"
            updateWuwUiStopped()
            return
        }

        Thread({
            val sampleRate = 16000
            val durationMs = currentWuwRecDurationMs
            val bufferSize = sampleRate * durationMs / 1000
            val minBufferBytes = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufferBytes = maxOf(minBufferBytes, bufferSize * 2)
            val audioRecord = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            } catch (e: Exception) {
                activity.runOnUiThread {
                    txtWuwOutput.text = "Record failed: ${e.message}"
                    isRecordingWuwTemplate = false
                    btnWuwRecord.text = "Record"
                    updateWuwUiStopped()
                }
                return@Thread
            }
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                activity.runOnUiThread {
                    txtWuwOutput.text = "AudioRecord not initialised."
                    isRecordingWuwTemplate = false
                    btnWuwRecord.text = "Record"
                    updateWuwUiStopped()
                }
                return@Thread
            }
            activity.runOnUiThread { txtWuwOutput.text = "Recording... 0%" }
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
                        activity.runOnUiThread { txtWuwOutput.text = "Recording... $p%" }
                    }
                }
            }
            audioRecord.stop()
            audioRecord.release()
            activity.runOnUiThread {
                btnWuwRecord.text = "Record"
                updateWuwUiStopped()
            }
            val rawPcm = if (totalRead < pcmBuffer.size) pcmBuffer.copyOf(totalRead) else pcmBuffer
            if (rawPcm.isEmpty()) {
                activity.runOnUiThread { txtWuwOutput.text = "No audio captured." }
                return@Thread
            }
            activity.runOnUiThread { txtWuwOutput.text = "Trimming silence..." }
            val trimmedPcm = trimSilence(rawPcm)
            if (trimmedPcm.size < sampleRate / 2) {
                activity.runOnUiThread { txtWuwOutput.text = "Too little speech detected (<0.5s)." }
                return@Thread
            }
            activity.runOnUiThread { txtWuwOutput.text = "Extracting features..." }
            val mfccExtractor = wuwSessionManager?.getMfccExtractor() ?: MfccExtractor()
            val mfccFrames = mfccExtractor.extract(trimmedPcm)
            if (mfccFrames.isEmpty()) {
                activity.runOnUiThread { txtWuwOutput.text = "Feature extraction failed." }
                return@Thread
            }
            activity.runOnUiThread { showWuwNamingDialog(mfccFrames, trimmedPcm) }
        }, "WuwRecordThread").start()
    }

    private fun showWuwNamingDialog(mfccFrames: List<FloatArray>, trimmedPcm: ShortArray) {
        val input = EditText(activity)
        val timestamp = SimpleDateFormat("HHmm", Locale.US).format(Date())
        input.setText("ww_$timestamp")
        input.setSelectAllOnFocus(true)
        AlertDialog.Builder(activity)
            .setTitle("Save Wake Word")
            .setMessage("Enter a name for this template:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val store = wuwTemplateStore
                    if (store != null) {
                        val finalName = store.uniqueName(name)
                        store.saveTemplate(finalName, mfccFrames, trimmedPcm)
                        refreshWuwTemplateList()
                        txtWuwOutput.text = "Template saved as '$finalName'."
                    }
                }
            }
            .setNegativeButton("Discard", null)
            .show()
    }

    fun stopWuwTemplateRecording() {
        isRecordingWuwTemplate = false
    }

    fun startWuwListening() {
        if (wuwSessionManager?.isListening == true) return
        val templateName = selectedWuwTemplate
        if (templateName == null) {
            txtWuwOutput.text = "No template selected. Select one from the list."
            return
        }
        val store = wuwTemplateStore ?: return
        if (!store.hasTemplate(templateName)) {
            txtWuwOutput.text = "Template '$templateName' not found."
            refreshWuwTemplateList()
            return
        }
        val manager = WakeWordSessionManager(context = activity, threshold = seekWuwThreshold.progress / 100f)
        val template = store.loadTemplate(templateName)
        if (template.isEmpty()) {
            txtWuwOutput.text = "Failed to load template '$templateName'."
            manager.destroy()
            return
        }
        manager.setTemplateDirectly(template)

        // Reset similarity history for new listening session
        similarityHistory.clear()

        manager.similarityListener = { similarity ->
            val target = seekWuwThreshold.progress / 100f
            activity.runOnUiThread {
                addSimilarity(similarity)
                progressWuwSimilarity.progress = (similarity * 100).toInt()
                txtWuwSimilarityHistory.text = formatSimilarityHistory()
                txtWuwOutput.text = String.format(Locale.US, "Listening using '%s'\nCurrent: %.2f | Target: %.2f", templateName, similarity, target)
            }
        }

        // Pipe PCM data to the waveform view
        manager.pcmListener = { pcm ->
            activity.runOnUiThread {
                viewWuwWaveform.pcmSamples = pcm
            }
        }
        manager.wakeWordListener = WakeWordListener {
            activity.runOnUiThread {
                txtWuwDetection.visibility = View.VISIBLE
                txtWuwDetection.text = "[WAKE WORD DETECTED]"
                txtWuwOutput.text = "Wake word detected from '$templateName'!"
                stopWuwListening()
            }
        }
        // Auto-stop when silence detected after utterance
        manager.silenceAutoStopListener = { peakSimilarity ->
            activity.runOnUiThread {
                wuwSessionManager = null
                val target = seekWuwThreshold.progress / 100f
                txtWuwOutput.text = String.format(Locale.US,
                    "Silence detected. Peak similarity: %.2f (target: %.2f). %s",
                    peakSimilarity, target,
                    if (peakSimilarity >= target) "Close to threshold!" else "Try again with clearer speech.")
                txtWuwDetection.text = if (peakSimilarity >= target) "[ALMOST — try lowering threshold]" else ""
                txtWuwDetection.visibility = if (peakSimilarity >= target * 0.8f) View.VISIBLE else View.GONE
                updateWuwUiStopped()
            }
        }

        manager.errorListener = { message ->
            activity.runOnUiThread {
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

    fun stopWuwListening() {
        wuwSessionManager?.stopListening()
        wuwSessionManager?.destroy()
        wuwSessionManager = null
        updateWuwUiStopped()
    }

    fun updateWuwUiListening() {
        txtWuwStatus.text = activity.getString(R.string.vosk_status_active)
        txtWuwStatus.setTextColor(ContextCompat.getColor(activity, android.R.color.holo_red_dark))
        btnWuwRecord.isEnabled = false
        btnWuwPlay.isEnabled = false
        btnWuwMatch.isEnabled = true
        btnWuwMatch.text = "Stop"
        btnWuwDelete.isEnabled = false
        setViewEnabled(radioWuwTemplates, false)
        seekWuwThreshold.isEnabled = false
        progressWuwSimilarity.visibility = View.VISIBLE
        progressWuwSimilarity.progress = 0
        viewWuwWaveform.visibility = View.VISIBLE
        txtWuwSimilarityHistory.visibility = View.VISIBLE
        txtWuwSimilarityHistory.text = ""
    }

    fun updateWuwUiStopped() {
        txtWuwStatus.text = activity.getString(R.string.vosk_status_idle)
        txtWuwStatus.setTextColor(ContextCompat.getColor(activity, android.R.color.darker_gray))
        btnWuwRecord.isEnabled = true
        btnWuwRecord.text = "Record"
        btnWuwPlay.isEnabled = selectedWuwTemplate != null
        btnWuwMatch.isEnabled = selectedWuwTemplate != null
        btnWuwMatch.text = "Match"
        btnWuwDelete.isEnabled = selectedWuwTemplate != null
        setViewEnabled(radioWuwTemplates, true)
        seekWuwThreshold.isEnabled = true
        viewWuwWaveform.visibility = View.GONE
        txtWuwSimilarityHistory.visibility = View.GONE
        progressWuwSimilarity.visibility = View.GONE
    }

    fun playSelectedWuwTemplate() {
        if (isPlayingWuwTemplate) return
        val templateName = selectedWuwTemplate ?: return
        val store = wuwTemplateStore ?: return
        val pcm = store.loadPcm(templateName) ?: run {
            txtWuwOutput.text = "No audio saved for template '$templateName'."
            return
        }
        txtWuwOutput.text = "Playing template '$templateName'..."
        val sampleRate = 16000
        isPlayingWuwTemplate = true
        btnWuwPlay.isEnabled = false
        Thread({
            val minBufferBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBufferBytes, pcm.size * 2))
                .build()
            audioTrack.play()
            audioTrack.write(pcm, 0, pcm.size)
            while (audioTrack.playbackHeadPosition < pcm.size && isPlayingWuwTemplate) {
                Thread.sleep(50)
            }
            audioTrack.stop()
            audioTrack.release()
            activity.runOnUiThread {
                isPlayingWuwTemplate = false
                btnWuwPlay.isEnabled = true
                txtWuwOutput.text = "Playback finished for '$templateName'."
            }
        }, "WuwPlayThread").start()
    }
}
