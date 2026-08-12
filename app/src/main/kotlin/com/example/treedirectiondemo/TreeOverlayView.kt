package com.example.treedirectiondemo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

class TreeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var deltaDegrees: Double? = null
    private var distanceMeters: Double? = null
    private var gpsQuality: String = "WAITING"
    private var ready = false
    private val horizontalFovDegrees = 62.0

    fun updateTarget(
        deltaDegrees: Double?,
        distanceMeters: Double?,
        gpsQuality: String,
        ready: Boolean
    ) {
        this.deltaDegrees = deltaDegrees
        this.distanceMeters = distanceMeters
        this.gpsQuality = gpsQuality
        this.ready = ready
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val cx = width / 2f
        val cy = height * 0.43f

        drawReticle(canvas, cx, cy)

        val delta = deltaDegrees
        if (!ready || delta == null) {
            drawAcquiring(canvas, cx, cy)
            return
        }

        val halfFov = horizontalFovDegrees / 2.0
        val visible = abs(delta) <= halfFov
        val x = if (visible) {
            (width / 2.0 + (delta / horizontalFovDegrees) * width).toFloat()
        } else if (delta < 0) 44f else width - 44f

        if (visible) drawVisibleTarget(canvas, x, cy, delta)
        else drawEdgeIndicator(canvas, x, cy, delta)
    }

    private fun drawReticle(canvas: Canvas, cx: Float, cy: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = Color.argb(170, 255, 255, 255)
        canvas.drawCircle(cx, cy, 18f, paint)
        canvas.drawLine(cx - 34f, cy, cx - 20f, cy, paint)
        canvas.drawLine(cx + 20f, cy, cx + 34f, cy, paint)
        canvas.drawLine(cx, cy - 34f, cx, cy - 20f, paint)
        canvas.drawLine(cx, cy + 20f, cx, cy + 34f, paint)
    }

    private fun drawVisibleTarget(canvas: Canvas, x: Float, y: Float, delta: Double) {
        val good = gpsQuality == "EXCELLENT" || gpsQuality == "GOOD"
        val accent = if (good) Color.rgb(91, 214, 123) else Color.rgb(255, 193, 7)
        val aligned = abs(delta) <= 3.0

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(210, 10, 18, 14)
        val card = RectF(x - 82f, y - 96f, x + 82f, y + 84f)
        canvas.drawRoundRect(card, 24f, 24f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (aligned) 7f else 5f
        paint.color = accent
        canvas.drawCircle(x, y - 12f, 32f, paint)

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.WHITE
        paint.textSize = 34f
        canvas.drawText("TREE", x, y - 50f, paint)

        paint.textSize = 24f
        paint.color = Color.rgb(220, 230, 224)
        val distanceText = distanceMeters?.let { if (it < 10) String.format("%.1f m", it) else String.format("%.0f m", it) } ?: "-- m"
        canvas.drawText(distanceText, x, y + 48f, paint)

        if (aligned) {
            paint.textSize = 18f
            paint.color = accent
            canvas.drawText("TARGET ALIGNED", x, y + 72f, paint)
        }
    }

    private fun drawEdgeIndicator(canvas: Canvas, x: Float, y: Float, delta: Double) {
        val accent = Color.rgb(91, 214, 123)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(215, 10, 18, 14)
        val left = delta < 0
        val box = if (left) RectF(12f, y - 58f, 158f, y + 58f) else RectF(width - 158f, y - 58f, width - 12f, y + 58f)
        canvas.drawRoundRect(box, 24f, 24f, paint)

        paint.color = accent
        val arrowX = if (left) 42f else width - 42f
        val path = Path()
        if (left) {
            path.moveTo(arrowX - 16f, y)
            path.lineTo(arrowX + 14f, y - 22f)
            path.lineTo(arrowX + 14f, y + 22f)
        } else {
            path.moveTo(arrowX + 16f, y)
            path.lineTo(arrowX - 14f, y - 22f)
            path.lineTo(arrowX - 14f, y + 22f)
        }
        path.close()
        canvas.drawPath(path, paint)

        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.WHITE
        paint.textSize = 18f
        val textX = if (left) 103f else width - 103f
        canvas.drawText("TREE", textX, y - 8f, paint)
        paint.textSize = 16f
        paint.color = Color.rgb(205, 216, 209)
        canvas.drawText("${abs(delta).toInt()}°", textX, y + 20f, paint)
    }

    private fun drawAcquiring(canvas: Canvas, cx: Float, cy: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(190, 10, 18, 14)
        val box = RectF(cx - 140f, cy - 42f, cx + 140f, cy + 42f)
        canvas.drawRoundRect(box, 22f, 22f, paint)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 20f
        paint.color = Color.WHITE
        canvas.drawText("Acquiring location & heading…", cx, cy + 7f, paint)
    }
}
