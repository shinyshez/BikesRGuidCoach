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
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private var seekBarUpdateRunnable: Runnable? = null
    
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
                        playPauseButton.setImageResource(android.R.drawable.ic_media_play)
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
                playPauseButton.setImageResource(
                    if (playing) android.R.drawable.ic_media_pause
                    else android.R.drawable.ic_media_play
                )
                
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
                Log.d(TAG, "Video size: ${videoSize.width}x${videoSize.height}")
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
    
    fun play() {
        exoPlayer?.play()
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
            
            graphicOverlay.post {
                if (isPlaying) {
                    startPoseProcessing()
                } else {
                    processCurrentFrameForPose()
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
                processCurrentFrameForPose()
                delay(100) // Process every 100ms during playback
            }
        }
    }
    
    private fun stopPoseProcessing() {
        processingJob?.cancel()
    }
    
    private fun processCurrentFrameForPose() {
        coroutineScope.launch {
            try {
                // Extract current frame using MediaMetadataRetriever for pose detection
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, videoUri)
                
                val currentPosition = getCurrentPosition().toLong()
                val bitmap = retriever.getFrameAtTime(currentPosition * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                
                retriever.release()
                
                if (bitmap != null) {
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
            } catch (e: Exception) {
                Log.e(TAG, "Error processing frame for pose detection", e)
            }
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
        } catch (e: Exception) {
            Log.e(TAG, "Error updating time display", e)
            timeDisplay.text = "0:00 / 0:00 (Frame 0/0)"
        }
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