package com.example.presensor.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.presensor.R
import com.example.presensor.ble.ReaderManager.ConnectionState

class ReaderStatusService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "reader_status_channel"
        private const val ACTION_STOP = "STOP_SERVICE"

        // Single reference to look up the active instance
        private var instance: ReaderStatusService? = null

        /**
         * Pure lambda API: Anyone can call this from anywhere to update the icon!
         */
        fun updateStatus(state: ConnectionState) {
            instance?.updateNotification(state)
        }

        fun startService(context: Context) {
            val intent = Intent(context, ReaderStatusService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ReaderStatusService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        updateNotification(ConnectionState.DISCONNECTED)
    }

    private fun updateNotification(state: ConnectionState) {
        val (icon, text) = when (state) {
            ConnectionState.CONNECTED -> Pair(
                R.drawable.ic_reader_connected,
                "Presensor Reader: Ready"
            )

            ConnectionState.SCANNING -> Pair(
                R.drawable.ic_reader_disconnected,
                "Presensor Reader: Scanning..."
            )

            ConnectionState.CONNECTING -> Pair(
                R.drawable.ic_reader_disconnected,
                "Presensor Reader: Connecting/Authenticating..."
            )

            ConnectionState.DISCONNECTED -> Pair(
                R.drawable.ic_reader_disconnected,
                "Presensor Reader: Disconnected"
            )
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle("Presensor Attendance")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reader Connection Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null // Clean up memory reference
    }
}