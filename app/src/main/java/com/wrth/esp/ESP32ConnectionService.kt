package com.wrth.esp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.Timer
import java.util.TimerTask

class ESP32ConnectionService : Service() {

    private var updateTimer: Timer? = null
    private var lastSentTitle: String = ""

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
        startUpdateLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startUpdateLoop() {
        updateTimer?.cancel()
        updateTimer = Timer()
        updateTimer?.schedule(object : TimerTask() {
            override fun run() {
                if (!BluetoothSerialManager.isConnected) return

                val newTitle = MediaDataStore.currentTitle
                val newArtist = MediaDataStore.currentArtist

                if (newTitle != lastSentTitle && newTitle != "Unknown") {
                    lastSentTitle = newTitle
                    BluetoothSerialManager.sendMediaPacket(
                        newTitle,
                        newArtist,
                        MediaDataStore.currentArtBytes
                    )
                }

                val controller = MediaDataStore.currentController
                val state = controller?.playbackState
                val duration = MediaDataStore.currentDuration

                if (state != null && duration > 0) {
                    val position = state.position
                    val isPlaying = state.state == android.media.session.PlaybackState.STATE_PLAYING
                    BluetoothSerialManager.sendTimelinePacket(position, duration, isPlaying)
                }
            }
        }, 0, 1000)
    }

    private fun startForegroundServiceNotification() {
        val channelId = "esp32_channel"
        val channel = NotificationChannel(channelId, "ESP32 Service", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ESP32 Screen Sync")
            .setContentText("Connected and running...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        updateTimer?.cancel()
        lastSentTitle = ""
    }

    override fun onBind(intent: Intent?): IBinder? = null
}