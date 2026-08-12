package com.example.treedirectiondemo

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.widget.Button
import android.widget.EditText
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
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: TreeOverlayView
    private lateinit var tvDebug: TextView
    private lateinit var etTreeLat: EditText
    private lateinit var etTreeLng: EditText
    private lateinit var etSetupBearing: EditText
    private lateinit var btnResetTree: Button
    private lateinit var btnJitter: Button

    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val fusedLocation by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val handler = Handler(Looper.getMainLooper())

    private var treeLat: Double? = null
    private var treeLng: Double? = null
    private var setupBearing: Double? = null
    private val testTreeDistanceMeters = 35.0

    private var headingDegrees: Double? = null
    private var rawHeadingDegrees: Double? = null
    private var rawLocation: Location? = null
    private var effectiveLocation: Location? = null
    private var gpsBearing: Double? = null
    private var smoothedGpsBearing: Double? = null
    private var smoothedScreenDelta: Double? = null
    private var distanceToTreeMeters: Float? = null

    private var simulateGpsJitter = false
    private var jitterMeters = 6.0
    private var autoTreeCreated = false

    // Tuning values for stability vs responsiveness.
    private val headingAlpha = 0.08
    private val headingDeadbandDeg = 1.0
    private val gpsBearingAlpha = 0.18
    private val screenDeltaAlpha = 0.14
    private val screenDeltaDeadbandDeg = 0.7
    private val maxScreenStepDeg = 2.5

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val cameraOk = result[Manifest.permission.CAMERA] == true || hasPermission(Manifest.permission.CAMERA)
        val locationOk = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        if (cameraOk) startCamera()
        if (locationOk) startLocationUpdates()
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                rawLocation = it
                maybeCreateTestTree()
                recomputeEffectiveLocation()
            }
        }
    }

    private val jitterTicker = object : Runnable {
        override fun run() {
            if (simulateGpsJitter && rawLocation != null) recomputeEffectiveLocation()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupActions()
        requestRuntimePermissions()
        handler.post(jitterTicker)
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.treeOverlay)
        tvDebug = findViewById(R.id.tvDebug)
        etTreeLat = findViewById(R.id.etTreeLat)
        etTreeLng = findViewById(R.id.etTreeLng)
        etSetupBearing = findViewById(R.id.etSetupBearing)
        btnResetTree = findViewById(R.id.btnLock)
        btnJitter = findViewById(R.id.btnJitter)
    }

    private fun setupActions() {
        findViewById<Button>(R.id.btnApply).setOnClickListener {
            val lat = etTreeLat.text.toString().toDoubleOrNull()
            val lng = etTreeLng.text.toString().toDoubleOrNull()
            if (lat == null || lng == null) {
                Toast.makeText(this, "Invalid tree coordinates", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            treeLat = lat
            treeLng = lng
            setupBearing = etSetupBearing.text.toString().toDoubleOrNull()?.let(BearingMath::normalizeDegrees)
            autoTreeCreated = true
            smoothedGpsBearing = null
            smoothedScreenDelta = null
            recomputeGpsGeometry()
            renderState()
        }

        btnResetTree.setOnClickListener {
            autoTreeCreated = false
            treeLat = null
            treeLng = null
            gpsBearing = null
            smoothedGpsBearing = null
            smoothedScreenDelta = null
            distanceToTreeMeters = null
            maybeCreateTestTree(force = true)
            recomputeEffectiveLocation()
        }

        btnJitter.setOnClickListener {
            simulateGpsJitter = !simulateGpsJitter
            btnJitter.text = if (simulateGpsJitter) "GPS JITTER: ON" else "GPS JITTER: OFF"
            recomputeEffectiveLocation()
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
            val cameraProvider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setWaitForAccurateLocation(false)
            .build()
        fusedLocation.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        fusedLocation.removeLocationUpdates(locationCallback)
    }

    override fun onDestroy() {
        handler.removeCallbacks(jitterTicker)
        super.onDestroy()
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

        val rawHeading = BearingMath.normalizeDegrees(Math.toDegrees(orientation[0].toDouble()))
        rawHeadingDegrees = rawHeading

        headingDegrees = headingDegrees?.let { prev ->
            val error = BearingMath.angleDifference(rawHeading, prev)
            if (abs(error) < headingDeadbandDeg) {
                prev
            } else {
                BearingMath.normalizeDegrees(prev + error * headingAlpha)
            }
        } ?: rawHeading

        maybeCreateTestTree()
        renderState()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun maybeCreateTestTree(force: Boolean = false) {
        if (autoTreeCreated && !force) return
        val start = rawLocation ?: return
        val bearing = headingDegrees ?: return
        val dest = destinationPoint(start.latitude, start.longitude, bearing, testTreeDistanceMeters)
        treeLat = dest.first
        treeLng = dest.second
        setupBearing = bearing
        autoTreeCreated = true
        smoothedGpsBearing = bearing
        smoothedScreenDelta = 0.0
        etTreeLat.setText(String.format("%.7f", treeLat))
        etTreeLng.setText(String.format("%.7f", treeLng))
        etSetupBearing.setText(String.format("%.1f", setupBearing))
        recomputeGpsGeometry()
        Toast.makeText(this, "Test tree fixed ${testTreeDistanceMeters.toInt()}m ahead", Toast.LENGTH_SHORT).show()
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

    private fun recomputeEffectiveLocation() {
        val source = rawLocation ?: return
        effectiveLocation = if (simulateGpsJitter) addRandomOffset(source, jitterMeters) else Location(source)
        recomputeGpsGeometry()
        renderState()
    }

    private fun recomputeGpsGeometry() {
        val loc = effectiveLocation ?: rawLocation ?: return
        val lat = treeLat ?: return
        val lng = treeLng ?: return

        val newBearing = BearingMath.bearingDegrees(loc.latitude, loc.longitude, lat, lng)
        gpsBearing = newBearing
        smoothedGpsBearing = smoothedGpsBearing?.let { prev ->
            BearingMath.normalizeDegrees(prev + BearingMath.angleDifference(newBearing, prev) * gpsBearingAlpha)
        } ?: newBearing

        val result = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, lat, lng, result)
        distanceToTreeMeters = result[0]
    }

    private fun addRandomOffset(source: Location, radiusMeters: Double): Location {
        val angle = Random.nextDouble(0.0, Math.PI * 2.0)
        val radius = Random.nextDouble(0.0, radiusMeters)
        val north = cos(angle) * radius
        val east = sin(angle) * radius
        val latScale = 111_320.0
        val lngScale = max(1.0, 111_320.0 * cos(Math.toRadians(source.latitude)))
        return Location(source).apply {
            latitude = source.latitude + north / latScale
            longitude = source.longitude + east / lngScale
            accuracy = max(source.accuracy, radiusMeters.toFloat())
        }
    }

    private fun renderState() {
        val heading = headingDegrees
        val target = smoothedGpsBearing ?: gpsBearing
        val rawDelta = if (heading != null && target != null) BearingMath.angleDifference(target, heading) else 0.0

        val stableDelta = smoothedScreenDelta?.let { prev ->
            val error = BearingMath.angleDifference(rawDelta, prev)
            if (abs(error) < screenDeltaDeadbandDeg) {
                prev
            } else {
                val desiredStep = error * screenDeltaAlpha
                val limitedStep = desiredStep.coerceIn(-maxScreenStepDeg, maxScreenStepDeg)
                BearingMath.normalizeSignedDegrees(prev + limitedStep)
            }
        } ?: rawDelta

        smoothedScreenDelta = stableDelta
        overlay.updateDirection(stableDelta, true, "FIXED TREE")

        tvDebug.text = buildString {
            appendLine("MODE: FIXED TREE / NOISE FILTERED")
            appendLine("Raw heading    : ${fmt(rawHeadingDegrees)}°")
            appendLine("Camera heading : ${fmt(heading)}°")
            appendLine("Initial bearing: ${fmt(setupBearing)}°")
            appendLine("Raw bearing    : ${fmt(gpsBearing)}°")
            appendLine("Current bearing: ${fmt(target)}°")
            appendLine("Raw delta      : ${fmt(rawDelta)}°")
            appendLine("Screen delta   : ${fmt(stableDelta)}°")
            appendLine("Distance       : ${distanceToTreeMeters?.let { String.format("%.1f", it) } ?: "--"} m")
            appendLine("GPS accuracy   : ${rawLocation?.accuracy?.let { String.format("±%.1f", it) } ?: "--"} m")
            appendLine("Phone GPS      : ${locText(effectiveLocation)}")
            appendLine("FIXED Tree GPS : ${treeLat?.let { String.format("%.7f", it) } ?: "--"}, ${treeLng?.let { String.format("%.7f", it) } ?: "--"}")
            append("Jitter demo    : ${if (simulateGpsJitter) "ON (±${jitterMeters.toInt()}m)" else "OFF"}")
        }
    }

    private fun fmt(value: Double?) = value?.let { String.format("%.1f", it) } ?: "--"
    private fun locText(location: Location?) = location?.let { String.format("%.7f, %.7f", it.latitude, it.longitude) } ?: "--"
}
