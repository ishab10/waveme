package com.waveme.mesh

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FileTransferManager(
    private val context: Context,
    private val connectionsManager: NearbyConnectionsManager,
    private val encryptionManager: EncryptionManager
) {

    interface FileTransferListener {
        fun onFileCompleted(file: File, fileName: String, senderId: String, destinationId: String, topic: String?)
        fun onFileProgress(payloadId: Long, fileName: String, progress: Int, isIncoming: Boolean)
        fun onTransferFailed(payloadId: Long, isIncoming: Boolean)
    }

    private var listener: FileTransferListener? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Thread-safe maps
    private val incomingFiles = ConcurrentHashMap<Long, IncomingFile>()
    private val incomingPayloads = ConcurrentHashMap<Long, Payload>()
    private val completedTransfers = ConcurrentHashMap.newKeySet<Long>()
    private val outgoingFiles = ConcurrentHashMap<Long, String>()

    // Larger buffer for I/O operations
    private val IO_BUFFER_SIZE = 1024 * 1024 // 1MB buffer

    data class IncomingFile(
        val payloadId: Long,
        val fileName: String,
        val senderId: String,
        val destinationId: String,
        val totalSize: Long,
        var receivedSize: Long = 0,
        val destinationFile: File,
        val encryptionInfo: String? = null,
        val topic: String? = null
    )

    fun setListener(listener: FileTransferListener?) {
        this.listener = listener
    }

    private fun fastCopyTo(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(IO_BUFFER_SIZE)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
        }
        output.flush()
    }

    suspend fun sendFile(
        endpointIds: List<String>,
        uri: Uri,
        destinationId: String,
        mySenderId: String,
        recipientPublicKey: String? = null,
        topic: String? = null
    ): Long? {
        if (endpointIds.isEmpty()) return null

        return withContext(Dispatchers.IO) {
            try {
                var fileName = "unknown.jpg"
                var fileSize = 0L

                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex >= 0) fileName = cursor.getString(nameIndex)
                        if (sizeIndex >= 0) fileSize = cursor.getLong(sizeIndex)
                    }
                }

                val safeFileName = fileName.replace(":", "_")

                // OPTIMIZATION: If NOT encrypting, use zero-copy file descriptor payload for maximum speed
                if (recipientPublicKey == null) {
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    if (pfd != null) {
                        val filePayload = Payload.fromFile(pfd)
                        val payloadId = filePayload.id

                        val metadataJson = JSONObject().apply {
                            put("type", "FILE_METADATA")
                            put("payloadId", payloadId)
                            put("destinationId", destinationId)
                            put("senderId", mySenderId)
                            put("fileName", safeFileName)
                            put("fileSize", fileSize)
                            if (topic != null) put("topic", topic)
                        }

                        val metadataPayload =
                            Payload.fromBytes("JSON_METADATA:${metadataJson}".toByteArray())
                        connectionsManager.sendPayload(endpointIds, metadataPayload)
                        connectionsManager.sendPayload(endpointIds, filePayload)

                        outgoingFiles[payloadId] = safeFileName
                        return@withContext payloadId
                    }
                }

                // If encryption is needed or PFD failed, fallback to cache + encrypted path
                val cacheDir = File(context.cacheDir, "outgoing_files")
                if (!cacheDir.exists()) cacheDir.mkdirs()
                val uniqueName = "${UUID.randomUUID()}_$safeFileName"
                var finalFile = File(cacheDir, uniqueName)

                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(finalFile).use { output ->
                        fastCopyTo(input, output)
                    }
                }

                var encryptionInfo: String? = null
                if (recipientPublicKey != null) {
                    val encryptedFile = File(cacheDir, "enc_${uniqueName}")
                    encryptionInfo =
                        encryptionManager.encryptFile(finalFile, encryptedFile, recipientPublicKey)
                    if (encryptionInfo != null) {
                        finalFile.delete()
                        finalFile = encryptedFile
                    }
                }

                return@withContext sendExistingFile(
                    endpointIds,
                    finalFile,
                    safeFileName,
                    destinationId,
                    mySenderId,
                    encryptionInfo,
                    topic
                )
            } catch (e: Exception) {
                Log.e("FileTransfer", "Failed to send file", e)
                null
            }
        }
    }

    suspend fun sendExistingFile(
        endpointIds: List<String>,
        file: File,
        fileName: String,
        destinationId: String,
        senderId: String,
        encryptionInfo: String? = null,
        topic: String? = null
    ): Long? {
        if (endpointIds.isEmpty() || !file.exists()) return null

        return withContext(Dispatchers.IO) {
            try {
                val filePayload = Payload.fromFile(file)
                val payloadId = filePayload.id

                val metadataJson = JSONObject().apply {
                    put("type", "FILE_METADATA")
                    put("payloadId", payloadId)
                    put("destinationId", destinationId)
                    put("senderId", senderId)
                    put("fileName", fileName)
                    put("fileSize", file.length())
                    if (topic != null) put("topic", topic)
                    if (encryptionInfo != null) {
                        put("encryptionInfo", encryptionInfo)
                    }
                }

                val metadataPayload =
                    Payload.fromBytes("JSON_METADATA:${metadataJson}".toByteArray())

                connectionsManager.sendPayload(endpointIds, metadataPayload)
                connectionsManager.sendPayload(endpointIds, filePayload)

                outgoingFiles[payloadId] = fileName
                payloadId
            } catch (e: Exception) {
                Log.e("FileTransfer", "Failed to send existing file", e)
                null
            }
        }
    }

    fun onPayloadReceived(endpointId: String, payload: Payload) {
        when (payload.type) {
            Payload.Type.BYTES -> {
                val content = String(payload.asBytes() ?: return, Charsets.UTF_8)
                if (content.startsWith("JSON_METADATA:")) {
                    try {
                        val jsonStr = content.substring("JSON_METADATA:".length)
                        val json = JSONObject(jsonStr)
                        val payloadId = json.getLong("payloadId")
                        val destinationId = json.getString("destinationId")
                        val senderId = json.getString("senderId")
                        val fileName = json.getString("fileName")
                        val fileSize = json.getLong("fileSize")
                        val topic = if (json.has("topic")) json.getString("topic") else null
                        val encryptionInfo = if (json.has("encryptionInfo")) json.getString("encryptionInfo") else null

                        val destFile = File(
                            context.getExternalFilesDir(null),
                            "received_${System.currentTimeMillis()}_$fileName"
                        )

                        val incomingFile = IncomingFile(payloadId, fileName, senderId, destinationId, fileSize, 0, destFile, encryptionInfo, topic)
                        incomingFiles[payloadId] = incomingFile

                        if (completedTransfers.contains(payloadId)) {
                            processCompletedFile(payloadId, incomingFile)
                            completedTransfers.remove(payloadId)
                        }
                    } catch (e: Exception) {
                        Log.e("FileTransfer", "Error parsing JSON metadata", e)
                    }
                }
            }
            Payload.Type.FILE -> {
                incomingPayloads[payload.id] = payload
            }
            else -> {}
        }
    }

    fun onPayloadTransferUpdate(update: PayloadTransferUpdate) {
        val payloadId = update.payloadId
        if (outgoingFiles.containsKey(payloadId)) {
            handleOutgoingUpdate(update)
        } else {
            handleIncomingUpdate(update)
        }
    }

    private fun handleOutgoingUpdate(update: PayloadTransferUpdate) {
        val payloadId = update.payloadId
        val fileName = outgoingFiles[payloadId] ?: "Unknown"
        when (update.status) {
            PayloadTransferUpdate.Status.IN_PROGRESS -> {
                val total = if (update.totalBytes > 0) update.totalBytes else 1
                val progress = ((update.bytesTransferred * 100) / total).toInt()
                listener?.onFileProgress(payloadId, fileName, progress, false)
            }
            PayloadTransferUpdate.Status.SUCCESS -> {
                listener?.onFileProgress(payloadId, fileName, 100, false)
                outgoingFiles.remove(payloadId)
            }
            PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                listener?.onTransferFailed(payloadId, false)
                outgoingFiles.remove(payloadId)
            }
        }
    }

    private fun handleIncomingUpdate(update: PayloadTransferUpdate) {
        val payloadId = update.payloadId
        val fileName = incomingFiles[payloadId]?.fileName ?: "Unknown"
        when (update.status) {
            PayloadTransferUpdate.Status.IN_PROGRESS -> {
                incomingFiles[payloadId]?.let {
                    it.receivedSize = update.bytesTransferred
                    val progress = if (it.totalSize > 0) ((it.receivedSize * 100) / it.totalSize).toInt() else 0
                    listener?.onFileProgress(payloadId, fileName, progress, true)
                }
            }
            PayloadTransferUpdate.Status.SUCCESS -> {
                listener?.onFileProgress(payloadId, fileName, 100, true)
                val incomingFile = incomingFiles[payloadId]
                if (incomingFile != null) {
                    processCompletedFile(payloadId, incomingFile)
                } else {
                    completedTransfers.add(payloadId)
                }
            }
            PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                listener?.onTransferFailed(payloadId, true)
                incomingPayloads.remove(payloadId)?.close()
                incomingFiles.remove(payloadId)
                completedTransfers.remove(payloadId)
            }
        }
    }

    private fun processCompletedFile(payloadId: Long, incomingFile: IncomingFile) {
        scope.launch {
            incomingPayloads[payloadId]?.let { payload ->
                try {
                    val pfd = payload.asFile()?.asParcelFileDescriptor() ?: return@launch
                    val inputStream = ParcelFileDescriptor.AutoCloseInputStream(pfd)

                    withContext(Dispatchers.IO) {
                        if (incomingFile.encryptionInfo != null) {
                            val tempReceivedFile = File(context.cacheDir, "temp_rec_${payloadId}")
                            FileOutputStream(tempReceivedFile).use { outputStream ->
                                inputStream.use { input ->
                                    fastCopyTo(input, outputStream)
                                }
                            }

                            val decrypted = encryptionManager.decryptFile(
                                tempReceivedFile,
                                incomingFile.destinationFile,
                                incomingFile.encryptionInfo
                            )
                            tempReceivedFile.delete()

                            if (!decrypted) {
                                Log.e("FileTransfer", "Failed to decrypt received file")
                                withContext(Dispatchers.Main) {
                                    listener?.onTransferFailed(payloadId, true)
                                }
                                return@withContext
                            }
                        } else {
                            FileOutputStream(incomingFile.destinationFile).use { outputStream ->
                                inputStream.use { input ->
                                    fastCopyTo(input, outputStream)
                                }
                            }
                        }

                        Log.d(
                            "FileTransfer",
                            "File processed and saved to ${incomingFile.destinationFile.absolutePath}"
                        )
                        withContext(Dispatchers.Main) {
                            listener?.onFileCompleted(
                                incomingFile.destinationFile,
                                incomingFile.fileName,
                                incomingFile.senderId,
                                incomingFile.destinationId,
                                incomingFile.topic
                            )
                        }
                    }
                } catch (e: IOException) {
                    Log.e("FileTransfer", "Failed to process received file", e)
                    withContext(Dispatchers.Main) {
                        listener?.onTransferFailed(payloadId, true)
                    }
                } finally {
                    payload.close()
                    incomingPayloads.remove(payloadId)
                    incomingFiles.remove(payloadId)
                }
            }
        }
    }
}