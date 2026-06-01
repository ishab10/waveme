package com.waveme.mesh

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.waveme.mesh.data.MessageEntity
import java.io.File

class ChatsAdapter(private val onClick: (String, String) -> Unit) :
    ListAdapter<Pair<String, MessageEntity>, ChatsAdapter.ChatViewHolder>(ChatDiffCallback) {

    private val knownPeers = mutableMapOf<String, MeshRouter.Peer>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val (id, lastMessage) = getItem(position)
        val peer = knownPeers[id]
        
        val displayName = when {
            id == MeshMessage.BROADCAST_ID -> "Mesh Group Chat"
            id.startsWith("group_") -> id.removePrefix("group_")
            peer != null -> peer.name
            else -> lastMessage.senderName // Fallback to DB name
        }
        
        holder.bind(id, displayName, lastMessage, peer, onClick)
    }

    fun updatePeers(peers: List<MeshRouter.Peer>) {
        knownPeers.clear()
        peers.forEach { knownPeers[it.id] = it }
        notifyDataSetChanged()
    }

    class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val chatNameTextView: TextView = itemView.findViewById(R.id.chatName)
        private val lastMessageTextView: TextView = itemView.findViewById(R.id.lastMessage)
        private val avatarImageView: ImageView = itemView.findViewById(R.id.chatAvatar)

        fun bind(id: String, displayName: String, lastMessage: MessageEntity, peer: MeshRouter.Peer?, onClick: (String, String) -> Unit) {
            chatNameTextView.text = displayName
            lastMessageTextView.text = lastMessage.content
            
            // Set Avatar
            when {
                id == MeshMessage.BROADCAST_ID -> {
                    avatarImageView.setImageResource(android.R.drawable.ic_menu_share)
                    avatarImageView.setPadding(10, 10, 10, 10)
                }
                id.startsWith("group_") -> {
                    avatarImageView.setImageResource(android.R.drawable.ic_menu_myplaces)
                    avatarImageView.setPadding(10, 10, 10, 10)
                }
                peer?.avatarPath != null -> {
                    avatarImageView.load(File(peer.avatarPath)) {
                        crossfade(true)
                        transformations(CircleCropTransformation())
                    }
                    avatarImageView.setPadding(0, 0, 0, 0)
                }
                else -> {
                    avatarImageView.setImageResource(android.R.drawable.ic_menu_view)
                    avatarImageView.setPadding(10, 10, 10, 10)
                }
            }

            itemView.setOnClickListener { onClick(id, displayName) }
        }
    }
}

object ChatDiffCallback : DiffUtil.ItemCallback<Pair<String, MessageEntity>>() {
    override fun areItemsTheSame(oldItem: Pair<String, MessageEntity>, newItem: Pair<String, MessageEntity>): Boolean {
        // Use ID for item identity
        return oldItem.first == newItem.first
    }

    override fun areContentsTheSame(oldItem: Pair<String, MessageEntity>, newItem: Pair<String, MessageEntity>): Boolean {
        return oldItem.second.id == newItem.second.id && 
               oldItem.second.content == newItem.second.content
    }
}
