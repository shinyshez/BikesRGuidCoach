package com.mtbanalyzer

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.mtbanalyzer.detector.RiderDetector
import com.mtbanalyzer.detector.RiderDetectorManager

/**
 * New camera image processor that uses the pluggable rider detection system
 */
class RiderDetectionProcessor(
    private val detectorManager: RiderDetectorManager,
    private val graphicOverlay: GraphicOverlay,
    private val settingsManager: SettingsManager,
    private val recordingCooldownMs: Long = 1000L
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "RiderDetectionProcessor"
    }

    interface RiderDetectionCallback {
        fun onRiderDetected(confidence: Double, debugInfo: String = "")
        fun onRiderLost()
        fun onUpdateUI(riderDetected: Boolean, confidence: Double, debugInfo: String = "")
        fun onStartRecording()
        fun onStopRecording()
        fun onUpdateRecordingProgress(elapsedMs: Long)
    }

    private var isRiderDetected = false
    private var riderLastSeenTime = 0L
    private var lastRecordingAttemptTime = 0L
    private var recordingStartTime = 0L
    private var isCurrentlyRecording = false
    private var callback: RiderDetectionCallback? = null
    private var isDetectionEnabled = true

    init {
        // Set up the detector manager callback
        detectorManager.setCallback(object : RiderDetector.DetectionCallback {
            override fun onRiderDetected(confidence: Double, debugInfo: String) {
                handleRiderDetected(confidence, debugInfo)
            }

            override fun onRiderLost() {
                handleRiderLost()
            }

            override fun onUpdateUI(riderDetected: Boolean, confidence: Double, debugInfo: String) {
                callback?.onUpdateUI(riderDetected, confidence, debugInfo)
            }
        })
    }

    fun setCallback(callback: RiderDetectionCallback) {
        this.callback = callback
    }
    
    fun setRecordingStarted() {
        recordingStartTime = System.currentTimeMillis()
        isCurrentlyRecording = true
        detectorManager.setRecordingStarted()
        Log.d(TAG, "Recording started at $recordingStartTime")
    }
    
    fun setRecordingStopped() {
        isCurrentlyRecording = false
        recordingStartTime = 0L
        lastRecordingAttemptTime = 0L
        detectorManager.setRecordingStopped()
        Log.d(TAG, "Recording stopped")
    }

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            try {
                // Note: Image source info is set by CameraManager with actual preview dimensions
                
                if (isDetectionEnabled) {
                    // Process frame with current detector - manager will handle closing the imageProxy
                    detectorManager.processFrame(imageProxy, graphicOverlay)
                    
                    // Handle recording logic
                    handleRecordingLogic()
                } else {
                    // Detection disabled - just close the image and clear overlays
                    graphicOverlay.clear()
                    imageProxy.close()
                    
                    // Update UI to show detection is disabled
                    callback?.onUpdateUI(false, 0.0, "Detection disabled")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in analyze", e)
                // If there's an error before calling processFrame, we need to close it ourselves
                try {
                    imageProxy.close()
                } catch (closeException: Exception) {
                    Log.w(TAG, "Error closing imageProxy after error", closeException)
                }
            }
        } else {
            imageProxy.close()
        }
    }


    private fun handleRiderDetected(confidence: Double, debugInfo: String) {
        val currentTime = System.currentTimeMillis()
        riderLastSeenTime = currentTime
        
        if (!isRiderDetected) {
            // Rider just entered frame
            isRiderDetected = true
            Log.d(TAG, "Rider entered frame: $debugInfo")
            callback?.onRiderDetected(confidence, debugInfo)
            
            // Check if we should start recording
            val timeSinceLastAttempt = currentTime - lastRecordingAttemptTime
            if (timeSinceLastAttempt > recordingCooldownMs && !isCurrentlyRecording) {
                Log.d(TAG, "Starting new recording...")
                lastRecordingAttemptTime = currentTime
                callback?.onStartRecording()
            } else {
                Log.d(TAG, "Rider re-detected - skipping due to cooldown (${timeSinceLastAttempt}ms) or already recording")
            }
        }
    }

    private fun handleRiderLost() {
        if (isRiderDetected) {
            // Rider left frame
            isRiderDetected = false
            val postRiderDelayMs = settingsManager.getPostRiderDelayMs()
            Log.d(TAG, "Rider left frame - continuing recording for ${postRiderDelayMs}ms")
            callback?.onRiderLost()
        }
    }

    private fun handleRecordingLogic() {
        val currentTime = System.currentTimeMillis()
        
        // Only process recording logic if we're actually recording
        if (isCurrentlyRecording && recordingStartTime > 0) {
            val elapsedSinceStart = currentTime - recordingStartTime
            val elapsedSinceRiderLeft = currentTime - riderLastSeenTime
            
            // Notify about recording progress
            callback?.onUpdateRecordingProgress(elapsedSinceStart)
            
            // Check if recording should stop
            val recordingDurationMs = settingsManager.getRecordingDurationMs()
            val postRiderDelayMs = settingsManager.getPostRiderDelayMs()
            
            if (elapsedSinceStart > recordingDurationMs || 
                (!isRiderDetected && elapsedSinceRiderLeft > postRiderDelayMs)) {
                callback?.onStopRecording()
                // Recording will be marked as stopped when callback is received
            }
        }
    }

    fun reset() {
        isRiderDetected = false
        riderLastSeenTime = 0L
        lastRecordingAttemptTime = 0L
        detectorManager.reset()
        graphicOverlay.clear()
    }

    fun release() {
        callback = null
        detectorManager.release()
        graphicOverlay.clear()
        Log.d(TAG, "Rider detection processor released")
    }
    
    /**
     * Switch to a different detector type
     */
    fun switchDetector(detectorType: String) {
        Log.d(TAG, "Switching detector to: $detectorType")
        detectorManager.switchDetector(detectorType)
    }
    
    /**
     * Get available detector types
     */
    fun getAvailableDetectors() = detectorManager.getAvailableDetectors()
    
    /**
     * Get current detector info
     */
    fun getCurrentDetectorInfo() = detectorManager.getCurrentDetectorInfo()
    
    /**
     * Enable or disable rider detection
     */
    fun setDetectionEnabled(enabled: Boolean) {
        isDetectionEnabled = enabled
        Log.d(TAG, "Detection enabled: $enabled")
        
        if (!enabled) {
            // Clear any existing detections
            isRiderDetected = false
            riderLastSeenTime = 0L
            graphicOverlay.clear()
        }
    }
    
    /**
     * Check if detection is currently enabled
     */
    fun isDetectionEnabled(): Boolean = isDetectionEnabled
}