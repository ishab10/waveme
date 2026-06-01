package com.waveme.mesh

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

class NearbyConnectionsManager(private val context: Context, private val listener: NearbyConnectionsListener) {

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val serviceId = "com.waveme.mesh.SERVICE_ID"
    
    /**
     * P2P_STAR is the fastest strategy for high-bandwidth data transfer.
     * It allows the system to use Wi-Fi Direct and Wi-Fi LAN more aggressively.
     */
    private val strategy = Strategy.P2P_STAR

    private val connectedEndpoints = ConcurrentHashMap<String, String>()
    private val pendingConnections = ConcurrentHashMap<String, String>()

    interface NearbyConnectionsListener {
        fun onEndpointDiscovered(endpointId: String, info: DiscoveredEndpointInfo)
        fun onConnectionInitiated(endpointId: String, info: ConnectionInfo)
        fun onConnectionResult(endpointId: String, result: ConnectionResolution)
        fun onDisconnected(endpointId: String)
        fun onPayloadReceived(endpointId: String, payload: Payload)
        fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate)
    }

    private val endpointDiscoveryCallback = object : com.google.android.gms.nearby.connection.EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "onEndpointFound: ${info.endpointName}")
            listener.onEndpointDiscovered(endpointId, info)
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "onEndpointLost: $endpointId")
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d(TAG, "onConnectionInitiated: ${info.endpointName}")
            pendingConnections[endpointId] = info.endpointName
            listener.onConnectionInitiated(endpointId, info)
            
            // Accept the connection. SDK handles bandwidth upgrades.
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            Log.d(TAG, "onConnectionResult: ${result.status}")
            listener.onConnectionResult(endpointId, result)
            
            if (result.status.isSuccess) {
                val name = pendingConnections.remove(endpointId) ?: "Unknown"
                connectedEndpoints[endpointId] = name
            } else {
                pendingConnections.remove(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "onDisconnected: $endpointId")
            listener.onDisconnected(endpointId)
            connectedEndpoints.remove(endpointId)
            pendingConnections.remove(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            listener.onPayloadReceived(endpointId, payload)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            listener.onPayloadTransferUpdate(endpointId, update)
        }
    }

    fun startAdvertising(userNickname: String, lowPower: Boolean = false) {
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(strategy)
            .setLowPower(lowPower)
            .build()
            
        connectionsClient.startAdvertising(
            userNickname, serviceId, connectionLifecycleCallback, advertisingOptions
        )
            .addOnSuccessListener { Log.d(TAG, "Advertising started (Strategy: P2P_STAR)") }
            .addOnFailureListener { e -> Log.e(TAG, "Advertising failed", e) }
    }

    fun stopAdvertising() {
        connectionsClient.stopAdvertising()
    }

    fun startDiscovery(lowPower: Boolean = false) {
        val discoveryOptions = com.google.android.gms.nearby.connection.DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .setLowPower(lowPower)
            .build()
            
        connectionsClient.startDiscovery(
            serviceId, endpointDiscoveryCallback, discoveryOptions
        )
            .addOnSuccessListener { Log.d(TAG, "Discovery started (Strategy: P2P_STAR)") }
            .addOnFailureListener { e -> Log.e(TAG, "Discovery failed", e) }
    }

    fun stopDiscovery() {
        connectionsClient.stopDiscovery()
    }

    fun requestConnection(endpointId: String, userNickname: String) {
        if (!pendingConnections.containsKey(endpointId) && !connectedEndpoints.containsKey(endpointId)) {
            pendingConnections[endpointId] = "Unknown (Requesting)"
            connectionsClient.requestConnection(
                userNickname, endpointId, connectionLifecycleCallback
            )
                .addOnSuccessListener { Log.d(TAG, "Connection requested to $endpointId") }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Connection request failed", e)
                    pendingConnections.remove(endpointId)
                }
        }
    }

    fun disconnectFromEndpoint(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
    }
    
    fun sendPayload(endpointId: String, payload: Payload) {
        connectionsClient.sendPayload(endpointId, payload)
    }

    fun sendPayload(endpointIds: List<String>, payload: Payload) {
        if (endpointIds.isNotEmpty()) {
            connectionsClient.sendPayload(endpointIds, payload)
        }
    }

    fun sendPayloadToAll(payload: Payload) {
        if (connectedEndpoints.isNotEmpty()) {
            connectionsClient.sendPayload(connectedEndpoints.keys.toList(), payload)
        }
    }

    fun sendPayloadToAllBut(payload: Payload, excludedEndpointId: String?) {
        val targets = connectedEndpoints.keys.filter { it != excludedEndpointId }
        if (targets.isNotEmpty()) {
            connectionsClient.sendPayload(targets, payload)
        }
    }
    
    fun addConnectedEndpoint(endpointId: String, endpointName: String) {
        connectedEndpoints[endpointId] = endpointName
    }

    fun getConnectedEndpoints(): List<String> {
        return connectedEndpoints.keys.toList()
    }

    fun getEndpointName(endpointId: String): String {
        return connectedEndpoints[endpointId] ?: pendingConnections[endpointId] ?: "Unknown"
    }
    
    fun isConnectingOrConnected(endpointId: String): Boolean {
        return connectedEndpoints.containsKey(endpointId) || pendingConnections.containsKey(endpointId)
    }

    fun stopAllEndpoints() {
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        pendingConnections.clear()
    }
    
    fun getFileDescriptor(uri: Uri): ParcelFileDescriptor? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: FileNotFoundException) {
            Log.e("NearbyConnections", "File not found for URI: $uri", e)
            null
        }
    }

    fun getContext(): Context {
        return context
    }
    
    companion object {
        private const val TAG = "NearbyConnections"
    }
}
