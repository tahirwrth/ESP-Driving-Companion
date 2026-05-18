package com.wrth.esp

import android.media.session.MediaController

object MediaDataStore {
    var currentTitle: String = "Unknown"
    var currentArtist: String = "Unknown"
    var currentDuration: Long = 0L
    var currentArtBytes: ByteArray = ByteArray(0)
    var currentController: MediaController? = null
}