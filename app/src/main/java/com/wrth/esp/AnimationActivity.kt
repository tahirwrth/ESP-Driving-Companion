package com.wrth.esp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

class AnimationActivity : AppCompatActivity(), BleManager.VideoListListener {

    private lateinit var ivGifPreview: ImageView
    private lateinit var btnPickGif: Button
    private lateinit var lvVideoList: ListView
    private lateinit var videoListAdapter: ArrayAdapter<String>
    private val discoveredVideos = mutableListOf<String>()

    private var selectedGifUri: Uri? = null

    private val pickGifLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val uri = result.data?.data
            if (uri != null) {
                selectedGifUri = uri
                displaySelectedGif(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = android.widget.ScrollView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        val rootLayout = androidx.appcompat.widget.LinearLayoutCompat(this).apply {
            orientation = androidx.appcompat.widget.LinearLayoutCompat.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(48, 48, 48, 48)
        }

        ivGifPreview = ImageView(this).apply {
            layoutParams = androidx.appcompat.widget.LinearLayoutCompat.LayoutParams(400, 400).apply {
                gravity = android.view.Gravity.CENTER
                setMargins(0, 0, 0, 32)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(android.graphics.Color.parseColor("#111111"))
        }
        rootLayout.addView(ivGifPreview)

        btnPickGif = Button(this).apply {
            text = "Select GIF File"
            layoutParams = androidx.appcompat.widget.LinearLayoutCompat.LayoutParams(
                androidx.appcompat.widget.LinearLayoutCompat.LayoutParams.MATCH_PARENT,
                androidx.appcompat.widget.LinearLayoutCompat.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }
            setOnClickListener { openGifPicker() }
        }
        rootLayout.addView(btnPickGif)


        val tvListLabel = TextView(this).apply {
            text = "Stored SD Animations (Tap to Play):"
            setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            textSize = 14f
            layoutParams = androidx.appcompat.widget.LinearLayoutCompat.LayoutParams(
                androidx.appcompat.widget.LinearLayoutCompat.LayoutParams.MATCH_PARENT,
                androidx.appcompat.widget.LinearLayoutCompat.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }
        }
        rootLayout.addView(tvListLabel)

        lvVideoList = ListView(this).apply {
            layoutParams = androidx.appcompat.widget.LinearLayoutCompat.LayoutParams(
                androidx.appcompat.widget.LinearLayoutCompat.LayoutParams.MATCH_PARENT,
                400
            )
            setBackgroundColor(android.graphics.Color.parseColor("#151515"))
        }
        rootLayout.addView(lvVideoList)

        videoListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, discoveredVideos)
        lvVideoList.adapter = videoListAdapter

        lvVideoList.setOnItemClickListener { _, _, position, _ ->
            val clickedVideoName = discoveredVideos[position]
            Toast.makeText(this, "Commanding Remote Play: $clickedVideoName", Toast.LENGTH_SHORT).show()
            BleManager.selectRemoteVideo(clickedVideoName)
        }

        scrollView.addView(rootLayout)
        setContentView(scrollView)
    }

    override fun onResume() {
        super.onResume()
        BleManager.setVideoListListener(this)
        if (BleManager.isConnected) {
            BleManager.requestVideoList()
        }
    }

    override fun onPause() {
        super.onPause()
        BleManager.setVideoListListener(null)
    }

    override fun onVideoListReceived(videos: List<String>) {
        runOnUiThread {
            discoveredVideos.clear()
            discoveredVideos.addAll(videos)
            videoListAdapter.notifyDataSetChanged()
        }
    }

    private fun openGifPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/gif"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        pickGifLauncher.launch(Intent.createChooser(intent, "Select Performance Animation"))
    }

    private fun displaySelectedGif(uri: Uri) {
        Glide.with(this)
            .asGif()
            .load(uri)
            .into(ivGifPreview)
    }
}