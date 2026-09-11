package com.offline.ble.mesh.ble

enum class BleConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    DISCOVERING_SERVICES,
    READY,
    TRANSMITTING,
    WAITING_FOR_ACK,
    DISCONNECTING,
    FAILED
}