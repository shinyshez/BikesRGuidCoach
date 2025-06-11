package com.mtbanalyzer.detector

import android.graphics.*
import android.util.Log
import androidx.camera.core.ImageProxy
import com.mtbanalyzer.GraphicOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

/**
 * Optical flow-based rider detection using Lucas-Kanade method
 * Tracks feature points between frames to detect motion patterns consistent with riders
 */
class OpticalFlowRiderDetector : RiderDetector() {
    
    companion object {
        private const val TAG = "OpticalFlowDetector"
        private const val DEFAULT_FEATURE_COUNT = 25  // Reduced from 100 to 25 for performance
        private const val DEFAULT_MIN_FEATURE_DISTANCE = 15  // Increased spacing to reduce overlap
        private const val DEFAULT_FLOW_THRESHOLD = 2.0
        private const val DEFAULT_MIN_COHERENT_VECTORS = 5  // Reduced from 8 to match lower feature count
        private const val DEFAULT_MAX_VECTOR_MAGNITUDE = 50.0
    }
    
    private var previousFrame: Bitmap? = null
    private var previousFeatures: List<FeaturePoint> = emptyList()
    private var imageScaleFactor = 1.0f
    
    // Configuration parameters
    private var maxFeatureCount = DEFAULT_FEATURE_COUNT
    private var minFeatureDistance = DEFAULT_MIN_FEATURE_DISTANCE
    private var flowThreshold = DEFAULT_FLOW_THRESHOLD
    private var minCoherentVectors = DEFAULT_MIN_COHERENT_VECTORS
    private var maxVectorMagnitude = DEFAULT_MAX_VECTOR_MAGNITUDE
    private var showFlowOverlay = true
    
    data class FeaturePoint(
        val x: Float,
        val y: Float,
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
                // Get rotation info (same as motion detector)
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                val isRotated = rotationDegrees == 90 || rotationDegrees == 270
                
                // Get dimensions - swap if rotated to match what GraphicOverlay expects
                val originalWidth = if (isRotated) imageProxy.height else imageProxy.width
                val originalHeight = if (isRotated) imageProxy.width else imageProxy.height
                
                val bitmapStartTime = System.currentTimeMillis()
                val currentFrame = imageProxyToGrayscaleBitmap(imageProxy)
                val bitmapTime = System.currentTimeMillis() - bitmapStartTime
                
                // Calculate scale factor from original image to processed image
                imageScaleFactor = originalWidth.toFloat() / currentFrame.width.toFloat()
                
                // Debug: Log actual optical flow image dimensions
                android.util.Log.d("OpticalFlow", "ACTUAL_DIMENSIONS: currentFrame=${currentFrame.width}x${currentFrame.height}, original=${originalWidth}x${originalHeight}, bitmapTime=${bitmapTime}ms")
                
                val result = if (previousFrame != null && previousFeatures.isNotEmpty()) {
                    calculateOpticalFlow(previousFrame!!, currentFrame, graphicOverlay, originalWidth, originalHeight)
                } else {
                    // First frame or no previous features - just detect features for next frame
                    val features = detectFeatures(currentFrame)
                    previousFeatures = features
                    DetectionResult(false, 0.0, "First frame - detected ${features.size} features")
                }
                
                previousFrame?.recycle()
                previousFrame = currentFrame
                
                val totalTime = System.currentTimeMillis() - startTime
                android.util.Log.d("OpticalFlow", "TOTAL_PROCESS_TIME: ${totalTime}ms (bitmap: ${bitmapTime}ms)")
                
                result
            } catch (e: Exception) {
                Log.e(TAG, "Error processing optical flow", e)
                DetectionResult(false, 0.0, "Processing error: ${e.message}")
            }
        }
    }
    
    private fun calculateOpticalFlow(
        prevFrame: Bitmap, 
        currentFrame: Bitmap, 
        graphicOverlay: GraphicOverlay,
        originalWidth: Int,
        originalHeight: Int
    ): DetectionResult {
        val flowStartTime = System.currentTimeMillis()
        val flowVectors = mutableListOf<FlowVector>()
        val trackedFeatures = mutableListOf<FeaturePoint>()
        
        // Track each feature from previous frame
        for (feature in previousFeatures) {
            val newPosition = trackFeatureLK(prevFrame, currentFrame, feature)
            if (newPosition != null) {
                val magnitude = sqrt((newPosition.x - feature.x).pow(2) + (newPosition.y - feature.y).pow(2))
                
                // Filter out very small movements and very large movements (likely errors)
                if (magnitude > flowThreshold && magnitude < maxVectorMagnitude) {
                    val angle = atan2(newPosition.y - feature.y, newPosition.x - feature.x)
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
            val newFeatures = detectFeatures(currentFrame)
            previousFeatures = (trackedFeatures + newFeatures).take(maxFeatureCount)
        } else {
            previousFeatures = trackedFeatures
        }
        
        // Draw flow vectors on overlay
        if (showFlowOverlay) {
            graphicOverlay.clear()
            graphicOverlay.add(OpticalFlowGraphic(graphicOverlay, flowVectors, previousFeatures, originalWidth, originalHeight, currentFrame.width, currentFrame.height))
        }
        
        val flowTime = System.currentTimeMillis() - flowStartTime
        
        val debugInfo = "Vectors: ${flowVectors.size}, Features: ${previousFeatures.size}, AvgMag: %.1f, FlowTime: ${flowTime}ms".format(
            flowVectors.map { it.magnitude }.average().takeIf { !it.isNaN() } ?: 0.0
        )
        
        android.util.Log.d("OpticalFlow", "FLOW_CALCULATION_TIME: ${flowTime}ms for ${previousFeatures.size} features")
        
        return DetectionResult(riderDetected, confidence, debugInfo)
    }
    
    /**
     * Simple Lucas-Kanade feature tracking
     */
    private fun trackFeatureLK(
        prevFrame: Bitmap, 
        currentFrame: Bitmap, 
        feature: FeaturePoint
    ): FeaturePoint? {
        val windowSize = 11  // Reduced from 15 to 11 for performance
        val halfWindow = windowSize / 2
        
        val fx = feature.x.toInt()
        val fy = feature.y.toInt()
        
        // Check bounds
        if (fx < halfWindow || fy < halfWindow || 
            fx >= prevFrame.width - halfWindow || fy >= prevFrame.height - halfWindow ||
            fx >= currentFrame.width - halfWindow || fy >= currentFrame.height - halfWindow) {
            return null
        }
        
        try {
            // Extract window from previous frame
            val prevWindow = extractWindow(prevFrame, fx, fy, windowSize)
            
            // Search for best match in current frame (simple template matching)
            var bestX = fx
            var bestY = fy
            var bestScore = Double.MAX_VALUE
            
            val searchRadius = 12  // Reduced from 20 to 12 for performance
            for (dy in -searchRadius..searchRadius step 3) {  // Increased step from 2 to 3
                for (dx in -searchRadius..searchRadius step 3) {
                    val newX = fx + dx
                    val newY = fy + dy
                    
                    if (newX >= halfWindow && newY >= halfWindow && 
                        newX < currentFrame.width - halfWindow && newY < currentFrame.height - halfWindow) {
                        
                        val currentWindow = extractWindow(currentFrame, newX, newY, windowSize)
                        val score = calculateSSD(prevWindow, currentWindow)
                        
                        if (score < bestScore) {
                            bestScore = score
                            bestX = newX
                            bestY = newY
                        }
                    }
                }
            }
            
            // Only return if we found a reasonable match
            return if (bestScore < 1000) { // threshold for reasonable match
                FeaturePoint(bestX.toFloat(), bestY.toFloat())
            } else null
            
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun extractWindow(bitmap: Bitmap, centerX: Int, centerY: Int, size: Int): IntArray {
        val halfSize = size / 2
        val window = IntArray(size * size)
        var index = 0
        
        for (y in -halfSize..halfSize) {
            for (x in -halfSize..halfSize) {
                val pixelX = centerX + x
                val pixelY = centerY + y
                if (pixelX >= 0 && pixelY >= 0 && pixelX < bitmap.width && pixelY < bitmap.height) {
                    window[index] = toGrayscale(bitmap.getPixel(pixelX, pixelY))
                }
                index++
            }
        }
        return window
    }
    
    private fun calculateSSD(window1: IntArray, window2: IntArray): Double {
        var sum = 0.0
        for (i in window1.indices) {
            val diff = window1[i] - window2[i]
            sum += diff * diff
        }
        return sum
    }
    
    /**
     * Detect corner features using a simple corner detector
     */
    private fun detectFeatures(bitmap: Bitmap): List<FeaturePoint> {
        val features = mutableListOf<FeaturePoint>()
        val width = bitmap.width
        val height = bitmap.height
        
        // Use a simple corner detection (approximation of Harris corner detector)
        for (y in 15 until height - 15 step 12) {  // Increased step for fewer features
            for (x in 15 until width - 15 step 12) {
                val cornerStrength = calculateCornerStrength(bitmap, x, y)
                if (cornerStrength > 1000) { // threshold for corner detection
                    // Check minimum distance from existing features
                    var tooClose = false
                    for (existing in features) {
                        val distance = sqrt((x - existing.x).pow(2) + (y - existing.y).pow(2))
                        if (distance < minFeatureDistance) {
                            tooClose = true
                            break
                        }
                    }
                    
                    if (!tooClose) {
                        features.add(FeaturePoint(x.toFloat(), y.toFloat(), cornerStrength))
                    }
                }
                
                if (features.size >= maxFeatureCount) break
            }
            if (features.size >= maxFeatureCount) break
        }
        
        return features
    }
    
    private fun calculateCornerStrength(bitmap: Bitmap, x: Int, y: Int): Float {
        // Simple corner strength calculation
        val windowSize = 3
        var Ixx = 0f
        var Iyy = 0f
        var Ixy = 0f
        
        for (dy in -windowSize..windowSize) {
            for (dx in -windowSize..windowSize) {
                val px = x + dx
                val py = y + dy
                
                if (px > 0 && py > 0 && px < bitmap.width - 1 && py < bitmap.height - 1) {
                    // Calculate gradients
                    val gx = (toGrayscale(bitmap.getPixel(px + 1, py)) - 
                             toGrayscale(bitmap.getPixel(px - 1, py))) / 2f
                    val gy = (toGrayscale(bitmap.getPixel(px, py + 1)) - 
                             toGrayscale(bitmap.getPixel(px, py - 1))) / 2f
                    
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
    
    /**
     * Analyze flow vectors to determine if they indicate rider motion
     */
    private fun analyzeFlowForRider(flowVectors: List<FlowVector>): Boolean {
        if (flowVectors.size < minCoherentVectors) {
            return false
        }
        
        // Look for coherent motion patterns
        val avgMagnitude = flowVectors.map { it.magnitude }.average()
        
        // Check for dominant motion direction (riders typically move in consistent direction)
        val angles = flowVectors.map { it.angle }
        val avgAngle = atan2(angles.map { sin(it) }.average(), angles.map { cos(it) }.average())
        
        // Count vectors that are roughly aligned with dominant direction
        val coherentVectors = flowVectors.count { vector ->
            val angleDiff = abs(vector.angle - avgAngle)
            val normalizedDiff = min(angleDiff, 2 * PI - angleDiff)
            normalizedDiff < PI / 4 // Within 45 degrees
        }
        
        // Check for reasonable magnitude (not too slow, not too fast)
        val reasonableMagnitude = avgMagnitude > 3.0 && avgMagnitude < 25.0
        
        return coherentVectors >= minCoherentVectors && reasonableMagnitude
    }
    
    private fun calculateConfidence(flowVectors: List<FlowVector>): Double {
        if (flowVectors.isEmpty()) return 0.0
        
        val avgMagnitude = flowVectors.map { it.magnitude }.average()
        val vectorCount = flowVectors.size
        
        // Normalize confidence based on number of vectors and their magnitude
        val magnitudeScore = min(1.0, avgMagnitude / 15.0) // Peak confidence at magnitude 15
        val countScore = min(1.0, vectorCount / 20.0) // Peak confidence at 20 vectors
        
        return (magnitudeScore * 0.6 + countScore * 0.4).coerceIn(0.0, 1.0)
    }
    
    @androidx.camera.core.ExperimentalGetImage
    private fun imageProxyToGrayscaleBitmap(imageProxy: ImageProxy): Bitmap {
        val image = imageProxy.image ?: return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        
        // Get the Y plane (luminance) from YUV_420_888 format
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        
        // Get image dimensions
        val width = image.width
        val height = image.height
        val pixelStride = yPlane.pixelStride
        val rowStride = yPlane.rowStride
        val rowPadding = rowStride - pixelStride * width
        
        // Create a grayscale bitmap from Y plane
        val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        
        // Convert Y plane to grayscale pixels using setPixels for speed
        val data = ByteArray(yBuffer.remaining())
        yBuffer.get(data)
        
        // Create pixel array for batch setting - much faster than setPixel
        val pixels = IntArray(width * height)
        var offset = 0
        var pixelIndex = 0
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val yValue = data[offset].toInt() and 0xFF
                pixels[pixelIndex++] = Color.rgb(yValue, yValue, yValue)
                offset += pixelStride
            }
            offset += rowPadding
        }
        
        // Set all pixels at once
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        
        // Return a downscaled version for performance (optical flow needs smaller images)
        // Maintain aspect ratio when downscaling
        val maxDimension = 320
        val scale = minOf(maxDimension.toFloat() / width, maxDimension.toFloat() / height)
        val scaledWidth = (width * scale).toInt()
        val scaledHeight = (height * scale).toInt()
        
        android.util.Log.d("OpticalFlow", "DOWNSCALE: original=${width}x${height}, scaled=${scaledWidth}x${scaledHeight}, scale=$scale")
        
        return Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true).also {
            bitmap.recycle()
        }
    }
    
    private fun toGrayscale(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }
    
    override fun getDisplayName(): String = "Optical Flow Detection"
    
    override fun getDescription(): String = 
        "Tracks feature points between frames using optical flow to detect rider motion patterns. Good for detecting directional movement."
    
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
            minValue = 20,
            maxValue = 200
        ),
        "min_feature_distance" to ConfigOption(
            key = "min_feature_distance",
            displayName = "Minimum Feature Distance",
            description = "Minimum distance between detected features (pixels)",
            type = ConfigType.INTEGER,
            defaultValue = DEFAULT_MIN_FEATURE_DISTANCE,
            minValue = 5,
            maxValue = 30
        ),
        "flow_threshold" to ConfigOption(
            key = "flow_threshold",
            displayName = "Flow Threshold",
            description = "Minimum motion magnitude to be considered valid flow",
            type = ConfigType.FLOAT,
            defaultValue = DEFAULT_FLOW_THRESHOLD,
            minValue = 0.5,
            maxValue = 10.0
        ),
        "min_coherent_vectors" to ConfigOption(
            key = "min_coherent_vectors",
            displayName = "Minimum Coherent Vectors",
            description = "Minimum number of aligned motion vectors for rider detection",
            type = ConfigType.INTEGER,
            defaultValue = DEFAULT_MIN_COHERENT_VECTORS,
            minValue = 3,
            maxValue = 20
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
        previousFrame?.recycle()
        previousFrame = null
        previousFeatures = emptyList()
    }
    
    override fun release() {
        super.release()
        previousFrame?.recycle()
        previousFrame = null
        previousFeatures = emptyList()
    }
    
    /**
     * Custom graphic for displaying optical flow vectors
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
            color = Color.CYAN
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        
        private val featurePaint = Paint().apply {
            color = Color.RED
            strokeWidth = 1f
            style = Paint.Style.FILL
        }
        
        override fun draw(canvas: Canvas) {
            // Use the same approach as MotionGraphic - calculate preview area directly
            val overlayWidth = getOverlayWidth()
            val overlayHeight = getOverlayHeight()
            val cameraAspectRatio = getCameraAspectRatio()
            val viewAspectRatio = overlayWidth.toFloat() / overlayHeight.toFloat()
            
            val previewWidth: Float
            val previewHeight: Float
            val previewLeft: Float
            val previewTop: Float
            
            if (viewAspectRatio > cameraAspectRatio) {
                // View is wider than camera - preview is constrained by height
                previewHeight = overlayHeight.toFloat()
                previewWidth = previewHeight * cameraAspectRatio
                previewLeft = (overlayWidth - previewWidth) / 2
                previewTop = 0f
            } else {
                // View is taller than camera - preview is constrained by width
                previewWidth = overlayWidth.toFloat()
                previewHeight = previewWidth / cameraAspectRatio
                previewLeft = 0f
                previewTop = (overlayHeight - previewHeight) / 2
            }
            
            // Get the actual downscaled image dimensions from the first frame
            // We know the optical flow processing uses downscaled images
            val overlayImageWidth = getOverlayImageWidth()
            val overlayImageHeight = getOverlayImageHeight()
            
            // Debug logging
            android.util.Log.d("OpticalFlow", "DEBUG: overlaySize=${overlayWidth}x${overlayHeight}, " +
                    "previewBounds=($previewLeft,$previewTop)-($previewWidth,$previewHeight), " +
                    "overlayImageSize=${overlayImageWidth}x${overlayImageHeight}, " +
                    "originalImageSize=${imageWidth}x${imageHeight}, " +
                    "cameraAspect=$cameraAspectRatio, viewAspect=$viewAspectRatio")
            
            // The optical flow coordinates are in downscaled image space
            // We need to scale them directly to the preview area
            // Based on logs: optical flow X ~150-200, Y ~10-80, so likely ~320x240 or similar
            
            // Let's directly scale from optical flow coordinate space to preview space
            // We'll assume the optical flow image maintains aspect ratio with original
            val flowImageAspectRatio = imageWidth.toFloat() / imageHeight.toFloat() // 640/480 = 1.33
            
            // Use the actual optical flow image dimensions
            val actualFlowWidth = flowImageWidth.toFloat()
            val actualFlowHeight = flowImageHeight.toFloat()
            
            // The optical flow image maintains the camera aspect ratio, but the preview area
            // might be letterboxed/pillarboxed. We need to scale uniformly to maintain aspect ratio.
            val flowAspectRatio = actualFlowWidth / actualFlowHeight
            val previewAspectRatio = previewWidth / previewHeight
            
            val scale = if (flowAspectRatio > previewAspectRatio) {
                // Flow is wider relative to its height than preview - constrain by width
                previewWidth / actualFlowWidth
            } else {
                // Flow is taller relative to its width than preview - constrain by height
                previewHeight / actualFlowHeight
            }
            
            val scaleX = scale
            val scaleY = scale
            
            // Calculate the actual size of the scaled optical flow content
            val scaledFlowWidth = actualFlowWidth * scale
            val scaledFlowHeight = actualFlowHeight * scale
            
            // Center the optical flow content within the preview area
            val offsetX = (previewWidth - scaledFlowWidth) / 2f
            val offsetY = (previewHeight - scaledFlowHeight) / 2f
            
            android.util.Log.d("OpticalFlow", "ASPECT_RATIO_FIX: flowAspect=$flowAspectRatio, previewAspect=$previewAspectRatio, uniformScale=$scale, offset=($offsetX,$offsetY)")
            
            // Debug: Draw some test points to see coordinate mapping
            val testPaint = Paint().apply {
                color = Color.MAGENTA
                strokeWidth = 8f
                style = Paint.Style.FILL
            }
            
            // Draw test points at corners of the expected area
            canvas.drawCircle(previewLeft, previewTop, 15f, testPaint) // Top-left
            canvas.drawCircle(previewLeft + previewWidth, previewTop, 15f, testPaint) // Top-right
            canvas.drawCircle(previewLeft, previewTop + previewHeight, 15f, testPaint) // Bottom-left
            canvas.drawCircle(previewLeft + previewWidth, previewTop + previewHeight, 15f, testPaint) // Bottom-right
            
            // Draw flow vectors
            for ((index, vector) in flowVectors.withIndex()) {
                // Map optical flow coordinates directly to preview coordinates with centering offset
                val startX = previewLeft + offsetX + (vector.startPoint.x * scaleX)
                val startY = previewTop + offsetY + (vector.startPoint.y * scaleY)
                val endX = previewLeft + offsetX + (vector.endPoint.x * scaleX)
                val endY = previewTop + offsetY + (vector.endPoint.y * scaleY)
                
                // Debug logging for first few vectors
                if (index < 3) {
                    android.util.Log.d("OpticalFlow", "Vector $index: optical(${vector.startPoint.x},${vector.startPoint.y}) -> screen($startX,$startY)")
                }
                
                canvas.drawLine(startX, startY, endX, endY, vectorPaint)
                
                // Draw arrow head
                val angle = atan2(endY - startY, endX - startX)
                val arrowLength = 10f
                val arrowAngle = PI / 6
                
                val arrowX1 = endX - arrowLength * cos(angle - arrowAngle).toFloat()
                val arrowY1 = endY - arrowLength * sin(angle - arrowAngle).toFloat()
                val arrowX2 = endX - arrowLength * cos(angle + arrowAngle).toFloat()
                val arrowY2 = endY - arrowLength * sin(angle + arrowAngle).toFloat()
                
                canvas.drawLine(endX, endY, arrowX1, arrowY1, vectorPaint)
                canvas.drawLine(endX, endY, arrowX2, arrowY2, vectorPaint)
            }
            
            // Draw feature points
            for (feature in features) {
                val x = previewLeft + offsetX + (feature.x * scaleX)
                val y = previewTop + offsetY + (feature.y * scaleY)
                canvas.drawCircle(x, y, 3f, featurePaint)
            }
        }
    }
}