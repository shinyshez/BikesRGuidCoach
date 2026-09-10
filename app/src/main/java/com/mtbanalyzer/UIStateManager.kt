package com.mtbanalyzer

import android.content.Context
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Drives the capture screen's one status chip and the recording progress line.
 *
 * The chip says a single thing at a time: "Watching for a rider" (amber) while auto-record
 * is armed, "Rider in frame" (green), "REC 0:03 / 0:08" (red) while recording, then a
 * brief "Saved" or error. In manual mode it stays hidden unless a recording is in progress.
 */
class UIStateManager(
    private val context: Context,
    private val statusChip: View,
    private val statusDot: View,
    private val statusText: TextView,
    private val recordingProgress: ProgressBar
) {
    private val settingsManager = SettingsManager(context)

    enum class AppState {
        MONITORING,
        RIDER_DETECTED,
        RECORDING,
        SAVING,
        ERROR
    }

    private var currentState = AppState.MONITORING
    private var autoMode = false

    /** Whether auto-record is armed; decides if the idle chip shows at all. */
    fun setAutoMode(enabled: Boolean) {
        autoMode = enabled
        if (currentState == AppState.MONITORING || currentState == AppState.RIDER_DETECTED) {
            showIdle(riderDetected = currentState == AppState.RIDER_DETECTED)
        }
    }

    fun updateDetectionState(riderDetected: Boolean, confidence: Double, isRecording: Boolean) {
        if (isRecording || currentState == AppState.RECORDING) return
        if (currentState == AppState.SAVING || currentState == AppState.ERROR) return
        currentState = if (riderDetected) AppState.RIDER_DETECTED else AppState.MONITORING
        showIdle(riderDetected)
    }

    private fun showIdle(riderDetected: Boolean) {
        recordingProgress.visibility = View.GONE
        if (!autoMode) {
            statusChip.visibility = View.GONE
            return
        }
        statusChip.visibility = View.VISIBLE
        statusChip.setBackgroundResource(R.drawable.badge_background)
        if (riderDetected) {
            tintDot(android.R.color.holo_green_light)
            statusText.text = "Rider in frame"
        } else {
            tintDot(android.R.color.holo_orange_light)
            statusText.text = "Watching for a rider"
        }
    }

    fun updateRecordingStarted() {
        currentState = AppState.RECORDING
        statusChip.visibility = View.VISIBLE
        statusChip.setBackgroundResource(R.drawable.status_chip_recording)
        tintDot(android.R.color.holo_red_light)
        recordingProgress.visibility = View.VISIBLE
        recordingProgress.progress = 0
        updateRecordingProgress(0)
    }

    fun updateRecordingProgress(elapsedMs: Long) {
        if (currentState != AppState.RECORDING) return
        val maxDurationMs = settingsManager.getRecordingDurationMs()
        statusText.text = "REC ${formatClock(elapsedMs)} / ${formatClock(maxDurationMs)}"
        recordingProgress.progress = ((elapsedMs.toFloat() / maxDurationMs) * 100).toInt()
    }

    fun updateRecordingFinished(success: Boolean, message: String? = null) {
        recordingProgress.visibility = View.GONE
        statusChip.visibility = View.VISIBLE
        if (success) {
            currentState = AppState.SAVING
            statusChip.setBackgroundResource(R.drawable.status_chip_saved)
            tintDot(android.R.color.holo_green_light)
            statusText.text = message ?: "Saved"
            statusChip.postDelayed({ if (currentState == AppState.SAVING) resetToMonitoring() }, 2000)
        } else {
            showError(message ?: "Recording failed")
        }
    }

    fun updateError(errorMessage: String) {
        recordingProgress.visibility = View.GONE
        showError(errorMessage)
    }

    private fun showError(message: String) {
        currentState = AppState.ERROR
        statusChip.visibility = View.VISIBLE
        statusChip.setBackgroundResource(R.drawable.status_chip_recording)
        tintDot(android.R.color.holo_red_light)
        statusText.text = message
        statusChip.postDelayed({ if (currentState == AppState.ERROR) resetToMonitoring() }, 3000)
    }

    private fun resetToMonitoring() {
        currentState = AppState.MONITORING
        showIdle(riderDetected = false)
    }

    private fun tintDot(colorRes: Int) {
        statusDot.backgroundTintList = ContextCompat.getColorStateList(context, colorRes)
    }

    private fun formatClock(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        return String.format("%d:%02d", totalSec / 60, totalSec % 60)
    }

    fun getCurrentState(): AppState = currentState

    fun canStartRecording(): Boolean {
        return currentState == AppState.MONITORING || currentState == AppState.RIDER_DETECTED
    }
}
