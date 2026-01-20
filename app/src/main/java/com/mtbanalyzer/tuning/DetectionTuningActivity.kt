package com.mtbanalyzer.tuning

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.mtbanalyzer.GraphicOverlay
import com.mtbanalyzer.R
import com.mtbanalyzer.detector.RiderDetector
import com.mtbanalyzer.detector.RiderDetector.ConfigType
import com.mtbanalyzer.detector.RiderDetectorManager
import com.mtbanalyzer.detector.PoseRiderDetector
import com.mtbanalyzer.detector.MotionRiderDetector
import com.mtbanalyzer.detector.OpticalFlowRiderDetectorFast
import com.mtbanalyzer.detector.HybridRiderDetector
import kotlinx.coroutines.*

class DetectionTuningActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DetectionTuning"
    }

    // UI Components
    private lateinit var toolbar: MaterialToolbar
    private lateinit var btnLoadVideo: ImageButton
    private lateinit var playerView: PlayerView
    private lateinit var graphicOverlay: GraphicOverlay
    private lateinit var detectionStatus: TextView
    private lateinit var noVideoPlaceholder: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var currentTime: TextView
    private lateinit var totalTime: TextView
    private lateinit var btnFrameBack: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnFrameForward: ImageButton
    private lateinit var detectorSpinner: Spinner
    private lateinit var detectorDescription: TextView
    private lateinit var parametersContainer: LinearLayout
    private lateinit var timelineContainer: LinearLayout
    private lateinit var resultsContainer: LinearLayout
    private lateinit var resultsText: TextView
    private lateinit var btnRunDetection: MaterialButton
    private lateinit var btnCompare: MaterialButton
    private lateinit var btnBenchmark: MaterialButton
    private lateinit var progressOverlay: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView

    // Media Player
    private var exoPlayer: ExoPlayer? = null
    private var videoUri: Uri? = null
    private var videoDuration = 0L
    private var isPlaying = false

    // Frame extraction
    private lateinit var frameExtractor: VideoFrameExtractor

    // Detector
    private var currentDetector: RiderDetector? = null
    private val detectorTypes = listOf(
        RiderDetectorManager.DETECTOR_POSE to "ML Kit Pose Detection",
        RiderDetectorManager.DETECTOR_MOTION to "Motion Detection",
        RiderDetectorManager.DETECTOR_OPTICAL_FLOW to "Optical Flow Detection",
        RiderDetectorManager.DETECTOR_HYBRID to "Hybrid Detection"
    )

    // Coroutines
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var seekBarUpdateRunnable: Runnable? = null

    // Current detection state
    private var currentDetectionResult: RiderDetector.DetectionResult? = null
    private var detectionSession: DetectionSession? = null

    // Video picker launcher
    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                loadVideo(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_detection_tuning)

            initializeViews()
            setupToolbar()
            setupDetectorSpinner()
            setupPlaybackControls()
            setupActionButtons()

            frameExtractor = VideoFrameExtractor(this)

            // Initialize with default detector (delayed to avoid crash on startup)
            // switchDetector will be called when spinner selection fires
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            Toast.makeText(this, "Error initializing: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        btnLoadVideo = findViewById(R.id.btnLoadVideo)
        playerView = findViewById(R.id.playerView)
        graphicOverlay = findViewById(R.id.graphicOverlay)
        detectionStatus = findViewById(R.id.detectionStatus)
        noVideoPlaceholder = findViewById(R.id.noVideoPlaceholder)
        seekBar = findViewById(R.id.seekBar)
        currentTime = findViewById(R.id.currentTime)
        totalTime = findViewById(R.id.totalTime)
        btnFrameBack = findViewById(R.id.btnFrameBack)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnFrameForward = findViewById(R.id.btnFrameForward)
        detectorSpinner = findViewById(R.id.detectorSpinner)
        detectorDescription = findViewById(R.id.detectorDescription)
        parametersContainer = findViewById(R.id.parametersContainer)
        timelineContainer = findViewById(R.id.timelineContainer)
        resultsContainer = findViewById(R.id.resultsContainer)
        resultsText = findViewById(R.id.resultsText)
        btnRunDetection = findViewById(R.id.btnRunDetection)
        btnCompare = findViewById(R.id.btnCompare)
        btnBenchmark = findViewById(R.id.btnBenchmark)
        progressOverlay = findViewById(R.id.progressOverlay)
        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            finish()
        }

        btnLoadVideo.setOnClickListener {
            openVideoPicker()
        }
    }

    private fun setupDetectorSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            detectorTypes.map { it.second }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        detectorSpinner.adapter = adapter

        // Set listener after a short delay to avoid immediate initialization issues
        detectorSpinner.post {
            detectorSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val detectorType = detectorTypes[position].first
                    switchDetector(detectorType)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    private fun setupPlaybackControls() {
        // Initialize ExoPlayer
        exoPlayer = ExoPlayer.Builder(this).build()
        playerView.player = exoPlayer
        playerView.useController = false

        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        videoDuration = exoPlayer?.duration ?: 0L
                        seekBar.max = videoDuration.toInt()
                        totalTime.text = formatTime(videoDuration)
                        noVideoPlaceholder.visibility = View.GONE
                        startSeekBarUpdater()
                    }
                    Player.STATE_ENDED -> {
                        isPlaying = false
                        updatePlayPauseButton()
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                updatePlayPauseButton()
            }
        })

        // Play/Pause
        btnPlayPause.setOnClickListener {
            if (isPlaying) {
                exoPlayer?.pause()
            } else {
                val currentPos = exoPlayer?.currentPosition ?: 0
                val duration = exoPlayer?.duration ?: 0
                if (currentPos >= duration - 500) {
                    exoPlayer?.seekTo(0)
                }
                exoPlayer?.play()
            }
        }

        // Frame step forward
        btnFrameForward.setOnClickListener {
            stepFrame(forward = true)
        }

        // Frame step backward
        btnFrameBack.setOnClickListener {
            stepFrame(forward = false)
        }

        // Seek bar
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    exoPlayer?.seekTo(progress.toLong())
                    currentTime.text = formatTime(progress.toLong())
                    // Process detection on current frame when seeking
                    processCurrentFrame()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                exoPlayer?.pause()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupActionButtons() {
        btnRunDetection.setOnClickListener {
            if (videoUri != null) {
                runFullDetection()
            } else {
                Toast.makeText(this, "Please load a video first", Toast.LENGTH_SHORT).show()
            }
        }

        btnCompare.setOnClickListener {
            Toast.makeText(this, "Compare mode coming in Phase 5", Toast.LENGTH_SHORT).show()
        }

        btnBenchmark.setOnClickListener {
            Toast.makeText(this, "Benchmarking coming in Phase 6", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openVideoPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        videoPickerLauncher.launch(intent)
    }

    private fun loadVideo(uri: Uri) {
        videoUri = uri
        Log.d(TAG, "Loading video: $uri")

        // Load into ExoPlayer
        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()

        // Load into frame extractor
        coroutineScope.launch {
            try {
                frameExtractor.setVideoSource(uri)
                Log.d(TAG, "Frame extractor ready: ${frameExtractor.width}x${frameExtractor.height}, " +
                        "${frameExtractor.durationMs}ms, ${frameExtractor.frameRate}fps")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading video into frame extractor", e)
                Toast.makeText(this@DetectionTuningActivity,
                    "Error loading video: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        // Reset detection state
        currentDetector?.reset()
        currentDetectionResult = null
        detectionSession = null
        resultsContainer.visibility = View.GONE
        timelineContainer.visibility = View.GONE
    }

    private fun switchDetector(detectorType: String) {
        Log.d(TAG, "Switching to detector: $detectorType")

        try {
            // Release current detector
            currentDetector?.release()

            // Create new detector
            currentDetector = when (detectorType) {
                RiderDetectorManager.DETECTOR_POSE -> {
                    val options = PoseDetectorOptions.Builder()
                        .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
                        .build()
                    val poseDetector = PoseDetection.getClient(options)
                    PoseRiderDetector(poseDetector)
                }
                RiderDetectorManager.DETECTOR_MOTION -> MotionRiderDetector()
                RiderDetectorManager.DETECTOR_OPTICAL_FLOW -> OpticalFlowRiderDetectorFast()
                RiderDetectorManager.DETECTOR_HYBRID -> {
                    val options = PoseDetectorOptions.Builder()
                        .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
                        .build()
                    val poseDetector = PoseDetection.getClient(options)
                    val poseRiderDetector = PoseRiderDetector(poseDetector)
                    val motionRiderDetector = MotionRiderDetector()
                    HybridRiderDetector(poseRiderDetector, motionRiderDetector)
                }
                else -> {
                    val options = PoseDetectorOptions.Builder()
                        .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
                        .build()
                    val poseDetector = PoseDetection.getClient(options)
                    PoseRiderDetector(poseDetector)
                }
            }

            // Update UI
            detectorDescription.text = currentDetector?.getDescription() ?: ""

            // Generate parameter controls
            generateParameterControls()

            // Process current frame with new detector (only if video loaded)
            if (videoUri != null) {
                processCurrentFrame()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error switching detector to $detectorType", e)
            Toast.makeText(this, "Error initializing detector: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun generateParameterControls() {
        // Clear existing parameters (keep the title)
        val childCount = parametersContainer.childCount
        if (childCount > 1) {
            parametersContainer.removeViews(1, childCount - 1)
        }

        val configOptions = currentDetector?.getConfigOptions() ?: return

        for ((key, option) in configOptions) {
            val paramView = when (option.type) {
                ConfigType.BOOLEAN -> createBooleanControl(option)
                ConfigType.INTEGER, ConfigType.FLOAT -> createSliderControl(option)
                else -> null
            }
            paramView?.let { parametersContainer.addView(it) }
        }
    }

    private fun createBooleanControl(option: RiderDetector.ConfigOption): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8.dpToPx()
            }
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val label = TextView(this).apply {
            text = option.displayName
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val switch = SwitchMaterial(this).apply {
            isChecked = option.defaultValue as? Boolean ?: false
            setOnCheckedChangeListener { _, isChecked ->
                updateDetectorConfig(option.key, isChecked)
            }
        }

        layout.addView(label)
        layout.addView(switch)
        return layout
    }

    private fun createSliderControl(option: RiderDetector.ConfigOption): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12.dpToPx()
            }
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val label = TextView(this).apply {
            text = option.displayName
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val valueText = TextView(this).apply {
            setTextColor(android.graphics.Color.LTGRAY)
            textSize = 14f
        }

        headerLayout.addView(label)
        headerLayout.addView(valueText)

        val isFloat = option.type == ConfigType.FLOAT
        val min = (option.minValue as? Number)?.toFloat() ?: 0f
        val max = (option.maxValue as? Number)?.toFloat() ?: 100f
        val defaultVal = (option.defaultValue as? Number)?.toFloat() ?: min

        // For floats, use 0-100 and divide by 100
        val sliderMin = if (isFloat) 0f else min
        val sliderMax = if (isFloat) 100f else max
        val sliderDefault = if (isFloat) (defaultVal * 100) else defaultVal

        val slider = Slider(this).apply {
            valueFrom = sliderMin
            valueTo = sliderMax
            value = sliderDefault.coerceIn(sliderMin, sliderMax)
            stepSize = if (isFloat) 1f else 1f

            addOnChangeListener { _, value, _ ->
                val actualValue = if (isFloat) value / 100.0 else value.toInt()
                valueText.text = if (isFloat) String.format("%.2f", actualValue) else actualValue.toString()
                updateDetectorConfig(option.key, actualValue)
            }
        }

        // Set initial value text
        val initialValue = if (isFloat) sliderDefault / 100.0 else sliderDefault.toInt()
        valueText.text = if (isFloat) String.format("%.2f", initialValue) else initialValue.toString()

        layout.addView(headerLayout)
        layout.addView(slider)
        return layout
    }

    private fun updateDetectorConfig(key: String, value: Any) {
        val settings = mutableMapOf<String, Any>()
        settings[key] = value
        currentDetector?.configure(settings)

        // Re-process current frame with new settings
        processCurrentFrame()
    }

    private fun processCurrentFrame() {
        val uri = videoUri ?: return
        val detector = currentDetector ?: return
        val position = exoPlayer?.currentPosition ?: return

        coroutineScope.launch {
            try {
                val imageProxy = frameExtractor.extractFrameAsImageProxy(position)
                if (imageProxy != null) {
                    val startTime = System.currentTimeMillis()
                    val result = detector.processFrame(imageProxy, graphicOverlay)
                    val processingTime = System.currentTimeMillis() - startTime

                    currentDetectionResult = result
                    updateDetectionStatus(result, processingTime)

                    // Close the image proxy
                    imageProxy.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing frame", e)
            }
        }
    }

    private fun updateDetectionStatus(result: RiderDetector.DetectionResult, processingTimeMs: Long) {
        val statusText = if (result.riderDetected) {
            "Detected (${String.format("%.1f", result.confidence * 100)}%)"
        } else {
            "No Detection"
        }

        detectionStatus.text = "$statusText\n${processingTimeMs}ms"
        detectionStatus.setBackgroundResource(
            if (result.riderDetected) R.drawable.status_badge_detected
            else R.drawable.status_badge_background
        )
    }

    private fun runFullDetection() {
        val uri = videoUri ?: return
        val detector = currentDetector ?: return

        showProgress("Preparing detection...")

        coroutineScope.launch {
            try {
                val results = mutableListOf<DetectionFrameResult>()
                val frameInterval = 100L // Process every 100ms
                var currentMs = 0L
                val totalMs = frameExtractor.durationMs
                var frameIndex = 0

                // Reset detector state
                detector.reset()

                while (currentMs <= totalMs) {
                    val progress = ((currentMs.toFloat() / totalMs) * 100).toInt()
                    updateProgress(progress, "Processing frame $frameIndex...")

                    val imageProxy = frameExtractor.extractFrameAsImageProxy(currentMs)
                    if (imageProxy != null) {
                        val startTime = System.currentTimeMillis()
                        val result = detector.processFrame(imageProxy, graphicOverlay)
                        val processingTime = System.currentTimeMillis() - startTime

                        results.add(DetectionFrameResult.fromDetectionResult(
                            frameIndex = frameIndex,
                            timestampMs = currentMs,
                            result = result,
                            processingTimeMs = processingTime
                        ))

                        imageProxy.close()
                    }

                    currentMs += frameInterval
                    frameIndex++
                }

                // Create session
                detectionSession = DetectionSession(
                    videoUri = uri,
                    detectorType = detector.getDisplayName(),
                    parameters = emptyMap(), // TODO: capture current parameters
                    results = results,
                    totalFrames = frameIndex,
                    processedFrames = results.size
                )

                hideProgress()
                showResults(detectionSession!!)

            } catch (e: Exception) {
                Log.e(TAG, "Error running detection", e)
                hideProgress()
                Toast.makeText(this@DetectionTuningActivity,
                    "Detection error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showProgress(message: String) {
        progressOverlay.visibility = View.VISIBLE
        progressBar.progress = 0
        progressText.text = message
    }

    private fun updateProgress(progress: Int, message: String) {
        mainHandler.post {
            progressBar.progress = progress
            progressText.text = message
        }
    }

    private fun hideProgress() {
        mainHandler.post {
            progressOverlay.visibility = View.GONE
        }
    }

    private fun showResults(session: DetectionSession) {
        resultsContainer.visibility = View.VISIBLE
        timelineContainer.visibility = View.VISIBLE

        val detectionCount = session.results.count { it.riderDetected }
        val resultsString = buildString {
            appendLine("Detector: ${session.detectorType}")
            appendLine("Frames processed: ${session.processedFrames}")
            appendLine("Detections: $detectionCount (${String.format("%.1f", session.detectionRate * 100)}%)")
            appendLine("Avg processing time: ${String.format("%.1f", session.avgProcessingTimeMs)}ms")
            appendLine("Avg confidence: ${String.format("%.1f", session.avgConfidence * 100)}%")
        }

        resultsText.text = resultsString

        // TODO: Update timeline visualization (Phase 4)
    }

    private fun stepFrame(forward: Boolean) {
        exoPlayer?.pause()
        val currentPos = exoPlayer?.currentPosition ?: 0
        val frameMs = (1000.0 / (frameExtractor.frameRate.takeIf { it > 0 } ?: 30f)).toLong()

        val newPos = if (forward) {
            minOf(currentPos + frameMs, videoDuration - 1)
        } else {
            maxOf(currentPos - frameMs, 0)
        }

        exoPlayer?.seekTo(newPos)
        currentTime.text = formatTime(newPos)
        seekBar.progress = newPos.toInt()

        // Process detection on new frame
        processCurrentFrame()
    }

    private fun startSeekBarUpdater() {
        seekBarUpdateRunnable = object : Runnable {
            override fun run() {
                if (isPlaying) {
                    val currentPos = exoPlayer?.currentPosition ?: 0
                    seekBar.progress = currentPos.toInt()
                    currentTime.text = formatTime(currentPos)
                }
                mainHandler.postDelayed(this, 100)
            }
        }
        mainHandler.post(seekBarUpdateRunnable!!)
    }

    private fun updatePlayPauseButton() {
        btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun formatTime(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / 1000) / 60
        return String.format("%d:%02d", minutes, seconds)
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        seekBarUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
        exoPlayer?.release()
        currentDetector?.release()
        frameExtractor.release()
    }
}
