package com.offline.ble.mesh.ble.protocol

import com.offline.ble.mesh.data.model.MessageEntity
import com.offline.ble.mesh.data.model.MessageStatus
import com.offline.ble.mesh.data.model.MessageType
import org.json.JSONObject

data class MessagePayload(
    val messageId: String,
    val senderId: String,
    val receiverId: String,
    val timestamp: Long,
    val content: String,
    val messageType: String = "TEXT",
    val hopCount: Int = 0,
    val ttl: Int = 5
) {
    fun toBytes(): ByteArray {
        val json = JSONObject().apply {
            put("mid", messageId)
            put("snd", senderId)
            put("rcv", receiverId)
            put("ts", timestamp)
            put("cnt", content)
            put("typ", messageType)
            put("hop", hopCount)
            put("ttl", ttl)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun toEntity(status: MessageStatus = MessageStatus.RECEIVED): MessageEntity {
        return MessageEntity(
            messageId = messageId,
            senderId = senderId,
            receiverId = receiverId,
            timestamp = timestamp,
            content = content,
            messageType = runCatching { MessageType.valueOf(messageType) }.getOrDefault(MessageType.TEXT),
            status = status,
            hopCount = hopCount,
            ttl = ttl,
            receivedAt = System.currentTimeMillis(),
            createdAt = timestamp
        )
    }

    companion object {
        fun fromBytes(bytes: ByteArray): MessagePayload? = runCatching {
            val str = String(bytes, Charsets.UTF_8)
            val json = JSONObject(str)
            MessagePayload(
                messageId = json.getString("mid"),
                senderId = json.getString("snd"),
                receiverId = json.getString("rcv"),
                timestamp = json.getLong("ts"),
                content = json.getString("cnt"),
                messageType = json.optString("typ", "TEXT"),
                hopCount = json.optInt("hop", 0),
                ttl = json.optInt("ttl", 5)
            )
        }.getOrNull()

        fun fromEntity(entity: MessageEntity): MessagePayload {
            return MessagePayload(
                messageId = entity.messageId,
                senderId = entity.senderId,
                receiverId = entity.receiverId,
                timestamp = entity.timestamp,
                content = entity.content,
                messageType = entity.messageType.name,
                hopCount = entity.hopCount,
                ttl = entity.ttl
            )
        }
    }
}