package com.mtbanalyzer

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

class RecordingManager(
    private val context: Context,
    private val contentResolver: ContentResolver
) {
    private val settingsManager = SettingsManager(context)
    private val soundManager = SoundManager(context)
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    companion object {
        private const val TAG = "RecordingManager"
    }

    enum class RecordingState {
        IDLE, RECORDING, ERROR, SAVING
    }

    interface RecordingCallback {
        fun onRecordingStarted()
        fun onRecordingFinished(success: Boolean, uri: String?, error: String?)
        fun onRecordingProgress(elapsedMs: Long)
    }

    private var recording: Recording? = null
    private var recordingStartTime = 0L
    private val recordingLock = Object()

    @Volatile
    var recordingState = RecordingState.IDLE
        private set

    fun startRecording(
        videoCapture: VideoCapture<Recorder>,
        callback: RecordingCallback
    ): Boolean {
        synchronized(recordingLock) {
            Log.d(TAG, "startRecording called, current state: $recordingState")

            // Prevent concurrent recording attempts
            if (recordingState != RecordingState.IDLE) {
                Log.w(TAG, "Cannot start recording - current state is $recordingState")
                return false
            }

            if (recording != null) {
                Log.w(TAG, "Recording object exists but should be null in IDLE state")
                return false
            }

            // Set state immediately to prevent race conditions
            recordingState = RecordingState.RECORDING
        }

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "MTB_$name")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/MTBAnalyzer")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        Log.d(TAG, "Preparing recording with output: $mediaStoreOutputOptions")

        recording = videoCapture.output
            .prepareRecording(context, mediaStoreOutputOptions)
            .start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                handleRecordingEvent(recordEvent, callback)
            }

        return true
    }

    private fun handleRecordingEvent(recordEvent: VideoRecordEvent, callback: RecordingCallback) {
        when (recordEvent) {
            is VideoRecordEvent.Start -> {
                recordingStartTime = System.currentTimeMillis()
                callback.onRecordingStarted()
                Log.d(TAG, "Recording started")
                
                // Haptic feedback on recording start
                if (settingsManager.isHapticFeedbackEnabled()) {
                    performHapticFeedback(HapticPattern.RECORDING_START)
                }
                
                // Sound feedback on recording start
                soundManager.playRecordingStart()
            }
            is VideoRecordEvent.Finalize -> {
                if (!recordEvent.hasError()) {
                    val msg = "Video capture succeeded: ${recordEvent.outputResults.outputUri}"
                    Log.d(TAG, msg)
                    synchronized(recordingLock) {
                        recordingState = RecordingState.SAVING
                    }
                    callback.onRecordingFinished(true, recordEvent.outputResults.outputUri.toString(), null)
                    
                    // Haptic feedback on successful recording
                    if (settingsManager.isHapticFeedbackEnabled()) {
                        performHapticFeedback(HapticPattern.RECORDING_SUCCESS)
                    }
                    
                    // Sound feedback on successful recording
                    soundManager.playRecordingStop()
                    
                    // Reset to idle after a delay
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        synchronized(recordingLock) {
                            if (recordingState == RecordingState.SAVING) {
                                recordingState = RecordingState.IDLE
                            }
                        }
                    }, 2000)
                } else {
                    recording?.close()
                    recording = null
                    recordingStartTime = 0L
                    Log.e(TAG, "Video capture failed: ${recordEvent.error}")
                    synchronized(recordingLock) {
                        recordingState = RecordingState.ERROR
                    }
                    callback.onRecordingFinished(false, null, "Error ${recordEvent.error}")
                    
                    // Haptic feedback on recording error
                    if (settingsManager.isHapticFeedbackEnabled()) {
                        performHapticFeedback(HapticPattern.RECORDING_ERROR)
                    }
                    
                    // Sound feedback on recording error
                    soundManager.playError()
                    
                    // Reset to idle after showing error
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        synchronized(recordingLock) {
                            if (recordingState == RecordingState.ERROR) {
                                recordingState = RecordingState.IDLE
                            }
                        }
                    }, 3000)
                }
            }
        }
    }

    fun stopRecording() {
        val currentRecording = recording
        if (currentRecording != null) {
            currentRecording.stop()
            recording = null
            recordingStartTime = 0L
            Log.d(TAG, "Recording stopped")
        } else {
            Log.w(TAG, "stopRecording called but no recording exists")
        }
    }

    fun getElapsedTime(): Long {
        return if (recordingStartTime > 0) {
            System.currentTimeMillis() - recordingStartTime
        } else {
            0L
        }
    }

    fun isRecording(): Boolean {
        return recording != null && recordingState == RecordingState.RECORDING
    }

    fun release() {
        recording?.stop()
        recording = null
        recordingStartTime = 0L
        synchronized(recordingLock) {
            recordingState = RecordingState.IDLE
        }
        soundManager.release()
        Log.d(TAG, "Recording resources released")
    }
    
    private enum class HapticPattern {
        RECORDING_START,
        RECORDING_SUCCESS,
        RECORDING_ERROR
    }
    
    private fun performHapticFeedback(pattern: HapticPattern) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = when (pattern) {
                    HapticPattern.RECORDING_START -> VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                    HapticPattern.RECORDING_SUCCESS -> VibrationEffect.createWaveform(longArrayOf(0, 50, 100, 50), -1)
                    HapticPattern.RECORDING_ERROR -> VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100), -1)
                }
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                when (pattern) {
                    HapticPattern.RECORDING_START -> vibrator.vibrate(100)
                    HapticPattern.RECORDING_SUCCESS -> vibrator.vibrate(longArrayOf(0, 50, 100, 50), -1)
                    HapticPattern.RECORDING_ERROR -> vibrator.vibrate(longArrayOf(0, 100, 50, 100), -1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to perform haptic feedback", e)
        }
    }
}