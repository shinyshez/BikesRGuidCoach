package com.mtbanalyzer

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlinx.coroutines.*

class VideoPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {
    
    companion object {
        private const val TAG = "VideoPlayerView"
    }
    
    // UI Components
    private lateinit var videoView: VideoView
    private lateinit var frameImageView: ImageView
    private lateinit var graphicOverlay: GraphicOverlay
    private lateinit var playPauseButton: ImageButton
    private lateinit var frameForwardButton: ImageButton
    private lateinit var frameBackwardButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var timeDisplay: TextView
    private lateinit var poseToggleButton: Button
    private lateinit var loadingIndicator: ProgressBar
    
    // Video properties
    private var videoUri: Uri? = null
    private var isPlaying = false
    private var videoDuration = 0
    
    // Pose detection
    private var isPoseDetectionEnabled = false
    private lateinit var poseDetector: PoseDetector
    private var processingJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    
    // Frame extraction
    private var mediaRetriever: MediaMetadataRetriever? = null
    private var lastProcessedTime = -1L
    private val frameProcessingInterval = 100L
    
    // Frame stepping
    private var isFrameSteppingMode = false
    private var currentFrameIndex = 0L
    private var totalFrames = 0L
    private var frameRate = 30.0
    private var frameDurationUs = 33333L
    private var currentPositionMs = 0
    
    // Seekbar throttling
    private var seekFrameJob: Job? = null
    private var lastSeekRequest = 0L
    
    // Frame button hold-to-repeat
    private var frameButtonHoldJob: Job? = null
    private val frameButtonInitialDelay = 300L
    private val frameButtonRepeatDelay = 50L
    
    // Callbacks
    private var onVideoLoadedListener: ((duration: Int) -> Unit)? = null
    private var onVideoErrorListener: ((what: Int, extra: Int) -> Unit)? = null
    private var onVideoCompletionListener: (() -> Unit)? = null
    
    private val mainHandler = Handler(Looper.getMainLooper())
    
    init {
        LayoutInflater.from(context).inflate(R.layout.view_video_player, this, true)
        initializeViews()
        initializePoseDetector()
        setupControls()
    }
    
    private fun initializeViews() {
        videoView = findViewById(R.id.videoView)
        frameImageView = findViewById(R.id.frameImageView)
        graphicOverlay = findViewById(R.id.graphic_overlay)
        playPauseButton = findViewById(R.id.playPauseButton)
        frameForwardButton = findViewById(R.id.frameForwardButton)
        frameBackwardButton = findViewById(R.id.frameBackwardButton)
        seekBar = findViewById(R.id.seekBar)
        timeDisplay = findViewById(R.id.timeDisplay)
        poseToggleButton = findViewById(R.id.poseToggleButton)
        loadingIndicator = findViewById(R.id.loadingIndicator)
    }
    
    private fun initializePoseDetector() {
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
            .build()
            
        poseDetector = PoseDetection.getClient(options)
    }
    
    fun setVideo(uri: Uri) {
        videoUri = uri
        setupVideoView()
        initializeMediaRetriever()
    }
    
    private fun setupVideoView() {
        try {
            videoView.setVideoURI(videoUri)
            
            videoView.setOnPreparedListener { mediaPlayer ->
                videoDuration = mediaPlayer.duration
                seekBar.max = videoDuration
                updateTimeDisplay(0)
                loadingIndicator.visibility = View.GONE
                
                // Initialize frame tracking
                currentPositionMs = 0
                currentFrameIndex = 0
                
                // Center and scale the video properly
                centerVideoView(mediaPlayer)
                
                // Notify listener
                onVideoLoadedListener?.invoke(videoDuration)
            }
            
            videoView.setOnCompletionListener {
                isPlaying = false
                playPauseButton.setImageResource(android.R.drawable.ic_media_play)
                stopPoseProcessing()
                onVideoCompletionListener?.invoke()
            }
            
            videoView.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Video error: what=$what, extra=$extra")
                onVideoErrorListener?.invoke(what, extra)
                true
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up video", e)
        }
    }
    
    private fun initializeMediaRetriever() {
        try {
            mediaRetriever = MediaMetadataRetriever().apply {
                setDataSource(context, videoUri)
                
                val durationStr = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val frameRateStr = extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                
                val videoDurationMs = durationStr?.toLongOrNull() ?: videoDuration.toLong()
                frameRate = frameRateStr?.toDoubleOrNull() ?: 30.0
                frameDurationUs = (1_000_000.0 / frameRate).toLong()
                totalFrames = ((videoDurationMs * frameRate) / 1000.0).toLong()
                
                Log.d(TAG, "Video properties: duration=${videoDurationMs}ms, frameRate=${frameRate}fps, totalFrames=$totalFrames")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing media retriever", e)
            isPoseDetectionEnabled = false
            poseToggleButton.isEnabled = false
        }
    }
    
    private fun setupControls() {
        playPauseButton.setOnClickListener {
            if (isPlaying) {
                pause()
            } else {
                play()
            }
        }
        
        setupFrameStepButtons()
        
        poseToggleButton.setOnClickListener {
            togglePoseDetection()
        }
        
        setupSeekBar()
        
        startSeekBarUpdater()
    }
    
    private fun setupFrameStepButtons() {
        // Forward button - single click
        frameForwardButton.setOnClickListener {
            stepFrame(forward = true)
        }
        
        // Forward button - hold to repeat
        frameForwardButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    frameButtonHoldJob?.cancel()
                    frameButtonHoldJob = coroutineScope.launch {
                        withContext(Dispatchers.Main) {
                            stepFrame(forward = true)
                        }
                        delay(frameButtonInitialDelay)
                        while (isActive) {
                            withContext(Dispatchers.Main) {
                                stepFrame(forward = true)
                            }
                            delay(frameButtonRepeatDelay)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    frameButtonHoldJob?.cancel()
                    frameButtonHoldJob = null
                    true
                }
                else -> false
            }
        }
        
        // Backward button - single click
        frameBackwardButton.setOnClickListener {
            stepFrame(forward = false)
        }
        
        // Backward button - hold to repeat
        frameBackwardButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    frameButtonHoldJob?.cancel()
                    frameButtonHoldJob = coroutineScope.launch {
                        withContext(Dispatchers.Main) {
                            stepFrame(forward = false)
                        }
                        delay(frameButtonInitialDelay)
                        while (isActive) {
                            withContext(Dispatchers.Main) {
                                stepFrame(forward = false)
                            }
                            delay(frameButtonRepeatDelay)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    frameButtonHoldJob?.cancel()
                    frameButtonHoldJob = null
                    true
                }
                else -> false
            }
        }
    }
    
    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentPositionMs = progress
                    currentFrameIndex = ((progress * frameRate) / 1000.0).toLong()
                    updateTimeDisplay(progress)
                    
                    seekFrameJob?.cancel()
                    lastSeekRequest = System.currentTimeMillis()
                    
                    seekFrameJob = coroutineScope.launch {
                        val requestTime = lastSeekRequest
                        delay(50)
                        
                        if (requestTime == lastSeekRequest) {
                            try {
                                val frameTimeUs = currentFrameIndex * frameDurationUs
                                val frameBitmap = withContext(Dispatchers.IO) {
                                    mediaRetriever?.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                                }
                                
                                withContext(Dispatchers.Main) {
                                    if (requestTime == lastSeekRequest && frameBitmap != null) {
                                        frameImageView.setImageBitmap(frameBitmap)
                                        frameImageView.visibility = View.VISIBLE
                                        isFrameSteppingMode = true
                                        
                                        if (isPoseDetectionEnabled) {
                                            processBitmapForPose(frameBitmap)
                                        }
                                    }
                                    videoView.seekTo(progress)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error extracting frame during seek", e)
                                withContext(Dispatchers.Main) {
                                    videoView.seekTo(progress)
                                }
                            }
                        }
                    }
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                if (isPlaying) {
                    pause()
                }
            }
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    
    fun play() {
        if (isFrameSteppingMode) {
            frameImageView.visibility = View.GONE
            isFrameSteppingMode = false
        }
        
        videoView.start()
        isPlaying = true
        playPauseButton.setImageResource(android.R.drawable.ic_media_pause)
        
        if (isPoseDetectionEnabled) {
            startPoseProcessing()
        }
    }
    
    fun pause() {
        if (videoView.isPlaying) {
            videoView.pause()
        }
        isPlaying = false
        playPauseButton.setImageResource(android.R.drawable.ic_media_play)
        stopPoseProcessing()
    }
    
    fun seekTo(position: Int) {
        videoView.seekTo(position)
        seekBar.progress = position
        updateTimeDisplay(position)
    }
    
    fun getCurrentPosition(): Int = videoView.currentPosition
    
    fun getDuration(): Int = videoDuration
    
    fun isPlaying(): Boolean = isPlaying
    
    private fun stepFrame(forward: Boolean) {
        if (isPlaying) {
            pause()
        }
        
        val newFrameIndex = if (forward) {
            minOf(currentFrameIndex + 1, totalFrames - 1)
        } else {
            maxOf(currentFrameIndex - 1, 0)
        }
        
        if (newFrameIndex == currentFrameIndex) {
            return
        }
        
        currentFrameIndex = newFrameIndex
        
        val frameTimeUs = currentFrameIndex * frameDurationUs
        val frameTimeMs = (frameTimeUs / 1000).toInt()
        
        currentPositionMs = frameTimeMs
        
        seekBar.progress = frameTimeMs
        updateTimeDisplay(frameTimeMs)
        
        coroutineScope.launch {
            try {
                val frameBitmap = withContext(Dispatchers.IO) {
                    mediaRetriever?.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                }
                
                withContext(Dispatchers.Main) {
                    if (frameBitmap != null) {
                        frameImageView.setImageBitmap(frameBitmap)
                        frameImageView.visibility = View.VISIBLE
                        isFrameSteppingMode = true
                    }
                    
                    videoView.seekTo(frameTimeMs)
                    
                    if (isPoseDetectionEnabled && frameBitmap != null) {
                        processBitmapForPose(frameBitmap)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting frame", e)
                withContext(Dispatchers.Main) {
                    videoView.seekTo(frameTimeMs)
                }
            }
        }
    }
    
    private fun togglePoseDetection() {
        isPoseDetectionEnabled = !isPoseDetectionEnabled
        
        if (isPoseDetectionEnabled) {
            poseToggleButton.text = "Hide Pose"
            graphicOverlay.visibility = View.VISIBLE
            
            graphicOverlay.post {
                if (isPlaying) {
                    startPoseProcessing()
                } else {
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
    
    fun setPoseDetectionEnabled(enabled: Boolean) {
        if (enabled != isPoseDetectionEnabled) {
            togglePoseDetection()
        }
    }
    
    fun isPoseDetectionEnabled(): Boolean = isPoseDetectionEnabled
    
    private fun startPoseProcessing() {
        processingJob = coroutineScope.launch {
            while (isActive && isPoseDetectionEnabled && isPlaying) {
                val currentPosition = videoView.currentPosition.toLong()
                
                if (currentPosition - lastProcessedTime >= frameProcessingInterval) {
                    processFrameAtTime(currentPosition * 1000)
                    lastProcessedTime = currentPosition
                }
                
                delay(50)
            }
        }
    }
    
    private fun stopPoseProcessing() {
        processingJob?.cancel()
    }
    
    private fun processCurrentFrame() {
        val currentPosition = videoView.currentPosition.toLong()
        coroutineScope.launch {
            processFrameAtTime(currentPosition * 1000)
        }
    }
    
    private suspend fun processFrameAtTime(timeUs: Long) {
        try {
            val bitmap = withContext(Dispatchers.IO) {
                mediaRetriever?.getFrameAtTime(timeUs)
            }
            
            if (bitmap != null) {
                processBitmapForPose(bitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting frame at time $timeUs", e)
        }
    }
    
    private suspend fun processBitmapForPose(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        
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
        graphicOverlay.clear()
        graphicOverlay.setImageSourceInfo(imageWidth, imageHeight)
        graphicOverlay.setShowPreviewBorder(false)
        
        val landmarks = pose.allPoseLandmarks
        if (landmarks.isNotEmpty()) {
            val poseGraphic = PoseGraphic(graphicOverlay, pose)
            graphicOverlay.add(poseGraphic)
        }
    }
    
    private fun updateTimeDisplay(position: Int) {
        val currentSec = position / 1000
        val totalSec = videoDuration / 1000
        val currentMin = currentSec / 60
        val currentSecRem = currentSec % 60
        val totalMin = totalSec / 60
        val totalSecRem = totalSec % 60
        
        val displayFrameIndex = ((position * frameRate) / 1000.0).toLong()
        
        timeDisplay.text = String.format("%d:%02d / %d:%02d (Frame %d/%d)", 
            currentMin, currentSecRem, totalMin, totalSecRem, displayFrameIndex, totalFrames)
    }
    
    private fun startSeekBarUpdater() {
        val updateRunnable = object : Runnable {
            override fun run() {
                if (isPlaying && videoView.isPlaying) {
                    val currentPosition = videoView.currentPosition
                    seekBar.progress = currentPosition
                    updateTimeDisplay(currentPosition)
                }
                mainHandler.postDelayed(this, 100)
            }
        }
        mainHandler.post(updateRunnable)
    }
    
    fun setOnVideoLoadedListener(listener: (duration: Int) -> Unit) {
        onVideoLoadedListener = listener
    }
    
    fun setOnVideoErrorListener(listener: (what: Int, extra: Int) -> Unit) {
        onVideoErrorListener = listener
    }
    
    fun setOnVideoCompletionListener(listener: () -> Unit) {
        onVideoCompletionListener = listener
    }
    
    private fun centerVideoView(mediaPlayer: android.media.MediaPlayer) {
        val videoWidth = mediaPlayer.videoWidth
        val videoHeight = mediaPlayer.videoHeight
        
        if (videoWidth == 0 || videoHeight == 0) {
            Log.w(TAG, "Video dimensions are zero, cannot center")
            return
        }
        
        // Get container dimensions
        val containerWidth = width
        val containerHeight = height
        
        if (containerWidth == 0 || containerHeight == 0) {
            // Container not measured yet, try again after layout
            post { centerVideoView(mediaPlayer) }
            return
        }
        
        // Calculate video aspect ratio
        val videoAspectRatio = videoWidth.toFloat() / videoHeight.toFloat()
        val containerAspectRatio = containerWidth.toFloat() / containerHeight.toFloat()
        
        val layoutParams = videoView.layoutParams as ConstraintLayout.LayoutParams
        
        if (videoAspectRatio > containerAspectRatio) {
            // Video is wider than container - fill width completely, center vertically
            layoutParams.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            layoutParams.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            layoutParams.dimensionRatio = "${videoWidth}:${videoHeight}"
        } else {
            // Video is taller than container - fill height completely, center horizontally  
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
        
        // Also apply the same centering to the frame ImageView
        val frameLayoutParams = frameImageView.layoutParams as ConstraintLayout.LayoutParams
        frameLayoutParams.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
        frameLayoutParams.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
        frameLayoutParams.dimensionRatio = "${videoWidth}:${videoHeight}"
        frameLayoutParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        frameLayoutParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        frameLayoutParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        frameLayoutParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        frameImageView.layoutParams = frameLayoutParams
        
        Log.d(TAG, "Centered video: ${videoWidth}x${videoHeight} (${String.format("%.2f", videoAspectRatio)}) in ${containerWidth}x${containerHeight} (${String.format("%.2f", containerAspectRatio)})")
    }
    
    fun release() {
        try {
            videoView.stopPlayback()
            poseDetector.close()
            seekFrameJob?.cancel()
            frameButtonHoldJob?.cancel()
            coroutineScope.cancel()
            mediaRetriever?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing resources", e)
        }
    }
}