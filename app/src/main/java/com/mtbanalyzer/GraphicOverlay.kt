package com.mtbanalyzer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
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
        postInvalidate()
    }
    
    fun setImageSourceInfo(imageWidth: Int, imageHeight: Int) {
        synchronized(lock) {
            this.imageWidth = imageWidth
            this.imageHeight = imageHeight
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
                
                // For fillCenter scale type (which PreviewView uses)
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
            }
            
            for (graphic in graphics) {
                graphic.draw(canvas)
            }
        }
    }
}