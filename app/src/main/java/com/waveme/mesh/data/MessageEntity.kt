package com.waveme.mesh.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val senderId: String,
    val senderName: String,
    val destinationId: String,
    val content: String,
    val attachmentPath: String? = null,
    val timestamp: Long,
    val type: String,
    val topic: String?,
    val isEncrypted: Boolean,
    val isDelivered: Boolean = false,
    val isSeen: Boolean = false,
    val deliveryAttempts: Int = 0
)
