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

class VideoComparisonActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "VideoComparison"
        const val EXTRA_VIDEO1_URI = "video1_uri"
        const val EXTRA_VIDEO1_NAME = "video1_name"
        const val EXTRA_VIDEO2_URI = "video2_uri"
        const val EXTRA_VIDEO2_NAME = "video2_name"
    }
    
    private lateinit var videoPlayer1: VideoPlayerView
    private lateinit var videoPlayer2: VideoPlayerView
    private lateinit var video1Title: TextView
    private lateinit var video2Title: TextView
    private lateinit var playBothButton: Button
    private lateinit var pauseBothButton: Button
    private lateinit var lockButton: Button
    private lateinit var layoutToggleButton: ImageButton
    
    private var video1Uri: Uri? = null
    private var video2Uri: Uri? = null
    private var isSideBySide = true
    private var isLocked = false
    private var positionOffset = 0 // Video2 position - Video1 position
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private var syncRunnable: Runnable? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_comparison)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Video Comparison"
        
        initializeViews()
        setupVideoData()
        setupControls()
        
        // Set initial layout orientation
        updateLayoutOrientation(resources.configuration.orientation)
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
    
    private fun initializeViews() {
        videoPlayer1 = findViewById(R.id.videoPlayer1)
        videoPlayer2 = findViewById(R.id.videoPlayer2)
        video1Title = findViewById(R.id.video1Title)
        video2Title = findViewById(R.id.video2Title)
        
        // Global controls
        playBothButton = findViewById(R.id.playBothButton)
        pauseBothButton = findViewById(R.id.pauseBothButton)
        lockButton = findViewById(R.id.lockButton)
        layoutToggleButton = findViewById(R.id.layoutToggleButton)
    }
    
    private fun setupVideoData() {
        video1Uri = intent.getStringExtra(EXTRA_VIDEO1_URI)?.let { Uri.parse(it) }
        video2Uri = intent.getStringExtra(EXTRA_VIDEO2_URI)?.let { Uri.parse(it) }
        
        video1Title.text = intent.getStringExtra(EXTRA_VIDEO1_NAME) ?: "Video 1"
        video2Title.text = intent.getStringExtra(EXTRA_VIDEO2_NAME) ?: "Video 2"
        
        if (video1Uri == null || video2Uri == null) {
            Toast.makeText(this, "Error loading videos", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        setupVideoPlayers()
    }
    
    private fun setupVideoPlayers() {
        try {
            // Setup video 1
            videoPlayer1.setVideo(video1Uri!!)
            videoPlayer1.setOnVideoLoadedListener { duration ->
                Log.d(TAG, "Video 1 loaded with duration: $duration ms")
            }
            videoPlayer1.setOnVideoErrorListener { what, extra ->
                Log.e(TAG, "Video1 error: what=$what, extra=$extra")
                Toast.makeText(this, "Error playing video 1", Toast.LENGTH_SHORT).show()
            }
            
            // Setup video 2
            videoPlayer2.setVideo(video2Uri!!)
            videoPlayer2.setOnVideoLoadedListener { duration ->
                Log.d(TAG, "Video 2 loaded with duration: $duration ms")
            }
            videoPlayer2.setOnVideoErrorListener { what, extra ->
                Log.e(TAG, "Video2 error: what=$what, extra=$extra")
                Toast.makeText(this, "Error playing video 2", Toast.LENGTH_SHORT).show()
            }
            
            // Override individual play/pause buttons to respect lock state
            setupIndividualControls()
            
            // Setup seek synchronization for locked mode
            setupSeekSynchronization()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up videos", e)
            Toast.makeText(this, "Error setting up videos: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun setupIndividualControls() {
        // Override play/pause button for video 1
        val playPause1 = videoPlayer1.findViewById<ImageButton>(R.id.playPauseButton)
        playPause1.setOnClickListener {
            if (isLocked) {
                // When locked, control both videos
                if (videoPlayer1.isPlaying() || videoPlayer2.isPlaying()) {
                    pauseVideos()
                } else {
                    playVideos()
                }
            } else {
                if (videoPlayer1.isPlaying()) {
                    videoPlayer1.pause()
                } else {
                    videoPlayer1.play()
                }
            }
        }
        
        // Override play/pause button for video 2
        val playPause2 = videoPlayer2.findViewById<ImageButton>(R.id.playPauseButton)
        playPause2.setOnClickListener {
            if (isLocked) {
                // When locked, control both videos
                if (videoPlayer1.isPlaying() || videoPlayer2.isPlaying()) {
                    pauseVideos()
                } else {
                    playVideos()
                }
            } else {
                if (videoPlayer2.isPlaying()) {
                    videoPlayer2.pause()
                } else {
                    videoPlayer2.play()
                }
            }
        }
    }
    
    private fun setupSeekSynchronization() {
        // Setup seek callbacks for synchronized seeking when locked
        videoPlayer1.setOnSeekListener { position, fromUser ->
            if (fromUser && isLocked) {
                // Seek video2 with preserved offset
                val video2Position = position + positionOffset
                if (video2Position >= 0 && video2Position <= videoPlayer2.getDuration()) {
                    videoPlayer2.seekTo(video2Position)
                }
                // Pause both videos during seeking for better sync
                pauseVideos()
            }
        }
        
        videoPlayer2.setOnSeekListener { position, fromUser ->
            if (fromUser && isLocked) {
                // Seek video1 with preserved offset  
                val video1Position = position - positionOffset
                if (video1Position >= 0 && video1Position <= videoPlayer1.getDuration()) {
                    videoPlayer1.seekTo(video1Position)
                }
                // Pause both videos during seeking for better sync
                pauseVideos()
            }
        }
    }
    
    private fun setupControls() {
        // Global controls
        playBothButton.setOnClickListener {
            playVideos()
        }
        
        pauseBothButton.setOnClickListener {
            pauseVideos()
        }
        
        lockButton.setOnClickListener {
            toggleLock()
        }
        
        layoutToggleButton.setOnClickListener {
            toggleLayout()
        }
    }
    
    private fun playVideos() {
        videoPlayer1.play()
        videoPlayer2.play()
        
        if (isLocked) {
            startSyncRunnable()
        }
    }
    
    private fun pauseVideos() {
        videoPlayer1.pause()
        videoPlayer2.pause()
        stopSyncRunnable()
    }
    
    private fun toggleLock() {
        isLocked = !isLocked
        
        if (isLocked) {
            // Calculate current offset when locking
            val position1 = videoPlayer1.getCurrentPosition()
            val position2 = videoPlayer2.getCurrentPosition()
            positionOffset = position2 - position1
            
            lockButton.text = "Unlock"
            lockButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF44AA44.toInt()) // Green
            Toast.makeText(this, "Videos locked with offset: ${positionOffset}ms", Toast.LENGTH_SHORT).show()
            
            Log.d(TAG, "Locked videos with offset: $positionOffset")
            
            if (videoPlayer1.isPlaying() || videoPlayer2.isPlaying()) {
                startSyncRunnable()
            }
        } else {
            lockButton.text = "Lock"
            lockButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF4444FF.toInt()) // Blue
            Toast.makeText(this, "Videos unlocked - independent control", Toast.LENGTH_SHORT).show()
            
            Log.d(TAG, "Unlocked videos")
            stopSyncRunnable()
        }
    }
    
    private fun startSyncRunnable() {
        stopSyncRunnable()
        
        syncRunnable = object : Runnable {
            override fun run() {
                if (isLocked && (videoPlayer1.isPlaying() || videoPlayer2.isPlaying())) {
                    // Keep videos in sync - but only check, don't aggressively seek
                    val position1 = videoPlayer1.getCurrentPosition()
                    val expectedPosition2 = position1 + positionOffset
                    val actualPosition2 = videoPlayer2.getCurrentPosition()
                    
                    // Only resync if videos are significantly out of sync (200ms+)
                    // This reduces stuttering from constant seeking
                    val syncDrift = kotlin.math.abs(expectedPosition2 - actualPosition2)
                    if (syncDrift > 200) {
                        Log.d(TAG, "Videos out of sync by ${syncDrift}ms, resyncing")
                        if (expectedPosition2 >= 0 && expectedPosition2 <= videoPlayer2.getDuration()) {
                            videoPlayer2.seekTo(expectedPosition2)
                        }
                    }
                }
                // Check sync less frequently to reduce performance impact
                mainHandler.postDelayed(this, 300)
            }
        }
        mainHandler.post(syncRunnable!!)
    }
    
    private fun stopSyncRunnable() {
        syncRunnable?.let {
            mainHandler.removeCallbacks(it)
        }
        syncRunnable = null
    }
    
    private fun toggleLayout() {
        val container = findViewById<View>(R.id.videoContainer)
        if (isSideBySide) {
            // Switch to top/bottom layout
            Toast.makeText(this, "Layout toggle - implement constraint changes", Toast.LENGTH_SHORT).show()
            layoutToggleButton.setImageResource(android.R.drawable.ic_menu_sort_by_size)
            isSideBySide = false
        } else {
            // Switch back to side-by-side
            layoutToggleButton.setImageResource(android.R.drawable.ic_menu_view)
            isSideBySide = true
        }
    }
    
    private fun updateLayoutOrientation(orientation: Int) {
        val videoContainer = findViewById<LinearLayout>(R.id.videoContainer)
        val divider = findViewById<View>(R.id.divider)
        val video1Section = findViewById<LinearLayout>(R.id.video1Section)
        val video2Section = findViewById<LinearLayout>(R.id.video2Section)
        
        when (orientation) {
            Configuration.ORIENTATION_PORTRAIT -> {
                // Stack videos vertically in portrait
                videoContainer.orientation = LinearLayout.VERTICAL
                
                // Update video section layout params for vertical stacking
                val video1Params = video1Section.layoutParams as LinearLayout.LayoutParams
                video1Params.width = LinearLayout.LayoutParams.MATCH_PARENT
                video1Params.height = 0
                video1Params.weight = 1f
                video1Section.layoutParams = video1Params
                
                val video2Params = video2Section.layoutParams as LinearLayout.LayoutParams
                video2Params.width = LinearLayout.LayoutParams.MATCH_PARENT
                video2Params.height = 0
                video2Params.weight = 1f
                video2Section.layoutParams = video2Params
                
                // Update divider for horizontal line
                val dividerParams = divider.layoutParams as LinearLayout.LayoutParams
                dividerParams.width = LinearLayout.LayoutParams.MATCH_PARENT
                dividerParams.height = 2
                dividerParams.weight = 0f
                divider.layoutParams = dividerParams
                
                Log.d(TAG, "Layout updated to vertical (portrait)")
            }
            
            Configuration.ORIENTATION_LANDSCAPE -> {
                // Place videos side-by-side in landscape
                videoContainer.orientation = LinearLayout.HORIZONTAL
                
                // Update video section layout params for horizontal placement
                val video1Params = video1Section.layoutParams as LinearLayout.LayoutParams
                video1Params.width = 0
                video1Params.height = LinearLayout.LayoutParams.MATCH_PARENT
                video1Params.weight = 1f
                video1Section.layoutParams = video1Params
                
                val video2Params = video2Section.layoutParams as LinearLayout.LayoutParams
                video2Params.width = 0
                video2Params.height = LinearLayout.LayoutParams.MATCH_PARENT
                video2Params.weight = 1f
                video2Section.layoutParams = video2Params
                
                // Update divider for vertical line
                val dividerParams = divider.layoutParams as LinearLayout.LayoutParams
                dividerParams.width = 2
                dividerParams.height = LinearLayout.LayoutParams.MATCH_PARENT
                dividerParams.weight = 0f
                divider.layoutParams = dividerParams
                
                Log.d(TAG, "Layout updated to horizontal (landscape)")
            }
        }
    }
    
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "Orientation changed: ${newConfig.orientation}")
        
        // Update layout orientation based on device orientation
        updateLayoutOrientation(newConfig.orientation)
        
        // Trigger video re-layout for both players
        videoPlayer1.handleOrientationChange(newConfig)
        videoPlayer2.handleOrientationChange(newConfig)
    }
    
    override fun onPause() {
        super.onPause()
        pauseVideos()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopSyncRunnable()
        try {
            videoPlayer1.release()
            videoPlayer2.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing videos", e)
        }
    }
}