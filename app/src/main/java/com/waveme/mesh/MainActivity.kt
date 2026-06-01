package com.waveme.mesh

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.waveme.mesh.data.MessageEntity
import com.waveme.mesh.data.UserPreferences
import com.waveme.mesh.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), WaveMeshService.ServiceListener {

    private val viewModel: MainViewModel by viewModels()

    private var isBound = false
    private lateinit var binding: ActivityMainBinding
    
    @Inject
    lateinit var userPreferences: UserPreferences

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WaveMeshService.LocalBinder
            viewModel.waveMeshService = binder.getService()
            isBound = true
            viewModel.waveMeshService?.addServiceListener(this@MainActivity)
            viewModel.updateStatus()
            
            viewModel.waveMeshService?.broadcastUserGroups()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            viewModel.waveMeshService = null
            isBound = false
            showError("Service disconnected. Please restart the app.", "Exit") { finish() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!userPreferences.isOnboardingCompleted()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chats -> {
                    replaceFragment(ChatsFragment())
                    binding.fabCreateGroup.hide()
                    true
                }
                R.id.nav_discovery -> {
                    replaceFragment(DiscoveryFragment())
                    binding.fabCreateGroup.show()
                    true
                }
                R.id.nav_gallery -> {
                    replaceFragment(GalleryFragment())
                    binding.fabCreateGroup.hide()
                    true
                }
                else -> false
            }
        }

        if (savedInstanceState == null) {
            binding.bottomNavigation.selectedItemId = R.id.nav_discovery
        }

        binding.fabCreateGroup.setOnClickListener {
            showCreateGroupDialog()
        }

        viewModel.fileProgress.observe(this) { progress ->
            if (progress != null) {
                binding.globalProgressBar.visibility = View.VISIBLE
                binding.globalProgressBar.isIndeterminate = false
                binding.globalProgressBar.progress = progress
            } else {
                binding.globalProgressBar.visibility = View.GONE
            }
        }
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun showCreateGroupDialog() {
        val editText = EditText(this).apply {
            setHint("Group Name")
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Create a New Group")
            .setView(editText)
            .setPositiveButton("Create") { _, _ ->
                val groupName = editText.text.toString()
                if (groupName.isNotBlank()) {
                    val currentGroups = userPreferences.getUserGroups().toMutableSet()
                    currentGroups.add(groupName)
                    userPreferences.saveUserGroups(currentGroups)
                    
                    // Update VM with the new set, including the expiry for the new group
                    val topicsWithExpiry = userPreferences.getUserGroupsWithExpiry()
                    viewModel.onTopicsChanged(topicsWithExpiry)
                    
                    viewModel.waveMeshService?.broadcastUserGroups()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndStart()
        viewModel.onTopicsChanged(userPreferences.getUserGroupsWithExpiry())
    }

    private fun checkPermissionsAndStart() {
        if (!hasPermissions()) {
            showError("Wave needs connectivity permissions to function.", "Setup Permissions") {
                startActivity(Intent(this, PermissionRequestActivity::class.java))
            }
        } else if (!isLocationEnabled()) {
            showError("Location services are disabled. They are required for peer discovery.", "Enable Location") {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        } else if (!isBluetoothEnabled()) {
            showError("Bluetooth is disabled. It is required for Nearby Connections.", "Enable Bluetooth") {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
        } else {
            hideError()
            startAndBindService()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_profile -> {
                startActivity(Intent(this, ProfileActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun startAndBindService() {
        if (isBound) return

        val intent = Intent(this, WaveMeshService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            showError("Failed to start the communication service.", "Exit") { finish() }
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
        viewModel.onPeersChanged(peers)
    }

    override fun onDiscoveredTopicsChanged(discoveredTopics: Map<String, Long>) {
        viewModel.onTopicsChanged(discoveredTopics)
    }
    
    override fun onMessageReceived(message: MessageEntity) {}

    override fun onFileProgress(payloadId: Long, fileName: String, progress: Int, isIncoming: Boolean) {
        viewModel.onFileProgress(progress)
    }

    override fun onFileCompleted(fileName: String, senderId: String) {
        viewModel.onFileCompleted()
    }

    override fun onMessageAcked(messageId: String) {}

    override fun onMessageSeen(messageId: String) {}

    override fun onConnectionStateChanged(endpointId: String, state: WaveMeshService.ConnectionState, endpointName: String) {
        viewModel.onConnectionStateChanged(state, endpointName)
    }

    private fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        }
        return permissions
    }

    private fun hasPermissions(): Boolean {
        return getRequiredPermissions().all { 
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED 
        }
    }
    
    private fun showError(message: String, buttonText: String, onButtonClick: () -> Unit) {
        binding.mainContent.visibility = View.GONE
        binding.errorLayout.visibility = View.VISIBLE
        binding.errorText.text = message
        binding.errorButton.text = buttonText
        binding.errorButton.setOnClickListener { onButtonClick() }
    }

    private fun hideError() {
        binding.mainContent.visibility = View.VISIBLE
        binding.errorLayout.visibility = View.GONE
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
               locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun isBluetoothEnabled(): Boolean {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bluetoothManager?.adapter?.isEnabled ?: false
    }
}
