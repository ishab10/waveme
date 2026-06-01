package com.waveme.mesh

import android.net.Uri
import android.util.Log
import com.waveme.mesh.data.UserPreferences
import com.google.android.gms.nearby.connection.Payload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MeshRouter(
    private val myDeviceId: String,
    private var myDeviceName: String,
    private val connectionsManager: NearbyConnectionsManager,
    private val encryptionManager: EncryptionManager,
    private val fileTransferManager: FileTransferManager,
    private val messageListener: (MeshMessage) -> Unit,
    private val onPeersChanged: (List<Peer>) -> Unit,
    private val onDiscoveredTopicsChanged: (Map<String, Long>) -> Unit,
    private val onMessageSent: (String) -> Unit,
    private val onMessageDeleted: (String) -> Unit = {},
    private val onMessageAcked: (String) -> Unit = {},
    private val onMessageSeen: (String) -> Unit = {}
) {
    data class Peer(val id: String, val name: String, val expiryTime: Long? = null, val avatarPath: String? = null)

    companion object {
        val DEFAULT_CHANNELS = listOf("Music", "Sports", "Tech")
    }

    private val seenMessages = Collections.synchronizedMap(object : java.util.LinkedHashMap<String, Boolean>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
            return size > 500
        }
    })

    private val publicKeys = ConcurrentHashMap<String, String>()
    private val knownPeers = ConcurrentHashMap<String, Peer>()
    private val discoveredTopics = ConcurrentHashMap<String, Long>()
    
    private val nextHopTable = ConcurrentHashMap<String, String>()
    private val distanceTable = ConcurrentHashMap<String, Int>()

    private val deviceIdToEndpointId = ConcurrentHashMap<String, String>() 
    private val endpointIdToDeviceId = ConcurrentHashMap<String, String>() 

    private val pendingPrivateMessages = ConcurrentHashMap<String, MutableList<PendingMessage>>()
    private val pendingFiles = ConcurrentHashMap<String, MutableList<PendingFile>>()

    data class PendingMessage(val id: String, val content: String, val type: MessageType)
    data class PendingFile(val messageId: String, val uri: Uri)

    private val scope = CoroutineScope(Dispatchers.IO)
    private var cleanupJob: Job? = null
    
    private val userPreferences = UserPreferences(connectionsManager.getContext())

    init {
        startCleanupJob()
    }

    private fun startCleanupJob() {
        cleanupJob = scope.launch {
            while (true) {
                delay(10000) 
                val now = System.currentTimeMillis()
                val expired = discoveredTopics.filter { it.value != Long.MAX_VALUE && it.value < now }.keys
                if (expired.isNotEmpty()) {
                    expired.forEach { discoveredTopics.remove(it) }
                    onDiscoveredTopicsChanged(discoveredTopics.toMap())
                }
            }
        }
    }

    fun updateMyName(newName: String) {
        myDeviceName = newName
    }

    fun broadcastPublicKey() {
        val publicKey = encryptionManager.getPublicKeyStr()
        if (publicKey.isNotEmpty()) {
            val message = MeshMessage(
                senderId = myDeviceId,
                senderName = myDeviceName,
                destinationId = MeshMessage.BROADCAST_ID,
                content = publicKey,
                type = MessageType.PUBLIC_KEY_ANNOUNCEMENT,
                ttl = 3 
            )
            routeMessage(message)
        }
    }
    
    fun broadcastUserGroups(groups: Set<String>) {
        val jsonArray = JSONArray(groups)
        val message = MeshMessage(
            senderId = myDeviceId,
            senderName = myDeviceName,
            destinationId = MeshMessage.BROADCAST_ID,
            content = jsonArray.toString(),
            type = MessageType.GROUP_ANNOUNCEMENT
        )
        routeMessage(message)
    }

    fun sendMessage(destinationId: String, content: String, messageId: String? = null, topic: String? = null) {
        scope.launch {
            var finalContent = content
            var isEncrypted = false

            if (destinationId != MeshMessage.BROADCAST_ID) {
                val recipientKey = publicKeys[destinationId]
                if (recipientKey != null) {
                    val encrypted = encryptionManager.encrypt(content, recipientKey)
                    if (encrypted != null) {
                        finalContent = encrypted
                        isEncrypted = true
                    } else {
                        Log.e("MeshRouter", "Encryption failed")
                        return@launch
                    }
                } else {
                    if (messageId != null) {
                        pendingPrivateMessages.computeIfAbsent(destinationId) { mutableListOf() }
                            .add(PendingMessage(messageId, content, MessageType.TEXT))
                    }
                    return@launch
                }
            }

            val message = MeshMessage(
                id = messageId ?: UUID.randomUUID().toString(),
                senderId = myDeviceId,
                senderName = myDeviceName,
                destinationId = destinationId,
                content = finalContent,
                topic = topic,
                type = MessageType.TEXT,
                isEncrypted = isEncrypted
            )
            routeMessage(message)

            if (messageId != null) {
                onMessageSent(messageId)
            }
        }
    }

    fun sendDeleteRequest(messageId: String, destinationId: String, topic: String? = null) {
        scope.launch {
            val message = MeshMessage(
                id = UUID.randomUUID().toString(),
                senderId = myDeviceId,
                senderName = myDeviceName,
                destinationId = destinationId,
                content = messageId, 
                topic = topic,
                type = MessageType.MESSAGE_DELETE,
                isEncrypted = false
            )
            routeMessage(message)
        }
    }

    fun sendAck(messageId: String, destinationId: String) {
        scope.launch {
            val message = MeshMessage(
                id = UUID.randomUUID().toString(),
                senderId = myDeviceId,
                senderName = myDeviceName,
                destinationId = destinationId,
                content = messageId,
                type = MessageType.ACK,
                isEncrypted = false
            )
            routeMessage(message)
        }
    }

    fun sendSeenIndicator(messageId: String, destinationId: String) {
        scope.launch {
            val message = MeshMessage(
                id = UUID.randomUUID().toString(),
                senderId = myDeviceId,
                senderName = myDeviceName,
                destinationId = destinationId,
                content = messageId,
                type = MessageType.SEEN,
                isEncrypted = false
            )
            routeMessage(message)
        }
    }

    fun sendAvatarRequest(destinationId: String) {
        scope.launch {
            val message = MeshMessage(
                id = UUID.randomUUID().toString(),
                senderId = myDeviceId,
                senderName = myDeviceName,
                destinationId = destinationId,
                content = "REQUEST_AVATAR",
                type = MessageType.AVATAR_REQUEST,
                isEncrypted = false
            )
            routeMessage(message)
        }
    }

    fun sendFile(destinationId: String, uri: Uri, messageId: String, topic: String? = null) {
        scope.launch {
            var recipientKey: String? = null
            if (destinationId != MeshMessage.BROADCAST_ID) {
                recipientKey = publicKeys[destinationId]
                if (recipientKey == null) {
                    pendingFiles.computeIfAbsent(destinationId) { mutableListOf() }
                        .add(PendingFile(messageId, uri))
                    return@launch
                }
            }

            val nextHop = if (destinationId != MeshMessage.BROADCAST_ID) nextHopTable[destinationId] else null
            
            if (nextHop != null) {
                fileTransferManager.sendFile(listOf(nextHop), uri, destinationId, myDeviceId, recipientKey, topic)
            } else {
                val connectedEndpoints = connectionsManager.getConnectedEndpoints()
                if (connectedEndpoints.isNotEmpty()) {
                    fileTransferManager.sendFile(connectedEndpoints, uri, destinationId, myDeviceId, recipientKey, topic)
                }
            }

            onMessageSent(messageId)
        }
    }

    fun onPayloadReceived(fromEndpointId: String, payload: Payload) {
        when (payload.type) {
            Payload.Type.BYTES -> {
                val bytes = payload.asBytes() ?: return
                val jsonStr = String(bytes, StandardCharsets.UTF_8)

                if (jsonStr.startsWith("FILE_METADATA:") || jsonStr.startsWith("JSON_METADATA:")) {
                    return
                }

                val message = MeshMessage.fromJson(jsonStr)
                if (message != null) {
                    handleMessage(message, fromEndpointId)
                }
            }
            Payload.Type.FILE -> {}
            Payload.Type.STREAM -> {}
        }
    }

    private fun handleMessage(message: MeshMessage, fromEndpointId: String) {
        if (seenMessages.containsKey(message.id)) {
            return
        }
        seenMessages[message.id] = true

        // POLICY COMPLIANCE: Drop all messages from blocked users
        if (userPreferences.getBlockedUsers().contains(message.senderId)) {
            Log.d("MeshRouter", "Dropped message from blocked user: ${message.senderId}")
            return
        }

        val currentDistance = distanceTable[message.senderId] ?: Int.MAX_VALUE
        val newDistance = 5 - message.ttl 
        
        if (newDistance < currentDistance) {
            distanceTable[message.senderId] = newDistance
            nextHopTable[message.senderId] = fromEndpointId
        }

        // Always update peer info from any message to keep names fresh
        val existingPeer = knownPeers[message.senderId]
        if (existingPeer == null || existingPeer.name != message.senderName) {
            val peer = Peer(
                id = message.senderId,
                name = message.senderName,
                expiryTime = existingPeer?.expiryTime,
                avatarPath = existingPeer?.avatarPath
            )
            knownPeers[message.senderId] = peer
            onPeersChanged(knownPeers.values.toList())
        }

        if (message.type == MessageType.PUBLIC_KEY_ANNOUNCEMENT) {
            publicKeys[message.senderId] = message.content
            
            if (newDistance == 0) {
                deviceIdToEndpointId[message.senderId] = fromEndpointId
                endpointIdToDeviceId[fromEndpointId] = message.senderId
            }

            pendingPrivateMessages.remove(message.senderId)?.forEach { pending ->
                sendMessage(message.senderId, pending.content, pending.id)
            }

            pendingFiles.remove(message.senderId)?.forEach { pending ->
                sendFile(message.senderId, pending.uri, pending.messageId)
            }
        } else if (message.type == MessageType.GROUP_ANNOUNCEMENT) {
            val jsonArray = JSONArray(message.content)
            val ephemeralExpiry = System.currentTimeMillis() + (19 * 60 * 1000)
            var updated = false
            for (i in 0 until jsonArray.length()) {
                val topic = jsonArray.getString(i)
                val expiry = if (DEFAULT_CHANNELS.contains(topic)) Long.MAX_VALUE else ephemeralExpiry
                discoveredTopics[topic] = expiry
                updated = true
            }
            if (updated) {
                onDiscoveredTopicsChanged(discoveredTopics.toMap())
            }
        } else if (message.type == MessageType.MESSAGE_DELETE) {
            onMessageDeleted(message.content)
        } else if (message.type == MessageType.ACK) {
            if (message.destinationId == myDeviceId) {
                onMessageAcked(message.content)
            }
        } else if (message.type == MessageType.SEEN) {
            if (message.destinationId == myDeviceId) {
                onMessageSeen(message.content)
            }
        }

        val isTarget = if (message.topic != null) {
            // If it's a topic message, we are a target if we have joined this topic
            val userGroups = userPreferences.getUserGroups()
            userGroups.contains(message.topic)
        } else {
            // If it's not a topic message, standard destination check
            message.destinationId == myDeviceId || (message.destinationId == MeshMessage.BROADCAST_ID && message.topic == null)
        }

        if (isTarget) {
            scope.launch {
                // Filter out system message types from reaching the UI message listener
                val isSystemMessage = message.type == MessageType.PUBLIC_KEY_ANNOUNCEMENT ||
                                     message.type == MessageType.GROUP_ANNOUNCEMENT ||
                                     message.type == MessageType.MESSAGE_DELETE ||
                                     message.type == MessageType.ACK ||
                                     message.type == MessageType.SEEN ||
                                     message.type == MessageType.AVATAR_REQUEST ||
                                     message.type == MessageType.AVATAR_UPDATE

                if (!isSystemMessage) {
                    if (message.isEncrypted && message.destinationId == myDeviceId) {
                        sendAck(message.id, message.senderId)
                        val decryptedContent = encryptionManager.decrypt(message.content)
                        if (decryptedContent != null) {
                            val decryptedMessage = message.copy(content = decryptedContent, isEncrypted = false)
                            messageListener(decryptedMessage)
                        }
                    } else {
                        if (message.destinationId == myDeviceId && message.type == MessageType.TEXT) {
                            sendAck(message.id, message.senderId)
                        }
                        messageListener(message)
                    }
                }
            }
        }

        if (message.ttl > 0 && (message.destinationId != myDeviceId)) {
            forwardMessage(message, fromEndpointId)
        }
    }

    private fun forwardMessage(message: MeshMessage, excludeEndpointId: String?) {
        val newTtl = message.ttl - 1
        if (newTtl <= 0) return

        val forwardedMessage = message.copy(ttl = newTtl)
        val jsonStr = forwardedMessage.toJson()
        val payload = Payload.fromBytes(jsonStr.toByteArray(StandardCharsets.UTF_8))

        val nextHop = if (message.destinationId != MeshMessage.BROADCAST_ID) nextHopTable[message.destinationId] else null
        
        if (nextHop != null && nextHop != excludeEndpointId) {
            connectionsManager.sendPayload(nextHop, payload)
        } else {
            connectionsManager.sendPayloadToAllBut(payload, excludeEndpointId)
        }
    }

    private fun routeMessage(message: MeshMessage) {
        seenMessages[message.id] = true
        val jsonStr = message.toJson()
        val payload = Payload.fromBytes(jsonStr.toByteArray(StandardCharsets.UTF_8))
        
        val nextHop = if (message.destinationId != MeshMessage.BROADCAST_ID) nextHopTable[message.destinationId] else null
        
        if (nextHop != null) {
            connectionsManager.sendPayload(nextHop, payload)
        } else {
            connectionsManager.sendPayloadToAll(payload)
        }
    }

    fun onPeerDisconnected(endpointId: String) {
        val deviceId = endpointIdToDeviceId.remove(endpointId)
        if (deviceId != null) {
            deviceIdToEndpointId.remove(deviceId)
            publicKeys.remove(deviceId)
            knownPeers.remove(deviceId)
            
            val affectedDestinations = nextHopTable.filter { it.value == endpointId }.keys
            affectedDestinations.forEach { 
                nextHopTable.remove(it)
                distanceTable.remove(it)
            }
            
            onPeersChanged(knownPeers.values.toList())
        }
    }

    fun updatePeerAvatar(peerId: String, path: String) {
        val existing = knownPeers[peerId]
        if (existing != null) {
            knownPeers[peerId] = existing.copy(avatarPath = path)
            onPeersChanged(knownPeers.values.toList())
        } else {
            // Even if we don't know the name yet, we can store the avatar path
            knownPeers[peerId] = Peer(id = peerId, name = "Unknown", avatarPath = path)
            onPeersChanged(knownPeers.values.toList())
        }
    }

    fun getEndpointIdForDevice(deviceId: String): String? {
        return deviceIdToEndpointId[deviceId] ?: nextHopTable[deviceId]
    }

    fun getKnownPeers(): List<Peer> {
        return knownPeers.values.toList()
    }

    fun getKnownTopics(): Map<String, Long> {
        return discoveredTopics.toMap()
    }
}
