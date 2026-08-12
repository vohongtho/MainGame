package com.example.treedirectiondemo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

class TreeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var deltaDegrees = 0.0
    private var locked = false
    private var targetLabel = "TREE"
    private val horizontalFovDegrees = 60.0

    fun updateDirection(deltaDegrees: Double, locked: Boolean, label: String = "TREE") {
        this.deltaDegrees = deltaDegrees
        this.locked = locked
        this.targetLabel = label
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val cx = width / 2f
        val cy = height * 0.45f

        paint.strokeWidth = 2f
        paint.color = Color.argb(150, 255, 255, 255)
        canvas.drawLine(cx - 28f, cy, cx + 28f, cy, paint)
        canvas.drawLine(cx, cy - 28f, cx, cy + 28f, paint)

        val halfFov = horizontalFovDegrees / 2.0
        val visible = abs(deltaDegrees) <= halfFov
        val x = if (visible) {
            (width / 2.0 + (deltaDegrees / horizontalFovDegrees) * width).toFloat()
        } else if (deltaDegrees < 0) {
            42f
        } else {
            width - 42f
        }

        val markerY = cy
        val markerColor = if (locked) Color.rgb(0, 230, 118) else Color.rgb(255, 193, 7)

        if (visible) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 6f
            paint.color = markerColor
            canvas.drawCircle(x, markerY, 34f, paint)
            canvas.drawLine(x, markerY + 34f, x, markerY + 76f, paint)

            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 34f
            paint.color = Color.WHITE
            canvas.drawText(targetLabel, x, markerY - 50f, paint)

            paint.textSize = 27f
            canvas.drawText(String.format("%+.1f°", deltaDegrees), x, markerY + 112f, paint)
        } else {
            paint.style = Paint.Style.FILL
            paint.color = markerColor
            val path = Path()
            if (deltaDegrees < 0) {
                path.moveTo(x - 18f, markerY)
                path.lineTo(x + 18f, markerY - 25f)
                path.lineTo(x + 18f, markerY + 25f)
            } else {
                path.moveTo(x + 18f, markerY)
                path.lineTo(x - 18f, markerY - 25f)
                path.lineTo(x - 18f, markerY + 25f)
            }
            path.close()
            canvas.drawPath(path, paint)

            paint.textSize = 28f
            paint.textAlign = if (deltaDegrees < 0) Paint.Align.LEFT else Paint.Align.RIGHT
            paint.color = Color.WHITE
            val tx = if (deltaDegrees < 0) 18f else width - 18f
            canvas.drawText(String.format("TREE %+.0f°", deltaDegrees), tx, markerY - 45f, paint)
        }

        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 28f
        paint.color = markerColor
        canvas.drawText(if (locked) "LOCKED" else "LIVE GPS", cx, 150f, paint)
    }
}
