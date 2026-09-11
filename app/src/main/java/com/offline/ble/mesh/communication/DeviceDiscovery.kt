package com.offline.ble.mesh.communication

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class DiscoveredPeer(
    val device: BluetoothDevice,
    val deviceId: String,
    val rssi: Int,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
) {
    val address: String get() = device.address
    val displayName: String get() = if (deviceId.isNotBlank()) deviceId else device.address
}

class DeviceDiscovery(
    private val bluetoothAdapter: BluetoothAdapter?,
    private val coroutineScope: CoroutineScope
) {
    private val tag = "DeviceDiscovery"
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    private val isAdvertising = AtomicBoolean(false)
    private val isScanning = AtomicBoolean(false)

    private val peersMap = ConcurrentHashMap<String, DiscoveredPeer>()
    private var cleanupJob: Job? = null

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            isAdvertising.set(true)
            Log.i(tag, "BLE Advertising started successfully for service ${CommunicationConstants.SERVICE_UUID}")
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            isAdvertising.set(false)
            Log.e(tag, "BLE Advertising start failure: $errorCode")
        }
    }

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
            isScanning.set(false)
            Log.e(tag, "BLE Scan failed: $errorCode")
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val scanRecord = result.scanRecord
        val serviceUuid = ParcelUuid(CommunicationConstants.SERVICE_UUID)

        var remoteId = ""
        val serviceData = scanRecord?.getServiceData(serviceUuid)
        if (serviceData != null && serviceData.isNotEmpty()) {
            remoteId = String(serviceData, Charsets.UTF_8).trim()
        }

        val peer = DiscoveredPeer(
            device = device,
            deviceId = remoteId,
            rssi = result.rssi,
            lastSeenTimestamp = System.currentTimeMillis()
        )
        peersMap[device.address] = peer
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising(localDeviceId: String) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return
        advertiser = bluetoothAdapter.bluetoothLeAdvertiser ?: return
        if (isAdvertising.get()) return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val serviceUuid = ParcelUuid(CommunicationConstants.SERVICE_UUID)
        val shortId = if (localDeviceId.length >= 8) localDeviceId.substring(0, 8) else localDeviceId
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(serviceUuid)
            .addServiceData(serviceUuid, shortId.toByteArray(Charsets.UTF_8))
            .build()

        try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (e: Exception) {
            Log.e(tag, "Error starting advertising: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        if (!isAdvertising.get()) return
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            isAdvertising.set(false)
            Log.i(tag, "BLE Advertising stopped")
        } catch (e: Exception) {
            Log.e(tag, "Error stopping advertising: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return
        scanner = bluetoothAdapter.bluetoothLeScanner ?: return
        if (isScanning.get()) return

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(CommunicationConstants.SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setReportDelay(0)
            .build()

        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
            isScanning.set(true)
            Log.i(tag, "BLE Scan initiated")
            startStalePeerEviction()
        } catch (e: Exception) {
            Log.e(tag, "Error starting scan: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (!isScanning.get()) return
        try {
            scanner?.stopScan(scanCallback)
            isScanning.set(false)
            Log.i(tag, "BLE Scan stopped")
        } catch (e: Exception) {
            Log.e(tag, "Error stopping scan: ${e.message}")
        } finally {
            cleanupJob?.cancel()
            cleanupJob = null
        }
    }

    private fun startStalePeerEviction() {
        cleanupJob?.cancel()
        cleanupJob = coroutineScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(15000L)
                val cutoff = System.currentTimeMillis() - 35000L
                val it = peersMap.entries.iterator()
                while (it.hasNext()) {
                    val entry = it.next()
                    if (entry.value.lastSeenTimestamp < cutoff) {
                        it.remove()
                    }
                }
            }
        }
    }

    fun getDiscoveredPeers(): List<DiscoveredPeer> {
        return peersMap.values.sortedByDescending { it.lastSeenTimestamp }
    }

    fun findPeer(targetIdentifier: String): DiscoveredPeer? {
        if (targetIdentifier.isBlank()) return null
        return peersMap.values.firstOrNull {
            it.device.address.equals(targetIdentifier, ignoreCase = true) ||
                    (it.deviceId.isNotBlank() && targetIdentifier.startsWith(it.deviceId, ignoreCase = true)) ||
                    (it.deviceId.isNotBlank() && it.deviceId.startsWith(targetIdentifier, ignoreCase = true))
        }
    }

    fun stop() {
        stopAdvertising()
        stopScanning()
        peersMap.clear()
    }
}