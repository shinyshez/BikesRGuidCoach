package com.mtbanalyzer.detector

import androidx.camera.core.ImageProxy
import com.mtbanalyzer.GraphicOverlay

/**
 * Abstract interface for rider detection mechanisms.
 * Implementations can use different approaches like pose detection, motion detection,
 * object detection, or hybrid methods.
 */
abstract class RiderDetector {
    
    /**
     * Data class representing detection result
     */
    data class DetectionResult(
        val riderDetected: Boolean,
        val confidence: Double,
        val debugInfo: String = ""
    )
    
    /**
     * Callback interface for detection events
     */
    interface DetectionCallback {
        fun onRiderDetected(confidence: Double, debugInfo: String = "")
        fun onRiderLost()
        fun onUpdateUI(riderDetected: Boolean, confidence: Double, debugInfo: String = "")
    }
    
    private var detectionCallback: DetectionCallback? = null
    private var detectorEnabled = true
    
    /**
     * Set the callback for detection events
     */
    fun setDetectionCallback(callback: DetectionCallback) {
        this.detectionCallback = callback
    }
    
    /**
     * Enable or disable the detector
     */
    open fun setDetectorEnabled(enabled: Boolean) {
        detectorEnabled = enabled
    }
    
    /**
     * Check if detector is enabled
     */
    protected fun isDetectorEnabled(): Boolean = detectorEnabled
    
    /**
     * Get the current callback
     */
    protected fun getDetectionCallback(): DetectionCallback? = detectionCallback
    
    /**
     * Process an image frame for rider detection
     */
    abstract suspend fun processFrame(imageProxy: ImageProxy, graphicOverlay: GraphicOverlay): DetectionResult
    
    /**
     * Get the display name of this detector
     */
    abstract fun getDisplayName(): String
    
    /**
     * Get a description of how this detector works
     */
    abstract fun getDescription(): String
    
    /**
     * Configure detector-specific settings
     */
    abstract fun configure(settings: Map<String, Any>)
    
    /**
     * Get configuration options for this detector
     */
    abstract fun getConfigOptions(): Map<String, ConfigOption>
    
    /**
     * Reset detector state
     */
    open fun reset() {
        // Default implementation - override if needed
    }
    
    /**
     * Release detector resources
     */
    open fun release() {
        detectionCallback = null
    }
    
    /**
     * Configuration option definition
     */
    data class ConfigOption(
        val key: String,
        val displayName: String,
        val description: String,
        val type: ConfigType,
        val defaultValue: Any,
        val minValue: Any? = null,
        val maxValue: Any? = null,
        val options: List<String>? = null
    )
    
    enum class ConfigType {
        BOOLEAN,
        INTEGER,
        FLOAT,
        STRING,
        ENUM
    }
}