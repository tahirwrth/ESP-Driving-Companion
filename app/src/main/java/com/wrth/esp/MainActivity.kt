package com.wrth.esp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        BluetoothSerialManager.init(this)

        // android 12+ runtime bluetooth permission prompt
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 100)
            }
        }

        // notification access check
        if (!isNotificationServiceEnabled()) {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }

        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)

        btnStart.setOnClickListener {
            Log.d(null, "killed, btn pressed")
            // start bluetooth serial connection
            BluetoothSerialManager.autoConnect("TAHIRPC")

            // start foreground updater service
            val serviceIntent = Intent(this, ESP32ConnectionService::class.java)
            startService(serviceIntent)
        }

        btnStop.setOnClickListener {
            BluetoothSerialManager.closeConnection()

            val serviceIntent = Intent(this, ESP32ConnectionService::class.java)
            stopService(serviceIntent)

            Log.d("MainActivity", "Stop button pressed: Service and Bluetooth radar terminated.")
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(packageName)
    }
}