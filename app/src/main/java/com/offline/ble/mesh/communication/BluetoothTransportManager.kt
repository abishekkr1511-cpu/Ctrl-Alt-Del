package com.offline.ble.mesh.communication

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class BluetoothTransportManager(
    private val context: Context,
    val localDeviceId: String = UUID.randomUUID().toString()
) : TransportManager {

    private val tag = "BluetoothTransportMgr"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter = bluetoothManager?.adapter

    private val receivedCallbacks = CopyOnWriteArrayList<(MeshPacket) -> Unit>()

    // Duplicate message cache: remembers the last 500 processed messageIds to prevent duplicate delivery
    private val processedMessageIds = Collections.synchronizedSet(LinkedHashSet<String>())

    private val connectionManager = ConnectionManager(
        context = context,
        coroutineScope = scope,
        localDeviceId = localDeviceId,
        onPacketReceivedCallback = { packet ->
            handleIncomingPacket(packet)
        }
    )

    private val deviceDiscovery = DeviceDiscovery(
        bluetoothAdapter = bluetoothAdapter,
        coroutineScope = scope
    )

    override fun startAdvertisingAndDiscovery() {
        Log.i(tag, "Starting offline BLE transport advertising & discovery for local device $localDeviceId")
        connectionManager.openServer()
        deviceDiscovery.startAdvertising(localDeviceId)
        deviceDiscovery.startScanning()
    }

    override fun sendPacket(packet: MeshPacket) {
        val peers = deviceDiscovery.getDiscoveredPeers()
        if (peers.isEmpty()) {
            Log.w(tag, "sendPacket called but no nearby peers discovered yet. Packet ${packet.messageId} queued for discovery")
            return
        }

        // Find best target peer (direct match by address/id or first available peer)
        val targetPeer = deviceDiscovery.findPeer(packet.senderId) ?: peers.firstOrNull()
        if (targetPeer == null) {
            Log.w(tag, "No available target peer found in radio range for packet ${packet.messageId}")
            return
        }

        Log.i(tag, "Transmitting MeshPacket ${packet.messageId} to peer ${targetPeer.address}")
        connectionManager.transmitPacket(targetPeer.device, packet) { success, error ->
            if (success) {
                Log.i(tag, "Successfully transmitted MeshPacket ${packet.messageId} to ${targetPeer.address}")
            } else {
                Log.e(tag, "Failed to transmit MeshPacket ${packet.messageId}: $error")
            }
        }
    }

    override fun onPacketReceived(callback: (MeshPacket) -> Unit) {
        receivedCallbacks.add(callback)
    }

    private fun handleIncomingPacket(packet: MeshPacket) {
        // Duplicate protection check
        synchronized(processedMessageIds) {
            if (processedMessageIds.contains(packet.messageId)) {
                Log.w(tag, "DUPLICATE BLOCKED: MeshPacket ${packet.messageId} already processed. Dropped.")
                return
            }
            processedMessageIds.add(packet.messageId)
            if (processedMessageIds.size > 500) {
                val oldest = processedMessageIds.iterator().next()
                processedMessageIds.remove(oldest)
            }
        }

        Log.i(tag, "Delivering received MeshPacket ${packet.messageId} to application layer: '${packet.textContent}'")
        receivedCallbacks.forEach { callback ->
            try {
                callback(packet)
            } catch (e: Exception) {
                Log.e(tag, "Error in packet received callback: ${e.message}")
            }
        }
    }

    override fun stop() {
        Log.i(tag, "Stopping BluetoothTransportManager")
        deviceDiscovery.stop()
        connectionManager.stop()
        scope.cancel()
    }
}