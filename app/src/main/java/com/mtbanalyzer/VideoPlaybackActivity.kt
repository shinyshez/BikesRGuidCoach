package com.mtbanalyzer

import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class VideoPlaybackActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "VideoPlaybackActivity"
        const val EXTRA_VIDEO_URI = "video_uri"
        const val EXTRA_VIDEO_NAME = "video_name"
    }
    
    private lateinit var videoPlayerView: VideoPlayerView
    private lateinit var videoTitle: TextView
    private lateinit var controlsContainer: View
    private lateinit var topControlsBar: View
    private lateinit var bottomControlsBar: View
    
    private var videoUri: Uri? = null
    private var videoName: String? = null
    
    // UI Control state
    private var controlsVisible = true
    private val hideControlsRunnable = Runnable { hideControls() }
    private val showControlsDelay = 3000L // Hide controls after 3 seconds
    private val mainHandler = Handler(Looper.getMainLooper())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enable full-screen immersive mode
        enableFullScreenMode()
        
        setContentView(R.layout.activity_video_playback)
        
        // Hide action bar for full-screen experience
        supportActionBar?.hide()
        
        initializeViews()
        setupVideoData()
        setupTouchListener()
        
        // Initialize controls visibility
        showControls()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
    
    private fun initializeViews() {
        videoPlayerView = findViewById(R.id.videoPlayerView)
        videoTitle = findViewById(R.id.videoTitle)
        controlsContainer = findViewById(R.id.controlsContainer)
        topControlsBar = findViewById(R.id.topControlsBar)
        bottomControlsBar = findViewById(R.id.bottomControlsBar)
    }
    
    private fun setupVideoData() {
        videoUri = intent.getStringExtra(EXTRA_VIDEO_URI)?.let { Uri.parse(it) }
        videoName = intent.getStringExtra(EXTRA_VIDEO_NAME) ?: "Video"
        
        if (videoUri == null) {
            Toast.makeText(this, "Error loading video", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        // Set video title
        videoTitle.text = videoName?.replace("MTB_", "")?.replace(".mp4", "") ?: "Video"
        
        // Setup video player
        videoPlayerView.setVideo(videoUri!!)
        
        // Set listeners
        videoPlayerView.setOnVideoLoadedListener { duration ->
            Log.d(TAG, "Video loaded with duration: $duration ms")
            
            // Override play/pause button after video is loaded
            setupPlayPauseOverride()
        }
        
        videoPlayerView.setOnVideoErrorListener { what, extra ->
            Toast.makeText(this, "Error playing video", Toast.LENGTH_SHORT).show()
        }
        
        videoPlayerView.setOnVideoCompletionListener {
            showControls()
        }
        
        videoPlayerView.setOnVideoTapListener {
            toggleControls()
        }
    }
    
    private fun enableFullScreenMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }
    
    private fun setupPlayPauseOverride() {
        // Override play/pause to manage controls visibility
        val originalPlayPauseButton = videoPlayerView.findViewById<ImageButton>(R.id.playPauseButton)
        originalPlayPauseButton?.setOnClickListener {
            if (videoPlayerView.isPlaying()) {
                videoPlayerView.pause()
                showControls()
                mainHandler.removeCallbacks(hideControlsRunnable)
            } else {
                videoPlayerView.play()
                scheduleControlsHide()
            }
        }
    }
    
    private fun setupTouchListener() {
        // Handle touches on the video title for showing/hiding controls
        videoTitle.setOnClickListener {
            toggleControls()
        }
    }
    
    private fun showControls() {
        if (!controlsVisible) {
            controlsVisible = true
            
            // Ensure controls are visible
            controlsContainer.visibility = View.VISIBLE
            
            // Bring controls to front
            controlsContainer.bringToFront()
            
            topControlsBar.visibility = View.VISIBLE
            bottomControlsBar.visibility = View.VISIBLE
            
            topControlsBar.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200)
                .start()
            
            bottomControlsBar.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200)
                .start()
        }
        scheduleControlsHide()
    }
    
    private fun hideControls() {
        if (controlsVisible) {
            controlsVisible = false
            
            topControlsBar.animate()
                .alpha(0f)
                .translationY(-topControlsBar.height.toFloat())
                .setDuration(200)
                .withEndAction {
                    topControlsBar.visibility = View.INVISIBLE
                }
                .start()
            
            bottomControlsBar.animate()
                .alpha(0f)
                .translationY(bottomControlsBar.height.toFloat())
                .setDuration(200)
                .withEndAction {
                    bottomControlsBar.visibility = View.INVISIBLE
                }
                .start()
        }
        mainHandler.removeCallbacks(hideControlsRunnable)
    }
    
    private fun scheduleControlsHide() {
        mainHandler.removeCallbacks(hideControlsRunnable)
        if (videoPlayerView.isPlaying()) {
            mainHandler.postDelayed(hideControlsRunnable, showControlsDelay)
        }
    }
    
    private fun toggleControls() {
        if (controlsVisible) {
            hideControls()
        } else {
            showControls()
        }
    }
    
    override fun onResume() {
        super.onResume()
        enableFullScreenMode()
    }
    
    override fun onPause() {
        super.onPause()
        videoPlayerView.pause()
        mainHandler.removeCallbacks(hideControlsRunnable)
    }
    
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "Orientation changed: ${newConfig.orientation}")
        
        // Re-enable full screen mode after orientation change
        enableFullScreenMode()
        
        // Trigger video re-layout
        videoPlayerView.handleOrientationChange(newConfig)
        
        // Show controls briefly after orientation change
        showControls()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        videoPlayerView.release()
    }
}