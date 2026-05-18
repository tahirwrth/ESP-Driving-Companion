package com.wrth.esp

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.collections.isNullOrEmpty
import android.content.Context
import androidx.core.content.edit

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
        val prefs = getSharedPreferences("ESP_PREFS", Context.MODE_PRIVATE)

        // short press = connect to previous device
        btnStart.setOnClickListener {
            val savedMac = prefs.getString("SAVED_MAC", null)
            if (savedMac != null) {
                // write ALLOW flag for service
                prefs.edit { putBoolean("ALLOW_BACKGROUND_RUN", true) }

                BluetoothSerialManager.connectToESP32(savedMac)
                startService(Intent(this, ESP32ConnectionService::class.java))
                Toast.makeText(this, "Connecting to saved device...", Toast.LENGTH_SHORT).show()
            } else {
                showBluetoothDeviceSelector()
            }
        }

        // long press = select bluetooth device
        btnStart.setOnLongClickListener {
            showBluetoothDeviceSelector()
            true
        }

        btnStop.setOnClickListener {
            // write DENY flag so cant be revived
            prefs.edit { putBoolean("ALLOW_BACKGROUND_RUN", false) }

            BluetoothSerialManager.closeConnection()
            stopService(Intent(this, ESP32ConnectionService::class.java))
            Toast.makeText(this, "Service Killed Permanently", Toast.LENGTH_SHORT).show()
        }
    }

    // show list of paired devices
    @SuppressLint("MissingPermission")
    private fun showBluetoothDeviceSelector() {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return

        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter.bondedDevices
        if (pairedDevices.isNullOrEmpty()) return

        val deviceNames = pairedDevices.map { it.name ?: "Unknown Device" }.toTypedArray()
        val deviceMacs = pairedDevices.map { it.address }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select ESP32 Device")
            .setItems(deviceNames) { _, which ->
                val selectedName = deviceNames[which]
                val selectedMac = deviceMacs[which]

                // save new MAC address
                getSharedPreferences("ESP_PREFS", Context.MODE_PRIVATE)
                    .edit {
                        putString("SAVED_MAC", selectedMac)
                    }

                // kill old connection before starting new
                BluetoothSerialManager.closeConnection()

                // start connecting new device
                BluetoothSerialManager.connectToESP32(selectedMac)
                startService(Intent(this, ESP32ConnectionService::class.java))

                Toast.makeText(this, "Connecting to $selectedName...", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(packageName)
    }
}