package com.wrth.esp

object MediaDataStore {
    var currentTitle: String = "Unknown"
    var currentArtist: String = "Unknown"
    var currentDuration: Long = 0L
    var currentPosition: Long = 0L
    var isPlaying: Boolean = false
    var currentArtBytes: ByteArray = ByteArray(0)
    var currentController: android.media.session.MediaController? = null
}