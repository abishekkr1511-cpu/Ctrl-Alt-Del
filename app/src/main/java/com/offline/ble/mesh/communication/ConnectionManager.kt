package com.offline.ble.mesh.communication

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class ConnectionManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val localDeviceId: String,
    private val onPacketReceivedCallback: (MeshPacket) -> Unit
) {
    private val tag = "ConnectionManager"
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private var gattServer: BluetoothGattServer? = null
    private var ackCharacteristic: BluetoothGattCharacteristic? = null
    private val chunkAssembler = PacketSerializer.ChunkAssembler()

    private var activeGattClient: BluetoothGatt? = null
    private val isClientBusy = AtomicBoolean(false)
    private var pendingPacket: MeshPacket? = null
    private var pendingChunks: List<PacketSerializer.RawChunk> = emptyList()
    private var currentChunkIdx: Int = 0
    private var negotiatedMtu: Int = CommunicationConstants.DEFAULT_ATT_MTU

    private var clientTimeoutJob: Job? = null
    private var onSendComplete: ((Boolean, String?) -> Unit)? = null

    // GATT SERVER CALLBACKS (Peripheral Role - Receiving messages)
    private val serverCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            device ?: return
            characteristic ?: return
            if (characteristic.uuid == CommunicationConstants.CHAR_DEVICE_IDENTITY_UUID) {
                val bytes = localDeviceId.toByteArray(Charsets.UTF_8)
                val response = if (offset < bytes.size) bytes.copyOfRange(offset, bytes.size) else ByteArray(0)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            device ?: return
            characteristic ?: return
            val data = value ?: ByteArray(0)

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }

            if (characteristic.uuid == CommunicationConstants.CHAR_MESSAGE_TRANSFER_UUID) {
                val completedPacket = chunkAssembler.processChunk(data)
                if (completedPacket != null) {
                    Log.i(tag, "Reassembled MeshPacket ${completedPacket.messageId} from ${device.address}")
                    sendAck(device, completedPacket.messageId)
                    onPacketReceivedCallback(completedPacket)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
            device ?: return
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    // GATT CLIENT CALLBACKS (Central Role - Sending messages)
    private val clientCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            gatt ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finalizeClientSession(false, "Connection error: $status")
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(tag, "GATT connected to ${gatt.device.address}. Requesting MTU 512")
                gatt.requestMtu(CommunicationConstants.DESIRED_ATT_MTU)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(tag, "GATT disconnected from ${gatt.device.address}")
                if (isClientBusy.get()) {
                    finalizeClientSession(false, "GATT disconnected unexpectedly")
                } else {
                    closeClientGatt()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            gatt ?: return
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else CommunicationConstants.DEFAULT_ATT_MTU
            Log.i(tag, "MTU set to $negotiatedMtu. Discovering services...")
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            gatt ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finalizeClientSession(false, "Service discovery failed")
                return
            }

            val service = gatt.getService(CommunicationConstants.SERVICE_UUID)
            if (service == null) {
                finalizeClientSession(false, "Mesh service not found on peer")
                return
            }

            val ackChar = service.getCharacteristic(CommunicationConstants.CHAR_ACKNOWLEDGEMENT_UUID)
            if (ackChar != null) {
                gatt.setCharacteristicNotification(ackChar, true)
                val cccd = ackChar.getDescriptor(CommunicationConstants.CCCD_UUID)
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(cccd)
                    }
                    return
                }
            }
            startChunkTransmission(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            gatt ?: return
            startChunkTransmission(gatt)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            gatt ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finalizeClientSession(false, "Write failed status $status")
                return
            }

            currentChunkIdx++
            if (currentChunkIdx < pendingChunks.size) {
                sendNextChunk(gatt)
            } else {
                Log.i(tag, "All ${pendingChunks.size} chunks sent. Waiting for ACK...")
                startAckTimeout()
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            handleAckValue(value)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            super.onCharacteristicChanged(gatt, characteristic)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                characteristic?.value?.let { handleAckValue(it) }
            }
        }
    }

    private fun handleAckValue(value: ByteArray) {
        val ackStr = String(value, Charsets.UTF_8)
        val expectedMid = pendingPacket?.messageId
        if (expectedMid != null && ackStr.contains(expectedMid)) {
            clientTimeoutJob?.cancel()
            Log.i(tag, "Verified ACK for MeshPacket $expectedMid")
            finalizeClientSession(true, null)
        }
    }

    @SuppressLint("MissingPermission")
    fun openServer(): Boolean {
        if (gattServer != null) return true
        val bm = bluetoothManager ?: return false

        try {
            val server = bm.openGattServer(context, serverCallback) ?: return false
            gattServer = server

            val service = BluetoothGattService(
                CommunicationConstants.SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            val identityChar = BluetoothGattCharacteristic(
                CommunicationConstants.CHAR_DEVICE_IDENTITY_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            service.addCharacteristic(identityChar)

            val messageChar = BluetoothGattCharacteristic(
                CommunicationConstants.CHAR_MESSAGE_TRANSFER_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            service.addCharacteristic(messageChar)

            val ackChar = BluetoothGattCharacteristic(
                CommunicationConstants.CHAR_ACKNOWLEDGEMENT_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            val cccd = BluetoothGattDescriptor(
                CommunicationConstants.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            ackChar.addDescriptor(cccd)
            service.addCharacteristic(ackChar)
            ackCharacteristic = ackChar

            server.addService(service)
            Log.i(tag, "GATT Server listening on ${CommunicationConstants.SERVICE_UUID}")
            return true
        } catch (e: Exception) {
            Log.e(tag, "Error opening GATT Server: ${e.message}")
            return false
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendAck(device: BluetoothDevice, messageId: String) {
        val server = gattServer ?: return
        val char = ackCharacteristic ?: return
        val ackData = "{\"type\":\"ACK\",\"mid\":\"$messageId\"}".toByteArray(Charsets.UTF_8)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, char, false, ackData)
            } else {
                @Suppress("DEPRECATION")
                char.value = ackData
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, char, false)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error sending ACK: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun transmitPacket(
        targetDevice: BluetoothDevice,
        packet: MeshPacket,
        onComplete: (Boolean, String?) -> Unit
    ) {
        if (!isClientBusy.compareAndSet(false, true)) {
            onComplete(false, "ConnectionManager is currently busy")
            return
        }

        pendingPacket = packet
        onSendComplete = onComplete
        currentChunkIdx = 0

        clientTimeoutJob = coroutineScope.launch(Dispatchers.IO) {
            delay(CommunicationConstants.CONNECTION_TIMEOUT_MS)
            if (isClientBusy.get()) {
                finalizeClientSession(false, "Connection timed out")
            }
        }

        try {
            activeGattClient = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                targetDevice.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                targetDevice.connectGatt(context, false, clientCallback)
            }
        } catch (e: Exception) {
            finalizeClientSession(false, "Exception initiating connection: ${e.message}")
        }
    }

    private fun startChunkTransmission(gatt: BluetoothGatt) {
        val packet = pendingPacket ?: run {
            finalizeClientSession(false, "No pending packet")
            return
        }

        val maxPayload = kotlin.math.max(20, negotiatedMtu - 3 - CommunicationConstants.CHUNK_HEADER_SIZE)
        pendingChunks = PacketSerializer.chunkPacket(packet, maxPayload)
        currentChunkIdx = 0
        sendNextChunk(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun sendNextChunk(gatt: BluetoothGatt) {
        val service = gatt.getService(CommunicationConstants.SERVICE_UUID)
        val char = service?.getCharacteristic(CommunicationConstants.CHAR_MESSAGE_TRANSFER_UUID)
        if (char == null) {
            finalizeClientSession(false, "Transfer characteristic not found")
            return
        }

        val chunk = pendingChunks[currentChunkIdx]
        val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val res = gatt.writeCharacteristic(char, chunk.packetBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            res == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = chunk.packetBytes
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }

        if (!success) {
            finalizeClientSession(false, "Failed to write chunk $currentChunkIdx")
        }
    }

    private fun startAckTimeout() {
        clientTimeoutJob?.cancel()
        clientTimeoutJob = coroutineScope.launch(Dispatchers.IO) {
            delay(CommunicationConstants.ACK_TIMEOUT_MS)
            finalizeClientSession(false, "ACK timeout")
        }
    }

    private fun finalizeClientSession(success: Boolean, error: String?) {
        clientTimeoutJob?.cancel()
        val callback = onSendComplete
        onSendComplete = null

        coroutineScope.launch(Dispatchers.IO) {
            delay(250L)
            closeClientGatt()
            isClientBusy.set(false)
            pendingPacket = null
            pendingChunks = emptyList()
            callback?.invoke(success, error)
        }
    }

    @SuppressLint("MissingPermission")
    fun closeClientGatt() {
        try {
            activeGattClient?.disconnect()
            activeGattClient?.close()
            activeGattClient = null
        } catch (e: Exception) {
            Log.e(tag, "Error closing client GATT: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun closeServer() {
        try {
            gattServer?.clearServices()
            gattServer?.close()
            gattServer = null
            chunkAssembler.clear()
        } catch (e: Exception) {
            Log.e(tag, "Error closing server: ${e.message}")
        }
    }

    fun stop() {
        closeClientGatt()
        closeServer()
    }
}