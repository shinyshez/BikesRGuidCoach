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
    private val recordingOverlay: View,
    private val confidenceText: TextView? = null,
    private val pulseRing: View? = null,
    private val recordingProgress: android.widget.ProgressBar? = null,
    private val recordingDot: View? = null
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
                    statusText.text = "Rider Detected"
                    confidenceText?.text = String.format("%.1f%% confidence", confidence * 100)
                    recordingStatus.text = "Ready to record"
                    
                    // Show pulse animation
                    pulseRing?.visibility = View.VISIBLE
                    pulseRing?.animate()
                        ?.scaleX(1.2f)
                        ?.scaleY(1.2f)
                        ?.alpha(0.0f)
                        ?.setDuration(1000)
                        ?.withEndAction {
                            pulseRing?.scaleX = 1.0f
                            pulseRing?.scaleY = 1.0f
                            pulseRing?.alpha = 0.3f
                        }
                        ?.start()
                }
            }
            else -> {
                if (currentState != AppState.RECORDING) {
                    updateState(AppState.MONITORING)
                    statusIndicator.backgroundTintList = ContextCompat.getColorStateList(context, android.R.color.holo_orange_light)
                    statusText.text = "Monitoring"
                    confidenceText?.text = ""
                    recordingStatus.text = "Waiting for rider..."
                    pulseRing?.visibility = View.GONE
                }
            }
        }
    }

    fun updateRecordingStarted() {
        updateState(AppState.RECORDING)
        recordingOverlay.visibility = View.VISIBLE
        recordingProgress?.visibility = View.VISIBLE
        recordingProgress?.progress = 0
        
        statusIndicator.setBackgroundResource(R.drawable.circle_indicator)
        statusIndicator.backgroundTintList = ContextCompat.getColorStateList(context, android.R.color.holo_red_light)
        statusText.text = "Recording"
        confidenceText?.text = ""
        recordingStatus.text = "Recording: 0.0s / 8.0s"
        pulseRing?.visibility = View.GONE
        
        // Animate recording dot
        recordingDot?.animate()
            ?.alpha(0.3f)
            ?.setDuration(500)
            ?.withEndAction {
                recordingDot?.animate()
                    ?.alpha(1.0f)
                    ?.setDuration(500)
                    ?.withEndAction { animateRecordingDot() }
                    ?.start()
            }
            ?.start()
    }
    
    private fun animateRecordingDot() {
        if (currentState == AppState.RECORDING) {
            recordingDot?.animate()
                ?.alpha(0.3f)
                ?.setDuration(500)
                ?.withEndAction {
                    recordingDot?.animate()
                        ?.alpha(1.0f)
                        ?.setDuration(500)
                        ?.withEndAction { animateRecordingDot() }
                        ?.start()
                }
                ?.start()
        }
    }

    fun updateRecordingProgress(elapsedMs: Long, maxDurationMs: Long = 8000L) {
        if (currentState == AppState.RECORDING) {
            val seconds = elapsedMs / 1000
            val tenths = (elapsedMs % 1000) / 100
            val progress = ((elapsedMs.toFloat() / maxDurationMs) * 100).toInt()
            
            recordingStatus.text = String.format(
                "Recording: %d.%ds / %.1fs", 
                seconds, 
                tenths, 
                maxDurationMs / 1000.0
            )
            
            recordingProgress?.progress = progress
        }
    }

    fun updateRecordingFinished(success: Boolean, message: String? = null) {
        recordingOverlay.visibility = View.GONE
        recordingProgress?.visibility = View.GONE
        recordingDot?.clearAnimation()
        
        if (success) {
            updateState(AppState.SAVING)
            recordingStatus.text = message ?: "Video saved!"
            statusIndicator.backgroundTintList = ContextCompat.getColorStateList(context, android.R.color.holo_green_light)
            statusText.text = "Video saved"
            confidenceText?.text = "Success!"
            
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
            statusText.text = "Error"
            confidenceText?.text = "Failed"
            
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
        confidenceText?.text = ""
        recordingStatus.text = "Waiting for rider..."
        recordingOverlay.visibility = View.GONE
        recordingProgress?.visibility = View.GONE
        pulseRing?.visibility = View.GONE
        recordingDot?.clearAnimation()
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