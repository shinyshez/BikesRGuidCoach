package com.mtbanalyzer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.mtbanalyzer.detector.RiderDetectorManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.Calendar

class MainActivity : AppCompatActivity(), 
    CameraManager.CameraCallback,
    RecordingManager.RecordingCallback,
    RiderDetectionProcessor.RiderDetectionCallback,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var viewFinder: PreviewView
    private lateinit var graphicOverlay: GraphicOverlay
    private lateinit var cameraExecutor: ExecutorService
    // New architecture components
    private lateinit var cameraManager: CameraManager
    private lateinit var recordingManager: RecordingManager
    private lateinit var riderDetectionProcessor: RiderDetectionProcessor
    private lateinit var riderDetectorManager: RiderDetectorManager
    private lateinit var uiStateManager: UIStateManager
    private lateinit var settingsManager: SettingsManager
    
    // UI elements
    private lateinit var statusChip: View
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var recordingProgress: android.widget.ProgressBar
    private lateinit var modeManual: TextView
    private lateinit var modeAuto: TextView
    private lateinit var recordButton: android.widget.ImageButton
    private lateinit var recentClipsContainer: View
    private lateinit var recentClipsList: RecyclerView
    private lateinit var clipCountText: TextView
    private lateinit var bottomGradient: View
    private lateinit var recentClipsAdapter: RecentClipsAdapter
    // Clip ids on screen when it opened; anything newer gets a NEW tag in the strip
    private var knownClipIds: Set<Long>? = null
    
    // Wake lock for preventing sleep during detection
    private lateinit var wakeLock: PowerManager.WakeLock
    
    // Timer for manual recording progress
    private var recordingProgressTimer: java.util.Timer? = null

    companion object {
        private const val TAG = "MTBAnalyzer"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_main)

            viewFinder = findViewById(R.id.viewFinder)
            graphicOverlay = findViewById(R.id.graphicOverlay)
            
            // Initialize UI elements
            statusChip = findViewById(R.id.statusChip)
            statusDot = findViewById(R.id.statusDot)
            statusText = findViewById(R.id.statusText)
            recordingProgress = findViewById(R.id.recordingProgress)
            modeManual = findViewById(R.id.modeManual)
            modeAuto = findViewById(R.id.modeAuto)
            recordButton = findViewById(R.id.recordButton)
            recentClipsContainer = findViewById(R.id.recentClipsContainer)
            recentClipsList = findViewById(R.id.recentClipsList)
            clipCountText = findViewById(R.id.clipCountText)
            bottomGradient = findViewById(R.id.bottomGradient)
            
            // Initialize navigation buttons
            findViewById<android.widget.ImageButton>(R.id.settingsButton).setOnClickListener {
                startActivity(android.content.Intent(this, SettingsActivity::class.java))
            }
            
            findViewById<android.widget.ImageButton>(R.id.galleryButton).setOnClickListener {
                startActivity(android.content.Intent(this, VideoGalleryActivity::class.java))
            }
            
            // Today's clips: tap one to play it straight from the camera
            recentClipsAdapter = RecentClipsAdapter { clip ->
                startActivity(android.content.Intent(this, VideoPlaybackActivity::class.java).apply {
                    putExtra(VideoPlaybackActivity.EXTRA_VIDEO_URI, clip.uri.toString())
                    putExtra(VideoPlaybackActivity.EXTRA_VIDEO_NAME, clip.displayName)
                })
            }
            recentClipsList.adapter = recentClipsAdapter
            loadRecentClips()
            
            recordButton.setOnClickListener {
                manualStartRecording()
            }

            // Initialize rider detection system

            cameraExecutor = Executors.newSingleThreadExecutor()
            
            // Initialize new architecture components
            initializeComponents(coldLaunch = savedInstanceState == null)

            Log.d(TAG, "App started successfully")

            if (allPermissionsGranted()) {
                Log.d(TAG, "All permissions granted, starting camera")
                startCamera()
            } else {
                Log.d(TAG, "Requesting permissions")
                ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            Toast.makeText(this, "Error starting app: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun initializeComponents(coldLaunch: Boolean) {
        settingsManager = SettingsManager(this)
        // Auto-record is per session: the app always opens with it off so it never starts
        // recording on its own. It can be turned on for the session from the toggle, the
        // settings screen or the remote. (A rotation is not a cold launch and keeps it.)
        if (coldLaunch) settingsManager.setRiderDetectionEnabled(false)
        cameraManager = CameraManager(this, this, cameraExecutor)
        recordingManager = RecordingManager(this, contentResolver)
        
        // Initialize rider detection system
        riderDetectorManager = RiderDetectorManager(this, settingsManager)
        riderDetectorManager.initialize()
        
        riderDetectionProcessor = RiderDetectionProcessor(
            riderDetectorManager, graphicOverlay, settingsManager
        )
        
        uiStateManager = UIStateManager(this, statusChip, statusDot, statusText, recordingProgress)
        
        // Set callbacks
        riderDetectionProcessor.setCallback(this)
        
        // Initialize performance overlay state from settings
        if (settingsManager.shouldShowPerformanceOverlay()) {
            riderDetectionProcessor.togglePerformanceOverlay()
        }
        
        // Listen for settings changes
        settingsManager.registerChangeListener(this)
        
        // Initialize wake lock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MTBAnalyzer::RiderDetectionWakeLock"
        )
        
        // Mode pill: Manual | Auto
        updateModeUi(settingsManager.isRiderDetectionEnabled())
        modeManual.setOnClickListener { setAutoRecord(false) }
        modeAuto.setOnClickListener { setAutoRecord(true) }
    }
    
    /** Arms or disarms auto-record for this session and reflects it everywhere. */
    private fun setAutoRecord(enabled: Boolean) {
        if (settingsManager.isRiderDetectionEnabled() == enabled) return
        settingsManager.setRiderDetectionEnabled(enabled)
        updateModeUi(enabled)
        updateDetectionState(enabled)
    }
    
    private fun updateModeUi(auto: Boolean) {
        val black = ContextCompat.getColor(this, android.R.color.black)
        val dim = 0xFFDDDDDD.toInt()
        modeManual.setBackgroundResource(if (auto) 0 else R.drawable.mode_segment_selected)
        modeManual.setTextColor(if (auto) dim else black)
        modeAuto.setBackgroundResource(if (auto) R.drawable.mode_segment_selected else 0)
        modeAuto.setTextColor(if (auto) black else dim)
        modeManual.isSelected = !auto
        modeAuto.isSelected = auto
    }
    
    
    /** Today's MTB_ clips, newest first, for the strip above the record button. */
    private fun loadRecentClips() {
        val clips = mutableListOf<VideoItem>()
        try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE
            )
            val todayStart = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis / 1000 // MediaStore uses seconds
            val selection = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Video.Media.DATE_ADDED} >= ?"
            val selectionArgs = arrayOf("MTB_%", todayStart.toString())
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                while (cursor.moveToNext() && clips.size < 30) {
                    val id = cursor.getLong(idCol)
                    clips.add(VideoItem(
                        id = id,
                        uri = android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        displayName = cursor.getString(nameCol) ?: "MTB_$id.mp4",
                        dateAdded = cursor.getLong(dateCol),
                        duration = cursor.getLong(durCol),
                        size = cursor.getLong(sizeCol)
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading today's clips", e)
        }
        val known = knownClipIds ?: clips.map { it.id }.toSet().also { knownClipIds = it }
        val newIds = clips.map { it.id }.filterNot { it in known }.toSet()
        recentClipsAdapter.submit(clips, newIds)
        clipCountText.text = if (clips.size == 1) "1 clip" else "${clips.size} clips"
        recentClipsContainer.visibility = if (clips.isEmpty()) View.GONE else View.VISIBLE
        val gradientDp = if (clips.isEmpty()) 170 else 290
        bottomGradient.layoutParams = bottomGradient.layoutParams.apply {
            height = (gradientDp * resources.displayMetrics.density).toInt()
        }
        if (newIds.isNotEmpty()) recentClipsList.scrollToPosition(0)
    }

    private fun startCamera() {
        cameraManager.startCamera(viewFinder, graphicOverlay, riderDetectionProcessor, this)
        
        // After camera starts, monitor for preview bounds
        viewFinder.viewTreeObserver.addOnGlobalLayoutListener {
            updatePreviewBounds()
        }
        
        // Initialize detection state based on settings
        updateDetectionState(settingsManager.isRiderDetectionEnabled())
    }
    
    private fun updatePreviewBounds() {
        // Preview bounds are now correctly calculated and set by CameraManager
        // This method is no longer needed but kept for potential future use
    }

    // CameraManager.CameraCallback implementation
    override fun onCameraReady() {
        Log.d(TAG, "Camera ready")
        uiStateManager.updateDetectionState(false, 0.0, false)
    }

    override fun onCameraError(error: String) {
        Log.e(TAG, "Camera error: $error")
        uiStateManager.updateError(error)
        Toast.makeText(this, "Camera error: $error", Toast.LENGTH_LONG).show()
    }

    // RecordingManager.RecordingCallback implementation
    override fun onRecordingStarted() {
        runOnUiThread {
            uiStateManager.updateRecordingStarted()
            // Notify rider detection processor that recording has actually started
            riderDetectionProcessor.setRecordingStarted()
            // Update record button to show recording state
            recordButton.setImageResource(R.drawable.ic_recording)
            recordButton.contentDescription = "Stop recording"
        }
    }

    override fun onRecordingFinished(success: Boolean, uri: String?, error: String?) {
        runOnUiThread {
            if (success) {
                uiStateManager.updateRecordingFinished(true, "Saved")
                // The new clip lands in the strip with a NEW tag
                loadRecentClips()
            } else {
                uiStateManager.updateRecordingFinished(false, error ?: "Recording failed")
            }
            // Notify rider detection processor that recording has stopped
            riderDetectionProcessor.setRecordingStopped()
            
            // Reset record button to normal state
            recordButton.setImageResource(R.drawable.ic_record)
            recordButton.contentDescription = "Record"
            
            // Stop progress timer in case this was manual recording
            stopRecordingProgressTimer()
        }
    }

    override fun onRecordingProgress(elapsedMs: Long) {
        runOnUiThread {
            uiStateManager.updateRecordingProgress(elapsedMs)
        }
    }

    // RiderDetectionProcessor.RiderDetectionCallback implementation
    override fun onRiderDetected(confidence: Double, debugInfo: String) {
        Log.d(TAG, "Rider detected with confidence: $confidence, debug: $debugInfo")
    }

    override fun onRiderLost() {
        Log.d(TAG, "Rider lost")
    }

    override fun onUpdateUI(riderDetected: Boolean, confidence: Double, debugInfo: String) {
        runOnUiThread {
            uiStateManager.updateDetectionState(
                riderDetected, 
                confidence, 
                recordingManager.isRecording()
            )
        }
    }

    override fun onStartRecording() {
        val videoCapture = cameraManager.videoCapture
        if (videoCapture != null && uiStateManager.canStartRecording()) {
            recordingManager.startRecording(videoCapture, this)
        } else {
            Log.w(TAG, "Cannot start recording - videoCapture=$videoCapture, canStart=${uiStateManager.canStartRecording()}")
        }
    }

    override fun onStopRecording() {
        if (recordingManager.isRecording()) {
            recordingManager.stopRecording()
            // Will be marked as stopped in onRecordingFinished callback
        }
    }

    override fun onUpdateRecordingProgress(elapsedMs: Long) {
        if (recordingManager.isRecording()) {
            runOnUiThread {
                uiStateManager.updateRecordingProgress(elapsedMs)
            }
        }
    }

    private fun manualStartRecording() {
        Log.d(TAG, "Manual test record button clicked")
        val videoCapture = cameraManager.videoCapture
        if (videoCapture != null && !recordingManager.isRecording()) {
            Log.d(TAG, "Starting manual recording...")
            recordingManager.startRecording(videoCapture, this)
            
            // Start progress timer for manual recording
            startRecordingProgressTimer()
        } else {
            Log.d(TAG, "Stopping manual recording...")
            recordingManager.stopRecording()
            
            // Stop progress timer
            stopRecordingProgressTimer()
        }
    }
    
    private fun startRecordingProgressTimer() {
        stopRecordingProgressTimer() // Cancel any existing timer
        
        recordingProgressTimer = java.util.Timer()
        recordingProgressTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                if (recordingManager.isRecording()) {
                    val elapsedMs = recordingManager.getElapsedTime()
                    runOnUiThread {
                        uiStateManager.updateRecordingProgress(elapsedMs)
                    }
                    
                    // Check if we've reached max duration
                    val maxDurationMs = settingsManager.getRecordingDurationMs()
                    if (elapsedMs >= maxDurationMs) {
                        Log.d(TAG, "Max recording duration reached, stopping...")
                        runOnUiThread {
                            recordingManager.stopRecording()
                            stopRecordingProgressTimer()
                        }
                    }
                } else {
                    // Recording stopped, cancel timer
                    stopRecordingProgressTimer()
                }
            }
        }, 100, 100) // Update every 100ms
    }
    
    private fun stopRecordingProgressTimer() {
        recordingProgressTimer?.cancel()
        recordingProgressTimer = null
    }

    

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Pick up clips imported or deleted while away
        if (::recentClipsAdapter.isInitialized) loadRecentClips()
    }
    
    // SharedPreferences.OnSharedPreferenceChangeListener implementation
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            "detector_type" -> {
                val detectorType = settingsManager.getDetectorType()
                Log.d(TAG, "Detector type changed to: $detectorType")
                riderDetectionProcessor.switchDetector(detectorType)
                Toast.makeText(this, "Switched to ${getDetectorDisplayName(detectorType)}", Toast.LENGTH_SHORT).show()
            }
            "rider_detection_enabled" -> {
                val enabled = settingsManager.isRiderDetectionEnabled()
                Log.d(TAG, "Rider detection enabled changed to: $enabled")
                // Keep the mode pill in step with the settings screen / remote
                updateModeUi(enabled)
                updateDetectionState(enabled)
            }
            "motion_threshold", "min_motion_area", "show_motion_overlay" -> {
                // Motion detection settings changed
                Log.d(TAG, "Motion detection settings changed: $key")
                riderDetectorManager.applyCurrentSettings()
            }
            "show_performance_overlay" -> {
                val showOverlay = settingsManager.shouldShowPerformanceOverlay()
                Log.d(TAG, "Performance overlay setting changed to: $showOverlay")
                if (showOverlay) {
                    riderDetectionProcessor.togglePerformanceOverlay()
                } else if (riderDetectionProcessor.isPerformanceOverlayEnabled()) {
                    riderDetectionProcessor.togglePerformanceOverlay()
                }
            }
        }
    }
    
    private fun updateDetectionState(enabled: Boolean) {
        if (enabled) {
            // Enable detection and keep screen on
            riderDetectionProcessor.setDetectionEnabled(true)
            
            uiStateManager.setAutoMode(true)
            
            // Keep screen on using window flags (most reliable for camera apps)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            
            // Also acquire wake lock as backup
            if (!wakeLock.isHeld) {
                wakeLock.acquire()
                Log.d(TAG, "Screen kept on and wake lock acquired - detection enabled")
            }
        } else {
            // Disable detection and allow screen to sleep
            riderDetectionProcessor.setDetectionEnabled(false)
            
            uiStateManager.setAutoMode(false)
            
            // Allow screen to turn off
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            
            // Release wake lock
            if (wakeLock.isHeld) {
                wakeLock.release()
                Log.d(TAG, "Screen sleep allowed and wake lock released - detection disabled")
            }
        }
    }
    
    private fun getDetectorDisplayName(detectorType: String): String {
        return when(detectorType) {
            "pose" -> "ML Kit Pose Detection"
            "motion" -> "Motion Detection"
            "optical_flow" -> "Optical Flow Detection"
            "hybrid" -> "Hybrid Detection"
            else -> "Unknown Detector"
        }
    }

    // Volume key interception for Bluetooth remote control
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (::settingsManager.isInitialized && settingsManager.isBluetoothRemoteEnabled()) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    // Volume up: Start/stop manual recording
                    Log.d(TAG, "Volume up pressed - Bluetooth remote control")
                    if (recordingManager.isRecording()) {
                        Log.d(TAG, "Stopping recording via Bluetooth remote")
                        recordingManager.stopRecording()
                        Toast.makeText(this, "Recording stopped (remote)", Toast.LENGTH_SHORT).show()
                    } else {
                        Log.d(TAG, "Starting recording via Bluetooth remote")
                        manualStartRecording()
                        Toast.makeText(this, "Recording started (remote)", Toast.LENGTH_SHORT).show()
                    }
                    return true // Consume the event
                }
                
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    // Volume down: Toggle rider detection
                    Log.d(TAG, "Volume down pressed - Bluetooth remote control")
                    val currentState = settingsManager.isRiderDetectionEnabled()
                    val newState = !currentState
                    setAutoRecord(newState)
                    Toast.makeText(this, 
                        if (newState) "Auto-record enabled (remote)" 
                        else "Auto-record disabled (remote)", 
                        Toast.LENGTH_SHORT).show()
                    return true // Consume the event
                }
            }
        }
        
        // If Bluetooth remote is disabled or key not handled, use default behavior
        return super.onKeyDown(keyCode, event)
    }
    
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        val orientation = if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        Log.d(TAG, "Orientation changed to: $orientation")
        
        // Update UI layout for orientation
        updateLayoutForOrientation(newConfig.orientation)
    }
    
    private fun updateLayoutForOrientation(orientation: Int) {
        val orientation = if (orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        Log.d(TAG, "UI updated for $orientation mode")
        
        // The bottom control panel layout automatically adapts to orientation changes
        // No manual layout adjustments needed since all controls are now in the bottom panel
    }

    override fun onDestroy() {
        super.onDestroy()
        settingsManager.unregisterChangeListener(this)
        recordingManager.release()
        cameraManager.release()
        riderDetectionProcessor.release()
        riderDetectorManager.release()
        cameraExecutor.shutdown()
        
        // Clean up recording timer
        stopRecordingProgressTimer()
        
        // Clear screen on flag and release wake lock
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
            Log.d(TAG, "Screen flag cleared and wake lock released on destroy")
        }
    }
}