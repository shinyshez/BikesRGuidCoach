package com.mtbanalyzer

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

class SoundManager(context: Context) {
    companion object {
        private const val TAG = "SoundManager"
    }
    
    private val settingsManager = SettingsManager(context)
    private var toneGenerator: ToneGenerator? = null
    
    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create ToneGenerator", e)
        }
    }
    
    fun playRecordingStart() {
        if (settingsManager.isSoundFeedbackEnabled()) {
            playTone(ToneGenerator.TONE_PROP_BEEP)
        }
    }
    
    fun playRecordingStop() {
        if (settingsManager.isSoundFeedbackEnabled()) {
            playTone(ToneGenerator.TONE_PROP_BEEP2)
        }
    }
    
    fun playError() {
        if (settingsManager.isSoundFeedbackEnabled()) {
            playTone(ToneGenerator.TONE_PROP_NACK)
        }
    }
    
    private fun playTone(toneType: Int) {
        try {
            toneGenerator?.startTone(toneType, 200)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play tone", e)
        }
    }
    
    fun release() {
        toneGenerator?.release()
        toneGenerator = null
    }
}