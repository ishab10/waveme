package com.waveme.mesh

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import com.waveme.mesh.data.MessageEntity
import com.waveme.mesh.databinding.FragmentChatsBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ChatsFragment : Fragment(), WaveMeshService.ServiceListener {

    private var _binding: FragmentChatsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ChatsViewModel by viewModels()
    private lateinit var chatsAdapter: ChatsAdapter
    
    private var waveMeshService: WaveMeshService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WaveMeshService.LocalBinder
            val serviceInstance = binder.getService()
            waveMeshService = serviceInstance
            isBound = true
            serviceInstance.addServiceListener(this@ChatsFragment)
            
            // Initial sync of peers for avatars
            chatsAdapter.updatePeers(serviceInstance.meshRouter.getKnownPeers())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            waveMeshService = null
            isBound = false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        chatsAdapter = ChatsAdapter { recipientId, recipientName ->
            val intent = Intent(requireContext(), ChatActivity::class.java).apply {
                putExtra("RECIPIENT_ID", recipientId)
                putExtra("RECIPIENT_NAME", recipientName)
            }
            startActivity(intent)
        }

        binding.chatsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.chatsRecyclerView.adapter = chatsAdapter

        viewModel.chatList.observe(viewLifecycleOwner, Observer { chats ->
            if (chats.isEmpty()) {
                binding.emptyView.visibility = View.VISIBLE
                binding.chatsRecyclerView.visibility = View.GONE
            } else {
                binding.emptyView.visibility = View.GONE
                binding.chatsRecyclerView.visibility = View.VISIBLE
                chatsAdapter.submitList(chats)
            }
        })
    }

    override fun onStart() {
        super.onStart()
        Intent(requireContext(), WaveMeshService::class.java).also { intent ->
            requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            waveMeshService?.removeServiceListener(this)
            requireContext().unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onPeersChanged(peers: List<MeshRouter.Peer>) {
        chatsAdapter.updatePeers(peers)
    }

    override fun onDiscoveredTopicsChanged(topics: Map<String, Long>) {}
    override fun onMessageReceived(message: MessageEntity) {}
    override fun onMessageAcked(messageId: String) {}
    override fun onMessageSeen(messageId: String) {}
    override fun onFileProgress(payloadId: Long, fileName: String, progress: Int, isIncoming: Boolean) {}
    override fun onFileCompleted(fileName: String, senderId: String) {}
    override fun onConnectionStateChanged(endpointId: String, state: WaveMeshService.ConnectionState, endpointName: String) {}

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
