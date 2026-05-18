package com.wrth.esp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.StringBuilder
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.concurrent.thread

@SuppressLint("MissingPermission")
object BluetoothSerialManager {

    private const val TAG = "BluetoothSerialManager"
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @Volatile var isConnected = false
    @Volatile private var shouldLoopConnect = false

    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var audioManager: AudioManager? = null

    var onSeekCommandReceived: ((Int) -> Unit)? = null

    fun init(context: Context) {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    fun autoConnect(targetDeviceName: String) {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return

        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter.bondedDevices
        if (!pairedDevices.isNullOrEmpty()) {
            for (device in pairedDevices) {
                if (device.name == targetDeviceName) {
                    connectToESP32(device.address)
                    return
                }
            }
        }
    }

    fun connectToESP32(macAddress: String) {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return

        val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(macAddress)
        shouldLoopConnect = true

        thread {
            while (shouldLoopConnect) {
                if (!isConnected) {
                    try {
                        Log.d(TAG, "Attempting Bluetooth connection to ${device.name}...")
                        bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                        bluetoothSocket?.connect()

                        outputStream = bluetoothSocket?.outputStream
                        inputStream = bluetoothSocket?.inputStream
                        isConnected = true

                        Log.d(TAG, "Bluetooth Success")

                        listenForCommandsDirect()

                    } catch (e: Exception) { // 2. Changed from IOException to Exception to safeguard thread life
                        Log.e(TAG, "Connection failed: ${e.message}. Retrying in 3 seconds...")
                        cleanSocketsOnly()
                        try {
                            Thread.sleep(3000)
                        } catch (ie: InterruptedException) {
                            Log.e(TAG, "Reconnection sleep interrupted.")
                            break
                        }
                    }
                } else {
                    try {
                        Thread.sleep(1000)
                    } catch (ie: InterruptedException) { break }
                }
            }
            Log.d(TAG, "Connection loop thread has completely exited.")
        }
    }

    fun sendMediaPacket(title: String, artist: String, imageBytes: ByteArray) {
        if (!isConnected || outputStream == null) return
        thread {
            try {
                val titleBytes = title.toByteArray(Charsets.UTF_8)
                val artistBytes = artist.toByteArray(Charsets.UTF_8)
                val totalSize = 2 + 1 + titleBytes.size + 1 + artistBytes.size + 4 + imageBytes.size
                val buffer = ByteBuffer.allocate(totalSize)

                buffer.put(0xAA.toByte()).put(0xBB.toByte())
                buffer.put(titleBytes.size.toByte()).put(titleBytes)
                buffer.put(artistBytes.size.toByte()).put(artistBytes)
                buffer.putInt(imageBytes.size).put(imageBytes)

                outputStream?.write(buffer.array())
                outputStream?.flush()
            } catch (e: IOException) { handleStreamDrop() }
        }
    }

    fun sendTimelinePacket(positionMs: Long, durationMs: Long, isPlaying: Boolean) {
        if (!isConnected || outputStream == null) return
        thread {
            try {
                val state = if (isPlaying) "1" else "0"
                val packetString = "T:$positionMs:$durationMs:$state\n"

                outputStream?.write(packetString.toByteArray(Charsets.UTF_8))
                outputStream?.flush()
            } catch (e: IOException) { handleStreamDrop() }
        }
    }

    private fun listenForCommandsDirect() {
        val buffer = ByteArray(1024)
        val stringBuilder = StringBuilder()

        while (isConnected && shouldLoopConnect) {
            try {
                val bytes = inputStream?.read(buffer) ?: -1
                if (bytes > 0) {
                    val incoming = String(buffer, 0, bytes, Charsets.UTF_8)
                    stringBuilder.append(incoming)

                    var newlineIdx = stringBuilder.indexOf("\n")
                    while (newlineIdx != -1) {
                        val command = stringBuilder.substring(0, newlineIdx).trim()
                        handleIncomingESP32Command(command)
                        stringBuilder.delete(0, newlineIdx + 1)
                        newlineIdx = stringBuilder.indexOf("\n")
                    }
                } else if (bytes == -1) {
                    throw IOException("Stream ended")
                }
            } catch (e: IOException) {
                Log.e(TAG, "Connection lost during read loop.")
                handleStreamDrop()
                break
            }
        }
    }

    private fun handleIncomingESP32Command(command: String) {
        Log.d(TAG, "Received command: $command")
        when {
            command == "P" -> {
                audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            }
            command == ">" -> {
                audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
                audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))
            }
            command == "<" -> {
                audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            }
            command.startsWith("S:") -> {
                val percentage = command.substringAfter("S:").toIntOrNull()
                if (percentage != null) {
                    onSeekCommandReceived?.invoke(percentage)
                }
            }
        }
    }

    private fun handleStreamDrop() {
        isConnected = false
        cleanSocketsOnly()
    }

    fun closeConnection() {
        Log.w(TAG, "ℹ️ closeConnection() was called!", Throwable())
        shouldLoopConnect = false
        isConnected = false
        cleanSocketsOnly()
    }

    private fun cleanSocketsOnly() {
        try {
            outputStream?.close()
            inputStream?.close()
            bluetoothSocket?.close()
        } catch (e: IOException) {}
        outputStream = null
        inputStream = null
        bluetoothSocket = null
    }
}