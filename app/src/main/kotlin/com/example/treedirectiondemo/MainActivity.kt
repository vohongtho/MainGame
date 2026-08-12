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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var arView: ArCoreCameraView
    private lateinit var uiView: TreeNavigatorUiView

    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val fusedLocation by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var arSession: Session? = null
    private var arRunning = false
    private var installRequested = false
    private var arFrame = ArCoreCameraView.FrameState(
        tracking = false,
        anchorReady = false,
        screenX = null,
        screenY = null,
        inFront = false,
        distanceMeters = null,
        horizontalAngleDeg = null,
        verticalAngleDeg = null,
        movementSinceAnchorMeters = null,
        trackingFailureReason = "NONE",
        cameraTrackingState = TrackingState.PAUSED
    )

    // AR values are allowed to freeze briefly across a tracking pause, but are never replaced by GPS.
    private var lastGoodArDistance: Double? = null
    private var lastGoodArDirection: Double? = null

    private var rawLocation: Location? = null
    private var filteredLocation: Location? = null
    private var gpsAccuracy = Float.MAX_VALUE
    private var gpsBearing: Double? = null
    private var filteredBearing: Double? = null

    private var magneticHeading: Double? = null
    private var trueHeading: Double? = null
    private var filteredHeading: Double? = null
    private var gameYaw: Double? = null
    private var turnSpeed = 0.0
    private var headingStability = 2.0
    private var sensorAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    private var previousGameYaw: Double? = null
    private var previousGameTimestamp = 0L

    private var treeLat: Double? = null
    private var treeLng: Double? = null
    private var selectedDistance = 35
    private var elevationOffset = 0
    private var autoTargetPending = true

    private var showDistance = true
    private var showGuidance = true
    private var declinationEnabled = true
    private var headingSmoothing = "Balanced"
    private var gpsSmoothing = "High"
    private var previousScreen = TreeNavigatorUiView.Screen.HOME

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) startLocationUpdates()
        if (hasPermission(Manifest.permission.CAMERA)) ensureArSession()
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::acceptLocation)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        arView = findViewById(R.id.arView)
        uiView = findViewById(R.id.uiView)

        arView.setDisplayRotation(display?.rotation ?: Surface.ROTATION_0)
        arView.onFrameState = { frame ->
            arFrame = frame
            if (frame.tracking && frame.anchorReady) {
                frame.distanceMeters?.let { lastGoodArDistance = it.toDouble() }
                frame.horizontalAngleDeg?.let { lastGoodArDirection = it }
            }
            maybeAutoCreateTarget()
            renderUi()
        }
        uiView.onAction = ::handleUiAction

        requestPermissionsIfNeeded()
        renderUi()
    }

    override fun onResume() {
        super.onResume()
        registerSensors()
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) startLocationUpdates()
        if (hasPermission(Manifest.permission.CAMERA)) ensureArSession()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        fusedLocation.removeLocationUpdates(locationCallback)
        if (arRunning) {
            arView.onPause()
            arSession?.pause()
            arRunning = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        arView.detachSession()
        arSession?.close()
        arSession = null
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.CAMERA)) needed += Manifest.permission.CAMERA
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) needed += Manifest.permission.ACCESS_FINE_LOCATION
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) needed += Manifest.permission.ACCESS_COARSE_LOCATION
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun ensureArSession() {
        if (!hasPermission(Manifest.permission.CAMERA) || arRunning) return
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }

            val session = arSession ?: Session(this).also { s ->
                val config = Config(s).apply {
                    focusMode = Config.FocusMode.AUTO
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    planeFindingMode = Config.PlaneFindingMode.DISABLED
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                }
                s.configure(config)
                arSession = s
                arView.attachSession(s)
            }

            arView.setDisplayRotation(display?.rotation ?: Surface.ROTATION_0)
            session.resume()
            arView.onResume()
            arRunning = true
        } catch (_: Throwable) {
            toast("ARCore is unavailable on this device")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 900L)
            .setMinUpdateIntervalMillis(450L)
            .setMinUpdateDistanceMeters(0.5f)
            .setWaitForAccurateLocation(false)
            .build()
        fusedLocation.removeLocationUpdates(locationCallback)
        fusedLocation.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun registerSensors() {
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val yaw = extractYaw(event) ?: return
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> updateGameYaw(yaw, event.timestamp)
            Sensor.TYPE_ROTATION_VECTOR -> updateAbsoluteHeading(yaw)
        }
        maybeAutoCreateTarget()
        recomputeGlobalGeometry()
        renderUi()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            sensorAccuracy = accuracy
            renderUi()
        }
    }

    private fun extractYaw(event: SensorEvent): Double? {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR && event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return null
        val rotation = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        when (display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_0 -> SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_X, SensorManager.AXIS_Y, remapped)
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remapped)
            else -> SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped)
        }
        SensorManager.getOrientation(remapped, orientation)
        return BearingMath.normalizeDegrees(Math.toDegrees(orientation[0].toDouble()))
    }

    private fun updateGameYaw(yaw: Double, timestamp: Long) {
        gameYaw = yaw
        previousGameYaw?.let { previous ->
            val dt = (timestamp - previousGameTimestamp) / 1_000_000_000.0
            if (dt > 0.002) {
                val speed = abs(BearingMath.angleDifference(yaw, previous)) / dt
                turnSpeed = turnSpeed * 0.78 + min(speed, 360.0) * 0.22
            }
        }
        previousGameYaw = yaw
        previousGameTimestamp = timestamp
    }

    private fun updateAbsoluteHeading(magnetic: Double) {
        magneticHeading = magnetic
        val loc = filteredLocation ?: rawLocation
        val declination = if (declinationEnabled && loc != null) {
            GeomagneticField(
                loc.latitude.toFloat(),
                loc.longitude.toFloat(),
                if (loc.hasAltitude()) loc.altitude.toFloat() else 0f,
                System.currentTimeMillis()
            ).declination.toDouble()
        } else 0.0

        val absolute = BearingMath.normalizeDegrees(magnetic + declination)
        trueHeading = absolute
        val previous = filteredHeading
        if (previous == null) {
            filteredHeading = absolute
        } else {
            val error = BearingMath.angleDifference(absolute, previous)
            headingStability = headingStability * 0.90 + abs(error) * 0.10
            val alpha = when (headingSmoothing) {
                "Stable" -> 0.06
                "Responsive" -> 0.26
                else -> 0.13
            }
            val adaptive = if (turnSpeed > 30.0) max(alpha, 0.45) else alpha
            filteredHeading = BearingMath.normalizeDegrees(previous + error * adaptive)
        }
    }

    private fun acceptLocation(location: Location) {
        rawLocation = location
        gpsAccuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE

        if (location.hasAccuracy() && location.accuracy > 40f && filteredLocation != null) {
            renderUi()
            return
        }

        val previous = filteredLocation
        filteredLocation = if (previous == null) {
            Location(location)
        } else {
            val displacement = previous.distanceTo(location).toDouble()
            val base = when (gpsSmoothing) {
                "Low" -> 0.55
                "Medium" -> 0.34
                else -> 0.20
            }
            val accuracyWeight = when {
                gpsAccuracy <= 4f -> 1.0
                gpsAccuracy <= 8f -> 0.75
                gpsAccuracy <= 15f -> 0.45
                else -> 0.18
            }
            val motionBoost = if ((location.hasSpeed() && location.speed > 0.7f) || displacement > 5.0) 0.18 else 0.0
            val alpha = (base * accuracyWeight + motionBoost).coerceIn(0.05, 0.70)
            Location(location).apply {
                latitude = previous.latitude + (location.latitude - previous.latitude) * alpha
                longitude = previous.longitude + (location.longitude - previous.longitude) * alpha
            }
        }

        maybeAutoCreateTarget()
        recomputeGlobalGeometry()
        renderUi()
    }

    private fun maybeAutoCreateTarget() {
        if (!autoTargetPending || treeLat != null) return
        val loc = filteredLocation ?: return
        val heading = filteredHeading ?: trueHeading ?: return
        if (gpsAccuracy > 10f || !arFrame.tracking) return
        createTarget(loc, heading, selectedDistance, elevationOffset)
        autoTargetPending = false
    }

    private fun createTarget(location: Location, heading: Double, distance: Int, elevation: Int) {
        val dest = destinationPoint(location.latitude, location.longitude, heading, distance.toDouble())
        treeLat = dest.first
        treeLng = dest.second
        gpsBearing = heading
        filteredBearing = heading
        lastGoodArDistance = null
        lastGoodArDirection = null
        arView.clearTargetAnchor()
        arView.placeTargetAhead(distance.toFloat(), elevation.toFloat())
        recomputeGlobalGeometry()
    }

    /** GPS geometry remains diagnostic/global-reference data after the AR target has been requested. */
    private fun recomputeGlobalGeometry() {
        val loc = filteredLocation ?: return
        val lat = treeLat ?: return
        val lng = treeLng ?: return
        val bearing = BearingMath.bearingDegrees(loc.latitude, loc.longitude, lat, lng)
        gpsBearing = bearing
        filteredBearing = filteredBearing?.let { previous ->
            val error = BearingMath.angleDifference(bearing, previous)
            BearingMath.normalizeDegrees(previous + error * if (gpsAccuracy <= 8f) 0.20 else 0.08)
        } ?: bearing
    }

    private fun handleUiAction(action: TreeNavigatorUiView.Action) {
        when (action) {
            TreeNavigatorUiView.Action.OPEN_MENU -> open(TreeNavigatorUiView.Screen.MENU)
            TreeNavigatorUiView.Action.OPEN_SETTINGS -> open(TreeNavigatorUiView.Screen.SETTINGS)
            TreeNavigatorUiView.Action.OPEN_CREATE -> open(TreeNavigatorUiView.Screen.CREATE)
            TreeNavigatorUiView.Action.OPEN_DIAGNOSTICS -> open(TreeNavigatorUiView.Screen.DIAGNOSTICS)
            TreeNavigatorUiView.Action.OPEN_CALIBRATION -> open(TreeNavigatorUiView.Screen.CALIBRATION)
            TreeNavigatorUiView.Action.OPEN_GUIDE -> open(TreeNavigatorUiView.Screen.GUIDE)
            TreeNavigatorUiView.Action.OPEN_ABOUT -> open(TreeNavigatorUiView.Screen.ABOUT)
            TreeNavigatorUiView.Action.GO_HOME -> {
                uiView.screen = TreeNavigatorUiView.Screen.HOME
                renderUi()
            }
            TreeNavigatorUiView.Action.BACK -> {
                uiView.screen = previousScreen
                renderUi()
            }
            TreeNavigatorUiView.Action.EXIT -> finish()
            TreeNavigatorUiView.Action.CYCLE_DISTANCE -> {
                selectedDistance = if (selectedDistance >= 100) 10 else selectedDistance + 5
                renderUi()
            }
            TreeNavigatorUiView.Action.CYCLE_ELEVATION -> {
                elevationOffset = when (elevationOffset) {
                    0 -> 1
                    1 -> 2
                    2 -> -2
                    -2 -> -1
                    else -> 0
                }
                renderUi()
            }
            TreeNavigatorUiView.Action.CREATE_TARGET -> {
                val loc = filteredLocation ?: rawLocation
                val heading = filteredHeading ?: trueHeading
                if (loc == null || heading == null || !arFrame.tracking) {
                    toast("Wait for GPS, heading and AR tracking")
                } else {
                    createTarget(loc, heading, selectedDistance, elevationOffset)
                    autoTargetPending = false
                    uiView.screen = TreeNavigatorUiView.Screen.HOME
                    toast("Locking target ${selectedDistance} m ahead…")
                }
            }
            TreeNavigatorUiView.Action.TOGGLE_DISTANCE -> {
                showDistance = !showDistance
                renderUi()
            }
            TreeNavigatorUiView.Action.TOGGLE_GUIDANCE -> {
                showGuidance = !showGuidance
                renderUi()
            }
            TreeNavigatorUiView.Action.TOGGLE_DECLINATION -> {
                declinationEnabled = !declinationEnabled
                renderUi()
            }
            TreeNavigatorUiView.Action.CYCLE_HEADING_SMOOTHING -> {
                headingSmoothing = when (headingSmoothing) {
                    "Balanced" -> "Stable"
                    "Stable" -> "Responsive"
                    else -> "Balanced"
                }
                renderUi()
            }
            TreeNavigatorUiView.Action.CYCLE_GPS_SMOOTHING -> {
                gpsSmoothing = when (gpsSmoothing) {
                    "High" -> "Medium"
                    "Medium" -> "Low"
                    else -> "High"
                }
                renderUi()
            }
        }
    }

    private fun open(screen: TreeNavigatorUiView.Screen) {
        previousScreen = if (uiView.screen == TreeNavigatorUiView.Screen.MENU) {
            TreeNavigatorUiView.Screen.HOME
        } else {
            uiView.screen
        }
        uiView.screen = screen
        renderUi()
    }

    private fun renderUi() {
        val heading = filteredHeading ?: trueHeading
        val fallbackDelta = if (heading != null && filteredBearing != null) {
            BearingMath.angleDifference(filteredBearing!!, heading)
        } else null

        val anchorExists = arFrame.anchorReady
        val arTrackingTarget = anchorExists && arFrame.tracking

        // After the AR anchor exists, AR camera-space is authoritative. GPS is never substituted
        // into the main marker/distance contract.
        val direction = if (arTrackingTarget) {
            arFrame.horizontalAngleDeg ?: lastGoodArDirection
        } else if (!anchorExists) {
            fallbackDelta
        } else {
            lastGoodArDirection
        }

        val arDistance = if (arTrackingTarget) {
            arFrame.distanceMeters?.toDouble() ?: lastGoodArDistance
        } else if (anchorExists) {
            lastGoodArDistance
        } else null

        val globalDistance = filteredLocation?.let { loc ->
            val lat = treeLat
            val lng = treeLng
            if (lat != null && lng != null) {
                val out = FloatArray(1)
                Location.distanceBetween(loc.latitude, loc.longitude, lat, lng, out)
                out[0].toDouble()
            } else null
        }

        val gpsQuality = when {
            gpsAccuracy <= 8f -> "GOOD"
            gpsAccuracy <= 15f -> "FAIR"
            gpsAccuracy < Float.MAX_VALUE -> "POOR"
            else -> "WAITING"
        }

        val headingQuality = when {
            sensorAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE -> "CALIBRATE"
            heading != null -> "GOOD"
            else -> "WAITING"
        }

        val arStatus = when {
            arTrackingTarget && arFrame.screenX != null && arFrame.screenY != null -> "WORLD LOCKED"
            arTrackingTarget && !arFrame.inFront -> "TARGET BEHIND"
            arTrackingTarget -> "TARGET OFF SCREEN"
            anchorExists -> "TRACKING PAUSED"
            treeLat != null -> "LOCKING TARGET"
            arFrame.tracking -> "READY TO LOCK"
            else -> "ACQUIRING"
        }

        uiView.model = TreeNavigatorUiView.Model(
            gpsQuality = gpsQuality,
            gpsAccuracy = gpsAccuracy,
            headingQuality = headingQuality,
            headingAccuracyDeg = headingStability.coerceIn(0.8,15.0),
            targetDistanceM = arDistance,
            gpsDistanceM = globalDistance,
            directionDeltaDeg = direction,
            targetScreenX = if (arTrackingTarget) arFrame.screenX else null,
            targetScreenY = if (arTrackingTarget) arFrame.screenY else null,
            targetInFront = arTrackingTarget && arFrame.inFront,
            targetReady = anchorExists,
            targetRequested = treeLat != null,
            arTracking = arTrackingTarget,
            arMovementM = arFrame.movementSinceAnchorMeters?.toDouble(),
            arFailureReason = arFrame.trackingFailureReason,
            selectedDistanceM = selectedDistance,
            elevationOffsetM = elevationOffset,
            showDistance = showDistance,
            showGuidance = showGuidance,
            headingSmoothing = headingSmoothing,
            gpsSmoothing = gpsSmoothing,
            declinationEnabled = declinationEnabled,
            gameYaw = gameYaw,
            magneticHeading = magneticHeading,
            trueHeading = trueHeading,
            filteredHeading = filteredHeading,
            turnSpeed = turnSpeed,
            gpsBearing = gpsBearing,
            filteredBearing = filteredBearing,
            phoneLat = filteredLocation?.latitude,
            phoneLng = filteredLocation?.longitude,
            treeLat = treeLat,
            treeLng = treeLng,
            arStatus = arStatus
        )
    }

    private fun destinationPoint(
        lat: Double,
        lng: Double,
        bearingDeg: Double,
        distanceMeters: Double
    ): Pair<Double,Double> {
        val earth = 6_371_000.0
        val d = distanceMeters / earth
        val b = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lng)
        val lat2 = asin(sin(lat1)*cos(d) + cos(lat1)*sin(d)*cos(b))
        val lon2 = lon1 + atan2(
            sin(b)*sin(d)*cos(lat1),
            cos(d)-sin(lat1)*sin(lat2)
        )
        return Math.toDegrees(lat2) to Math.toDegrees(lon2)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
