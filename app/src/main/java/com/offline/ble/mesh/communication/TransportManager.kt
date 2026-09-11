package com.offline.ble.mesh.communication

interface TransportManager {
    /**
     * Starts BLE advertising and continuous/periodic discovery for nearby compatible phones.
     */
    fun startAdvertisingAndDiscovery()

    /**
     * Transmits a MeshPacket over the air to available nearby phone(s).
     */
    fun sendPacket(packet: MeshPacket)

    /**
     * Registers a callback invoked whenever a valid MeshPacket is received from another phone.
     */
    fun onPacketReceived(callback: (MeshPacket) -> Unit)

    /**
     * Halts advertising, scanning, and active GATT connections to release radio hardware resources.
     */
    fun stop()
}