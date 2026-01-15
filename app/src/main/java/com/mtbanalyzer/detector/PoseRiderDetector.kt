package com.mtbanalyzer.detector

import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetector
import com.mtbanalyzer.GraphicOverlay
import com.mtbanalyzer.PoseGraphic
import com.mtbanalyzer.tuning.BitmapProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Pose-based rider detection using ML Kit Pose Detection
 */
class PoseRiderDetector(
    private val poseDetector: PoseDetector
) : RiderDetector() {
    
    companion object {
        private const val TAG = "PoseRiderDetector"
        private const val DEFAULT_CONFIDENCE_THRESHOLD = 0.5
        private const val DEFAULT_MIN_POSES = 1
    }
    
    private var confidenceThreshold = DEFAULT_CONFIDENCE_THRESHOLD
    private var minPosesRequired = DEFAULT_MIN_POSES
    private var showOverlay = true
    
    @androidx.camera.core.ExperimentalGetImage
    override suspend fun processFrame(imageProxy: ImageProxy, graphicOverlay: GraphicOverlay): DetectionResult {
        if (!isDetectorEnabled()) {
            return DetectionResult(false, 0.0, "Detector disabled")
        }

        // Check if this is a video frame (BitmapProvider) or camera frame
        val image = if (imageProxy is BitmapProvider) {
            // Video frame - use bitmap directly
            InputImage.fromBitmap(imageProxy.getBitmap(), imageProxy.imageInfo.rotationDegrees)
        } else {
            // Camera frame - use media image
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                return DetectionResult(false, 0.0, "No image data")
            }
            InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        }
        
        return suspendCancellableCoroutine { continuation ->
            poseDetector.process(image)
                .addOnSuccessListener { pose ->
                    // Update overlay with pose graphics
                    graphicOverlay.clear()
                    if (pose.allPoseLandmarks.isNotEmpty() && showOverlay) {
                        val poseGraphic = PoseGraphic(graphicOverlay, pose)
                        graphicOverlay.add(poseGraphic)
                    }
                    
                    // Calculate detection result
                    val poseLandmarks = pose.allPoseLandmarks
                    val riderDetected = poseLandmarks.size >= minPosesRequired
                    
                    val avgConfidence = if (riderDetected) {
                        poseLandmarks.map { it.inFrameLikelihood }.average()
                    } else 0.0
                    
                    val riderDetectedWithThreshold = riderDetected && avgConfidence > confidenceThreshold
                    
                    val debugInfo = "Poses: ${poseLandmarks.size}, AvgConf: %.2f, Threshold: %.2f".format(avgConfidence, confidenceThreshold)
                    
                    Log.d(TAG, "Detection result: $debugInfo, detected: $riderDetectedWithThreshold")
                    
                    val result = DetectionResult(riderDetectedWithThreshold, avgConfidence, debugInfo)
                    continuation.resume(result)
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Pose detection failed", exception)
                    val result = DetectionResult(false, 0.0, "Detection failed: ${exception.message}")
                    continuation.resume(result)
                }
        }
    }
    
    override fun getDisplayName(): String = "ML Kit Pose Detection"
    
    override fun getDescription(): String = 
        "Uses Google ML Kit to detect human poses in the camera feed. Reliable but may struggle with fast motion or partial views."
    
    override fun configure(settings: Map<String, Any>) {
        confidenceThreshold = (settings["confidence_threshold"] as? Double) ?: DEFAULT_CONFIDENCE_THRESHOLD
        minPosesRequired = (settings["min_poses"] as? Int) ?: DEFAULT_MIN_POSES
        showOverlay = (settings["show_overlay"] as? Boolean) ?: true
    }
    
    override fun getConfigOptions(): Map<String, ConfigOption> = mapOf(
        "confidence_threshold" to ConfigOption(
            key = "confidence_threshold",
            displayName = "Confidence Threshold",
            description = "Minimum confidence level (0.0-1.0) for pose detection",
            type = ConfigType.FLOAT,
            defaultValue = DEFAULT_CONFIDENCE_THRESHOLD,
            minValue = 0.0,
            maxValue = 1.0
        ),
        "min_poses" to ConfigOption(
            key = "min_poses",
            displayName = "Minimum Poses",
            description = "Minimum number of pose landmarks required for detection",
            type = ConfigType.INTEGER,
            defaultValue = DEFAULT_MIN_POSES,
            minValue = 1,
            maxValue = 33
        ),
        "show_overlay" to ConfigOption(
            key = "show_overlay",
            displayName = "Show Pose Overlay",
            description = "Display detected pose landmarks on camera preview",
            type = ConfigType.BOOLEAN,
            defaultValue = true
        )
    )
    
    override fun release() {
        super.release()
        poseDetector.close()
    }
}