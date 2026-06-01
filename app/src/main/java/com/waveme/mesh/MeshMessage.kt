package com.waveme.mesh

import org.json.JSONObject
import java.util.UUID

enum class MessageType {
    TEXT, FILE_OFFER, FILE_REQUEST, IMAGE, AUDIO, UNKNOWN, PUBLIC_KEY_ANNOUNCEMENT, AVATAR_REQUEST, AVATAR_UPDATE, GROUP_ANNOUNCEMENT, MESSAGE_DELETE, ACK, SEEN
}

data class MeshMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val senderName: String,
    val destinationId: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: MessageType = MessageType.TEXT,
    val topic: String? = null,
    val ttl: Int = 5,
    val isEncrypted: Boolean = false
) {
    companion object {
        const val BROADCAST_ID = "BROADCAST"

        fun fromJson(jsonStr: String): MeshMessage? {
            return try {
                val json = JSONObject(jsonStr)
                MeshMessage(
                    id = json.getString("id"),
                    senderId = json.getString("senderId"),
                    senderName = json.getString("senderName"),
                    destinationId = json.getString("destinationId"),
                    content = json.getString("content"),
                    timestamp = json.getLong("timestamp"),
                    type = MessageType.valueOf(json.getString("type")),
                    topic = json.optString("topic", null),
                    ttl = json.optInt("ttl", 5),
                    isEncrypted = json.optBoolean("isEncrypted", false)
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    fun toJson(): String {
        val json = JSONObject()
        json.put("id", id)
        json.put("senderId", senderId)
        json.put("senderName", senderName)
        json.put("destinationId", destinationId)
        json.put("content", content)
        json.put("timestamp", timestamp)
        json.put("type", type.name)
        json.put("topic", topic)
        json.put("ttl", ttl)
        json.put("isEncrypted", isEncrypted)
        return json.toString()
    }
}
