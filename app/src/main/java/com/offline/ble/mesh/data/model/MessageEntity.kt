package com.offline.ble.mesh.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey
    val messageId: String,
    val senderId: String,
    val receiverId: String,
    val timestamp: Long,
    val content: String,
    val messageType: MessageType = MessageType.TEXT,
    val status: MessageStatus = MessageStatus.PENDING,
    val hopCount: Int = 0,
    val ttl: Int = 5,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAttemptAt: Long? = null,
    val retryCount: Int = 0,
    val receivedAt: Long? = null,
    val forwarded: Boolean = false
)