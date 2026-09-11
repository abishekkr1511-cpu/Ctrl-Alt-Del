package com.offline.ble.mesh.communication

data class MeshPacket(
    val messageId: String,
    val timestamp: Long,
    val senderId: String,
    val senderLang: String = "en",
    val priority: String = "normal",
    val textContent: String
)