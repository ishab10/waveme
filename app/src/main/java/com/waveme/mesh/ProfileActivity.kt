package com.waveme.mesh

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.waveme.mesh.databinding.ActivityProfileBinding
import com.waveme.mesh.data.UserPreferences
import com.waveme.mesh.data.AppDatabase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@AndroidEntryPoint
class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    
    @Inject
    lateinit var userPreferences: UserPreferences
    
    private var selectedAvatarUri: Uri? = null

    // Service Binding logic
    private var waveMeshService: WaveMeshService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WaveMeshService.LocalBinder
            waveMeshService = binder.getService()
            isBound = true
            updateStatusUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            waveMeshService = null
            isBound = false
            updateStatusUI()
        }
    }

    private val avatarPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedAvatarUri = it
            binding.avatarImg.setImageURI(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup UI
        binding.usernameInput.setText(userPreferences.getUsername())
        binding.bioInput.setText(userPreferences.getBio())
        userPreferences.getAvatarUri()?.let { binding.avatarImg.setImageURI(Uri.parse(it)) }
        
        // Eco-mode initialization
        binding.ecoModeSwitch.isChecked = userPreferences.isEcoMode()

        // Make status read-only
        binding.statusInput.isEnabled = false 
        binding.statusInput.alpha = 0.8f

        binding.avatarImg.setOnClickListener { avatarPicker.launch("image/*") }

        binding.saveBtn.setOnClickListener {
            val newName = binding.usernameInput.text.toString().trim()
            val newBio = binding.bioInput.text.toString().trim()
            val ecoEnabled = binding.ecoModeSwitch.isChecked
            
            if (newName.isNotEmpty()) {
                // 1. Update local persistent storage
                userPreferences.setUsername(newName)
                userPreferences.setBio(newBio)
                userPreferences.setEcoMode(ecoEnabled)
                
                if (selectedAvatarUri != null) {
                    saveAndCompressAvatar(selectedAvatarUri!!)
                }

                // 2. Notify active service to update mesh identity immediately
                if (isBound && waveMeshService != null) {
                    waveMeshService?.updateNickname(newName)
                }

                Toast.makeText(this, "Profile Saved", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "Username cannot be empty", Toast.LENGTH_SHORT).show()
            }
        }

        binding.deleteDataBtn.setOnClickListener {
            showDeleteConfirmation()
        }
    }

    private fun showDeleteConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Delete All Data?")
            .setMessage("This will permanently remove all your messages and profile settings. This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                performDataDeletion()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performDataDeletion() {
        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Clear Database
            AppDatabase.getDatabase(applicationContext).messageDao().deleteAllMessages()
            
            // 2. Clear Preferences
            userPreferences.clearAllData()
            
            // 3. Clear Files (Avatars)
            filesDir.listFiles()?.forEach { it.delete() }

            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, "All data deleted", Toast.LENGTH_LONG).show()
                
                // Restart App or move to Onboarding
                val intent = Intent(this@ProfileActivity, OnboardingActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, WaveMeshService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun updateStatusUI() {
        if (isBound && waveMeshService != null) {
            binding.statusInput.setText("Status: Online (Mesh Active)")
            binding.statusInput.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            binding.statusInput.setText("Status: Offline")
            binding.statusInput.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }
    }

    private fun saveAndCompressAvatar(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            
            // Resize to a reasonable thumbnail size (e.g., 256x256)
            val thumbnail = Bitmap.createScaledBitmap(originalBitmap, 256, 256, true)
            
            val file = File(filesDir, "my_avatar_${System.currentTimeMillis()}.jpg")
            // Delete old avatars to save space
            filesDir.listFiles { f -> f.name.startsWith("my_avatar") }?.forEach { it.delete() }
            
            val outputStream = FileOutputStream(file)
            thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            outputStream.close()

            userPreferences.setAvatarUri(Uri.fromFile(file).toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
