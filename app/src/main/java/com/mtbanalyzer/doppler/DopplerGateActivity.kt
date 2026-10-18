package com.mtbanalyzer.doppler

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mtbanalyzer.R
import kotlin.math.max

/**
 * Developer experiment: is a Doppler zero crossing a usable timing gate?
 *
 * One phone plays an 18 kHz tone, the other listens; carry one of them past the other. Each
 * pass flashes the screen and logs its crossing time, the split from the previous pass and
 * the speed the approach shift implies. Either button works alone, so one phone can do both.
 */
class DopplerGateActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DopplerGate"
        private const val SAMPLE_RATE = 48_000
        private const val MAX_LOG_LINES = 20
    }

    private lateinit var statusText: TextView
    private lateinit var liveText: TextView
    private lateinit var passLog: TextView
    private lateinit var trace: DopplerTraceView
    private lateinit var flash: View
    private lateinit var listenButton: Button
    private lateinit var toneButton: Button

    private val tone = TonePlayer()
    @Volatile private var listening = false
    private var audioThread: Thread? = null
    private var sourceName = ""
    private var frameCount = 0
    private var lastPassSec: Double? = null
    private var passCount = 0
    private val logLines = ArrayDeque<String>()

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else statusText.text = "Microphone permission denied"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_doppler_gate)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        statusText = findViewById(R.id.dopplerStatus)
        liveText = findViewById(R.id.dopplerLive)
        passLog = findViewById(R.id.dopplerPassLog)
        trace = findViewById(R.id.dopplerTrace)
        flash = findViewById(R.id.dopplerFlash)
        listenButton = findViewById(R.id.dopplerListenButton)
        toneButton = findViewById(R.id.dopplerToneButton)

        listenButton.setOnClickListener {
            if (listening) stopListening()
            else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) startListening()
            else requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
        toneButton.setOnClickListener {
            if (tone.isPlaying) tone.stop() else tone.start()
            refreshButtons()
        }
        findViewById<Button>(R.id.dopplerClearButton).setOnClickListener {
            logLines.clear(); passCount = 0; lastPassSec = null
            passLog.text = ""; trace.clear()
        }
        refreshButtons()
    }

    override fun onStop() {
        super.onStop()
        stopListening()
        tone.stop()
        refreshButtons()
    }

    @SuppressLint("MissingPermission") // checked by the caller
    private fun startListening() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            statusText.text = "This device can't record at $SAMPLE_RATE Hz"
            return
        }
        // Noise suppression and AGC can eat a steady 18 kHz tone; ask for the rawest input.
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val unprocessed = audioManager.getProperty(
            AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED
        ) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED
        else MediaRecorder.AudioSource.VOICE_RECOGNITION
        sourceName = if (unprocessed) "unprocessed mic" else "voice-recognition mic"

        val record = AudioRecord(
            source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf, SAMPLE_RATE / 5 * 2)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            statusText.text = "Couldn't open the microphone"
            return
        }

        val detector = DopplerGateDetector(SAMPLE_RATE)
        trace.thresholdHz = detector.thresholdHz
        detector.listener = object : DopplerGateDetector.Listener {
            override fun onFrame(frame: DopplerGateDetector.Frame) {
                trace.addFrame(frame)
                if (++frameCount % 10 == 0) runOnUiThread { showLive(frame, detector.baselineHz) }
            }

            override fun onPass(pass: DopplerGateDetector.Pass) {
                trace.addPass(pass.timeSec)
                runOnUiThread { onPass(pass) }
            }
        }

        listening = true
        audioThread = Thread({
            val buf = ShortArray(SAMPLE_RATE / 50)
            try {
                record.startRecording()
                while (listening) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) detector.process(buf, 0, n)
                    else if (n < 0) { Log.w(TAG, "AudioRecord.read returned $n"); break }
                }
            } finally {
                record.stop()
                record.release()
            }
        }, "doppler-gate").apply { start() }

        statusText.text = "Listening for 18 kHz…"
        refreshButtons()
    }

    private fun stopListening() {
        listening = false
        audioThread?.join(500)
        audioThread = null
        if (!isFinishing) statusText.text = "Not listening"
        refreshButtons()
    }

    private fun showLive(frame: DopplerGateDetector.Frame, baselineHz: Double) {
        if (!listening) return
        liveText.text = if (frame.heard) {
            "Shift %+.1f Hz · SNR %.0f dB · tone at %.1f Hz\n%s".format(
                frame.shiftHz, frame.snrDb, baselineHz, sourceName
            )
        } else {
            "No tone heard (SNR %.0f dB)\n%s".format(frame.snrDb, sourceName)
        }
    }

    private fun onPass(pass: DopplerGateDetector.Pass) {
        passCount++
        val split = lastPassSec?.let { "  split %.3f s".format(pass.timeSec - it) } ?: ""
        lastPassSec = pass.timeSec
        val line = "#%d  %.3f s%s\n    ≈ %.1f m/s (%.0f km/h) · +%.0f Hz · confirmed +%.0f ms".format(
            passCount, pass.timeSec, split, pass.speedMps, pass.speedMps * 3.6,
            pass.approachHz, (pass.confirmedAtSec - pass.timeSec) * 1000
        )
        Log.i(TAG, line.replace("\n", ""))
        logLines.addFirst(line)
        while (logLines.size > MAX_LOG_LINES) logLines.removeLast()
        passLog.text = logLines.joinToString("\n")
        statusText.text = "PASS #$passCount"

        flash.animate().cancel()
        flash.alpha = 1f
        flash.animate().alpha(0f).setDuration(700).start()
        flash.performHapticFeedback(
            HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    private fun refreshButtons() {
        listenButton.text = if (listening) "Stop listening" else "Listen"
        toneButton.text = if (tone.isPlaying) "Stop tone" else "Play 18 kHz tone"
    }
}
