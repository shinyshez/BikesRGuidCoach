package com.mtbanalyzer.doppler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.widget.SeekBar

/**
 * Ticks over a [SeekBar] where the rider passed the timing gate. Sits on top of the seek bar
 * at the same size and lines up with its track (the bar's padding is the thumb's travel).
 */
class PassMarksView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var seekBar: SeekBar? = null
    private var marksMs: List<Int> = emptyList()

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        strokeWidth = 3 * density
        strokeCap = Paint.Cap.ROUND
    }

    fun bind(seekBar: SeekBar) {
        this.seekBar = seekBar
        invalidate()
    }

    fun setMarks(marksMs: List<Int>) {
        this.marksMs = marksMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bar = seekBar ?: return
        val max = bar.max
        if (max <= 0 || marksMs.isEmpty()) return
        val left = bar.paddingLeft.toFloat()
        val right = (width - bar.paddingRight).toFloat()
        val mid = height / 2f
        val half = 7 * density
        for (ms in marksMs) {
            if (ms > max) continue
            val x = left + (right - left) * ms / max
            canvas.drawLine(x, mid - half, x, mid + half, paint)
        }
    }
}
