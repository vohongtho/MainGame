package com.example.treedirectiondemo

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var arView: ArCoreCameraView
    private lateinit var overlay: TreeOverlayView
    private lateinit var tvGpsStatus: TextView
    private lateinit var tvCompassStatus: TextView
    private lateinit var tvDirection: TextView
    private lateinit var gpsWarning: View

    private lateinit var panelMenu: View
    private lateinit var panelCreate: View
    private lateinit var panelSettings: View
    private lateinit var panelDiagnostics: View
    private lateinit var panelCalibration: View
    private lateinit var panelGuide: View
    private lateinit var panelAbout: View
    private val panels = mutableListOf<View>()

    private lateinit var tvCreateDistance: TextView
    private lateinit var tvCreateDistanceHero: TextView
    private lateinit var tvDiagnostics: TextView
    private lateinit var tvCalibrationHeading: TextView
    private lateinit var swDistance: Switch
    private lateinit var swGuidance: Switch
    private lateinit var swTrueNorth: Switch
    private lateinit var swStability: Switch

    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val fusedLocation by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var arSession: Session? = null
    private var installRequested = false
    private var arFrameState: ArCoreCameraView.FrameState? = null
    private var arCoreStatus = "STARTING"

    private var currentLocation: Location? = null
    private var gpsAccuracy = Float.MAX_VALUE
    private var locationQuality = "WAITING"

    private var magneticHeading: Double? = null
    private var trueHeading: Double? = null
    private var headingAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var lastHeadingSample: Double? = null
    private var headingStabilityEstimate = 4.0

    private var treeLat: Double? = null
    private var treeLng: Double? = null
    private var gpsDistanceMeters: Float? = null
    private var gpsBearing: Double? = null
    private var directionDelta: Double? = null
    private var autoTargetPlaced = false
    private var createDistanceMeters = 35

    private var showDistance = true
    private var showGuidance = true
    private var trueNorthCorrection = true
    private var microStabilization = true

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) startLocationUpdates()
        if (!hasPermission(Manifest.permission.CAMERA)) {
            showToast("Camera permission is required for AR navigation")
        } else {
            resumeArCore()
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location ->
                currentLocation = Location(location)
                gpsAccuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
                locationQuality = when {
                    !location.hasAccuracy() -> "POOR"
                    location.accuracy <= 4f -> "EXCELLENT"
                    location.accuracy <= 8f -> "GOOD"
                    location.accuracy <= 15f -> "FAIR"
                    else -> "POOR"
                }
                recomputeGpsGeometry()
                maybeAutoCreateTarget()
                renderState()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        bindActions()
        bindArCoreFrames()
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun bindViews() {
        arView = findViewById(R.id.arView)
        overlay = findViewById(R.id.treeOverlay)
        tvGpsStatus = findViewById(R.id.tvGpsStatus)
        tvCompassStatus = findViewById(R.id.tvCompassStatus)
        tvDirection = findViewById(R.id.tvDirection)
        gpsWarning = findViewById(R.id.gpsWarning)

        panelMenu = findViewById(R.id.panelMenu)
        panelCreate = findViewById(R.id.panelCreate)
        panelSettings = findViewById(R.id.panelSettings)
        panelDiagnostics = findViewById(R.id.panelDiagnostics)
        panelCalibration = findViewById(R.id.panelCalibration)
        panelGuide = findViewById(R.id.panelGuide)
        panelAbout = findViewById(R.id.panelAbout)
        panels += listOf(panelMenu, panelCreate, panelSettings, panelDiagnostics, panelCalibration, panelGuide, panelAbout)

        tvCreateDistance = findViewById(R.id.tvCreateDistance)
        tvCreateDistanceHero = findViewById(R.id.tvCreateDistanceHero)
        tvDiagnostics = findViewById(R.id.tvDiagnostics)
        tvCalibrationHeading = findViewById(R.id.tvCalibrationHeading)
        swDistance = findViewById(R.id.swDistance)
        swGuidance = findViewById(R.id.swGuidance)
        swTrueNorth = findViewById(R.id.swTrueNorth)
        swStability = findViewById(R.id.swStability)
    }

    private fun bindActions() {
        findViewById<Button>(R.id.btnMenu).setOnClickListener { showPanel(panelMenu) }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showPanel(panelSettings) }
        findViewById<Button>(R.id.btnResetTree).setOnClickListener {
            createDistanceMeters = 35
            updateCreateDistanceUi()
            placeTarget(createDistanceMeters)
        }

        findViewById<Button>(R.id.btnMenuClose).setOnClickListener { showHome() }
        findViewById<TextView>(R.id.menuHome).setOnClickListener { showHome() }
        findViewById<TextView>(R.id.menuDiagnostics).setOnClickListener { showPanel(panelDiagnostics) }
        findViewById<TextView>(R.id.menuCreate).setOnClickListener { showPanel(panelCreate) }
        findViewById<TextView>(R.id.menuSettings).setOnClickListener { showPanel(panelSettings) }
        findViewById<TextView>(R.id.menuCalibration).setOnClickListener { showPanel(panelCalibration) }
        findViewById<TextView>(R.id.menuGuide).setOnClickListener { showPanel(panelGuide) }
        findViewById<TextView>(R.id.menuAbout).setOnClickListener { showPanel(panelAbout) }
        findViewById<TextView>(R.id.menuExit).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnCreateBack).setOnClickListener { showHome() }
        findViewById<Button>(R.id.btnDistanceMinus).setOnClickListener {
            createDistanceMeters = (createDistanceMeters - 5).coerceAtLeast(10)
            updateCreateDistanceUi()
        }
        findViewById<Button>(R.id.btnDistancePlus).setOnClickListener {
            createDistanceMeters = (createDistanceMeters + 5).coerceAtMost(100)
            updateCreateDistanceUi()
        }
        findViewById<Button>(R.id.btnCreateNow).setOnClickListener {
            placeTarget(createDistanceMeters)
            showHome()
        }

        findViewById<Button>(R.id.btnSettingsBack).setOnClickListener { showHome() }
        swDistance.setOnCheckedChangeListener { _, checked ->
            showDistance = checked
            renderState()
        }
        swGuidance.setOnCheckedChangeListener { _, checked ->
            showGuidance = checked
            renderState()
        }
        swTrueNorth.setOnCheckedChangeListener { _, checked ->
            trueNorthCorrection = checked
            recomputeHeadingFromMagnetic()
            recomputeGpsGeometry()
            renderState()
        }
        swStability.setOnCheckedChangeListener { _, checked ->
            microStabilization = checked
            overlay.setMicroStabilizationEnabled(checked)
            renderState()
        }

        findViewById<Button>(R.id.btnDiagBack).setOnClickListener { showHome() }
        findViewById<Button>(R.id.btnCalibrateFromDiag).setOnClickListener { showPanel(panelCalibration) }
        findViewById<Button>(R.id.btnCalibrationBack).setOnClickListener { showHome() }
        findViewById<Button>(R.id.btnCalibrationDone).setOnClickListener { showHome() }
        findViewById<Button>(R.id.btnGuideBack).setOnClickListener { showHome() }
        findViewById<Button>(R.id.btnAboutBack).setOnClickListener { showHome() }
    }

    private fun bindArCoreFrames() {
        arView.onFrameState = { state ->
            arFrameState = state
            arCoreStatus = when {
                state.tracking && state.anchorReady -> "WORLD LOCKED"
                state.tracking -> "TRACKING"
                else -> state.cameraTrackingState.name
            }
            maybeAutoCreateTarget()
            renderState()
        }
    }

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) startLocationUpdates()
        if (hasPermission(Manifest.permission.CAMERA)) resumeArCore()
    }

    override fun onPause() {
        arView.onPause()
        try { arSession?.pause() } catch (_: Throwable) {}
        fusedLocation.removeLocationUpdates(locationCallback)
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        arView.detachSession()
        try { arSession?.close() } catch (_: Throwable) {}
        arSession = null
        super.onDestroy()
    }

    private fun resumeArCore() {
        if (!hasPermission(Manifest.permission.CAMERA)) return
        try {
            if (arSession == null) {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        arCoreStatus = "INSTALLING ARCORE"
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                }
                val session = Session(this)
                val config = Config(session).apply {
                    focusMode = Config.FocusMode.AUTO
                    planeFindingMode = Config.PlaneFindingMode.DISABLED
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                }
                session.configure(config)
                arSession = session
                arView.attachSession(session)
            }

            arView.setDisplayRotation(display?.rotation ?: Surface.ROTATION_0)
            arSession?.resume()
            arView.onResume()
            arCoreStatus = "TRACKING START"
        } catch (t: Throwable) {
            arCoreStatus = "ARCORE ERROR"
            showToast("ARCore could not start: ${t.javaClass.simpleName}")
            renderState()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(600L)
            .setMinUpdateDistanceMeters(0.5f)
            .setWaitForAccurateLocation(false)
            .build()
        fusedLocation.removeLocationUpdates(locationCallback)
        fusedLocation.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val rotation = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        when (display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_0 -> SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_X, SensorManager.AXIS_Y, remapped)
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remapped)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped)
        }
        SensorManager.getOrientation(remapped, orientation)
        val magnetic = BearingMath.normalizeDegrees(Math.toDegrees(orientation[0].toDouble()))
        magneticHeading = magnetic

        lastHeadingSample?.let { previous ->
            val movement = abs(BearingMath.angleDifference(magnetic, previous))
            headingStabilityEstimate = (headingStabilityEstimate * 0.88 + movement * 0.12).coerceIn(0.4, 18.0)
        }
        lastHeadingSample = magnetic

        recomputeHeadingFromMagnetic()
        recomputeGpsGeometry()
        maybeAutoCreateTarget()
        renderState()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            headingAccuracy = accuracy
            renderState()
        }
    }

    private fun recomputeHeadingFromMagnetic() {
        val magnetic = magneticHeading ?: return
        val declination = if (trueNorthCorrection) {
            currentLocation?.let {
                GeomagneticField(
                    it.latitude.toFloat(),
                    it.longitude.toFloat(),
                    if (it.hasAltitude()) it.altitude.toFloat() else 0f,
                    System.currentTimeMillis()
                ).declination.toDouble()
            } ?: 0.0
        } else 0.0
        trueHeading = BearingMath.normalizeDegrees(magnetic + declination)
    }

    private fun maybeAutoCreateTarget() {
        if (autoTargetPlaced || treeLat != null) return
        val location = currentLocation ?: return
        if (location.accuracy > 15f) return
        if (trueHeading == null) return
        if (arFrameState?.tracking != true) return
        placeTarget(35, silent = true)
    }

    private fun placeTarget(distanceMeters: Int, silent: Boolean = false) {
        val location = currentLocation
        val heading = trueHeading
        if (location == null || heading == null) {
            if (!silent) showToast("Wait for GPS and heading before creating a tree")
            return
        }
        if (arFrameState?.tracking != true) {
            if (!silent) showToast("Wait for AR tracking before creating a tree")
            return
        }

        val target = destinationPoint(location.latitude, location.longitude, heading, distanceMeters.toDouble())
        treeLat = target.first
        treeLng = target.second
        autoTargetPlaced = true
        gpsDistanceMeters = distanceMeters.toFloat()
        gpsBearing = heading
        directionDelta = 0.0

        arView.clearTargetAnchor()
        arView.placeTargetAhead(distanceMeters.toFloat())
        if (!silent) showToast("Tree target locked ${distanceMeters} m ahead")
        recomputeGpsGeometry()
        renderState()
    }

    private fun recomputeGpsGeometry() {
        val location = currentLocation ?: return
        val lat = treeLat ?: return
        val lng = treeLng ?: return
        val result = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, lat, lng, result)
        gpsDistanceMeters = result[0]
        gpsBearing = BearingMath.bearingDegrees(location.latitude, location.longitude, lat, lng)
        val heading = trueHeading
        directionDelta = if (heading != null) BearingMath.angleDifference(gpsBearing!!, heading) else null
    }

    private fun renderState() {
        val ar = arFrameState
        val distance = if (ar?.tracking == true && ar.anchorReady) ar.distanceMeters else gpsDistanceMeters
        val ready = treeLat != null && (ar?.tracking == true || trueHeading != null)
        val arLocked = ar?.tracking == true && ar.anchorReady

        overlay.updateTarget(
            directionDegrees = directionDelta,
            distanceMeters = distance?.toDouble(),
            gpsQuality = locationQuality,
            ready = ready,
            arTracking = arLocked,
            arScreenX = ar?.screenX,
            arScreenY = ar?.screenY,
            arInFront = ar?.inFront == true,
            showDistance = showDistance,
            showGuidance = showGuidance
        )

        tvGpsStatus.text = if (gpsAccuracy < Float.MAX_VALUE) {
            "GPS  ${shortGpsQuality()}  ±${String.format("%.1f", gpsAccuracy)} m"
        } else "GPS  WAITING"

        tvCompassStatus.text = when {
            trueHeading == null -> "HEADING  WAITING"
            headingAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE -> "HEADING  CALIBRATE"
            else -> "HEADING  ±${String.format("%.1f", headingStabilityEstimate)}°"
        }

        gpsWarning.visibility = if (gpsAccuracy > 15f && gpsAccuracy < Float.MAX_VALUE) View.VISIBLE else View.GONE

        tvDirection.visibility = if (showGuidance) View.VISIBLE else View.GONE
        tvDirection.text = directionText(ar)
        tvDirection.setTextColor(
            if (arLocked && ar?.inFront == false) 0xFFFF554D.toInt() else 0xFF55E66F.toInt()
        )

        tvCalibrationHeading.text = "Heading quality: ${headingQuality()}  ·  ${trueHeading?.let { String.format("%.1f°", it) } ?: "--"}"
        updateDiagnostics()
    }

    private fun directionText(ar: ArCoreCameraView.FrameState?): String {
        if (treeLat == null) return "Acquiring target…"
        if (ar?.tracking == true && ar.anchorReady) {
            if (!ar.inFront) return "Tree is behind you"
            val x = ar.screenX
            if (x != null) {
                return when {
                    x < 0.43f -> directionDelta?.let { "Turn left ${abs(it).toInt()}°" } ?: "Turn left"
                    x > 0.57f -> directionDelta?.let { "Turn right ${abs(it).toInt()}°" } ?: "Turn right"
                    else -> "Tree is ahead"
                }
            }
        }
        val delta = directionDelta ?: return "Acquiring direction…"
        return when {
            abs(delta) <= 3.0 -> "Tree is ahead"
            abs(delta) >= 150.0 -> "Tree is behind you"
            delta < 0 -> "Turn left ${abs(delta).toInt()}°"
            else -> "Turn right ${abs(delta).toInt()}°"
        }
    }

    private fun updateDiagnostics() {
        val ar = arFrameState
        tvDiagnostics.text = buildString {
            appendLine("TRACKING")
            appendLine("ARCore status       ${arCoreStatus}")
            appendLine("Camera tracking     ${ar?.cameraTrackingState ?: "--"}")
            appendLine("World anchor        ${if (ar?.anchorReady == true) "ACTIVE" else "--"}")
            appendLine("Anchor in front     ${ar?.inFront ?: "--"}")
            appendLine("AR screen X         ${ar?.screenX?.let { String.format("%.3f", it) } ?: "--"}")
            appendLine("AR screen Y         ${ar?.screenY?.let { String.format("%.3f", it) } ?: "--"}")
            appendLine("AR distance         ${ar?.distanceMeters?.let { String.format("%.2f m", it) } ?: "--"}")
            appendLine()
            appendLine("GLOBAL POSITION")
            appendLine("GPS quality         $locationQuality")
            appendLine("GPS accuracy        ${if (gpsAccuracy < Float.MAX_VALUE) String.format("±%.1f m", gpsAccuracy) else "--"}")
            appendLine("Phone GPS           ${locationText(currentLocation)}")
            appendLine("Tree GPS            ${treeText()}")
            appendLine("GPS distance        ${gpsDistanceMeters?.let { String.format("%.2f m", it) } ?: "--"}")
            appendLine("GPS bearing         ${gpsBearing?.let { String.format("%.1f°", it) } ?: "--"}")
            appendLine()
            appendLine("HEADING")
            appendLine("Magnetic heading    ${magneticHeading?.let { String.format("%.1f°", it) } ?: "--"}")
            appendLine("True heading        ${trueHeading?.let { String.format("%.1f°", it) } ?: "--"}")
            appendLine("Heading stability   ±${String.format("%.1f°", headingStabilityEstimate)}")
            appendLine("Direction delta     ${directionDelta?.let { String.format("%+.1f°", it) } ?: "--"}")
            appendLine()
            appendLine("SETTINGS")
            appendLine("True north          ${if (trueNorthCorrection) "ON" else "OFF"}")
            appendLine("Micro stabilization ${if (microStabilization) "ON" else "OFF"}")
            appendLine("Show distance       ${if (showDistance) "ON" else "OFF"}")
            append("Show guidance       ${if (showGuidance) "ON" else "OFF"}")
        }
    }

    private fun showPanel(panel: View) {
        panels.forEach { it.visibility = View.GONE }
        panel.visibility = View.VISIBLE
        if (panel == panelDiagnostics) updateDiagnostics()
    }

    private fun showHome() {
        panels.forEach { it.visibility = View.GONE }
    }

    override fun onBackPressed() {
        if (panels.any { it.visibility == View.VISIBLE }) showHome() else super.onBackPressed()
    }

    private fun updateCreateDistanceUi() {
        tvCreateDistance.text = "$createDistanceMeters m"
        tvCreateDistanceHero.text = "$createDistanceMeters m"
    }

    private fun shortGpsQuality(): String = when (locationQuality) {
        "EXCELLENT" -> "GOOD"
        else -> locationQuality
    }

    private fun headingQuality(): String = when {
        trueHeading == null -> "Acquiring"
        headingAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE -> "Calibrate"
        headingStabilityEstimate <= 3.0 -> "Good"
        else -> "Fair"
    }

    private fun destinationPoint(
        lat: Double,
        lng: Double,
        bearingDeg: Double,
        distanceMeters: Double
    ): Pair<Double, Double> {
        val earthRadius = 6_371_000.0
        val angularDistance = distanceMeters / earthRadius
        val bearing = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(lat)
        val lng1 = Math.toRadians(lng)
        val lat2 = asin(
            sin(lat1) * cos(angularDistance) +
                cos(lat1) * sin(angularDistance) * cos(bearing)
        )
        val lng2 = lng1 + atan2(
            sin(bearing) * sin(angularDistance) * cos(lat1),
            cos(angularDistance) - sin(lat1) * sin(lat2)
        )
        return Math.toDegrees(lat2) to Math.toDegrees(lng2)
    }

    private fun treeText(): String = if (treeLat != null && treeLng != null) {
        String.format("%.7f, %.7f", treeLat, treeLng)
    } else "--"

    private fun locationText(location: Location?): String = location?.let {
        String.format("%.7f, %.7f", it.latitude, it.longitude)
    } ?: "--"

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun showToast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
