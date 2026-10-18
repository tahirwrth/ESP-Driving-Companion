package com.wrth.esp

import android.content.ComponentName
import android.graphics.*
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import java.io.ByteArrayOutputStream
import java.util.Locale
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale

class MediaListenerService : NotificationListenerService() {

    private lateinit var mediaSessionManager: MediaSessionManager
    private val TAG = "MediaListenerService"

    override fun onCreate() {
        super.onCreate()
        mediaSessionManager = getSystemService(MediaSessionManager::class.java)
        val componentName = ComponentName(this, MediaListenerService::class.java)
        mediaSessionManager.addOnActiveSessionsChangedListener(
            { controllers -> updateActiveController(controllers) },
            componentName
        )
        updateActiveController(mediaSessionManager.getActiveSessions(componentName))
    }

    // scrape maps notification from phone, catered for G Maps
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        val packageName = sbn.packageName

        if (packageName == "com.google.android.apps.maps") {
            val extras = sbn.notification.extras

            val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""

            if (title.isBlank() && text.isBlank()) return

            val fullStr = "$title $text".lowercase(Locale.ROOT)

            // alert tracking
            if (fullStr.contains("police") || fullStr.contains("camera") || fullStr.contains("traffic") || fullStr.contains("hazard")) {
                Log.w(TAG, "NAV: Police detected. Sending alert")
                BleManager.sendAlertPacket("POLICE")
                return
            }

            parseAndSendNavigationData(title, text, fullStr)
        }
    }

    // get and send navigation to esp32
    private fun parseAndSendNavigationData(title: String, text: String, fullStr: String) {
        // get distance
        var distance = title
        if (distance.isEmpty() || distance.lowercase(Locale.ROOT).contains("navigation")) {
            val distRegex = Regex("\\b(\\d+(?:\\.\\d+)?\\s*(m|km|ft|mi|yd|yds|yards|meters|miles))\\b", RegexOption.IGNORE_CASE)
            distance = distRegex.find(fullStr)?.value ?: "--"
        }

        // get roundabout exit
        val exitRegex = Regex("(\\d+)(?:st|nd|rd|th)\\s+exit", RegexOption.IGNORE_CASE)
        val exitNumber = exitRegex.find(fullStr)?.groupValues?.get(1) ?: ""

        // wip: get turn
        val turn = when {
            Regex("roundabout|traffic circle|\\bcircle\\b|◷|⨀").containsMatchIn(fullStr) -> "ROUND"
            Regex("u-turn|u turn|make a u|↶|↺|↻|↷").containsMatchIn(fullStr) -> "U-TURN"
            Regex("keep left|slight left|↖|⮨").containsMatchIn(fullStr) -> "KEEP L"
            Regex("keep right|slight right|↗|⮩").containsMatchIn(fullStr) -> "KEEP R"
            Regex("\\bleft\\b|←|↰|⟵").containsMatchIn(fullStr) -> "LEFT"
            Regex("\\bright\\b|→|↱|⟶").containsMatchIn(fullStr) -> "RIGHT"
            Regex("\\bexit\\b").containsMatchIn(fullStr) -> "KEEP L" // UK road specific (exit to the left)
            else -> "STRAIGHT"
        }

        // extracts street name from verbs/adjectives
        var street = text
        val removePrefixes = listOf("towards ", "turn left onto ", "turn right onto ", "keep left onto ", "keep right onto ", "onto ", "merge onto ")

        for (prefix in removePrefixes) {
            if (street.lowercase(Locale.ROOT).startsWith(prefix)) {
                street = street.substring(prefix.length)
                break
            }
        }

        street = street.split(" ").joinToString(" ") { it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString() } }
        street = street.replace(Regex("[^a-zA-Z0-9 ]"), "").trim().take(22)

        if (street.isBlank()) street = "Follow Route"

        Log.w(TAG, "NAV: Sending -> Turn: [$turn] Dist: [$distance] Street: [$street] Exit: [$exitNumber]")

        BleManager.sendAlertPacket("CLEAR")
        Thread.sleep(100)
        BleManager.sendNavPacket(turn, distance, "$street|$exitNumber")
    }

    private fun updateActiveController(controllers: List<MediaController>?) {
        val activeController = controllers?.firstOrNull() ?: return
        MediaDataStore.currentController = activeController
        activeController.registerCallback(mediaCallback)
        mediaCallback.onMetadataChanged(activeController.metadata)
        mediaCallback.onPlaybackStateChanged(activeController.playbackState)
    }

    // blur background of image
    private fun blurBitmap(bitmap: Bitmap, radius: Float): Bitmap {
        val outputBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
        val rs = RenderScript.create(this)
        val input = Allocation.createFromBitmap(rs, outputBitmap)
        val output = Allocation.createTyped(rs, input.type)
        val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))

        script.setRadius(radius.coerceIn(1f, 25f))
        script.setInput(input)
        script.forEach(output)
        output.copyTo(outputBitmap)

        input.destroy()
        output.destroy()
        script.destroy()
        rs.destroy()

        return outputBitmap
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            state?.let {
                MediaDataStore.currentPosition = it.position
                MediaDataStore.isPlaying = it.state == PlaybackState.STATE_PLAYING
            }
        }

        // when song is changed
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


                // creates whole image including song name, artist, album art, background for esp32 to display
                albumArt?.let { bitmap ->
                    val finalWidth = 360
                    val finalHeight = 360
                    val finalBitmap = createBitmap(finalWidth, finalHeight)
                    val canvas = Canvas(finalBitmap)

                    val bgBitmap = bitmap.scale(400, 400)
                    val blurredBg = blurBitmap(bgBitmap, 25f)
                    val bgLeft = (finalWidth - 400) / 2f
                    val bgTop = (finalHeight - 400) / 2f
                    val bgPaint = Paint().apply { alpha = 178 }
                    canvas.drawBitmap(blurredBg, bgLeft, bgTop, bgPaint)
                    canvas.drawColor(Color.argb(70, 0, 0, 0))

                    val coverSize = 180
                    val coverYPosition = 50f

                    val centerArt = bitmap.scale(coverSize, coverSize)
                    val centerLeft = (finalWidth - coverSize) / 2f
                    val centerTop = coverYPosition

                    val shadowPaint = Paint().apply {
                        setShadowLayer(15f, 0f, 8f, Color.BLACK)
                        isAntiAlias = true
                    }
                    canvas.drawRect(centerLeft, centerTop, centerLeft + coverSize, centerTop + coverSize, shadowPaint)
                    canvas.drawBitmap(centerArt, centerLeft, centerTop, null)

                    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        textSize = 24f
                        typeface = ResourcesCompat.getFont(this@MediaListenerService, R.font.sf_pro_display_medium)
                        textAlign = Paint.Align.CENTER
                        setShadowLayer(5f, 0f, 2f, Color.BLACK)
                    }

                    val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        textSize = 18f
                        typeface = ResourcesCompat.getFont(this@MediaListenerService, R.font.sf_pro_display_light)
                        textAlign = Paint.Align.CENTER
                        setShadowLayer(5f, 0f, 2f, Color.BLACK)
                    }

                    val textBaseY = coverYPosition + coverSize
                    val displayTitle = if (title.length > 25) title.take(22) + "..." else title
                    val displayArtist = if (artist.length > 30) artist.take(27) + "..." else artist

                    canvas.drawText(displayTitle, 180f, textBaseY + 40f, titlePaint)
                    canvas.drawText(displayArtist, 180f, textBaseY + 65f, artistPaint)

                    val stream = ByteArrayOutputStream()
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                    val jpgData = stream.toByteArray()
                    val currentHash = jpgData.contentHashCode()

                    MediaDataStore.currentArtBytes = jpgData

                    BleManager.latestImageData = jpgData
                    BleManager.latestImageHash = currentHash

                    BleManager.sendMediaHash(currentHash)

                    bgBitmap.recycle()
                    blurredBg.recycle()
                    centerArt.recycle()
                    finalBitmap.recycle()
                }
            }
        }
    }
}