package com.waveme.mesh

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.waveme.mesh.data.MessageEntity
import com.waveme.mesh.databinding.ItemGalleryMediaBinding
import java.io.File

class GalleryAdapter(private val onClick: (MessageEntity) -> Unit) :
    ListAdapter<MessageEntity, GalleryAdapter.GalleryViewHolder>(GalleryDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryViewHolder {
        val binding = ItemGalleryMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return GalleryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GalleryViewHolder, position: Int) {
        holder.bind(getItem(position), onClick)
    }

    class GalleryViewHolder(private val binding: ItemGalleryMediaBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MessageEntity, onClick: (MessageEntity) -> Unit) {
            val path = item.attachmentPath
            
            // Coil can load File, String (URI), or Uri objects directly.
            // We'll pass the path string directly if it's a URI, otherwise wrap in File.
            val model: Any? = if (path != null) {
                if (path.startsWith("content://") || path.startsWith("file://")) {
                    path
                } else {
                    File(path)
                }
            } else null

            // Extract file name from path or content
            val fileName = if (path != null) {
                val file = File(path)
                if (file.name.startsWith("received_") || file.name.startsWith("sent_")) {
                    // Try to clean up the auto-generated prefix if possible
                    file.name.substringAfter("_").substringAfter("_")
                } else {
                    file.name
                }
            } else {
                item.content
            }
            
            binding.fileNameText.text = fileName

            when (item.type) {
                "IMAGE" -> {
                    binding.galleryThumbnail.load(model) {
                        placeholder(android.R.drawable.ic_menu_gallery)
                        error(android.R.drawable.ic_menu_report_image)
                    }
                    binding.mediaTypeIcon.setImageResource(android.R.drawable.ic_menu_gallery)
                    binding.fileNameOverlay.visibility = View.GONE
                    binding.fileNameText.visibility = View.GONE
                }
                "VIDEO" -> {
                    binding.galleryThumbnail.load(model) {
                        placeholder(android.R.drawable.presence_video_online)
                    }
                    binding.mediaTypeIcon.setImageResource(android.R.drawable.presence_video_online)
                    binding.fileNameOverlay.visibility = View.VISIBLE
                    binding.fileNameText.visibility = View.VISIBLE
                }
                "PDF", "FILE" -> {
                    binding.galleryThumbnail.setImageResource(android.R.drawable.ic_menu_agenda)
                    binding.mediaTypeIcon.setImageResource(android.R.drawable.ic_menu_save)
                    binding.fileNameOverlay.visibility = View.VISIBLE
                    binding.fileNameText.visibility = View.VISIBLE
                }
                else -> {
                    binding.galleryThumbnail.setImageResource(android.R.drawable.ic_menu_send)
                    binding.mediaTypeIcon.setImageResource(android.R.drawable.ic_menu_directions)
                    binding.fileNameOverlay.visibility = View.VISIBLE
                    binding.fileNameText.visibility = View.VISIBLE
                }
            }

            binding.root.setOnClickListener { onClick(item) }
        }
    }

    object GalleryDiffCallback : DiffUtil.ItemCallback<MessageEntity>() {
        override fun areItemsTheSame(oldItem: MessageEntity, newItem: MessageEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: MessageEntity, newItem: MessageEntity): Boolean {
            return oldItem == newItem
        }
    }
}
