package com.waveme.mesh.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncManager @Inject constructor() {
    private val client = OkHttpClient()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val syncUrl = "http://192.168.1.100:4000/sync"
    
    private val pendingSync = CopyOnWriteArrayList<JSONObject>()
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        startFlushInterval()
    }

    private fun startFlushInterval() {
        scope.launch {
            while (true) {
                delay(10000)
                flushPendingSync()
            }
        }
    }

    fun syncToNode(message: MessageEntity) {
        val json = JSONObject()
        try {
            json.put("id", message.id)
            json.put("content", message.content)
            json.put("sender", message.senderId)
            json.put("timestamp", message.timestamp)
            json.put("room", message.topic ?: message.destinationId)

            sendRequest(json)
        } catch (e: Exception) {
            Log.e("SyncManager", "Error preparing sync payload: ${e.message}")
        }
    }

    private fun sendRequest(json: JSONObject, isRetry: Boolean = false) {
        val body = json.toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url(syncUrl)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val msgId = json.optString("id", "unknown")
                Log.e("SyncManager", "Sync failed for message $msgId: ${e.message}")
                if (!isRetry) {
                    pendingSync.add(json)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val msgId = json.optString("id", "unknown")
                if (response.isSuccessful) {
                    Log.d("SyncManager", "Sync successful for message $msgId")
                } else {
                    Log.e("SyncManager", "Sync failed for message $msgId with code ${response.code}")
                    if (!isRetry) {
                        pendingSync.add(json)
                    }
                }
                response.close()
            }
        })
    }

    private fun flushPendingSync() {
        if (pendingSync.isEmpty()) return
        
        Log.d("SyncManager", "Flushing ${pendingSync.size} pending messages")
        for (json in pendingSync) {
            val body = json.toString().toRequestBody(JSON)
            val request = Request.Builder()
                .url(syncUrl)
                .post(body)
                .build()
            
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d("SyncManager", "Retry successful for message ${json.optString("id")}")
                        pendingSync.remove(json)
                    } else {
                        Log.e("SyncManager", "Retry failed for message ${json.optString("id")} with code ${response.code}")
                    }
                }
            } catch (e: IOException) {
                Log.e("SyncManager", "Retry failed for message ${json.optString("id")}: ${e.message}")
            }
        }
    }
}
