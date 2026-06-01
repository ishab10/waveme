package com.waveme.mesh

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import java.io.File
import java.util.Locale

class PeerAdapter(private val onClick: (MeshRouter.Peer) -> Unit) :
    ListAdapter<MeshRouter.Peer, PeerAdapter.PeerViewHolder>(PeerDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_peer, parent, false)
        return PeerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
        holder.bind(getItem(position), onClick)
    }

    class PeerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.peerName)
        private val statusTextView: TextView = itemView.findViewById(R.id.peerStatusText)
        private val avatarIcon: ImageView = itemView.findViewById(R.id.peerAvatar)
        private val timerTextView: TextView = itemView.findViewById(R.id.peerTimer)

        fun bind(peer: MeshRouter.Peer, onClick: (MeshRouter.Peer) -> Unit) {
            nameTextView.text = peer.name
            
            // Handle timer visibility and calculation
            if (peer.expiryTime != null && peer.id.startsWith("group_")) {
                val remainingMs = peer.expiryTime - System.currentTimeMillis()
                if (remainingMs > 0) {
                    val minutes = (remainingMs / 1000) / 60
                    val seconds = (remainingMs / 1000) % 60
                    timerTextView.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                    timerTextView.visibility = View.VISIBLE
                } else {
                    timerTextView.visibility = View.GONE
                }
            } else {
                timerTextView.visibility = View.GONE
            }
            
            // Set Avatar
            if (peer.avatarPath != null) {
                avatarIcon.load(File(peer.avatarPath)) {
                    crossfade(true)
                    transformations(CircleCropTransformation())
                    error(R.drawable.circle_bg) // Fallback to circle background
                }
                avatarIcon.setPadding(0, 0, 0, 0)
            } else {
                // Default icons based on type
                avatarIcon.setPadding(12, 12, 12, 12)
                when {
                    peer.id == MeshMessage.BROADCAST_ID -> {
                        statusTextView.text = "Public Channel"
                        avatarIcon.setImageResource(android.R.drawable.ic_menu_slideshow)
                    }
                    peer.id.startsWith("group_") -> {
                        statusTextView.text = "Interest Group"
                        avatarIcon.setImageResource(android.R.drawable.ic_menu_myplaces)
                    }
                    else -> {
                        statusTextView.text = "Nearby Peer"
                        avatarIcon.setImageResource(android.R.drawable.ic_menu_view)
                    }
                }
            }

            itemView.setOnClickListener { onClick(peer) }
        }
    }

    object PeerDiffCallback : DiffUtil.ItemCallback<MeshRouter.Peer>() {
        override fun areItemsTheSame(oldItem: MeshRouter.Peer, newItem: MeshRouter.Peer): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: MeshRouter.Peer, newItem: MeshRouter.Peer): Boolean {
            return oldItem == newItem && 
                   oldItem.expiryTime == newItem.expiryTime && 
                   oldItem.avatarPath == newItem.avatarPath
        }
    }
}
