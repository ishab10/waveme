package com.waveme.mesh

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import coil.load
import com.waveme.mesh.databinding.ActivityFullscreenImageBinding
import java.io.File
import java.io.FileInputStream

class FullscreenImageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFullscreenImageBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFullscreenImageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val imagePath = intent.getStringExtra("IMAGE_PATH")
        if (imagePath != null) {
            val model: Any = if (imagePath.startsWith("content://") || imagePath.startsWith("file://")) {
                imagePath
            } else {
                File(imagePath)
            }

            binding.fullscreenImageView.load(model) {
                crossfade(true)
            }

            binding.shareButton.setOnClickListener {
                shareImage(imagePath)
            }

            binding.downloadButton.setOnClickListener {
                saveImageToGallery(imagePath)
            }
        }

        binding.closeButton.setOnClickListener {
            finish()
        }
    }

    private fun saveImageToGallery(path: String) {
        if (path.startsWith("content://")) {
            Toast.makeText(this, "Cannot download this image type directly", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val file = File(path)
            if (!file.exists()) {
                Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
                return
            }

            val fileName = "Wave_${System.currentTimeMillis()}_${file.name}"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Wave")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val uri = contentResolver.insert(collection, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    FileInputStream(file).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                }

                Toast.makeText(this, "Image saved to Gallery (Pictures/Wave)", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to save image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareImage(path: String) {
        try {
            val uri = if (path.startsWith("content://")) {
                Uri.parse(path)
            } else {
                FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    File(path)
                )
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Image via"))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to share image", Toast.LENGTH_SHORT).show()
        }
    }
}