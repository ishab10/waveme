package com.waveme.mesh

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import coil.transform.RoundedCornersTransformation
import com.waveme.mesh.data.MessageEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter(
    private val context: Context,
    private var myDeviceId: String,
    private val messages: MutableList<MessageEntity>,
    private val onLongClick: (MessageEntity) -> Unit = {}
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val knownPeers = mutableMapOf<String, MeshRouter.Peer>()

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val senderName: TextView = view.findViewById(R.id.senderName)
        val peerAvatarOther: ImageView = view.findViewById(R.id.peerAvatarOther)
        
        // Me views
        val layoutMe: LinearLayout = view.findViewById(R.id.layoutMe)
        val contentMe: TextView = view.findViewById(R.id.messageContentMe)
        val imageMe: ImageView = view.findViewById(R.id.messageImageMe)
        val timeMe: TextView = view.findViewById(R.id.messageTimeMe)
        val status: TextView = view.findViewById(R.id.messageStatus)
        val ivSecureMe: ImageView = view.findViewById(R.id.iv_secure_me)

        // Other views
        val layoutOther: LinearLayout = view.findViewById(R.id.layoutOther)
        val containerOther: View = view.findViewById(R.id.containerOther)
        val contentOther: TextView = view.findViewById(R.id.messageContentOther)
        val imageOther: ImageView = view.findViewById(R.id.messageImageOther)
        val timeOther: TextView = view.findViewById(R.id.messageTimeOther)
        val ivSecureOther: ImageView = view.findViewById(R.id.iv_secure_other)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        val isMe = message.senderId == myDeviceId
        val isSecure = message.isEncrypted
        val timeStr = timeFormat.format(Date(message.timestamp))

        if (isMe) {
            holder.layoutMe.visibility = View.VISIBLE
            holder.containerOther.visibility = View.GONE
            holder.senderName.visibility = View.GONE
            holder.ivSecureMe.visibility = if (isSecure) View.VISIBLE else View.GONE
            holder.timeMe.text = timeStr

            setupContent(message, holder.contentMe, holder.imageMe)
            
            holder.status.text = when {
                message.isSeen -> "Seen"
                message.isDelivered -> "Delivered"
                else -> "Sent"
            }
            
            holder.layoutMe.setOnLongClickListener {
                onLongClick(message)
                true
            }
        } else {
            holder.layoutMe.visibility = View.GONE
            holder.containerOther.visibility = View.VISIBLE
            holder.ivSecureOther.visibility = if (isSecure) View.VISIBLE else View.GONE
            holder.timeOther.text = timeStr
            
            val peer = knownPeers[message.senderId]
            
            holder.senderName.visibility = View.VISIBLE
            holder.senderName.text = peer?.name ?: message.senderName

            // Load peer avatar
            if (peer?.avatarPath != null) {
                holder.peerAvatarOther.load(File(peer.avatarPath)) {
                    transformations(CircleCropTransformation())
                    crossfade(true)
                }
                holder.peerAvatarOther.setPadding(0, 0, 0, 0)
            } else {
                holder.peerAvatarOther.setImageResource(android.R.drawable.ic_menu_view)
                holder.peerAvatarOther.setPadding(6, 6, 6, 6)
            }

            setupContent(message, holder.contentOther, holder.imageOther)
            
            holder.layoutOther.setOnLongClickListener {
                onLongClick(message)
                true
            }
        }
    }
    
    private fun setupContent(message: MessageEntity, textView: TextView, imageView: ImageView) {
        val path = message.attachmentPath
        
        when (message.type) {
            "IMAGE" -> {
                textView.visibility = View.GONE
                imageView.visibility = View.VISIBLE
                loadImagePreview(path, imageView)
                imageView.setOnClickListener { path?.let { openPath(it) } }
            }
            "VIDEO" -> {
                textView.visibility = View.GONE
                imageView.visibility = View.VISIBLE
                loadVideoThumbnail(path, imageView)
                imageView.setOnClickListener { path?.let { openPath(it) } }
            }
            "PDF" -> {
                textView.visibility = View.VISIBLE
                imageView.visibility = View.VISIBLE
                loadPdfThumbnail(path, imageView)
                textView.text = message.content
                val clickListener = View.OnClickListener { path?.let { openPath(it) } }
                textView.setOnClickListener(clickListener)
                imageView.setOnClickListener(clickListener)
            }
            "FILE" -> {
                textView.visibility = View.VISIBLE
                imageView.visibility = View.VISIBLE
                imageView.setImageResource(android.R.drawable.ic_menu_save)
                textView.text = message.content
                if (path != null) {
                    val clickListener = View.OnClickListener { openPath(path) }
                    textView.setOnClickListener(clickListener)
                    imageView.setOnClickListener(clickListener)
                }
            }
            else -> {
                textView.visibility = View.VISIBLE
                imageView.visibility = View.GONE
                textView.text = message.content
                if (path != null) {
                    textView.setOnClickListener { openPath(path) }
                } else {
                    textView.setOnClickListener(null)
                }
            }
        }
    }

    private fun loadImagePreview(path: String?, imageView: ImageView) {
        if (path == null) {
            imageView.setImageResource(android.R.drawable.ic_menu_gallery)
            return
        }
        
        val model: Any = if (path.startsWith("content://")) Uri.parse(path) else File(path)
        
        imageView.load(model) {
            crossfade(true)
            placeholder(android.R.drawable.ic_menu_gallery)
            error(android.R.drawable.ic_menu_report_image)
            transformations(RoundedCornersTransformation(16f))
        }
    }

    private fun loadVideoThumbnail(path: String?, imageView: ImageView) {
        if (path == null) {
            imageView.setImageResource(android.R.drawable.presence_video_online)
            return
        }
        
        // For video thumbnails, Coil can handle them with the right extensions,
        // but for now we'll stick to a robust manual method or generic icon if Coil fails
        try {
            val file = File(path)
            if (file.exists()) {
                val thumbnail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ThumbnailUtils.createVideoThumbnail(file, Size(400, 400), null)
                } else {
                    ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Video.Thumbnails.MINI_KIND)
                }
                if (thumbnail != null) {
                    imageView.setImageBitmap(thumbnail)
                    return
                }
            }
        } catch (e: Exception) {}
        
        imageView.setImageResource(android.R.drawable.presence_video_online)
    }

    private fun loadPdfThumbnail(path: String?, imageView: ImageView) {
        if (path == null) {
            imageView.setImageResource(android.R.drawable.ic_menu_agenda)
            return
        }
        try {
            val pfd: ParcelFileDescriptor? = if (path.startsWith("content://")) {
                context.contentResolver.openFileDescriptor(Uri.parse(path), "r")
            } else {
                val file = File(path)
                if (file.exists()) ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) else null
            }

            if (pfd != null) {
                val renderer = PdfRenderer(pfd)
                if (renderer.pageCount > 0) {
                    val page = renderer.openPage(0)
                    val bitmap = Bitmap.createBitmap(400, 560, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    imageView.setImageBitmap(bitmap)
                    page.close()
                } else {
                    imageView.setImageResource(android.R.drawable.ic_menu_agenda)
                }
                renderer.close()
                pfd.close()
            } else {
                imageView.setImageResource(android.R.drawable.ic_menu_agenda)
            }
        } catch (e: Exception) {
            imageView.setImageResource(android.R.drawable.ic_menu_agenda)
        }
    }

    private fun openPath(path: String) {
        if (path.startsWith("content://")) {
            openUri(Uri.parse(path))
        } else {
            val file = File(path)
            if (file.exists()) {
                openFile(file)
            } else {
                Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openUri(uri: Uri) {
        try {
            val mimeType = context.contentResolver.getType(uri) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot open file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getMimeType(file: File): String {
        val extension = file.extension
        return if (extension.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: "*/*"
        } else {
            "*/*"
        }
    }

    private fun openFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            
            val mimeType = getMimeType(file)
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            
            val chooser = Intent.createChooser(intent, "Open file with")
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot open file: No app found", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    override fun getItemCount() = messages.size

    fun addMessage(message: MessageEntity) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }
    
    fun setMessages(newMessages: List<MessageEntity>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    fun setMyDeviceId(id: String) {
        this.myDeviceId = id
        notifyDataSetChanged()
    }

    fun updatePeers(peers: List<MeshRouter.Peer>) {
        knownPeers.clear()
        peers.forEach { knownPeers[it.id] = it }
        notifyDataSetChanged()
    }
}
