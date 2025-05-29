package com.mtbanalyzer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
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
    private lateinit var cameraExecutor: ExecutorService
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var poseDetector: PoseDetector

    private var isRiderDetected = false
    private var recordingStartTime = 0L
    private val recordingDurationMs = 8000L // 8 seconds
    private var riderLastSeenTime = 0L
    private val postRiderDelayMs = 2000L // 2 seconds after rider leaves

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

            // Initialize pose detector for rider detection
            val options = PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
            poseDetector = PoseDetection.getClient(options)

            Log.d(TAG, "App started successfully")

            if (allPermissionsGranted()) {
                Log.d(TAG, "All permissions granted, starting camera")
                startCamera()
            } else {
                Log.d(TAG, "Requesting permissions")
                ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            }

            cameraExecutor = Executors.newSingleThreadExecutor()

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

            // Video capture
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
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
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, videoCapture, imageAnalyzer
                )
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

                poseDetector.process(image)
                    .addOnSuccessListener { pose ->
                        val currentTime = System.currentTimeMillis()

                        // Check if a person (rider) is detected
                        val riderCurrentlyDetected = pose.allPoseLandmarks.isNotEmpty()

                        if (riderCurrentlyDetected) {
                            riderLastSeenTime = currentTime
                            
                            if (!isRiderDetected) {
                                // Rider just entered frame - start recording
                                isRiderDetected = true
                                startRecording()
                                Log.d(TAG, "Rider detected - starting recording")
                            }
                        } else if (!riderCurrentlyDetected && isRiderDetected) {
                            // Rider left frame
                            isRiderDetected = false
                            Log.d(TAG, "Rider left frame - continuing recording for 2 seconds")
                        }

                        // Stop recording either after max duration OR 2 seconds after rider left
                        if (recording != null) {
                            val elapsedSinceStart = currentTime - recordingStartTime
                            val elapsedSinceRiderLeft = currentTime - riderLastSeenTime
                            
                            if (elapsedSinceStart > recordingDurationMs || 
                                (!isRiderDetected && elapsedSinceRiderLeft > postRiderDelayMs)) {
                                stopRecording()
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
        val videoCapture = this.videoCapture ?: return

        if (recording != null) {
            recording?.stop()
            recording = null
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

        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .apply {
                if (ContextCompat.checkSelfPermission(this@MainActivity,
                        Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when(recordEvent) {
                    is VideoRecordEvent.Start -> {
                        recordingStartTime = System.currentTimeMillis()
                        runOnUiThread {
                            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val msg = "Video capture succeeded: ${recordEvent.outputResults.outputUri}"
                            Log.d(TAG, msg)
                            runOnUiThread {
                                Toast.makeText(this, "Video saved!", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture failed: ${recordEvent.error}")
                        }
                    }
                }
            }
    }

    private fun stopRecording() {
        recording?.stop()
        recording = null
        Log.d(TAG, "Recording stopped")
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
        cameraExecutor.shutdown()
        poseDetector.close()
    }
}