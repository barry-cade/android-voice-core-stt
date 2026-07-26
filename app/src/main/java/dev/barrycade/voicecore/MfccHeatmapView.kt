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

    var frames: List<FloatArray> = emptyList()
    var globalMin: Float = -2f
    var globalMax: Float = 2f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (frames.isEmpty()) return

        val cols = frames.size
        val rows = frames[0].size

        val colWidth = width.toFloat() / cols
        val rowHeight = height.toFloat() / rows

        for (x in 0 until cols) {
            val coeffs = frames[x]
            for (y in 0 until rows) {
                val value = coeffs[y]
                val norm = ((value - globalMin) / (globalMax - globalMin))
                    .coerceIn(0f, 1f)

                // Blue → Red gradient
                val r = (norm * 255).toInt()
                val b = (255 - r)
                val color = Color.rgb(r, 0, b)

                paint.color = color

                canvas.drawRect(
                    x * colWidth,
                    y * rowHeight,
                    (x + 1) * colWidth,
                    (y + 1) * rowHeight,
                    paint
                )
            }
        }
    }
}