package com.offline.ble.mesh.ble.scanner

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import com.offline.ble.mesh.ble.BleConstants
import com.offline.ble.mesh.ble.DiscoveredDevice
import com.offline.ble.mesh.manager.DebugLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class BleScannerManager(
    private val bluetoothAdapter: BluetoothAdapter?,
    private val coroutineScope: CoroutineScope
) {
    private val tag = "BleScanner"
    private var scanner: BluetoothLeScanner? = null

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val deviceMap = ConcurrentHashMap<String, DiscoveredDevice>()
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private var cleanupJob: Job? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result ?: return
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            results?.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            _isScanning.value = false
            val errorMsg = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                else -> "Unknown error ($errorCode)"
            }
            DebugLogManager.e(tag, "BLE Scan failed: $errorMsg")
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val scanRecord = result.scanRecord
        val serviceUuid = ParcelUuid(BleConstants.SERVICE_UUID)

        // Extract device identity from service data
        var deviceId = ""
        val serviceData = scanRecord?.getServiceData(serviceUuid)
        if (serviceData != null && serviceData.isNotEmpty()) {
            deviceId = String(serviceData, Charsets.UTF_8).trim()
        }

        val discovered = DiscoveredDevice(
            device = device,
            deviceId = deviceId,
            rssi = result.rssi,
            lastSeenTimestamp = System.currentTimeMillis()
        )

        val isNew = !deviceMap.containsKey(device.address)
        deviceMap[device.address] = discovered
        updateDiscoveredList()

        if (isNew) {
            DebugLogManager.i(tag, "Discovered compatible peer: ${device.address} (ID: ${discovered.displayName}, RSSI: ${result.rssi} dBm)")
        }
    }

    private fun updateDiscoveredList() {
        _discoveredDevices.value = deviceMap.values.sortedByDescending { it.lastSeenTimestamp }
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            DebugLogManager.w(tag, "Cannot start scan: Bluetooth is disabled")
            return
        }

        scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            DebugLogManager.w(tag, "BluetoothLeScanner is unavailable")
            return
        }

        if (_isScanning.value) {
            DebugLogManager.d(tag, "Scan is already active")
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setReportDelay(0)
            .build()

        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
            _isScanning.value = true
            DebugLogManager.i(tag, "Started BLE scan for compatible mesh devices")
            startStaleDeviceCleanup()
        } catch (e: SecurityException) {
            DebugLogManager.e(tag, "Permission missing for BLE scan", e)
        } catch (e: Exception) {
            DebugLogManager.e(tag, "Exception during scan start", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (!_isScanning.value && scanner == null) return
        try {
            scanner?.stopScan(scanCallback)
            _isScanning.value = false
            DebugLogManager.i(tag, "Stopped BLE scan")
        } catch (e: SecurityException) {
            DebugLogManager.e(tag, "Permission missing for stopping scan", e)
        } catch (e: Exception) {
            DebugLogManager.e(tag, "Exception stopping scan", e)
        } finally {
            cleanupJob?.cancel()
            cleanupJob = null
        }
    }

    private fun startStaleDeviceCleanup() {
        cleanupJob?.cancel()
        cleanupJob = coroutineScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(15000L)
                val cutoff = System.currentTimeMillis() - 35000L
                var removed = false
                val iterator = deviceMap.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.value.lastSeenTimestamp < cutoff) {
                        iterator.remove()
                        removed = true
                    }
                }
                if (removed) {
                    updateDiscoveredList()
                }
            }
        }
    }

    fun getDeviceByAddressOrId(target: String): DiscoveredDevice? {
        return deviceMap.values.firstOrNull {
            it.device.address.equals(target, ignoreCase = true) ||
                    (it.deviceId.isNotBlank() && target.startsWith(it.deviceId, ignoreCase = true)) ||
                    (it.deviceId.isNotBlank() && it.deviceId.startsWith(target, ignoreCase = true))
        }
    }

    fun clear() {
        deviceMap.clear()
        updateDiscoveredList()
    }
}