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
    
    override fun draw(canvas: Canvas) {
        val landmarks = pose.allPoseLandmarks
        if (landmarks.isEmpty()) {
            return
        }
        
        // Draw all landmarks
        for (landmark in landmarks) {
            drawPoint(canvas, landmark)
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
    
    private fun drawPoint(canvas: Canvas, landmark: PoseLandmark) {
        val x = translateX(landmark.position.x)
        val y = translateY(landmark.position.y)
        canvas.drawCircle(x, y, 8f, landmarkPaint)
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