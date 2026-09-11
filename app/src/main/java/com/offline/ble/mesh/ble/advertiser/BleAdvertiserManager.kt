package com.offline.ble.mesh.ble.advertiser

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid
import com.offline.ble.mesh.ble.BleConstants
import com.offline.ble.mesh.manager.DebugLogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BleAdvertiserManager(
    private val bluetoothAdapter: BluetoothAdapter?,
    private val localDeviceId: String
) {
    private val tag = "BleAdvertiser"
    private var advertiser: BluetoothLeAdvertiser? = null

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            _isAdvertising.value = true
            DebugLogManager.i(tag, "BLE Advertising started successfully (Service UUID: ${BleConstants.SERVICE_UUID})")
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            _isAdvertising.value = false
            val errorMsg = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                else -> "Unknown error ($errorCode)"
            }
            DebugLogManager.e(tag, "BLE Advertising failed: $errorMsg")
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            DebugLogManager.w(tag, "Cannot start advertising: Bluetooth is disabled or unavailable")
            return
        }

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            DebugLogManager.w(tag, "BLE Advertising is not supported on this device")
            return
        }

        if (_isAdvertising.value) {
            DebugLogManager.d(tag, "Advertising is already active")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val serviceUuid = ParcelUuid(BleConstants.SERVICE_UUID)
        val shortId = if (localDeviceId.length >= 8) localDeviceId.substring(0, 8) else localDeviceId
        val serviceData = shortId.toByteArray(Charsets.UTF_8)

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(serviceUuid)
            .addServiceData(serviceUuid, serviceData)
            .build()

        try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
            DebugLogManager.d(tag, "Requested BLE advertising start with short ID: $shortId")
        } catch (e: SecurityException) {
            DebugLogManager.e(tag, "Permission missing for BLE advertising", e)
        } catch (e: Exception) {
            DebugLogManager.e(tag, "Exception starting advertising", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        if (!_isAdvertising.value && advertiser == null) return
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            _isAdvertising.value = false
            DebugLogManager.i(tag, "BLE Advertising stopped")
        } catch (e: SecurityException) {
            DebugLogManager.e(tag, "Permission missing while stopping advertising", e)
        } catch (e: Exception) {
            DebugLogManager.e(tag, "Exception stopping advertising", e)
        }
    }
}