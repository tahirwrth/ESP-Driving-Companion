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
            showBluetoothDeviceSelector()
        }

        btnStop.setOnClickListener {
            BluetoothSerialManager.closeConnection()

            val serviceIntent = Intent(this, ESP32ConnectionService::class.java)
            stopService(serviceIntent)

            Log.d("MainActivity", "Stop button pressed: Service and Bluetooth radar terminated.")
        }
    }

    // show list of paired devices
    @SuppressLint("MissingPermission")
    private fun showBluetoothDeviceSelector() {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Please enable Bluetooth first", Toast.LENGTH_SHORT).show()
            return
        }

        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter.bondedDevices
        if (pairedDevices.isNullOrEmpty()) {
            Toast.makeText(this, "No paired devices found. Pair your ESP32 in settings first.", Toast.LENGTH_LONG).show()
            return
        }

        // convert set into arrays for UI
        val deviceNames = pairedDevices.map { it.name ?: "Unknown Device" }.toTypedArray()
        val deviceMacs = pairedDevices.map { it.address }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select ESP32 Device")
            .setItems(deviceNames) { _, which ->
                val selectedName = deviceNames[which]
                val selectedMac = deviceMacs[which]

                // pass MAC address
                BluetoothSerialManager.connectToESP32(selectedMac)

                // start background service
                val serviceIntent = Intent(this, ESP32ConnectionService::class.java)
                startService(serviceIntent)

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