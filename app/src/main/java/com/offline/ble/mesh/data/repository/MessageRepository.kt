package com.offline.ble.mesh.data.repository

import com.offline.ble.mesh.data.local.MessageDao
import com.offline.ble.mesh.data.model.MessageEntity
import com.offline.ble.mesh.data.model.MessageStatus
import kotlinx.coroutines.flow.Flow

class MessageRepository(private val messageDao: MessageDao) {

    val allMessages: Flow<List<MessageEntity>> = messageDao.getAllMessagesFlow()

    suspend fun insertMessage(message: MessageEntity): Boolean {
        val rowId = messageDao.insertMessage(message)
        return rowId != -1L
    }

    suspend fun updateMessage(message: MessageEntity) {
        messageDao.updateMessage(message)
    }

    suspend fun getMessageById(messageId: String): MessageEntity? {
        return messageDao.getMessageById(messageId)
    }

    suspend fun hasMessage(messageId: String): Boolean {
        return messageDao.hasMessage(messageId)
    }

    suspend fun updateStatus(messageId: String, status: MessageStatus, lastAttemptAt: Long? = null) {
        messageDao.updateStatus(messageId, status, lastAttemptAt)
    }

    suspend fun markDelivered(messageId: String) {
        messageDao.markDelivered(messageId)
    }

    suspend fun incrementRetry(messageId: String, timestamp: Long = System.currentTimeMillis()) {
        messageDao.incrementRetry(messageId, timestamp)
    }

    suspend fun getPendingMessages(): List<MessageEntity> {
        return messageDao.getPendingMessages()
    }

    suspend fun getPendingMessagesForReceiver(receiverId: String): List<MessageEntity> {
        return messageDao.getPendingMessagesForReceiver(receiverId)
    }

    suspend fun getTotalMessageCount(): Int {
        return messageDao.getTotalMessageCount()
    }

    suspend fun clearAll() {
        messageDao.clearAll()
    }

    suspend fun deleteExpired(threshold: Long) {
        messageDao.deleteExpired(threshold)
    }
}