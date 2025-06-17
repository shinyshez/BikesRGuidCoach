package com.mtbanalyzer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

class ScrubOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    private val backgroundPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00000000") // Fully transparent - don't dim the video
    }
    
    private val scrubBarPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFFFFF")
        strokeCap = Paint.Cap.ROUND
    }
    
    private val scrubIndicatorPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF4444") // Red indicator
        strokeCap = Paint.Cap.ROUND
    }
    
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 48f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }
    
    private val timePaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    
    private var scrubProgress = 0.5f // 0.0 to 1.0
    private var timeText = "0:00"
    private var totalTimeText = "0:00"
    private var isActive = false
    private var scrubBarHeight = 8f
    private var scrubIndicatorRadius = 16f
    
    init {
        // Scale dimensions with display density
        val density = context.resources.displayMetrics.density
        scrubBarHeight *= density
        scrubIndicatorRadius *= density
        textPaint.textSize *= density
        timePaint.textSize *= density
    }
    
    fun setActive(active: Boolean) {
        isActive = active
        visibility = if (active) VISIBLE else GONE
    }
    
    fun updateScrubPosition(progress: Float) {
        scrubProgress = progress.coerceIn(0f, 1f)
        invalidate()
    }
    
    fun updateTimeDisplay(currentTime: String, totalTime: String) {
        timeText = currentTime
        totalTimeText = totalTime
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (!isActive) return
        
        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2f
        
        // Draw semi-transparent background
        canvas.drawRect(0f, 0f, width, height, backgroundPaint)
        
        // Draw scrub bar
        val barMargin = width * 0.1f
        val barWidth = width - (2 * barMargin)
        val barY = centerY
        
        // Draw scrub bar background
        canvas.drawRoundRect(
            barMargin, 
            barY - scrubBarHeight / 2f,
            width - barMargin,
            barY + scrubBarHeight / 2f,
            scrubBarHeight / 2f,
            scrubBarHeight / 2f,
            scrubBarPaint.apply { alpha = 100 }
        )
        
        // Draw scrub bar progress
        val progressX = barMargin + (barWidth * scrubProgress)
        canvas.drawRoundRect(
            barMargin,
            barY - scrubBarHeight / 2f,
            progressX,
            barY + scrubBarHeight / 2f,
            scrubBarHeight / 2f,
            scrubBarHeight / 2f,
            scrubBarPaint.apply { alpha = 255 }
        )
        
        // Draw scrub indicator
        canvas.drawCircle(progressX, barY, scrubIndicatorRadius, scrubIndicatorPaint)
        
        // No instruction text - removed for cleaner UI
        
        // Draw time display
        val timeY = barY + scrubIndicatorRadius + 60f * context.resources.displayMetrics.density
        canvas.drawText("$timeText / $totalTimeText", width / 2f, timeY, timePaint)
    }
}