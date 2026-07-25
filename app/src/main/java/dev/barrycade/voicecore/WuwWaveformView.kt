package dev.barrycade.voicecore

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * Custom View that draws a real-time waveform from PCM audio samples.
 *
 * The trailing portion of the buffer is drawn as a thin filled waveform.
 * A thick horizontal line marks the centre (zero).
 */
class WuwWaveformView(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** PCM samples to display. Updated on the UI thread. */
    var pcmSamples: ShortArray = ShortArray(0)
        set(value) {
            field = value
            postInvalidate()
        }

    /** Peak amplitude for scaling (RMS-based, updated each frame). */
    private var displayPeak: Float = 1f

    /** Smoothing factor for the peak display. */
    private var peakSmoothing: Float = 0.3f

    private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 105, 180) // Hot pink
        style = Paint.Style.FILL
    }

    private val centreLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 0, 0, 0)
        strokeWidth = 1f
    }

    private val peakLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 200, 0, 0)
        strokeWidth = 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val centreY = h / 2f

        // Draw centre line
        canvas.drawLine(0f, centreY, w, centreY, centreLinePaint)

        val samples = pcmSamples
        if (samples.isEmpty()) return

        // Compute RMS peak for this buffer
        var sumSq = 0.0
        for (s in samples) {
            sumSq += (s.toDouble() / 32768.0) * (s.toDouble() / 32768.0)
        }
        val rms = kotlin.math.sqrt(sumSq / samples.size.toDouble()).toFloat()

        // Smooth peak
        displayPeak = peakSmoothing * rms + (1f - peakSmoothing) * displayPeak
        if (displayPeak < 0.01f) displayPeak = 0.01f

        // Draw peak indicator line on the right side
        val peakY = centreY - (centreY * (displayPeak / 1.0f).coerceIn(0f, 1f))
        canvas.drawLine(w - 4f, centreY, w - 4f, peakY, peakLinePaint)

        // Waveform: one vertical bar per pixel column
        val samplesPerPixel = max(1, samples.size / w.toInt())
        val halfH = h * 0.45f

        for (x in 0 until w.toInt()) {
            val startIdx = x * samplesPerPixel
            val endIdx = minOf(startIdx + samplesPerPixel, samples.size)
            if (startIdx >= samples.size) break

            // Find peak amplitude in this pixel column
            var maxAmp = 0f
            for (i in startIdx until endIdx) {
                val amp = samples[i].toFloat() / 32768f
                if (amp < 0f) {
                    if (-amp > maxAmp) maxAmp = -amp
                } else {
                    if (amp > maxAmp) maxAmp = amp
                }
            }

            val scaledAmp = (maxAmp / displayPeak).coerceIn(0f, 1f) * halfH
            val top = centreY - scaledAmp
            val bottom = centreY + scaledAmp
            canvas.drawLine(x.toFloat(), top, x.toFloat(), bottom, waveformPaint)
        }
    }
}