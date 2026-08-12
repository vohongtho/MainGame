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
import kotlin.math.cos
import kotlin.math.sin

class TreeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var deltaDegrees: Double? = null
    private var distanceMeters: Double? = null
    private var gpsQuality: String = "WAITING"
    private var ready = false
    private var targetArea = false
    private var uncertaintyDegrees = 180.0
    private val horizontalFovDegrees = 62.0

    fun updateTarget(
        deltaDegrees: Double?,
        distanceMeters: Double?,
        gpsQuality: String,
        ready: Boolean,
        targetArea: Boolean,
        uncertaintyDegrees: Double
    ) {
        this.deltaDegrees = deltaDegrees
        this.distanceMeters = distanceMeters
        this.gpsQuality = gpsQuality
        this.ready = ready
        this.targetArea = targetArea
        this.uncertaintyDegrees = uncertaintyDegrees
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val cx = width / 2f
        val targetY = height * 0.41f
        drawReticle(canvas, cx, targetY)
        drawCompassArc(canvas, cx, height * 0.71f)

        if (!ready) {
            drawAcquiring(canvas, cx, targetY)
            return
        }

        if (targetArea) {
            drawTargetArea(canvas, cx, targetY)
            return
        }

        val delta = deltaDegrees ?: return
        val halfFov = horizontalFovDegrees / 2.0
        val visible = abs(delta) <= halfFov
        val x = if (visible) {
            (width / 2.0 + (delta / horizontalFovDegrees) * width).toFloat()
        } else if (delta < 0) 52f else width - 52f

        if (visible) drawVisibleTarget(canvas, x, targetY, delta)
        else drawEdgeIndicator(canvas, x, targetY, delta)
    }

    private fun drawReticle(canvas: Canvas, cx: Float, cy: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.4f
        paint.color = Color.argb(210, 255, 255, 255)
        canvas.drawLine(cx - 31f, cy, cx - 9f, cy, paint)
        canvas.drawLine(cx + 9f, cy, cx + 31f, cy, paint)
        canvas.drawLine(cx, cy - 31f, cx, cy - 9f, paint)
        canvas.drawLine(cx, cy + 9f, cx, cy + 31f, paint)
    }

    private fun accentColor(): Int = when (gpsQuality) {
        "EXCELLENT", "GOOD" -> Color.rgb(76, 220, 102)
        "FAIR" -> Color.rgb(255, 181, 42)
        else -> Color.rgb(244, 83, 75)
    }

    private fun drawVisibleTarget(canvas: Canvas, x: Float, y: Float, delta: Double) {
        val accent = accentColor()
        val aligned = abs(delta) <= 3.0

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(220, 6, 15, 11)
        val card = RectF(x - 82f, y - 105f, x + 82f, y + 97f)
        canvas.drawRoundRect(card, 26f, 26f, paint)

        paint.color = accent
        canvas.drawCircle(x, y - 37f, 26f, paint)

        paint.color = Color.rgb(5, 18, 10)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 27f
        paint.isFakeBoldText = true
        canvas.drawText("♣", x, y - 28f, paint)
        paint.isFakeBoldText = false

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (aligned) 6f else 4f
        paint.color = accent
        canvas.drawOval(RectF(x - 47f, y + 2f, x + 47f, y + 28f), paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = 14f
        canvas.drawText("TARGET TREE", x, y + 52f, paint)

        paint.textSize = 27f
        paint.isFakeBoldText = true
        val distanceText = distanceMeters?.let { if (it < 10) String.format("%.1f m", it) else String.format("%.0f m", it) } ?: "-- m"
        canvas.drawText(distanceText, x, y + 83f, paint)
        paint.isFakeBoldText = false

        if (aligned) {
            paint.textSize = 13f
            paint.color = accent
            canvas.drawText("ON TARGET", x, y - 77f, paint)
        }
    }

    private fun drawTargetArea(canvas: Canvas, cx: Float, cy: Float) {
        val accent = accentColor()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(218, 6, 15, 11)
        canvas.drawRoundRect(RectF(cx - 125f, cy - 93f, cx + 125f, cy + 93f), 28f, 28f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.color = accent
        canvas.drawCircle(cx, cy - 20f, 44f, paint)
        paint.strokeWidth = 2f
        paint.color = Color.argb(130, Color.red(accent), Color.green(accent), Color.blue(accent))
        canvas.drawCircle(cx, cy - 20f, 63f, paint)

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.WHITE
        paint.textSize = 19f
        paint.isFakeBoldText = true
        canvas.drawText("TARGET AREA", cx, cy + 52f, paint)
        paint.isFakeBoldText = false
        paint.textSize = 14f
        paint.color = Color.rgb(207, 227, 214)
        canvas.drawText("GPS precision is limited at close range", cx, cy + 76f, paint)
    }

    private fun drawEdgeIndicator(canvas: Canvas, x: Float, y: Float, delta: Double) {
        val accent = accentColor()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(220, 6, 15, 11)
        val left = delta < 0
        val box = if (left) RectF(12f, y - 62f, 166f, y + 62f) else RectF(width - 166f, y - 62f, width - 12f, y + 62f)
        canvas.drawRoundRect(box, 26f, 26f, paint)

        paint.color = accent
        val arrowX = if (left) 43f else width - 43f
        val path = Path()
        if (left) {
            path.moveTo(arrowX - 18f, y)
            path.lineTo(arrowX + 15f, y - 24f)
            path.lineTo(arrowX + 15f, y + 24f)
        } else {
            path.moveTo(arrowX + 18f, y)
            path.lineTo(arrowX - 15f, y - 24f)
            path.lineTo(arrowX - 15f, y + 24f)
        }
        path.close()
        canvas.drawPath(path, paint)

        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.WHITE
        paint.textSize = 16f
        paint.isFakeBoldText = true
        val textX = if (left) 108f else width - 108f
        canvas.drawText("TREE", textX, y - 8f, paint)
        paint.isFakeBoldText = false
        paint.textSize = 15f
        paint.color = Color.rgb(213, 225, 217)
        val directionText = if (abs(delta) >= 150) "BEHIND" else "${abs(delta).toInt()}°"
        canvas.drawText(directionText, textX, y + 22f, paint)
    }

    private fun drawCompassArc(canvas: Canvas, cx: Float, cy: Float) {
        val radius = width * 0.36f
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(128, 3, 10, 7)
        canvas.drawOval(RectF(cx - radius, cy - radius * 0.46f, cx + radius, cy + radius * 0.46f), paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.argb(165, 255, 255, 255)
        val arc = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(arc, 200f, 140f, false, paint)

        for (i in -6..6) {
            val angle = Math.toRadians((-90 + i * 10).toDouble())
            val outer = radius
            val inner = if (i % 3 == 0) radius - 18f else radius - 10f
            val x1 = cx + cos(angle).toFloat() * inner
            val y1 = cy + sin(angle).toFloat() * inner
            val x2 = cx + cos(angle).toFloat() * outer
            val y2 = cy + sin(angle).toFloat() * outer
            paint.strokeWidth = if (i % 3 == 0) 3f else 1.5f
            canvas.drawLine(x1, y1, x2, y2, paint)
        }

        val delta = deltaDegrees ?: 0.0
        val clamped = delta.coerceIn(-60.0, 60.0)
        val needleAngle = Math.toRadians((-90 + clamped * 0.70).toDouble())
        val needleLength = radius - 25f
        paint.style = Paint.Style.FILL
        paint.color = accentColor()
        val nx = cx + cos(needleAngle).toFloat() * needleLength
        val ny = cy + sin(needleAngle).toFloat() * needleLength
        canvas.drawCircle(nx, ny, 8f, paint)

        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 13f
        paint.color = Color.argb(210, 255, 255, 255)
        canvas.drawText("W", cx - radius + 16f, cy + 6f, paint)
        canvas.drawText("E", cx + radius - 16f, cy + 6f, paint)
    }

    private fun drawAcquiring(canvas: Canvas, cx: Float, cy: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(205, 6, 15, 11)
        val box = RectF(cx - 150f, cy - 46f, cx + 150f, cy + 46f)
        canvas.drawRoundRect(box, 24f, 24f, paint)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 19f
        paint.color = Color.WHITE
        canvas.drawText("Acquiring location & heading…", cx, cy + 7f, paint)
    }
}
