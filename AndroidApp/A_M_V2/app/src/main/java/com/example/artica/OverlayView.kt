package com.example.artica

import DetectionResult
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val textPaint = Paint().apply {
        color = Color.RED
        textSize = 48f
        style = Paint.Style.FILL
    }

    private var results: List<DetectionResult> = emptyList()

    fun setResults(detections: List<DetectionResult>) {
        results = detections
        invalidate()
    }


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        for (det in results) {

            val left = det.x * w
            val top = det.y * h
            val right = (det.x + det.width) * w
            val bottom = (det.y + det.height) * h

            canvas.drawRect(left, top, right, bottom, paint)
            canvas.drawText(
                "${(det.score * 100).toInt()}%",
                left,
                top - 8,
                textPaint
            )
        }
    }

}
