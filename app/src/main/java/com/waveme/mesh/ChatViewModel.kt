package com.waveme.mesh

import android.net.Uri
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.waveme.mesh.data.AppDatabase
import com.waveme.mesh.data.MessageEntity
import com.waveme.mesh.data.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val database: AppDatabase,
    private val userPreferences: UserPreferences
) : ViewModel() {

    enum class PeerStatus {
        ONLINE, RECONNECTING, OFFLINE
    }

    var waveMeshService: WaveMeshService? = null

    private val _chatTitle = MutableLiveData<String>()
    val chatTitle: LiveData<String> = _chatTitle

    private val _recipientPeer = MutableLiveData<MeshRouter.Peer?>()
    val recipientPeer: LiveData<MeshRouter.Peer?> = _recipientPeer

    private val _progressBarVisibility = MutableLiveData<Boolean>()
    val progressBarVisibility: LiveData<Boolean> = _progressBarVisibility

    private val _progressBarProgress = MutableLiveData<Int>()
    val progressBarProgress: LiveData<Int> = _progressBarProgress

    private val _connectionStatusToast = MutableLiveData<String>()
    val connectionStatusToast: LiveData<String> = _connectionStatusToast

    private val _finishActivity = MutableLiveData<Boolean>()
    val finishActivity: LiveData<Boolean> = _finishActivity

    private val _fileError = MutableLiveData<String>()
    val fileError: LiveData<String> = _fileError

    private val _peerStatus = MutableLiveData<PeerStatus>(PeerStatus.ONLINE)
    val peerStatus: LiveData<PeerStatus> = _peerStatus

    fun getMessages(myId: String, recipientId: String): LiveData<List<MessageEntity>> {
        return when {
            recipientId.startsWith("group_") -> {
                val topic = recipientId.substringAfter("group_")
                database.messageDao().getGroupMessages(topic).asLiveData()
            }
            recipientId == MeshMessage.BROADCAST_ID -> {
                database.messageDao().getBroadcastMessages().asLiveData()
            }
            else -> {
                database.messageDao().getPrivateMessages(myId, recipientId).asLiveData()
            }
        }
    }

    fun sendMessage(recipientId: String, content: String) {
        val service = waveMeshService ?: return

        val topic = if (recipientId.startsWith("group_")) recipientId.substringAfter("group_") else null
        val destination = if (topic != null || recipientId == MeshMessage.BROADCAST_ID) MeshMessage.BROADCAST_ID else recipientId

        viewModelScope.launch {
            val msgId = UUID.randomUUID().toString()
            val entity = MessageEntity(
                id = msgId,
                senderId = service.myDeviceId,
                senderName = service.myNickname,
                destinationId = destination,
                content = content,
                timestamp = System.currentTimeMillis(),
                type = "TEXT",
                topic = topic,
                isEncrypted = destination != MeshMessage.BROADCAST_ID,
                isDelivered = false,
                isSeen = false,
                deliveryAttempts = 0
            )
            database.messageDao().insertMessage(entity)
            service.meshRouter.sendMessage(destination, content, msgId, topic)
        }
    }
    
    fun markAsSeen(messageId: String, senderId: String) {
        val service = waveMeshService ?: return
        viewModelScope.launch {
            database.messageDao().markAsSeen(messageId)
            service.meshRouter.sendSeenIndicator(messageId, senderId)
        }
    }

    fun deleteMessage(messageId: String, recipientId: String) {
        val service = waveMeshService ?: return

        val topic = if (recipientId.startsWith("group_")) recipientId.substringAfter("group_") else null
        val destination = if (topic != null || recipientId == MeshMessage.BROADCAST_ID) MeshMessage.BROADCAST_ID else recipientId

        viewModelScope.launch {
            database.messageDao().deleteMessage(messageId)
            service.meshRouter.sendDeleteRequest(messageId, destination, topic)
        }
    }

    fun sendFile(recipientId: String, uri: Uri, fileName: String, mimeType: String) {
        val service = waveMeshService ?: return

        val topic = if (recipientId.startsWith("group_")) recipientId.substringAfter("group_") else null
        val destination = if (topic != null || recipientId == MeshMessage.BROADCAST_ID) MeshMessage.BROADCAST_ID else recipientId

        val msgType = when {
            mimeType.startsWith("image/") -> "IMAGE"
            mimeType.startsWith("video/") -> "VIDEO"
            mimeType == "application/pdf" -> "PDF"
            else -> "FILE"
        }
        val displayContent = when (msgType) {
            "IMAGE" -> "Image: $fileName"
            "VIDEO" -> "Video: $fileName"
            "PDF" -> "PDF: $fileName"
            else -> "File: $fileName"
        }

        viewModelScope.launch {
            try {
                // Copy the file to a permanent internal location so the gallery can always find it
                val persistentFile = withContext(Dispatchers.IO) {
                    val sentDir = File(service.getExternalFilesDir(null), "sent")
                    if (!sentDir.exists()) sentDir.mkdirs()
                    val file = File(sentDir, "sent_${System.currentTimeMillis()}_$fileName")
                    service.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    file
                }

                val msgId = UUID.randomUUID().toString()
                val entity = MessageEntity(
                    id = msgId,
                    senderId = service.myDeviceId,
                    senderName = service.myNickname,
                    destinationId = destination,
                    content = displayContent,
                    attachmentPath = persistentFile.absolutePath,
                    timestamp = System.currentTimeMillis(),
                    type = msgType,
                    topic = topic,
                    isEncrypted = destination != MeshMessage.BROADCAST_ID && !destination.startsWith("group_"),
                    isDelivered = false,
                    isSeen = false,
                    deliveryAttempts = 0
                )
                database.messageDao().insertMessage(entity)
                service.meshRouter.sendFile(destination, uri, msgId, topic)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to send file", e)
                _fileError.postValue("Failed to send file: ${e.message}")
            }
        }
    }

    fun onFileProgress(progress: Int, recipientId: String, recipientName: String) {
        _progressBarVisibility.postValue(true)
        _progressBarProgress.postValue(progress)
        if (recipientId.startsWith("group_") || recipientId == MeshMessage.BROADCAST_ID) {
            _chatTitle.postValue("Sending ($progress%)...")
        }
        if (progress >= 100) {
            _progressBarVisibility.postValue(false)
            if (recipientId.startsWith("group_") || recipientId == MeshMessage.BROADCAST_ID) {
                _chatTitle.postValue(recipientName)
            }
        }
    }

    fun onFileCompleted(recipientId: String, recipientName: String) {
        _progressBarVisibility.postValue(false)
        if (recipientId.startsWith("group_") || recipientId == MeshMessage.BROADCAST_ID) {
            _chatTitle.postValue(recipientName)
        }
    }

    fun onConnectionStateChanged(endpointId: String, state: WaveMeshService.ConnectionState, endpointName: String, recipientId: String, recipientName: String) {
        val service = waveMeshService ?: return
        val targetEndpointId = service.meshRouter.getEndpointIdForDevice(recipientId)

        if (targetEndpointId == endpointId || service.meshRouter.getEndpointIdForDevice(recipientId) == null) {
            when (state) {
                WaveMeshService.ConnectionState.CONNECTING -> {
                    _peerStatus.postValue(PeerStatus.RECONNECTING)
                }
                WaveMeshService.ConnectionState.CONNECTED -> {
                    _peerStatus.postValue(PeerStatus.ONLINE)
                }
                else -> {
                    _peerStatus.postValue(PeerStatus.OFFLINE)
                }
            }
        }
    }

    fun checkInitialConnection(recipientId: String) {
        val service = waveMeshService ?: return

        // Update recipient peer info
        val peer = service.meshRouter.getKnownPeers().find { it.id == recipientId }
        _recipientPeer.postValue(peer)

        if (peer != null && !recipientId.startsWith("group_") && recipientId != MeshMessage.BROADCAST_ID) {
            _chatTitle.postValue(peer.name)
            // Request avatar if missing
            if (peer.avatarPath == null) {
                service.meshRouter.sendAvatarRequest(recipientId)
            }
        }

        if (recipientId.startsWith("group_") || recipientId == MeshMessage.BROADCAST_ID) {
            if (recipientId.startsWith("group_")) {
                val topic = recipientId.substringAfter("group_")
                // Automatically "join" the group when opening the chat
                val currentGroups = userPreferences.getUserGroups().toMutableSet()
                if (currentGroups.add(topic)) {
                    userPreferences.saveUserGroups(currentGroups)
                    service.broadcastUserGroups()
                }
            }
            _peerStatus.postValue(PeerStatus.ONLINE)
            return
        }

        val endpointId = service.meshRouter.getEndpointIdForDevice(recipientId)
        _peerStatus.postValue(if (endpointId != null) PeerStatus.ONLINE else PeerStatus.OFFLINE)
    }

    fun leaveGroup(groupId: String) {
        waveMeshService?.leaveGroup(groupId)
        _finishActivity.postValue(true)
    }
}
