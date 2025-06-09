package com.mtbanalyzer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * Overlay graphic for displaying performance metrics
 */
class PerformanceOverlay(
    overlay: GraphicOverlay,
    private val performanceMonitor: PerformanceMonitor,
    private val detectorName: String
) : GraphicOverlay.Graphic(overlay) {
    
    private val textPaint = Paint().apply {
        color = Color.GREEN
        textSize = 32f
        isAntiAlias = true
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }
    
    private val backgroundPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0) // Semi-transparent black
        style = Paint.Style.FILL
    }
    
    private val warningPaint = Paint().apply {
        color = Color.YELLOW
        textSize = 32f
        isAntiAlias = true
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }
    
    private val criticalPaint = Paint().apply {
        color = Color.RED
        textSize = 32f
        isAntiAlias = true
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }
    
    override fun draw(canvas: Canvas) {
        val metrics = performanceMonitor.getMetrics()
        
        // Position in top-left corner
        val padding = 20f
        val lineHeight = 40f
        var yPosition = padding + 50f // Start below top status bar
        
        // Calculate background size
        val lines = mutableListOf<String>()
        lines.add("Detector: $detectorName")
        lines.add("FPS: ${metrics.fps.toInt()} (${metrics.averageFrameTimeMs.toInt()}ms)")
        lines.add("Process: ${metrics.lastFrameTimeMs}ms")
        lines.add("Detect: ${metrics.detectorProcessingTimeMs}ms")
        lines.add("Memory: ${metrics.memoryUsageMb}MB")
        
        if (metrics.detectionToRecordingLatencyMs > 0) {
            lines.add("Det→Rec: ${metrics.detectionToRecordingLatencyMs}ms")
        }
        
        if (metrics.droppedFrames > 0) {
            lines.add("Dropped: ${metrics.droppedFrames}")
        }
        
        // Draw background
        val maxWidth = lines.maxOf { textPaint.measureText(it) }
        val backgroundRect = RectF(
            padding,
            yPosition - 30f,
            padding + maxWidth + 20f,
            yPosition + (lines.size * lineHeight) + 10f
        )
        canvas.drawRoundRect(backgroundRect, 10f, 10f, backgroundPaint)
        
        // Draw detector name
        canvas.drawText(lines[0], padding + 10f, yPosition, textPaint)
        yPosition += lineHeight
        
        // Draw FPS with color coding
        val fpsPaint = when {
            metrics.fps < 15 -> criticalPaint
            metrics.fps < 25 -> warningPaint
            else -> textPaint
        }
        canvas.drawText(lines[1], padding + 10f, yPosition, fpsPaint)
        yPosition += lineHeight
        
        // Draw processing times
        val processPaint = when {
            metrics.lastFrameTimeMs > 50 -> criticalPaint
            metrics.lastFrameTimeMs > 33 -> warningPaint
            else -> textPaint
        }
        canvas.drawText(lines[2], padding + 10f, yPosition, processPaint)
        yPosition += lineHeight
        
        // Draw detector time
        canvas.drawText(lines[3], padding + 10f, yPosition, textPaint)
        yPosition += lineHeight
        
        // Draw memory usage
        val memoryPaint = when {
            metrics.memoryUsageMb > 400 -> criticalPaint
            metrics.memoryUsageMb > 300 -> warningPaint
            else -> textPaint
        }
        canvas.drawText(lines[4], padding + 10f, yPosition, memoryPaint)
        yPosition += lineHeight
        
        // Draw detection to recording latency if available
        if (metrics.detectionToRecordingLatencyMs > 0) {
            val latencyPaint = when {
                metrics.detectionToRecordingLatencyMs > 1000 -> criticalPaint
                metrics.detectionToRecordingLatencyMs > 500 -> warningPaint
                else -> textPaint
            }
            canvas.drawText(lines[5], padding + 10f, yPosition, latencyPaint)
            yPosition += lineHeight
        }
        
        // Draw dropped frames if any
        if (metrics.droppedFrames > 0) {
            canvas.drawText(lines[lines.size - 1], padding + 10f, yPosition, criticalPaint)
        }
    }
}