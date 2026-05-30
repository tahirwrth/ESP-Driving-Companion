package com.wrth.esp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Initialize BLE with safe context
        BleManager.init(applicationContext)

        // 2. Immediate Permission Check
        checkAndRequestPermissions()

        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val prefs = getSharedPreferences("ESP_PREFS", Context.MODE_PRIVATE)

        btnStart.setOnClickListener {
            val savedMac = prefs.getString("SAVED_MAC", null)
            if (savedMac != null) {
                prefs.edit().putBoolean("ALLOW_BACKGROUND_RUN", true).apply()
                BleManager.connectToESP32(savedMac)
                startForegroundServiceSafe()
            } else {
                showBluetoothDeviceSelector()
            }
        }

        btnStart.setOnLongClickListener {
            showBluetoothDeviceSelector()
            true
        }

        btnStop.setOnClickListener {
            prefs.edit().putBoolean("ALLOW_BACKGROUND_RUN", false).apply()
            BleManager.closeConnection()
            stopService(Intent(this, ESP32ConnectionService::class.java))
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE)
        }

        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 101)
        }
    }

    @SuppressLint("MissingPermission")
    private fun showBluetoothDeviceSelector() {
        val scanner = BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner
        if (scanner == null) {
            Toast.makeText(this, "Enable Bluetooth First!", Toast.LENGTH_SHORT).show()
            return
        }

        val deviceNames = mutableListOf<String>()
        val deviceMacs = mutableListOf<String>()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceNames)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Scanning for Car Screen...")
            .setAdapter(adapter) { _, which ->
                val mac = deviceMacs[which]
                getSharedPreferences("ESP_PREFS", Context.MODE_PRIVATE).edit()
                    .putString("SAVED_MAC", mac).putBoolean("ALLOW_BACKGROUND_RUN", true).apply()
                BleManager.connectToESP32(mac)
                startForegroundServiceSafe()
            }.show()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: "Unknown"
                val mac = result.device.address
                if (!deviceMacs.contains(mac)) {
                    deviceNames.add(if (name == "Guition-Remote") "⭐ $name" else name)
                    deviceMacs.add(mac)
                    adapter.notifyDataSetChanged()
                }
            }
        }

        scanner.startScan(scanCallback)
        Handler(Looper.getMainLooper()).postDelayed({ scanner.stopScan(scanCallback) }, 5000)
    }

    private fun startForegroundServiceSafe() {
        val intent = Intent(this, ESP32ConnectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}