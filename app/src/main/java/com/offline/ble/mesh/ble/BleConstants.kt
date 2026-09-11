package com.offline.ble.mesh.ble

import java.util.UUID

object BleConstants {
    // Custom GATT Service for Offline Mesh Communication
    val SERVICE_UUID: UUID = UUID.fromString("0000b1e0-0000-1000-8000-00805f9b34fb")

    // Characteristic: Device Identity Handshake (Read / Write)
    val CHAR_DEVICE_IDENTITY_UUID: UUID = UUID.fromString("0000b1e1-0000-1000-8000-00805f9b34fb")

    // Characteristic: Message Transfer (Write / Write without response)
    val CHAR_MESSAGE_TRANSFER_UUID: UUID = UUID.fromString("0000b1e2-0000-1000-8000-00805f9b34fb")

    // Characteristic: Acknowledgement (Notify / Write)
    val CHAR_ACKNOWLEDGEMENT_UUID: UUID = UUID.fromString("0000b1e3-0000-1000-8000-00805f9b34fb")

    // Standard Client Characteristic Configuration Descriptor (CCCD)
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // BLE Packet and MTU parameters
    const val DEFAULT_ATT_MTU = 23
    const val DESIRED_ATT_MTU = 512
    const val CHUNK_HEADER_SIZE = 20 // 16 bytes UUID + 2 bytes chunk index + 2 bytes total chunks
    const val MAX_CHUNK_PAYLOAD_SIZE = 180

    // Timing and retry constants
    const val ACK_TIMEOUT_MS = 10000L
    const val CONNECTION_TIMEOUT_MS = 12000L
    const val MAX_RETRY_COUNT = 4
    const val RETRY_BACKOFF_BASE_MS = 3000L

    // Scanning intervals for battery efficiency
    const val SCAN_ACTIVE_DURATION_MS = 10000L
    const val SCAN_REST_DURATION_MS = 15000L
}