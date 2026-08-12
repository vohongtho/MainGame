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
        val directionDeltaDeg: Double? = null,
        val targetScreenX: Float? = null,
        val targetScreenY: Float? = null,
        val targetInFront: Boolean = true,
        val targetReady: Boolean = false,
        val arTracking: Boolean = false,
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
        set(value) { field = value; invalidate() }
    var model: Model = Model()
        set(value) { field = value; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val W = 265f
    private val H = 580f
    private val green = Color.rgb(77, 214, 60)
    private val greenBright = Color.rgb(83, 234, 69)
    private val orange = Color.rgb(255, 165, 0)
    private val red = Color.rgb(255, 51, 51)
    private val white = Color.rgb(248, 249, 248)
    private val muted = Color.rgb(200, 207, 202)
    private val panel = Color.rgb(11, 17, 14)
    private val card = Color.rgb(20, 27, 23)
    private val stroke = Color.rgb(48, 58, 52)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(width / W, height / H)
        when (screen) {
            Screen.HOME -> drawHome(canvas)
            Screen.CREATE -> drawCreate(canvas)
            Screen.SETTINGS -> drawSettings(canvas)
            Screen.DIAGNOSTICS -> drawDiagnostics(canvas)
            Screen.CALIBRATION -> drawCalibration(canvas)
            Screen.GUIDE -> drawGuide(canvas)
            Screen.ABOUT -> drawAbout(canvas)
            Screen.MENU -> drawMenu(canvas)
        }
        canvas.restore()
    }

    private fun drawHome(c: Canvas) {
        // Top controls exactly follow the original mockup proportions.
        circleButton(c, 29f, 29f, "≡")
        circleButton(c, 238f, 29f, "⚙")
        statusChip(c, 28f, 58f, 103f, 95f, true)
        statusChip(c, 146f, 58f, 239f, 95f, false)

        val gpsPoor = model.gpsAccuracy.isFinite() && model.gpsAccuracy >= 15f
        val calibrate = model.headingQuality == "CALIBRATE"
        if (gpsPoor && !model.targetReady) {
            drawGpsPoorState(c)
            return
        }
        if (calibrate && !model.targetReady) {
            drawCalibrationHomeState(c)
            return
        }

        crosshair(c, 133f, 244f)
        val delta = model.directionDeltaDeg ?: 0.0
        val behind = model.targetReady && (!model.targetInFront || abs(delta) >= 120.0)
        val accent = when {
            behind -> red
            abs(delta) >= 22.0 -> orange
            else -> green
        }

        if (model.targetReady) {
            val tx = ((model.targetScreenX ?: 0.65f) * W).coerceIn(72f, 222f)
            val ty = ((model.targetScreenY ?: 0.39f) * H).coerceIn(150f, 330f)
            if (behind) {
                drawTarget(c, 154f, 252f, red)
            } else {
                drawTarget(c, tx, ty, accent)
            }
        }
        compass(c, delta, accent, behind)
        bottomCta(c)
    }

    private fun statusChip(c: Canvas, l: Float, t: Float, r: Float, b: Float, gps: Boolean) {
        roundRect(c, l, t, r, b, 8f, Color.argb(218, 16, 25, 21))
        val isBad = if (gps) model.gpsAccuracy.isFinite() && model.gpsAccuracy >= 15f else model.headingQuality == "CALIBRATE"
        val accent = if (isBad) orange else green
        if (gps) pinIcon(c, l + 19f, (t + b) / 2f, accent, 9f) else headingIcon(c, l + 19f, (t + b) / 2f, accent, 9f)
        text(c, if (gps) if (isBad) "GPS POOR" else "GPS GOOD" else if (isBad) "HEADING" else "HEADING GOOD", l + 35f, t + 14f, 8.0f, white, false, Paint.Align.LEFT)
        val value = if (gps) {
            if (model.gpsAccuracy.isFinite()) "±${String.format("%.1f", model.gpsAccuracy)} m" else "--"
        } else {
            if (isBad) "CALIBRATE" else "±${String.format("%.1f", model.headingAccuracyDeg)}°"
        }
        text(c, value, l + 35f, t + 29f, 12.5f, accent, true, Paint.Align.LEFT)
    }

    private fun drawTarget(c: Canvas, x: Float, y: Float, accent: Int) {
        // Dashed/outlined ellipse under target.
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = accent
        c.drawOval(RectF(x - 31f, y + 15f, x + 31f, y + 27f), p)
        p.style = Paint.Style.FILL
        pinIcon(c, x, y - 7f, accent, 20f)
        roundRect(c, x - 43f, y + 27f, x + 43f, y + 73f, 9f, Color.argb(206, 7, 12, 9))
        text(c, "TARGET TREE", x, y + 45f, 8.5f, white, false)
        if (model.showDistance) text(c, distanceText(), x, y + 65f, 19f, white, true)
    }

    private fun compass(c: Canvas, delta: Double, accent: Int, behind: Boolean) {
        val cx = 133f
        val cy = 478f
        val radius = 119f
        // Dark translucent hemisphere.
        p.style = Paint.Style.FILL
        p.color = Color.argb(135, 0, 6, 3)
        c.drawCircle(cx, cy, radius, p)
        // White upper arc and tick marks from ~200° to 340°.
        p.style = Paint.Style.STROKE
        p.color = Color.WHITE
        p.strokeWidth = 1.4f
        c.drawArc(RectF(cx-radius, cy-radius, cx+radius, cy+radius), 200f, 140f, false, p)
        for (i in 0..28) {
            val a = Math.toRadians((200.0 + i * 5.0))
            val longTick = i % 5 == 0
            val r1 = radius - if (longTick) 12f else 7f
            val x1 = cx + cos(a).toFloat() * r1
            val y1 = cy + sin(a).toFloat() * r1
            val x2 = cx + cos(a).toFloat() * radius
            val y2 = cy + sin(a).toFloat() * radius
            p.strokeWidth = if (longTick) 2f else 1f
            c.drawLine(x1, y1, x2, y2, p)
        }
        text(c, "W", 29f, 458f, 10f, white, true)
        text(c, "E", 238f, 458f, 10f, white, true)

        val clamped = delta.coerceIn(-60.0, 60.0)
        val needleAngle = Math.toRadians(-90.0 + clamped * 0.68)
        val nx = cx + cos(needleAngle).toFloat() * 86f
        val ny = cy + sin(needleAngle).toFloat() * 86f
        arrowHead(c, nx, ny, needleAngle, accent)

        if (model.showGuidance) {
            val label = when {
                behind -> "Tree is behind you"
                abs(delta) <= 3.0 -> "Tree is ahead"
                delta < 0 -> "Turn left"
                else -> "Turn right"
            }
            text(c, label, cx, 493f, 12f, accent, true)
            val deg = if (behind) (180.0 - abs(abs(delta) - 180.0)).toInt() else abs(delta).toInt()
            text(c, "${deg}°", cx, 520f, 26f, accent, false)
        }
    }

    private fun bottomCta(c: Canvas) {
        roundRect(c, 18f, 538f, 247f, 574f, 18f, Color.argb(235, 36, 43, 40))
        treeIcon(c, 42f, 556f, green, 9f)
        text(c, "Place test tree ${model.selectedDistanceM} m ahead", 139f, 561f, 11f, white, false)
    }

    private fun drawGpsPoorState(c: Canvas) {
        crosshair(c, 133f, 224f)
        p.style = Paint.Style.FILL
        p.color = Color.argb(45, 0, 0, 0)
        c.drawCircle(133f, 263f, 54f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = orange
        c.drawOval(RectF(106f, 249f, 160f, 277f), p)
        text(c, "--", 133f, 267f, 13f, orange, true)
        roundRect(c, 20f, 396f, 245f, 458f, 7f, Color.argb(228, 29, 27, 11))
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = orange
        c.drawRoundRect(RectF(20f,396f,245f,458f),7f,7f,p)
        text(c, "⚠  GPS accuracy is limited", 31f, 416f, 10f, white, true, Paint.Align.LEFT)
        text(c, "Move to a more open area for a stronger GPS fix.", 31f, 438f, 8.5f, muted, false, Paint.Align.LEFT)
        bottomCta(c)
    }

    private fun drawCalibrationHomeState(c: Canvas) {
        p.style = Paint.Style.FILL
        p.color = Color.argb(70, 0, 0, 0)
        c.drawRect(0f, 100f, W, H, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = green
        c.drawArc(RectF(58f, 152f, 207f, 301f), -88f, 188f, false, p)
        phoneIcon(c, 133f, 229f, white)
        text(c, "Move your phone in a figure-eight pattern", 133f, 349f, 10.5f, white, true)
        text(c, "Keep the phone level and move slowly.", 133f, 373f, 8.5f, muted, false)
        outlineButton(c, 22f, 521f, 243f, 564f, "CANCEL")
    }

    private fun drawCreate(c: Canvas) {
        panelBackground(c)
        header(c, "Create test tree")
        text(c, "Creates a target tree ${model.selectedDistanceM} metres away", 40f, 87f, 10f, muted, false, Paint.Align.LEFT)
        text(c, "in the direction you are currently looking.", 40f, 104f, 10f, muted, false, Paint.Align.LEFT)
        personIcon(c, 47f, 191f)
        treeIcon(c, 215f, 185f, green, 20f)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f; p.color = muted
        c.drawLine(64f, 198f, 196f, 198f, p)
        c.drawLine(64f, 194f, 64f, 202f, p); c.drawLine(196f,194f,196f,202f,p)
        text(c, "${model.selectedDistanceM} m", 132f, 188f, 12f, white, true)
        row(c, 18f, 258f, 247f, 308f, "Distance", "${model.selectedDistanceM} m", true)
        row(c, 18f, 309f, 247f, 359f, "Direction", "Current view", true)
        row(c, 18f, 360f, 247f, 410f, "Elevation offset", "${model.elevationOffsetM} m", true)
        primaryButton(c, 18f, 492f, 247f, 536f, "CREATE NOW")
    }

    private fun drawSettings(c: Canvas) {
        panelBackground(c); header(c, "Options")
        sectionLabel(c, "DISPLAY", 93f)
        toggleRow(c, 18f, 104f, "Show distance", model.showDistance)
        toggleRow(c, 18f, 142f, "Show direction guidance", model.showGuidance)
        row(c, 18f, 180f, 247f, 218f, "Distance unit", "Metres (m)", true)
        row(c, 18f, 218f, 247f, 256f, "Theme", "Dark", true)
        sectionLabel(c, "FILTERING & STABILITY", 282f)
        row(c, 18f, 293f, 247f, 334f, "Heading smoothing", model.headingSmoothing, true)
        row(c, 18f, 334f, 247f, 375f, "GPS position smoothing", model.gpsSmoothing, true)
        toggleRow(c, 18f, 375f, "Magnetic declination", model.declinationEnabled)
        sectionLabel(c, "OTHER", 429f)
        row(c, 18f, 441f, 247f, 479f, "Calibrate compass", "", true)
        row(c, 18f, 479f, 247f, 517f, "User guide", "", true)
        row(c, 18f, 517f, 247f, 555f, "About", "", true)
    }

    private fun drawDiagnostics(c: Canvas) {
        panelBackground(c); header(c, "Details (Diagnostic)")
        var y = 81f
        diagnosticRow(c, y, "Game yaw (°)", fmt(model.gameYaw)); y += 31f
        diagnosticRow(c, y, "Compass heading (°)", fmt(model.magneticHeading)); y += 31f
        diagnosticRow(c, y, "True heading (°)", fmt(model.trueHeading)); y += 31f
        diagnosticRow(c, y, "Filtered heading (°)", fmt(model.filteredHeading)); y += 31f
        diagnosticRow(c, y, "Turn speed (°/s)", String.format("%.1f", model.turnSpeed)); y += 39f
        diagnosticRow(c, y, "Bearing to tree (°)", fmt(model.gpsBearing)); y += 31f
        diagnosticRow(c, y, "AR direction delta (°)", model.directionDeltaDeg?.let { String.format("%.1f", it) } ?: "--"); y += 31f
        diagnosticRow(c, y, "Distance (m)", model.targetDistanceM?.let { String.format("%.1f", it) } ?: "--"); y += 39f
        diagnosticRow(c, y, "GPS quality", "${model.gpsQuality} (${accuracyText()})"); y += 47f
        diagnosticRow(c, y, "AR tracking", model.arStatus); y += 47f
        diagnosticTwoLine(c, y, "Your position", coord(model.phoneLat, model.phoneLng)); y += 61f
        diagnosticTwoLine(c, y, "Target tree (fixed)", coord(model.treeLat, model.treeLng))
        outlineButton(c, 18f, 521f, 247f, 563f, "CALIBRATE COMPASS", green)
    }

    private fun drawCalibration(c: Canvas) {
        panelBackground(c); header(c, "Calibrate compass")
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = green
        c.drawCircle(133f, 232f, 76f, p)
        c.drawArc(RectF(57f,156f,209f,308f), -88f, 190f, false, p)
        phoneIcon(c, 133f, 231f, white)
        text(c, "Move your phone in a figure-eight pattern", 133f, 351f, 11f, white, true)
        text(c, "Keep the phone level and move slowly.", 133f, 375f, 9f, muted, false)
        text(c, "Heading quality: ${model.headingQuality} · ${fmt(model.trueHeading)}°", 133f, 420f, 9.5f, if (model.headingQuality == "CALIBRATE") orange else green, true)
        outlineButton(c, 18f, 521f, 247f, 563f, "DONE")
    }

    private fun drawMenu(c: Canvas) {
        panelBackground(c)
        treeIcon(c, 29f, 58f, green, 10f)
        text(c, "TREE NAVIGATOR", 48f, 62f, 14f, white, true, Paint.Align.LEFT)
        menuItem(c, 87f, "⌂", "Home", screen == Screen.HOME || true, Action.GO_HOME)
        menuItem(c, 126f, "▣", "Details", false, Action.OPEN_DIAGNOSTICS)
        menuItem(c, 165f, "♧", "Create test tree", false, Action.OPEN_CREATE)
        menuItem(c, 204f, "⚙", "Options", false, Action.OPEN_SETTINGS)
        menuItem(c, 243f, "◎", "Calibrate compass", false, Action.OPEN_CALIBRATION)
        menuItem(c, 282f, "▤", "User guide", false, Action.OPEN_GUIDE)
        menuItem(c, 321f, "ⓘ", "About", false, Action.OPEN_ABOUT)
        p.color = stroke; p.strokeWidth = 1f; c.drawLine(17f, 514f, 248f, 514f, p)
        text(c, "↪", 31f, 551f, 17f, red, true)
        text(c, "Exit", 53f, 551f, 12f, red, false, Paint.Align.LEFT)
    }

    private fun drawGuide(c: Canvas) {
        panelBackground(c); header(c, "User guide")
        text(c, "1", 34f, 112f, 23f, green, true)
        text(c, "Wait for GPS and heading to become GOOD.", 60f, 108f, 10f, white, true, Paint.Align.LEFT)
        text(c, "2", 34f, 177f, 23f, green, true)
        text(c, "Point the camera towards the target direction.", 60f, 173f, 10f, white, true, Paint.Align.LEFT)
        text(c, "3", 34f, 242f, 23f, green, true)
        text(c, "Create a test tree. ARCore locks it in world space.", 60f, 238f, 10f, white, true, Paint.Align.LEFT)
        text(c, "4", 34f, 307f, 23f, green, true)
        text(c, "Walk past it and turn around to reacquire the marker.", 60f, 303f, 10f, white, true, Paint.Align.LEFT)
        roundRect(c, 18f, 365f, 247f, 447f, 8f, card)
        text(c, "Tip", 31f, 389f, 11f, green, true, Paint.Align.LEFT)
        text(c, "After AR world lock, GPS does not move the marker.", 31f, 414f, 9f, muted, false, Paint.Align.LEFT)
        text(c, "GPS is only a global reference and recovery signal.", 31f, 432f, 9f, muted, false, Paint.Align.LEFT)
        outlineButton(c, 18f, 521f, 247f, 563f, "DONE")
    }

    private fun drawAbout(c: Canvas) {
        panelBackground(c); header(c, "About")
        treeIcon(c, 133f, 148f, green, 30f)
        text(c, "TREE NAVIGATOR", 133f, 201f, 21f, white, true)
        text(c, "AR navigation for fixed tree targets", 133f, 226f, 10f, muted, false)
        roundRect(c, 18f, 270f, 247f, 396f, 8f, card)
        text(c, "Tracking", 31f, 296f, 10f, muted, false, Paint.Align.LEFT)
        text(c, "ARCore visual-inertial world anchor", 31f, 316f, 10f, white, true, Paint.Align.LEFT)
        text(c, "Global reference", 31f, 347f, 10f, muted, false, Paint.Align.LEFT)
        text(c, "GPS + true-north compass", 31f, 367f, 10f, white, true, Paint.Align.LEFT)
        outlineButton(c, 18f, 521f, 247f, 563f, "DONE")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val x = event.x / width * W
        val y = event.y / height * H
        when (screen) {
            Screen.HOME -> when {
                y < 55f && x < 65f -> fire(Action.OPEN_MENU)
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
                y > 510f -> fire(Action.OPEN_CALIBRATION)
            }
            Screen.CALIBRATION, Screen.GUIDE, Screen.ABOUT -> if (y < 70f || y > 505f) fire(Action.BACK)
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

    private fun panelBackground(c: Canvas) {
        p.style = Paint.Style.FILL; p.color = panel; c.drawRect(0f,0f,W,H,p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = stroke
        c.drawRoundRect(RectF(5f,7f,W-5f,H-7f),18f,18f,p)
    }

    private fun header(c: Canvas, title: String) {
        text(c, "‹", 27f, 37f, 25f, white, false)
        text(c, title, 58f, 37f, 13f, white, true, Paint.Align.LEFT)
    }

    private fun row(c: Canvas, l: Float, t: Float, r: Float, b: Float, label: String, value: String, chevron: Boolean) {
        roundRect(c,l,t,r,b,5f,Color.argb(130,20,27,23))
        p.style=Paint.Style.STROKE;p.strokeWidth=1f;p.color=stroke;c.drawRoundRect(RectF(l,t,r,b),5f,5f,p)
        text(c,label,l+12f,(t+b)/2f+4f,10.5f,white,false,Paint.Align.LEFT)
        if (value.isNotBlank()) text(c,value,r-18f,(t+b)/2f+4f,10f,white,false,Paint.Align.RIGHT)
        if (chevron) text(c,"›",r-9f,(t+b)/2f+5f,16f,muted,false)
    }

    private fun toggleRow(c: Canvas, l: Float, t: Float, label: String, enabled: Boolean) {
        row(c,l,t,247f,t+38f,label,"",false)
        val x=224f; val y=t+19f
        roundRect(c,x-15f,y-8f,x+15f,y+8f,8f,if(enabled) Color.rgb(39,169,48) else Color.rgb(65,70,67))
        p.style=Paint.Style.FILL;p.color=white;c.drawCircle(if(enabled)x+7f else x-7f,y,6f,p)
    }

    private fun diagnosticRow(c: Canvas, y: Float, label: String, value: String) {
        text(c,label,22f,y+10f,9.5f,white,false,Paint.Align.LEFT)
        text(c,value,242f,y+10f,10f,green,true,Paint.Align.RIGHT)
        p.style=Paint.Style.STROKE;p.strokeWidth=.7f;p.color=Color.rgb(39,47,43);c.drawLine(18f,y+20f,247f,y+20f,p)
    }

    private fun diagnosticTwoLine(c: Canvas, y: Float, label: String, value: String) {
        roundRect(c,18f,y,247f,y+53f,6f,Color.argb(105,20,27,23))
        text(c,label,27f,y+21f,9.5f,white,false,Paint.Align.LEFT)
        text(c,value,238f,y+21f,9.2f,white,false,Paint.Align.RIGHT)
    }

    private fun sectionLabel(c: Canvas, s: String, y: Float) = text(c,s,18f,y,8.5f,muted,false,Paint.Align.LEFT)

    private fun menuItem(c: Canvas, y: Float, icon: String, label: String, selected: Boolean, action: Action) {
        if (selected) roundRect(c,17f,y-18f,248f,y+13f,5f,Color.rgb(29,39,34))
        text(c,icon,31f,y+3f,15f,white,true)
        text(c,label,53f,y+3f,11.5f,if(selected) green else white,false,Paint.Align.LEFT)
    }

    private fun primaryButton(c: Canvas,l:Float,t:Float,r:Float,b:Float,label:String){roundRect(c,l,t,r,b,5f,Color.rgb(47,174,49));text(c,label,(l+r)/2,(t+b)/2+5f,12f,white,true)}
    private fun outlineButton(c:Canvas,l:Float,t:Float,r:Float,b:Float,label:String,color:Int=white){p.style=Paint.Style.STROKE;p.strokeWidth=1.2f;p.color=stroke;c.drawRoundRect(RectF(l,t,r,b),5f,5f,p);text(c,label,(l+r)/2,(t+b)/2+5f,11f,color,true)}

    private fun circleButton(c: Canvas, x: Float, y: Float, label: String) {
        p.style=Paint.Style.FILL;p.color=Color.argb(150,63,78,71);c.drawCircle(x,y,12f,p);text(c,label,x,y+4f,14f,white,false)
    }
    private fun crosshair(c:Canvas,x:Float,y:Float){p.style=Paint.Style.STROKE;p.strokeWidth=1.5f;p.color=white;c.drawLine(x-12f,y,x+12f,y,p);c.drawLine(x,y-12f,x,y+12f,p)}
    private fun treeIcon(c:Canvas,x:Float,y:Float,color:Int,size:Float){p.style=Paint.Style.FILL;p.color=color;c.drawCircle(x-size*.35f,y-size*.2f,size*.45f,p);c.drawCircle(x+size*.25f,y-size*.25f,size*.48f,p);c.drawCircle(x,y-size*.55f,size*.5f,p);p.color=Color.rgb(75,88,39);c.drawRect(x-size*.12f,y-size*.05f,x+size*.12f,y+size*.65f,p)}
    private fun personIcon(c:Canvas,x:Float,y:Float){p.style=Paint.Style.FILL;p.color=Color.rgb(151,164,159);c.drawCircle(x,y-16f,5f,p);c.drawRoundRect(RectF(x-5f,y-10f,x+5f,y+10f),4f,4f,p);p.strokeWidth=4f;p.style=Paint.Style.STROKE;c.drawLine(x-3f,y+8f,x-7f,y+23f,p);c.drawLine(x+3f,y+8f,x+7f,y+23f,p)}
    private fun phoneIcon(c:Canvas,x:Float,y:Float,color:Int){p.style=Paint.Style.STROKE;p.strokeWidth=3f;p.color=color;c.drawRoundRect(RectF(x-18f,y-31f,x+18f,y+31f),4f,4f,p);p.style=Paint.Style.FILL;c.drawCircle(x,y+25f,2f,p)}
    private fun pinIcon(c:Canvas,x:Float,y:Float,color:Int,size:Float){p.style=Paint.Style.FILL;p.color=color;val path=Path();path.moveTo(x,y+size);path.cubicTo(x-size*.85f,y+size*.1f,x-size*.75f,y-size*.8f,x,y-size);path.cubicTo(x+size*.75f,y-size*.8f,x+size*.85f,y+size*.1f,x,y+size);path.close();c.drawPath(path,p);p.color=Color.rgb(12,35,18);c.drawCircle(x,y-size*.28f,size*.28f,p);treeIcon(c,x,y-size*.28f,color,size*.18f)}
    private fun headingIcon(c:Canvas,x:Float,y:Float,color:Int,size:Float){p.style=Paint.Style.STROKE;p.strokeWidth=2f;p.color=color;c.drawCircle(x,y,size*.65f,p);c.drawLine(x-size*.9f,y,x+size*.9f,y,p);c.drawLine(x,y-size*.9f,x,y+size*.9f,p)}
    private fun arrowHead(c:Canvas,x:Float,y:Float,a:Double,color:Int){p.style=Paint.Style.FILL;p.color=color;val ux=cos(a).toFloat();val uy=sin(a).toFloat();val px=-uy;val py=ux;val path=Path();path.moveTo(x+ux*12f,y+uy*12f);path.lineTo(x-ux*8f+px*6f,y-uy*8f+py*6f);path.lineTo(x-ux*8f-px*6f,y-uy*8f-py*6f);path.close();c.drawPath(path,p)}

    private fun roundRect(c:Canvas,l:Float,t:Float,r:Float,b:Float,rad:Float,color:Int){p.style=Paint.Style.FILL;p.color=color;c.drawRoundRect(RectF(l,t,r,b),rad,rad,p)}
    private fun text(c:Canvas,s:String,x:Float,y:Float,size:Float,color:Int,bold:Boolean=false,align:Paint.Align=Paint.Align.CENTER){p.style=Paint.Style.FILL;p.color=color;p.textSize=size;p.textAlign=align;p.typeface=if(bold) Typeface.create(Typeface.DEFAULT,Typeface.BOLD) else Typeface.create(Typeface.DEFAULT,Typeface.NORMAL);c.drawText(s,x,y,p)}

    private fun distanceText(): String = model.targetDistanceM?.let { if(it<10) String.format("%.1f m",it) else String.format("%.0f m",it) } ?: "-- m"
    private fun accuracyText(): String = if(model.gpsAccuracy.isFinite()) "±${String.format("%.1f",model.gpsAccuracy)} m" else "--"
    private fun fmt(v:Double?):String=v?.let{String.format("%.1f",it)}?:"--"
    private fun coord(lat:Double?,lng:Double?):String=if(lat!=null&&lng!=null) String.format("%.6f  %.6f",lat,lng) else "--"
}
