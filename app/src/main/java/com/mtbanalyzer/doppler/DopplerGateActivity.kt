package com.mtbanalyzer.doppler

import android.Manifest
import android.content.pm.PackageManager
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
    private val listening: Boolean get() = listener.isRunning
    private val listener by lazy {
        DopplerListener(this, object : DopplerListener.Callback {
            override fun onFrame(frame: DopplerGateDetector.Frame, baselineHz: Double) {
                trace.addFrame(frame)
                if (++frameCount % 10 == 0) runOnUiThread { showLive(frame, baselineHz) }
            }

            override fun onPass(pass: DopplerGateDetector.Pass, elapsedRealtimeNanos: Long) {
                trace.addPass(pass.timeSec)
                runOnUiThread { showPass(pass) }
            }

            override fun onError(message: String) {
                runOnUiThread { stopListening(); statusText.text = message }
            }
        })
    }
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

    private fun startListening() {
        if (listener.start()) {
            statusText.text = "Listening for 18 kHz…"
            refreshButtons()
        }
    }

    private fun stopListening() {
        listener.stop()
        if (!isFinishing) statusText.text = "Not listening"
        refreshButtons()
    }

    private fun showLive(frame: DopplerGateDetector.Frame, baselineHz: Double) {
        if (!listening) return
        liveText.text = if (frame.heard) {
            "Shift %+.1f Hz · SNR %.0f dB · tone at %.1f Hz\n%s".format(
                frame.shiftHz, frame.snrDb, baselineHz, listener.sourceName
            )
        } else {
            "No tone heard (SNR %.0f dB)\n%s".format(frame.snrDb, listener.sourceName)
        }
    }

    private fun showPass(pass: DopplerGateDetector.Pass) {
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
