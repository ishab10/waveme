package com.waveme.mesh

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import com.waveme.mesh.data.AppDatabase
import com.waveme.mesh.data.MessageEntity
import com.waveme.mesh.data.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class ChatsViewModel @Inject constructor(
    private val database: AppDatabase,
    private val userPreferences: UserPreferences
) : ViewModel() {

    private val myId = userPreferences.getUserId()

    val chatList: LiveData<List<Pair<String, MessageEntity>>> = 
        database.messageDao().getChatHistory().map { messages ->
            messages.groupBy { msg ->
                if (msg.destinationId == MeshMessage.BROADCAST_ID) {
                    MeshMessage.BROADCAST_ID
                } else if (msg.topic != null) {
                    "group_${msg.topic}"
                } else if (msg.senderId == myId) {
                    msg.destinationId 
                } else {
                    msg.senderId
                }
            }.map { (id, msgs) ->
                val lastMsg = msgs.maxByOrNull { it.timestamp }!!
                val displayName = when {
                    id == MeshMessage.BROADCAST_ID -> "Mesh Group Chat"
                    id.startsWith("group_") -> id.removePrefix("group_")
                    lastMsg.senderId == myId -> "Chat with ${lastMsg.destinationId}"
                    else -> lastMsg.senderName
                }
                displayName to lastMsg
            }.sortedByDescending { it.second.timestamp }
        }.asLiveData()
}
