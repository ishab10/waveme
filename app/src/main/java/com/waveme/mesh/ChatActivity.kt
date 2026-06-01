package com.waveme.mesh

import android.R
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.waveme.mesh.data.MessageEntity
import com.waveme.mesh.data.UserPreferences
import com.waveme.mesh.databinding.ActivityChatBinding
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class ChatActivity : AppCompatActivity(), WaveMeshService.ServiceListener {

    private val viewModel: ChatViewModel by viewModels()

    @Inject
    lateinit var userPreferences: UserPreferences

    private var isBound = false
    private lateinit var binding: ActivityChatBinding

    private lateinit var recipientId: String
    private lateinit var recipientName: String
    private lateinit var messageAdapter: MessageAdapter

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WaveMeshService.LocalBinder
            val serviceInstance = binder.getService()
            viewModel.waveMeshService = serviceInstance
            isBound = true

            serviceInstance.addServiceListener(this@ChatActivity)

            val myId = serviceInstance.myDeviceId
            messageAdapter.setMyDeviceId(myId)

            // Initialize peer info for adapter
            messageAdapter.updatePeers(serviceInstance.meshRouter.getKnownPeers())

            observeMessages(myId)
            viewModel.checkInitialConnection(recipientId)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            viewModel.waveMeshService = null
            isBound = false
        }
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            var fileName = "file"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex)
                }
            }
            val mimeType = contentResolver.getType(uri) ?: "*/*"
            viewModel.sendFile(recipientId, it, fileName, mimeType)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recipientId = intent.getStringExtra("RECIPIENT_ID") ?: MeshMessage.BROADCAST_ID
        recipientName = intent.getStringExtra("RECIPIENT_NAME") ?: "Mesh Group Chat"

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        binding.chatTitle.text = recipientName
        updateToolbarInfo(null) // Set defaults

        messageAdapter = MessageAdapter(this, "", mutableListOf(), onLongClick = { message ->
            if (message.senderId == viewModel.waveMeshService?.myDeviceId) {
                showDeleteDialog(message)
            }
        })
        binding.messageListView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.messageListView.adapter = messageAdapter

        binding.sendBtn.setOnClickListener {
            val text = binding.messageInput.text.toString()
            if (text.isNotEmpty() && isBound) {
                viewModel.sendMessage(recipientId, text)
                binding.messageInput.text.clear()
            }
        }

        binding.attachBtn.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        observeViewModel()
    }

    private fun updateToolbarInfo(peer: MeshRouter.Peer?) {
        if (recipientId == MeshMessage.BROADCAST_ID) {
            binding.chatTitle.text = "Mesh Group Chat"
            binding.chatSubtitle.text = "Public Channel"
            binding.chatAvatar.setImageResource(R.drawable.ic_menu_share)
            binding.onlineDot.visibility = View.VISIBLE
            return
        }

        if (recipientId.startsWith("group_")) {
            binding.chatTitle.text = recipientId.removePrefix("group_")
            binding.chatSubtitle.text = "Interest Group"
            binding.chatAvatar.setImageResource(R.drawable.ic_menu_myplaces)
            binding.onlineDot.visibility = View.VISIBLE
            return
        }

        // Individual Peer
        if (peer != null) {
            binding.chatTitle.text = peer.name
            if (peer.avatarPath != null) {
                binding.chatAvatar.load(File(peer.avatarPath)) {
                    transformations(CircleCropTransformation())
                    crossfade(true)
                }
                binding.chatAvatar.setPadding(0, 0, 0, 0)
            } else {
                binding.chatAvatar.setImageResource(R.drawable.ic_menu_view)
                binding.chatAvatar.setPadding(10, 10, 10, 10)
            }
        } else {
            binding.chatTitle.text = recipientName
            binding.chatAvatar.setImageResource(R.drawable.ic_menu_view)
        }
    }

    private fun showDeleteDialog(message: MessageEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Message")
            .setMessage("Do you want to delete this message for everyone?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteMessage(message.id, recipientId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeViewModel() {
        viewModel.chatTitle.observe(this, Observer { title ->
            binding.chatTitle.text = title
        })

        viewModel.recipientPeer.observe(this, Observer { peer ->
            updateToolbarInfo(peer)
        })

        viewModel.progressBarVisibility.observe(this, Observer { isVisible ->
            binding.progressBar.visibility = if (isVisible) View.VISIBLE else View.GONE
        })

        viewModel.progressBarProgress.observe(this, Observer { progress ->
            binding.progressBar.isIndeterminate = false
            binding.progressBar.progress = progress
        })

        viewModel.connectionStatusToast.observe(this, Observer { message ->
            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
        })

        viewModel.finishActivity.observe(this, Observer { shouldFinish ->
            if (shouldFinish) {
                finish()
            }
        })

        viewModel.fileError.observe(this, Observer { error ->
            Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG).show()
        })

        viewModel.peerStatus.observe(this, Observer { status ->
            when (status) {
                ChatViewModel.PeerStatus.ONLINE -> {
                    binding.offlineBanner.visibility = View.GONE
                    binding.onlineDot.setBackgroundResource(com.waveme.mesh.R.drawable.bg_online_dot)
                    binding.chatSubtitle.text = "Online"
                }

                ChatViewModel.PeerStatus.RECONNECTING -> {
                    binding.offlineBanner.visibility = View.VISIBLE
                    binding.offlineBanner.text = "Reconnecting to peer..."
                    binding.offlineBanner.setBackgroundColor(
                        ContextCompat.getColor(
                            this,
                            android.R.color.holo_blue_light
                        )
                    )
                    binding.onlineDot.setBackgroundResource(R.drawable.presence_away)
                    binding.chatSubtitle.text = "Connecting..."
                }

                ChatViewModel.PeerStatus.OFFLINE -> {
                    binding.offlineBanner.visibility = View.VISIBLE
                    binding.offlineBanner.text =
                        "Peer is offline. Messages will be sent once connected."
                    binding.offlineBanner.setBackgroundColor(
                        ContextCompat.getColor(
                            this,
                            android.R.color.holo_orange_light
                        )
                    )
                    binding.onlineDot.setBackgroundResource(R.drawable.presence_offline)
                    binding.chatSubtitle.text = "Offline"
                }
            }
        })
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        if (recipientId == MeshMessage.BROADCAST_ID) return true

        if (recipientId.startsWith("group_")) {
            val topic = recipientId.removePrefix("group_")
            if (UserPreferences.DEFAULT_CHANNELS.contains(topic)) {
                return true
            }
            menuInflater.inflate(com.waveme.mesh.R.menu.chat_menu, menu)
            menu?.findItem(com.waveme.mesh.R.id.action_disconnect)?.title = "Leave Group"
        } else {
            menuInflater.inflate(com.waveme.mesh.R.menu.chat_menu, menu)
            // Add Block option for private chat
            menu?.add(0, 999, 0, "Block User")
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            com.waveme.mesh.R.id.action_disconnect -> {
                if (recipientId.startsWith("group_")) {
                    viewModel.leaveGroup(recipientId)
                } else {
                    viewModel.waveMeshService?.disconnectFromPeer(recipientId)
                }
                true
            }
            999 -> { // Block User ID
                userPreferences.blockUser(recipientId)
                viewModel.waveMeshService?.disconnectFromPeer(recipientId)
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun observeMessages(myId: String) {
        viewModel.getMessages(myId, recipientId).observe(this, Observer { messages ->
            messageAdapter.setMessages(messages)

            // Toggle empty state
            binding.emptyStateLayout.visibility =
                if (messages.isEmpty()) View.VISIBLE else View.GONE

            if (messages.isNotEmpty()) {
                binding.messageListView.scrollToPosition(messages.size - 1)

                // Mark unseen incoming messages as seen
                messages.filter { it.senderId != myId && !it.isSeen }.forEach {
                    viewModel.markAsSeen(it.id, it.senderId)
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, WaveMeshService::class.java)
        try {
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Snackbar.make(binding.root, "Failed to connect to the service", Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            viewModel.waveMeshService?.removeServiceListener(this)
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onPeersChanged(peers: List<MeshRouter.Peer>) {
        viewModel.checkInitialConnection(recipientId)
        messageAdapter.updatePeers(peers)
    }

    override fun onDiscoveredTopicsChanged(topics: Map<String, Long>) {}

    override fun onMessageReceived(message: MessageEntity) {}

    override fun onMessageAcked(messageId: String) {}

    override fun onMessageSeen(messageId: String) {}

    override fun onFileProgress(payloadId: Long, fileName: String, progress: Int, isIncoming: Boolean) {
        viewModel.onFileProgress(progress, recipientId, recipientName)
    }

    override fun onFileCompleted(fileName: String, senderId: String) {
        viewModel.onFileCompleted(recipientId, recipientName)
    }

    override fun onConnectionStateChanged(endpointId: String, state: WaveMeshService.ConnectionState, endpointName: String) {
        viewModel.onConnectionStateChanged(endpointId, state, endpointName, recipientId, recipientName)
    }
}
