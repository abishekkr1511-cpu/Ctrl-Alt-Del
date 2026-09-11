package com.offline.ble.mesh.data.local

import androidx.room.TypeConverter
import com.offline.ble.mesh.data.model.MessageStatus
import com.offline.ble.mesh.data.model.MessageType

class Converters {
    @TypeConverter
    fun fromMessageStatus(status: MessageStatus): String = status.name

    @TypeConverter
    fun toMessageStatus(value: String): MessageStatus = runCatching {
        MessageStatus.valueOf(value)
    }.getOrDefault(MessageStatus.PENDING)

    @TypeConverter
    fun fromMessageType(type: MessageType): String = type.name

    @TypeConverter
    fun toMessageType(value: String): MessageType = runCatching {
        MessageType.valueOf(value)
    }.getOrDefault(MessageType.TEXT)
}