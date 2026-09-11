package com.offline.ble.mesh.ble

import android.bluetooth.BluetoothDevice

data class DiscoveredDevice(
    val device: BluetoothDevice,
    val deviceId: String,
    val rssi: Int,
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    val connectionState: BleConnectionState = BleConnectionState.DISCONNECTED
) {
    val address: String
        get() = device.address

    val displayName: String
        get() = if (deviceId.isNotBlank()) deviceId else device.address
}