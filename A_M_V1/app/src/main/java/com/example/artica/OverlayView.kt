package com.example.artica

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
        strokeWidth = 4f
    }

    private val textPaint = Paint().apply {
        color = Color.RED
        textSize = 36f
        style = Paint.Style.FILL
    }

    private var detections: List<FomoDetection> = emptyList()

    fun setResults(newDetections: List<FomoDetection>) {
        detections = newDetections
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cellWidth = width / 12f
        val cellHeight = height / 12f

        for (det in detections) {
            val left = det.cellX * cellWidth
            val top = det.cellY * cellHeight
            val right = left + cellWidth
            val bottom = top + cellHeight

            canvas.drawRect(left, top, right, bottom, paint)
            canvas.drawText(
                "C${det.classId} ${(det.score * 100).toInt()}%",
                left + 4f,
                top + 36f,
                textPaint
            )
        }
    }
}
