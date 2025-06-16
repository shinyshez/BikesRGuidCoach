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
import net.protyposis.android.mediaplayer.MediaPlayer
import net.protyposis.android.mediaplayer.MediaSource
import net.protyposis.android.mediaplayer.UriSource
import net.protyposis.android.mediaplayer.VideoView as MediaPlayerVideoView
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
    private lateinit var videoView: MediaPlayerVideoView
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
    private var mediaPlayer: MediaPlayer? = null
    
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
    
    init {
        LayoutInflater.from(context).inflate(R.layout.view_video_player, this, true)
        initializeViews()
        initializePoseDetector()
        setupControls()
    }
    
    private fun initializeViews() {
        videoView = findViewById(R.id.videoView)
        graphicOverlay = findViewById(R.id.graphic_overlay)
        playPauseButton = findViewById(R.id.playPauseButton)
        frameForwardButton = findViewById(R.id.frameForwardButton)
        frameBackwardButton = findViewById(R.id.frameBackwardButton)
        seekBar = findViewById(R.id.seekBar)
        timeDisplay = findViewById(R.id.timeDisplay)
        poseToggleButton = findViewById(R.id.poseToggleButton)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        
        // Log button initialization
        Log.d(TAG, "Frame buttons initialized: forward=${frameForwardButton != null}, backward=${frameBackwardButton != null}")
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
        // Don't extract metadata here - wait for onPrepared
    }
    
    private fun setupVideoView() {
        try {
            // Use setDataSource for MediaPlayer-Extended
            val mediaSource = UriSource(context, videoUri)
            videoView.setVideoSource(mediaSource)
            
            videoView.setOnPreparedListener { mp ->
                mediaPlayer = mp as MediaPlayer
                videoDuration = videoView.duration
                seekBar.max = videoDuration
                updateTimeDisplay(0)
                loadingIndicator.visibility = View.GONE
                
                // Extract metadata now that video is prepared
                extractVideoMetadata()
                
                // Initialize frame tracking
                currentFrameIndex = 0
                
                // Center and scale the video properly with actual video dimensions
                post {
                    centerVideoView()
                }
                
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
    
    private fun centerVideoView() {
        // Let VideoView handle its own aspect ratio naturally
        Log.d(TAG, "VideoView prepared - using native aspect ratio handling")
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
                    // Return false to allow click events to also be processed
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    frameButtonHoldJob?.cancel()
                    frameButtonHoldJob = null
                    // Return false to allow click events to also be processed
                    false
                }
                else -> false
            }
        }
        
        // Forward button - single click (as fallback)
        frameForwardButton.setOnClickListener {
            Log.d(TAG, "Forward button clicked")
            // Only step if not already handled by touch listener
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
                    // Return false to allow click events to also be processed
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    frameButtonHoldJob?.cancel()
                    frameButtonHoldJob = null
                    // Return false to allow click events to also be processed
                    false
                }
                else -> false
            }
        }
        
        // Backward button - single click (as fallback)
        frameBackwardButton.setOnClickListener {
            Log.d(TAG, "Backward button clicked")
            // Only step if not already handled by touch listener
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
                    
                    // Perform seeking with MediaPlayer-Extended
                    videoView.seekTo(progress)
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
        currentFrameIndex = ((position * frameRate) / 1000.0).toLong()
        updateTimeDisplay(position)
    }
    
    fun getCurrentPosition(): Int = videoView.currentPosition
    
    fun getDuration(): Int = videoDuration
    
    fun isPlaying(): Boolean = isPlaying
    
    private fun stepFrame(forward: Boolean) {
        Log.d(TAG, "stepFrame called: forward=$forward, frameRate=$frameRate, frameDurationMs=$frameDurationMs")
        
        if (isPlaying) {
            pause()
        }
        
        // Get current position
        val currentPos = videoView.currentPosition
        Log.d(TAG, "Current position: $currentPos ms, duration: $videoDuration ms")
        
        // Simple approach: step by a fixed amount (33ms ~= 30fps)
        val stepSize = if (frameDurationMs > 0) frameDurationMs.toInt() else 33
        
        val newPosition = if (forward) {
            minOf(currentPos + stepSize, videoDuration - 1)
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
        
        // Use MediaPlayer-Extended's precise frame-accurate seeking
        videoView.seekTo(newPosition)
        
        // Force a pause to ensure frame is displayed
        if (!isPlaying) {
            videoView.pause()
        }
        
        // Process pose detection after a small delay to ensure seek is complete
        mainHandler.postDelayed({
            val actualPos = videoView.currentPosition
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
            val totalSec = videoDuration / 1000
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
        val updateRunnable = object : Runnable {
            override fun run() {
                if (isPlaying && videoView.isPlaying) {
                    val currentPosition = videoView.currentPosition
                    seekBar.progress = currentPosition
                    currentFrameIndex = ((currentPosition * frameRate) / 1000.0).toLong()
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
    
    fun handleOrientationChange(newConfig: Configuration) {
        Log.d(TAG, "Configuration changed: ${newConfig.orientation}")
        
        // Re-center the video for the new orientation
        post {
            centerVideoView()
        }
    }
    
    fun release() {
        try {
            videoView.stopPlayback()
            mediaPlayer?.release()
            mediaPlayer = null
            poseDetector.close()
            frameButtonHoldJob?.cancel()
            coroutineScope.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing resources", e)
        }
    }
}