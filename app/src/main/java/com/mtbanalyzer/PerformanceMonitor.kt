package com.mtbanalyzer

import android.os.SystemClock
import android.util.Log
import kotlin.math.roundToInt

/**
 * Performance monitoring for frame processing and detection
 */
class PerformanceMonitor {
    companion object {
        private const val TAG = "PerformanceMonitor"
        private const val FPS_SAMPLE_WINDOW_MS = 1000L // Calculate FPS every second
        private const val MAX_FRAME_SAMPLES = 30
    }
    
    // Frame timing
    private var lastFrameTime = 0L
    private val frameTimes = mutableListOf<Long>()
    private var frameCount = 0L
    private var lastFpsCalculationTime = 0L
    
    // FPS calculation
    private var currentFps = 0.0
    private var averageFrameTimeMs = 0.0
    private var droppedFrames = 0
    
    // Detection timing
    private var firstDetectionTime = 0L
    private var recordingStartTime = 0L
    private var detectionToRecordingLatencyMs = 0L
    
    // Processing times
    private var lastDetectorProcessingTimeMs = 0L
    private var lastFrameProcessingTimeMs = 0L
    
    // Memory tracking
    private var lastMemoryUsageMb = 0
    
    // Status
    private var isEnabled = true
    
    /**
     * Mark the start of frame processing
     */
    fun onFrameStart(): Long {
        return SystemClock.elapsedRealtime()
    }
    
    /**
     * Mark the end of frame processing
     */
    fun onFrameEnd(startTime: Long) {
        if (!isEnabled) return
        
        val currentTime = SystemClock.elapsedRealtime()
        val frameTime = currentTime - startTime
        
        // Update frame timing
        lastFrameTime = currentTime
        frameTimes.add(frameTime)
        if (frameTimes.size > MAX_FRAME_SAMPLES) {
            frameTimes.removeAt(0)
        }
        frameCount++
        
        lastFrameProcessingTimeMs = frameTime
        
        // Calculate FPS periodically
        if (currentTime - lastFpsCalculationTime >= FPS_SAMPLE_WINDOW_MS) {
            calculateFps(currentTime)
            lastFpsCalculationTime = currentTime
        }
        
        // Update memory usage
        updateMemoryUsage()
    }
    
    /**
     * Mark detector processing time
     */
    fun onDetectorProcessed(processingTimeMs: Long) {
        lastDetectorProcessingTimeMs = processingTimeMs
    }
    
    /**
     * Mark when a rider is first detected
     */
    fun onRiderDetected() {
        if (firstDetectionTime == 0L) {
            firstDetectionTime = SystemClock.elapsedRealtime()
            Log.d(TAG, "First rider detection at: $firstDetectionTime")
        }
    }
    
    /**
     * Mark when recording actually starts
     */
    fun onRecordingStarted() {
        recordingStartTime = SystemClock.elapsedRealtime()
        
        if (firstDetectionTime > 0) {
            detectionToRecordingLatencyMs = recordingStartTime - firstDetectionTime
            Log.d(TAG, "Detection to recording latency: ${detectionToRecordingLatencyMs}ms")
        }
    }
    
    /**
     * Reset detection timing for next session
     */
    fun resetDetectionTiming() {
        firstDetectionTime = 0L
        recordingStartTime = 0L
        detectionToRecordingLatencyMs = 0L
    }
    
    /**
     * Mark a dropped frame
     */
    fun onFrameDropped() {
        droppedFrames++
    }
    
    private fun calculateFps(currentTime: Long) {
        if (frameTimes.isEmpty()) return
        
        // Calculate average frame time
        averageFrameTimeMs = frameTimes.average()
        
        // Calculate FPS from average frame time
        currentFps = if (averageFrameTimeMs > 0) {
            1000.0 / averageFrameTimeMs
        } else {
            0.0
        }
    }
    
    private fun updateMemoryUsage() {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        lastMemoryUsageMb = (usedMemory / 1024 / 1024).toInt()
    }
    
    /**
     * Get current performance metrics
     */
    fun getMetrics(): PerformanceMetrics {
        return PerformanceMetrics(
            fps = currentFps,
            averageFrameTimeMs = averageFrameTimeMs,
            lastFrameTimeMs = lastFrameProcessingTimeMs,
            droppedFrames = droppedFrames,
            detectorProcessingTimeMs = lastDetectorProcessingTimeMs,
            memoryUsageMb = lastMemoryUsageMb,
            detectionToRecordingLatencyMs = detectionToRecordingLatencyMs
        )
    }
    
    /**
     * Enable/disable performance monitoring
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) {
            reset()
        }
    }
    
    /**
     * Reset all metrics
     */
    fun reset() {
        frameTimes.clear()
        frameCount = 0
        droppedFrames = 0
        currentFps = 0.0
        averageFrameTimeMs = 0.0
        lastFrameProcessingTimeMs = 0L
        lastDetectorProcessingTimeMs = 0L
        resetDetectionTiming()
    }
    
    data class PerformanceMetrics(
        val fps: Double,
        val averageFrameTimeMs: Double,
        val lastFrameTimeMs: Long,
        val droppedFrames: Int,
        val detectorProcessingTimeMs: Long,
        val memoryUsageMb: Int,
        val detectionToRecordingLatencyMs: Long
    ) {
        fun toDisplayString(): String {
            return buildString {
                appendLine("FPS: ${fps.roundToInt()} (${averageFrameTimeMs.roundToInt()}ms avg)")
                appendLine("Detector: ${detectorProcessingTimeMs}ms")
                appendLine("Memory: ${memoryUsageMb}MB")
                if (detectionToRecordingLatencyMs > 0) {
                    appendLine("Det→Rec: ${detectionToRecordingLatencyMs}ms")
                }
                if (droppedFrames > 0) {
                    appendLine("Dropped: $droppedFrames")
                }
            }
        }
    }
}