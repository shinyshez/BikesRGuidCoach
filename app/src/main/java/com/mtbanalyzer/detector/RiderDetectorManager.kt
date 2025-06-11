package com.mtbanalyzer.detector

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.mtbanalyzer.GraphicOverlay
import com.mtbanalyzer.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Manager for different rider detection implementations
 */
class RiderDetectorManager(
    private val context: Context,
    private val settingsManager: SettingsManager
) {
    
    companion object {
        private const val TAG = "RiderDetectorManager"
        
        // Detector type constants
        const val DETECTOR_POSE = "pose"
        const val DETECTOR_MOTION = "motion"
        const val DETECTOR_HYBRID = "hybrid"
        const val DETECTOR_OPTICAL_FLOW = "optical_flow"
    }
    
    enum class DetectorType(val id: String, val displayName: String) {
        POSE(DETECTOR_POSE, "ML Kit Pose Detection"),
        MOTION(DETECTOR_MOTION, "Motion Detection"),
        HYBRID(DETECTOR_HYBRID, "Hybrid Detection"),
        OPTICAL_FLOW(DETECTOR_OPTICAL_FLOW, "Optical Flow Detection")
    }
    
    private var currentDetector: RiderDetector? = null
    private var detectorCallback: RiderDetector.DetectionCallback? = null
    
    // State tracking for detection logic
    private var isRiderDetected = false
    private var riderLastSeenTime = 0L
    private var lastRecordingAttemptTime = 0L
    private var recordingStartTime = 0L
    private var isCurrentlyRecording = false
    private val recordingCooldownMs = 1000L
    
    /**
     * Initialize the detector manager with the selected detector type
     */
    fun initialize() {
        val detectorType = settingsManager.getDetectorType()
        switchDetector(detectorType)
    }
    
    /**
     * Switch to a different detector type
     */
    fun switchDetector(detectorType: String) {
        Log.d(TAG, "Switching to detector: $detectorType")
        
        // Release current detector
        currentDetector?.release()
        
        // Create new detector
        currentDetector = createDetector(detectorType)
        
        // Apply callback and settings
        currentDetector?.setDetectionCallback(createInternalCallback())
        applyDetectorSettings()
        
        Log.d(TAG, "Switched to detector: ${currentDetector?.getDisplayName()}")
    }
    
    /**
     * Set the callback for detection events
     */
    fun setCallback(callback: RiderDetector.DetectionCallback) {
        detectorCallback = callback
    }
    
    private var processingTimeCallback: ((Long) -> Unit)? = null
    
    fun setProcessingTimeCallback(callback: (Long) -> Unit) {
        processingTimeCallback = callback
    }
    
    /**
     * Process a camera frame
     */
    fun processFrame(imageProxy: ImageProxy, graphicOverlay: GraphicOverlay) {
        val detector = currentDetector
        if (detector == null) {
            Log.w(TAG, "No detector available for frame processing")
            imageProxy.close()
            return
        }
        
        val startTime = System.currentTimeMillis()
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = detector.processFrame(imageProxy, graphicOverlay)
                
                // Measure actual processing time and report it
                val processingTime = System.currentTimeMillis() - startTime
                processingTimeCallback?.invoke(processingTime)
                
                handleDetectionResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing frame", e)
            } finally {
                // Always close the imageProxy after processing
                try {
                    imageProxy.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing imageProxy", e)
                }
            }
        }
    }
    
    /**
     * Get available detector types
     */
    fun getAvailableDetectors(): List<DetectorType> = DetectorType.values().toList()
    
    /**
     * Get current detector info
     */
    fun getCurrentDetectorInfo(): Pair<String, String>? {
        return currentDetector?.let { 
            it.getDisplayName() to it.getDescription()
        }
    }
    
    /**
     * Get configuration options for current detector
     */
    fun getCurrentDetectorConfigOptions(): Map<String, RiderDetector.ConfigOption> {
        return currentDetector?.getConfigOptions() ?: emptyMap()
    }
    
    /**
     * Set recording state
     */
    fun setRecordingStarted() {
        recordingStartTime = System.currentTimeMillis()
        isCurrentlyRecording = true
        Log.d(TAG, "Recording started at $recordingStartTime")
    }
    
    fun setRecordingStopped() {
        isCurrentlyRecording = false
        recordingStartTime = 0L
        lastRecordingAttemptTime = 0L
        Log.d(TAG, "Recording stopped")
    }
    
    /**
     * Reset detector state
     */
    fun reset() {
        currentDetector?.reset()
        isRiderDetected = false
        riderLastSeenTime = 0L
        lastRecordingAttemptTime = 0L
    }
    
    /**
     * Release resources
     */
    fun release() {
        currentDetector?.release()
        currentDetector = null
        detectorCallback = null
    }
    
    private fun createDetector(detectorType: String): RiderDetector {
        return when (detectorType) {
            DETECTOR_POSE -> {
                val options = PoseDetectorOptions.Builder()
                    .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                    .build()
                val poseDetector = PoseDetection.getClient(options)
                PoseRiderDetector(poseDetector)
            }
            
            DETECTOR_MOTION -> {
                MotionRiderDetector()
            }
            
            DETECTOR_HYBRID -> {
                val options = PoseDetectorOptions.Builder()
                    .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                    .build()
                val poseDetector = PoseDetection.getClient(options)
                val poseRiderDetector = PoseRiderDetector(poseDetector)
                val motionRiderDetector = MotionRiderDetector()
                HybridRiderDetector(poseRiderDetector, motionRiderDetector)
            }
            
            DETECTOR_OPTICAL_FLOW -> {
                OpticalFlowRiderDetectorFast()
            }
            
            else -> {
                Log.w(TAG, "Unknown detector type: $detectorType, falling back to pose detection")
                val options = PoseDetectorOptions.Builder()
                    .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                    .build()
                val poseDetector = PoseDetection.getClient(options)
                PoseRiderDetector(poseDetector)
            }
        }
    }
    
    private fun applyDetectorSettings() {
        val detector = currentDetector ?: return
        
        // Get detector-specific settings from SettingsManager
        val settings = mutableMapOf<String, Any>()
        
        // Apply common settings
        settings["confidence_threshold"] = settingsManager.getDetectionSensitivityThreshold()
        settings["show_overlay"] = settingsManager.shouldShowPoseOverlay()
        
        // Apply motion detector specific settings
        if (detector is MotionRiderDetector || 
            (detector is HybridRiderDetector && detector.getDisplayName().contains("Motion"))) {
            settings.putAll(settingsManager.getMotionDetectorSettings())
        }
        
        detector.configure(settings)
    }
    
    private fun createInternalCallback(): RiderDetector.DetectionCallback {
        return object : RiderDetector.DetectionCallback {
            override fun onRiderDetected(confidence: Double, debugInfo: String) {
                detectorCallback?.onRiderDetected(confidence, debugInfo)
            }
            
            override fun onRiderLost() {
                detectorCallback?.onRiderLost()
            }
            
            override fun onUpdateUI(riderDetected: Boolean, confidence: Double, debugInfo: String) {
                detectorCallback?.onUpdateUI(riderDetected, confidence, debugInfo)
            }
        }
    }
    
    private fun handleDetectionResult(result: RiderDetector.DetectionResult) {
        val currentTime = System.currentTimeMillis()
        
        // Update UI
        detectorCallback?.onUpdateUI(result.riderDetected, result.confidence, result.debugInfo)
        
        // Handle rider detection state changes
        if (result.riderDetected) {
            riderLastSeenTime = currentTime
            
            if (!isRiderDetected) {
                // Rider just entered frame
                isRiderDetected = true
                Log.d(TAG, "Rider entered frame - ${result.debugInfo}")
                detectorCallback?.onRiderDetected(result.confidence, result.debugInfo)
                
                // Check if we should start recording
                val timeSinceLastAttempt = currentTime - lastRecordingAttemptTime
                if (timeSinceLastAttempt > recordingCooldownMs && !isCurrentlyRecording) {
                    Log.d(TAG, "Starting new recording...")
                    lastRecordingAttemptTime = currentTime
                    // Note: Recording start will be handled by the callback receiver
                } else {
                    Log.d(TAG, "Rider re-detected - skipping due to cooldown (${timeSinceLastAttempt}ms) or already recording")
                }
            }
        } else if (!result.riderDetected && isRiderDetected) {
            // Rider left frame
            isRiderDetected = false
            val postRiderDelayMs = settingsManager.getPostRiderDelayMs()
            Log.d(TAG, "Rider left frame - continuing recording for ${postRiderDelayMs}ms")
            detectorCallback?.onRiderLost()
        }
        
        // Handle recording logic
        handleRecordingLogic(currentTime)
    }
    
    private fun handleRecordingLogic(currentTime: Long) {
        // Only process recording logic if we're actually recording
        if (isCurrentlyRecording && recordingStartTime > 0) {
            val elapsedSinceStart = currentTime - recordingStartTime
            val elapsedSinceRiderLeft = currentTime - riderLastSeenTime
            
            // Check if recording should stop
            val recordingDurationMs = settingsManager.getRecordingDurationMs()
            val postRiderDelayMs = settingsManager.getPostRiderDelayMs()
            
            if (elapsedSinceStart > recordingDurationMs || 
                (!isRiderDetected && elapsedSinceRiderLeft > postRiderDelayMs)) {
                // Note: Recording stop will be handled by the callback receiver
            }
        }
    }
    
    /**
     * Re-apply current settings to the active detector
     */
    fun applyCurrentSettings() {
        applyDetectorSettings()
    }
}