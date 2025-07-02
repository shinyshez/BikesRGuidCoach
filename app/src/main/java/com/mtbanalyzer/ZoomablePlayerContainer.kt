package com.mtbanalyzer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout

/**
 * A container that wraps PlayerView and provides zoom functionality.
 * Uses the exact same Matrix approach as the working ZoomableImageView.
 */
class ZoomablePlayerContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val matrix = Matrix()
    private val savedMatrix = Matrix()
    
    // Touch modes
    private var mode = NONE
    private val start = PointF()
    private val mid = PointF()
    private var oldDist = 1f
    
    // Zoom limits
    private val minScale = 0.5f
    private val maxScale = 10f
    private var currentScale = 1f
    
    // Scale gesture detector
    private val scaleGestureDetector: ScaleGestureDetector
    
    companion object {
        const val NONE = 0
        const val DRAG = 1
        const val ZOOM = 2
    }
    
    init {
        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val focusX = detector.focusX
                val focusY = detector.focusY
                
                val newScale = currentScale * scaleFactor
                
                if (newScale in minScale..maxScale) {
                    matrix.postScale(scaleFactor, scaleFactor, focusX, focusY)
                    currentScale = newScale
                    invalidate()
                }
                
                return true
            }
        })
        
        // This is important for custom drawing
        setWillNotDraw(false)
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        
        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(matrix)
                start.set(event.x, event.y)
                mode = DRAG
            }
            
            MotionEvent.ACTION_POINTER_DOWN -> {
                oldDist = spacing(event)
                if (oldDist > 10f) {
                    savedMatrix.set(matrix)
                    midPoint(mid, event)
                    mode = ZOOM
                }
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
            }
            
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG && !scaleGestureDetector.isInProgress) {
                    matrix.set(savedMatrix)
                    val dx = event.x - start.x
                    val dy = event.y - start.y
                    matrix.postTranslate(dx, dy)
                    invalidate()
                }
            }
        }
        
        return true
    }
    
    private fun spacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(x * x + y * y)
    }
    
    private fun midPoint(point: PointF, event: MotionEvent) {
        val x = event.getX(0) + event.getX(1)
        val y = event.getY(0) + event.getY(1)
        point.set(x / 2, y / 2)
    }
    
    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.concat(matrix)
        super.dispatchDraw(canvas)
        canvas.restore()
    }
    
    fun reset() {
        matrix.reset()
        currentScale = 1f
        invalidate()
    }
    
    fun isZoomed(): Boolean = currentScale > 1.0f
    
    fun getCurrentScale(): Float = currentScale
    
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Don't intercept - we'll handle events passed from VideoPlayerView
        return false
    }
}