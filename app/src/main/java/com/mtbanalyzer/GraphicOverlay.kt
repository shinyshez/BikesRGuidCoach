package com.mtbanalyzer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.View
import java.util.ArrayList

class GraphicOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val lock = Object()
    private var previewWidth: Int = 0
    private var widthScaleFactor = 1.0f
    private var previewHeight: Int = 0
    private var heightScaleFactor = 1.0f
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0
    private var facing = CameraFacing.BACK
    private val graphics = ArrayList<Graphic>()
    private var cameraAspectRatio: Float = 16f / 9f // Default fallback, will be updated with actual camera ratio
    private var showPreviewBorder: Boolean = false // Flag to control border display
    
    // Actual preview bounds within the overlay
    private var actualPreviewLeft: Float = 0f
    private var actualPreviewTop: Float = 0f
    private var actualPreviewRight: Float = 0f
    private var actualPreviewBottom: Float = 0f
    
    enum class CameraFacing {
        BACK, FRONT
    }
    
    abstract class Graphic(private val overlay: GraphicOverlay) {
        abstract fun draw(canvas: Canvas)
        
        fun scale(pixel: Float): Float {
            return pixel * overlay.widthScaleFactor
        }
        
        fun translateX(x: Float): Float {
            val scaledX = x * overlay.widthScaleFactor
            val offsetX = (overlay.width - (overlay.imageWidth * overlay.widthScaleFactor)) / 2
            
            return if (overlay.facing == CameraFacing.FRONT) {
                overlay.width - scaledX - offsetX
            } else {
                scaledX + offsetX
            }
        }
        
        fun translateY(y: Float): Float {
            val scaledY = y * overlay.heightScaleFactor
            val offsetY = (overlay.height - (overlay.imageHeight * overlay.heightScaleFactor)) / 2
            return scaledY + offsetY
        }
        
        fun postInvalidate() {
            overlay.postInvalidate()
        }
        
        fun getOverlayWidth(): Int = overlay.width
        fun getOverlayHeight(): Int = overlay.height
        fun getOverlayImageWidth(): Int = overlay.imageWidth
        fun getOverlayImageHeight(): Int = overlay.imageHeight
        fun getActualPreviewBounds(): FloatArray = floatArrayOf(
            overlay.actualPreviewLeft,
            overlay.actualPreviewTop,
            overlay.actualPreviewRight,
            overlay.actualPreviewBottom
        )
        
        fun getCameraAspectRatio(): Float = overlay.cameraAspectRatio
    }
    
    fun clear() {
        synchronized(lock) {
            graphics.clear()
        }
        postInvalidate()
    }
    
    fun add(graphic: Graphic) {
        synchronized(lock) {
            graphics.add(graphic)
        }
        postInvalidate()
    }
    
    fun remove(graphic: Graphic) {
        synchronized(lock) {
            graphics.remove(graphic)
        }
        postInvalidate()
    }
    
    fun setCameraInfo(previewWidth: Int, previewHeight: Int, facing: CameraFacing) {
        synchronized(lock) {
            this.previewWidth = previewWidth
            this.previewHeight = previewHeight
            this.facing = facing
        }
        Log.d("GraphicOverlay", "setCameraInfo - previewSize: ${previewWidth}x${previewHeight}")
        postInvalidate()
    }
    
    // New method to set the actual camera preview bounds
    fun setActualPreviewBounds(left: Float, top: Float, right: Float, bottom: Float) {
        synchronized(lock) {
            actualPreviewLeft = left
            actualPreviewTop = top
            actualPreviewRight = right
            actualPreviewBottom = bottom
        }
        Log.d("GraphicOverlay", "setActualPreviewBounds - bounds: ($left, $top) to ($right, $bottom)")
        postInvalidate()
    }
    
    fun setImageSourceInfo(imageWidth: Int, imageHeight: Int) {
        synchronized(lock) {
            this.imageWidth = imageWidth
            this.imageHeight = imageHeight
            // Calculate actual camera aspect ratio from image dimensions
            this.cameraAspectRatio = imageWidth.toFloat() / imageHeight.toFloat()
        }
        Log.d("GraphicOverlay", "setImageSourceInfo - imageSize: ${imageWidth}x${imageHeight}, aspect ratio: $cameraAspectRatio")
        postInvalidate()
    }
    
    fun setShowPreviewBorder(show: Boolean) {
        synchronized(lock) {
            this.showPreviewBorder = show
        }
        postInvalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        synchronized(lock) {
            if (imageWidth != 0 && imageHeight != 0) {
                // Calculate scale factors based on how the image fills the view
                val viewAspectRatio = width.toFloat() / height.toFloat()
                val imageAspectRatio = imageWidth.toFloat() / imageHeight.toFloat()
                
                // For fitCenter scale type (which PreviewView uses)
                // Scale uniformly to fit the entire image within the view
                if (viewAspectRatio > imageAspectRatio) {
                    // View is wider than image, scale by height
                    heightScaleFactor = height.toFloat() / imageHeight.toFloat()
                    widthScaleFactor = heightScaleFactor
                } else {
                    // View is taller than image, scale by width
                    widthScaleFactor = width.toFloat() / imageWidth.toFloat()
                    heightScaleFactor = widthScaleFactor
                }
                
                Log.d("GraphicOverlay", "View size: ${width}x${height}, Image size: ${imageWidth}x${imageHeight}")
                Log.d("GraphicOverlay", "Scale factors: width=$widthScaleFactor, height=$heightScaleFactor")
                
                // Draw a white border around the camera preview area only if enabled
                if (showPreviewBorder) {
                    drawPreviewBorder(canvas)
                }
            }
            
            for (graphic in graphics) {
                graphic.draw(canvas)
            }
        }
    }
    
    private fun drawPreviewBorder(canvas: Canvas) {
        val borderPaint = Paint().apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 10f
        }
        
        // Use the actual camera aspect ratio detected from image dimensions
        // Falls back to 16:9 if not yet determined
        
        // Calculate preview size assuming 16:9 camera with fitCenter scaling
        val viewAspectRatio = width.toFloat() / height.toFloat()
        
        val previewWidth: Float
        val previewHeight: Float
        val previewLeft: Float
        val previewTop: Float
        
        if (viewAspectRatio > cameraAspectRatio) {
            // View is wider than camera - preview is constrained by height
            previewHeight = height.toFloat()
            previewWidth = previewHeight * cameraAspectRatio
            previewLeft = (width - previewWidth) / 2
            previewTop = 0f
        } else {
            // View is taller than camera - preview is constrained by width
            previewWidth = width.toFloat()
            previewHeight = previewWidth / cameraAspectRatio
            previewLeft = 0f
            previewTop = (height - previewHeight) / 2
        }
        
        // Draw border around the calculated preview area
        canvas.drawRect(
            previewLeft,
            previewTop,
            previewLeft + previewWidth,
            previewTop + previewHeight,
            borderPaint
        )
        
        Log.d("GraphicOverlay", "Camera preview border (${cameraAspectRatio} aspect) at: (${previewLeft}, ${previewTop}) size: ${previewWidth}x${previewHeight}")
    }
}