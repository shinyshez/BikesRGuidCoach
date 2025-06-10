package com.mtbanalyzer

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlinx.coroutines.*
import java.util.concurrent.Executors

class VideoPlaybackActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "VideoPlaybackActivity"
        const val EXTRA_VIDEO_URI = "video_uri"
        const val EXTRA_VIDEO_NAME = "video_name"
    }
    
    private lateinit var videoView: VideoView
    private lateinit var graphicOverlay: GraphicOverlay
    private lateinit var playPauseButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var timeDisplay: TextView
    private lateinit var poseToggleButton: Button
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var videoTitle: TextView
    private lateinit var controlsContainer: View
    private lateinit var topControlsBar: View
    private lateinit var bottomControlsBar: View
    
    private var videoUri: Uri? = null
    private var videoName: String? = null
    private var isPlaying = false
    private var videoDuration = 0
    private var isPoseDetectionEnabled = false
    
    private lateinit var poseDetector: PoseDetector
    private val processingExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var processingJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    
    // Frame extraction
    private var mediaRetriever: MediaMetadataRetriever? = null
    private var lastProcessedTime = -1L
    private val frameProcessingInterval = 100L // Process every 100ms
    
    // UI Control state
    private var controlsVisible = true
    private val hideControlsRunnable = Runnable { hideControls() }
    private val showControlsDelay = 3000L // Hide controls after 3 seconds
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enable full-screen immersive mode
        enableFullScreenMode()
        
        setContentView(R.layout.activity_video_playback)
        
        // Hide action bar for full-screen experience
        supportActionBar?.hide()
        
        initializeViews()
        setupVideoData()
        initializePoseDetector()
        setupControls()
        setupTouchListener()
        
        // Initialize controls visibility
        showControls()
    }
    
    private fun centerVideoView(mediaPlayer: MediaPlayer) {
        val videoWidth = mediaPlayer.videoWidth
        val videoHeight = mediaPlayer.videoHeight
        
        if (videoWidth == 0 || videoHeight == 0) {
            Log.w(TAG, "Video dimensions are zero, cannot center")
            return
        }
        
        // Get screen dimensions
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        // Calculate video aspect ratio
        val videoAspectRatio = videoWidth.toFloat() / videoHeight.toFloat()
        val screenAspectRatio = screenWidth.toFloat() / screenHeight.toFloat()
        
        val layoutParams = videoView.layoutParams as ConstraintLayout.LayoutParams
        
        if (videoAspectRatio > screenAspectRatio) {
            // Video is wider than screen - fill width completely, center vertically
            layoutParams.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            layoutParams.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            layoutParams.dimensionRatio = "${videoWidth}:${videoHeight}"
        } else {
            // Video is taller than screen - fill height completely, center horizontally  
            layoutParams.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            layoutParams.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            layoutParams.dimensionRatio = "${videoWidth}:${videoHeight}"
        }
        
        // Center the video and ensure it fills as much as possible
        layoutParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        layoutParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        layoutParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        layoutParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        
        videoView.layoutParams = layoutParams
        
        Log.d(TAG, "Scaled video to fill screen: ${videoWidth}x${videoHeight} (${String.format("%.2f", videoAspectRatio)}) on ${screenWidth}x${screenHeight} (${String.format("%.2f", screenAspectRatio)})")
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
    
    private fun initializeViews() {
        videoView = findViewById(R.id.videoView)
        graphicOverlay = findViewById(R.id.graphic_overlay)
        playPauseButton = findViewById(R.id.playPauseButton)
        seekBar = findViewById(R.id.seekBar)
        timeDisplay = findViewById(R.id.timeDisplay)
        poseToggleButton = findViewById(R.id.poseToggleButton)
        loadingIndicator = findViewById(R.id.loadingIndicator)
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
        
        setupVideoView()
        initializeMediaRetriever()
    }
    
    private fun initializePoseDetector() {
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
            .build()
            
        poseDetector = PoseDetection.getClient(options)
    }
    
    private fun initializeMediaRetriever() {
        try {
            mediaRetriever = MediaMetadataRetriever().apply {
                setDataSource(this@VideoPlaybackActivity, videoUri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing media retriever", e)
            Toast.makeText(this, "Error: Cannot analyze this video format", Toast.LENGTH_SHORT).show()
            isPoseDetectionEnabled = false
            poseToggleButton.isEnabled = false
        }
    }
    
    private fun setupVideoView() {
        try {
            videoView.setVideoURI(videoUri)
            
            videoView.setOnPreparedListener { mediaPlayer ->
                videoDuration = mediaPlayer.duration
                seekBar.max = videoDuration
                updateTimeDisplay(0)
                loadingIndicator.visibility = View.GONE
                
                // Center the video and maintain aspect ratio
                centerVideoView(mediaPlayer)
            }
            
            videoView.setOnCompletionListener {
                isPlaying = false
                playPauseButton.setImageResource(android.R.drawable.ic_media_play)
                stopPoseProcessing()
            }
            
            videoView.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Video error: what=$what, extra=$extra")
                Toast.makeText(this, "Error playing video", Toast.LENGTH_SHORT).show()
                true
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up video", e)
            Toast.makeText(this, "Error setting up video: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun setupControls() {
        playPauseButton.setOnClickListener {
            if (isPlaying) {
                pauseVideo()
            } else {
                playVideo()
            }
        }
        
        poseToggleButton.setOnClickListener {
            togglePoseDetection()
        }
        
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    videoView.seekTo(progress)
                    updateTimeDisplay(progress)
                    if (isPoseDetectionEnabled) {
                        processCurrentFrame()
                    }
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                if (isPlaying) {
                    pauseVideo()
                }
            }
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        startSeekBarUpdater()
        
        // Start auto-hide timer for controls
        scheduleControlsHide()
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
    
    private fun setupTouchListener() {
        controlsContainer.setOnClickListener {
            if (controlsVisible) {
                hideControls()
            } else {
                showControls()
            }
        }
        
        // Also handle direct touches on the video view
        videoView.setOnClickListener {
            if (controlsVisible) {
                hideControls()
            } else {
                showControls()
            }
        }
    }
    
    private fun showControls() {
        if (!controlsVisible) {
            controlsVisible = true
            
            // Ensure controls are clickable and visible
            controlsContainer.visibility = View.VISIBLE
            controlsContainer.isClickable = true
            controlsContainer.isFocusable = true
            
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
                    // Keep container visible but non-interactive for touch detection
                    controlsContainer.isClickable = true
                    controlsContainer.isFocusable = true
                }
                .start()
        }
        mainHandler.removeCallbacks(hideControlsRunnable)
    }
    
    private fun scheduleControlsHide() {
        mainHandler.removeCallbacks(hideControlsRunnable)
        if (isPlaying) {
            mainHandler.postDelayed(hideControlsRunnable, showControlsDelay)
        }
    }
    
    private fun playVideo() {
        videoView.start()
        isPlaying = true
        playPauseButton.setImageResource(android.R.drawable.ic_media_pause)
        
        if (isPoseDetectionEnabled) {
            startPoseProcessing()
        }
        
        scheduleControlsHide()
    }
    
    private fun pauseVideo() {
        if (videoView.isPlaying) {
            videoView.pause()
        }
        isPlaying = false
        playPauseButton.setImageResource(android.R.drawable.ic_media_play)
        stopPoseProcessing()
        
        // Keep controls visible when paused
        showControls()
        mainHandler.removeCallbacks(hideControlsRunnable)
    }
    
    private fun togglePoseDetection() {
        isPoseDetectionEnabled = !isPoseDetectionEnabled
        
        if (isPoseDetectionEnabled) {
            poseToggleButton.text = "Hide Pose"
            graphicOverlay.visibility = View.VISIBLE
            
            // Wait for layout to complete before processing
            graphicOverlay.post {
                Log.d(TAG, "GraphicOverlay layout completed. Size: ${graphicOverlay.width}x${graphicOverlay.height}")
                
                if (isPlaying) {
                    startPoseProcessing()
                } else {
                    // Process current frame when paused
                    processCurrentFrame()
                }
            }
        } else {
            poseToggleButton.text = "Show Pose"
            graphicOverlay.visibility = View.GONE
            graphicOverlay.clear()
            stopPoseProcessing()
        }
    }
    
    private fun startPoseProcessing() {
        processingJob = coroutineScope.launch {
            while (isActive && isPoseDetectionEnabled && isPlaying) {
                val currentPosition = videoView.currentPosition.toLong()
                
                // Only process if enough time has passed
                if (currentPosition - lastProcessedTime >= frameProcessingInterval) {
                    processFrameAtTime(currentPosition * 1000) // Convert to microseconds
                    lastProcessedTime = currentPosition
                }
                
                delay(50) // Check every 50ms
            }
        }
    }
    
    private fun stopPoseProcessing() {
        processingJob?.cancel()
    }
    
    private fun processCurrentFrame() {
        val currentPosition = videoView.currentPosition.toLong()
        coroutineScope.launch {
            processFrameAtTime(currentPosition * 1000) // Convert to microseconds
        }
    }
    
    private suspend fun processFrameAtTime(timeUs: Long) {
        try {
            val bitmap = withContext(Dispatchers.IO) {
                mediaRetriever?.getFrameAtTime(timeUs)
            }
            
            if (bitmap != null) {
                Log.d(TAG, "Extracted frame at time $timeUs: ${bitmap.width}x${bitmap.height}")
                processBitmapForPose(bitmap)
            } else {
                Log.w(TAG, "Failed to extract frame at time $timeUs")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting frame at time $timeUs", e)
        }
    }
    
    private suspend fun processBitmapForPose(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        
        Log.d(TAG, "Processing bitmap: ${bitmap.width}x${bitmap.height}")
        
        poseDetector.process(inputImage)
            .addOnSuccessListener { pose ->
                mainHandler.post {
                    if (isPoseDetectionEnabled) {
                        updatePoseOverlay(pose, bitmap.width, bitmap.height)
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Pose detection failed", e)
            }
    }
    
    private fun updatePoseOverlay(pose: Pose, imageWidth: Int, imageHeight: Int) {
        // Debug logging
        val landmarks = pose.allPoseLandmarks
        Log.d(TAG, "Pose detected with ${landmarks.size} landmarks")
        
        if (landmarks.isNotEmpty()) {
            // Log first few landmarks for debugging
            landmarks.take(3).forEach { landmark ->
                Log.d(TAG, "Landmark ${landmark.landmarkType}: position=(${landmark.position.x}, ${landmark.position.y})")
            }
        }
        
        graphicOverlay.clear()
        
        // For video playback, we need to account for how VideoView scales the video
        // VideoView uses fitCenter by default, which means the video is scaled to fit 
        // within the view while maintaining aspect ratio
        
        // Get the VideoView dimensions
        val videoViewWidth = videoView.width
        val videoViewHeight = videoView.height
        
        Log.d(TAG, "VideoView dimensions: ${videoViewWidth}x${videoViewHeight}")
        Log.d(TAG, "Video frame dimensions: ${imageWidth}x${imageHeight}")
        
        // Set the image source info - this is the original video frame size
        graphicOverlay.setImageSourceInfo(imageWidth, imageHeight)
        
        // Don't show the white border for video playback
        graphicOverlay.setShowPreviewBorder(false)
        
        // Draw the pose only if landmarks were detected
        if (landmarks.isNotEmpty()) {
            val poseGraphic = PoseGraphic(graphicOverlay, pose)
            graphicOverlay.add(poseGraphic)
        } else {
            Log.d(TAG, "No pose landmarks detected")
        }
    }
    
    private fun updateTimeDisplay(position: Int) {
        val currentSec = position / 1000
        val totalSec = videoDuration / 1000
        val currentMin = currentSec / 60
        val currentSecRem = currentSec % 60
        val totalMin = totalSec / 60
        val totalSecRem = totalSec % 60
        
        timeDisplay.text = String.format("%d:%02d / %d:%02d", 
            currentMin, currentSecRem, totalMin, totalSecRem)
    }
    
    private fun startSeekBarUpdater() {
        val handler = Handler(Looper.getMainLooper())
        val updateRunnable = object : Runnable {
            override fun run() {
                if (isPlaying && videoView.isPlaying) {
                    val currentPosition = videoView.currentPosition
                    seekBar.progress = currentPosition
                    updateTimeDisplay(currentPosition)
                }
                handler.postDelayed(this, 100)
            }
        }
        handler.post(updateRunnable)
    }
    
    override fun onResume() {
        super.onResume()
        enableFullScreenMode()
    }
    
    override fun onPause() {
        super.onPause()
        pauseVideo()
        mainHandler.removeCallbacks(hideControlsRunnable)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            videoView.stopPlayback()
            poseDetector.close()
            processingExecutor.shutdown()
            coroutineScope.cancel()
            mediaRetriever?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error in cleanup", e)
        }
    }
}