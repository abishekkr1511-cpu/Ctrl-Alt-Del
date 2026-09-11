package com.offline.ble.mesh.ble.protocol

import org.json.JSONObject

data class AckPayload(
    val messageId: String,
    val receiverId: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toBytes(): ByteArray {
        val json = JSONObject().apply {
            put("type", "ACK")
            put("mid", messageId)
            put("rcv", receiverId)
            put("ts", timestamp)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun fromBytes(bytes: ByteArray): AckPayload? = runCatching {
            val str = String(bytes, Charsets.UTF_8)
            val json = JSONObject(str)
            if (json.optString("type") == "ACK") {
                AckPayload(
                    messageId = json.getString("mid"),
                    receiverId = json.getString("rcv"),
                    timestamp = json.getLong("ts")
                )
            } else null
        }.getOrNull()
    }
}