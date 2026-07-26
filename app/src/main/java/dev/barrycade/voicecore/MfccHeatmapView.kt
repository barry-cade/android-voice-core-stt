package dev.barrycade.voicecore

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Custom View that renders MFCC frames as a colour heatmap.
 *
 * Each column represents one MFCC frame, each row represents one coefficient index.
 * Colour intensity maps to coefficient magnitude.
 *
 * Supports two colour scaling modes:
 * - Per-coefficient (default): each row independently scaled to [0,1] so coefficient
 *   0 (energy) doesn't drown out spectral shape coefficients.
 * - Global: single scale across all coefficients, useful when comparing two views
 *   with [fixedScaleMin]/[fixedScaleMax].
 *
 * Supports two layout modes:
 * - **Fit mode** (default): scales the heatmap to fill the view width.
 * - **Fixed-pixel mode**: draws one column per frame regardless of view width —
 *   wrap in a HorizontalScrollView for scrolling through the full heatmap.
 */
class MfccHeatmapView(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** MFCC frames to display. Each FloatArray is one frame's coefficients. */
    var mfccFrames: List<FloatArray> = emptyList()
        set(value) {
            field = value
            postInvalidate()
        }

    /** When true, draws at fixed pixel resolution (one column per frame). */
    var useFixedPixelMode: Boolean = false

    /** Pixels per frame column in fixed-pixel mode. Default 8px per frame. */
    var pixelsPerFrame: Int = 8

    /**
     * Fixed min for colour scaling across views.
     * When NaN, auto-computes from the data.
     * Only used when [usePerCoefficientScale] is false.
     */
    var fixedScaleMin: Float = Float.NaN

    /**
     * Fixed max for colour scaling across views.
     * When NaN, auto-computes from the data.
     * Only used when [usePerCoefficientScale] is false.
     */
    var fixedScaleMax: Float = Float.NaN

    /**
     * When true, each coefficient row is independently scaled to its own
     * [0,1] range. This prevents coefficient 0 (energy) from drowning out
     * the other spectral shape coefficients. Default true.
     */
    var usePerCoefficientScale: Boolean = true

    /** Paint for drawing heatmap cells. */
    private val cellPaint = Paint()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (useFixedPixelMode && mfccFrames.isNotEmpty()) {
            val desiredWidth = maxOf(1, mfccFrames.size * pixelsPerFrame)
            val w = MeasureSpec.makeMeasureSpec(desiredWidth, MeasureSpec.EXACTLY)
            super.onMeasure(w, heightMeasureSpec)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val frames = mfccFrames
        if (frames.isEmpty() || frames[0].isEmpty()) {
            return
        }

        val numFrames = frames.size
        val numCoeffs = frames[0].size
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        val cellWidth: Float
        val cellHeight: Float

        if (useFixedPixelMode) {
            cellWidth = pixelsPerFrame.toFloat()
            cellHeight = viewHeight / numCoeffs
        } else {
            cellWidth = viewWidth / numFrames
            cellHeight = viewHeight / numCoeffs
        }

        if (usePerCoefficientScale) {
            drawPerCoefficientScale(canvas, frames, numFrames, numCoeffs, cellWidth, cellHeight)
        } else {
            drawGlobalScale(canvas, frames, numFrames, numCoeffs, cellWidth, cellHeight)
        }
    }

    private fun drawPerCoefficientScale(
        canvas: Canvas,
        frames: List<FloatArray>,
        numFrames: Int,
        numCoeffs: Int,
        cellWidth: Float,
        cellHeight: Float
    ) {
        // Pre-compute min/max per coefficient row for independent scaling.
        val coeffMin = FloatArray(numCoeffs) { Float.MAX_VALUE }
        val coeffMax = FloatArray(numCoeffs) { Float.MIN_VALUE }
        for (frame in frames) {
            for (c in 0 until minOf(frame.size, numCoeffs)) {
                if (frame[c] < coeffMin[c]) coeffMin[c] = frame[c]
                if (frame[c] > coeffMax[c]) coeffMax[c] = frame[c]
            }
        }
        for (c in 0 until numCoeffs) {
            if (coeffMax[c] - coeffMin[c] < 1e-6f) {
                coeffMax[c] = coeffMin[c] + 1f
            }
        }

        for (frameIdx in 0 until numFrames) {
            val frame = frames[frameIdx]
            for (coeffIdx in 0 until minOf(frame.size, numCoeffs)) {
                val normalized = ((frame[coeffIdx] - coeffMin[coeffIdx]) /
                    (coeffMax[coeffIdx] - coeffMin[coeffIdx])).coerceIn(0f, 1f)
                cellPaint.color = heatmapColour(normalized)

                val left = frameIdx * cellWidth
                val top = coeffIdx * cellHeight
                canvas.drawRect(left, top, left + cellWidth, top + cellHeight, cellPaint)
            }
        }
    }

    private fun drawGlobalScale(
        canvas: Canvas,
        frames: List<FloatArray>,
        numFrames: Int,
        numCoeffs: Int,
        cellWidth: Float,
        cellHeight: Float
    ) {
        val scaleMin: Float
        val scaleMax: Float
        if (!fixedScaleMin.isNaN() && !fixedScaleMax.isNaN()) {
            scaleMin = fixedScaleMin
            scaleMax = fixedScaleMax
        } else {
            var min = Float.MAX_VALUE
            var max = Float.MIN_VALUE
            for (frame in frames) {
                for (c in frame) {
                    if (c < min) min = c
                    if (c > max) max = c
                }
            }
            scaleMin = min
            scaleMax = if (max - min < 1e-6f) min + 1f else max
        }

        val range = scaleMax - scaleMin

        for (frameIdx in 0 until numFrames) {
            val frame = frames[frameIdx]
            for (coeffIdx in 0 until minOf(frame.size, numCoeffs)) {
                val normalized = ((frame[coeffIdx] - scaleMin) / range).coerceIn(0f, 1f)
                cellPaint.color = heatmapColour(normalized)

                val left = frameIdx * cellWidth
                val top = coeffIdx * cellHeight
                canvas.drawRect(left, top, left + cellWidth, top + cellHeight, cellPaint)
            }
        }
    }

    /**
     * Map a normalised value [0, 1] to a diverging colour:
     *   0.0 → deep blue  (coefficient strongly negative)
     *   0.5 → black      (coefficient near zero)
     *   1.0 → deep red   (coefficient strongly positive)
     */
    private fun heatmapColour(t: Float): Int {
        return if (t <= 0.5f) {
            val p = t / 0.5f
            val blue = (80f + 175f * (1f - p)).toInt().coerceIn(0, 255)
            val red = (80f * (1f - p)).toInt().coerceIn(0, 255)
            val green = (80f * (1f - p)).toInt().coerceIn(0, 255)
            Color.rgb(red, green, blue)
        } else {
            val p = (t - 0.5f) / 0.5f
            val red = (80f + 175f * p).toInt().coerceIn(0, 255)
            val green = (80f * (1f - p)).toInt().coerceIn(0, 255)
            val blue = (80f * (1f - p)).toInt().coerceIn(0, 255)
            Color.rgb(red, green, blue)
        }
    }
}