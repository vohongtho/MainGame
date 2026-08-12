package com.example.treedirectiondemo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reference-driven UI renderer based on the approved 259 x 580 camera panel.
 * The UI never invents a target projection: the tree pin is drawn only when ARCore supplies
 * real screen coordinates for the world anchor.
 */
class TreeNavigatorUiView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Screen { HOME, CREATE, SETTINGS, DIAGNOSTICS, CALIBRATION, GUIDE, ABOUT, MENU }

    enum class Action {
        OPEN_MENU, OPEN_SETTINGS, OPEN_CREATE, OPEN_DIAGNOSTICS, OPEN_CALIBRATION,
        OPEN_GUIDE, OPEN_ABOUT, GO_HOME, BACK, EXIT, CREATE_TARGET,
        CYCLE_DISTANCE, CYCLE_ELEVATION, TOGGLE_DISTANCE, TOGGLE_GUIDANCE,
        CYCLE_HEADING_SMOOTHING, CYCLE_GPS_SMOOTHING, TOGGLE_DECLINATION
    }

    data class Model(
        val gpsQuality: String = "WAITING",
        val gpsAccuracy: Float = Float.MAX_VALUE,
        val headingQuality: String = "WAITING",
        val headingAccuracyDeg: Double = 99.0,
        val targetDistanceM: Double? = null,
        val gpsDistanceM: Double? = null,
        val directionDeltaDeg: Double? = null,
        val targetScreenX: Float? = null,
        val targetScreenY: Float? = null,
        val targetInFront: Boolean = true,
        val targetReady: Boolean = false,
        val targetRequested: Boolean = false,
        val arTracking: Boolean = false,
        val arMovementM: Double? = null,
        val arFailureReason: String = "NONE",
        val selectedDistanceM: Int = 35,
        val elevationOffsetM: Int = 0,
        val showDistance: Boolean = true,
        val showGuidance: Boolean = true,
        val headingSmoothing: String = "Balanced",
        val gpsSmoothing: String = "High",
        val declinationEnabled: Boolean = true,
        val gameYaw: Double? = null,
        val magneticHeading: Double? = null,
        val trueHeading: Double? = null,
        val filteredHeading: Double? = null,
        val turnSpeed: Double = 0.0,
        val gpsBearing: Double? = null,
        val filteredBearing: Double? = null,
        val phoneLat: Double? = null,
        val phoneLng: Double? = null,
        val treeLat: Double? = null,
        val treeLng: Double? = null,
        val arStatus: String = "ACQUIRING"
    )

    var onAction: ((Action) -> Unit)? = null

    var screen: Screen = Screen.HOME
        set(value) {
            field = value
            invalidate()
        }

    var model: Model = Model()
        set(value) {
            field = value
            invalidate()
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val W = 259f
    private val H = 580f

    private val green = Color.rgb(75, 218, 54)
    private val orange = Color.rgb(255, 164, 0)
    private val red = Color.rgb(255, 46, 46)
    private val white = Color.rgb(248, 250, 248)
    private val muted = Color.rgb(193, 202, 196)
    private val panel = Color.rgb(10, 16, 13)
    private val card = Color.rgb(18, 25, 21)
    private val stroke = Color.rgb(46, 56, 50)
    private val topChip = Color.argb(225, 27, 35, 31)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(width / W, height / H)
        when (screen) {
            Screen.HOME -> drawHome(canvas)
            Screen.CREATE -> drawCreate(canvas)
            Screen.SETTINGS -> drawSettings(canvas)
            Screen.DIAGNOSTICS -> drawDiagnostics(canvas)
            Screen.CALIBRATION -> drawCalibrationCamera(canvas)
            Screen.GUIDE -> drawGuide(canvas)
            Screen.ABOUT -> drawAbout(canvas)
            Screen.MENU -> drawMenu(canvas)
        }
        canvas.restore()
    }

    // -----------------------------------------------------------------------------------------
    // HOME / CAMERA
    // -----------------------------------------------------------------------------------------

    private fun drawHome(c: Canvas) {
        drawTopBar(c)

        val gpsPoor = model.gpsAccuracy.isFinite() && model.gpsAccuracy >= 15f
        if (gpsPoor && !model.targetReady) {
            drawGpsPoorBody(c)
            return
        }

        crosshair(c, 129.5f, 244f)

        val delta = model.directionDeltaDeg ?: 0.0
        val behind = model.targetReady && model.arTracking && !model.targetInFront
        val accent = when {
            behind -> red
            abs(delta) >= 22.0 -> orange
            else -> green
        }

        // Critical rule: no AR projection -> no tree marker. Never use a default screen position.
        if (model.targetReady && model.arTracking && model.targetInFront) {
            val sx = model.targetScreenX
            val sy = model.targetScreenY
            if (sx != null && sy != null && sx in 0f..1f && sy in 0f..1f) {
                drawTarget(c, sx * W, sy * H, accent)
            }
        } else if (model.targetRequested && !model.targetReady) {
            drawLockingState(c)
        }

        drawCompass(c, delta, accent, behind)
        bottomCta(c)
    }

    private fun drawTopBar(c: Canvas) {
        circleButton(c, 27f, 27f)
        drawMenuIcon(c, 27f, 27f)
        circleButton(c, 241f, 27f)
        drawGearIcon(c, 241f, 27f)
        statusChip(c, 25f, 58f, 128f, 96f, gps = true)
        statusChip(c, 142f, 58f, 249f, 96f, gps = false)
    }

    private fun statusChip(c: Canvas, l: Float, t: Float, r: Float, b: Float, gps: Boolean) {
        roundRect(c, l, t, r, b, 8f, topChip)
        val bad = if (gps) model.gpsAccuracy.isFinite() && model.gpsAccuracy >= 15f else model.headingQuality == "CALIBRATE"
        val accent = if (bad) orange else green
        if (gps) drawGpsPin(c, l + 18f, (t + b) / 2f, accent, 8f)
        else drawHeadingTarget(c, l + 18f, (t + b) / 2f, accent, 8f)

        val title = when {
            gps && bad -> "GPS POOR"
            gps -> "GPS GOOD"
            bad -> "HEADING"
            else -> "HEADING GOOD"
        }
        text(c, title, l + 34f, t + 14f, 7.4f, white, true, Paint.Align.LEFT)

        val value = if (gps) {
            if (model.gpsAccuracy.isFinite()) "±${String.format("%.1f", model.gpsAccuracy)} m" else "--"
        } else if (bad) {
            "CALIBRATE"
        } else {
            "±${String.format("%.1f", model.headingAccuracyDeg)}°"
        }
        text(c, value, l + 34f, t + 30f, if (!gps && bad) 9.2f else 13.0f, accent, true, Paint.Align.LEFT)
    }

    private fun drawTarget(c: Canvas, x: Float, y: Float, accent: Int) {
        // The tree marker follows raw AR screen projection. It is not clamped into a "safe" area.
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.0f
        p.color = accent
        c.drawOval(RectF(x - 30f, y + 15f, x + 30f, y + 27f), p)

        drawTreePin(c, x, y - 7f, accent, 19f)

        val cardTop = y + 27f
        roundRect(c, x - 44f, cardTop, x + 44f, cardTop + 48f, 8f, Color.argb(205, 5, 10, 7))
        text(c, "TARGET TREE", x, cardTop + 18f, 7.9f, white, true)
        if (model.showDistance) {
            text(c, distanceText(model.targetDistanceM), x, cardTop + 39f, 19f, white, true)
        }
    }

    private fun drawLockingState(c: Canvas) {
        roundRect(c, 75f, 279f, 184f, 317f, 19f, Color.argb(205, 8, 13, 10))
        drawHeadingTarget(c, 91f, 298f, green, 7f)
        text(c, "LOCKING TARGET", 104f, 302f, 8.3f, white, true, Paint.Align.LEFT)
    }

    private fun drawCompass(c: Canvas, delta: Double, accent: Int, behind: Boolean) {
        val cx = 129.5f
        val cy = 507f
        val radius = 111f

        p.style = Paint.Style.FILL
        p.color = Color.argb(145, 0, 7, 3)
        c.drawCircle(cx, cy, radius, p)

        p.style = Paint.Style.STROKE
        p.color = Color.WHITE
        p.strokeWidth = 1.4f
        c.drawArc(RectF(cx-radius, cy-radius, cx+radius, cy+radius), 200f, 140f, false, p)

        for (i in 0..28) {
            val angle = Math.toRadians(200.0 + i * 5.0)
            val longTick = i % 5 == 0
            val r1 = radius - if (longTick) 12f else 7f
            p.strokeWidth = if (longTick) 2f else 1f
            c.drawLine(
                cx + cos(angle).toFloat() * r1,
                cy + sin(angle).toFloat() * r1,
                cx + cos(angle).toFloat() * radius,
                cy + sin(angle).toFloat() * radius,
                p
            )
        }

        text(c, "W", 26f, 478f, 10f, white, true)
        text(c, "E", 241f, 478f, 10f, white, true)

        val clamped = delta.coerceIn(-60.0, 60.0)
        val needleAngle = Math.toRadians(-90.0 + clamped * 0.68)
        val nx = cx + cos(needleAngle).toFloat() * 80f
        val ny = cy + sin(needleAngle).toFloat() * 80f
        drawCompassNeedle(c, nx, ny, needleAngle, accent)

        if (model.showGuidance) {
            val label = when {
                !model.targetReady && model.targetRequested -> "Locking AR target"
                behind -> "Tree is behind you"
                abs(delta) <= 3.0 -> "Tree is ahead"
                delta < 0 -> "Turn left"
                else -> "Turn right"
            }
            text(c, label, cx, 478f, 11.5f, accent, true)
            val degreeText = if (model.targetReady) "${abs(delta).coerceAtMost(180.0).toInt()}°" else "--"
            text(c, degreeText, cx, 508f, 26f, accent, false)
        }
    }

    private fun bottomCta(c: Canvas) {
        roundRect(c, 15f, 538f, 244f, 574f, 18f, Color.argb(238, 37, 44, 41))
        drawTreeIcon(c, 38f, 557f, green, 8f)
        text(c, "Place test tree ${model.selectedDistanceM} m ahead", 133f, 561f, 10.2f, white, false)
    }

    private fun drawGpsPoorBody(c: Canvas) {
        p.style = Paint.Style.FILL
        p.color = Color.argb(72, 0, 0, 0)
        c.drawRect(0f, 105f, W, H, p)
        crosshair(c, 129.5f, 224f)

        p.color = Color.argb(55, 0, 0, 0)
        c.drawCircle(129.5f, 263f, 54f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = orange
        c.drawOval(RectF(102f, 249f, 157f, 277f), p)
        text(c, "--", 129.5f, 267f, 13f, orange, true)

        roundRect(c, 17f, 395f, 242f, 459f, 7f, Color.argb(230, 29, 27, 11))
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = orange
        c.drawRoundRect(RectF(17f, 395f, 242f, 459f), 7f, 7f, p)
        drawWarningIcon(c, 31f, 414f, orange)
        text(c, "GPS accuracy is limited", 44f, 417f, 9.3f, white, true, Paint.Align.LEFT)
        text(c, "Move to an open area with a clearer view of the sky.", 29f, 439f, 7.8f, muted, false, Paint.Align.LEFT)
        bottomCta(c)
    }

    private fun drawCalibrationCamera(c: Canvas) {
        drawTopBar(c)
        p.style = Paint.Style.FILL
        p.color = Color.argb(88, 0, 0, 0)
        c.drawRect(0f, 105f, W, H, p)

        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.color = Color.rgb(74, 81, 76)
        c.drawCircle(129.5f, 253f, 69f, p)
        p.color = green
        c.drawArc(RectF(60.5f, 184f, 198.5f, 322f), -90f, 105f, false, p)
        drawPhoneIcon(c, 129.5f, 253f, white)

        text(c, "Move your phone in a figure-eight pattern", 129.5f, 391f, 10.1f, white, true)
        text(c, "Keep the phone level and move slowly.", 129.5f, 415f, 8.4f, muted, false)
        outlineButton(c, 15f, 538f, 244f, 574f, "CANCEL")
    }

    // -----------------------------------------------------------------------------------------
    // CREATE / OPTIONS / DETAILS
    // -----------------------------------------------------------------------------------------

    private fun drawCreate(c: Canvas) {
        panelBackground(c)
        header(c, "Create test tree")
        text(c, "Creates a target tree ${model.selectedDistanceM} meters away", 39f, 87f, 9.4f, muted, false, Paint.Align.LEFT)
        text(c, "in the direction you are currently looking.", 39f, 104f, 9.4f, muted, false, Paint.Align.LEFT)

        drawPersonIcon(c, 47f, 191f)
        drawTreeIcon(c, 211f, 185f, green, 20f)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f
        p.color = muted
        c.drawLine(64f, 198f, 193f, 198f, p)
        c.drawLine(64f, 194f, 64f, 202f, p)
        c.drawLine(193f, 194f, 193f, 202f, p)
        text(c, "${model.selectedDistanceM} m", 129.5f, 188f, 12f, white, true)

        row(c, 15f, 258f, 244f, 308f, "Distance", "${model.selectedDistanceM} m", true)
        row(c, 15f, 309f, 244f, 359f, "Direction", "Current view", true)
        row(c, 15f, 360f, 244f, 410f, "Elevation offset", "${model.elevationOffsetM} m", true)
        primaryButton(c, 15f, 492f, 244f, 536f, "CREATE NOW")
    }

    private fun drawSettings(c: Canvas) {
        panelBackground(c)
        header(c, "Options")

        sectionLabel(c, "DISPLAY", 93f)
        toggleRow(c, 15f, 104f, "Show distance", model.showDistance)
        toggleRow(c, 15f, 142f, "Show direction guidance", model.showGuidance)
        row(c, 15f, 180f, 244f, 218f, "Distance unit", "Meters (m)", true)
        row(c, 15f, 218f, 244f, 256f, "Theme", "Dark", true)

        sectionLabel(c, "FILTERING & STABILITY", 282f)
        row(c, 15f, 293f, 244f, 334f, "Heading smoothing", model.headingSmoothing, true)
        row(c, 15f, 334f, 244f, 375f, "GPS position smoothing", model.gpsSmoothing, true)
        toggleRow(c, 15f, 375f, "Magnetic declination", model.declinationEnabled)

        sectionLabel(c, "OTHER", 429f)
        row(c, 15f, 441f, 244f, 479f, "Calibrate compass", "", true)
        row(c, 15f, 479f, 244f, 517f, "User guide", "", true)
        row(c, 15f, 517f, 244f, 555f, "About", "", true)
    }

    private fun drawDiagnostics(c: Canvas) {
        panelBackground(c)
        header(c, "Details (Diagnostic)")

        var y = 72f
        diagnosticRow(c, y, "AR status", model.arStatus); y += 26f
        diagnosticRow(c, y, "AR distance (m)", fmt(model.targetDistanceM)); y += 26f
        diagnosticRow(c, y, "AR movement since lock (m)", fmt(model.arMovementM)); y += 26f
        diagnosticRow(c, y, "AR projected on screen", if (model.targetScreenX != null && model.targetScreenY != null) "YES" else "NO"); y += 26f
        diagnosticRow(c, y, "AR failure reason", model.arFailureReason); y += 34f

        diagnosticRow(c, y, "GPS distance (m)", fmt(model.gpsDistanceM)); y += 26f
        diagnosticRow(c, y, "GPS quality", "${model.gpsQuality} ${accuracyText()}"); y += 26f
        diagnosticRow(c, y, "GPS bearing (°)", fmt(model.gpsBearing)); y += 26f
        diagnosticRow(c, y, "Filtered bearing (°)", fmt(model.filteredBearing)); y += 34f

        diagnosticRow(c, y, "True heading (°)", fmt(model.trueHeading)); y += 26f
        diagnosticRow(c, y, "Filtered heading (°)", fmt(model.filteredHeading)); y += 26f
        diagnosticRow(c, y, "Turn speed (°/s)", String.format("%.1f", model.turnSpeed)); y += 34f

        diagnosticTwoLine(c, y, "Your position", coord(model.phoneLat, model.phoneLng)); y += 45f
        diagnosticTwoLine(c, y, "Target tree (fixed)", coord(model.treeLat, model.treeLng))
        outlineButton(c, 15f, 538f, 244f, 574f, "CALIBRATE COMPASS", green)
    }

    private fun drawMenu(c: Canvas) {
        panelBackground(c)
        drawTreeIcon(c, 28f, 58f, green, 9f)
        text(c, "TREE NAVIGATOR", 47f, 62f, 14f, white, true, Paint.Align.LEFT)

        menuItem(c, 87f, "home", "Home", true)
        menuItem(c, 126f, "details", "Details", false)
        menuItem(c, 165f, "tree", "Create test tree", false)
        menuItem(c, 204f, "settings", "Options", false)
        menuItem(c, 243f, "compass", "Calibrate compass", false)
        menuItem(c, 282f, "guide", "User guide", false)
        menuItem(c, 321f, "info", "About", false)

        p.style = Paint.Style.STROKE
        p.color = stroke
        p.strokeWidth = 1f
        c.drawLine(15f, 514f, 244f, 514f, p)
        drawExitIcon(c, 28f, 548f, red)
        text(c, "Exit", 50f, 552f, 12f, red, false, Paint.Align.LEFT)
    }

    private fun drawGuide(c: Canvas) {
        panelBackground(c)
        header(c, "User guide")
        guideStep(c, "1", "Wait until GPS and heading show GOOD.", 104f)
        guideStep(c, "2", "Point the camera in the target direction.", 168f)
        guideStep(c, "3", "Create a target. ARCore locks it in world space.", 232f)
        guideStep(c, "4", "Walk past it, then turn around to find it again.", 296f)

        roundRect(c, 15f, 365f, 244f, 447f, 8f, card)
        text(c, "Tip", 28f, 389f, 11f, green, true, Paint.Align.LEFT)
        text(c, "Once world-locked, GPS does not move the marker.", 28f, 414f, 8.4f, muted, false, Paint.Align.LEFT)
        text(c, "GPS remains a global reference and recovery signal.", 28f, 432f, 8.4f, muted, false, Paint.Align.LEFT)
        outlineButton(c, 15f, 538f, 244f, 574f, "DONE")
    }

    private fun drawAbout(c: Canvas) {
        panelBackground(c)
        header(c, "About")
        drawTreeIcon(c, 129.5f, 148f, green, 30f)
        text(c, "TREE NAVIGATOR", 129.5f, 201f, 21f, white, true)
        text(c, "AR navigation for fixed tree targets", 129.5f, 226f, 10f, muted, false)

        roundRect(c, 15f, 270f, 244f, 396f, 8f, card)
        text(c, "Tracking", 28f, 296f, 10f, muted, false, Paint.Align.LEFT)
        text(c, "ARCore visual-inertial world anchor", 28f, 316f, 10f, white, true, Paint.Align.LEFT)
        text(c, "Global reference", 28f, 347f, 10f, muted, false, Paint.Align.LEFT)
        text(c, "GPS + true-north compass", 28f, 367f, 10f, white, true, Paint.Align.LEFT)
        outlineButton(c, 15f, 538f, 244f, 574f, "DONE")
    }

    // -----------------------------------------------------------------------------------------
    // TOUCH MAP
    // -----------------------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val x = event.x / width * W
        val y = event.y / height * H

        when (screen) {
            Screen.HOME -> when {
                y < 55f && x < 62f -> fire(Action.OPEN_MENU)
                y < 55f && x > 205f -> fire(Action.OPEN_SETTINGS)
                y > 525f -> fire(Action.OPEN_CREATE)
            }
            Screen.CREATE -> when {
                y < 70f -> fire(Action.BACK)
                y in 258f..308f -> fire(Action.CYCLE_DISTANCE)
                y in 360f..410f -> fire(Action.CYCLE_ELEVATION)
                y in 485f..545f -> fire(Action.CREATE_TARGET)
            }
            Screen.SETTINGS -> when {
                y < 70f -> fire(Action.BACK)
                y in 104f..142f -> fire(Action.TOGGLE_DISTANCE)
                y in 142f..180f -> fire(Action.TOGGLE_GUIDANCE)
                y in 293f..334f -> fire(Action.CYCLE_HEADING_SMOOTHING)
                y in 334f..375f -> fire(Action.CYCLE_GPS_SMOOTHING)
                y in 375f..416f -> fire(Action.TOGGLE_DECLINATION)
                y in 441f..479f -> fire(Action.OPEN_CALIBRATION)
                y in 479f..517f -> fire(Action.OPEN_GUIDE)
                y in 517f..560f -> fire(Action.OPEN_ABOUT)
            }
            Screen.DIAGNOSTICS -> when {
                y < 70f -> fire(Action.BACK)
                y > 525f -> fire(Action.OPEN_CALIBRATION)
            }
            Screen.CALIBRATION -> when {
                y < 55f && x < 62f -> fire(Action.OPEN_MENU)
                y < 55f && x > 205f -> fire(Action.OPEN_SETTINGS)
                y > 525f -> fire(Action.BACK)
            }
            Screen.GUIDE, Screen.ABOUT -> if (y < 70f || y > 525f) fire(Action.BACK)
            Screen.MENU -> when {
                y in 73f..111f -> fire(Action.GO_HOME)
                y in 112f..150f -> fire(Action.OPEN_DIAGNOSTICS)
                y in 151f..189f -> fire(Action.OPEN_CREATE)
                y in 190f..228f -> fire(Action.OPEN_SETTINGS)
                y in 229f..267f -> fire(Action.OPEN_CALIBRATION)
                y in 268f..306f -> fire(Action.OPEN_GUIDE)
                y in 307f..345f -> fire(Action.OPEN_ABOUT)
                y > 520f -> fire(Action.EXIT)
            }
        }
        return true
    }

    private fun fire(action: Action) = onAction?.invoke(action)

    // -----------------------------------------------------------------------------------------
    // COMPONENTS
    // -----------------------------------------------------------------------------------------

    private fun panelBackground(c: Canvas) {
        p.style = Paint.Style.FILL
        p.color = panel
        c.drawRect(0f, 0f, W, H, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = stroke
        c.drawRoundRect(RectF(5f, 7f, W - 5f, H - 7f), 18f, 18f, p)
    }

    private fun header(c: Canvas, title: String) {
        drawBackIcon(c, 25f, 32f, white)
        text(c, title, 57f, 37f, 13f, white, true, Paint.Align.LEFT)
    }

    private fun row(c: Canvas, l: Float, t: Float, r: Float, b: Float, label: String, value: String, chevron: Boolean) {
        roundRect(c, l, t, r, b, 5f, Color.argb(132, 20, 27, 23))
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = stroke
        c.drawRoundRect(RectF(l, t, r, b), 5f, 5f, p)
        text(c, label, l + 12f, (t + b) / 2f + 4f, 10.0f, white, false, Paint.Align.LEFT)
        if (value.isNotBlank()) text(c, value, r - 18f, (t + b) / 2f + 4f, 9.6f, white, false, Paint.Align.RIGHT)
        if (chevron) drawChevron(c, r - 9f, (t + b) / 2f, muted)
    }

    private fun toggleRow(c: Canvas, l: Float, t: Float, label: String, enabled: Boolean) {
        row(c, l, t, 244f, t + 38f, label, "", false)
        val x = 221f
        val y = t + 19f
        roundRect(c, x - 15f, y - 8f, x + 15f, y + 8f, 8f, if (enabled) Color.rgb(39, 169, 48) else Color.rgb(65, 70, 67))
        p.style = Paint.Style.FILL
        p.color = white
        c.drawCircle(if (enabled) x + 7f else x - 7f, y, 6f, p)
    }

    private fun diagnosticRow(c: Canvas, y: Float, label: String, value: String) {
        text(c, label, 19f, y + 10f, 8.5f, white, false, Paint.Align.LEFT)
        text(c, value, 240f, y + 10f, 8.9f, green, true, Paint.Align.RIGHT)
        p.style = Paint.Style.STROKE
        p.strokeWidth = .7f
        p.color = Color.rgb(39, 47, 43)
        c.drawLine(15f, y + 18f, 244f, y + 18f, p)
    }

    private fun diagnosticTwoLine(c: Canvas, y: Float, label: String, value: String) {
        roundRect(c, 15f, y, 244f, y + 42f, 6f, Color.argb(108, 20, 27, 23))
        text(c, label, 24f, y + 18f, 8.7f, white, false, Paint.Align.LEFT)
        text(c, value, 237f, y + 18f, 8.5f, white, false, Paint.Align.RIGHT)
    }

    private fun guideStep(c: Canvas, number: String, body: String, y: Float) {
        text(c, number, 31f, y + 8f, 23f, green, true)
        text(c, body, 57f, y + 3f, 9.1f, white, true, Paint.Align.LEFT)
    }

    private fun menuItem(c: Canvas, y: Float, icon: String, label: String, selected: Boolean) {
        if (selected) roundRect(c, 15f, y - 18f, 244f, y + 13f, 5f, Color.rgb(29, 39, 34))
        drawMenuRowIcon(c, icon, 28f, y - 1f, if (selected) white else muted)
        text(c, label, 50f, y + 3f, 11.4f, if (selected) green else white, false, Paint.Align.LEFT)
    }

    private fun sectionLabel(c: Canvas, value: String, y: Float) =
        text(c, value, 15f, y, 8.4f, muted, false, Paint.Align.LEFT)

    private fun primaryButton(c: Canvas, l: Float, t: Float, r: Float, b: Float, label: String) {
        roundRect(c, l, t, r, b, 5f, Color.rgb(48, 175, 49))
        text(c, label, (l + r) / 2f, (t + b) / 2f + 5f, 12f, white, true)
    }

    private fun outlineButton(c: Canvas, l: Float, t: Float, r: Float, b: Float, label: String, color: Int = white) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f
        p.color = stroke
        c.drawRoundRect(RectF(l, t, r, b), 5f, 5f, p)
        text(c, label, (l + r) / 2f, (t + b) / 2f + 4f, 10.4f, color, true)
    }

    private fun circleButton(c: Canvas, x: Float, y: Float) {
        p.style = Paint.Style.FILL
        p.color = Color.argb(165, 93, 112, 104)
        c.drawCircle(x, y, 12f, p)
    }

    // -----------------------------------------------------------------------------------------
    // VECTOR ICONS — no device-dependent emoji for production controls.
    // -----------------------------------------------------------------------------------------

    private fun drawMenuIcon(c: Canvas, x: Float, y: Float) {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 1.6f
        p.color = white
        c.drawLine(x - 5f, y - 4f, x + 5f, y - 4f, p)
        c.drawLine(x - 5f, y, x + 5f, y, p)
        c.drawLine(x - 5f, y + 4f, x + 5f, y + 4f, p)
        p.strokeCap = Paint.Cap.BUTT
    }

    private fun drawGearIcon(c: Canvas, x: Float, y: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.7f
        p.color = white
        c.drawCircle(x, y, 4f, p)
        c.drawCircle(x, y, 7f, p)
        for (i in 0 until 8) {
            val a = i * Math.PI / 4.0
            c.drawLine(
                x + cos(a).toFloat() * 7f,
                y + sin(a).toFloat() * 7f,
                x + cos(a).toFloat() * 9f,
                y + sin(a).toFloat() * 9f,
                p
            )
        }
    }

    private fun drawGpsPin(c: Canvas, x: Float, y: Float, color: Int, size: Float) {
        p.style = Paint.Style.FILL
        p.color = color
        val path = Path().apply {
            moveTo(x, y + size)
            cubicTo(x - size * .8f, y + size * .2f, x - size * .72f, y - size * .72f, x, y - size)
            cubicTo(x + size * .72f, y - size * .72f, x + size * .8f, y + size * .2f, x, y + size)
            close()
        }
        c.drawPath(path, p)
        p.color = Color.rgb(18, 79, 23)
        c.drawCircle(x, y - size * .25f, size * .28f, p)
    }

    private fun drawHeadingTarget(c: Canvas, x: Float, y: Float, color: Int, size: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.8f
        p.color = color
        c.drawCircle(x, y, size * .68f, p)
        c.drawLine(x - size, y, x + size, y, p)
        c.drawLine(x, y - size, x, y + size, p)
        p.style = Paint.Style.FILL
        c.drawCircle(x, y, size * .18f, p)
    }

    private fun drawTreePin(c: Canvas, x: Float, y: Float, color: Int, size: Float) {
        p.style = Paint.Style.FILL
        p.color = color
        val path = Path().apply {
            moveTo(x, y + size)
            cubicTo(x - size * .82f, y + size * .1f, x - size * .78f, y - size * .75f, x, y - size)
            cubicTo(x + size * .78f, y - size * .75f, x + size * .82f, y + size * .1f, x, y + size)
            close()
        }
        c.drawPath(path, p)
        p.color = Color.rgb(18, 54, 23)
        c.drawCircle(x, y - size * .27f, size * .38f, p)
        drawTreeIcon(c, x, y - size * .18f, color, size * .27f)
    }

    private fun drawTreeIcon(c: Canvas, x: Float, y: Float, color: Int, size: Float) {
        p.style = Paint.Style.FILL
        p.color = color
        c.drawCircle(x - size * .34f, y - size * .18f, size * .45f, p)
        c.drawCircle(x + size * .28f, y - size * .22f, size * .48f, p)
        c.drawCircle(x, y - size * .55f, size * .52f, p)
        p.color = Color.rgb(78, 93, 38)
        c.drawRect(x - size * .12f, y - size * .03f, x + size * .12f, y + size * .68f, p)
    }

    private fun drawCompassNeedle(c: Canvas, x: Float, y: Float, angle: Double, color: Int) {
        p.style = Paint.Style.FILL
        p.color = color
        val ux = cos(angle).toFloat()
        val uy = sin(angle).toFloat()
        val px = -uy
        val py = ux
        val path = Path().apply {
            moveTo(x + ux * 13f, y + uy * 13f)
            lineTo(x - ux * 9f + px * 5f, y - uy * 9f + py * 5f)
            lineTo(x - ux * 5f, y - uy * 5f)
            lineTo(x - ux * 9f - px * 5f, y - uy * 9f - py * 5f)
            close()
        }
        c.drawPath(path, p)
    }

    private fun drawPhoneIcon(c: Canvas, x: Float, y: Float, color: Int) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.color = color
        c.drawRoundRect(RectF(x - 18f, y - 31f, x + 18f, y + 31f), 4f, 4f, p)
        p.style = Paint.Style.FILL
        c.drawCircle(x, y + 25f, 2f, p)
    }

    private fun drawPersonIcon(c: Canvas, x: Float, y: Float) {
        p.style = Paint.Style.FILL
        p.color = Color.rgb(151, 164, 159)
        c.drawCircle(x, y - 16f, 5f, p)
        c.drawRoundRect(RectF(x - 5f, y - 10f, x + 5f, y + 10f), 4f, 4f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        c.drawLine(x - 3f, y + 8f, x - 7f, y + 23f, p)
        c.drawLine(x + 3f, y + 8f, x + 7f, y + 23f, p)
    }

    private fun drawWarningIcon(c: Canvas, x: Float, y: Float, color: Int) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.7f
        p.color = color
        val path = Path().apply {
            moveTo(x, y - 7f)
            lineTo(x - 7f, y + 6f)
            lineTo(x + 7f, y + 6f)
            close()
        }
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        c.drawRect(x - 0.8f, y - 2f, x + 0.8f, y + 2f, p)
        c.drawCircle(x, y + 4f, 1f, p)
    }

    private fun drawBackIcon(c: Canvas, x: Float, y: Float, color: Int) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f
        p.strokeCap = Paint.Cap.ROUND
        p.color = color
        c.drawLine(x + 5f, y, x - 5f, y, p)
        c.drawLine(x - 5f, y, x, y - 5f, p)
        c.drawLine(x - 5f, y, x, y + 5f, p)
        p.strokeCap = Paint.Cap.BUTT
    }

    private fun drawChevron(c: Canvas, x: Float, y: Float, color: Int) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.3f
        p.color = color
        c.drawLine(x - 2f, y - 4f, x + 2f, y, p)
        c.drawLine(x + 2f, y, x - 2f, y + 4f, p)
    }

    private fun drawExitIcon(c: Canvas, x: Float, y: Float, color: Int) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.7f
        p.color = color
        c.drawRect(x - 6f, y - 6f, x + 1f, y + 6f, p)
        c.drawLine(x, y, x + 8f, y, p)
        c.drawLine(x + 8f, y, x + 4f, y - 4f, p)
        c.drawLine(x + 8f, y, x + 4f, y + 4f, p)
    }

    private fun drawMenuRowIcon(c: Canvas, type: String, x: Float, y: Float, color: Int) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f
        p.color = color
        when (type) {
            "home" -> {
                val path = Path().apply {
                    moveTo(x - 6f, y)
                    lineTo(x, y - 6f)
                    lineTo(x + 6f, y)
                    lineTo(x + 5f, y + 7f)
                    lineTo(x - 5f, y + 7f)
                    close()
                }
                c.drawPath(path, p)
            }
            "details" -> {
                c.drawRect(x - 5f, y - 7f, x + 5f, y + 7f, p)
                c.drawLine(x - 2f, y - 3f, x + 3f, y - 3f, p)
                c.drawLine(x - 2f, y + 1f, x + 3f, y + 1f, p)
                c.drawLine(x - 2f, y + 5f, x + 3f, y + 5f, p)
            }
            "tree" -> drawTreeIcon(c, x, y + 3f, color, 7f)
            "settings" -> drawGearIconAt(c, x, y, color)
            "compass" -> drawHeadingTarget(c, x, y, color, 7f)
            "guide" -> {
                c.drawRect(x - 6f, y - 7f, x + 6f, y + 7f, p)
                c.drawLine(x, y - 7f, x, y + 7f, p)
            }
            "info" -> {
                c.drawCircle(x, y, 7f, p)
                text(c, "i", x, y + 4f, 10f, color, true)
            }
        }
    }

    private fun drawGearIconAt(c: Canvas, x: Float, y: Float, color: Int) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f
        p.color = color
        c.drawCircle(x, y, 3f, p)
        c.drawCircle(x, y, 6f, p)
        for (i in 0 until 8) {
            val a = i * Math.PI / 4.0
            c.drawLine(
                x + cos(a).toFloat() * 6f,
                y + sin(a).toFloat() * 6f,
                x + cos(a).toFloat() * 8f,
                y + sin(a).toFloat() * 8f,
                p
            )
        }
    }

    private fun crosshair(c: Canvas, x: Float, y: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f
        p.color = white
        c.drawLine(x - 12f, y, x + 12f, y, p)
        c.drawLine(x, y - 12f, x, y + 12f, p)
    }

    private fun roundRect(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
        p.style = Paint.Style.FILL
        p.color = color
        c.drawRoundRect(RectF(l, t, r, b), radius, radius, p)
    }

    private fun text(
        c: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        bold: Boolean = false,
        align: Paint.Align = Paint.Align.CENTER
    ) {
        p.style = Paint.Style.FILL
        p.color = color
        p.textSize = size
        p.textAlign = align
        p.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        c.drawText(value, x, y, p)
    }

    private fun distanceText(value: Double?): String = value?.let {
        if (it < 10.0) String.format("%.1f m", it) else String.format("%.0f m", it)
    } ?: "-- m"

    private fun accuracyText(): String = if (model.gpsAccuracy.isFinite()) {
        "±${String.format("%.1f", model.gpsAccuracy)} m"
    } else "--"

    private fun fmt(value: Double?): String = value?.let { String.format("%.2f", it) } ?: "--"

    private fun coord(lat: Double?, lng: Double?): String =
        if (lat != null && lng != null) String.format("%.6f  %.6f", lat, lng) else "--"
}
