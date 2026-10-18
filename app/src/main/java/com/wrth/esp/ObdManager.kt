package com.wrth.esp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

@SuppressLint("MissingPermission")
object ObdManager {
    private const val TAG = "ObdManager"
    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var obdSocket: BluetoothSocket? = null
    private var targetMacAddress: String? = null

    @Volatile var isConnected = false
    @Volatile var isRunning = false
    @Volatile private var isConnecting = false

    private var baroPressureKpa: Int = 101 // default to sea level atmospheric pressure
    private var executorService: ScheduledExecutorService? = null

    fun start(macAddress: String) {
        if (isRunning) return
        targetMacAddress = macAddress
        isRunning = true

        executorService = Executors.newSingleThreadScheduledExecutor()
        executorService?.submit {
            supervisorLoop()
        }
        Log.d(TAG, "Supervisor thread started for OBD")
    }

    fun stop() {
        isRunning = false
        disconnectRawSocket()
        executorService?.shutdownNow()
        executorService = null
        Log.d(TAG, "OBD cleanly shut down")
    }

    private fun supervisorLoop() {
        while (isRunning) {
            if (BleManager.currentEspPage == 4) {
                // ensure connected and polling when on boost pressure page on ESP
                if (obdSocket == null || !obdSocket!!.isConnected || !isConnected) {
                    attemptConnection()
                } else {
                    pollObdData()
                }
            } else {
                // drop connection to ELM327 when not on boost page
                if (isConnected || obdSocket != null) {
                    Log.d(TAG, "Not on boost pressure page, dropping ELM327")
                    disconnectRawSocket()
                }
            }

            // loop interval sleep to reduce cpu usage as this is a background process
            try { Thread.sleep(50) } catch (e: InterruptedException) { break }
        }
    }

    private fun attemptConnection() {
        if (isConnecting || targetMacAddress == null) return
        isConnecting = true

        try {
            Log.d(TAG, "Attempting connection to $targetMacAddress")
            val adapter = BluetoothAdapter.getDefaultAdapter()
            val device = adapter.getRemoteDevice(targetMacAddress)

            obdSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            obdSocket?.connect()

            if (obdSocket?.isConnected == true) {
                Log.d(TAG, "OBD Bluetooth linked. Starting ELM327...")
                Thread.sleep(500)
                initialiseElm327()
                isConnected = true
                Log.d(TAG, "OBD ready")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed. Retrying in 2 seconds", e)
            disconnectRawSocket()
            Thread.sleep(2000)
        } finally {
            isConnecting = false
        }
    }

    private fun initialiseElm327() {
        val outStream = obdSocket?.outputStream ?: throw IOException("No output stream")
        val inStream = obdSocket?.inputStream ?: throw IOException("No input stream")

        sendCommand(outStream, inStream, "ATZ\r")
        Thread.sleep(1000)

        sendCommand(outStream, inStream, "ATE0\r")
        Thread.sleep(200)

        sendCommand(outStream, inStream, "ATS0\r")
        Thread.sleep(200)

        sendCommand(outStream, inStream, "ATSP0\r")
        Thread.sleep(1000)

        val baroResponse = sendCommand(outStream, inStream, "0133\r")
        baroPressureKpa = parseObdHex(baroResponse, "4133") ?: 101
        Log.d(TAG, "Baro pressure set to $baroPressureKpa kPa")
    }

    private fun pollObdData() {
        try {
            val outStream = obdSocket?.outputStream ?: throw IOException("No output stream")
            val inStream = obdSocket?.inputStream ?: throw IOException("No input stream")

            val mapResponse = sendCommand(outStream, inStream, "010B\r")

            // ELM327 error, adapter disconnected from ECU
            if (mapResponse.contains("UNABLE TO CONNECT") || mapResponse.isEmpty()) {
                throw IOException("Adapter reported ECU drop")
            }

            val mapKpa = parseObdHex(mapResponse, "410B")

            if (mapKpa != null && BleManager.currentEspPage == 4) {
                val boostKpa = mapKpa - baroPressureKpa
                val boostPsi = boostKpa * 0.145038f
                BleManager.sendBoostPacket(boostPsi)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Polling crashed, trying to reconnect", e)
            disconnectRawSocket() // automatically will try reconnect after being disconnected while boost page active
        }
    }

    @Throws(IOException::class)
    private fun sendCommand(outStream: OutputStream, inStream: InputStream, cmd: String): String {
        outStream.write(cmd.toByteArray())
        outStream.flush()

        val buffer = ByteArray(256)
        var bytes: Int
        val response = java.lang.StringBuilder()

        while (isRunning) {
            bytes = inStream.read(buffer)
            if (bytes > 0) {
                val str = String(buffer, 0, bytes)
                response.append(str)
                if (response.contains(">")) break
            } else if (bytes == -1) {
                throw IOException("Bluetooth stream ended unexpectedly")
            }
        }
        return response.toString()
    }

    private fun parseObdHex(response: String, expectedHeader: String): Int? {
        val cleanRes = response.replace("[^A-Za-z0-9]".toRegex(), "")
        val index = cleanRes.indexOf(expectedHeader)

        if (index != -1) {
            val dataStart = index + expectedHeader.length
            if (cleanRes.length >= dataStart + 2) {
                val hexData = cleanRes.substring(dataStart, dataStart + 2)
                return try {
                    hexData.toInt(16)
                } catch (e: NumberFormatException) {
                    null
                }
            }
        }
        return null
    }

    private fun disconnectRawSocket() {
        isConnected = false
        try { obdSocket?.inputStream?.close() } catch (e: Exception) {}
        try { obdSocket?.outputStream?.close() } catch (e: Exception) {}
        try { obdSocket?.close() } catch (e: Exception) {}
        obdSocket = null
    }
}