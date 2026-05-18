package com.wrth.esp

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.util.Log
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.Timer
import java.util.TimerTask

class MediaListenerService : NotificationListenerService() {

    private val TAG = "MediaListenerService"
    private lateinit var mediaSessionManager: MediaSessionManager
    private var currentController: MediaController? = null

    // Timer for our 1-second ticks
    private var timelineTimer: Timer? = null
    private var currentTrackDuration: Long = 0L

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            metadata?.let {
                val title = it.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown"
                val artist = it.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown"
                val albumArt = it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)

                currentTrackDuration = it.getLong(MediaMetadata.METADATA_KEY_DURATION)

                prepareDataForESP32(title, artist, albumArt)
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "LISTENER CONNECTED TO OS")
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val componentName = ComponentName(this, MediaListenerService::class.java)

        // listen for seek command from Bluetooth Manager
        BluetoothSerialManager.onSeekCommandReceived = { percentage ->
            if (currentTrackDuration > 0) {
                val targetPositionMs = (currentTrackDuration * percentage) / 100
                currentController?.transportControls?.seekTo(targetPositionMs)
                Log.d(TAG, "Seeking to $percentage% ($targetPositionMs ms)")
            }
        }

        try {
            val activeSessions = mediaSessionManager.getActiveSessions(componentName)
            if (activeSessions.isNotEmpty()) {
                Log.d(TAG, "FOUND ACTIVE MEDIA: ${activeSessions[0].packageName}")
                attachToController(activeSessions[0])
            } else {
                Log.d(TAG, "NO ACTIVE MEDIA FOUND ON STARTUP")
            }

            mediaSessionManager.addOnActiveSessionsChangedListener({ controllers ->
                if (!controllers.isNullOrEmpty()) attachToController(controllers[0])
            }, componentName)
        } catch (e: SecurityException) {}
    }

    private fun attachToController(controller: MediaController) {
        currentController?.unregisterCallback(mediaCallback)
        currentController = controller
        currentController?.registerCallback(mediaCallback)

        mediaCallback.onMetadataChanged(controller.metadata)

        // start 1-second ticker when new app takes over
        startTimelineTracker()
    }

    private fun startTimelineTracker() {
        timelineTimer?.cancel()
        timelineTimer = Timer()
        timelineTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                Log.d(TAG, "Timer Tick")

                currentController?.let { controller ->
                    val state = controller.playbackState
                    if (state != null) {
                        Log.d(TAG, "Sending Timeline Data. Duration: $currentTrackDuration")
                        val position = state.position
                        val isPlaying = state.state == PlaybackState.STATE_PLAYING
                        BluetoothSerialManager.sendTimelinePacket(position, currentTrackDuration, isPlaying)
                    } else {
                        Log.d(TAG, "PlaybackState is NULL")
                    }
                }
            }
        }, 0, 1000)
    }

    private fun prepareDataForESP32(title: String, artist: String, originalBitmap: Bitmap?) {
        var imageBytes = ByteArray(0)
        originalBitmap?.let {
            val scaledBitmap = Bitmap.createScaledBitmap(it, 200, 200, true)
            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
            imageBytes = outputStream.toByteArray()
        }
        BluetoothSerialManager.sendMediaPacket(title, artist, imageBytes)
    }

    override fun onDestroy() {
        super.onDestroy()
        timelineTimer?.cancel()
    }
}