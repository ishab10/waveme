package com.waveme.mesh

import android.Manifest
import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class NotificationHelper(private val context: Context) {

    private val CHANNEL_ID = "wave_notifications"
    private val NOTIFICATION_ID_CONNECTION = 100
    private val NOTIFICATION_ID_PROGRESS = 101

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Wave Notifications"
            val descriptionText = "Connection and file transfer status"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showConnectionNotification(title: String, message: String) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_CONNECTION, builder.build())
        } catch (e: Exception) {
            Log.e("NotificationHelper", "Failed to show connection notification", e)
        }
    }

    fun showFileProgressNotification(fileName: String, progress: Int, isIncoming: Boolean) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            val title = if (isIncoming) "Receiving: $fileName" else "Sending: $fileName"
            val icon = if (isIncoming) R.drawable.stat_sys_download else R.drawable.stat_sys_upload

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText("$progress% complete")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(100, progress, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_PROGRESS, builder.build())
        } catch (e: Exception) {
             Log.e("NotificationHelper", "Failed to show progress notification", e)
        }
    }

    fun hideFileProgressNotification() {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_PROGRESS)
        } catch (e: Exception) {
            Log.e("NotificationHelper", "Failed to cancel notification", e)
        }
    }
}