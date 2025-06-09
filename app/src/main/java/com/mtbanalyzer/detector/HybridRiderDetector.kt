package com.mtbanalyzer.detector

import android.util.Log
import androidx.camera.core.ImageProxy
import com.mtbanalyzer.GraphicOverlay
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Hybrid rider detection that combines multiple detection methods
 * Uses both pose detection and motion detection with weighted scoring
 */
class HybridRiderDetector(
    private val poseDetector: PoseRiderDetector,
    private val motionDetector: MotionRiderDetector
) : RiderDetector() {
    
    companion object {
        private const val TAG = "HybridRiderDetector"
        private const val DEFAULT_POSE_WEIGHT = 0.7
        private const val DEFAULT_MOTION_WEIGHT = 0.3
        private const val DEFAULT_COMBINED_THRESHOLD = 0.4
    }
    
    private var poseWeight = DEFAULT_POSE_WEIGHT
    private var motionWeight = DEFAULT_MOTION_WEIGHT
    private var combinedThreshold = DEFAULT_COMBINED_THRESHOLD
    private var requireBothDetectors = false
    
    @androidx.camera.core.ExperimentalGetImage
    override suspend fun processFrame(imageProxy: ImageProxy, graphicOverlay: GraphicOverlay): DetectionResult {
        if (!isDetectorEnabled()) {
            return DetectionResult(false, 0.0, "Detector disabled")
        }
        
        return try {
            // Run motion detector first (it creates its own bitmap copy)
            val motionDetection = motionDetector.processFrame(imageProxy, graphicOverlay)
            
            // Then run pose detector (which uses ML Kit InputImage)
            val poseDetection = poseDetector.processFrame(imageProxy, graphicOverlay)
            
            combineResults(poseDetection, motionDetection)
        } catch (e: Exception) {
            Log.e(TAG, "Error in hybrid detection", e)
            DetectionResult(false, 0.0, "Hybrid detection error: ${e.message}")
        }
    }
    
    private fun combineResults(poseResult: DetectionResult, motionResult: DetectionResult): DetectionResult {
        // Calculate weighted confidence score
        val combinedConfidence = (poseResult.confidence * poseWeight) + (motionResult.confidence * motionWeight)
        
        // Determine if rider is detected based on strategy
        val riderDetected = if (requireBothDetectors) {
            // Both detectors must agree
            poseResult.riderDetected && motionResult.riderDetected
        } else {
            // Use combined confidence threshold
            combinedConfidence > combinedThreshold
        }
        
        val debugInfo = buildString {
            append("Hybrid: ")
            append("Pose(${if (poseResult.riderDetected) "✓" else "✗"}, %.2f) ".format(poseResult.confidence))
            append("Motion(${if (motionResult.riderDetected) "✓" else "✗"}, %.2f) ".format(motionResult.confidence))
            append("Combined(%.2f, threshold=%.2f)".format(combinedConfidence, combinedThreshold))
            if (requireBothDetectors) append(" [Both required]")
        }
        
        Log.d(TAG, debugInfo)
        
        return DetectionResult(riderDetected, combinedConfidence, debugInfo)
    }
    
    override fun getDisplayName(): String = "Hybrid Detection"
    
    override fun getDescription(): String = 
        "Combines pose detection and motion detection for improved accuracy. Can be configured to require both methods or use weighted scoring."
    
    override fun configure(settings: Map<String, Any>) {
        poseWeight = (settings["pose_weight"] as? Double) ?: DEFAULT_POSE_WEIGHT
        motionWeight = (settings["motion_weight"] as? Double) ?: DEFAULT_MOTION_WEIGHT
        combinedThreshold = (settings["combined_threshold"] as? Double) ?: DEFAULT_COMBINED_THRESHOLD
        requireBothDetectors = (settings["require_both"] as? Boolean) ?: false
        
        // Forward settings to child detectors
        val poseSettings = settings.filterKeys { it.startsWith("pose_") }
            .mapKeys { it.key.removePrefix("pose_") }
        val motionSettings = settings.filterKeys { it.startsWith("motion_") }
            .mapKeys { it.key.removePrefix("motion_") }
        
        if (poseSettings.isNotEmpty()) {
            poseDetector.configure(poseSettings)
        }
        if (motionSettings.isNotEmpty()) {
            motionDetector.configure(motionSettings)
        }
    }
    
    override fun getConfigOptions(): Map<String, ConfigOption> {
        val hybridOptions = mapOf(
            "pose_weight" to ConfigOption(
                key = "pose_weight",
                displayName = "Pose Detection Weight",
                description = "Weight given to pose detection results (0.0-1.0)",
                type = ConfigType.FLOAT,
                defaultValue = DEFAULT_POSE_WEIGHT,
                minValue = 0.0,
                maxValue = 1.0
            ),
            "motion_weight" to ConfigOption(
                key = "motion_weight",
                displayName = "Motion Detection Weight",
                description = "Weight given to motion detection results (0.0-1.0)",
                type = ConfigType.FLOAT,
                defaultValue = DEFAULT_MOTION_WEIGHT,
                minValue = 0.0,
                maxValue = 1.0
            ),
            "combined_threshold" to ConfigOption(
                key = "combined_threshold",
                displayName = "Combined Threshold",
                description = "Threshold for combined confidence score (0.0-1.0)",
                type = ConfigType.FLOAT,
                defaultValue = DEFAULT_COMBINED_THRESHOLD,
                minValue = 0.0,
                maxValue = 1.0
            ),
            "require_both" to ConfigOption(
                key = "require_both",
                displayName = "Require Both Detectors",
                description = "Both pose and motion detection must agree for positive detection",
                type = ConfigType.BOOLEAN,
                defaultValue = false
            )
        )
        
        // Add prefixed options from child detectors
        val poseOptions = poseDetector.getConfigOptions().mapKeys { "pose_${it.key}" }
        val motionOptions = motionDetector.getConfigOptions().mapKeys { "motion_${it.key}" }
        
        return hybridOptions + poseOptions + motionOptions
    }
    
    override fun setDetectorEnabled(enabled: Boolean) {
        super.setDetectorEnabled(enabled)
        poseDetector.setDetectorEnabled(enabled)
        motionDetector.setDetectorEnabled(enabled)
    }
    
    override fun reset() {
        super.reset()
        poseDetector.reset()
        motionDetector.reset()
    }
    
    override fun release() {
        super.release()
        poseDetector.release()
        motionDetector.release()
    }
}