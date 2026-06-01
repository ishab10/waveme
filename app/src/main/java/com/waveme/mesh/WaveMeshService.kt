package com.waveme.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.waveme.mesh.data.AppDatabase
import com.waveme.mesh.data.MessageEntity
import com.waveme.mesh.data.UserPreferences
import com.waveme.mesh.data.SyncManager
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

class WaveMeshService : Service(), NearbyConnectionsManager.NearbyConnectionsListener {

    private val binder = LocalBinder()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    lateinit var nearbyConnectionsManager: NearbyConnectionsManager
        private set
    lateinit var meshRouter: MeshRouter
        private set
    lateinit var encryptionManager: EncryptionManager
        private set
    lateinit var fileTransferManager: FileTransferManager
        private set
    private lateinit var database: AppDatabase
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var userPreferences: UserPreferences
    private lateinit var syncManager: SyncManager

    lateinit var myDeviceId: String
    lateinit var myNickname: String
    var isServiceRunning = false
        private set

    private var isLowPowerMode = true 
    private var powerModeJob: Job? = null
    private var boundClientCount = 0

    companion object {
        const val TAG = "WaveMeshService"
        const val ACTION_QUIT = "com.waveme.mesh.ACTION_QUIT"
    }

    enum class ConnectionState {
        CONNECTING, CONNECTED, FAILED, LOST
    }

    interface ServiceListener {
        fun onPeersChanged(peers: List<MeshRouter.Peer>)
        fun onDiscoveredTopicsChanged(topics: Map<String, Long>)
        fun onMessageReceived(message: MessageEntity)
        fun onMessageAcked(messageId: String)
        fun onMessageSeen(messageId: String)
        fun onFileProgress(payloadId: Long, fileName: String, progress: Int, isIncoming: Boolean)
        fun onFileCompleted(fileName: String, senderId: String)
        fun onConnectionStateChanged(endpointId: String, state: ConnectionState, endpointName: String)
    }

    private val listeners = CopyOnWriteArrayList<ServiceListener>()

    inner class LocalBinder : Binder() {
        fun getService(): WaveMeshService = this@WaveMeshService
    }

    override fun onBind(intent: Intent): IBinder {
        boundClientCount++
        powerModeJob?.cancel()
        refreshPowerMode()
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        boundClientCount--
        if (boundClientCount <= 0) {
            powerModeJob?.cancel()
            powerModeJob = serviceScope.launch {
                delay(3000)
                setLowPowerMode(true)
            }
        }
        return true
    }

    override fun onRebind(intent: Intent?) {
        boundClientCount++
        powerModeJob?.cancel()
        refreshPowerMode()
        super.onRebind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) {
            Log.d(TAG, "Quit action received from notification")
            stopSelf()
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WaveMeshService Created")

        database = AppDatabase.getDatabase(this)
        notificationHelper = NotificationHelper(this)
        userPreferences = UserPreferences(this)
        syncManager = SyncManager()

        myDeviceId = userPreferences.getUserId()
        myNickname = userPreferences.getUsername()
        
        // Initial power mode based on user settings
        isLowPowerMode = userPreferences.isEcoMode()

        nearbyConnectionsManager = NearbyConnectionsManager(this, this)
        encryptionManager = EncryptionManager()
        fileTransferManager = FileTransferManager(this, nearbyConnectionsManager, encryptionManager)

        fileTransferManager.setListener(object : FileTransferManager.FileTransferListener {
            override fun onFileCompleted(file: File, fileName: String, senderId: String, destinationId: String, topic: String?) {
                serviceScope.launch {
                    val isAvatar = fileName.startsWith("AVATAR_")
                    if (isAvatar) {
                        meshRouter.updatePeerAvatar(senderId, file.absolutePath)
                        return@launch
                    }

                    // Resolve sender nickname if known
                    val senderName = meshRouter.getKnownPeers().find { it.id == senderId }?.name ?: "Peer"

                    val extension = file.extension.lowercase()
                    val msgType = when {
                        extension in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp") -> "IMAGE"
                        extension in listOf("mp4", "mkv", "avi", "mov", "3gp") -> "VIDEO"
                        extension == "pdf" -> "PDF"
                        else -> "FILE"
                    }
                    val contentText = when (msgType) {
                        "IMAGE" -> "Image received: $fileName"
                        "VIDEO" -> "Video received: $fileName"
                        "PDF" -> "PDF received: $fileName"
                        else -> "File received: $fileName"
                    }

                    val entity = MessageEntity(
                        id = UUID.randomUUID().toString(),
                        senderId = senderId,
                        senderName = senderName,
                        destinationId = destinationId,
                        content = contentText,
                        attachmentPath = file.absolutePath,
                        timestamp = System.currentTimeMillis(),
                        type = msgType,
                        topic = topic,
                        isEncrypted = destinationId != MeshMessage.BROADCAST_ID && !destinationId.startsWith("group_"),
                        isDelivered = true
                    )
                    database.messageDao().insertMessage(entity)
                    
                    // Auto-sync received file message to hardware node
                    syncManager.syncToNode(entity)

                    withContext(Dispatchers.Main) {
                        listeners.forEach { it.onMessageReceived(entity) }
                        listeners.forEach { it.onFileCompleted(fileName, senderId) }
                    }

                    notificationHelper.showConnectionNotification("File Received", "$senderName shared $fileName")
                    notificationHelper.hideFileProgressNotification()
                }
            }

            override fun onFileProgress(payloadId: Long, fileName: String, progress: Int, isIncoming: Boolean) {
                if (!fileName.startsWith("AVATAR_")) {
                    notificationHelper.showFileProgressNotification(fileName, progress, isIncoming)
                }
                serviceScope.launch(Dispatchers.Main) {
                    listeners.forEach { it.onFileProgress(payloadId, fileName, progress, isIncoming) }
                }
            }

            override fun onTransferFailed(payloadId: Long, isIncoming: Boolean) {
                Log.e(TAG, "File transfer failed. Payload: $payloadId, Incoming: $isIncoming")
                notificationHelper.showConnectionNotification("Transfer Failed", "File transfer was canceled or failed.")
                notificationHelper.hideFileProgressNotification()
            }
        })

        meshRouter = MeshRouter(
            myDeviceId,
            myNickname,
            nearbyConnectionsManager,
            encryptionManager,
            fileTransferManager,
            messageListener = { message ->
                serviceScope.launch {
                    if (message.type == MessageType.AVATAR_REQUEST) {
                        sendAvatarTo(message.senderId)
                        return@launch
                    }

                    if (message.type == MessageType.DATA_SYNC_REQUEST) {
                        handleSyncRequest(message)
                        return@launch
                    }

                    val isLink = message.content.contains("http://") || message.content.contains("https://")
                    val finalType = if (isLink) "LINK" else message.type.name

                    val entity = MessageEntity(
                        id = message.id,
                        senderId = message.senderId,
                        senderName = message.senderName,
                        destinationId = message.destinationId,
                        content = if (message.type == MessageType.PUBLIC_KEY_ANNOUNCEMENT) "New peer key received" else message.content,
                        timestamp = message.timestamp,
                        type = finalType,
                        topic = message.topic,
                        isEncrypted = message.isEncrypted,
                        isDelivered = true
                    )
                    database.messageDao().insertMessage(entity)
                    
                    // Auto-sync received message to hardware node
                    syncManager.syncToNode(entity)

                    withContext(Dispatchers.Main) {
                        listeners.forEach { it.onMessageReceived(entity) }
                    }

                    if (message.type != MessageType.PUBLIC_KEY_ANNOUNCEMENT) {
                        notificationHelper.showConnectionNotification("New Message", "${message.senderName}: ${message.content}")
                    }
                }
            },
            onPeersChanged = { peers ->
                serviceScope.launch(Dispatchers.Main) {
                    listeners.forEach { it.onPeersChanged(peers) }
                }
            },
            onDiscoveredTopicsChanged = { topics ->
                serviceScope.launch(Dispatchers.Main) {
                    listeners.forEach { it.onDiscoveredTopicsChanged(topics) }
                }
            },
            onMessageSent = { _ ->
                serviceScope.launch {
                }
            },
            onMessageDeleted = { messageId ->
                serviceScope.launch {
                    database.messageDao().deleteMessage(messageId)
                }
            },
            onMessageAcked = { messageId ->
                serviceScope.launch {
                    database.messageDao().markAsDelivered(messageId)
                    withContext(Dispatchers.Main) {
                        listeners.forEach { it.onMessageAcked(messageId) }
                    }
                }
            },
            onMessageSeen = { messageId ->
                serviceScope.launch {
                    database.messageDao().markAsSeen(messageId)
                    withContext(Dispatchers.Main) {
                        listeners.forEach { it.onMessageSeen(messageId) }
                    }
                }
            }
        )

        startForegroundService()
        isServiceRunning = true

        startMesh()
        startRetryLoop()
        startAutoDeleteTask()
    }

    private fun handleSyncRequest(message: MeshMessage) {
        serviceScope.launch {
            // In a real implementation, we would extract what needs syncing from message.content
            // For now, let's sync all messages as an example of hardware backup
            val allMessages = database.messageDao().getChatHistorySync() // Use sync variant for simplicity in sample
            val jsonArray = JSONArray()
            allMessages.forEach { msg ->
                val json = org.json.JSONObject().apply {
                    put("id", msg.id)
                    put("senderId", msg.senderId)
                    put("content", msg.content)
                    put("timestamp", msg.timestamp)
                }
                jsonArray.put(json)
            }
            
            val response = MeshMessage(
                senderId = myDeviceId,
                senderName = myNickname,
                destinationId = message.senderId,
                content = jsonArray.toString(),
                type = MessageType.DATA_SYNC_RESPONSE
            )
            meshRouter.sendMessage(response.destinationId, response.content)
        }
    }

    private fun startRetryLoop() {
        serviceScope.launch {
            while (isServiceRunning) {
                // Eco-mode uses a much slower retry loop to save battery
                delay(if (isLowPowerMode) 120000 else 30000)
                val pending = database.messageDao().getPendingMessages(5)
                pending.forEach { msg ->
                    database.messageDao().incrementDeliveryAttempts(msg.id)
                    meshRouter.sendMessage(msg.destinationId, msg.content, msg.id, msg.topic)
                }
            }
        }
    }

    private fun startAutoDeleteTask() {
        serviceScope.launch {
            while (isServiceRunning) {
                val ninetyDaysInMillis = 90L * 24 * 60 * 60 * 1000
                val threshold = System.currentTimeMillis() - ninetyDaysInMillis
                database.messageDao().deleteMessagesOlderThan(threshold)
                
                // Run this cleanup daily
                delay(24 * 60 * 60 * 1000)
            }
        }
    }

    fun refreshPowerMode() {
        val ecoMode = userPreferences.isEcoMode()
        if (ecoMode) {
            // Eco-mode overrides everything to LOW POWER
            setLowPowerMode(true)
        } else {
            // Standard behavior: HIGH POWER when UI is visible, LOW POWER otherwise
            setLowPowerMode(boundClientCount <= 0)
        }
    }

    private fun setLowPowerMode(enabled: Boolean) {
        if (isLowPowerMode == enabled) return
        isLowPowerMode = enabled
        Log.d(TAG, "Switching mesh to ${if (enabled) "LOW POWER" else "HIGH POWER"} mode")
        
        updateForegroundNotification()
        
        nearbyConnectionsManager.stopAdvertising()
        nearbyConnectionsManager.stopDiscovery()
        
        startMesh()
    }

    private fun startMesh() {
        nearbyConnectionsManager.startAdvertising(myNickname, lowPower = isLowPowerMode)
        nearbyConnectionsManager.startDiscovery(lowPower = isLowPowerMode)
    }

    private fun startForegroundService() {
        val channelId = "WaveMeshServiceChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Wave Mesh Network Service",
                NotificationManager.IMPORTANCE_DEFAULT 
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = createNotification()
        startForeground(1, notification)
    }

    private fun updateForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, createNotification())
    }

    private fun createNotification(): Notification {
        val channelId = "WaveMeshServiceChannel"
        val contentText = if (isLowPowerMode) {
            "Wave Mesh: Battery Saver Active"
        } else {
            "Wave Mesh: High Performance Mode"
        }

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val quitIntent = Intent(this, WaveMeshService::class.java).apply {
            action = ACTION_QUIT
        }
        val quitPendingIntent = PendingIntent.getService(
            this, 0, quitIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Wave Mesh Network")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Quit", quitPendingIntent)
            .build()
    }

    fun addServiceListener(listener: ServiceListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            listener.onPeersChanged(meshRouter.getKnownPeers())
            listener.onDiscoveredTopicsChanged(meshRouter.getKnownTopics())
        }
    }

    fun removeServiceListener(listener: ServiceListener) {
        listeners.remove(listener)
    }

    fun broadcastUserGroups() {
        val userGroups = userPreferences.getUserGroups()
        if (userGroups.isNotEmpty()) {
            meshRouter.broadcastUserGroups(userGroups)
        }
    }

    fun disconnectFromPeer(deviceId: String) {
        val endpointId = meshRouter.getEndpointIdForDevice(deviceId)
        if (endpointId != null) {
            nearbyConnectionsManager.disconnectFromEndpoint(endpointId)
        }
    }

    fun getDeviceIdForEndpoint(endpointId: String): String? {
        return meshRouter.getKnownPeers().find { meshRouter.getEndpointIdForDevice(it.id) == endpointId }?.id
    }

    fun leaveGroup(groupId: String) {
        if (groupId.startsWith("group_")) {
            val topic = groupId.substringAfter("group_")
            val currentGroups = userPreferences.getUserGroups().toMutableSet()
            if (currentGroups.remove(topic)) {
                userPreferences.saveUserGroups(currentGroups)
                broadcastUserGroups()
                serviceScope.launch(Dispatchers.Main) {
                    listeners.forEach { it.onDiscoveredTopicsChanged(userPreferences.getUserGroupsWithExpiry()) }
                }
            }
        }
    }

    fun updateNickname(newName: String) {
        myNickname = newName
        meshRouter.updateMyName(newName)
        nearbyConnectionsManager.stopAdvertising()
        nearbyConnectionsManager.startAdvertising(myNickname, lowPower = isLowPowerMode)
        meshRouter.broadcastPublicKey()
        broadcastAvatarUpdate()
    }

    fun broadcastAvatarUpdate() {
        serviceScope.launch {
            val avatarUri = userPreferences.getAvatarUri()
            if (avatarUri != null) {
                try {
                    val file = File(Uri.parse(avatarUri).path ?: return@launch)
                    if (file.exists()) {
                        val connectedEndpoints = nearbyConnectionsManager.getConnectedEndpoints()
                        if (connectedEndpoints.isNotEmpty()) {
                            fileTransferManager.sendExistingFile(
                                connectedEndpoints, 
                                file, 
                                "AVATAR_${myDeviceId}.jpg", 
                                MeshMessage.BROADCAST_ID, 
                                myDeviceId
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to broadcast avatar update", e)
                }
            }
        }
    }

    fun sendAvatarTo(targetDeviceId: String) {
        serviceScope.launch {
            val avatarUri = userPreferences.getAvatarUri() ?: return@launch
            try {
                val file = File(Uri.parse(avatarUri).path ?: return@launch)
                if (file.exists()) {
                    val endpointId = meshRouter.getEndpointIdForDevice(targetDeviceId)
                    if (endpointId != null) {
                        fileTransferManager.sendExistingFile(
                            listOf(endpointId),
                            file,
                            "AVATAR_${myDeviceId}.jpg",
                            targetDeviceId,
                            myDeviceId
                        )
                    } else {
                        // Fallback to neighbors if next hop is unknown
                        broadcastAvatarUpdate()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send avatar to $targetDeviceId", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        nearbyConnectionsManager.stopAllEndpoints()
        serviceJob.cancel()
        Log.d(TAG, "WaveMeshService Destroyed")
    }

    override fun onEndpointDiscovered(endpointId: String, info: DiscoveredEndpointInfo) {
        if (nearbyConnectionsManager.isConnectingOrConnected(endpointId)) {
            return
        }

        val myName = myNickname
        val remoteName = info.endpointName

        if (myName < remoteName) {
            nearbyConnectionsManager.requestConnection(endpointId, myName)
        } else if (myName > remoteName) {
            // Waiting
        } else {
            serviceScope.launch {
                delay(Random.nextLong(500, 2000))
                if (!nearbyConnectionsManager.isConnectingOrConnected(endpointId)) {
                    nearbyConnectionsManager.requestConnection(endpointId, myName)
                }
            }
        }
    }

    override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
        serviceScope.launch(Dispatchers.Main) {
            listeners.forEach { it.onConnectionStateChanged(endpointId, ConnectionState.CONNECTING, info.endpointName) }
        }
    }

    override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
        val name = nearbyConnectionsManager.getEndpointName(endpointId)
        if (result.status.isSuccess) {
            notificationHelper.showConnectionNotification("Connected", "Joined mesh network")

            serviceScope.launch(Dispatchers.Main) {
                listeners.forEach { it.onConnectionStateChanged(endpointId, ConnectionState.CONNECTED, name) }
            }

            serviceScope.launch {
                delay(1000)
                meshRouter.broadcastPublicKey()
                broadcastUserGroups()
                broadcastAvatarUpdate()
            }
        } else {
            serviceScope.launch(Dispatchers.Main) {
                listeners.forEach { it.onConnectionStateChanged(endpointId, ConnectionState.FAILED, name) }
            }
        }
    }

    override fun onDisconnected(endpointId: String) {
        val name = nearbyConnectionsManager.getEndpointName(endpointId)
        meshRouter.onPeerDisconnected(endpointId)
        serviceScope.launch(Dispatchers.Main) {
            listeners.forEach { it.onConnectionStateChanged(endpointId, ConnectionState.LOST, name) }
        }
    }

    override fun onPayloadReceived(endpointId: String, payload: Payload) {
        Log.d(TAG, "onPayloadReceived: ${payload.id}, type=${payload.type}")
        fileTransferManager.onPayloadReceived(endpointId, payload)
        meshRouter.onPayloadReceived(endpointId, payload)
    }

    override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
        fileTransferManager.onPayloadTransferUpdate(update)
    }
}
