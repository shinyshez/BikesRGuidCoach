package com.mtbanalyzer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark

class PoseGraphic(
    overlay: GraphicOverlay,
    private val pose: Pose
) : GraphicOverlay.Graphic(overlay) {
    
    private val landmarkPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        strokeWidth = 10f
    }
    
    private val linePaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    
    // Debug paint for highlighting visible landmarks
    private val debugPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        strokeWidth = 15f
    }
    
    override fun draw(canvas: Canvas) {
        // Always draw a debug indicator to show overlay is active
        val debugIndicatorPaint = Paint().apply {
            color = Color.CYAN
            style = Paint.Style.FILL
        }
        // Draw a small circle in top-left corner
        canvas.drawCircle(50f, 50f, 20f, debugIndicatorPaint)
        
        val landmarks = pose.allPoseLandmarks
        if (landmarks.isEmpty()) {
            android.util.Log.d("PoseGraphic", "No landmarks to draw")
            return
        }
        
        android.util.Log.d("PoseGraphic", "Drawing ${landmarks.size} landmarks")
        android.util.Log.d("PoseGraphic", "Canvas size: ${canvas.width}x${canvas.height}")
        android.util.Log.d("PoseGraphic", "Overlay dimensions: ${getOverlayWidth()}x${getOverlayHeight()}")
        android.util.Log.d("PoseGraphic", "Image dimensions: ${getOverlayImageWidth()}x${getOverlayImageHeight()}")
        
        // Draw all landmarks
        for ((index, landmark) in landmarks.withIndex()) {
            drawPoint(canvas, landmark, index)
        }
        
        // Draw connections between body parts
        drawLine(canvas, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER)
        drawLine(canvas, PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW)
        drawLine(canvas, PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST)
        drawLine(canvas, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW)
        drawLine(canvas, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST)
        drawLine(canvas, PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_HIP)
        drawLine(canvas, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_HIP)
        drawLine(canvas, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP)
        drawLine(canvas, PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE)
        drawLine(canvas, PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE)
        drawLine(canvas, PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE)
        drawLine(canvas, PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE)
    }
    
    private fun drawPoint(canvas: Canvas, landmark: PoseLandmark, index: Int) {
        val originalX = landmark.position.x
        val originalY = landmark.position.y
        val x = translateX(originalX)
        val y = translateY(originalY)
        
        // Debug logging for first few landmarks
        if (index < 3) {
            android.util.Log.d("PoseGraphic", "Landmark $index (${landmark.landmarkType}): original=($originalX, $originalY), translated=($x, $y)")
            android.util.Log.d("PoseGraphic", "Canvas bounds: width=${canvas.width}, height=${canvas.height}")
            android.util.Log.d("PoseGraphic", "Point visibility: x in [0,${canvas.width}]: ${x >= 0 && x <= canvas.width}, y in [0,${canvas.height}]: ${y >= 0 && y <= canvas.height}")
        }
        
        // Use different colors for first few landmarks to help debugging
        val paint = when {
            index < 3 -> debugPaint // Red for first 3 landmarks
            else -> landmarkPaint   // Green for others
        }
        
        canvas.drawCircle(x, y, 8f, paint)
    }
    
    private fun drawLine(canvas: Canvas, startLandmarkType: Int, endLandmarkType: Int) {
        val startLandmark = pose.getPoseLandmark(startLandmarkType)
        val endLandmark = pose.getPoseLandmark(endLandmarkType)
        
        if (startLandmark != null && endLandmark != null) {
            val startX = translateX(startLandmark.position.x)
            val startY = translateY(startLandmark.position.y)
            val endX = translateX(endLandmark.position.x)
            val endY = translateY(endLandmark.position.y)
            
            canvas.drawLine(startX, startY, endX, endY, linePaint)
        }
    }
}