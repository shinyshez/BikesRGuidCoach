package com.mtbanalyzer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.Calendar

class MainActivity : AppCompatActivity(), 
    CameraManager.CameraCallback,
    RecordingManager.RecordingCallback,
    PoseDetectionProcessor.PoseDetectionCallback {

    private lateinit var viewFinder: PreviewView
    private lateinit var graphicOverlay: GraphicOverlay
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var poseDetector: PoseDetector
    
    // New architecture components
    private lateinit var cameraManager: CameraManager
    private lateinit var recordingManager: RecordingManager
    private lateinit var poseDetectionProcessor: PoseDetectionProcessor
    private lateinit var uiStateManager: UIStateManager
    
    // UI elements
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var recordingStatus: TextView
    private lateinit var recordingOverlay: View
    private lateinit var confidenceText: TextView
    private lateinit var pulseRing: View
    private lateinit var recordingProgress: android.widget.ProgressBar
    private lateinit var recordingDot: View
    private lateinit var segmentCount: TextView
    private lateinit var timeDisplay: TextView

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
            statusIndicator = findViewById(R.id.statusIndicator)
            statusText = findViewById(R.id.statusText)
            recordingStatus = findViewById(R.id.recordingStatus)
            recordingOverlay = findViewById(R.id.recordingOverlay)
            confidenceText = findViewById(R.id.confidenceText)
            pulseRing = findViewById(R.id.pulseRing)
            recordingProgress = findViewById(R.id.recordingProgress)
            recordingDot = findViewById(R.id.recordingDot)
            segmentCount = findViewById(R.id.segmentCount)
            timeDisplay = findViewById(R.id.timeDisplay)
            
            // Initialize navigation buttons
            findViewById<android.widget.ImageButton>(R.id.settingsButton).setOnClickListener {
                startActivity(android.content.Intent(this, SettingsActivity::class.java))
            }
            
            findViewById<android.widget.ImageButton>(R.id.galleryButton).setOnClickListener {
                startActivity(android.content.Intent(this, VideoGalleryActivity::class.java))
            }
            
            // Update time display
            updateTimeDisplay()
            
            // Update video count
            updateVideoCount()
            
            // Test button for manual recording
            findViewById<android.widget.Button>(R.id.testRecordButton).setOnClickListener {
                manualStartRecording()
            }

            // Initialize pose detector for rider detection
            val options = PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
            poseDetector = PoseDetection.getClient(options)

            cameraExecutor = Executors.newSingleThreadExecutor()
            
            // Initialize new architecture components
            initializeComponents()

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

    private fun initializeComponents() {
        val settingsManager = SettingsManager(this)
        cameraManager = CameraManager(this, this, cameraExecutor)
        recordingManager = RecordingManager(this, contentResolver)
        poseDetectionProcessor = PoseDetectionProcessor(poseDetector, graphicOverlay, settingsManager)
        uiStateManager = UIStateManager(
            this, statusIndicator, statusText, recordingStatus, recordingOverlay,
            confidenceText, pulseRing, recordingProgress, recordingDot
        )
        
        // Set callbacks
        poseDetectionProcessor.setCallback(this)
    }
    
    private fun updateTimeDisplay() {
        val currentTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        timeDisplay.text = currentTime
        
        // Update every minute
        timeDisplay.postDelayed({ updateTimeDisplay() }, 60000)
    }
    
    private fun updateVideoCount() {
        try {
            val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED)
            val selection = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("MTB_%")
            
            val cursor = contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )
            
            var todayCount = 0
            val todayStart = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis / 1000 // MediaStore uses seconds
            
            cursor?.use {
                val dateColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (it.moveToNext()) {
                    val dateAdded = it.getLong(dateColumn)
                    if (dateAdded >= todayStart) {
                        todayCount++
                    }
                }
            }
            
            segmentCount.text = "$todayCount videos recorded today"
            
        } catch (e: Exception) {
            Log.e(TAG, "Error counting videos", e)
            segmentCount.text = "0 videos recorded today"
        }
    }

    private fun startCamera() {
        cameraManager.startCamera(viewFinder, graphicOverlay, poseDetectionProcessor, this)
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
            // Notify pose processor that recording has actually started
            poseDetectionProcessor.setRecordingStarted()
        }
    }

    override fun onRecordingFinished(success: Boolean, uri: String?, error: String?) {
        runOnUiThread {
            if (success) {
                uiStateManager.updateRecordingFinished(true, "Video saved!")
                // Update video count after successful recording
                updateVideoCount()
            } else {
                uiStateManager.updateRecordingFinished(false, error ?: "Recording failed")
            }
            // Notify pose processor that recording has stopped
            poseDetectionProcessor.setRecordingStopped()
        }
    }

    override fun onRecordingProgress(elapsedMs: Long) {
        runOnUiThread {
            uiStateManager.updateRecordingProgress(elapsedMs)
        }
    }

    // PoseDetectionProcessor.PoseDetectionCallback implementation
    override fun onRiderDetected(confidence: Double) {
        Log.d(TAG, "Rider detected with confidence: $confidence")
    }

    override fun onRiderLost() {
        Log.d(TAG, "Rider lost")
    }

    override fun onUpdateUI(riderDetected: Boolean, confidence: Double) {
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
        } else {
            Log.d(TAG, "Stopping manual recording...")
            recordingManager.stopRecording()
        }
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
        // Update video count when returning to the app
        updateVideoCount()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        recordingManager.release()
        cameraManager.release()
        poseDetectionProcessor.release()
        cameraExecutor.shutdown()
        poseDetector.close()
    }
}