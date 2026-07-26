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
 * Custom View that draws a waveform from PCM audio samples.
 *
 * Supports two modes:
 * - **Fit mode** (default): scales the waveform to fill the view width.
 * - **Fixed-pixel mode**: draws one vertical line per sample (or fixed
 *   samples-per-pixel) regardless of view width — use inside a
 *   [HorizontalScrollView] to scroll through the full waveform.
 *
 * The [fixedPeak] property allows locking the amplitude scale across
 * two views so they can be visually compared (e.g. template vs live).
 * Set [useFixedPeak] to true and assign a common peak value to both.
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

    /**
     * When true, draws at a fixed pixel resolution (1 pixel per sample,
     * or [fixedSamplesPerPixel] per vertical bar).
     * The view's measured width will be samples.size / fixedSamplesPerPixel,
     * so wrap this in a HorizontalScrollView for scrolling.
     */
    var useFixedPixelMode: Boolean = false

    /**
     * Samples per pixel column in fixed-pixel mode.
     * 1 = one pixel per sample (good for short PCM), 4 or 8 for longer.
     */
    var fixedSamplesPerPixel: Int = 4

    /**
     * When > 0, forces the amplitude scale to this value instead of
     * auto-computing from the buffer. Set the same value on two views
     * to compare waveforms at the same scale.
     */
    var fixedPeak: Float = -1f

    /** Peak amplitude for scaling (auto-computed when [fixedPeak] <= 0). */
    private var displayPeak: Float = 1f

    /** Smoothing factor for the auto-computed peak display. */
    private var peakSmoothing: Float = 0.3f

    private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 105, 180) // Hot pink
        style = Paint.Style.FILL
    }

    private val centreLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 0, 0, 0)
        strokeWidth = 1f
    }

    /** Called to notify the parent that width may have changed in fixed-pixel mode. */
    private var onWidthChanged: (() -> Unit)? = null

    fun setOnWidthChangedListener(listener: () -> Unit) {
        onWidthChanged = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (useFixedPixelMode && pcmSamples.isNotEmpty()) {
            val desiredWidth = max(1, pcmSamples.size / fixedSamplesPerPixel)
            val w = MeasureSpec.makeMeasureSpec(desiredWidth, MeasureSpec.EXACTLY)
            super.onMeasure(w, heightMeasureSpec)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
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

        // Determine the peak to use for scaling
        val peak: Float
        if (fixedPeak > 0f) {
            peak = fixedPeak
        } else {
            // Auto-compute RMS peak
            var sumSq = 0.0
            for (s in samples) {
                sumSq += (s.toDouble() / 32768.0) * (s.toDouble() / 32768.0)
            }
            val rms = kotlin.math.sqrt(sumSq / samples.size.toDouble()).toFloat()
            displayPeak = peakSmoothing * rms + (1f - peakSmoothing) * displayPeak
            if (displayPeak < 0.01f) displayPeak = 0.01f
            peak = displayPeak
        }

        // Samples per pixel column
        val spPixel = if (useFixedPixelMode) {
            fixedSamplesPerPixel
        } else {
            max(1, samples.size / w.toInt())
        }

        val halfH = h * 0.45f
        val numColumns = if (useFixedPixelMode) {
            samples.size / spPixel
        } else {
            w.toInt()
        }

        for (x in 0 until numColumns) {
            val startIdx = x * spPixel
            val endIdx = minOf(startIdx + spPixel, samples.size)
            if (startIdx >= samples.size) break

            var maxAmp = 0f
            for (i in startIdx until endIdx) {
                val amp = samples[i].toFloat() / 32768f
                if (amp < 0f) {
                    if (-amp > maxAmp) maxAmp = -amp
                } else {
                    if (amp > maxAmp) maxAmp = amp
                }
            }

            val scaledAmp = (maxAmp / peak).coerceIn(0f, 1f) * halfH
            val top = centreY - scaledAmp
            val bottom = centreY + scaledAmp
            canvas.drawLine(x.toFloat(), top, x.toFloat(), bottom, waveformPaint)
        }
    }
}
