package com.example.treedirectiondemo

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
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
    private val prefs by lazy { getSharedPreferences("tree_navigator", MODE_PRIVATE) }
    private val targetStore by lazy { TreeTargetStore(this) }

    private var arSession: Session? = null
    private var arRunning = false
    private var installRequested = false
    private var geospatialSupported = true

    private var arFrame = emptyArFrame()
    private var previousTargetState = ArCoreCameraView.TargetState.NONE

    private var activeTarget: TreeTargetStore.Target? = null
    private var targetResolutionRequested = false

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

    private var selectedDistance = 35
    private var elevationOffset = 0
    private var showDistance = true
    private var showGuidance = true
    private var declinationEnabled = true
    private var headingSmoothing = "Balanced"
    private var gpsSmoothing = "High"
    private var previousScreen = TreeNavigatorUiView.Screen.HOME

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            toast("Precise location is required for Geospatial navigation")
            renderUi()
            return@registerForActivityResult
        }
        startProductionServices()
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::acceptLocation)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSettings()
        activeTarget = targetStore.fromIntent(intent)?.also(targetStore::save) ?: targetStore.load()
        setContentView(R.layout.activity_main)

        arView = findViewById(R.id.arView)
        uiView = findViewById(R.id.uiView)
        arView.setDisplayRotation(display?.rotation ?: Surface.ROTATION_0)
        arView.onFrameState = { frame ->
            arFrame = frame
            handleTargetStateTransition(frame.targetState)
            renderUi()
        }
        uiView.onAction = ::handleUiAction

        if (hasGeospatialConsent()) {
            requestPermissionsIfNeededOrStart()
        } else {
            showGeospatialConsent()
        }
        renderUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        targetStore.fromIntent(intent)?.let { target ->
            setProductionTarget(target)
            toast("Tree ${target.treeId} loaded")
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasGeospatialConsent() && hasRequiredPermissions()) {
            startProductionServices()
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        fusedLocation.removeLocationUpdates(locationCallback)
        if (arRunning) {
            // GL surface must stop before Session.pause() so it cannot call update() on a paused session.
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

    private fun hasGeospatialConsent(): Boolean = prefs.getBoolean(KEY_GEOSPATIAL_CONSENT, false)

    private fun showGeospatialConsent() {
        AlertDialog.Builder(this)
            .setTitle("AR navigation data use")
            .setMessage(
                "Tree Navigator uses your camera and precise location to resolve real-world tree positions. " +
                    "This application runs on Google Play Services for AR (ARCore), which is provided by " +
                    "Google LLC and governed by the Google Privacy Policy.\n\n" +
                    "Continue only if you agree to use these device data for AR navigation."
            )
            .setCancelable(false)
            .setPositiveButton("CONTINUE") { _, _ ->
                prefs.edit().putBoolean(KEY_GEOSPATIAL_CONSENT, true).apply()
                requestPermissionsIfNeededOrStart()
            }
            .setNegativeButton("EXIT") { _, _ -> finish() }
            .show()
    }

    private fun requestPermissionsIfNeededOrStart() {
        val needed = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.CAMERA)) needed += Manifest.permission.CAMERA
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) needed += Manifest.permission.ACCESS_FINE_LOCATION
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) needed += Manifest.permission.ACCESS_COARSE_LOCATION
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
        else startProductionServices()
    }

    private fun hasRequiredPermissions(): Boolean =
        hasPermission(Manifest.permission.CAMERA) && hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startProductionServices() {
        registerSensors()
        startLocationUpdates()
        ensureArSession()
    }

    private fun ensureArSession() {
        if (!hasRequiredPermissions() || arRunning) return
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }

            val session = arSession ?: Session(this).also { s ->
                geospatialSupported = s.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)
                if (!geospatialSupported) {
                    toast("This device does not support ARCore Geospatial")
                }

                val config = Config(s).apply {
                    focusMode = Config.FocusMode.AUTO
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    // Horizontal planes can improve dynamic alignment of terrain anchors on ground.
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                    if (geospatialSupported) geospatialMode = Config.GeospatialMode.ENABLED
                }
                s.configure(config)
                arSession = s
                arView.attachSession(s)
            }

            arView.setDisplayRotation(display?.rotation ?: Surface.ROTATION_0)
            session.resume()
            arView.onResume()
            arRunning = true
            requestActiveTargetResolution(force = true)
        } catch (t: Throwable) {
            geospatialSupported = false
            toast("Unable to start Geospatial AR: ${t.javaClass.simpleName}")
            renderUi()
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
        sensorManager.unregisterListener(this)
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

        if (location.hasAccuracy() && location.accuracy > 50f && filteredLocation != null) {
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

        recomputeGlobalGeometry()
        renderUi()
    }

    /**
     * Creates a generated test target, but resolves it through the exact same Terrain Anchor path
     * as a production tree. The only difference is where its fixed lat/lng came from.
     */
    private fun createGeneratedTestTarget(location: Location, heading: Double, distance: Int, elevation: Int) {
        val dest = destinationPoint(location.latitude, location.longitude, heading, distance.toDouble())
        val target = TreeTargetStore.Target(
            treeId = "TEST-${System.currentTimeMillis()}",
            latitude = dest.first,
            longitude = dest.second,
            altitudeAboveTerrainM = elevation.toDouble(),
            source = TreeTargetStore.Target.Source.GENERATED_TEST
        )
        setProductionTarget(target)
    }

    private fun setProductionTarget(target: TreeTargetStore.Target) {
        activeTarget = target
        targetStore.save(target)
        targetResolutionRequested = false
        gpsBearing = null
        filteredBearing = null
        previousTargetState = ArCoreCameraView.TargetState.NONE
        recomputeGlobalGeometry()
        requestActiveTargetResolution(force = true)
        renderUi()
    }

    private fun requestActiveTargetResolution(force: Boolean = false) {
        val target = activeTarget ?: return
        if (!arRunning || !geospatialSupported) return
        if (targetResolutionRequested && !force) return
        targetResolutionRequested = true
        arView.replaceTerrainTarget(
            target.latitude,
            target.longitude,
            target.altitudeAboveTerrainM
        )
    }

    private fun recomputeGlobalGeometry() {
        val loc = filteredLocation ?: return
        val target = activeTarget ?: return
        val bearing = BearingMath.bearingDegrees(loc.latitude, loc.longitude, target.latitude, target.longitude)
        gpsBearing = bearing
        filteredBearing = filteredBearing?.let { previous ->
            val error = BearingMath.angleDifference(bearing, previous)
            BearingMath.normalizeDegrees(previous + error * if (gpsAccuracy <= 8f) 0.20 else 0.08)
        } ?: bearing
    }

    private fun handleTargetStateTransition(newState: ArCoreCameraView.TargetState) {
        if (newState == previousTargetState) return
        when (newState) {
            ArCoreCameraView.TargetState.LOCKED -> toast("Tree is geospatially locked")
            ArCoreCameraView.TargetState.ERROR -> toast("Target resolve failed: ${arFrame.targetError ?: "unknown error"}")
            ArCoreCameraView.TargetState.TRACKING_LOST -> {
                if (previousTargetState == ArCoreCameraView.TargetState.LOCKED) {
                    toast("Localization degraded — marker hidden until tracking recovers")
                }
            }
            else -> Unit
        }
        previousTargetState = newState
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
                saveSettings(); renderUi()
            }
            TreeNavigatorUiView.Action.CYCLE_ELEVATION -> {
                elevationOffset = when (elevationOffset) {
                    0 -> 1; 1 -> 2; 2 -> -2; -2 -> -1; else -> 0
                }
                saveSettings(); renderUi()
            }
            TreeNavigatorUiView.Action.CREATE_TARGET -> {
                val loc = filteredLocation ?: rawLocation
                val heading = filteredHeading ?: trueHeading
                when {
                    !geospatialSupported -> toast("Geospatial mode is not supported on this device")
                    !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) -> toast("Precise location is required")
                    loc == null -> toast("Waiting for precise location")
                    heading == null -> toast("Waiting for heading sensor")
                    !arRunning -> toast("AR session is not ready")
                    else -> {
                        createGeneratedTestTarget(loc, heading, selectedDistance, elevationOffset)
                        uiView.screen = TreeNavigatorUiView.Screen.HOME
                        toast("Resolving terrain target…")
                    }
                }
            }
            TreeNavigatorUiView.Action.TOGGLE_DISTANCE -> {
                showDistance = !showDistance; saveSettings(); renderUi()
            }
            TreeNavigatorUiView.Action.TOGGLE_GUIDANCE -> {
                showGuidance = !showGuidance; saveSettings(); renderUi()
            }
            TreeNavigatorUiView.Action.TOGGLE_DECLINATION -> {
                declinationEnabled = !declinationEnabled; saveSettings(); renderUi()
            }
            TreeNavigatorUiView.Action.CYCLE_HEADING_SMOOTHING -> {
                headingSmoothing = when (headingSmoothing) {
                    "Balanced" -> "Stable"; "Stable" -> "Responsive"; else -> "Balanced"
                }
                saveSettings(); renderUi()
            }
            TreeNavigatorUiView.Action.CYCLE_GPS_SMOOTHING -> {
                gpsSmoothing = when (gpsSmoothing) {
                    "High" -> "Medium"; "Medium" -> "Low"; else -> "High"
                }
                saveSettings(); renderUi()
            }
        }
    }

    private fun open(screen: TreeNavigatorUiView.Screen) {
        previousScreen = if (uiView.screen == TreeNavigatorUiView.Screen.MENU) {
            TreeNavigatorUiView.Screen.HOME
        } else uiView.screen
        uiView.screen = screen
        renderUi()
    }

    private fun renderUi() {
        val heading = filteredHeading ?: trueHeading
        val target = activeTarget
        val fallbackDelta = if (heading != null && filteredBearing != null) {
            BearingMath.angleDifference(filteredBearing!!, heading)
        } else null

        val worldLocked =
            arFrame.targetState == ArCoreCameraView.TargetState.LOCKED &&
                arFrame.anchorReady &&
                arFrame.geospatialState == ArCoreCameraView.GeospatialState.LOCALIZED

        val direction = if (worldLocked) arFrame.horizontalAngleDeg else fallbackDelta
        val arDistance = if (worldLocked) arFrame.distanceMeters?.toDouble() else null
        val globalDistance = if (target != null) filteredLocation?.let { loc ->
            val out = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, target.latitude, target.longitude, out)
            out[0].toDouble()
        } else null

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
            !hasGeospatialConsent() -> "CONSENT REQUIRED"
            !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) -> "PRECISE LOCATION REQUIRED"
            !geospatialSupported -> "GEOSPATIAL UNSUPPORTED"
            arFrame.geospatialState == ArCoreCameraView.GeospatialState.ERROR -> "EARTH ERROR: ${arFrame.earthState}"
            arFrame.targetState == ArCoreCameraView.TargetState.ERROR -> "TARGET ERROR: ${arFrame.targetError ?: arFrame.terrainResolveState}"
            arFrame.targetState == ArCoreCameraView.TargetState.RESOLVING_TERRAIN -> "RESOLVING TERRAIN"
            arFrame.targetState == ArCoreCameraView.TargetState.WAITING_FOR_GEOSPATIAL -> "WAITING FOR GEO LOCALIZATION"
            arFrame.targetState == ArCoreCameraView.TargetState.TRACKING_LOST -> "LOCALIZATION DEGRADED"
            worldLocked && !arFrame.inFront -> "TARGET BEHIND"
            worldLocked && (arFrame.screenX == null || arFrame.screenY == null) -> "TARGET OFF SCREEN"
            worldLocked -> "GEOSPATIAL LOCKED"
            arFrame.geospatialState == ArCoreCameraView.GeospatialState.LOCALIZING -> "LOCALIZING"
            arFrame.geospatialState == ArCoreCameraView.GeospatialState.LOCALIZED -> "READY"
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
            targetScreenX = if (worldLocked) arFrame.screenX else null,
            targetScreenY = if (worldLocked) arFrame.screenY else null,
            targetInFront = worldLocked && arFrame.inFront,
            targetReady = worldLocked,
            targetRequested = target != null,
            arTracking = worldLocked,
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
            treeLat = target?.latitude,
            treeLng = target?.longitude,
            arStatus = arStatus,
            targetId = target?.treeId ?: "--",
            targetSource = target?.source?.name ?: "NONE",
            geospatialState = arFrame.geospatialState.name,
            earthState = arFrame.earthState,
            geospatialHorizontalAccuracyM = arFrame.geospatialHorizontalAccuracyM,
            geospatialYawAccuracyDeg = arFrame.geospatialYawAccuracyDeg,
            terrainResolveState = arFrame.terrainResolveState,
            apiKeyPresent = BuildConfig.ARCORE_API_KEY_PRESENT
        )
    }

    private fun loadSettings() {
        selectedDistance = prefs.getInt("selected_distance", 35).coerceIn(10, 100)
        elevationOffset = prefs.getInt("elevation_offset", 0).coerceIn(-2, 2)
        showDistance = prefs.getBoolean("show_distance", true)
        showGuidance = prefs.getBoolean("show_guidance", true)
        declinationEnabled = prefs.getBoolean("declination", true)
        headingSmoothing = prefs.getString("heading_smoothing", "Balanced") ?: "Balanced"
        gpsSmoothing = prefs.getString("gps_smoothing", "High") ?: "High"
    }

    private fun saveSettings() {
        prefs.edit()
            .putInt("selected_distance", selectedDistance)
            .putInt("elevation_offset", elevationOffset)
            .putBoolean("show_distance", showDistance)
            .putBoolean("show_guidance", showGuidance)
            .putBoolean("declination", declinationEnabled)
            .putString("heading_smoothing", headingSmoothing)
            .putString("gps_smoothing", gpsSmoothing)
            .apply()
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

    private fun emptyArFrame() = ArCoreCameraView.FrameState(
        tracking = false,
        anchorReady = false,
        targetState = ArCoreCameraView.TargetState.NONE,
        geospatialState = ArCoreCameraView.GeospatialState.DISABLED,
        earthState = "DISABLED",
        geospatialHorizontalAccuracyM = null,
        geospatialYawAccuracyDeg = null,
        terrainResolveState = "NONE",
        targetError = null,
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

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val KEY_GEOSPATIAL_CONSENT = "geospatial_consent"
    }
}
