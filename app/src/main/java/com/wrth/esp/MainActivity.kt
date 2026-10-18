package com.wrth.esp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var isSimulatingBoost = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BleManager.init(this)
        setContentView(R.layout.activity_main)

        checkAndRequestPermissions()

        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)

        // tap (start auto-connect / passive listening)
        btnStart.setOnClickListener {
            val savedMac = BleManager.getSavedMacAddress(this)
            if (savedMac != null) {
                BleManager.enableAutoConnect(this)

                btnStart.text = "Listening for ESP..."
                btnStart.isEnabled = false
                Toast.makeText(this, "Background listening activated", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Hold button down to scan and select HUD first", Toast.LENGTH_LONG).show()
                showBluetoothDeviceSelector()
            }
        }

        // long press start to select (new) device
        btnStart.setOnLongClickListener {
            showBluetoothDeviceSelector()
            true
        }

        // stop (kills passive listener or active connection)
        btnStop.setOnClickListener {
            BleManager.closeConnection()

            btnStart.text = "Start Connection"
            btnStart.isEnabled = true
        }

        val btnTestBoost = findViewById<Button>(R.id.btnTestBoost)
        btnTestBoost.setOnClickListener {
            toggleBoostSimulator()
        }

        // GIF/MJPEG selection
        val btnOpenAnimations = findViewById<Button>(R.id.btnOpenAnimations)
        btnOpenAnimations.setOnClickListener {
            val intent = Intent(this@MainActivity, AnimationActivity::class.java)
            startActivity(intent)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        // location permissions for Google Fused GPS
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        // BLE permissions for Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // foreground service permissions for Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE)
        }

        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 101)
        }
    }

    private fun toggleBoostSimulator() {
        if (BleManager.isSimulating) {
            BleManager.isSimulating = false
            Toast.makeText(this, "HUD Simulator stopped", Toast.LENGTH_SHORT).show()

            // start real GPS is sim is stopped while page is active
            if (BleManager.currentEspPage == 2) {
                GpsManager.updatePageContext(2, this)
            }
            return
        }

        if (!BleManager.isConnected) {
            Toast.makeText(this, "Connect to ESP32 first", Toast.LENGTH_SHORT).show()
            return
        }

        BleManager.isSimulating = true
        Toast.makeText(this, "HUD Simulator started", Toast.LENGTH_SHORT).show()

        GpsManager.stopTracking()

        Thread {
            var fakeBoost = -10.0f
            var accelerating = true

            while (BleManager.isSimulating && BleManager.isConnected) {

                // simulate boost
                if (BleManager.currentEspPage == 4) {
                    BleManager.sendBoostPacket(fakeBoost)

                    if (accelerating) {
                        fakeBoost += 0.6f
                        if (fakeBoost >= 16.5f) {
                            accelerating = false
                            Thread.sleep(500)
                        }
                    } else {
                        fakeBoost -= 1.2f
                        if (fakeBoost <= -10.0f) {
                            fakeBoost = -10.0f
                            accelerating = true
                            Thread.sleep(1500)
                        }
                    }
                }

                // simulate GPS
                else if (BleManager.currentEspPage == 2) {
                    BleManager.sendGpsPacket(80, 80, 450)
                }

                Thread.sleep(30)
            }
            BleManager.isSimulating = false
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun showBluetoothDeviceSelector() {
        val scanner = BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner
        if (scanner == null) {
            Toast.makeText(this, "Enable Bluetooth first", Toast.LENGTH_SHORT).show()
            return
        }

        val deviceNames = mutableListOf<String>()
        val deviceMacs = mutableListOf<String>()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceNames)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Scanning for ESP")
            .setAdapter(adapter) { _, which ->
                val mac = deviceMacs[which]
                BleManager.saveMacAddress(this, mac)
                BleManager.enableAutoConnect(this)

                val btnStart = findViewById<Button>(R.id.btnStart)
                btnStart.text = "Listening for ESP"
                btnStart.isEnabled = false

                Toast.makeText(this, "Saved and listening", Toast.LENGTH_SHORT).show()
            }.create()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name

                if (!name.isNullOrBlank()) {
                    val mac = result.device.address

                    if (!deviceMacs.contains(mac)) {
                        deviceNames.add(if (name.contains("Guition", true) || name.contains("ESP", true)) "⭐ $name" else name)
                        deviceMacs.add(mac)
                        runOnUiThread { adapter.notifyDataSetChanged() }
                    }
                }
            }
        }

        dialog.setOnDismissListener {
            scanner.stopScan(scanCallback)
        }

        scanner.startScan(scanCallback)
        dialog.show()

        Handler(Looper.getMainLooper()).postDelayed({
            if (dialog.isShowing) scanner.stopScan(scanCallback)
        }, 10000)
    }
}