package com.offline.ble.mesh.ble.server

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.offline.ble.mesh.ble.BleConstants
import com.offline.ble.mesh.ble.protocol.AckPayload
import com.offline.ble.mesh.ble.protocol.MessageAssembler
import com.offline.ble.mesh.ble.protocol.MessagePayload
import com.offline.ble.mesh.manager.DebugLogManager
import java.util.concurrent.ConcurrentHashMap

class BleGattServerManager(
    private val context: Context,
    private val localDeviceId: String,
    private val onMessageReceived: (MessagePayload, BluetoothDevice) -> Unit,
    private val onAckReceived: (AckPayload) -> Unit
) {
    private val tag = "BleGattServer"
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private var gattServer: BluetoothGattServer? = null
    private val messageAssembler = MessageAssembler()
    private val connectedClients = ConcurrentHashMap<String, BluetoothDevice>()

    private var ackCharacteristic: BluetoothGattCharacteristic? = null

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            device ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedClients[device.address] = device
                DebugLogManager.i(tag, "Client connected to GATT Server: ${device.address} (Status: $status)")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedClients.remove(device.address)
                DebugLogManager.i(tag, "Client disconnected from GATT Server: ${device.address}")
            }
        }

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

            if (characteristic.uuid == BleConstants.CHAR_DEVICE_IDENTITY_UUID) {
                val fullBytes = localDeviceId.toByteArray(Charsets.UTF_8)
                val responseBytes = if (offset < fullBytes.size) {
                    fullBytes.copyOfRange(offset, fullBytes.size)
                } else {
                    ByteArray(0)
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, responseBytes)
                DebugLogManager.d(tag, "Sent local Device ID to reading client: ${device.address}")
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

            when (characteristic.uuid) {
                BleConstants.CHAR_MESSAGE_TRANSFER_UUID -> {
                    val completedPayload = messageAssembler.onChunkReceived(data)
                    if (completedPayload != null) {
                        DebugLogManager.i(tag, "Reassembled full message (${completedPayload.messageId}) from ${device.address}")
                        onMessageReceived(completedPayload, device)
                    }
                }
                BleConstants.CHAR_ACKNOWLEDGEMENT_UUID -> {
                    val ack = AckPayload.fromBytes(data)
                    if (ack != null) {
                        DebugLogManager.i(tag, "Received ACK write for message ${ack.messageId} from ${device.address}")
                        onAckReceived(ack)
                    }
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
            DebugLogManager.d(tag, "Descriptor write from ${device.address}: CCCD updated")
        }
    }

    @SuppressLint("MissingPermission")
    fun openServer(): Boolean {
        if (gattServer != null) return true
        if (bluetoothManager == null) {
            DebugLogManager.e(tag, "BluetoothManager is null")
            return false
        }

        try {
            val server = bluetoothManager.openGattServer(context, serverCallback)
            if (server == null) {
                DebugLogManager.e(tag, "Failed to open BluetoothGattServer")
                return false
            }
            gattServer = server

            val service = BluetoothGattService(
                BleConstants.SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            // 1. Device Identity Characteristic
            val identityChar = BluetoothGattCharacteristic(
                BleConstants.CHAR_DEVICE_IDENTITY_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            service.addCharacteristic(identityChar)

            // 2. Message Transfer Characteristic
            val messageChar = BluetoothGattCharacteristic(
                BleConstants.CHAR_MESSAGE_TRANSFER_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            service.addCharacteristic(messageChar)

            // 3. Acknowledgement Characteristic
            val ackChar = BluetoothGattCharacteristic(
                BleConstants.CHAR_ACKNOWLEDGEMENT_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            val cccd = BluetoothGattDescriptor(
                BleConstants.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            ackChar.addDescriptor(cccd)
            service.addCharacteristic(ackChar)
            ackCharacteristic = ackChar

            server.addService(service)
            DebugLogManager.i(tag, "BluetoothGattServer opened with Service ${BleConstants.SERVICE_UUID}")
            return true
        } catch (e: SecurityException) {
            DebugLogManager.e(tag, "Missing permission to open GATT server", e)
            return false
        } catch (e: Exception) {
            DebugLogManager.e(tag, "Exception opening GATT server", e)
            return false
        }
    }

    @SuppressLint("MissingPermission")
    fun sendAck(device: BluetoothDevice, ack: AckPayload) {
        val server = gattServer ?: return
        val char = ackCharacteristic ?: return
        val ackBytes = ack.toBytes()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, char, false, ackBytes)
            } else {
                @Suppress("DEPRECATION")
                char.value = ackBytes
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, char, false)
            }
            DebugLogManager.i(tag, "Notified ACK for msg ${ack.messageId} to ${device.address}")
        } catch (e: SecurityException) {
            DebugLogManager.e(tag, "Permission error sending ACK", e)
        } catch (e: Exception) {
            DebugLogManager.e(tag, "Exception sending ACK", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun closeServer() {
        try {
            gattServer?.clearServices()
            gattServer?.close()
            gattServer = null
            connectedClients.clear()
            messageAssembler.clear()
            DebugLogManager.i(tag, "BluetoothGattServer closed")
        } catch (e: Exception) {
            DebugLogManager.e(tag, "Exception closing GATT server", e)
        }
    }
}