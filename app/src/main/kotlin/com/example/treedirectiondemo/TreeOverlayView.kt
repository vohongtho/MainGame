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
import kotlin.math.tan

class TreeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var horizontalDegrees: Double? = null
    private var verticalDegrees: Double? = null
    private var distanceMeters: Double? = null
    private var gpsQuality = "WAITING"
    private var ready = false
    private var targetArea = false
    private var uncertaintyDegrees = 180.0
    private var behind = false

    private val horizontalFovDegrees = 62.0
    private val verticalFovDegrees = 46.0

    fun updateTarget(
        horizontalDegrees: Double?,
        verticalDegrees: Double?,
        distanceMeters: Double?,
        gpsQuality: String,
        ready: Boolean,
        targetArea: Boolean,
        uncertaintyDegrees: Double,
        behind: Boolean
    ) {
        this.horizontalDegrees = horizontalDegrees
        this.verticalDegrees = verticalDegrees
        this.distanceMeters = distanceMeters
        this.gpsQuality = gpsQuality
        this.ready = ready
        this.targetArea = targetArea
        this.uncertaintyDegrees = uncertaintyDegrees
        this.behind = behind
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val cx = width / 2f
        val cy = height * 0.43f
        drawReticle(canvas, cx, cy)
        drawCompassArc(canvas, cx, height * 0.77f)

        if (!ready) {
            drawAcquiring(canvas, cx, cy)
            return
        }
        if (targetArea) {
            drawTargetArea(canvas, cx, cy)
            return
        }

        val h = horizontalDegrees ?: return
        val v = verticalDegrees ?: 0.0
        val halfHFov = horizontalFovDegrees / 2.0
        val halfVFov = verticalFovDegrees / 2.0

        if (behind || abs(h) > 95.0) {
            drawBehindIndicator(canvas, cx, cy, h)
            return
        }

        val visible = abs(h) <= halfHFov && abs(v) <= halfVFov
        val xNorm = tan(Math.toRadians(h)) / tan(Math.toRadians(halfHFov))
        val yNorm = tan(Math.toRadians(v)) / tan(Math.toRadians(halfVFov))
        val x = (cx + xNorm * cx).toFloat()
        val usableHalfHeight = height * 0.31f
        val y = (cy - yNorm * usableHalfHeight).toFloat()

        if (visible) drawVisibleTarget(canvas, x, y, h, v)
        else drawEdgeIndicator(canvas, x.coerceIn(52f, width - 52f), y.coerceIn(180f, height * 0.68f), h, v)
    }

    private fun accentColor(): Int = when (gpsQuality) {
        "EXCELLENT", "GOOD" -> Color.rgb(70, 220, 95)
        "FAIR" -> Color.rgb(255, 177, 38)
        else -> Color.rgb(244, 73, 65)
    }

    private fun drawReticle(canvas: Canvas, cx: Float, cy: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.8f
        paint.color = Color.argb(225, 255, 255, 255)
        canvas.drawLine(cx - 32f, cy, cx - 8f, cy, paint)
        canvas.drawLine(cx + 8f, cy, cx + 32f, cy, paint)
        canvas.drawLine(cx, cy - 32f, cx, cy - 8f, paint)
        canvas.drawLine(cx, cy + 8f, cx, cy + 32f, paint)
    }

    private fun drawVisibleTarget(canvas: Canvas, x: Float, y: Float, h: Double, v: Double) {
        val accent = accentColor()
        val aligned = abs(h) <= 3.0 && abs(v) <= 3.0

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(218, 4, 12, 8)
        canvas.drawRoundRect(RectF(x - 76f, y - 102f, x + 76f, y + 100f), 26f, 26f, paint)

        // Pin
        paint.color = accent
        val pin = Path().apply {
            moveTo(x, y - 98f)
            cubicTo(x - 36f, y - 98f, x - 42f, y - 48f, x, y - 18f)
            cubicTo(x + 42f, y - 48f, x + 36f, y - 98f, x, y - 98f)
            close()
        }
        canvas.drawPath(pin, paint)
        paint.color = Color.rgb(7, 18, 10)
        canvas.drawCircle(x, y - 70f, 15f, paint)
        paint.color = accent
        canvas.drawCircle(x, y - 73f, 7f, paint)
        paint.strokeWidth = 5f
        paint.style = Paint.Style.STROKE
        canvas.drawOval(RectF(x - 48f, y - 15f, x + 48f, y + 11f), paint)

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.WHITE
        paint.textSize = 14f
        paint.isFakeBoldText = true
        canvas.drawText("TARGET TREE", x, y + 39f, paint)
        paint.textSize = 29f
        canvas.drawText(distanceText(), x, y + 76f, paint)
        paint.isFakeBoldText = false

        if (aligned) {
            paint.textSize = 12f
            paint.color = accent
            paint.isFakeBoldText = true
            canvas.drawText("ON TARGET", x, y + 96f, paint)
            paint.isFakeBoldText = false
        }
    }

    private fun drawEdgeIndicator(canvas: Canvas, x: Float, y: Float, h: Double, v: Double) {
        val accent = accentColor()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(220, 4, 12, 8)
        canvas.drawRoundRect(RectF(x - 58f, y - 44f, x + 58f, y + 44f), 22f, 22f, paint)

        paint.color = accent
        val path = Path()
        if (abs(h) > horizontalFovDegrees / 2) {
            if (h < 0) {
                path.moveTo(x - 34f, y); path.lineTo(x - 8f, y - 20f); path.lineTo(x - 8f, y + 20f)
            } else {
                path.moveTo(x + 34f, y); path.lineTo(x + 8f, y - 20f); path.lineTo(x + 8f, y + 20f)
            }
        } else if (v > 0) {
            path.moveTo(x, y - 28f); path.lineTo(x - 20f, y); path.lineTo(x + 20f, y)
        } else {
            path.moveTo(x, y + 28f); path.lineTo(x - 20f, y); path.lineTo(x + 20f, y)
        }
        path.close(); canvas.drawPath(path, paint)

        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.WHITE
        paint.textSize = 13f
        paint.isFakeBoldText = true
        canvas.drawText("TREE", x, y + 36f, paint)
        paint.isFakeBoldText = false
    }

    private fun drawBehindIndicator(canvas: Canvas, cx: Float, cy: Float, h: Double) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(220, 4, 12, 8)
        canvas.drawRoundRect(RectF(cx - 132f, cy - 63f, cx + 132f, cy + 63f), 26f, 26f, paint)
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.rgb(244, 73, 65)
        paint.textSize = 14f
        paint.isFakeBoldText = true
        canvas.drawText("TREE IS BEHIND YOU", cx, cy - 10f, paint)
        paint.textSize = 32f
        canvas.drawText("${abs(h).coerceAtMost(180.0).toInt()}°", cx, cy + 31f, paint)
        paint.isFakeBoldText = false
    }

    private fun drawTargetArea(canvas: Canvas, cx: Float, cy: Float) {
        val accent = accentColor()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(218, 4, 12, 8)
        canvas.drawRoundRect(RectF(cx - 132f, cy - 94f, cx + 132f, cy + 94f), 28f, 28f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.color = accent
        canvas.drawCircle(cx, cy - 23f, 43f, paint)
        paint.strokeWidth = 2f
        paint.color = Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent))
        canvas.drawCircle(cx, cy - 23f, 62f, paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.WHITE
        paint.textSize = 19f
        paint.isFakeBoldText = true
        canvas.drawText("TARGET AREA", cx, cy + 48f, paint)
        paint.isFakeBoldText = false
        paint.textSize = 13f
        paint.color = Color.rgb(205, 225, 212)
        canvas.drawText("GPS precision limited at close range", cx, cy + 73f, paint)
    }

    private fun drawCompassArc(canvas: Canvas, cx: Float, cy: Float) {
        val radius = width * 0.39f
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(132, 0, 7, 4)
        canvas.drawOval(RectF(cx - radius, cy - radius * 0.49f, cx + radius, cy + radius * 0.49f), paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.argb(205, 255, 255, 255)
        val arc = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(arc, 200f, 140f, false, paint)
        for (i in -7..7) {
            val angle = Math.toRadians((-90 + i * 9).toDouble())
            val outer = radius
            val inner = if (i % 3 == 0) radius - 20f else radius - 10f
            canvas.drawLine(
                cx + cos(angle).toFloat() * inner,
                cy + sin(angle).toFloat() * inner,
                cx + cos(angle).toFloat() * outer,
                cy + sin(angle).toFloat() * outer,
                paint
            )
        }

        val h = horizontalDegrees ?: 0.0
        val clamped = h.coerceIn(-60.0, 60.0)
        val needleAngle = Math.toRadians((-90 + clamped * 0.72).toDouble())
        paint.style = Paint.Style.FILL
        paint.color = if (behind) Color.rgb(244, 73, 65) else accentColor()
        val nx = cx + cos(needleAngle).toFloat() * (radius - 23f)
        val ny = cy + sin(needleAngle).toFloat() * (radius - 23f)
        val arrow = Path().apply {
            moveTo(nx, ny - 14f); lineTo(nx - 9f, ny + 10f); lineTo(nx + 9f, ny + 10f); close()
        }
        canvas.drawPath(arrow, paint)

        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.WHITE
        paint.textSize = 13f
        canvas.drawText("W", cx - radius + 14f, cy + 6f, paint)
        canvas.drawText("E", cx + radius - 14f, cy + 6f, paint)
    }

    private fun drawAcquiring(canvas: Canvas, cx: Float, cy: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(205, 4, 12, 8)
        canvas.drawRoundRect(RectF(cx - 152f, cy - 46f, cx + 152f, cy + 46f), 24f, 24f, paint)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 18f
        paint.color = Color.WHITE
        canvas.drawText("Acquiring location & heading…", cx, cy + 7f, paint)
    }

    private fun distanceText(): String = distanceMeters?.let {
        if (it < 10) String.format("%.1f m", it) else String.format("%.0f m", it)
    } ?: "-- m"
}
