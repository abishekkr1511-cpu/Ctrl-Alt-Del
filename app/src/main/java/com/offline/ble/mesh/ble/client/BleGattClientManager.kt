package com.offline.ble.mesh.ble.client

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.offline.ble.mesh.ble.BleConnectionState
import com.offline.ble.mesh.ble.BleConstants
import com.offline.ble.mesh.ble.protocol.AckPayload
import com.offline.ble.mesh.ble.protocol.MessageChunker
import com.offline.ble.mesh.ble.protocol.MessagePayload
import com.offline.ble.mesh.manager.DebugLogManager
import com.offline.ble.mesh.manager.MetricsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class BleGattClientManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val onAckReceived: (AckPayload) -> Unit
) {
    private val tag = "BleGattClient"

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _connectedDeviceId = MutableStateFlow<String?>(null)
    val connectedDeviceId: StateFlow<String?> = _connectedDeviceId.asStateFlow()

    private var activeGatt: BluetoothGatt? = null
    private var activePayload: MessagePayload? = null
    private var activeChunks: List<MessageChunker.Chunk> = emptyList()
    private var currentChunkIndex: Int = 0
    private var negotiatedMtu: Int = BleConstants.DEFAULT_ATT_MTU
    private var onCompleteCallback: ((Boolean, String?) -> Unit)? = null

    private var connectionJob: Job? = null
    private var ackTimeoutJob: Job? = null
    private var connectStartTime: Long = 0L
    private var transmitStartTime: Long = 0L
    private val isBusy = AtomicBoolean(false)

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            gatt ?: return
            DebugLogManager.d(tag, "Client connection state: $newState, status: $status for ${gatt.device.address}")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                val errMsg = "Connection failed with GATT status $status"
                DebugLogManager.e(tag, errMsg)
                MetricsManager.recordFailure(errMsg)
                handleTransmissionFailure(errMsg)
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val connTime = System.currentTimeMillis() - connectStartTime
                MetricsManager.recordConnection(connTime)
                _connectionState.value = BleConnectionState.CONNECTED
                _connectedDeviceId.value = gatt.device.address
                MetricsManager.setActiveConnections(1)
                DebugLogManager.i(tag, "GATT connected in ${connTime}ms. Requesting MTU ${BleConstants.DESIRED_ATT_MTU}")

                // Request MTU
                gatt.requestMtu(BleConstants.DESIRED_ATT_MTU)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionState.value = BleConnectionState.DISCONNECTED
                _connectedDeviceId.value = null
                MetricsManager.setActiveConnections(0)
                DebugLogManager.i(tag, "GATT disconnected")
                if (isBusy.get()) {
                    handleTransmissionFailure("Disconnected unexpectedly before completion")
                } else {
                    closeGatt()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            gatt ?: return
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else BleConstants.DEFAULT_ATT_MTU
            DebugLogManager.i(tag, "MTU configured: $negotiatedMtu (Status: $status). Discovering services...")
            _connectionState.value = BleConnectionState.DISCOVERING_SERVICES
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            gatt ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleTransmissionFailure("Service discovery failed with status $status")
                return
            }

            val service = gatt.getService(BleConstants.SERVICE_UUID)
            if (service == null) {
                handleTransmissionFailure("Mesh Service ${BleConstants.SERVICE_UUID} not found on peer")
                return
            }

            val ackChar = service.getCharacteristic(BleConstants.CHAR_ACKNOWLEDGEMENT_UUID)
            if (ackChar == null) {
                handleTransmissionFailure("Acknowledgement characteristic missing")
                return
            }

            // Subscribe to ACK notifications
            gatt.setCharacteristicNotification(ackChar, true)
            val cccd = ackChar.getDescriptor(BleConstants.CCCD_UUID)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
            } else {
                // If no CCCD found, start transmitting directly
                startChunkTransmission(gatt)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            gatt ?: return
            DebugLogManager.d(tag, "CCCD written with status $status. Starting chunk transmission...")
            startChunkTransmission(gatt)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            gatt ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleTransmissionFailure("Characteristic write failed with status $status")
                return
            }

            currentChunkIndex++
            if (currentChunkIndex < activeChunks.size) {
                sendNextChunk(gatt)
            } else {
                val transmitDuration = System.currentTimeMillis() - transmitStartTime
                val totalBytes = activePayload?.toBytes()?.size?.toLong() ?: 0L
                MetricsManager.recordTransmission(transmitDuration, totalBytes)
                DebugLogManager.i(tag, "All ${activeChunks.size} chunks sent in ${transmitDuration}ms. Waiting for ACK...")
                _connectionState.value = BleConnectionState.WAITING_FOR_ACK
                startAckTimeout(gatt)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            handleIncomingAck(value)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            super.onCharacteristicChanged(gatt, characteristic)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                characteristic?.value?.let { handleIncomingAck(it) }
            }
        }
    }

    private fun handleIncomingAck(bytes: ByteArray) {
        val ack = AckPayload.fromBytes(bytes) ?: return
        val current = activePayload ?: return
        if (ack.messageId == current.messageId) {
            ackTimeoutJob?.cancel()
            val ackLatency = System.currentTimeMillis() - transmitStartTime
            MetricsManager.recordAckReceived(ack.messageId, ackLatency)
            DebugLogManager.i(tag, "Valid ACK verified for message ${ack.messageId} in ${ackLatency}ms!")
            onAckReceived(ack)
            finishSession(true, null)
        }
    }

    @SuppressLint("MissingPermission")
    fun transmitMessage(
        device: BluetoothDevice,
        payload: MessagePayload,
        onResult: (Boolean, String?) -> Unit
    ) {
        if (!isBusy.compareAndSet(false, true)) {
            onResult(false, "Client is currently busy with another transmission")
            return
        }

        activePayload = payload
        onCompleteCallback = onResult
        _connectionState.value = BleConnectionState.CONNECTING
        connectStartTime = System.currentTimeMillis()

        DebugLogManager.i(tag, "Initiating GATT connection to ${device.address} for message ${payload.messageId}")

        connectionJob = coroutineScope.launch(Dispatchers.IO) {
            delay(BleConstants.CONNECTION_TIMEOUT_MS)
            if (_connectionState.value != BleConnectionState.WAITING_FOR_ACK &&
                _connectionState.value != BleConnectionState.TRANSMITTING &&
                _connectionState.value != BleConnectionState.DISCONNECTED
            ) {
                DebugLogManager.e(tag, "Connection attempt timed out after ${BleConstants.CONNECTION_TIMEOUT_MS}ms")
                handleTransmissionFailure("Connection timed out")
            }
        }

        try {
            activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: SecurityException) {
            handleTransmissionFailure("Missing BLUETOOTH_CONNECT permission: ${e.message}")
        } catch (e: Exception) {
            handleTransmissionFailure("Exception connecting to device: ${e.message}")
        }
    }

    private fun startChunkTransmission(gatt: BluetoothGatt) {
        val payload = activePayload ?: run {
            handleTransmissionFailure("Active payload is null")
            return
        }

        // Calculate max payload per chunk based on negotiated MTU
        // ATT MTU - 3 bytes ATT opcode/handle - 20 bytes custom chunk header
        val maxChunkPayload = kotlin.math.max(20, negotiatedMtu - 3 - BleConstants.CHUNK_HEADER_SIZE)
        val payloadBytes = payload.toBytes()
        activeChunks = MessageChunker.chunkMessage(payload.messageId, payloadBytes, maxChunkPayload)
        currentChunkIndex = 0
        transmitStartTime = System.currentTimeMillis()
        _connectionState.value = BleConnectionState.TRANSMITTING

        DebugLogManager.i(tag, "Prepared ${activeChunks.size} chunks (Total: ${payloadBytes.size} bytes, Chunk size: $maxChunkPayload)")
        sendNextChunk(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun sendNextChunk(gatt: BluetoothGatt) {
        val service = gatt.getService(BleConstants.SERVICE_UUID)
        val char = service?.getCharacteristic(BleConstants.CHAR_MESSAGE_TRANSFER_UUID)
        if (char == null) {
            handleTransmissionFailure("Message transfer characteristic not available")
            return
        }

        val chunk = activeChunks[currentChunkIndex]
        DebugLogManager.d(tag, "Writing chunk ${chunk.chunkIndex + 1}/${chunk.totalChunks} (${chunk.rawBytes.size} bytes)")

        val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeCharacteristic(
                char,
                chunk.rawBytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            result == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = chunk.rawBytes
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }

        if (!success) {
            handleTransmissionFailure("Failed to initiate write for chunk ${chunk.chunkIndex}")
        }
    }

    private fun startAckTimeout(gatt: BluetoothGatt) {
        ackTimeoutJob?.cancel()
        ackTimeoutJob = coroutineScope.launch(Dispatchers.IO) {
            delay(BleConstants.ACK_TIMEOUT_MS)
            DebugLogManager.w(tag, "ACK timed out after ${BleConstants.ACK_TIMEOUT_MS}ms")
            handleTransmissionFailure("ACK timed out")
        }
    }

    private fun handleTransmissionFailure(reason: String) {
        ackTimeoutJob?.cancel()
        connectionJob?.cancel()
        MetricsManager.recordFailure(reason)
        finishSession(false, reason)
    }

    private fun finishSession(success: Boolean, error: String?) {
        _connectionState.value = if (success) BleConnectionState.DISCONNECTING else BleConnectionState.FAILED
        val callback = onCompleteCallback
        onCompleteCallback = null

        coroutineScope.launch(Dispatchers.IO) {
            delay(300L)
            closeGatt()
            isBusy.set(false)
            activePayload = null
            activeChunks = emptyList()
            currentChunkIndex = 0
            _connectionState.value = BleConnectionState.DISCONNECTED
            callback?.invoke(success, error)
        }
    }

    @SuppressLint("MissingPermission")
    fun closeGatt() {
        try {
            activeGatt?.disconnect()
            activeGatt?.close()
            activeGatt = null
        } catch (e: Exception) {
            DebugLogManager.e(tag, "Exception closing GATT client", e)
        }
    }
}