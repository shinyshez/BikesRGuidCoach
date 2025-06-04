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
        private const val KEY_SHOW_POSE_OVERLAY = "show_pose_overlay"
        
        // Default values
        private const val DEFAULT_RECORDING_DURATION = 8
        private const val DEFAULT_DETECTION_SENSITIVITY = 70
        private const val DEFAULT_POST_RIDER_DELAY = 2
        private const val DEFAULT_HAPTIC_FEEDBACK = true
        private const val DEFAULT_SOUND_FEEDBACK = false
        private const val DEFAULT_SHOW_POSE_OVERLAY = true
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
    
    fun shouldShowPoseOverlay(): Boolean {
        return sharedPreferences.getBoolean(KEY_SHOW_POSE_OVERLAY, DEFAULT_SHOW_POSE_OVERLAY)
    }
    
    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
    }
    
    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
    }
}