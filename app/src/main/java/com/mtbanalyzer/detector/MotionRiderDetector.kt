package com.mtbanalyzer.detector

import android.graphics.*
import android.util.Log
import androidx.camera.core.ImageProxy
import com.mtbanalyzer.GraphicOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Motion-based rider detection using frame difference analysis
 * Detects significant motion in the camera feed which could indicate a rider
 */
class MotionRiderDetector : RiderDetector() {
    
    companion object {
        private const val TAG = "MotionRiderDetector"
        private const val DEFAULT_MOTION_THRESHOLD = 30
        private const val DEFAULT_MIN_MOTION_AREA = 5000
        private const val DEFAULT_BLUR_RADIUS = 5
    }
    
    private var previousFrame: Bitmap? = null
    private var motionThreshold = DEFAULT_MOTION_THRESHOLD
    private var minMotionArea = DEFAULT_MIN_MOTION_AREA
    private var blurRadius = DEFAULT_BLUR_RADIUS
    private var showMotionOverlay = true
    
    @androidx.camera.core.ExperimentalGetImage
    override suspend fun processFrame(imageProxy: ImageProxy, graphicOverlay: GraphicOverlay): DetectionResult {
        if (!isDetectorEnabled()) {
            return DetectionResult(false, 0.0, "Detector disabled")
        }
        
        return withContext(Dispatchers.Default) {
            try {
                // Get rotation info
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                val isRotated = rotationDegrees == 90 || rotationDegrees == 270
                
                // Get dimensions - swap if rotated to match what GraphicOverlay expects
                val originalWidth = if (isRotated) imageProxy.height else imageProxy.width
                val originalHeight = if (isRotated) imageProxy.width else imageProxy.height
                
                Log.d(TAG, "ImageProxy: ${imageProxy.width}x${imageProxy.height}, rotation: $rotationDegrees, isRotated: $isRotated")
                Log.d(TAG, "Using dimensions: ${originalWidth}x${originalHeight}")
                
                val currentFrame = imageProxyToBitmap(imageProxy)
                val result = if (previousFrame != null) {
                    detectMotion(previousFrame!!, currentFrame, graphicOverlay, originalWidth, originalHeight)
                } else {
                    DetectionResult(false, 0.0, "First frame - no previous frame to compare")
                }
                
                previousFrame?.recycle()
                previousFrame = currentFrame
                
                result
            } catch (e: Exception) {
                Log.e(TAG, "Error processing frame", e)
                DetectionResult(false, 0.0, "Processing error: ${e.message}")
            }
        }
    }
    
    private fun detectMotion(prevFrame: Bitmap, currentFrame: Bitmap, graphicOverlay: GraphicOverlay, originalWidth: Int, originalHeight: Int): DetectionResult {
        val width = min(prevFrame.width, currentFrame.width)
        val height = min(prevFrame.height, currentFrame.height)
        
        // Sample step size
        val sampleStep = 4
        
        // Create motion bitmap at sampled resolution
        val sampledWidth = (width + sampleStep - 1) / sampleStep
        val sampledHeight = (height + sampleStep - 1) / sampleStep
        val motionBitmap = Bitmap.createBitmap(sampledWidth, sampledHeight, Bitmap.Config.ARGB_8888)
        
        Log.d(TAG, "detectMotion - input frame size: ${width}x${height}, sampleStep: $sampleStep")
        Log.d(TAG, "detectMotion - creating sampled bitmap: ${sampledWidth}x${sampledHeight}")
        
        var motionPixelCount = 0
        var motionY = 0
        
        // Debug: track motion detection bounds
        var minMotionX = Int.MAX_VALUE
        var maxMotionX = Int.MIN_VALUE
        var minMotionY = Int.MAX_VALUE
        var maxMotionY = Int.MIN_VALUE
        
        for (y in 0 until height step sampleStep) {
            var motionX = 0
            for (x in 0 until width step sampleStep) {
                val prevPixel = prevFrame.getPixel(x, y)
                val currPixel = currentFrame.getPixel(x, y)
                
                val prevGray = toGrayscale(prevPixel)
                val currGray = toGrayscale(currPixel)
                
                val diff = abs(currGray - prevGray)
                
                if (diff > motionThreshold) {
                    motionPixelCount++
                    if (showMotionOverlay) {
                        // Set pixel in motion bitmap
                        motionBitmap.setPixel(motionX, motionY, Color.RED)
                        // Track bounds of motion detection
                        minMotionX = min(minMotionX, motionX)
                        maxMotionX = max(maxMotionX, motionX)
                        minMotionY = min(minMotionY, motionY)
                        maxMotionY = max(maxMotionY, motionY)
                    }
                }
                motionX++
            }
            motionY++
        }
        
        // Debug: log motion detection bounds
        if (motionPixelCount > 0) {
            Log.d(TAG, "Motion detected in bitmap coords: X[$minMotionX-$maxMotionX] Y[$minMotionY-$maxMotionY] out of [0-${sampledWidth-1}][0-${sampledHeight-1}]")
        }
        
        // Update overlay
        if (showMotionOverlay) {
            graphicOverlay.clear()
            graphicOverlay.add(MotionGraphic(graphicOverlay, motionBitmap, originalWidth, originalHeight, showGrid = false))
        }
        
        val motionArea = motionPixelCount * sampleStep * sampleStep // Account for sampling
        val riderDetected = motionArea > minMotionArea
        val confidence = min(1.0, motionArea.toDouble() / (minMotionArea * 3)) // Scale confidence
        
        val debugInfo = "Motion pixels: $motionPixelCount, Area: $motionArea, Threshold: $minMotionArea"
        
        return DetectionResult(riderDetected, confidence, debugInfo)
    }
    
    private fun toGrayscale(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }
    
    @androidx.camera.core.ExperimentalGetImage
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
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
        
        // Create a grayscale bitmap from Y plane (without padding)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        // Convert Y plane to grayscale pixels
        val data = ByteArray(yBuffer.remaining())
        yBuffer.get(data)
        
        var offset = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = data[offset].toInt() and 0xFF
                val color = Color.rgb(pixel, pixel, pixel)
                bitmap.setPixel(x, y, color)
                offset += pixelStride
            }
            // Skip row padding
            offset += rowPadding
        }
        
        // Return a downscaled version for performance
        val scaledWidth = width / 4
        val scaledHeight = height / 4
        return Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true).also {
            bitmap.recycle()
        }
    }
    
    override fun getDisplayName(): String = "Motion Detection"
    
    override fun getDescription(): String = 
        "Detects riders by analyzing motion between consecutive frames. Fast but may trigger on other moving objects."
    
    override fun configure(settings: Map<String, Any>) {
        motionThreshold = (settings["motion_threshold"] as? Int) ?: DEFAULT_MOTION_THRESHOLD
        minMotionArea = (settings["min_motion_area"] as? Int) ?: DEFAULT_MIN_MOTION_AREA
        blurRadius = (settings["blur_radius"] as? Int) ?: DEFAULT_BLUR_RADIUS
        showMotionOverlay = (settings["show_motion_overlay"] as? Boolean) ?: true
    }
    
    override fun getConfigOptions(): Map<String, ConfigOption> = mapOf(
        "motion_threshold" to ConfigOption(
            key = "motion_threshold",
            displayName = "Motion Threshold",
            description = "Pixel intensity difference threshold for motion detection (0-255)",
            type = ConfigType.INTEGER,
            defaultValue = DEFAULT_MOTION_THRESHOLD,
            minValue = 5,
            maxValue = 100
        ),
        "min_motion_area" to ConfigOption(
            key = "min_motion_area",
            displayName = "Minimum Motion Area",
            description = "Minimum number of motion pixels to trigger detection",
            type = ConfigType.INTEGER,
            defaultValue = DEFAULT_MIN_MOTION_AREA,
            minValue = 1000,
            maxValue = 50000
        ),
        "show_motion_overlay" to ConfigOption(
            key = "show_motion_overlay",
            displayName = "Show Motion Overlay",
            description = "Display motion detection areas on camera preview",
            type = ConfigType.BOOLEAN,
            defaultValue = true
        )
    )
    
    override fun reset() {
        super.reset()
        previousFrame?.recycle()
        previousFrame = null
    }
    
    override fun release() {
        super.release()
        previousFrame?.recycle()
        previousFrame = null
    }
    
    /**
     * Custom graphic for displaying motion detection overlay
     */
    private class MotionGraphic(
        overlay: GraphicOverlay,
        private val motionBitmap: Bitmap,
        private val imageWidth: Int,
        private val imageHeight: Int,
        private val showGrid: Boolean = false
    ) : GraphicOverlay.Graphic(overlay) {
        
        override fun draw(canvas: Canvas) {
            val paint = Paint().apply {
                alpha = 128 // Semi-transparent
                color = Color.RED
            }
            
            val gridPaint = Paint().apply {
                color = Color.GREEN
                alpha = 64 // Very transparent
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
            
            val boundsPaint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            
            // Use the dynamic camera aspect ratio from GraphicOverlay
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
            
            // Map motion bitmap to the actual preview area
            val blockSizeX = previewWidth / motionBitmap.width
            val blockSizeY = previewHeight / motionBitmap.height
            
            // Draw motion areas only
            for (y in 0 until motionBitmap.height) {
                for (x in 0 until motionBitmap.width) {
                    val pixel = motionBitmap.getPixel(x, y)
                    if (pixel == Color.RED) {
                        // Calculate direct screen coordinates within the preview area
                        val left = previewLeft + (x * blockSizeX)
                        val top = previewTop + (y * blockSizeY)
                        val right = left + blockSizeX
                        val bottom = top + blockSizeY
                        
                        // Draw the rectangle directly
                        canvas.drawRect(left, top, right, bottom, paint)
                    }
                }
            }
        }
    }
}