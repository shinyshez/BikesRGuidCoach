package com.mtbanalyzer

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat

class UIStateManager(
    private val context: Context,
    private val statusIndicator: View,
    private val statusText: TextView,
    private val recordingStatus: TextView,
    private val recordingOverlay: TextView
) {
    companion object {
        private const val TAG = "UIStateManager"
    }

    enum class AppState {
        MONITORING,
        RIDER_DETECTED,
        RECORDING,
        SAVING,
        ERROR
    }

    private var currentState = AppState.MONITORING

    fun updateDetectionState(riderDetected: Boolean, confidence: Double, isRecording: Boolean) {
        if (isRecording) {
            // Don't update detection state while recording
            return
        }

        when {
            riderDetected -> {
                if (currentState != AppState.RECORDING) {
                    updateState(AppState.RIDER_DETECTED)
                    statusIndicator.backgroundTintList = ContextCompat.getColorStateList(context, android.R.color.holo_green_light)
                    statusText.text = String.format("Rider Detected (%.1f%%)", confidence * 100)
                    recordingStatus.text = "Ready to record"
                }
            }
            else -> {
                if (currentState != AppState.RECORDING) {
                    updateState(AppState.MONITORING)
                    statusIndicator.backgroundTintList = ContextCompat.getColorStateList(context, android.R.color.holo_orange_light)
                    statusText.text = "Monitoring"
                    recordingStatus.text = "Waiting for rider..."
                }
            }
        }
    }

    fun updateRecordingStarted() {
        updateState(AppState.RECORDING)
        recordingOverlay.visibility = View.VISIBLE
        statusIndicator.setBackgroundResource(R.drawable.circle_indicator)
        statusIndicator.backgroundTintList = ContextCompat.getColorStateList(context, android.R.color.holo_red_light)
        statusText.text = "Recording"
        recordingStatus.text = "Recording: 0.0s / 8.0s"
    }

    fun updateRecordingProgress(elapsedMs: Long, maxDurationMs: Long = 8000L) {
        if (currentState == AppState.RECORDING) {
            val seconds = elapsedMs / 1000
            val tenths = (elapsedMs % 1000) / 100
            recordingStatus.text = String.format(
                "Recording: %d.%ds / %.1fs", 
                seconds, 
                tenths, 
                maxDurationMs / 1000.0
            )
        }
    }

    fun updateRecordingFinished(success: Boolean, message: String? = null) {
        recordingOverlay.visibility = View.GONE
        
        if (success) {
            updateState(AppState.SAVING)
            recordingStatus.text = message ?: "Video saved!"
            statusIndicator.backgroundTintList = ContextCompat.getColorStateList(context, android.R.color.holo_green_light)
            statusText.text = "Video saved"
            
            // Auto-reset to monitoring after delay
            statusIndicator.postDelayed({
                if (currentState == AppState.SAVING) {
                    resetToMonitoring()
                }
            }, 2000)
        } else {
            updateState(AppState.ERROR)
            recordingStatus.text = message ?: "Recording failed"
            statusIndicator.backgroundTintList = ContextCompat.getColorStateList(context, android.R.color.holo_red_light)
            statusText.text = message ?: "Recording error"
            
            // Auto-reset to monitoring after error display
            statusIndicator.postDelayed({
                if (currentState == AppState.ERROR) {
                    resetToMonitoring()
                }
            }, 3000)
        }
    }

    fun updateError(errorMessage: String) {
        updateState(AppState.ERROR)
        statusIndicator.backgroundTintList = ContextCompat.getColorStateList(context, android.R.color.holo_red_light)
        statusText.text = "Error"
        recordingStatus.text = errorMessage
        recordingOverlay.visibility = View.GONE
        
        // Auto-reset after showing error
        statusIndicator.postDelayed({
            if (currentState == AppState.ERROR) {
                resetToMonitoring()
            }
        }, 3000)
    }

    private fun resetToMonitoring() {
        updateState(AppState.MONITORING)
        statusIndicator.backgroundTintList = ContextCompat.getColorStateList(context, android.R.color.holo_orange_light)
        statusText.text = "Monitoring"
        recordingStatus.text = "Waiting for rider..."
        recordingOverlay.visibility = View.GONE
    }

    private fun updateState(newState: AppState) {
        currentState = newState
    }

    fun getCurrentState(): AppState {
        return currentState
    }

    fun canStartRecording(): Boolean {
        return currentState == AppState.MONITORING || currentState == AppState.RIDER_DETECTED
    }
}