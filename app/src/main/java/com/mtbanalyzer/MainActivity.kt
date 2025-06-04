package com.mtbanalyzer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var graphicOverlay: GraphicOverlay
    private lateinit var cameraExecutor: ExecutorService
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var poseDetector: PoseDetector
    
    // UI elements
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var recordingStatus: TextView
    private lateinit var recordingOverlay: TextView

    private var isRiderDetected = false
    private var recordingStartTime = 0L
    private val recordingDurationMs = 8000L // 8 seconds
    private var riderLastSeenTime = 0L
    private val postRiderDelayMs = 2000L // 2 seconds after rider leaves
    private var lastRecordingAttemptTime = 0L
    private val recordingCooldownMs = 1000L // 1 second cooldown between attempts
    
    // Recording state tracking
    private enum class RecordingState {
        IDLE, RECORDING, ERROR, SAVING
    }
    @Volatile
    private var recordingState = RecordingState.IDLE
    private val recordingLock = Object()

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
            
            // Test button for manual recording
            findViewById<android.widget.Button>(R.id.testRecordButton).setOnClickListener {
                Log.d(TAG, "Test record button clicked")
                if (recording == null) {
                    Log.d(TAG, "Starting test recording...")
                    startRecording()
                } else {
                    Log.d(TAG, "Stopping test recording...")
                    stopRecording()
                }
            }

            // Initialize pose detector for rider detection
            val options = PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
            poseDetector = PoseDetection.getClient(options)

            Log.d(TAG, "App started successfully")

            cameraExecutor = Executors.newSingleThreadExecutor()

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

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }
            
            // Setup overlay will be done after preview starts
            viewFinder.post {
                graphicOverlay.setCameraInfo(
                    viewFinder.width,
                    viewFinder.height,
                    GraphicOverlay.CameraFacing.BACK
                )
            }

            // Video capture with fallback strategy
            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.HD, Quality.SD, Quality.LOWEST),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.LOWEST)
            )
            
            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // Image analysis for rider detection
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, RiderDetectionAnalyzer())
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Check if lifecycle is still valid
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.CREATED)) {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, videoCapture, imageAnalyzer
                    )
                    Log.d(TAG, "Camera bound successfully")
                } else {
                    Log.w(TAG, "Lifecycle not ready for camera binding")
                }
            } catch (exc: Exception) {
                Log.e(TAG, "Camera binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private inner class RiderDetectionAnalyzer : ImageAnalysis.Analyzer {
        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                
                // Update overlay with image dimensions
                // Note: For portrait orientation, width and height may need to be swapped
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                //Log.d(TAG, "ImageProxy: ${imageProxy.width}x${imageProxy.height}, rotation: $rotationDegrees")
                
                runOnUiThread {
                    if (rotationDegrees == 90 || rotationDegrees == 270) {
                        // Image is rotated, swap dimensions
                        graphicOverlay.setImageSourceInfo(
                            imageProxy.height,
                            imageProxy.width
                        )
                    } else {
                        graphicOverlay.setImageSourceInfo(
                            imageProxy.width,
                            imageProxy.height
                        )
                    }
                }

                poseDetector.process(image)
                    .addOnSuccessListener { pose ->
                        val currentTime = System.currentTimeMillis()

                        // Draw pose on overlay
                        graphicOverlay.clear()
                        if (pose.allPoseLandmarks.isNotEmpty()) {
                            val poseGraphic = PoseGraphic(graphicOverlay, pose)
                            graphicOverlay.add(poseGraphic)
                        }

                        // Check if a person (rider) is detected
                        val riderCurrentlyDetected = pose.allPoseLandmarks.isNotEmpty()
                        
                        // Calculate average confidence score
                        val avgConfidence = if (riderCurrentlyDetected) {
                            pose.allPoseLandmarks.map { it.inFrameLikelihood }.average()
                        } else 0.0

                        // Update UI with detection status
                        runOnUiThread {
                            updateDetectionUI(riderCurrentlyDetected, avgConfidence)
                        }

                        if (riderCurrentlyDetected) {
                            riderLastSeenTime = currentTime
                            
                            if (!isRiderDetected) {
                                // Rider just entered frame
                                isRiderDetected = true
                                Log.d(TAG, "Rider entered frame. Recording status: ${recording != null}")
                                
                                // Only start new recording if not already recording
                                synchronized(recordingLock) {
                                    val timeSinceLastAttempt = currentTime - lastRecordingAttemptTime
                                    if (recording == null && recordingState == RecordingState.IDLE && 
                                        timeSinceLastAttempt > recordingCooldownMs) {
                                        Log.d(TAG, "Starting new recording...")
                                        lastRecordingAttemptTime = currentTime
                                        startRecording()
                                    } else {
                                        Log.d(TAG, "Rider re-detected - skipping (recording=$recording, state=$recordingState, cooldown=${timeSinceLastAttempt}ms)")
                                    }
                                }
                            }
                        } else if (!riderCurrentlyDetected && isRiderDetected) {
                            // Rider left frame
                            isRiderDetected = false
                            Log.d(TAG, "Rider left frame - continuing recording for 2 seconds")
                        }

                        // Stop recording either after max duration OR 2 seconds after rider left
                        if (recording != null && recordingStartTime > 0) {
                            val elapsedSinceStart = currentTime - recordingStartTime
                            val elapsedSinceRiderLeft = currentTime - riderLastSeenTime
                            
                            if (elapsedSinceStart > recordingDurationMs || 
                                (!isRiderDetected && elapsedSinceRiderLeft > postRiderDelayMs)) {
                                stopRecording()
                            }
                            
                            // Update recording time
                            runOnUiThread {
                                updateRecordingUI(elapsedSinceStart)
                            }
                        }
                    }
                    .addOnFailureListener {
                        Log.e(TAG, "Pose detection failed", it)
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        }
    }

    private fun startRecording() {
        val videoCapture = this.videoCapture ?: run {
            Log.e(TAG, "videoCapture is null!")
            return
        }
        
        synchronized(recordingLock) {
            Log.d(TAG, "startRecording called, current state: $recordingState")
            
            // Prevent concurrent recording attempts
            if (recordingState != RecordingState.IDLE) {
                Log.w(TAG, "Cannot start recording - current state is $recordingState")
                return
            }

            if (recording != null) {
                Log.w(TAG, "Recording object exists but should be null in IDLE state")
                return
            }
            
            // Set state immediately to prevent race conditions
            recordingState = RecordingState.RECORDING
        }

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "MTB_$name")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.P) {
                put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/MTBAnalyzer")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        Log.d(TAG, "Preparing recording with output: $mediaStoreOutputOptions")
        
        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when(recordEvent) {
                    is VideoRecordEvent.Start -> {
                        recordingStartTime = System.currentTimeMillis()
                        runOnUiThread {
                            recordingOverlay.visibility = View.VISIBLE
                            statusIndicator.setBackgroundResource(R.drawable.circle_indicator)
                            statusIndicator.backgroundTintList = getColorStateList(android.R.color.holo_red_light)
                            statusText.text = "Recording"
                            recordingStatus.text = "Recording: 0.0s / 8.0s"
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val msg = "Video capture succeeded: ${recordEvent.outputResults.outputUri}"
                            Log.d(TAG, msg)
                            synchronized(recordingLock) {
                                recordingState = RecordingState.SAVING
                            }
                            runOnUiThread {
                                recordingOverlay.visibility = View.GONE
                                recordingStatus.text = "Video saved!"
                                statusIndicator.backgroundTintList = getColorStateList(android.R.color.holo_green_light)
                                statusText.text = "Video saved"
                            }
                            // Reset to idle after a delay
                            viewFinder.postDelayed({
                                synchronized(recordingLock) {
                                    if (recordingState == RecordingState.SAVING) {
                                        recordingState = RecordingState.IDLE
                                    }
                                }
                            }, 2000)
                        } else {
                            recording?.close()
                            recording = null
                            recordingStartTime = 0L
                            Log.e(TAG, "Video capture failed: ${recordEvent.error}")
                            synchronized(recordingLock) {
                                recordingState = RecordingState.ERROR
                            }
                            runOnUiThread {
                                recordingOverlay.visibility = View.GONE
                                recordingStatus.text = "Recording failed - Error ${recordEvent.error}"
                                statusIndicator.backgroundTintList = getColorStateList(android.R.color.holo_red_light)
                                statusText.text = "Error ${recordEvent.error}"
                            }
                            // Reset to idle after showing error
                            viewFinder.postDelayed({
                                synchronized(recordingLock) {
                                    if (recordingState == RecordingState.ERROR) {
                                        recordingState = RecordingState.IDLE
                                    }
                                }
                            }, 3000)
                        }
                    }
                }
            }
    }

    private fun stopRecording() {
        val currentRecording = recording
        if (currentRecording != null) {
            currentRecording.stop()
            recording = null
            recordingStartTime = 0L
            Log.d(TAG, "Recording stopped")
        } else {
            Log.w(TAG, "stopRecording called but no recording exists")
        }
    }
    
    private fun updateDetectionUI(riderDetected: Boolean, confidence: Double) {
        // Don't update UI if we're in error or saving state
        if (recordingState == RecordingState.ERROR || recordingState == RecordingState.SAVING) {
            return
        }
        
        if (riderDetected) {
            if (recordingState != RecordingState.RECORDING) {
                statusIndicator.backgroundTintList = getColorStateList(android.R.color.holo_green_light)
                statusText.text = String.format("Rider Detected (%.1f%%)", confidence * 100)
            }
            
            if (recording == null && recordingState == RecordingState.IDLE) {
                recordingStatus.text = "Ready to record"
            }
        } else {
            if (recordingState != RecordingState.RECORDING) {
                statusIndicator.backgroundTintList = getColorStateList(android.R.color.holo_orange_light)
                statusText.text = "Monitoring"
            }
            
            if (recording == null && recordingState == RecordingState.IDLE) {
                recordingStatus.text = "Waiting for rider..."
            }
        }
    }
    
    private fun updateRecordingUI(elapsedMs: Long) {
        val seconds = elapsedMs / 1000
        val tenths = (elapsedMs % 1000) / 100
        recordingStatus.text = String.format("Recording: %d.%ds / %.1fs", seconds, tenths, recordingDurationMs / 1000.0)
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

    override fun onDestroy() {
        super.onDestroy()
        recording?.stop()
        recording = null
        cameraExecutor.shutdown()
        poseDetector.close()
    }
}