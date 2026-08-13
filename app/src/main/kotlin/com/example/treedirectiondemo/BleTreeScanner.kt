package com.example.treedirectiondemo

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlin.math.exp
import kotlin.math.ln

/**
 * BLE proximity layer for real tree devices.
 *
 * Matching strategy is intentionally strict:
 * 1) exact configured BLE address when available;
 * 2) configured advertised identifier in device name/service/manufacturer payload;
 * 3) no automatic target claim. Nearest non-target devices remain diagnostic only.
 *
 * RSSI is used only as proximity evidence. It must not be interpreted as direction.
 */
class BleTreeScanner(
    context: Context,
    private val onState: (State) -> Unit
) {
    data class TargetIdentity(
        val treeId: String,
        val bleAddress: String? = null,
        val advertisedId: String? = null
    )

    data class State(
        val scanning: Boolean = false,
        val bluetoothAvailable: Boolean = false,
        val bluetoothEnabled: Boolean = false,
        val permissionGranted: Boolean = false,
        val targetMatched: Boolean = false,
        val targetAddress: String? = null,
        val targetName: String? = null,
        val rawRssi: Int? = null,
        val filteredRssi: Double? = null,
        val proximity: Proximity = Proximity.UNKNOWN,
        val estimatedDistanceM: Double? = null,
        val seenCount: Int = 0,
        val lastSeenElapsedMs: Long? = null,
        val error: String? = null
    )

    enum class Proximity { UNKNOWN, FAR, NEAR, VERY_NEAR }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private var target: TargetIdentity? = null
    private var state = State()
    private var filteredRssi: Double? = null
    private var seenCount = 0

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = accept(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::accept)
        override fun onScanFailed(errorCode: Int) {
            update(state.copy(scanning = false, error = "BLE scan failed ($errorCode)"))
        }
    }

    fun setTarget(identity: TargetIdentity?) {
        target = identity
        filteredRssi = null
        seenCount = 0
        update(baseState())
    }

    fun currentState(): State = state

    @SuppressLint("MissingPermission")
    fun start() {
        val a = adapter
        if (a == null) {
            update(baseState().copy(error = "Bluetooth is not available"))
            return
        }
        if (!hasScanPermission()) {
            update(baseState().copy(error = "Nearby devices permission is required"))
            return
        }
        if (!a.isEnabled) {
            update(baseState().copy(error = "Bluetooth is turned off"))
            return
        }
        if (state.scanning) return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()
        try {
            a.bluetoothLeScanner?.startScan(null, settings, callback)
            update(baseState().copy(scanning = true))
        } catch (t: Throwable) {
            update(baseState().copy(error = "Unable to start BLE scan: ${t.javaClass.simpleName}"))
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!hasScanPermission()) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        update(state.copy(scanning = false))
    }

    @SuppressLint("MissingPermission")
    private fun accept(result: ScanResult) {
        val identity = target ?: return
        val device = result.device
        val address = device.address
        val name = result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull()
        val payloadText = buildPayloadText(result)

        val addressMatch = identity.bleAddress?.equals(address, ignoreCase = true) == true
        val token = identity.advertisedId?.ifBlank { null } ?: identity.treeId.ifBlank { null }
        val tokenMatch = token != null && (
            name?.contains(token, ignoreCase = true) == true ||
                payloadText.contains(token, ignoreCase = true)
            )
        if (!addressMatch && !tokenMatch) return

        seenCount += 1
        val alpha = if (filteredRssi == null) 1.0 else 0.22
        filteredRssi = filteredRssi?.let { it + (result.rssi - it) * alpha } ?: result.rssi.toDouble()
        val rssi = filteredRssi!!
        val proximity = when {
            rssi >= -58 -> Proximity.VERY_NEAR
            rssi >= -70 -> Proximity.NEAR
            else -> Proximity.FAR
        }
        val distance = estimateDistanceFromRssi(rssi)
        update(
            baseState().copy(
                scanning = true,
                targetMatched = true,
                targetAddress = address,
                targetName = name,
                rawRssi = result.rssi,
                filteredRssi = rssi,
                proximity = proximity,
                estimatedDistanceM = distance,
                seenCount = seenCount,
                lastSeenElapsedMs = android.os.SystemClock.elapsedRealtime(),
                error = null
            )
        )
    }

    private fun buildPayloadText(result: ScanResult): String {
        val record = result.scanRecord ?: return ""
        val parts = mutableListOf<String>()
        record.serviceUuids?.forEach { parts += it.uuid.toString() }
        for (i in 0..65535) {
            val bytes = record.getManufacturerSpecificData(i) ?: continue
            parts += bytes.toString(Charsets.UTF_8)
            if (parts.size > 8) break
        }
        return parts.joinToString("|")
    }

    /**
     * Coarse BLE RSSI estimate only. Tx power/environment are unknown, so this is UI guidance,
     * never the authoritative navigation distance.
     */
    private fun estimateDistanceFromRssi(rssi: Double): Double {
        val calibratedTxPowerAt1m = -59.0
        val pathLossExponent = 2.2
        return exp(ln(10.0) * ((calibratedTxPowerAt1m - rssi) / (10.0 * pathLossExponent)))
            .coerceIn(0.2, 100.0)
    }

    private fun baseState() = State(
        scanning = state.scanning,
        bluetoothAvailable = adapter != null,
        bluetoothEnabled = adapter?.isEnabled == true,
        permissionGranted = hasScanPermission(),
        targetMatched = state.targetMatched,
        targetAddress = state.targetAddress,
        targetName = state.targetName,
        rawRssi = state.rawRssi,
        filteredRssi = state.filteredRssi,
        proximity = state.proximity,
        estimatedDistanceM = state.estimatedDistanceM,
        seenCount = state.seenCount,
        lastSeenElapsedMs = state.lastSeenElapsedMs,
        error = state.error
    )

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun update(newState: State) {
        state = newState
        onState(newState)
    }
}
