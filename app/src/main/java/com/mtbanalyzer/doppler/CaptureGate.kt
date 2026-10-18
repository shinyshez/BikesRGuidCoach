package com.mtbanalyzer.doppler

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.mtbanalyzer.clips.ClipRef

/**
 * The timing gate on the capture screen: listens for the beacon while the screen is up and
 * records, against each saved clip, where in it the rider passed.
 *
 * The owner forwards its lifecycle ([resume] / [pause]) and each saved clip's timeline
 * ([onClipTimeline]); everything else happens here. Listening only runs while [enabled].
 */
class CaptureGate(
    private val activity: Activity,
    private val indicator: View,
    private val dot: View,
    private val label: TextView,
) {
    private val store = PassMarkStore(activity)
    private val marker = PassMarker({ key, ms ->
        store.add(key, ms)
        Log.i(TAG, "Pass marked in clip $key at $ms ms")
    })
    private val listener = DopplerListener(activity, object : DopplerListener.Callback {
        override fun onFrame(frame: DopplerGateDetector.Frame, baselineHz: Double) {
            if (++frames % 10 == 0) activity.runOnUiThread { showHeard(frame.heard) }
        }

        override fun onPass(pass: DopplerGateDetector.Pass, elapsedRealtimeNanos: Long) {
            activity.runOnUiThread {
                marker.onPass(elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos())
                flashPass()
            }
        }

        override fun onError(message: String) {
            activity.runOnUiThread {
                indicator.visibility = View.VISIBLE
                label.text = "Gate off"
                dot.backgroundTintList = ColorStateList.valueOf(Color.RED)
                Log.w(TAG, message)
            }
        }
    })

    private var frames = 0
    private var resumed = false

    var enabled = false
        set(value) {
            field = value
            sync()
        }

    fun resume() {
        resumed = true
        sync()
    }

    fun pause() {
        resumed = false
        sync()
    }

    /** A recording [uri] was saved, spanning [startNanos]..[endNanos] on the boot clock. */
    fun onClipTimeline(uri: String, startNanos: Long, endNanos: Long) {
        val id = runCatching { ContentUris.parseId(Uri.parse(uri)) }.getOrNull() ?: return
        marker.onClip(ClipRef.Local(id).key, startNanos, endNanos, SystemClock.elapsedRealtimeNanos())
    }

    private fun sync() {
        val wanted = enabled && resumed && micGranted()
        if (wanted && !listener.isRunning) {
            indicator.visibility = View.VISIBLE
            showHeard(false)
            listener.start()
        } else if (!wanted && listener.isRunning) {
            listener.stop()
        }
        if (!enabled) {
            indicator.visibility = View.GONE
        } else if (resumed && !micGranted()) {
            indicator.visibility = View.VISIBLE
            label.text = "Gate · no mic"
            dot.backgroundTintList = ColorStateList.valueOf(Color.RED)
        }
    }

    private fun micGranted() = ContextCompat.checkSelfPermission(
        activity, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun showHeard(heard: Boolean) {
        label.text = if (heard) "Gate" else "Gate · no tone"
        dot.backgroundTintList = ColorStateList.valueOf(if (heard) HEARD else NOT_HEARD)
    }

    private fun flashPass() {
        label.text = "Pass"
        dot.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
        indicator.animate().cancel()
        indicator.scaleX = 1.25f
        indicator.scaleY = 1.25f
        indicator.animate().scaleX(1f).scaleY(1f).setDuration(400).start()
    }

    companion object {
        private const val TAG = "DopplerGate"
        private val HEARD = Color.parseColor("#4CAF50")
        private val NOT_HEARD = Color.parseColor("#888888")
    }
}
