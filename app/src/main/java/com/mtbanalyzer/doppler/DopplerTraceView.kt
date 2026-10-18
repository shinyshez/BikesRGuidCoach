package com.mtbanalyzer.doppler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Scrolling plot of the Doppler shift over the last few seconds, so the S-curve of a pass is
 * visible as it happens. Frames arrive on the audio thread; drawing happens on the UI thread.
 */
class DopplerTraceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val spanSec = 4.0
    private val lock = Any()
    private val frames = ArrayDeque<DopplerGateDetector.Frame>()
    private val passes = ArrayDeque<Double>()
    private var nowSec = 0.0

    /** Vertical half-range in Hz; grows to fit a fast pass and stays there. */
    private var rangeHz = 100.0
    var thresholdHz = 25.0

    private val density = resources.displayMetrics.density
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * density
    }
    private val axisPaint = Paint().apply { color = 0x66FFFFFF; strokeWidth = density }
    private val thresholdPaint = Paint().apply {
        color = 0x55FFC107; strokeWidth = density
        pathEffect = DashPathEffect(floatArrayOf(6 * density, 6 * density), 0f)
    }
    private val passPaint = Paint().apply { color = 0xFF4CAF50.toInt(); strokeWidth = 2 * density }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt(); textSize = 11 * density
    }
    private val path = Path()

    fun addFrame(frame: DopplerGateDetector.Frame) {
        synchronized(lock) {
            frames.addLast(frame)
            nowSec = frame.timeSec
            while (frames.first().timeSec < nowSec - spanSec) frames.removeFirst()
            if (frame.heard && kotlin.math.abs(frame.shiftHz) > rangeHz * 0.9) {
                rangeHz = kotlin.math.abs(frame.shiftHz) / 0.9
            }
        }
        postInvalidateOnAnimation()
    }

    fun addPass(timeSec: Double) {
        synchronized(lock) { passes.addLast(timeSec) }
        postInvalidateOnAnimation()
    }

    fun clear() {
        synchronized(lock) { frames.clear(); passes.clear(); rangeHz = 100.0; nowSec = 0.0 }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val mid = h / 2
        synchronized(lock) {
            val start = nowSec - spanSec
            fun x(t: Double) = ((t - start) / spanSec * w).toFloat()
            fun y(hz: Double) = (mid - hz / rangeHz * mid).toFloat()

            canvas.drawLine(0f, mid, w, mid, axisPaint)
            canvas.drawLine(0f, y(thresholdHz), w, y(thresholdHz), thresholdPaint)
            canvas.drawLine(0f, y(-thresholdHz), w, y(-thresholdHz), thresholdPaint)
            canvas.drawText("+%.0f Hz".format(rangeHz), 4 * density, 12 * density, labelPaint)
            canvas.drawText("−%.0f Hz".format(rangeHz), 4 * density, h - 4 * density, labelPaint)

            while (passes.isNotEmpty() && passes.first() < start) passes.removeFirst()
            for (p in passes) canvas.drawLine(x(p), 0f, x(p), h, passPaint)

            // Unheard frames break the line rather than drawing a spike to zero.
            path.reset()
            var penDown = false
            for (f in frames) {
                if (!f.heard) { penDown = false; continue }
                if (penDown) path.lineTo(x(f.timeSec), y(f.shiftHz))
                else path.moveTo(x(f.timeSec), y(f.shiftHz))
                penDown = true
            }
            canvas.drawPath(path, tracePaint)
        }
    }
}
