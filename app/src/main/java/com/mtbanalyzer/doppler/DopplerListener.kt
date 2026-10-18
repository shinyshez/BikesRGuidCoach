package com.mtbanalyzer.doppler

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import kotlin.math.max

/**
 * Runs [DopplerGateDetector] on the microphone on its own thread.
 *
 * Each pass is also placed on the boot clock ([SystemClock.elapsedRealtimeNanos]), the clock
 * the camera stamps frames with, so a pass can be matched to a frame of a recording. The
 * detector counts samples; [AudioRecord.getTimestamp] says when a given sample was captured,
 * and the two together give the crossing's capture time without the read latency.
 *
 * Callbacks arrive on the audio thread. The caller must hold RECORD_AUDIO.
 */
class DopplerListener(context: Context, private val callback: Callback) {

    interface Callback {
        fun onFrame(frame: DopplerGateDetector.Frame, baselineHz: Double) {}
        fun onPass(pass: DopplerGateDetector.Pass, elapsedRealtimeNanos: Long) {}
        /** Listening could not start, or stopped on an error. Not called after [stop]. */
        fun onError(message: String) {}
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Which input was opened, for display. Set by [start]. */
    var sourceName: String = ""
        private set

    @Volatile private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    /** Opens the mic and starts listening. False (after [Callback.onError]) if it couldn't. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            callback.onError("This device can't record at $SAMPLE_RATE Hz")
            return false
        }
        // Noise suppression and AGC can eat a steady 18 kHz tone; ask for the rawest input.
        val unprocessed = audioManager.getProperty(
            AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED
        ) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED
        else MediaRecorder.AudioSource.VOICE_RECOGNITION
        sourceName = if (unprocessed) "unprocessed mic" else "voice-recognition mic"

        val bufferBytes = max(minBuf, SAMPLE_RATE / 5 * 2)
        val record = try {
            AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord($sourceName) failed", e)
            callback.onError("Couldn't open the $sourceName: $e")
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            callback.onError("Couldn't open the $sourceName")
            return false
        }

        val detector = DopplerGateDetector(SAMPLE_RATE)
        val clock = SampleClock(SAMPLE_RATE)
        detector.listener = object : DopplerGateDetector.Listener {
            override fun onFrame(frame: DopplerGateDetector.Frame) =
                callback.onFrame(frame, detector.baselineHz)

            override fun onPass(pass: DopplerGateDetector.Pass) =
                callback.onPass(pass, clock.toElapsedNanos(pass.timeSec))
        }

        Log.i(TAG, "Listening: $sourceName, $SAMPLE_RATE Hz, buffer $bufferBytes bytes")
        running = true
        thread = Thread({
            val buf = ShortArray(SAMPLE_RATE / 50)
            val ts = AudioTimestamp()
            var samplesRead = 0L
            try {
                record.startRecording()
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n < 0) { Log.w(TAG, "AudioRecord.read returned $n"); break }
                    if (n == 0) continue
                    samplesRead += n
                    if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                        clock.anchor(ts.framePosition, ts.nanoTime)
                    } else {
                        // No hardware timestamp: the newest sample was captured about now.
                        clock.anchor(samplesRead, SystemClock.elapsedRealtimeNanos())
                    }
                    detector.process(buf, 0, n)
                }
            } catch (e: Throwable) {
                // Anything uncaught here kills the whole app; report it instead.
                Log.e(TAG, "Listening failed", e)
                if (running) callback.onError("Listening failed: $e")
            } finally {
                running = false
                runCatching { record.stop() }
                record.release()
            }
        }, "doppler-gate").apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    companion object {
        private const val TAG = "DopplerGate"
        const val SAMPLE_RATE = 48_000
    }
}

/**
 * Converts the detector's time (seconds of audio since the first sample) to the boot clock,
 * from the latest (sample index, capture time) pair the recorder reported.
 */
class SampleClock(private val sampleRate: Int) {
    @Volatile private var anchorFrame = 0L
    @Volatile private var anchorNanos = 0L

    fun anchor(framePosition: Long, nanoTime: Long) {
        anchorNanos = nanoTime
        anchorFrame = framePosition
    }

    fun toElapsedNanos(timeSec: Double): Long {
        val sample = timeSec * sampleRate
        return anchorNanos + ((sample - anchorFrame) * 1e9 / sampleRate).toLong()
    }
}
