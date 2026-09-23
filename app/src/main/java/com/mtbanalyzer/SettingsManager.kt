package com.mtbanalyzer

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

class SettingsManager(context: Context) {
    private val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    
    companion object {
        private const val KEY_RECORDING_DURATION = "recording_duration"
        private const val KEY_DETECTION_SENSITIVITY = "detection_sensitivity"
        private const val KEY_POST_RIDER_DELAY = "post_rider_delay"
        private const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"
        private const val KEY_SOUND_FEEDBACK = "sound_feedback"
        private const val KEY_START_CALLOUT = "start_callout"
        // Not the key of the Settings row itself ("start_callout_sample"), which stores nothing
        private const val KEY_START_CALLOUT_SAMPLE = "start_callout_sample_uri"
        private const val KEY_SHOW_POSE_OVERLAY = "show_pose_overlay"
        private const val KEY_DETECTOR_TYPE = "detector_type"
        private const val KEY_RIDER_DETECTION_ENABLED = "rider_detection_enabled"
        private const val KEY_MOTION_THRESHOLD = "motion_threshold"
        private const val KEY_MIN_MOTION_AREA = "min_motion_area"
        private const val KEY_SHOW_MOTION_OVERLAY = "show_motion_overlay"
        private const val KEY_SHOW_PERFORMANCE_OVERLAY = "show_performance_overlay"
        private const val KEY_BLUETOOTH_REMOTE_ENABLED = "bluetooth_remote_enabled"
        private const val KEY_VIEWER_LINK_ENABLED = "viewer_link_enabled"
        
        // Default values
        private const val DEFAULT_RECORDING_DURATION = 8
        private const val DEFAULT_DETECTION_SENSITIVITY = 70
        private const val DEFAULT_POST_RIDER_DELAY = 2
        private const val DEFAULT_HAPTIC_FEEDBACK = true
        private const val DEFAULT_SOUND_FEEDBACK = false
        private const val DEFAULT_START_CALLOUT = false
        private const val DEFAULT_SHOW_POSE_OVERLAY = true
        private const val DEFAULT_DETECTOR_TYPE = "pose"
        private const val DEFAULT_RIDER_DETECTION_ENABLED = false
        private const val DEFAULT_MOTION_THRESHOLD = 30
        private const val DEFAULT_MIN_MOTION_AREA = 5000
        private const val DEFAULT_SHOW_MOTION_OVERLAY = true
        private const val DEFAULT_SHOW_PERFORMANCE_OVERLAY = false
        private const val DEFAULT_BLUETOOTH_REMOTE_ENABLED = false
        private const val DEFAULT_VIEWER_LINK_ENABLED = false
    }
    
    fun getRecordingDuration(): Int {
        return sharedPreferences.getInt(KEY_RECORDING_DURATION, DEFAULT_RECORDING_DURATION)
    }
    
    fun getRecordingDurationMs(): Long {
        return getRecordingDuration() * 1000L
    }
    
    fun getDetectionSensitivity(): Int {
        return sharedPreferences.getInt(KEY_DETECTION_SENSITIVITY, DEFAULT_DETECTION_SENSITIVITY)
    }
    
    fun getDetectionSensitivityThreshold(): Double {
        // Convert percentage to threshold (higher percentage = lower threshold)
        return (100 - getDetectionSensitivity()) / 100.0
    }
    
    fun getPostRiderDelay(): Int {
        return sharedPreferences.getInt(KEY_POST_RIDER_DELAY, DEFAULT_POST_RIDER_DELAY)
    }
    
    fun getPostRiderDelayMs(): Long {
        return getPostRiderDelay() * 1000L
    }
    
    fun isHapticFeedbackEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_HAPTIC_FEEDBACK, DEFAULT_HAPTIC_FEEDBACK)
    }
    
    fun isSoundFeedbackEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_SOUND_FEEDBACK, DEFAULT_SOUND_FEEDBACK)
    }
    
    /** Whether a spoken callout ("Dropping") replaces the beep when a recording starts. */
    fun isStartCalloutEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_START_CALLOUT, DEFAULT_START_CALLOUT)
    }

    /** The user's own callout clip, or null when the bundled sample should be used. */
    fun getStartCalloutSample(): String? {
        return sharedPreferences.getString(KEY_START_CALLOUT_SAMPLE, null)?.takeIf { it.isNotBlank() }
    }

    /** Stores a picked audio file as the callout clip; null goes back to the bundled sample. */
    fun setStartCalloutSample(uri: String?) {
        val editor = sharedPreferences.edit()
        if (uri.isNullOrBlank()) {
            editor.remove(KEY_START_CALLOUT_SAMPLE)
        } else {
            editor.putString(KEY_START_CALLOUT_SAMPLE, uri)
        }
        editor.apply()
    }

    fun shouldShowPoseOverlay(): Boolean {
        return sharedPreferences.getBoolean(KEY_SHOW_POSE_OVERLAY, DEFAULT_SHOW_POSE_OVERLAY)
    }
    
    fun getDetectorType(): String {
        return sharedPreferences.getString(KEY_DETECTOR_TYPE, DEFAULT_DETECTOR_TYPE) ?: DEFAULT_DETECTOR_TYPE
    }
    
    fun setDetectorType(detectorType: String) {
        sharedPreferences.edit().putString(KEY_DETECTOR_TYPE, detectorType).apply()
    }
    
    fun isRiderDetectionEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_RIDER_DETECTION_ENABLED, DEFAULT_RIDER_DETECTION_ENABLED)
    }
    
    fun setRiderDetectionEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_RIDER_DETECTION_ENABLED, enabled).apply()
    }
    
    fun getMotionThreshold(): Int {
        return sharedPreferences.getInt(KEY_MOTION_THRESHOLD, DEFAULT_MOTION_THRESHOLD)
    }
    
    fun getMinMotionArea(): Int {
        return sharedPreferences.getInt(KEY_MIN_MOTION_AREA, DEFAULT_MIN_MOTION_AREA)
    }
    
    fun shouldShowMotionOverlay(): Boolean {
        return sharedPreferences.getBoolean(KEY_SHOW_MOTION_OVERLAY, DEFAULT_SHOW_MOTION_OVERLAY)
    }
    
    fun getMotionDetectorSettings(): Map<String, Any> {
        return mapOf(
            "motion_threshold" to getMotionThreshold(),
            "min_motion_area" to getMinMotionArea(),
            "show_motion_overlay" to shouldShowMotionOverlay()
        )
    }
    
    fun shouldShowPerformanceOverlay(): Boolean {
        return sharedPreferences.getBoolean(KEY_SHOW_PERFORMANCE_OVERLAY, DEFAULT_SHOW_PERFORMANCE_OVERLAY)
    }
    
    fun isBluetoothRemoteEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_BLUETOOTH_REMOTE_ENABLED, DEFAULT_BLUETOOTH_REMOTE_ENABLED)
    }
    
    /**
     * Whether the viewer link was left on. Recorded so Settings can say so at a glance; the
     * server is only ever started from the Viewer Link screen, never silently on launch.
     */
    fun isViewerLinkEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_VIEWER_LINK_ENABLED, DEFAULT_VIEWER_LINK_ENABLED)
    }

    fun setViewerLinkEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_VIEWER_LINK_ENABLED, enabled).apply()
    }
    
    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
    }
    
    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
    }
}