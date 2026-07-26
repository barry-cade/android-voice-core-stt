package dev.barrycade.voicecore

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class MfccHeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint()
    private val gridPaint = Paint().apply {
        color = Color.argb(80, 255, 255, 255)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint().apply {
        color = Color.argb(180, 255, 255, 255)
        textSize = 24f
        textAlign = Paint.Align.RIGHT
    }
    private val barPaint = Paint()
    private val scalePaint = Paint().apply {
        color = Color.WHITE
        textSize = 20f
        textAlign = Paint.Align.CENTER
    }

    var frames: List<FloatArray> = emptyList()
    var globalMin: Float = -2f
    var globalMax: Float = 2f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (frames.isEmpty()) return

        val cols = frames.size
        val rows = frames[0].size

        // Reserve space for labels (left) and scale bar (bottom)
        val labelWidth = 60f
        val scaleHeight = 20f
        val contentWidth = width.toFloat() - labelWidth
        val contentHeight = height.toFloat() - scaleHeight

        val colWidth = contentWidth / cols
        val rowHeight = contentHeight / rows

        for (x in 0 until cols) {
            val coeffs = frames[x]
            for (y in 0 until rows) {
                val value = coeffs[y]
                val norm = ((value - globalMin) / (globalMax - globalMin))
                    .coerceIn(0f, 1f)

                val r = (norm * 255).toInt()
                val b = (255 - r)
                val color = Color.rgb(r, 0, b)

                paint.color = color
                canvas.drawRect(
                    labelWidth + x * colWidth,
                    y * rowHeight,
                    labelWidth + (x + 1) * colWidth,
                    (y + 1) * rowHeight,
                    paint
                )

                // Grid line
                canvas.drawRect(
                    labelWidth + x * colWidth,
                    y * rowHeight,
                    labelWidth + (x + 1) * colWidth,
                    (y + 1) * rowHeight,
                    gridPaint
                )
            }
        }

        // Draw coefficient labels on the left
        for (y in 0 until rows) {
            canvas.drawText(
                "$y",
                labelWidth - 8f,
                y * rowHeight + rowHeight / 2f + 8f,
                labelPaint
            )
        }

        // Draw color scale bar at the bottom
        val barTop = contentHeight + 4f
        val barBottom = height.toFloat() - 2f
        val barSteps = 20
        val barStep = contentWidth / barSteps
        for (i in 0 until barSteps) {
            val t = i.toFloat() / barSteps
            val r = (t * 255).toInt()
            val b = (255 - r)
            barPaint.color = Color.rgb(r, 0, b)
            canvas.drawRect(
                labelWidth + i * barStep, barTop,
                labelWidth + (i + 1) * barStep, barBottom,
                barPaint
            )
        }

        // Scale min/max labels
        scalePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(String.format("%.1f", globalMin), labelWidth, height.toFloat() - 2f, scalePaint)
        scalePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(String.format("%.1f", globalMax), width.toFloat(), height.toFloat() - 2f, scalePaint)
    }
}