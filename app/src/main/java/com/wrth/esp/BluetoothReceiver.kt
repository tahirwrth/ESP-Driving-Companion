package com.wrth.esp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BluetoothReceiver : BroadcastReceiver() {

    // private val targetDeviceName = "ESP32_MediaScreen"
    private val targetDeviceName = "TAHIRPC"
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

        if (device?.name == targetDeviceName) {
            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    Log.d("com.wrth.esp.BluetoothReceiver", "ESP32 Connected. Starting background service...")

                    // Start the foreground service
                    val serviceIntent = Intent(context, ESP32ConnectionService::class.java)
                    serviceIntent.putExtra("MAC_ADDRESS", device.address)
                    context.startForegroundService(serviceIntent)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    Log.d("com.wrth.esp.BluetoothReceiver", "ESP32 Disconnected. Stopping service...")

                    // Stop the foreground service
                    val serviceIntent = Intent(context, ESP32ConnectionService::class.java)
                    context.stopService(serviceIntent)
                }
            }
        }
    }
}