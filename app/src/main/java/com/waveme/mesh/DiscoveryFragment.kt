package com.waveme.mesh

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import com.waveme.mesh.databinding.FragmentDiscoveryBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class DiscoveryFragment : Fragment() {

    private var _binding: FragmentDiscoveryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var peerAdapter: PeerAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDiscoveryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        peerAdapter = PeerAdapter { peer ->
            val intent = Intent(requireContext(), ChatActivity::class.java).apply {
                putExtra("RECIPIENT_ID", peer.id)
                putExtra("RECIPIENT_NAME", peer.name)
            }
            startActivity(intent)
        }

        binding.peersRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.peersRecyclerView.adapter = peerAdapter

        observeViewModel()
    }

    private fun observeViewModel() {
        viewModel.connectionStatus.observe(viewLifecycleOwner, Observer { status ->
            binding.connectionStatus.text = status
        })

        viewModel.peers.observe(viewLifecycleOwner, Observer { peers ->
            peerAdapter.submitList(peers)
            updateEmptyView(peers.isEmpty())
        })
    }

    private fun updateEmptyView(isEmpty: Boolean) {
        binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
