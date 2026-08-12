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
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: TreeOverlayView
    private lateinit var tvDistance: TextView
    private lateinit var tvDirection: TextView
    private lateinit var tvGpsStatus: TextView
    private lateinit var tvCompassStatus: TextView
    private lateinit var tvCoordinates: TextView
    private lateinit var tvDebug: TextView
    private lateinit var btnReset: Button
    private lateinit var btnDebug: Button

    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val fusedLocation by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var treeLat: Double? = null
    private var treeLng: Double? = null
    private val testTreeDistanceMeters = 35.0

    private var rawLocation: Location? = null
    private var filteredLocation: Location? = null
    private var navigationLocation: Location? = null
    private var gpsAccuracy = Float.MAX_VALUE
    private var locationQuality = "WAITING"
    private var navigationHoldMeters = 0.0
    private var bearingUncertaintyDeg = 180.0
    private var targetAreaMode = false

    private var magneticHeading: Double? = null
    private var trueHeading: Double? = null
    private var gameYaw: Double? = null
    private var fusedHeading: Double? = null
    private var absoluteOffset: Double? = null
    private var headingAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var turnSpeedDegPerSec = 0.0
    private var lastGameYaw: Double? = null
    private var lastGameTimestampNs = 0L

    private var rawBearing: Double? = null
    private var filteredBearing: Double? = null
    private var distanceToTreeMeters: Float? = null
    private var displayDelta: Double? = null

    private var debugVisible = false
    private var autoTreeCreated = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val cameraOk = result[Manifest.permission.CAMERA] == true || hasPermission(Manifest.permission.CAMERA)
        val locationOk = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        if (cameraOk) startCamera()
        if (locationOk) startLocationUpdates()
        if (!locationOk) showToast("Precise location is required for tree navigation")
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { acceptLocation(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupActions()
        requestRuntimePermissions()
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.treeOverlay)
        tvDistance = findViewById(R.id.tvDistance)
        tvDirection = findViewById(R.id.tvDirection)
        tvGpsStatus = findViewById(R.id.tvGpsStatus)
        tvCompassStatus = findViewById(R.id.tvCompassStatus)
        tvCoordinates = findViewById(R.id.tvCoordinates)
        tvDebug = findViewById(R.id.tvDebug)
        btnReset = findViewById(R.id.btnResetTree)
        btnDebug = findViewById(R.id.btnDebug)
    }

    private fun setupActions() {
        btnReset.setOnClickListener {
            autoTreeCreated = false
            treeLat = null
            treeLng = null
            rawBearing = null
            filteredBearing = null
            displayDelta = null
            targetAreaMode = false
            maybeCreateTestTree(force = true)
        }
        btnDebug.setOnClickListener {
            debugVisible = !debugVisible
            tvDebug.visibility = if (debugVisible) View.VISIBLE else View.GONE
            btnDebug.text = if (debugVisible) "Hide details" else "Details"
            renderState()
        }
    }

    private fun requestRuntimePermissions() {
        permissionLauncher.launch(arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        if (!hasPermission(Manifest.permission.CAMERA)) return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 900L)
            .setMinUpdateIntervalMillis(600L)
            .setMinUpdateDistanceMeters(0.8f)
            .setWaitForAccurateLocation(false)
            .build()
        fusedLocation.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        fusedLocation.removeLocationUpdates(locationCallback)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val yaw = extractYaw(event) ?: return
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> onGameYaw(yaw, event.timestamp)
            Sensor.TYPE_ROTATION_VECTOR -> onAbsoluteYaw(yaw)
        }
        maybeCreateTestTree()
        renderState()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            headingAccuracy = accuracy
            renderState()
        }
    }

    private fun extractYaw(event: SensorEvent): Double? {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR && event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return null
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
        return BearingMath.normalizeDegrees(Math.toDegrees(orientation[0].toDouble()))
    }

    private fun onGameYaw(yaw: Double, timestampNs: Long) {
        gameYaw = yaw
        lastGameYaw?.let { previous ->
            val dt = (timestampNs - lastGameTimestampNs) / 1_000_000_000.0
            if (dt > 0.001) {
                val instant = abs(BearingMath.angleDifference(yaw, previous)) / dt
                turnSpeedDegPerSec = turnSpeedDegPerSec * 0.78 + min(instant, 360.0) * 0.22
            }
        }
        lastGameYaw = yaw
        lastGameTimestampNs = timestampNs

        absoluteOffset?.let { offset ->
            val target = BearingMath.normalizeDegrees(yaw + offset)
            val previous = fusedHeading
            fusedHeading = if (previous == null) target else {
                val error = BearingMath.angleDifference(target, previous)
                val alpha = when {
                    turnSpeedDegPerSec < 2.0 -> 0.08
                    turnSpeedDegPerSec < 12.0 -> 0.20
                    turnSpeedDegPerSec < 45.0 -> 0.44
                    else -> 0.76
                }
                if (turnSpeedDegPerSec < 2.0 && abs(error) < 0.40) previous
                else BearingMath.normalizeDegrees(previous + error * alpha)
            }
        }
    }

    private fun onAbsoluteYaw(magneticYaw: Double) {
        magneticHeading = magneticYaw
        val loc = navigationLocation ?: filteredLocation ?: rawLocation
        val declination = loc?.let {
            GeomagneticField(
                it.latitude.toFloat(),
                it.longitude.toFloat(),
                if (it.hasAltitude()) it.altitude.toFloat() else 0f,
                System.currentTimeMillis()
            ).declination.toDouble()
        } ?: 0.0
        val absoluteTrue = BearingMath.normalizeDegrees(magneticYaw + declination)
        trueHeading = absoluteTrue

        val gy = gameYaw
        if (gy != null) {
            val measuredOffset = BearingMath.angleDifference(absoluteTrue, gy)
            absoluteOffset = absoluteOffset?.let { prev ->
                BearingMath.normalizeSignedDegrees(prev + BearingMath.angleDifference(measuredOffset, prev) * 0.018)
            } ?: measuredOffset
            if (fusedHeading == null) fusedHeading = absoluteTrue
        } else {
            fusedHeading = absoluteTrue
        }
    }

    private fun acceptLocation(location: Location) {
        rawLocation = location
        gpsAccuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
        locationQuality = when {
            !location.hasAccuracy() -> "POOR"
            location.accuracy <= 4f -> "EXCELLENT"
            location.accuracy <= 8f -> "GOOD"
            location.accuracy <= 14f -> "FAIR"
            else -> "POOR"
        }

        if (location.accuracy > 35f && filteredLocation != null) {
            renderState()
            return
        }

        updateFilteredLocation(location)
        updateNavigationLocation(location)
        maybeCreateTestTree()
        recomputeGeometry()
        renderState()
    }

    private fun updateFilteredLocation(location: Location) {
        val previous = filteredLocation
        filteredLocation = if (previous == null) {
            Location(location)
        } else {
            val displacement = previous.distanceTo(location).toDouble()
            val alpha = when {
                location.accuracy <= 4f -> 0.42
                location.accuracy <= 8f -> 0.28
                location.accuracy <= 14f -> 0.16
                else -> 0.08
            } + when {
                location.hasSpeed() && location.speed > 1.6f -> 0.18
                location.hasSpeed() && location.speed > 0.7f -> 0.10
                displacement > 5.0 -> 0.12
                else -> 0.0
            }
            val boundedAlpha = min(0.65, alpha)
            Location(location).apply {
                latitude = previous.latitude + (location.latitude - previous.latitude) * boundedAlpha
                longitude = previous.longitude + (location.longitude - previous.longitude) * boundedAlpha
                accuracy = max(1f, min(previous.accuracy, location.accuracy))
            }
        }
    }

    private fun updateNavigationLocation(location: Location) {
        val candidate = filteredLocation ?: Location(location)
        val previous = navigationLocation
        if (previous == null) {
            navigationLocation = Location(candidate)
            return
        }

        val displacement = previous.distanceTo(candidate).toDouble()
        val uncertaintyRadius = max(1.6, min(7.0, location.accuracy * 0.38))
        val speed = if (location.hasSpeed()) location.speed.toDouble() else 0.0
        val moving = speed > 0.45 || displacement > uncertaintyRadius + 1.5

        if (!moving || displacement <= uncertaintyRadius) {
            navigationHoldMeters = displacement
            return
        }

        val trustedMovement = displacement - uncertaintyRadius
        val movementRatio = (trustedMovement / displacement).coerceIn(0.0, 1.0)
        val speedWeight = when {
            speed > 1.8 -> 0.72
            speed > 1.0 -> 0.58
            speed > 0.45 -> 0.42
            else -> 0.30
        }
        val accuracyWeight = when {
            location.accuracy <= 4f -> 1.0
            location.accuracy <= 8f -> 0.78
            location.accuracy <= 14f -> 0.52
            else -> 0.28
        }
        val alpha = (movementRatio * speedWeight * accuracyWeight).coerceIn(0.10, 0.65)

        navigationLocation = Location(candidate).apply {
            latitude = previous.latitude + (candidate.latitude - previous.latitude) * alpha
            longitude = previous.longitude + (candidate.longitude - previous.longitude) * alpha
            accuracy = location.accuracy
        }
        navigationHoldMeters = 0.0
    }

    private fun maybeCreateTestTree(force: Boolean = false) {
        if (autoTreeCreated && !force) return
        val start = navigationLocation ?: filteredLocation ?: rawLocation ?: return
        val heading = fusedHeading ?: trueHeading ?: return
        if (!force && gpsAccuracy > 16f) return

        val dest = destinationPoint(start.latitude, start.longitude, heading, testTreeDistanceMeters)
        treeLat = dest.first
        treeLng = dest.second
        autoTreeCreated = true
        filteredBearing = heading
        displayDelta = 0.0
        recomputeGeometry()
        showToast("Target tree placed 35 m ahead")
    }

    private fun recomputeGeometry() {
        val loc = navigationLocation ?: return
        val lat = treeLat ?: return
        val lng = treeLng ?: return

        val result = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, lat, lng, result)
        distanceToTreeMeters = result[0]
        val distance = max(0.5, result[0].toDouble())

        val bearing = BearingMath.bearingDegrees(loc.latitude, loc.longitude, lat, lng)
        rawBearing = bearing

        bearingUncertaintyDeg = Math.toDegrees(atan2(max(1.0, gpsAccuracy * 0.45).toDouble(), distance))
            .coerceIn(0.5, 75.0)
        targetAreaMode = distance <= max(6.0, gpsAccuracy * 1.35)

        filteredBearing = if (targetAreaMode) {
            filteredBearing ?: bearing
        } else {
            filteredBearing?.let { prev ->
                val error = BearingMath.angleDifference(bearing, prev)
                val deadband = max(0.8, bearingUncertaintyDeg * 0.42)
                if (abs(error) <= deadband) {
                    prev
                } else {
                    val alpha = when {
                        gpsAccuracy <= 4f -> 0.26
                        gpsAccuracy <= 8f -> 0.18
                        gpsAccuracy <= 14f -> 0.11
                        else -> 0.06
                    }
                    BearingMath.normalizeDegrees(prev + error * alpha)
                }
            } ?: bearing
        }
    }

    private fun renderState() {
        val heading = fusedHeading ?: trueHeading
        val bearing = filteredBearing ?: rawBearing
        val rawDelta = if (heading != null && bearing != null) BearingMath.angleDifference(bearing, heading) else null

        if (rawDelta != null && !targetAreaMode) {
            displayDelta = displayDelta?.let { prev ->
                val error = BearingMath.angleDifference(rawDelta, prev)
                val deadband = when {
                    turnSpeedDegPerSec < 2.0 -> max(0.45, bearingUncertaintyDeg * 0.20)
                    else -> 0.25
                }
                if (abs(error) <= deadband) {
                    prev
                } else {
                    val alpha = when {
                        turnSpeedDegPerSec < 2.0 -> 0.06
                        turnSpeedDegPerSec < 12.0 -> 0.18
                        turnSpeedDegPerSec < 45.0 -> 0.42
                        else -> 0.74
                    }
                    BearingMath.normalizeSignedDegrees(prev + error * alpha)
                }
            } ?: rawDelta
        }

        val delta = displayDelta
        val distance = distanceToTreeMeters
        overlay.updateTarget(
            deltaDegrees = delta,
            distanceMeters = distance?.toDouble(),
            gpsQuality = locationQuality,
            ready = treeLat != null && heading != null,
            targetArea = targetAreaMode,
            uncertaintyDegrees = bearingUncertaintyDeg
        )

        tvDistance.text = distance?.let { if (it < 10f) String.format("%.1f m", it) else String.format("%.0f m", it) } ?: "-- m"
        tvDirection.text = when {
            treeLat == null || heading == null -> "Acquiring location & heading…"
            targetAreaMode -> "Target area reached"
            delta == null -> "Acquiring direction…"
            abs(delta) <= 3.0 -> "Tree is ahead"
            abs(delta) >= 150.0 -> "Tree is behind you"
            delta < 0 -> "Turn left ${abs(delta).toInt()}°"
            else -> "Turn right ${abs(delta).toInt()}°"
        }
        tvGpsStatus.text = "GPS  $locationQuality  ${if (gpsAccuracy < Float.MAX_VALUE) String.format("±%.0f m", gpsAccuracy) else ""}"
        tvCompassStatus.text = "HEADING  ${headingQuality()}"
        tvCoordinates.text = if (treeLat != null && treeLng != null) {
            String.format("Target  %.6f, %.6f", treeLat, treeLng)
        } else "Waiting for a reliable fix to create target…"

        tvDebug.text = buildString {
            appendLine("TRACKING ENGINE")
            appendLine("Game yaw         : ${fmt(gameYaw)}°")
            appendLine("Magnetic         : ${fmt(magneticHeading)}°")
            appendLine("True heading     : ${fmt(trueHeading)}°")
            appendLine("Fused heading    : ${fmt(fusedHeading)}°")
            appendLine("Turn speed       : ${String.format("%.1f", turnSpeedDegPerSec)}°/s")
            appendLine("Raw bearing      : ${fmt(rawBearing)}°")
            appendLine("Filtered bearing : ${fmt(filteredBearing)}°")
            appendLine("Display delta    : ${fmt(displayDelta)}°")
            appendLine("Bearing uncertainty: ±${String.format("%.1f", bearingUncertaintyDeg)}°")
            appendLine("Target area mode : $targetAreaMode")
            appendLine("GPS quality      : $locationQuality / ${if (gpsAccuracy < Float.MAX_VALUE) String.format("±%.1f m", gpsAccuracy) else "--"}")
            appendLine("Navigation hold  : ${String.format("%.1f", navigationHoldMeters)} m")
            appendLine("Raw phone        : ${locText(rawLocation)}")
            appendLine("Filtered phone   : ${locText(filteredLocation)}")
            appendLine("Navigation phone : ${locText(navigationLocation)}")
            append("Tree fixed       : ${treeLat?.let { String.format("%.7f", it) } ?: "--"}, ${treeLng?.let { String.format("%.7f", it) } ?: "--"}")
        }
    }

    private fun headingQuality(): String = when {
        headingAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE -> "CALIBRATE"
        absoluteOffset == null -> "ACQUIRING"
        else -> "GOOD"
    }

    private fun destinationPoint(lat: Double, lng: Double, bearingDeg: Double, distanceMeters: Double): Pair<Double, Double> {
        val r = 6_371_000.0
        val d = distanceMeters / r
        val brng = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lng)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(brng))
        val lon2 = lon1 + atan2(sin(brng) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return Math.toDegrees(lat2) to Math.toDegrees(lon2)
    }

    private fun fmt(value: Double?) = value?.let { String.format("%.1f", it) } ?: "--"
    private fun locText(location: Location?) = location?.let { String.format("%.7f, %.7f", it.latitude, it.longitude) } ?: "--"
    private fun showToast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
