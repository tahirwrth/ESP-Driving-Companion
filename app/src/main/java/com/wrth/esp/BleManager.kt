package com.wrth.esp

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

@SuppressLint("MissingPermission")
object BleManager {
    private const val TAG = "BleManager"

    private val SERVICE_UUID = UUID.fromString("4fa12001-c27d-4b52-9d33-4a1803766601")
    private val COMMAND_CHAR_UUID = UUID.fromString("4fa12004-c27d-4b52-9d33-4a1803766601")
    private val IMAGE_CHAR_UUID = UUID.fromString("4fa12005-c27d-4b52-9d33-4a1803766601")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var bluetoothGatt: BluetoothGatt? = null
    private var isConnecting = false
    var isConnected = false

    private var savedMacAddress: String? = null
    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    var onSeekCommandReceived: ((Int) -> Unit)? = null

    private val currentSession = AtomicInteger(0)
    private var isTransferring = false

    var latestImageData: ByteArray? = null
    var latestImageHash: Int = 0
    private var pingTimer: Timer? = null

    fun init(context: Context) {
        this.appContext = context.applicationContext
        this.audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    // esp32 connection
    fun connectToESP32(macAddress: String) {
        if (isConnecting || isConnected) return
        savedMacAddress = macAddress
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!bluetoothAdapter.isEnabled) return

        val device = bluetoothAdapter.getRemoteDevice(macAddress)
        isConnecting = true
        bluetoothGatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun closeConnection() {
        isConnected = false
        isConnecting = false
        pingTimer?.cancel()
        GpsManager.stopTracking()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    // timer for checking image difference between phone and esp32
    private fun startPingTimer() {
        pingTimer?.cancel()
        pingTimer = Timer()
        pingTimer?.schedule(object : TimerTask() {
            override fun run() {
                if (isConnected && bluetoothGatt != null && !isTransferring && latestImageHash != 0) {
                    val cmd = "H:$latestImageHash"
                    val gatt = bluetoothGatt ?: return
                    val service = gatt.getService(SERVICE_UUID) ?: return
                    val cmdChar = service.getCharacteristic(COMMAND_CHAR_UUID) ?: return
                    writeCharacteristicChunk(gatt, cmdChar, cmd.toByteArray())
                }
            }
        }, 500, 500)

        // start gps tracking only when connected
        appContext?.let { GpsManager.startTracking(it) }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeConnection()
                savedMacAddress?.let { connectToESP32(it) }
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                closeConnection()
                savedMacAddress?.let { connectToESP32(it) }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    val cmdChar = service.getCharacteristic(COMMAND_CHAR_UUID)
                    isConnected = true
                    startPingTimer()
                    cmdChar?.let { char ->
                        gatt.setCharacteristicNotification(char, true)
                        val descriptor = char.getDescriptor(CCCD_UUID)
                        descriptor?.let { desc ->
                            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(desc)
                        }
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == COMMAND_CHAR_UUID) {
                val cmd = String(value, Charsets.UTF_8).trim()
                handleIncomingESP32Command(cmd)
            }
        }
    }

    private fun handleIncomingESP32Command(command: String) {
        if (command.startsWith("REQ_IMG:")) {
            val requestedHash = command.removePrefix("REQ_IMG:").toIntOrNull()
            if (requestedHash == latestImageHash && latestImageData != null) {
                sendMediaPacket(latestImageData!!, latestImageHash)
            }
            return
        }

        // seek
        if (command.startsWith("S:")) {
            val percent = command.removePrefix("S:").toIntOrNull()
            if (percent != null) onSeekCommandReceived?.invoke(percent)
            return
        }

        when (command) {
            // pause/play
            "P" -> {
                audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            }
            // next song
            ">" -> {
                audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
                audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))
            }
            // previous song
            "<" -> {
                audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            }
        }
    }

    private fun writeCharacteristicChunk(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, data: ByteArray) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = data
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            gatt.writeCharacteristic(characteristic)
        }
    }

    // send full image in chunks within BLE 5.0 bandwidth
    private fun sendMediaPacket(imageBytes: ByteArray, hash: Int) {
        if (!isConnected || bluetoothGatt == null || isTransferring) return

        Thread {
            isTransferring = true
            val sessionId = currentSession.incrementAndGet()
            val gatt = bluetoothGatt ?: return@Thread
            val service = gatt.getService(SERVICE_UUID) ?: return@Thread
            val imageChar = service.getCharacteristic(IMAGE_CHAR_UUID) ?: return@Thread
            val cmdChar = service.getCharacteristic(COMMAND_CHAR_UUID) ?: return@Thread

            val chunkSize = 244
            var offset = 0

            try {
                writeCharacteristicChunk(gatt, cmdChar, "START:$hash".toByteArray())
                Thread.sleep(40)

                while (offset < imageBytes.size) {
                    if (sessionId != currentSession.get()) return@Thread

                    val end = (offset + chunkSize).coerceAtMost(imageBytes.size)
                    val chunk = imageBytes.copyOfRange(offset, end)

                    writeCharacteristicChunk(gatt, imageChar, chunk)

                    offset += chunkSize
                    Thread.sleep(20)
                }

                Thread.sleep(60)
                if (sessionId == currentSession.get()) {
                    writeCharacteristicChunk(gatt, cmdChar, "DECODE_NOW".toByteArray())
                }

            } catch (e: Exception) {
                Log.e(TAG, "Transfer Error", e)
            } finally {
                isTransferring = false
            }
        }.start()
    }

    // send timeline to esp32 for ring display
    fun sendTimelinePacket(position: Long, duration: Long, playing: Boolean) {
        if (!isConnected || bluetoothGatt == null || duration <= 0 || isTransferring) return
        val progress = ((position.toFloat() / duration.toFloat()) * 100).toInt()
        val cmd = "T:$progress"
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(SERVICE_UUID) ?: return
        val cmdChar = service.getCharacteristic(COMMAND_CHAR_UUID) ?: return
        writeCharacteristicChunk(gatt, cmdChar, cmd.toByteArray())
    }

    // navigation sender
    fun sendNavPacket(turn: String, distance: String, street: String) {
        if (!isConnected || bluetoothGatt == null) return
        val cmd = "N:$turn|$distance|$street"
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(SERVICE_UUID) ?: return
        val cmdChar = service.getCharacteristic(COMMAND_CHAR_UUID) ?: return
        writeCharacteristicChunk(gatt, cmdChar, cmd.toByteArray())
    }

    // wip: display alert i.e., police or hazard
    fun sendAlertPacket(alertType: String) {
        if (!isConnected || bluetoothGatt == null) return
        val cmd = "A:$alertType"
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(SERVICE_UUID) ?: return
        val cmdChar = service.getCharacteristic(COMMAND_CHAR_UUID) ?: return
        writeCharacteristicChunk(gatt, cmdChar, cmd.toByteArray())
    }

    // gps data sender for speed limit / cameras
    fun sendGpsPacket(currentSpeed: Int, speedLimit: Int, cameraDist: Int) {
        if (!isConnected || bluetoothGatt == null) return
        val cmd = "G:$currentSpeed|$speedLimit|$cameraDist"
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(SERVICE_UUID) ?: return
        val cmdChar = service.getCharacteristic(COMMAND_CHAR_UUID) ?: return
        writeCharacteristicChunk(gatt, cmdChar, cmd.toByteArray())
    }
}