package com.mtbanalyzer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.util.Log

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
    private val minScale = 1.0f
    private val maxScale = 10f
    private var currentScale = 1f
    
    // Scale gesture detector
    private val scaleGestureDetector: ScaleGestureDetector
    
    companion object {
        private const val TAG = "ZoomablePlayerContainer"
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
                    constrainMatrix()
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
                    constrainMatrix()
                    invalidate()
                }
            }
        }
        
        return true
    }
    
    private fun constrainMatrix() {
        if (currentScale <= 1.0f) {
            // If not zoomed, reset to center
            matrix.reset()
            currentScale = 1.0f
            return
        }
        
        // Get current matrix values
        val values = FloatArray(9)
        matrix.getValues(values)
        val currentTransX = values[Matrix.MTRANS_X]
        val currentTransY = values[Matrix.MTRANS_Y]
        val currentScaleX = values[Matrix.MSCALE_X]
        val currentScaleY = values[Matrix.MSCALE_Y]
        
        // Calculate the actual dimensions of scaled content
        val scaledWidth = width * currentScaleX
        val scaledHeight = height * currentScaleY
        
        // Calculate how much the content exceeds the view bounds
        val widthDiff = scaledWidth - width
        val heightDiff = scaledHeight - height
        
        var newTransX = currentTransX
        var newTransY = currentTransY
        
        // Only constrain if content is larger than view
        if (widthDiff > 0) {
            // Content wider than view - allow panning within excess area
            newTransX = newTransX.coerceIn(-widthDiff, 0f)
        } else {
            // Content smaller than view - center it
            newTransX = (width - scaledWidth) / 2f
        }
        
        if (heightDiff > 0) {
            // Content taller than view - allow panning within excess area
            newTransY = newTransY.coerceIn(-heightDiff, 0f)
        } else {
            // Content smaller than view - center it
            newTransY = (height - scaledHeight) / 2f
        }
        
        // Apply new translation if changed
        if (newTransX != currentTransX || newTransY != currentTransY) {
            val deltaX = newTransX - currentTransX
            val deltaY = newTransY - currentTransY
            Log.d(TAG, "Constraining: trans($currentTransX,$currentTransY) -> ($newTransX,$newTransY), delta($deltaX,$deltaY)")
            Log.d(TAG, "Scale: $currentScaleX, View: ${width}x${height}, Scaled: ${scaledWidth}x${scaledHeight}")
            matrix.postTranslate(deltaX, deltaY)
        }
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
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        
        // If view size changed (orientation change), reset zoom to prevent issues
        if (oldw != 0 && oldh != 0 && (oldw != w || oldh != h)) {
            // Reset zoom state on orientation change
            reset()
        }
    }
}