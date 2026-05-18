package com.wrth.esp

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

class ESP32ConnectionService : Service() {

    private val CHANNEL_ID = "ESP32_CarScreen_Channel"
    private val NOTIFICATION_ID = 1

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        BluetoothSerialManager.init(this) // initialise auto content manager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // show ongoing notification
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Car Screen Active")
            .setContentText("Connected and syncing media.")
            .setSmallIcon(R.drawable.stat_sys_data_bluetooth) // icon
            .build()

        startForeground(NOTIFICATION_ID, notification)

        // get MAC address from BroadcastReceiver and connect
        val macAddress = intent?.getStringExtra("MAC_ADDRESS")
        if (macAddress != null) {
            BluetoothSerialManager.connectToESP32(macAddress)
        }

        // restarts service if killed
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        BluetoothSerialManager.closeConnection()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "ESP32 Connection Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }
}