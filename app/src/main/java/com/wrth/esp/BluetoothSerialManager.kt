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

    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var isConnected = false
    private var audioManager: AudioManager? = null

    // callback which MediaListenerService listens to for seek
    var onSeekCommandReceived: ((Int) -> Unit)? = null

    fun init(context: Context) {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    fun connectToESP32(macAddress: String) {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return

        val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(macAddress)
        thread {
            try {
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                bluetoothSocket?.connect()
                outputStream = bluetoothSocket?.outputStream
                inputStream = bluetoothSocket?.inputStream
                isConnected = true
                Log.d(TAG, "Connected to ESP32 Successfully")
                startListeningForCommands()
            } catch (e: IOException) { Log.e(TAG, "BLUETOOTH FAILED: ${e.message}"); closeConnection() }
        }
    }

    // image and text
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
            } catch (e: IOException) { closeConnection() }
        }
    }

    // timeline
    fun sendTimelinePacket(positionMs: Long, durationMs: Long, isPlaying: Boolean) {
        if (!isConnected || outputStream == null) return
        thread {
            try {
                // Formatted as: T:Position:Duration:State\n
                val state = if (isPlaying) "1" else "0"
                val packetString = "T:$positionMs:$durationMs:$state\n"

                outputStream?.write(packetString.toByteArray(Charsets.UTF_8))
                outputStream?.flush()
            } catch (e: IOException) { closeConnection() }
        }
    }

    // listener
    private fun startListeningForCommands() {
        thread {
            val buffer = ByteArray(1024)
            val stringBuilder = StringBuilder()

            while (isConnected) {
                try {
                    val bytes = inputStream?.read(buffer) ?: -1
                    if (bytes > 0) {
                        val incoming = String(buffer, 0, bytes, Charsets.UTF_8)
                        stringBuilder.append(incoming)

                        // process commands in new lines
                        var newlineIdx = stringBuilder.indexOf("\n")
                        while (newlineIdx != -1) {
                            val command = stringBuilder.substring(0, newlineIdx).trim()
                            handleIncomingESP32Command(command)
                            stringBuilder.delete(0, newlineIdx + 1)
                            newlineIdx = stringBuilder.indexOf("\n")
                        }
                    }
                } catch (e: IOException) { break }
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
            // "S:xx" command extracts the xx as time and does callback
            command.startsWith("S:") -> {
                val percentage = command.substringAfter("S:").toIntOrNull()
                if (percentage != null) {
                    onSeekCommandReceived?.invoke(percentage)
                }
            }
        }
    }

    fun closeConnection() {
        isConnected = false
        try {
            outputStream?.close()
            inputStream?.close()
            bluetoothSocket?.close()
        } catch (e: IOException) {}
    }
}