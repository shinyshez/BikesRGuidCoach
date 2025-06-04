package com.mtbanalyzer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.widget.VideoView
import android.widget.MediaController
import android.widget.Button
import android.widget.TextView
import android.widget.ImageButton
import android.widget.SeekBar

class VideoComparisonActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "VideoComparison"
        const val EXTRA_VIDEO1_URI = "video1_uri"
        const val EXTRA_VIDEO1_NAME = "video1_name"
        const val EXTRA_VIDEO2_URI = "video2_uri"
        const val EXTRA_VIDEO2_NAME = "video2_name"
    }
    
    private lateinit var videoView1: VideoView
    private lateinit var videoView2: VideoView
    private lateinit var video1Title: TextView
    private lateinit var video2Title: TextView
    private lateinit var playBothButton: Button
    private lateinit var pauseBothButton: Button
    private lateinit var lockButton: Button
    private lateinit var layoutToggleButton: ImageButton
    
    // Individual controls for Video 1
    private lateinit var seekBar1: SeekBar
    private lateinit var playPause1: Button
    private lateinit var timeDisplay1: TextView
    
    // Individual controls for Video 2
    private lateinit var seekBar2: SeekBar
    private lateinit var playPause2: Button
    private lateinit var timeDisplay2: TextView
    
    private var video1Uri: Uri? = null
    private var video2Uri: Uri? = null
    private var isVideo1Playing = false
    private var isVideo2Playing = false
    private var isSideBySide = true
    private var video1Duration = 0
    private var video2Duration = 0
    private var isLocked = false
    private var positionOffset = 0 // Video2 position - Video1 position
    
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
        videoView1 = findViewById(R.id.videoView1)
        videoView2 = findViewById(R.id.videoView2)
        video1Title = findViewById(R.id.video1Title)
        video2Title = findViewById(R.id.video2Title)
        
        // Global controls
        playBothButton = findViewById(R.id.playBothButton)
        pauseBothButton = findViewById(R.id.pauseBothButton)
        lockButton = findViewById(R.id.lockButton)
        layoutToggleButton = findViewById(R.id.layoutToggleButton)
        
        // Individual controls for Video 1
        seekBar1 = findViewById(R.id.seekBar1)
        playPause1 = findViewById(R.id.playPause1)
        timeDisplay1 = findViewById(R.id.timeDisplay1)
        
        // Individual controls for Video 2
        seekBar2 = findViewById(R.id.seekBar2)
        playPause2 = findViewById(R.id.playPause2)
        timeDisplay2 = findViewById(R.id.timeDisplay2)
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
        
        setupVideoViews()
    }
    
    private fun setupVideoViews() {
        try {
            // Remove default media controls - we'll provide our own
            videoView1.setMediaController(null)
            videoView2.setMediaController(null)
            
            // Set video URIs
            videoView1.setVideoURI(video1Uri)
            videoView2.setVideoURI(video2Uri)
            
            // Setup listeners for video preparation
            videoView1.setOnPreparedListener { mediaPlayer ->
                video1Duration = mediaPlayer.duration
                seekBar1.max = video1Duration
                updateTimeDisplay1(0)
            }
            
            videoView2.setOnPreparedListener { mediaPlayer ->
                video2Duration = mediaPlayer.duration
                seekBar2.max = video2Duration
                updateTimeDisplay2(0)
            }
            
            // Error handling
            videoView1.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Video1 error: what=$what, extra=$extra")
                Toast.makeText(this, "Error playing video 1", Toast.LENGTH_SHORT).show()
                true
            }
            
            videoView2.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Video2 error: what=$what, extra=$extra")
                Toast.makeText(this, "Error playing video 2", Toast.LENGTH_SHORT).show()
                true
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up videos", e)
            Toast.makeText(this, "Error setting up videos: ${e.message}", Toast.LENGTH_LONG).show()
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
        
        // Individual controls for Video 1
        playPause1.setOnClickListener {
            if (isLocked) {
                // When locked, control both videos
                if (isVideo1Playing || isVideo2Playing) {
                    pauseVideos()
                } else {
                    playVideos()
                }
            } else {
                if (isVideo1Playing) {
                    pauseVideo1()
                } else {
                    playVideo1()
                }
            }
        }
        
        seekBar1.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    videoView1.seekTo(progress)
                    updateTimeDisplay1(progress)
                    
                    if (isLocked) {
                        // Seek video2 with preserved offset
                        val video2Position = progress + positionOffset
                        if (video2Position >= 0 && video2Position <= video2Duration) {
                            videoView2.seekTo(video2Position)
                            seekBar2.progress = video2Position
                            updateTimeDisplay2(video2Position)
                        }
                    }
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                if (isLocked) {
                    pauseVideos()
                } else {
                    pauseVideo1()
                }
            }
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // User can choose to play or stay paused
            }
        })
        
        // Individual controls for Video 2
        playPause2.setOnClickListener {
            if (isLocked) {
                // When locked, control both videos
                if (isVideo1Playing || isVideo2Playing) {
                    pauseVideos()
                } else {
                    playVideos()
                }
            } else {
                if (isVideo2Playing) {
                    pauseVideo2()
                } else {
                    playVideo2()
                }
            }
        }
        
        seekBar2.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    videoView2.seekTo(progress)
                    updateTimeDisplay2(progress)
                    
                    if (isLocked) {
                        // Seek video1 with preserved offset
                        val video1Position = progress - positionOffset
                        if (video1Position >= 0 && video1Position <= video1Duration) {
                            videoView1.seekTo(video1Position)
                            seekBar1.progress = video1Position
                            updateTimeDisplay1(video1Position)
                        }
                    }
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                if (isLocked) {
                    pauseVideos()
                } else {
                    pauseVideo2()
                }
            }
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // User can choose to play or stay paused
            }
        })
        
        // Update seek bars during playback
        startSeekBarUpdater()
    }
    
    private fun playVideos() {
        playVideo1()
        playVideo2()
    }
    
    private fun pauseVideos() {
        pauseVideo1()
        pauseVideo2()
    }
    
    private fun playVideo1() {
        try {
            videoView1.start()
            isVideo1Playing = true
            playPause1.text = "Pause"
        } catch (e: Exception) {
            Log.e(TAG, "Error starting video 1", e)
            Toast.makeText(this, "Error starting video 1", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun pauseVideo1() {
        try {
            if (videoView1.isPlaying) videoView1.pause()
            isVideo1Playing = false
            playPause1.text = "Play"
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing video 1", e)
        }
    }
    
    private fun playVideo2() {
        try {
            videoView2.start()
            isVideo2Playing = true
            playPause2.text = "Pause"
        } catch (e: Exception) {
            Log.e(TAG, "Error starting video 2", e)
            Toast.makeText(this, "Error starting video 2", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun pauseVideo2() {
        try {
            if (videoView2.isPlaying) videoView2.pause()
            isVideo2Playing = false
            playPause2.text = "Play"
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing video 2", e)
        }
    }
    
    private fun toggleLock() {
        isLocked = !isLocked
        
        if (isLocked) {
            // Calculate current offset when locking
            val position1 = videoView1.currentPosition
            val position2 = videoView2.currentPosition
            positionOffset = position2 - position1
            
            lockButton.text = "Unlock"
            lockButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF44AA44.toInt()) // Green
            Toast.makeText(this, "Videos locked with offset: ${positionOffset}ms", Toast.LENGTH_SHORT).show()
            
            Log.d(TAG, "Locked videos with offset: $positionOffset")
        } else {
            lockButton.text = "Lock"
            lockButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF4444FF.toInt()) // Blue
            Toast.makeText(this, "Videos unlocked - independent control", Toast.LENGTH_SHORT).show()
            
            Log.d(TAG, "Unlocked videos")
        }
    }
    
    private fun toggleLayout() {
        val container = findViewById<View>(R.id.videoContainer)
        if (isSideBySide) {
            // Switch to top/bottom layout
            container.layoutParams.apply {
                if (this is androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) {
                    // This would require updating the layout constraints
                    // For now, just show a toast
                    Toast.makeText(this@VideoComparisonActivity, "Layout toggle - implement constraint changes", Toast.LENGTH_SHORT).show()
                }
            }
            layoutToggleButton.setImageResource(android.R.drawable.ic_menu_sort_by_size)
            isSideBySide = false
        } else {
            // Switch back to side-by-side
            layoutToggleButton.setImageResource(android.R.drawable.ic_menu_view)
            isSideBySide = true
        }
    }
    
    private fun updateTimeDisplay1(position: Int) {
        val currentSec = position / 1000
        val totalSec = video1Duration / 1000
        val currentMin = currentSec / 60
        val currentSecRem = currentSec % 60
        val totalMin = totalSec / 60
        val totalSecRem = totalSec % 60
        
        timeDisplay1.text = String.format("%d:%02d", currentMin, currentSecRem)
    }
    
    private fun updateTimeDisplay2(position: Int) {
        val currentSec = position / 1000
        val totalSec = video2Duration / 1000
        val currentMin = currentSec / 60
        val currentSecRem = currentSec % 60
        val totalMin = totalSec / 60
        val totalSecRem = totalSec % 60
        
        timeDisplay2.text = String.format("%d:%02d", currentMin, currentSecRem)
    }
    
    private fun startSeekBarUpdater() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val updateRunnable = object : Runnable {
            override fun run() {
                // Update Video 1 controls
                if (isVideo1Playing && videoView1.isPlaying) {
                    val currentPosition1 = videoView1.currentPosition
                    seekBar1.progress = currentPosition1
                    updateTimeDisplay1(currentPosition1)
                }
                
                // Update Video 2 controls
                if (isVideo2Playing && videoView2.isPlaying) {
                    val currentPosition2 = videoView2.currentPosition
                    seekBar2.progress = currentPosition2
                    updateTimeDisplay2(currentPosition2)
                }
                
                handler.postDelayed(this, 100) // Update every 100ms
            }
        }
        handler.post(updateRunnable)
    }
    
    override fun onPause() {
        super.onPause()
        pauseVideos()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            videoView1.stopPlayback()
            videoView2.stopPlayback()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping videos", e)
        }
    }
}