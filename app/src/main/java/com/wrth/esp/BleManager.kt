package com.wrth.esp

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import androidx.core.content.edit

@SuppressLint("MissingPermission")
object BleManager {
    private const val TAG = "BleManager"

    private const val PREFS_NAME = "DashcamPrefs"
    private const val KEY_MAC = "saved_esp32_mac"

    private val CUSTOM_SVC_UUID = UUID.fromString("4fa12001-c27d-4b52-9d33-4a1803766601")
    private val CUSTOM_CMD_UUID = UUID.fromString("4fa12004-c27d-4b52-9d33-4a1803766601")
    private val CUSTOM_IMG_UUID = UUID.fromString("4fa12005-c27d-4b52-9d33-4a1803766601")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var imageCharacteristic: BluetoothGattCharacteristic? = null
    private var appContext: Context? = null
    private var prevTimeline: String? = null
    private var audioManager: AudioManager? = null

    // gps
    private var lastSentSpeed = -1
    private var lastSentLimit = -1
    private var lastSentCamDist = -999
    @Volatile var isSimulating: Boolean = false

    var isConnected = false
        private set

    // media state
    private val currentMediaSession = AtomicInteger(0)
    private var isTransferringImmediate = false
    var latestImageData: ByteArray? = null

    @Volatile var currentEspPage: Int = 0
    var latestImageHash: Int = 0
    private var pingTimer: Timer? = null

    // boost and gforce
    private var lastSentBoost = -999f
    private var lastSentGx = -999f
    private var lastSentGy = -999f

    // video selection ui
    interface VideoListListener {
        fun onVideoListReceived(videos: List<String>)
    }
    private var videoListListener: VideoListListener? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun setVideoListListener(listener: VideoListListener?) {
        this.videoListListener = listener
    }

    fun saveMacAddress(context: Context, macAddress: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_MAC, macAddress) }
    }

    fun getSavedMacAddress(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MAC, null)
    }

    fun enableAutoConnect(context: Context) {
        val savedMac = getSavedMacAddress(context)
        if (savedMac == null) return

        closeConnection()

        appContext = context.applicationContext
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // start foreground service to prevent android killing background listener
        val serviceIntent = Intent(appContext, TrackingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext?.startForegroundService(serviceIntent)
        } else {
            appContext?.startService(serviceIntent)
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = bluetoothManager.adapter.getRemoteDevice(savedMac)

        Log.w(TAG, "BLE: letting android do the auto-connecting")
        bluetoothGatt = device.connectGatt(context, true, gattCallback)
    }

    fun closeConnection() {
        pingTimer?.cancel()

        PhoneSensorManager.stop()
        ObdManager.stop()

        appContext?.let { it.stopService(Intent(it, TrackingService::class.java)) }

        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        isConnected = false
    }

    private fun startPingTimer() {
        pingTimer?.cancel()
        pingTimer = Timer()
        pingTimer?.schedule(object : TimerTask() {
            override fun run() {
                if (isConnected && bluetoothGatt != null && !isTransferringImmediate) {
                    if (latestImageHash != 0 && currentEspPage == 0) {
                        sendMediaHash(latestImageHash)
                        Thread.sleep(20)
                    }

                    try {
                        val controller = MediaDataStore.currentController
                        if (controller != null) {
                            val state = controller.playbackState
                            val metadata = controller.metadata

                            if (state != null && metadata != null) {
                                val duration = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
                                if (duration > 0) {
                                    var position = state.position
                                    if (state.state == android.media.session.PlaybackState.STATE_PLAYING) {
                                        val timeDelta = android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
                                        position += (timeDelta * state.playbackSpeed).toLong()
                                    }
                                    val percent = ((position.toFloat() / duration.toFloat()) * 100f).coerceIn(0f, 100f)
                                    sendTimelinePacket(percent)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Progress calculation error", e)
                    }
                }
            }
        }, 500, 500)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.w(TAG, "BLE: ESP32 Connected")
                isConnected = true

                appContext?.let { PhoneSensorManager.start(it) }

                val adapter = BluetoothAdapter.getDefaultAdapter()
                val obdDevice = adapter?.bondedDevices?.firstOrNull { device ->
                    val name = device.name ?: ""
                    name.contains("OBD", ignoreCase = true) || name.contains("ELM327", ignoreCase = true)
                }

                if (obdDevice != null) {
                    Log.d(TAG, "OBD Found: ${obdDevice.name}")
                    ObdManager.start(obdDevice.address)
                }

                gatt.requestMtu(512)
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "BLE: ESP32 Disconnected")
                isConnected = false

                PhoneSensorManager.stop()
                ObdManager.stop()

                writeCharacteristic = null
                imageCharacteristic = null
                pingTimer?.cancel()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(CUSTOM_SVC_UUID)
                if (service != null) {
                    writeCharacteristic = service.getCharacteristic(CUSTOM_CMD_UUID)
                    imageCharacteristic = service.getCharacteristic(CUSTOM_IMG_UUID)

                    if (writeCharacteristic != null && imageCharacteristic != null) {
                        Log.w(TAG, "BLE: Telemetry linked")
                        startPingTimer()

                        gatt.setCharacteristicNotification(writeCharacteristic, true)
                        val descriptor = writeCharacteristic!!.getDescriptor(CCCD_UUID)
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        }
                    }
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val response = String(characteristic.value).trim()
            handleIncomingESP32Command(response)
        }
    }

    private fun handleIncomingESP32Command(command: String) {
        if (!isConnected) return

        if (command.startsWith("VIDS:")) {
            val filePayload = command.removePrefix("VIDS:")
            if (filePayload.isNotEmpty()) {
                val filesList = filePayload.split(",")
                videoListListener?.onVideoListReceived(filesList)
            } else {
                videoListListener?.onVideoListReceived(emptyList())
            }
            return
        }

        if (command.startsWith("REQ_IMG:")) {
            val requestedHash = command.removePrefix("REQ_IMG:").toIntOrNull()
            if (requestedHash == latestImageHash && latestImageData != null) {
                sendImmediateImagePacket(latestImageData!!, latestImageHash)
            }
            return
        }

        if (command.startsWith("PAGE:")) {
            val page = command.substring(5).toIntOrNull() ?: 0
            currentEspPage = page
            appContext?.let { ctx ->
                GpsManager.updatePageContext(page, ctx)
            }
        }

        when (command) {
            "P" -> {
                audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            }
            ">" -> {
                audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
                audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))
            }
            "<" -> {
                audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            }
        }
    }

    private fun sendImmediateImagePacket(imageBytes: ByteArray, hash: Int) {
        if (!isConnected || bluetoothGatt == null || isTransferringImmediate) return

        Thread {
            isTransferringImmediate = true
            val sessionId = currentMediaSession.incrementAndGet()
            val chunkSize = 244
            var offset = 0

            try {
                sendCommandRaw("START:$hash|${imageBytes.size}")
                Thread.sleep(40)

                while (offset < imageBytes.size) {
                    if (sessionId != currentMediaSession.get()) return@Thread

                    val end = Math.min(offset + chunkSize, imageBytes.size)
                    val chunk = imageBytes.copyOfRange(offset, end)

                    sendImageBytesRaw(chunk)

                    offset += chunkSize
                    Thread.sleep(18)
                }

                Thread.sleep(60)
                if (sessionId == currentMediaSession.get()) {
                    sendCommandRaw("ANI_DECODE")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Transfer Error", e)
            } finally {
                isTransferringImmediate = false
            }
        }.start()
    }

    fun requestVideoList() {
        sendCommandRaw("GET_VIDS")
    }

    fun selectRemoteVideo(filename: String) {
        sendCommandRaw("PLAY_VID:$filename")
    }

    fun sendMediaHash(hash: Int) {
        if (!isConnected || writeCharacteristic == null || currentEspPage != 0) return
        sendCommandRaw("H:$hash")
    }

    fun sendTimelinePacket(percent: Float) {
        if (!isConnected || writeCharacteristic == null || currentEspPage != 0) return
        val payload = String.format(Locale.US, "T:%.2f", percent)
        if (payload != prevTimeline) {
            sendCommandRaw(payload)
        }
        prevTimeline = payload
    }

    // GPS

    fun sendGpsPacket(speed: Int, limit: Int, cameraDist: Int) {
        if (!isConnected || writeCharacteristic == null) return

        // dont send data if nothing is updated
        if (speed == lastSentSpeed && limit == lastSentLimit && cameraDist == lastSentCamDist) return

        lastSentSpeed = speed
        lastSentLimit = limit
        lastSentCamDist = cameraDist

        sendCommandRaw("G:$speed|$limit|$cameraDist")
    }

    fun sendAlertPacket(type: String) {
        if (!isConnected || writeCharacteristic == null) return
        sendCommandRaw("A:$type")
    }

    fun sendNavPacket(turn: String, distance: String, street: String) {
        if (!isConnected || writeCharacteristic == null) return
        sendCommandRaw("N:$turn|$distance|$street")
    }

    fun sendBoostPacket(boostPsi: Float) {
        if (!isConnected || writeCharacteristic == null) return
        if (Math.abs(boostPsi - lastSentBoost) < 0.05f) return
        lastSentBoost = boostPsi
        val payload = String.format(Locale.US, "B:%.1f", boostPsi)
        sendCommandRaw(payload)
    }

    fun sendGForcePacket(gX: Float, gY: Float) {
        if (!isConnected || writeCharacteristic == null) return
        if (Math.abs(gX - lastSentGx) < 0.02f && Math.abs(gY - lastSentGy) < 0.02f) return
        lastSentGx = gX
        lastSentGy = gY
        val payload = String.format(Locale.US, "F:%.2f|%.2f", gX, gY)
        sendCommandRaw(payload)
    }

    fun sendCommandRaw(payload: String) {
        if (!isConnected || writeCharacteristic == null) return
        writeBytes(writeCharacteristic!!, payload.toByteArray())
    }

    fun sendImageBytesRaw(payload: ByteArray) {
        if (!isConnected || imageCharacteristic == null) return
        writeBytes(imageCharacteristic!!, payload)
    }

    private fun writeBytes(char: BluetoothGattCharacteristic, payload: ByteArray) {
        try {
            bluetoothGatt?.let { gatt ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(char, payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                } else {
                    @Suppress("DEPRECATION")
                    char.value = payload
                    @Suppress("DEPRECATION")
                    char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(char)
                }
            }
        } catch (e: Exception) { Log.e(TAG, "BLE Write FAILED", e) }
    }
}