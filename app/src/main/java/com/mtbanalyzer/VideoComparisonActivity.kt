package com.mtbanalyzer

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
        
        // Override seek bars to maintain offset when locked
        val seekBar1 = videoPlayer1.findViewById<SeekBar>(R.id.seekBar)
        seekBar1.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && isLocked) {
                    // Seek video2 with preserved offset
                    val video2Position = progress + positionOffset
                    if (video2Position >= 0 && video2Position <= videoPlayer2.getDuration()) {
                        videoPlayer2.seekTo(video2Position)
                    }
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                if (isLocked) {
                    pauseVideos()
                }
            }
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        val seekBar2 = videoPlayer2.findViewById<SeekBar>(R.id.seekBar)
        seekBar2.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && isLocked) {
                    // Seek video1 with preserved offset
                    val video1Position = progress - positionOffset
                    if (video1Position >= 0 && video1Position <= videoPlayer1.getDuration()) {
                        videoPlayer1.seekTo(video1Position)
                    }
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                if (isLocked) {
                    pauseVideos()
                }
            }
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
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
                    // Keep videos in sync
                    val position1 = videoPlayer1.getCurrentPosition()
                    val expectedPosition2 = position1 + positionOffset
                    val actualPosition2 = videoPlayer2.getCurrentPosition()
                    
                    // If videos are out of sync by more than 50ms, resync
                    if (kotlin.math.abs(expectedPosition2 - actualPosition2) > 50) {
                        if (expectedPosition2 >= 0 && expectedPosition2 <= videoPlayer2.getDuration()) {
                            videoPlayer2.seekTo(expectedPosition2)
                        }
                    }
                }
                mainHandler.postDelayed(this, 100)
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