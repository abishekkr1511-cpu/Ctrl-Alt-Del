package com.offline.ble.mesh.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import com.offline.ble.mesh.ble.advertiser.BleAdvertiserManager
import com.offline.ble.mesh.ble.client.BleGattClientManager
import com.offline.ble.mesh.ble.protocol.AckPayload
import com.offline.ble.mesh.ble.protocol.MessagePayload
import com.offline.ble.mesh.ble.scanner.BleScannerManager
import com.offline.ble.mesh.ble.server.BleGattServerManager
import com.offline.ble.mesh.manager.DebugLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

class BleCommunicationManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val localDeviceId: String,
    private val onMessageReceived: (MessagePayload, BluetoothDevice) -> Unit,
    private val onAckReceived: (AckPayload) -> Unit
) {
    private val tag = "BleCommManager"
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    val scannerManager = BleScannerManager(bluetoothAdapter, coroutineScope)
    val advertiserManager = BleAdvertiserManager(bluetoothAdapter, localDeviceId)
    val serverManager = BleGattServerManager(
        context = context,
        localDeviceId = localDeviceId,
        onMessageReceived = onMessageReceived,
        onAckReceived = onAckReceived
    )
    val clientManager = BleGattClientManager(
        context = context,
        coroutineScope = coroutineScope,
        onAckReceived = onAckReceived
    )

    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = scannerManager.discoveredDevices
    val isScanning: StateFlow<Boolean> = scannerManager.isScanning
    val isAdvertising: StateFlow<Boolean> = advertiserManager.isAdvertising
    val connectionState: StateFlow<BleConnectionState> = clientManager.connectionState
    val connectedDeviceId: StateFlow<String?> = clientManager.connectedDeviceId

    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    val isBleSupported: Boolean
        get() = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE)

    fun start() {
        DebugLogManager.i(tag, "Starting BLE Mesh subsystem for local ID $localDeviceId")
        serverManager.openServer()
        advertiserManager.startAdvertising()
        scannerManager.startScanning()
    }

    fun stop() {
        DebugLogManager.i(tag, "Stopping BLE Mesh subsystem")
        scannerManager.stopScanning()
        advertiserManager.stopAdvertising()
        serverManager.closeServer()
        clientManager.closeGatt()
    }

    fun sendPayload(
        targetIdentifier: String,
        payload: MessagePayload,
        onResult: (Boolean, String?) -> Unit
    ) {
        val target = scannerManager.getDeviceByAddressOrId(targetIdentifier)
        if (target == null) {
            DebugLogManager.w(tag, "Target $targetIdentifier not currently found in discovered peers")
            onResult(false, "Target device not currently in range (scanning in progress)")
            return
        }

        clientManager.transmitMessage(target.device, payload, onResult)
    }

    fun sendAck(device: BluetoothDevice, ack: AckPayload) {
        serverManager.sendAck(device, ack)
    }
}