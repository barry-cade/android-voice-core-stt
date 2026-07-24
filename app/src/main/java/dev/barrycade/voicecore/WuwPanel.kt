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
            val durationMs = 4000
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
            val mfccExtractor = MfccExtractor()
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
        manager.similarityListener = { similarity ->
            val target = seekWuwThreshold.progress / 100f
            activity.runOnUiThread {
                progressWuwSimilarity.progress = (similarity * 100).toInt()
                txtWuwOutput.text = String.format(Locale.US, "Listening using '%s'\nCurrent: %.2f | Target: %.2f", templateName, similarity, target)
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
