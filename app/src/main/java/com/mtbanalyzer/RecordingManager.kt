package com.mtbanalyzer

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

class RecordingManager(
    private val context: Context,
    private val contentResolver: ContentResolver
) {
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
            }
            is VideoRecordEvent.Finalize -> {
                if (!recordEvent.hasError()) {
                    val msg = "Video capture succeeded: ${recordEvent.outputResults.outputUri}"
                    Log.d(TAG, msg)
                    synchronized(recordingLock) {
                        recordingState = RecordingState.SAVING
                    }
                    callback.onRecordingFinished(true, recordEvent.outputResults.outputUri.toString(), null)
                    
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
        Log.d(TAG, "Recording resources released")
    }
}