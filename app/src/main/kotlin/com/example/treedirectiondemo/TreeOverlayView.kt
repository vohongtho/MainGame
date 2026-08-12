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

    private var directionDegrees: Double? = null
    private var distanceMeters: Double? = null
    private var gpsQuality = "WAITING"
    private var ready = false
    private var arTracking = false
    private var arScreenX: Float? = null
    private var arScreenY: Float? = null
    private var arInFront = false
    private var showDistance = true
    private var showGuidance = true

    private var smoothX: Float? = null
    private var smoothY: Float? = null

    fun updateTarget(
        directionDegrees: Double?,
        distanceMeters: Double?,
        gpsQuality: String,
        ready: Boolean,
        arTracking: Boolean,
        arScreenX: Float?,
        arScreenY: Float?,
        arInFront: Boolean,
        showDistance: Boolean,
        showGuidance: Boolean
    ) {
        this.directionDegrees = directionDegrees
        this.distanceMeters = distanceMeters
        this.gpsQuality = gpsQuality
        this.ready = ready
        this.arTracking = arTracking
        this.arScreenX = arScreenX
        this.arScreenY = arScreenY
        this.arInFront = arInFront
        this.showDistance = showDistance
        this.showGuidance = showGuidance
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val cx = width / 2f
        val reticleY = height * 0.43f
        drawReticle(canvas, cx, reticleY)
        drawCompassArc(canvas, cx, height * 0.77f)

        if (!ready) {
            drawAcquiring(canvas, cx, reticleY)
            return
        }

        if (arTracking) {
            drawArTarget(canvas, cx, reticleY)
        } else {
            drawFallbackTarget(canvas, cx, reticleY)
        }
    }

    private fun drawArTarget(canvas: Canvas, cx: Float, cy: Float) {
        val rawX = arScreenX
        val rawY = arScreenY
        if (!arInFront || rawX == null || rawY == null) {
            drawBehindIndicator(canvas, cx, cy)
            return
        }

        // ARCore already performs visual-inertial smoothing. Keep only a tiny display deadband so
        // sub-pixel pose noise does not shimmer, without introducing the lag of GPS-style filters.
        val targetX = rawX * width
        val targetY = rawY * height
        val previousX = smoothX
        val previousY = smoothY
        smoothX = if (previousX == null || abs(targetX - previousX) > 2.5f) {
            if (previousX == null) targetX else previousX + (targetX - previousX) * 0.72f
        } else previousX
        smoothY = if (previousY == null || abs(targetY - previousY) > 2.5f) {
            if (previousY == null) targetY else previousY + (targetY - previousY) * 0.72f
        } else previousY

        val x = smoothX ?: targetX
        val y = smoothY ?: targetY
        val visible = x in -40f..(width + 40f) && y in 120f..(height * 0.70f)
        if (visible) {
            drawVisibleTarget(canvas, x.coerceIn(90f, width - 90f), y.coerceIn(190f, height * 0.66f))
        } else {
            drawEdgeIndicator(
                canvas,
                x.coerceIn(58f, width - 58f),
                y.coerceIn(180f, height * 0.66f)
            )
        }
    }

    private fun drawFallbackTarget(canvas: Canvas, cx: Float, cy: Float) {
        val delta = directionDegrees ?: return
        if (abs(delta) >= 120.0) {
            drawBehindIndicator(canvas, cx, cy)
            return
        }
        val halfFov = 31.0
        val visible = abs(delta) <= halfFov
        val x = if (visible) {
            (cx + (delta / (halfFov * 2.0)) * width).toFloat()
        } else if (delta < 0) 58f else width - 58f
        if (visible) drawVisibleTarget(canvas, x, cy)
        else drawEdgeIndicator(canvas, x, cy)
    }

    private fun accentColor(): Int = when (gpsQuality) {
        "EXCELLENT", "GOOD" -> Color.rgb(78, 219, 90)
        "FAIR" -> Color.rgb(255, 180, 38)
        else -> Color.rgb(244, 73, 65)
    }

    private fun drawReticle(canvas: Canvas, cx: Float, cy: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.7f
        paint.color = Color.argb(225, 255, 255, 255)
        canvas.drawLine(cx - 33f, cy, cx - 9f, cy, paint)
        canvas.drawLine(cx + 9f, cy, cx + 33f, cy, paint)
        canvas.drawLine(cx, cy - 33f, cx, cy - 9f, paint)
        canvas.drawLine(cx, cy + 9f, cx, cy + 33f, paint)
    }

    private fun drawVisibleTarget(canvas: Canvas, x: Float, y: Float) {
        val accent = accentColor()

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(220, 5, 12, 8)
        canvas.drawRoundRect(RectF(x - 74f, y - 110f, x + 74f, y + 98f), 26f, 26f, paint)

        // Map-pin silhouette from the approved mockup.
        paint.color = accent
        val pin = Path().apply {
            moveTo(x, y - 108f)
            cubicTo(x - 36f, y - 108f, x - 42f, y - 58f, x, y - 22f)
            cubicTo(x + 42f, y - 58f, x + 36f, y - 108f, x, y - 108f)
            close()
        }
        canvas.drawPath(pin, paint)
        paint.color = Color.rgb(4, 15, 8)
        canvas.drawCircle(x, y - 78f, 14f, paint)
        paint.color = accent
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        canvas.drawOval(RectF(x - 48f, y - 16f, x + 48f, y + 10f), paint)

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.WHITE
        paint.textSize = 13f
        paint.isFakeBoldText = true
        canvas.drawText("TARGET TREE", x, y + 39f, paint)
        if (showDistance) {
            paint.textSize = 29f
            canvas.drawText(distanceText(), x, y + 76f, paint)
        }
        paint.isFakeBoldText = false
    }

    private fun drawEdgeIndicator(canvas: Canvas, x: Float, y: Float) {
        val accent = accentColor()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(222, 4, 12, 8)
        canvas.drawRoundRect(RectF(x - 56f, y - 45f, x + 56f, y + 45f), 22f, 22f, paint)
        paint.color = accent

        val horizontal = x < 70f || x > width - 70f
        val path = Path()
        if (horizontal) {
            if (x < width / 2f) {
                path.moveTo(x - 30f, y)
                path.lineTo(x - 4f, y - 19f)
                path.lineTo(x - 4f, y + 19f)
            } else {
                path.moveTo(x + 30f, y)
                path.lineTo(x + 4f, y - 19f)
                path.lineTo(x + 4f, y + 19f)
            }
        } else if (y < height / 2f) {
            path.moveTo(x, y - 27f)
            path.lineTo(x - 19f, y)
            path.lineTo(x + 19f, y)
        } else {
            path.moveTo(x, y + 27f)
            path.lineTo(x - 19f, y)
            path.lineTo(x + 19f, y)
        }
        path.close()
        canvas.drawPath(path, paint)

        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.WHITE
        paint.textSize = 12f
        paint.isFakeBoldText = true
        canvas.drawText("TREE", x, y + 37f, paint)
        paint.isFakeBoldText = false
    }

    private fun drawBehindIndicator(canvas: Canvas, cx: Float, cy: Float) {
        if (!showGuidance) return
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(222, 5, 12, 8)
        canvas.drawRoundRect(RectF(cx - 135f, cy - 64f, cx + 135f, cy + 64f), 26f, 26f, paint)
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.rgb(244, 73, 65)
        paint.textSize = 14f
        paint.isFakeBoldText = true
        canvas.drawText("TREE IS BEHIND YOU", cx, cy - 8f, paint)
        paint.textSize = 30f
        canvas.drawText("TURN AROUND", cx, cy + 31f, paint)
        paint.isFakeBoldText = false
    }

    private fun drawCompassArc(canvas: Canvas, cx: Float, cy: Float) {
        val radius = width * 0.39f
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(138, 0, 7, 4)
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

        val delta = directionDegrees ?: 0.0
        val clamped = delta.coerceIn(-60.0, 60.0)
        val needleAngle = Math.toRadians((-90 + clamped * 0.72).toDouble())
        paint.style = Paint.Style.FILL
        paint.color = if (arTracking && !arInFront) Color.rgb(244, 73, 65) else accentColor()
        val nx = cx + cos(needleAngle).toFloat() * (radius - 23f)
        val ny = cy + sin(needleAngle).toFloat() * (radius - 23f)
        val arrow = Path().apply {
            moveTo(nx, ny - 14f)
            lineTo(nx - 9f, ny + 10f)
            lineTo(nx + 9f, ny + 10f)
            close()
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
        paint.color = Color.argb(210, 4, 12, 8)
        canvas.drawRoundRect(RectF(cx - 154f, cy - 46f, cx + 154f, cy + 46f), 24f, 24f, paint)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 18f
        paint.color = Color.WHITE
        canvas.drawText("Acquiring AR tracking…", cx, cy + 7f, paint)
    }

    private fun distanceText(): String = distanceMeters?.let {
        if (it < 10) String.format("%.1f m", it) else String.format("%.0f m", it)
    } ?: "-- m"
}
