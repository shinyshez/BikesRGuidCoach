package com.mtbanalyzer.detector

import android.graphics.*
import android.util.Log
import androidx.camera.core.ImageProxy
import com.mtbanalyzer.GraphicOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

/**
 * Highly optimized optical flow rider detection using streamlined Lucas-Kanade method
 * Significant performance improvements over original implementation
 */
class OpticalFlowRiderDetectorFast : RiderDetector() {
    
    companion object {
        private const val TAG = "OpticalFlowDetectorFast"
        private const val DEFAULT_FEATURE_COUNT = 20  // Fewer features for speed
        private const val DEFAULT_MIN_FEATURE_DISTANCE = 20  // Wider spacing
        private const val DEFAULT_FLOW_THRESHOLD = 3.0
        private const val DEFAULT_MIN_COHERENT_VECTORS = 4
        private const val DEFAULT_MAX_VECTOR_MAGNITUDE = 40.0
    }
    
    private var previousFrame: IntArray? = null
    private var previousFeatures: List<FeaturePoint> = emptyList()
    private var frameWidth = 0
    private var frameHeight = 0
    
    // Configuration parameters
    private var maxFeatureCount = DEFAULT_FEATURE_COUNT
    private var minFeatureDistance = DEFAULT_MIN_FEATURE_DISTANCE
    private var flowThreshold = DEFAULT_FLOW_THRESHOLD
    private var minCoherentVectors = DEFAULT_MIN_COHERENT_VECTORS
    private var maxVectorMagnitude = DEFAULT_MAX_VECTOR_MAGNITUDE
    private var showFlowOverlay = true
    
    data class FeaturePoint(
        val x: Int,
        val y: Int,
        val strength: Float = 0f
    )
    
    data class FlowVector(
        val startPoint: FeaturePoint,
        val endPoint: FeaturePoint,
        val magnitude: Float,
        val angle: Float
    )
    
    @androidx.camera.core.ExperimentalGetImage
    override suspend fun processFrame(imageProxy: ImageProxy, graphicOverlay: GraphicOverlay): DetectionResult {
        if (!isDetectorEnabled()) {
            return DetectionResult(false, 0.0, "Detector disabled")
        }
        
        val startTime = System.currentTimeMillis()
        
        return withContext(Dispatchers.Default) {
            try {
                // Get rotation info
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                val isRotated = rotationDegrees == 90 || rotationDegrees == 270
                
                // Get dimensions - swap if rotated to match what GraphicOverlay expects
                val originalWidth = if (isRotated) imageProxy.height else imageProxy.width
                val originalHeight = if (isRotated) imageProxy.width else imageProxy.height
                
                val convertStartTime = System.currentTimeMillis()
                val currentFrame = imageProxyToIntArray(imageProxy)
                val convertTime = System.currentTimeMillis() - convertStartTime
                
                Log.d(TAG, "Fast conversion: ${convertTime}ms, size: ${frameWidth}x${frameHeight}")
                
                val result = if (previousFrame != null && previousFeatures.isNotEmpty()) {
                    calculateOpticalFlowFast(previousFrame!!, currentFrame, graphicOverlay, originalWidth, originalHeight)
                } else {
                    // First frame - detect features for next frame
                    val features = detectFeaturesFast(currentFrame)
                    previousFeatures = features
                    DetectionResult(false, 0.0, "First frame - detected ${features.size} features")
                }
                
                previousFrame = currentFrame
                
                val totalTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "Fast total processing time: ${totalTime}ms")
                
                result
            } catch (e: Exception) {
                Log.e(TAG, "Error processing fast optical flow", e)
                DetectionResult(false, 0.0, "Processing error: ${e.message}")
            }
        }
    }
    
    private fun calculateOpticalFlowFast(
        prevFrame: IntArray,
        currentFrame: IntArray,
        graphicOverlay: GraphicOverlay,
        originalWidth: Int,
        originalHeight: Int
    ): DetectionResult {
        val flowStartTime = System.currentTimeMillis()
        val flowVectors = mutableListOf<FlowVector>()
        val trackedFeatures = mutableListOf<FeaturePoint>()
        
        // Track each feature from previous frame with simplified template matching
        for (feature in previousFeatures) {
            val newPosition = trackFeatureFast(prevFrame, currentFrame, feature)
            if (newPosition != null) {
                val magnitude = sqrt((newPosition.x - feature.x).toFloat().pow(2) + (newPosition.y - feature.y).toFloat().pow(2))
                
                // Filter valid movements
                if (magnitude > flowThreshold && magnitude < maxVectorMagnitude) {
                    val angle = atan2((newPosition.y - feature.y).toFloat(), (newPosition.x - feature.x).toFloat())
                    flowVectors.add(FlowVector(feature, newPosition, magnitude, angle))
                    trackedFeatures.add(newPosition)
                }
            }
        }
        
        // Analyze flow vectors for rider-like motion
        val riderDetected = analyzeFlowForRider(flowVectors)
        val confidence = calculateConfidence(flowVectors)
        
        // Update features for next frame
        if (trackedFeatures.size < maxFeatureCount / 2) {
            // If we lost too many features, detect new ones
            val newFeatures = detectFeaturesFast(currentFrame)
            previousFeatures = (trackedFeatures + newFeatures).take(maxFeatureCount)
        } else {
            previousFeatures = trackedFeatures
        }
        
        // Draw flow vectors on overlay
        if (showFlowOverlay) {
            graphicOverlay.clear()
            graphicOverlay.add(OpticalFlowGraphic(graphicOverlay, flowVectors, previousFeatures, originalWidth, originalHeight, frameWidth, frameHeight))
        }
        
        val flowTime = System.currentTimeMillis() - flowStartTime
        
        val debugInfo = "Vectors: ${flowVectors.size}, Features: ${previousFeatures.size}, AvgMag: %.1f, FlowTime: ${flowTime}ms".format(
            flowVectors.map { it.magnitude }.average().takeIf { !it.isNaN() } ?: 0.0
        )
        
        Log.d(TAG, "Fast flow calculation: ${flowTime}ms for ${previousFeatures.size} features")
        
        return DetectionResult(riderDetected, confidence, debugInfo)
    }
    
    /**
     * Simplified and fast Lucas-Kanade tracking
     */
    private fun trackFeatureFast(
        prevFrame: IntArray,
        currentFrame: IntArray,
        feature: FeaturePoint
    ): FeaturePoint? {
        val windowSize = 7  // Smaller window for speed
        val halfWindow = windowSize / 2
        
        val fx = feature.x
        val fy = feature.y
        
        // Check bounds
        if (fx < halfWindow || fy < halfWindow || 
            fx >= frameWidth - halfWindow || fy >= frameHeight - halfWindow) {
            return null
        }
        
        try {
            // Extract template from previous frame (flattened)
            val template = IntArray(windowSize * windowSize)
            var idx = 0
            for (dy in -halfWindow..halfWindow) {
                for (dx in -halfWindow..halfWindow) {
                    template[idx++] = prevFrame[(fy + dy) * frameWidth + (fx + dx)]
                }
            }
            
            // Search for best match in current frame with larger steps for speed
            var bestX = fx
            var bestY = fy
            var bestScore = Int.MAX_VALUE
            
            val searchRadius = 8  // Smaller search radius
            for (dy in -searchRadius..searchRadius step 2) {  // Larger step size
                for (dx in -searchRadius..searchRadius step 2) {
                    val newX = fx + dx
                    val newY = fy + dy
                    
                    if (newX >= halfWindow && newY >= halfWindow && 
                        newX < frameWidth - halfWindow && newY < frameHeight - halfWindow) {
                        
                        val score = calculateSSDFast(currentFrame, newX, newY, template, windowSize)
                        
                        if (score < bestScore) {
                            bestScore = score
                            bestX = newX
                            bestY = newY
                        }
                    }
                }
            }
            
            // Only return if we found a reasonable match
            return if (bestScore < 50000) { // Adjusted threshold
                FeaturePoint(bestX, bestY)
            } else null
            
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun calculateSSDFast(frame: IntArray, centerX: Int, centerY: Int, template: IntArray, windowSize: Int): Int {
        val halfSize = windowSize / 2
        var sum = 0
        var idx = 0
        
        for (dy in -halfSize..halfSize) {
            for (dx in -halfSize..halfSize) {
                val pixelX = centerX + dx
                val pixelY = centerY + dy
                
                // Add bounds checking to prevent array index out of bounds
                if (pixelX >= 0 && pixelY >= 0 && pixelX < frameWidth && pixelY < frameHeight) {
                    val frameIdx = pixelY * frameWidth + pixelX
                    if (frameIdx < frame.size && idx < template.size) {
                        val pixel = frame[frameIdx]
                        val diff = pixel - template[idx]
                        sum += diff * diff
                    }
                }
                idx++
            }
        }
        return sum
    }
    
    /**
     * Fast corner detection using simplified Harris detector
     */
    private fun detectFeaturesFast(frame: IntArray): List<FeaturePoint> {
        val features = mutableListOf<FeaturePoint>()
        val step = 15  // Larger step for fewer features
        
        // Simplified corner detection
        for (y in step until frameHeight - step step step) {
            for (x in step until frameWidth - step step step) {
                val cornerStrength = calculateCornerStrengthFast(frame, x, y)
                if (cornerStrength > 5000) { // Lower threshold for more features
                    // Check minimum distance from existing features
                    var tooClose = false
                    for (existing in features) {
                        val distance = sqrt((x - existing.x).toFloat().pow(2) + (y - existing.y).toFloat().pow(2))
                        if (distance < minFeatureDistance) {
                            tooClose = true
                            break
                        }
                    }
                    
                    if (!tooClose) {
                        features.add(FeaturePoint(x, y, cornerStrength))
                    }
                }
                
                if (features.size >= maxFeatureCount) break
            }
            if (features.size >= maxFeatureCount) break
        }
        
        return features
    }
    
    private fun calculateCornerStrengthFast(frame: IntArray, x: Int, y: Int): Float {
        // Simplified corner strength calculation using gradients
        val windowSize = 2  // Smaller window
        var Ixx = 0f
        var Iyy = 0f
        var Ixy = 0f
        
        for (dy in -windowSize..windowSize) {
            for (dx in -windowSize..windowSize) {
                val px = x + dx
                val py = y + dy
                
                if (px > 0 && py > 0 && px < frameWidth - 1 && py < frameHeight - 1) {
                    // Calculate gradients
                    val gx = (frame[py * frameWidth + (px + 1)] - frame[py * frameWidth + (px - 1)]) / 2f
                    val gy = (frame[(py + 1) * frameWidth + px] - frame[(py - 1) * frameWidth + px]) / 2f
                    
                    Ixx += gx * gx
                    Iyy += gy * gy
                    Ixy += gx * gy
                }
            }
        }
        
        // Harris corner response
        val det = Ixx * Iyy - Ixy * Ixy
        val trace = Ixx + Iyy
        return det - 0.04f * trace * trace
    }
    
    private fun analyzeFlowForRider(flowVectors: List<FlowVector>): Boolean {
        if (flowVectors.size < minCoherentVectors) {
            return false
        }
        
        // Look for coherent motion patterns
        val avgMagnitude = flowVectors.map { it.magnitude }.average()
        
        // Check for dominant motion direction
        val angles = flowVectors.map { it.angle }
        val avgAngle = atan2(angles.map { sin(it) }.average(), angles.map { cos(it) }.average())
        
        // Count vectors aligned with dominant direction
        val coherentVectors = flowVectors.count { vector ->
            val angleDiff = abs(vector.angle - avgAngle)
            val normalizedDiff = min(angleDiff, 2 * PI - angleDiff)
            normalizedDiff < PI / 3 // Slightly wider tolerance
        }
        
        // Check for reasonable magnitude
        val reasonableMagnitude = avgMagnitude > 4.0 && avgMagnitude < 30.0
        
        return coherentVectors >= minCoherentVectors && reasonableMagnitude
    }
    
    private fun calculateConfidence(flowVectors: List<FlowVector>): Double {
        if (flowVectors.isEmpty()) return 0.0
        
        val avgMagnitude = flowVectors.map { it.magnitude }.average()
        val vectorCount = flowVectors.size
        
        val magnitudeScore = min(1.0, avgMagnitude / 15.0)
        val countScore = min(1.0, vectorCount / 15.0)
        
        return (magnitudeScore * 0.6 + countScore * 0.4).coerceIn(0.0, 1.0)
    }
    
    @androidx.camera.core.ExperimentalGetImage
    private fun imageProxyToIntArray(imageProxy: ImageProxy): IntArray {
        val image = imageProxy.image ?: return IntArray(0)
        
        // Get Y plane for grayscale
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        
        val width = image.width
        val height = image.height
        val pixelStride = yPlane.pixelStride
        val rowStride = yPlane.rowStride
        val rowPadding = rowStride - pixelStride * width
        
        // Extract Y values directly to IntArray for speed
        val data = ByteArray(yBuffer.remaining())
        yBuffer.get(data)
        
        // Downscale to fixed size for consistent performance
        val targetWidth = 160
        val targetHeight = 120
        frameWidth = targetWidth
        frameHeight = targetHeight
        
        val result = IntArray(targetWidth * targetHeight)
        val scaleX = width.toFloat() / targetWidth
        val scaleY = height.toFloat() / targetHeight
        
        var offset = 0
        for (y in 0 until targetHeight) {
            for (x in 0 until targetWidth) {
                val srcX = (x * scaleX).toInt()
                val srcY = (y * scaleY).toInt()
                val srcIdx = srcY * rowStride + srcX * pixelStride
                if (srcIdx < data.size) {
                    result[offset++] = data[srcIdx].toInt() and 0xFF
                } else {
                    result[offset++] = 0
                }
            }
        }
        
        Log.d(TAG, "Fast downscale: ${width}x${height} -> ${targetWidth}x${targetHeight}")
        
        return result
    }
    
    override fun getDisplayName(): String = "Fast Optical Flow Detection"
    
    override fun getDescription(): String = 
        "Optimized optical flow tracking with simplified Lucas-Kanade method. Very fast performance."
    
    override fun configure(settings: Map<String, Any>) {
        maxFeatureCount = (settings["max_features"] as? Int) ?: DEFAULT_FEATURE_COUNT
        minFeatureDistance = (settings["min_feature_distance"] as? Int) ?: DEFAULT_MIN_FEATURE_DISTANCE
        flowThreshold = (settings["flow_threshold"] as? Double) ?: DEFAULT_FLOW_THRESHOLD
        minCoherentVectors = (settings["min_coherent_vectors"] as? Int) ?: DEFAULT_MIN_COHERENT_VECTORS
        maxVectorMagnitude = (settings["max_vector_magnitude"] as? Double) ?: DEFAULT_MAX_VECTOR_MAGNITUDE
        showFlowOverlay = (settings["show_flow_overlay"] as? Boolean) ?: true
    }
    
    override fun getConfigOptions(): Map<String, ConfigOption> = mapOf(
        "max_features" to ConfigOption(
            key = "max_features",
            displayName = "Maximum Features",
            description = "Maximum number of feature points to track",
            type = ConfigType.INTEGER,
            defaultValue = DEFAULT_FEATURE_COUNT,
            minValue = 10,
            maxValue = 50
        ),
        "min_feature_distance" to ConfigOption(
            key = "min_feature_distance",
            displayName = "Minimum Feature Distance",
            description = "Minimum distance between detected features (pixels)",
            type = ConfigType.INTEGER,
            defaultValue = DEFAULT_MIN_FEATURE_DISTANCE,
            minValue = 10,
            maxValue = 40
        ),
        "flow_threshold" to ConfigOption(
            key = "flow_threshold",
            displayName = "Flow Threshold",
            description = "Minimum motion magnitude to be considered valid flow",
            type = ConfigType.FLOAT,
            defaultValue = DEFAULT_FLOW_THRESHOLD,
            minValue = 1.0,
            maxValue = 10.0
        ),
        "min_coherent_vectors" to ConfigOption(
            key = "min_coherent_vectors",
            displayName = "Minimum Coherent Vectors",
            description = "Minimum number of aligned motion vectors for rider detection",
            type = ConfigType.INTEGER,
            defaultValue = DEFAULT_MIN_COHERENT_VECTORS,
            minValue = 2,
            maxValue = 15
        ),
        "show_flow_overlay" to ConfigOption(
            key = "show_flow_overlay",
            displayName = "Show Flow Overlay",
            description = "Display optical flow vectors on camera preview",
            type = ConfigType.BOOLEAN,
            defaultValue = true
        )
    )
    
    override fun reset() {
        super.reset()
        previousFrame = null
        previousFeatures = emptyList()
    }
    
    override fun release() {
        super.release()
        previousFrame = null
        previousFeatures = emptyList()
    }
    
    /**
     * Custom graphic for displaying fast optical flow vectors
     */
    private class OpticalFlowGraphic(
        overlay: GraphicOverlay,
        private val flowVectors: List<FlowVector>,
        private val features: List<FeaturePoint>,
        private val imageWidth: Int,
        private val imageHeight: Int,
        private val flowImageWidth: Int,
        private val flowImageHeight: Int
    ) : GraphicOverlay.Graphic(overlay) {
        
        private val vectorPaint = Paint().apply {
            color = Color.GREEN
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        
        private val featurePaint = Paint().apply {
            color = Color.YELLOW
            strokeWidth = 1f
            style = Paint.Style.FILL
        }
        
        override fun draw(canvas: Canvas) {
            // Calculate preview area (same approach as MotionGraphic)
            val overlayWidth = getOverlayWidth()
            val overlayHeight = getOverlayHeight()
            val cameraAspectRatio = getCameraAspectRatio()
            val viewAspectRatio = overlayWidth.toFloat() / overlayHeight.toFloat()
            
            val previewWidth: Float
            val previewHeight: Float
            val previewLeft: Float
            val previewTop: Float
            
            if (viewAspectRatio > cameraAspectRatio) {
                previewHeight = overlayHeight.toFloat()
                previewWidth = previewHeight * cameraAspectRatio
                previewLeft = (overlayWidth - previewWidth) / 2
                previewTop = 0f
            } else {
                previewWidth = overlayWidth.toFloat()
                previewHeight = previewWidth / cameraAspectRatio
                previewLeft = 0f
                previewTop = (overlayHeight - previewHeight) / 2
            }
            
            // Scale from flow coordinates to preview coordinates
            val scaleX = previewWidth / flowImageWidth.toFloat()
            val scaleY = previewHeight / flowImageHeight.toFloat()
            
            // Draw flow vectors
            for (vector in flowVectors) {
                val startX = previewLeft + (vector.startPoint.x * scaleX)
                val startY = previewTop + (vector.startPoint.y * scaleY)
                val endX = previewLeft + (vector.endPoint.x * scaleX)
                val endY = previewTop + (vector.endPoint.y * scaleY)
                
                canvas.drawLine(startX, startY, endX, endY, vectorPaint)
                
                // Draw arrow head
                val angle = atan2(endY - startY, endX - startX)
                val arrowLength = 20f
                val arrowAngle = PI / 4
                
                val arrowX1 = endX - arrowLength * cos(angle - arrowAngle).toFloat()
                val arrowY1 = endY - arrowLength * sin(angle - arrowAngle).toFloat()
                val arrowX2 = endX - arrowLength * cos(angle + arrowAngle).toFloat()
                val arrowY2 = endY - arrowLength * sin(angle + arrowAngle).toFloat()
                
                canvas.drawLine(endX, endY, arrowX1, arrowY1, vectorPaint)
                canvas.drawLine(endX, endY, arrowX2, arrowY2, vectorPaint)
            }
            
            // Draw feature points
            for (feature in features) {
                val x = previewLeft + (feature.x * scaleX)
                val y = previewTop + (feature.y * scaleY)
                canvas.drawCircle(x, y, 5f, featurePaint)
            }
        }
    }
}