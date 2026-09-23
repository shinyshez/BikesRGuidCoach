package com.mtbanalyzer

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.util.Log

/**
 * Audio feedback for recording events: short tones by default, or a spoken callout
 * ("Dropping") in place of the start beep when the user turns one on in Settings.
 *
 * The callout clip is either the user's own pick (a persisted `content://` URI from the
 * file picker) or an optional clip bundled at `res/raw/dropping.<ext>`. The bundled one is
 * looked up by name so the app builds and runs whether or not it is there.
 */
class SoundManager(private val context: Context) {
    companion object {
        private const val TAG = "SoundManager"

        /** Base name of the optional bundled callout clip: `app/src/main/res/raw/dropping.m4a`. */
        const val BUNDLED_CALLOUT_NAME = "dropping"

        /**
         * What to play when a recording starts. The callout replaces the start beep whenever
         * it is on and a clip is available; with no clip it falls back to the beep, so turning
         * the callout on can never leave the start of a recording silent for a user who had
         * sound feedback on.
         */
        fun resolveStartSound(
            soundFeedbackEnabled: Boolean,
            calloutEnabled: Boolean,
            calloutSample: String?
        ): StartSound = when {
            calloutEnabled && !calloutSample.isNullOrBlank() -> StartSound.Callout(calloutSample)
            soundFeedbackEnabled -> StartSound.Beep
            else -> StartSound.None
        }
    }

    /** The sound that starting a recording makes. */
    sealed class StartSound {
        object None : StartSound()
        object Beep : StartSound()
        data class Callout(val uri: String) : StartSound()
    }

    private val settingsManager = SettingsManager(context)
    private var toneGenerator: ToneGenerator? = null

    /** Kept prepared between recordings so the callout is not late to the drop. */
    private var calloutPlayer: MediaPlayer? = null
    private var preparedCallout: String? = null

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create ToneGenerator", e)
        }
    }

    fun playRecordingStart() {
        when (val sound = resolveStartSound(
            settingsManager.isSoundFeedbackEnabled(),
            settingsManager.isStartCalloutEnabled(),
            calloutSample()
        )) {
            is StartSound.Callout -> {
                if (!playCallout(sound.uri) && settingsManager.isSoundFeedbackEnabled()) {
                    playTone(ToneGenerator.TONE_PROP_BEEP)
                }
            }
            StartSound.Beep -> playTone(ToneGenerator.TONE_PROP_BEEP)
            StartSound.None -> {}
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

    /**
     * The callout clip to play: the user's pick if they chose one, otherwise the bundled
     * sample, or null when neither is there.
     */
    fun calloutSample(): String? = settingsManager.getStartCalloutSample() ?: bundledCallout()

    /** The bundled `res/raw/dropping` clip, or null if this build does not ship one. */
    @SuppressLint("DiscouragedApi") // optional resource: looked up by name so the build works without it
    fun bundledCallout(): String? {
        val id = context.resources.getIdentifier(BUNDLED_CALLOUT_NAME, "raw", context.packageName)
        return if (id != 0) "android.resource://${context.packageName}/$id" else null
    }

    /**
     * Decodes the callout clip ahead of the next recording. Call it off the recording path
     * (e.g. from `onResume`) — the clip is short and local, but preparing it here keeps the
     * decode out of the moment the rider drops in.
     */
    @Synchronized
    fun prepareCallout() {
        val sample = calloutSample().takeIf { settingsManager.isStartCalloutEnabled() }
        if (sample == null) releaseCallout() else loadCallout(sample)
    }

    /**
     * Plays the callout clip regardless of the enable switch, for the Settings preview.
     * Returns false when there is no usable clip.
     */
    fun playCalloutPreview(): Boolean {
        val sample = calloutSample() ?: return false
        return playCallout(sample)
    }

    /** Plays [sample], preparing it first if it is not the clip already loaded. */
    @Synchronized
    private fun playCallout(sample: String): Boolean {
        if (!loadCallout(sample)) return false

        return try {
            calloutPlayer?.let {
                if (it.isPlaying) it.pause()
                it.seekTo(0)
                it.start()
                true
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play callout sample $sample", e)
            releaseCallout()
            false
        }
    }

    /** Prepares [sample] for playback, reusing the player when it already holds that clip. */
    @Synchronized
    private fun loadCallout(sample: String): Boolean {
        if (sample == preparedCallout && calloutPlayer != null) return true

        releaseCallout()
        return try {
            calloutPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, Uri.parse(sample))
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "Callout playback error ($what, $extra)")
                    false
                }
                prepare()
            }
            preparedCallout = sample
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load callout sample $sample", e)
            releaseCallout()
            false
        }
    }

    private fun playTone(toneType: Int) {
        try {
            toneGenerator?.startTone(toneType, 200)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play tone", e)
        }
    }

    @Synchronized
    private fun releaseCallout() {
        try {
            calloutPlayer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release callout player", e)
        }
        calloutPlayer = null
        preparedCallout = null
    }

    fun release() {
        toneGenerator?.release()
        toneGenerator = null
        releaseCallout()
    }
}
