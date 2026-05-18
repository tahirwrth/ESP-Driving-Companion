package com.wrth.esp

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import androidx.core.graphics.scale
import androidx.core.graphics.createBitmap

class MediaListenerService : NotificationListenerService() {

    private val TAG = "MediaListenerService"
    private lateinit var mediaSessionManager: MediaSessionManager
    private var currentController: MediaController? = null

//    private val mediaCallback = object : MediaController.Callback() {
//        override fun onMetadataChanged(metadata: MediaMetadata?) {
//            metadata?.let {
//                val title = it.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown"
//                val artist = it.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown"
//                val albumArt = it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
//                    ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
//                val duration = it.getLong(MediaMetadata.METADATA_KEY_DURATION)
//
//                MediaDataStore.currentTitle = title
//                MediaDataStore.currentArtist = artist
//                MediaDataStore.currentDuration = duration
//
//                var imageBytes = ByteArray(0)
//                albumArt?.let { bitmap ->
//                    val scaled = bitmap.scale(200, 200)
//                    val out = ByteArrayOutputStream()
//                    scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
//                    imageBytes = out.toByteArray()
//                }
//                MediaDataStore.currentArtBytes = imageBytes
//            }
//        }

    private val mediaCallback = object : MediaController.Callback() {
        private val mediaCallback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                metadata?.let {
                    val title = it.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown"
                    val artist = it.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown"
                    val albumArt = it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    val duration = it.getLong(MediaMetadata.METADATA_KEY_DURATION)

                    MediaDataStore.currentTitle = title
                    MediaDataStore.currentArtist = artist
                    MediaDataStore.currentDuration = duration

                    var imageBytes = ByteArray(0)
                    albumArt?.let { bitmap ->
                        // canvas matching ESP screen resolution
                        val finalWidth = 360
                        val finalHeight = 360
                        val finalBitmap = createBitmap(finalWidth, finalHeight)
                        val canvas = Canvas(finalBitmap)

                        // background blur
                        val tinyBg = bitmap.scale(32, 32)
                        val largeBlurredBg = tinyBg.scale(400, 400)

                        val bgLeft = (finalWidth - 400) / 2f
                        val bgTop = (finalHeight - 400) / 2f
                        val bgPaint = Paint().apply { alpha = 178 }
                        canvas.drawBitmap(largeBlurredBg, bgLeft, bgTop, bgPaint)
                        canvas.drawColor(Color.argb(70, 0, 0, 0)) // darken overlay

                        // adjustable UI vars
                        val coverSize = 140
                        val coverYPosition = 50f

                        // init cover art
                        val centerArt = bitmap.scale(coverSize, coverSize)
                        val centerLeft = (finalWidth - coverSize) / 2f
                        val centerTop = coverYPosition

                        // shadow
                        val shadowPaint = Paint().apply {
                            setShadowLayer(15f, 0f, 8f, Color.BLACK)
                            isAntiAlias = true
                        }
                        canvas.drawRect(
                            centerLeft,
                            centerTop,
                            centerLeft + coverSize,
                            centerTop + coverSize,
                            shadowPaint
                        )

                        // draw cover art
                        canvas.drawBitmap(centerArt, centerLeft, centerTop, null)

                        // compress to jpeg as final
                        val out = ByteArrayOutputStream()
                        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
                        imageBytes = out.toByteArray()
                    }

                    MediaDataStore.currentArtBytes = imageBytes
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "LISTENER CONNECTED TO OS")
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val componentName = ComponentName(this, MediaListenerService::class.java)

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

        MediaDataStore.currentController = controller
        mediaCallback.onMetadataChanged(controller.metadata)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}