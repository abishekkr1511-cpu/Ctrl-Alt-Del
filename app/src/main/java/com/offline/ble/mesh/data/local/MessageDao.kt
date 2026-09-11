package com.offline.ble.mesh.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.offline.ble.mesh.data.model.MessageEntity
import com.offline.ble.mesh.data.model.MessageStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE messageId = :messageId)")
    suspend fun hasMessage(messageId: String): Boolean

    @Query("UPDATE messages SET status = :status, lastAttemptAt = :lastAttemptAt WHERE messageId = :messageId")
    suspend fun updateStatus(messageId: String, status: MessageStatus, lastAttemptAt: Long? = null)

    @Query("UPDATE messages SET status = 'DELIVERED' WHERE messageId = :messageId")
    suspend fun markDelivered(messageId: String)

    @Query("UPDATE messages SET retryCount = retryCount + 1, lastAttemptAt = :timestamp WHERE messageId = :messageId")
    suspend fun incrementRetry(messageId: String, timestamp: Long)

    @Query("SELECT * FROM messages WHERE status = 'PENDING' OR status = 'FAILED' ORDER BY createdAt ASC")
    suspend fun getPendingMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE receiverId = :receiverId AND (status = 'PENDING' OR status = 'FAILED') ORDER BY createdAt ASC")
    suspend fun getPendingMessagesForReceiver(receiverId: String): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessagesFlow(): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun getTotalMessageCount(): Int

    @Query("DELETE FROM messages WHERE status = 'EXPIRED' OR (status = 'FAILED' AND createdAt < :threshold)")
    suspend fun deleteExpired(threshold: Long)

    @Query("DELETE FROM messages")
    suspend fun clearAll()
}