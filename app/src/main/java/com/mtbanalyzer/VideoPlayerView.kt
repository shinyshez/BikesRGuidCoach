package com.mtbanalyzer

import android.content.Context
import android.content.res.Configuration
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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
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
    private lateinit var playerView: PlayerView
    private lateinit var graphicOverlay: GraphicOverlay
    private lateinit var scrubOverlay: ScrubOverlay
    private lateinit var playPauseButton: ImageButton
    private lateinit var frameForwardButton: ImageButton
    private lateinit var frameBackwardButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var timeDisplay: TextView
    private lateinit var poseToggleButton: Button
    private lateinit var loadingIndicator: ProgressBar
    
    // Media3 ExoPlayer
    private var exoPlayer: ExoPlayer? = null
    private var videoUri: Uri? = null
    private var isPlaying = false
    private var videoDuration = 0L
    
    // Pose detection
    private var isPoseDetectionEnabled = false
    private lateinit var poseDetector: PoseDetector
    private var processingJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    
    // Frame stepping
    private var currentFrameIndex = 0L
    private var totalFrames = 0L
    private var frameRate = 30.0
    private var frameDurationMs = 33.333 // Milliseconds per frame (1000/30)
    
    // Frame button hold-to-repeat
    private var frameButtonHoldJob: Job? = null
    private val frameButtonInitialDelay = 300L
    private val frameButtonRepeatDelay = 50L
    
    // Callbacks
    private var onVideoLoadedListener: ((duration: Int) -> Unit)? = null
    private var onVideoErrorListener: ((what: Int, extra: Int) -> Unit)? = null
    private var onVideoCompletionListener: (() -> Unit)? = null
    private var onSeekListener: ((position: Int, fromUser: Boolean) -> Unit)? = null
    private var onFrameStepListener: ((forward: Boolean) -> Unit)? = null
    private var onScrubListener: ((position: Int) -> Unit)? = null
    private var onPlayPauseListener: ((play: Boolean) -> Unit)? = null
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private var seekBarUpdateRunnable: Runnable? = null
    
    // Scrubbing gesture state
    private var isScrubbing = false
    private var scrubStartX = 0f
    private var scrubStartPosition = 0
    private val scrubSensitivity = 2.0f // Pixels per millisecond
    private val holdDuration = 500L // Time to hold before scrubbing starts
    
    init {
        LayoutInflater.from(context).inflate(R.layout.view_video_player, this, true)
        initializeViews()
        initializePoseDetector()
        initializePlayer()
        setupControls()
    }
    
    private fun initializeViews() {
        playerView = findViewById(R.id.playerView)
        graphicOverlay = findViewById(R.id.graphic_overlay)
        scrubOverlay = findViewById(R.id.scrub_overlay)
        playPauseButton = findViewById(R.id.playPauseButton)
        frameForwardButton = findViewById(R.id.frameForwardButton)
        frameBackwardButton = findViewById(R.id.frameBackwardButton)
        seekBar = findViewById(R.id.seekBar)
        timeDisplay = findViewById(R.id.timeDisplay)
        poseToggleButton = findViewById(R.id.poseToggleButton)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        
        Log.d(TAG, "Views initialized successfully")
    }
    
    private fun initializePoseDetector() {
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
            .build()
            
        poseDetector = PoseDetection.getClient(options)
    }
    
    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(context)
            .build()
            
        playerView.player = exoPlayer
        playerView.useController = false // Use our custom controls
        
        // Set up player listeners
        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        videoDuration = exoPlayer?.duration ?: 0L
                        seekBar.max = videoDuration.toInt()
                        loadingIndicator.visibility = View.GONE
                        
                        // Extract metadata and initialize frame tracking
                        extractVideoMetadata()
                        currentFrameIndex = 0
                        
                        // Notify listener
                        onVideoLoadedListener?.invoke(videoDuration.toInt())
                        
                        Log.d(TAG, "Video ready: duration=${videoDuration}ms")
                    }
                    Player.STATE_ENDED -> {
                        isPlaying = false
                        playPauseButton.setImageResource(android.R.drawable.ic_popup_sync) // Reload icon
                        stopPoseProcessing()
                        onVideoCompletionListener?.invoke()
                    }
                    Player.STATE_BUFFERING -> {
                        loadingIndicator.visibility = View.VISIBLE
                    }
                    Player.STATE_IDLE -> {
                        loadingIndicator.visibility = View.GONE
                    }
                }
            }
            
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                
                if (playing) {
                    playPauseButton.setImageResource(android.R.drawable.ic_media_pause)
                } else {
                    // Check if we're at the end when pausing/stopping
                    val currentPos = getCurrentPosition()
                    val duration = getDuration()
                    val isAtEnd = duration > 0 && currentPos >= duration - 500
                    Log.d(TAG, "onIsPlayingChanged: playing=$playing, pos=$currentPos, duration=$duration, isAtEnd=$isAtEnd")
                    playPauseButton.setImageResource(
                        if (isAtEnd) android.R.drawable.ic_popup_sync
                        else android.R.drawable.ic_media_play
                    )
                }
                
                if (playing && isPoseDetectionEnabled) {
                    startPoseProcessing()
                } else {
                    stopPoseProcessing()
                }
            }
            
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "ExoPlayer error: ${error.message}", error)
                onVideoErrorListener?.invoke(error.errorCode, 0)
            }
            
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                Log.d(TAG, "Video size changed: ${videoSize.width}x${videoSize.height}")
                // Update overlay when video size changes - wait for layout to complete
                post {
                    Log.d(TAG, "PlayerView size: ${playerView.width}x${playerView.height}")
                    Log.d(TAG, "GraphicOverlay size: ${graphicOverlay.width}x${graphicOverlay.height}")
                    
                    // Force overlay to recalculate if it doesn't have proper dimensions yet
                    if (graphicOverlay.width == 0 || graphicOverlay.height == 0) {
                        Log.d(TAG, "GraphicOverlay not yet laid out, requesting layout")
                        graphicOverlay.requestLayout()
                        
                        // Check again after a short delay
                        postDelayed({
                            Log.d(TAG, "After layout request - GraphicOverlay size: ${graphicOverlay.width}x${graphicOverlay.height}")
                        }, 100)
                    }
                }
            }
        })
    }
    
    fun setVideo(uri: Uri) {
        videoUri = uri
        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
    }
    
    private fun extractVideoMetadata() {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, videoUri)
            
            // Try to get frame rate from metadata
            val frameRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            
            frameRate = frameRateStr?.toDoubleOrNull() ?: 30.0
            frameDurationMs = 1000.0 / frameRate
            totalFrames = ((videoDuration * frameRate) / 1000.0).toLong()
            
            // Get actual frame count if available
            val frameCountStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
            frameCountStr?.toLongOrNull()?.let { actualFrameCount ->
                if (actualFrameCount > 0) {
                    totalFrames = actualFrameCount
                    // Recalculate frame rate based on actual frame count
                    frameRate = (totalFrames * 1000.0) / videoDuration
                    frameDurationMs = 1000.0 / frameRate
                }
            }
            
            retriever.release()
            
            Log.d(TAG, "Video metadata: frameRate=${frameRate}fps, totalFrames=$totalFrames, frameDurationMs=${frameDurationMs}ms, duration=${videoDuration}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting video metadata", e)
            // Use defaults
            frameRate = 30.0
            frameDurationMs = 33.333
            totalFrames = ((videoDuration * frameRate) / 1000.0).toLong()
        }
    }
    
    private fun setupControls() {
        playPauseButton.setOnClickListener {
            if (isPlaying) {
                pause()
            } else {
                // Use play() method which includes restart logic
                play()
            }
        }
        
        setupFrameStepButtons()
        
        poseToggleButton.setOnClickListener {
            togglePoseDetection()
        }
        
        setupSeekBar()
        
        setupScrubGesture()
        
        startSeekBarUpdater()
    }
    
    private fun setupFrameStepButtons() {
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
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    frameButtonHoldJob?.cancel()
                    frameButtonHoldJob = null
                    false
                }
                else -> false
            }
        }
        
        frameForwardButton.setOnClickListener {
            Log.d(TAG, "Forward button clicked")
            if (frameButtonHoldJob == null || !frameButtonHoldJob!!.isActive) {
                stepFrame(forward = true)
            }
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
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    frameButtonHoldJob?.cancel()
                    frameButtonHoldJob = null
                    false
                }
                else -> false
            }
        }
        
        frameBackwardButton.setOnClickListener {
            Log.d(TAG, "Backward button clicked")
            if (frameButtonHoldJob == null || !frameButtonHoldJob!!.isActive) {
                stepFrame(forward = false)
            }
        }
    }
    
    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // Update our tracking variables
                    currentFrameIndex = ((progress * frameRate) / 1000.0).toLong()
                    updateTimeDisplay(progress)
                    
                    // Perform frame-accurate seeking with ExoPlayer
                    exoPlayer?.seekTo(progress.toLong())
                    
                    // Notify callback for video comparison synchronization
                    onSeekListener?.invoke(progress, fromUser)
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Pause video during seeking
                if (isPlaying) {
                    pause()
                }
            }
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Video stays paused after seeking for frame analysis
            }
        })
    }
    
    private fun setupScrubGesture() {
        // Set up touch listener on the PlayerView for scrub gesture
        var holdStartTime = 0L
        var holdCheckRunnable: Runnable? = null
        
        playerView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Start tracking hold duration
                    holdStartTime = System.currentTimeMillis()
                    scrubStartX = event.x
                    scrubStartPosition = getCurrentPosition()
                    
                    // Cancel any existing hold check
                    holdCheckRunnable?.let { mainHandler.removeCallbacks(it) }
                    
                    // Schedule check for hold duration
                    holdCheckRunnable = Runnable {
                        if (!isScrubbing && !isPlaying) {
                            // Enter scrub mode
                            startScrubbing()
                        }
                    }
                    mainHandler.postDelayed(holdCheckRunnable!!, holdDuration)
                    
                    true // Consume the event
                }
                
                MotionEvent.ACTION_MOVE -> {
                    if (isScrubbing) {
                        // Calculate seek position based on drag distance
                        val deltaX = event.x - scrubStartX
                        val deltaMs = (deltaX * scrubSensitivity).toInt()
                        val newPosition = (scrubStartPosition + deltaMs).coerceIn(0, videoDuration.toInt())
                        
                        // Update scrub overlay
                        val progress = newPosition.toFloat() / videoDuration.toFloat()
                        scrubOverlay.updateScrubPosition(progress)
                        updateScrubTimeDisplay(newPosition)
                        
                        // Perform the seek
                        seekTo(newPosition)
                        
                        // Notify scrub listener if set (for video comparison sync)
                        onScrubListener?.invoke(newPosition)
                        
                        true
                    } else {
                        false
                    }
                }
                
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Cancel hold check if not yet in scrub mode
                    holdCheckRunnable?.let { mainHandler.removeCallbacks(it) }
                    holdCheckRunnable = null
                    
                    if (isScrubbing) {
                        // Exit scrub mode
                        stopScrubbing()
                        true
                    } else {
                        // If it was a quick tap and not playing, toggle play/pause
                        val holdDuration = System.currentTimeMillis() - holdStartTime
                        if (holdDuration < 200) { // Quick tap threshold
                            if (isPlaying) {
                                // Check for override listener
                                if (onPlayPauseListener != null) {
                                    onPlayPauseListener!!(false) // false = pause
                                } else {
                                    pause()
                                }
                            } else {
                                // Check for override listener
                                if (onPlayPauseListener != null) {
                                    onPlayPauseListener!!(true) // true = play
                                } else {
                                    // Use built-in play with restart logic
                                    play()
                                }
                            }
                        }
                        true // Consume the event to prevent it from being handled elsewhere
                    }
                }
                
                else -> false
            }
        }
    }
    
    private fun startScrubbing() {
        isScrubbing = true
        
        // Pause video if playing
        if (isPlaying) {
            pause()
        }
        
        // Show scrub overlay
        scrubOverlay.setActive(true)
        
        // Update initial position
        val progress = getCurrentPosition().toFloat() / videoDuration.toFloat()
        scrubOverlay.updateScrubPosition(progress)
        updateScrubTimeDisplay(getCurrentPosition())
        
        // Dim bottom controls for cleaner UI during scrubbing
        findViewById<View>(R.id.bottomControlsBar).animate()
            .alpha(0.3f)
            .setDuration(200)
            .start()
        
        Log.d(TAG, "Started scrubbing mode")
    }
    
    private fun stopScrubbing() {
        isScrubbing = false
        
        // Hide scrub overlay
        scrubOverlay.setActive(false)
        
        // Restore bottom controls to full brightness
        findViewById<View>(R.id.bottomControlsBar).animate()
            .alpha(1.0f)
            .setDuration(200)
            .start()
        
        // Process pose if enabled
        if (isPoseDetectionEnabled) {
            processCurrentFrameForPose()
        }
        
        Log.d(TAG, "Stopped scrubbing mode")
    }
    
    private fun updateScrubTimeDisplay(positionMs: Int) {
        val currentSec = positionMs / 1000
        val totalSec = videoDuration.toInt() / 1000
        val currentMin = currentSec / 60
        val currentSecRem = currentSec % 60
        val totalMin = totalSec / 60
        val totalSecRem = totalSec % 60
        
        val currentTime = String.format("%d:%02d", currentMin, currentSecRem)
        val totalTime = String.format("%d:%02d", totalMin, totalSecRem)
        
        scrubOverlay.updateTimeDisplay(currentTime, totalTime)
    }
    
    fun play() {
        // Check if we're at the end and should restart
        val currentPos = getCurrentPosition()
        val duration = getDuration()
        val isAtEnd = duration > 0 && currentPos >= duration - 500
        
        if (isAtEnd) {
            // Restart from beginning
            seekTo(0)
            mainHandler.postDelayed({
                exoPlayer?.play()
            }, 100)
        } else {
            exoPlayer?.play()
        }
        // isPlaying will be updated by onIsPlayingChanged listener
    }
    
    fun pause() {
        exoPlayer?.pause()
        // isPlaying will be updated by onIsPlayingChanged listener
    }
    
    fun seekTo(position: Int) {
        exoPlayer?.seekTo(position.toLong())
        seekBar.progress = position
        currentFrameIndex = ((position * frameRate) / 1000.0).toLong()
        updateTimeDisplay(position)
    }
    
    fun getCurrentPosition(): Int = exoPlayer?.currentPosition?.toInt() ?: 0
    
    fun getDuration(): Int = videoDuration.toInt()
    
    fun isPlaying(): Boolean = isPlaying
    
    private fun stepFrame(forward: Boolean) {
        Log.d(TAG, "stepFrame called: forward=$forward, frameRate=$frameRate, frameDurationMs=$frameDurationMs")
        
        // Check if there's a frame step override (for video comparison lock mode)
        if (onFrameStepListener != null) {
            onFrameStepListener!!(forward)
            return
        }
        
        if (isPlaying) {
            pause()
        }
        
        // Get current position
        val currentPos = getCurrentPosition()
        Log.d(TAG, "Current position: $currentPos ms, duration: $videoDuration ms")
        
        // Time-based stepping approach
        val stepSize = if (frameDurationMs > 0) frameDurationMs.toInt() else 33
        
        val newPosition = if (forward) {
            minOf(currentPos + stepSize, videoDuration.toInt() - 1)
        } else {
            maxOf(currentPos - stepSize, 0)
        }
        
        if (newPosition == currentPos) {
            Log.d(TAG, "Already at boundary: currentPos=$currentPos, newPosition=$newPosition")
            return
        }
        
        // Calculate frame index for display
        currentFrameIndex = if (frameDurationMs > 0) {
            (newPosition / frameDurationMs).toLong()
        } else {
            (newPosition / 33.333).toLong()
        }
        
        Log.d(TAG, "Seeking from $currentPos to $newPosition (frame $currentFrameIndex)")
        
        // Update UI immediately for responsiveness
        seekBar.progress = newPosition
        updateTimeDisplay(newPosition)
        
        // Use ExoPlayer's frame-accurate seeking
        exoPlayer?.seekTo(newPosition.toLong())
        
        // Process pose detection after a small delay to ensure seek is complete
        mainHandler.postDelayed({
            val actualPos = getCurrentPosition()
            Log.d(TAG, "After seek: target=$newPosition, actual position=${actualPos}ms")
            
            // Update UI with actual position
            updateTimeDisplay(actualPos)
            seekBar.progress = actualPos
            
            // Process pose detection on current frame if enabled
            if (isPoseDetectionEnabled) {
                processCurrentFrameForPose()
            }
        }, 100)
    }
    
    private fun togglePoseDetection() {
        isPoseDetectionEnabled = !isPoseDetectionEnabled
        
        if (isPoseDetectionEnabled) {
            poseToggleButton.text = "Hide Pose"
            graphicOverlay.visibility = View.VISIBLE
            Log.d(TAG, "Pose detection enabled - overlay visible: ${graphicOverlay.visibility == View.VISIBLE}")
            
            // Ensure overlay has proper dimensions before processing
            graphicOverlay.post {
                Log.d(TAG, "GraphicOverlay dimensions when enabled: ${graphicOverlay.width}x${graphicOverlay.height}")
                
                // Force layout if needed
                if (graphicOverlay.width == 0 || graphicOverlay.height == 0) {
                    Log.d(TAG, "Forcing GraphicOverlay layout")
                    graphicOverlay.requestLayout()
                    graphicOverlay.postDelayed({
                        Log.d(TAG, "After forced layout - GraphicOverlay: ${graphicOverlay.width}x${graphicOverlay.height}")
                        startPoseProcessingIfReady()
                    }, 50)
                } else {
                    startPoseProcessingIfReady()
                }
            }
        } else {
            poseToggleButton.text = "Show Pose"
            graphicOverlay.visibility = View.GONE
            graphicOverlay.clear()
            stopPoseProcessing()
            Log.d(TAG, "Pose detection disabled")
        }
    }
    
    private fun startPoseProcessingIfReady() {
        if (isPlaying) {
            Log.d(TAG, "Starting pose processing during playback")
            startPoseProcessing()
        } else {
            Log.d(TAG, "Processing current frame for pose detection")
            processCurrentFrameForPose()
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
                // Switch to main thread to call processCurrentFrameForPose (which needs getCurrentPosition)
                withContext(Dispatchers.Main) {
                    if (isPoseDetectionEnabled && isPlaying) {
                        processCurrentFrameForPose()
                    }
                }
                delay(100) // Process every 100ms during playback
            }
        }
    }
    
    private fun stopPoseProcessing() {
        processingJob?.cancel()
    }
    
    private fun processCurrentFrameForPose() {
        // Get current position on main thread (ExoPlayer requirement)
        val currentPosition = getCurrentPosition().toLong()
        Log.d(TAG, "Processing frame at position: ${currentPosition}ms")
        
        // Switch to background thread for frame extraction
        coroutineScope.launch {
            try {
                // Extract current frame using MediaMetadataRetriever for pose detection
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, videoUri)
                
                val bitmap = retriever.getFrameAtTime(currentPosition * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                
                retriever.release()
                
                if (bitmap != null) {
                    Log.d(TAG, "Frame extracted successfully: ${bitmap.width}x${bitmap.height}")
                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    
                    poseDetector.process(inputImage)
                        .addOnSuccessListener { pose ->
                            Log.d(TAG, "Pose detection successful - landmarks: ${pose.allPoseLandmarks.size}")
                            mainHandler.post {
                                if (isPoseDetectionEnabled) {
                                    updatePoseOverlay(pose, bitmap.width, bitmap.height)
                                }
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Pose detection failed", e)
                        }
                } else {
                    Log.w(TAG, "Failed to extract frame from video")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing frame for pose detection", e)
            }
        }
    }
    
    private fun updatePoseOverlay(pose: Pose, imageWidth: Int, imageHeight: Int) {
        Log.d(TAG, "Updating pose overlay - extracted frame: ${imageWidth}x${imageHeight}, overlay: ${graphicOverlay.width}x${graphicOverlay.height}")
        
        // Use the frame extraction dimensions (these are the actual image coordinates for pose landmarks)
        // The pose landmarks are relative to the extracted frame, so we'll use those dimensions directly
        val sourceWidth = imageWidth
        val sourceHeight = imageHeight
        
        Log.d(TAG, "Using source dimensions for pose mapping: ${sourceWidth}x${sourceHeight}")
        Log.d(TAG, "PlayerView dimensions: ${playerView.width}x${playerView.height}")
        
        graphicOverlay.clear()
        graphicOverlay.setImageSourceInfo(sourceWidth, sourceHeight)
        graphicOverlay.setShowPreviewBorder(false)
        
        val landmarks = pose.allPoseLandmarks
        Log.d(TAG, "Pose landmarks found: ${landmarks.size}")
        if (landmarks.isNotEmpty()) {
            val poseGraphic = PoseGraphic(graphicOverlay, pose)
            graphicOverlay.add(poseGraphic)
            Log.d(TAG, "PoseGraphic added to overlay with source ${sourceWidth}x${sourceHeight}")
        } else {
            Log.d(TAG, "No pose landmarks detected")
        }
    }
    
    private fun updateTimeDisplay(position: Int) {
        try {
            val currentSec = position / 1000
            val totalSec = videoDuration.toInt() / 1000
            val currentMin = currentSec / 60
            val currentSecRem = currentSec % 60
            val totalMin = totalSec / 60
            val totalSecRem = totalSec % 60
            
            val displayFrameIndex = if (frameRate > 0 && position >= 0) {
                ((position.toDouble() * frameRate) / 1000.0).toLong().coerceIn(0, totalFrames)
            } else {
                0L
            }
            
            timeDisplay.text = String.format("%d:%02d / %d:%02d (Frame %d/%d)", 
                currentMin, currentSecRem, totalMin, totalSecRem, displayFrameIndex, totalFrames)
                
            // Update play button icon based on position
            updatePlayButtonIcon(position)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating time display", e)
            timeDisplay.text = "0:00 / 0:00 (Frame 0/0)"
        }
    }
    
    private fun updatePlayButtonIcon(position: Int) {
        if (!isPlaying) {
            val duration = getDuration()
            if (duration > 0) {
                val isAtEnd = position >= duration - 500 // Within 500ms of end
                Log.d(TAG, "updatePlayButtonIcon: position=$position, duration=$duration, isAtEnd=$isAtEnd, isPlaying=$isPlaying")
                if (isAtEnd) {
                    playPauseButton.setImageResource(android.R.drawable.ic_popup_sync) // Reload icon
                } else {
                    playPauseButton.setImageResource(android.R.drawable.ic_media_play) // Play icon
                }
            }
        }
        // If playing, the icon is already set to pause by onIsPlayingChanged
    }
    
    private fun startSeekBarUpdater() {
        seekBarUpdateRunnable = object : Runnable {
            override fun run() {
                if (isPlaying && exoPlayer?.isPlaying == true) {
                    val currentPosition = getCurrentPosition()
                    seekBar.progress = currentPosition
                    currentFrameIndex = ((currentPosition * frameRate) / 1000.0).toLong()
                    updateTimeDisplay(currentPosition)
                }
                mainHandler.postDelayed(this, 100)
            }
        }
        mainHandler.post(seekBarUpdateRunnable!!)
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
    
    fun setOnSeekListener(listener: (position: Int, fromUser: Boolean) -> Unit) {
        onSeekListener = listener
    }
    
    fun setOnFrameStepListener(listener: (forward: Boolean) -> Unit) {
        onFrameStepListener = listener
    }
    
    fun setOnScrubListener(listener: (position: Int) -> Unit) {
        onScrubListener = listener
    }
    
    fun setOnPlayPauseListener(listener: (play: Boolean) -> Unit) {
        onPlayPauseListener = listener
    }
    
    fun handleOrientationChange(newConfig: Configuration) {
        Log.d(TAG, "Configuration changed: ${newConfig.orientation}")
        // Media3 PlayerView handles orientation changes automatically
        post {
            playerView.requestLayout()
        }
    }
    
    fun release() {
        try {
            exoPlayer?.release()
            exoPlayer = null
            poseDetector.close()
            frameButtonHoldJob?.cancel()
            processingJob?.cancel()
            coroutineScope.cancel()
            seekBarUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing resources", e)
        }
    }
}