package com.wrth.esp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.Timer
import java.util.TimerTask

// sticky service for when connection activated
class ESP32ConnectionService : Service() {

    private var updateTimer: Timer? = null

    override fun onCreate() {
        super.onCreate()

        val prefs = getSharedPreferences("ESP_PREFS", Context.MODE_PRIVATE)
        val allowRun = prefs.getBoolean("ALLOW_BACKGROUND_RUN", false)

        if (!allowRun) {
            stopSelf()
            return
        }

        startForegroundServiceNotification()
        startUpdateLoop()

        val savedMac = prefs.getString("SAVED_MAC", null)
        if (savedMac != null) {
            BleManager.connectToESP32(savedMac)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startUpdateLoop() {
        updateTimer?.cancel()
        updateTimer = Timer()
        updateTimer?.schedule(object : TimerTask() {
            override fun run() {
                if (!BleManager.isConnected) return

                val controller = MediaDataStore.currentController
                val state = controller?.playbackState
                val duration = MediaDataStore.currentDuration

                if (state != null && duration > 0) {
                    val position = state.position
                    val isPlaying = state.state == android.media.session.PlaybackState.STATE_PLAYING
                    BleManager.sendTimelinePacket(position, duration, isPlaying)
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
            .setContentText("BLE 5.0 Link Active...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        try {
            startForeground(1, notification)
        } catch (e: Exception) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        updateTimer?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}