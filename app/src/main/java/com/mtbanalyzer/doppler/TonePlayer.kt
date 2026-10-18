package com.mtbanalyzer.doppler

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays a continuous sine through the speaker: the other half of the Doppler gate experiment.
 *
 * The buffer holds a whole number of cycles (18 kHz at 48 kHz repeats every 8 samples), so a
 * static AudioTrack can loop it with no click at the seam.
 */
class TonePlayer(private val toneHz: Double = 18_000.0, private val sampleRate: Int = 48_000) {

    private var track: AudioTrack? = null

    val isPlaying: Boolean get() = track != null

    fun start() {
        if (track != null) return
        val samples = sampleRate / 10 // 100 ms: 1800 whole cycles of 18 kHz
        val pcm = ShortArray(samples) { (sin(2 * PI * toneHz * it / sampleRate) * 0.9 * Short.MAX_VALUE).toInt().toShort() }
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples * 2)
            .build()
        t.write(pcm, 0, samples)
        t.setLoopPoints(0, samples, -1)
        t.play()
        track = t
    }

    fun stop() {
        track?.run { stop(); release() }
        track = null
    }
}
