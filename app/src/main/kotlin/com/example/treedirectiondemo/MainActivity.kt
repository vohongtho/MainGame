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
import kotlin.math.cos
import kotlin.math.max
import kotlin.random.Random

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: TreeOverlayView
    private lateinit var tvDebug: TextView
    private lateinit var etTreeLat: EditText
    private lateinit var etTreeLng: EditText
    private lateinit var etSetupBearing: EditText
    private lateinit var btnLock: Button
    private lateinit var btnJitter: Button

    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val fusedLocation by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val handler = Handler(Looper.getMainLooper())

    private var treeLat = 1.300500
    private var treeLng = 103.800600
    private var setupBearing = 72.0

    private var headingDegrees: Double? = null
    private var rawLocation: Location? = null
    private var effectiveLocation: Location? = null
    private var gpsBearing: Double? = null
    private var distanceToTreeMeters: Float? = null

    private var locked = false
    private var lockedBearing = 72.0
    private var simulateGpsJitter = false
    private var jitterMeters = 6.0

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
                recomputeEffectiveLocation()
            }
        }
    }

    private val jitterTicker = object : Runnable {
        override fun run() {
            if (simulateGpsJitter && rawLocation != null) {
                recomputeEffectiveLocation()
            }
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
        btnLock = findViewById(R.id.btnLock)
        btnJitter = findViewById(R.id.btnJitter)
    }

    private fun setupActions() {
        findViewById<Button>(R.id.btnApply).setOnClickListener {
            val lat = etTreeLat.text.toString().toDoubleOrNull()
            val lng = etTreeLng.text.toString().toDoubleOrNull()
            val bearing = etSetupBearing.text.toString().toDoubleOrNull()

            if (lat == null || lng == null || bearing == null) {
                Toast.makeText(this, "Invalid tree setup values", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            treeLat = lat
            treeLng = lng
            setupBearing = BearingMath.normalizeDegrees(bearing)
            if (!locked) recomputeGpsGeometry()
            renderState()
        }

        btnLock.setOnClickListener {
            locked = !locked
            if (locked) {
                lockedBearing = setupBearing
            }
            btnLock.text = if (locked) "UNLOCK" else "LOCK SETUP BEARING"
            renderState()
        }

        btnJitter.setOnClickListener {
            simulateGpsJitter = !simulateGpsJitter
            btnJitter.text = if (simulateGpsJitter) "GPS JITTER: ON" else "GPS JITTER: OFF"
            recomputeEffectiveLocation()
        }
    }

    private fun requestRuntimePermissions() {
        val required = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        permissionLauncher.launch(required)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        if (!hasPermission(Manifest.permission.CAMERA)) return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
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
        val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationVector != null) {
            sensorManager.registerListener(this, rotationVector, SensorManager.SENSOR_DELAY_GAME)
        } else {
            Toast.makeText(this, "Rotation vector sensor unavailable", Toast.LENGTH_LONG).show()
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

        val displayRotation = display?.rotation ?: Surface.ROTATION_0
        when (displayRotation) {
            Surface.ROTATION_0 -> SensorManager.remapCoordinateSystem(
                rotation, SensorManager.AXIS_X, SensorManager.AXIS_Y, remapped
            )
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                rotation, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped
            )
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                rotation, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remapped
            )
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                rotation, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped
            )
        }

        SensorManager.getOrientation(remapped, orientation)
        val rawHeading = BearingMath.normalizeDegrees(Math.toDegrees(orientation[0].toDouble()))
        headingDegrees = headingDegrees?.let { previous ->
            BearingMath.normalizeDegrees(previous + BearingMath.angleDifference(rawHeading, previous) * 0.18)
        } ?: rawHeading

        renderState()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun recomputeEffectiveLocation() {
        val source = rawLocation ?: return
        effectiveLocation = if (simulateGpsJitter) addRandomOffset(source, jitterMeters) else Location(source)
        recomputeGpsGeometry()
        renderState()
    }

    private fun recomputeGpsGeometry() {
        val loc = effectiveLocation ?: return
        gpsBearing = BearingMath.bearingDegrees(loc.latitude, loc.longitude, treeLat, treeLng)
        val result = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, treeLat, treeLng, result)
        distanceToTreeMeters = result[0]
    }

    private fun addRandomOffset(source: Location, radiusMeters: Double): Location {
        val angle = Random.nextDouble(0.0, Math.PI * 2.0)
        val radius = Random.nextDouble(0.0, radiusMeters)
        val north = kotlin.math.cos(angle) * radius
        val east = kotlin.math.sin(angle) * radius

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
        val target = if (locked) lockedBearing else (gpsBearing ?: setupBearing)
        val delta = if (heading != null) BearingMath.angleDifference(target, heading) else 0.0
        overlay.updateDirection(delta, locked, "TREE")

        val raw = rawLocation
        val effective = effectiveLocation
        val mode = if (locked) "LOCKED / GPS ignored for marker" else "LIVE GPS BEARING"

        tvDebug.text = buildString {
            appendLine("MODE: $mode")
            appendLine("Camera heading : ${fmt(heading)}°")
            appendLine("Setup bearing  : ${fmt(setupBearing)}°")
            appendLine("GPS bearing    : ${fmt(gpsBearing)}°")
            appendLine("Target bearing : ${fmt(target)}°")
            appendLine("Screen delta   : ${fmt(delta)}°")
            appendLine("Distance       : ${distanceToTreeMeters?.let { String.format("%.1f", it) } ?: "--"} m")
            appendLine("GPS accuracy   : ${raw?.accuracy?.let { String.format("±%.1f", it) } ?: "--"} m")
            appendLine("Raw GPS        : ${locText(raw)}")
            appendLine("Used GPS       : ${locText(effective)}")
            append("Jitter demo    : ${if (simulateGpsJitter) "ON (±${jitterMeters.toInt()}m)" else "OFF"}")
        }
    }

    private fun fmt(value: Double?): String = value?.let { String.format("%.1f", it) } ?: "--"

    private fun locText(location: Location?): String = location?.let {
        String.format("%.6f, %.6f", it.latitude, it.longitude)
    } ?: "--"
}
