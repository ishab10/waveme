package com.waveme.mesh.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    suspend fun getAllMessagesSync(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE destinationId = 'BROADCAST' AND topic IS NULL ORDER BY timestamp ASC")
    fun getBroadcastMessages(): Flow<List<MessageEntity>>

    @Query("""
        SELECT * FROM messages 
        WHERE (senderId = :myId AND destinationId = :otherId) 
           OR (senderId = :otherId AND destinationId = :myId) 
        ORDER BY timestamp ASC
    """)
    fun getPrivateMessages(myId: String, otherId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE topic = :topic ORDER BY timestamp ASC")
    fun getGroupMessages(topic: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE isDelivered = 0 AND destinationId != 'BROADCAST' AND deliveryAttempts < :maxAttempts")
    suspend fun getPendingMessages(maxAttempts: Int): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("UPDATE messages SET isDelivered = 1 WHERE id = :messageId")
    suspend fun markAsDelivered(messageId: String)

    @Query("UPDATE messages SET isSeen = 1 WHERE id = :messageId")
    suspend fun markAsSeen(messageId: String)

    @Query("UPDATE messages SET deliveryAttempts = deliveryAttempts + 1 WHERE id = :messageId")
    suspend fun incrementDeliveryAttempts(messageId: String)

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getChatHistory(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    suspend fun getChatHistorySync(): List<MessageEntity>

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)
    
    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE type IN ('IMAGE', 'VIDEO') ORDER BY timestamp DESC")
    fun getGalleryPhotosAndVideos(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE type IN ('PDF', 'FILE') ORDER BY timestamp DESC")
    fun getGalleryDocuments(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE type = 'LINK' ORDER BY timestamp DESC")
    fun getGalleryLinks(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE attachmentPath IS NOT NULL OR type = 'LINK' ORDER BY timestamp DESC")
    fun getGalleryMedia(): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()

    @Query("DELETE FROM messages WHERE timestamp < :threshold")
    suspend fun deleteMessagesOlderThan(threshold: Long)
}
