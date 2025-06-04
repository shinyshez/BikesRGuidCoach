package com.mtbanalyzer

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetector

class PoseDetectionProcessor(
    private val poseDetector: PoseDetector,
    private val graphicOverlay: GraphicOverlay,
    private val recordingDurationMs: Long = 8000L,
    private val postRiderDelayMs: Long = 2000L,
    private val recordingCooldownMs: Long = 1000L
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "PoseDetectionProcessor"
    }

    interface PoseDetectionCallback {
        fun onRiderDetected(confidence: Double)
        fun onRiderLost()
        fun onUpdateUI(riderDetected: Boolean, confidence: Double)
        fun onStartRecording()
        fun onStopRecording()
        fun onUpdateRecordingProgress(elapsedMs: Long)
    }

    private var isRiderDetected = false
    private var riderLastSeenTime = 0L
    private var lastRecordingAttemptTime = 0L
    private var callback: PoseDetectionCallback? = null

    fun setCallback(callback: PoseDetectionCallback) {
        this.callback = callback
    }

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            
            updateOverlayImageInfo(imageProxy)
            
            poseDetector.process(image)
                .addOnSuccessListener { pose ->
                    processPoseDetection(pose)
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Pose detection failed", exception)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun updateOverlayImageInfo(imageProxy: ImageProxy) {
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        
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

    private fun processPoseDetection(pose: com.google.mlkit.vision.pose.Pose) {
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
        callback?.onUpdateUI(riderCurrentlyDetected, avgConfidence)

        handleRiderDetection(riderCurrentlyDetected, currentTime)
        handleRecordingLogic(currentTime)
    }

    private fun handleRiderDetection(riderCurrentlyDetected: Boolean, currentTime: Long) {
        if (riderCurrentlyDetected) {
            riderLastSeenTime = currentTime
            
            if (!isRiderDetected) {
                // Rider just entered frame
                isRiderDetected = true
                Log.d(TAG, "Rider entered frame")
                callback?.onRiderDetected(0.0) // Pass actual confidence if needed
                
                // Check if we should start recording
                val timeSinceLastAttempt = currentTime - lastRecordingAttemptTime
                if (timeSinceLastAttempt > recordingCooldownMs) {
                    Log.d(TAG, "Starting new recording...")
                    lastRecordingAttemptTime = currentTime
                    callback?.onStartRecording()
                } else {
                    Log.d(TAG, "Rider re-detected - skipping due to cooldown (${timeSinceLastAttempt}ms)")
                }
            }
        } else if (!riderCurrentlyDetected && isRiderDetected) {
            // Rider left frame
            isRiderDetected = false
            Log.d(TAG, "Rider left frame - continuing recording for ${postRiderDelayMs}ms")
            callback?.onRiderLost()
        }
    }

    private fun handleRecordingLogic(currentTime: Long) {
        // This method checks if recording should stop based on duration or rider absence
        // The actual recording state is managed by RecordingManager
        callback?.let { cb ->
            // Only process recording logic if a recording was started
            if (lastRecordingAttemptTime > 0) {
                val elapsedSinceStart = currentTime - lastRecordingAttemptTime
                val elapsedSinceRiderLeft = currentTime - riderLastSeenTime
                
                // Notify about recording progress
                cb.onUpdateRecordingProgress(elapsedSinceStart)
                
                // Check if recording should stop
                if (elapsedSinceStart > recordingDurationMs || 
                    (!isRiderDetected && elapsedSinceRiderLeft > postRiderDelayMs)) {
                    cb.onStopRecording()
                    // Reset the recording attempt time after stopping
                    lastRecordingAttemptTime = 0L
                }
            }
        }
    }

    fun reset() {
        isRiderDetected = false
        riderLastSeenTime = 0L
        lastRecordingAttemptTime = 0L
        graphicOverlay.clear()
    }

    fun release() {
        callback = null
        graphicOverlay.clear()
        Log.d(TAG, "Pose detection processor released")
    }
}