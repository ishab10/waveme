package com.waveme.mesh

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waveme.mesh.data.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val userPreferences: UserPreferences
) : ViewModel() {

    var waveMeshService: WaveMeshService? = null

    private val _peers = MutableLiveData<List<MeshRouter.Peer>>()
    val peers: LiveData<List<MeshRouter.Peer>> = _peers

    private val _connectionStatus = MutableLiveData<String>()
    val connectionStatus: LiveData<String> = _connectionStatus

    private val _fileProgress = MutableLiveData<Int?>()
    val fileProgress: LiveData<Int?> = _fileProgress

    private var meshTopics: Map<String, Long> = emptyMap()
    private var knownPeers: List<MeshRouter.Peer> = emptyList()

    private val defaultChannels = listOf("Music", "Sports", "Tech")

    init {
        refreshList()
        startTimerUpdateLoop()
    }

    private fun startTimerUpdateLoop() {
        viewModelScope.launch {
            while (true) {
                delay(1000)
                refreshList()
            }
        }
    }

    fun onPeersChanged(newPeers: List<MeshRouter.Peer>) {
        knownPeers = newPeers
        refreshList()
    }

    fun onTopicsChanged(topics: Map<String, Long>) {
        meshTopics = topics
        refreshList()
    }

    fun refreshList() {
        val combinedList = mutableListOf<MeshRouter.Peer>()
        
        combinedList.add(MeshRouter.Peer(MeshMessage.BROADCAST_ID, "Mesh Group Chat"))

        defaultChannels.forEach { channel ->
            combinedList.add(MeshRouter.Peer("group_$channel", channel))
        }
        
        // Merge mesh topics (from others) with local groups (created by user)
        val localGroups = userPreferences.getUserGroupsWithExpiry()
        val allTopics = meshTopics + localGroups
        
        allTopics.forEach { (topic, expiry) ->
            if (!defaultChannels.contains(topic)) {
                combinedList.add(MeshRouter.Peer("group_$topic", topic, expiry))
            }
        }
        
        combinedList.addAll(knownPeers)
        
        _peers.postValue(combinedList)
        updateStatus()
    }

    fun onConnectionStateChanged(state: WaveMeshService.ConnectionState, endpointName: String) {
        val statusText = when(state) {
            WaveMeshService.ConnectionState.CONNECTING -> "Connecting to $endpointName..."
            WaveMeshService.ConnectionState.CONNECTED -> "Connected to $endpointName"
            WaveMeshService.ConnectionState.FAILED -> "Failed to connect to $endpointName"
            WaveMeshService.ConnectionState.LOST -> "Disconnected from $endpointName"
        }

        if (state == WaveMeshService.ConnectionState.CONNECTED || state == WaveMeshService.ConnectionState.LOST) {
            updateStatus()
        } else {
            _connectionStatus.postValue("Status: $statusText")
        }
    }

    fun updateStatus() {
        waveMeshService?.let {
            _connectionStatus.postValue("Status: Online as ${it.myNickname} (${it.nearbyConnectionsManager.getConnectedEndpoints().size} peers)")
        }
    }

    fun onFileProgress(progress: Int) {
        _fileProgress.postValue(progress)
    }

    fun onFileCompleted() {
        _fileProgress.postValue(null)
    }
}
