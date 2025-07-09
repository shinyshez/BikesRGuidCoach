package com.mtbanalyzer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class DrawingOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "DrawingOverlay"
    }

    // Drawing tools
    enum class DrawingTool {
        PEN, ARROW
    }

    // Drawing path data
    data class DrawPath(
        val path: Path,
        val paint: Paint,
        val tool: DrawingTool,
        val timestamp: Long = 0L // Video timestamp in milliseconds
    )

    // Current drawing state
    private var currentTool = DrawingTool.PEN
    private var currentColor = Color.RED
    private var currentStrokeWidth = 8f
    private var isDrawingEnabled = false
    private var currentVideoTimestamp = 0L

    // Drawing paths
    private val allPaths = mutableListOf<DrawPath>()
    private val visiblePaths = mutableListOf<DrawPath>()
    private var currentPath: Path? = null
    private var currentPaint: Paint? = null

    // Touch handling
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var startTouchX = 0f
    private var startTouchY = 0f
    private var isDrawing = false

    init {
        // Enable drawing on this view
        setWillNotDraw(false)
    }

    fun setDrawingEnabled(enabled: Boolean) {
        isDrawingEnabled = enabled
        if (!enabled) {
            currentPath = null
            currentPaint = null
            isDrawing = false
        }
        Log.d(TAG, "Drawing enabled: $enabled")
    }

    fun setCurrentTool(tool: DrawingTool) {
        currentTool = tool
        Log.d(TAG, "Tool changed to: $tool")
    }

    fun setCurrentColor(color: Int) {
        currentColor = color
        Log.d(TAG, "Color changed to: #${Integer.toHexString(color)}")
    }

    fun setCurrentStrokeWidth(width: Float) {
        currentStrokeWidth = width
        Log.d(TAG, "Stroke width changed to: $width")
    }

    fun setCurrentVideoTimestamp(timestamp: Long) {
        currentVideoTimestamp = timestamp
        updateVisiblePaths()
    }

    private fun updateVisiblePaths() {
        visiblePaths.clear()
        // For now, show all paths. Later we can filter by timestamp
        visiblePaths.addAll(allPaths)
        // Use post to ensure we're on the main thread and avoid any layout issues
        post { invalidate() }
    }

    fun clearAllDrawings() {
        allPaths.clear()
        visiblePaths.clear()
        invalidate()
        Log.d(TAG, "All drawings cleared")
    }

    fun undoLastDrawing() {
        if (allPaths.isNotEmpty()) {
            allPaths.removeAt(allPaths.size - 1)
            updateVisiblePaths()
            Log.d(TAG, "Last drawing undone, remaining paths: ${allPaths.size}")
        } else {
            Log.d(TAG, "No drawings to undo")
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDrawingEnabled) {
            return false
        }

        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startDrawing(x, y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDrawing) {
                    continueDrawing(x, y)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isDrawing) {
                    finishDrawing(x, y)
                    return true
                }
            }
        }

        return false
    }

    private fun startDrawing(x: Float, y: Float) {
        startTouchX = x
        startTouchY = y
        lastTouchX = x
        lastTouchY = y
        isDrawing = true

        // Create new path and paint
        currentPath = Path()
        currentPaint = createPaint()

        when (currentTool) {
            DrawingTool.PEN -> {
                currentPath?.moveTo(x, y)
            }
            DrawingTool.ARROW -> {
                // For arrow, we'll draw it on ACTION_UP
            }
        }

        invalidate()
    }

    private fun continueDrawing(x: Float, y: Float) {
        when (currentTool) {
            DrawingTool.PEN -> {
                currentPath?.quadTo(lastTouchX, lastTouchY, (x + lastTouchX) / 2, (y + lastTouchY) / 2)
                invalidate()
            }
            DrawingTool.ARROW -> {
                // For arrow, we redraw preview during move
                invalidate()
            }
        }

        lastTouchX = x
        lastTouchY = y
    }

    private fun finishDrawing(x: Float, y: Float) {
        when (currentTool) {
            DrawingTool.PEN -> {
                currentPath?.lineTo(x, y)
            }
            DrawingTool.ARROW -> {
                drawArrow(currentPath!!, startTouchX, startTouchY, x, y)
            }
        }

        // Save the completed path
        currentPath?.let { path ->
            currentPaint?.let { paint ->
                allPaths.add(DrawPath(path, paint, currentTool, currentVideoTimestamp))
            }
        }

        updateVisiblePaths()
        isDrawing = false
        currentPath = null
        currentPaint = null

        Log.d(TAG, "Drawing finished. Total paths: ${allPaths.size}")
    }

    private fun createPaint(): Paint {
        return Paint().apply {
            color = currentColor
            strokeWidth = currentStrokeWidth
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
            // All tools use the same basic paint settings
        }
    }

    private fun drawArrow(path: Path, startX: Float, startY: Float, endX: Float, endY: Float) {
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)

        // Calculate arrow head
        val arrowLength = 30f
        val arrowAngle = Math.PI / 6 // 30 degrees

        val angle = atan2((endY - startY).toDouble(), (endX - startX).toDouble())
        val arrowX1 = endX - arrowLength * cos(angle - arrowAngle).toFloat()
        val arrowY1 = endY - arrowLength * sin(angle - arrowAngle).toFloat()
        val arrowX2 = endX - arrowLength * cos(angle + arrowAngle).toFloat()
        val arrowY2 = endY - arrowLength * sin(angle + arrowAngle).toFloat()

        path.moveTo(endX, endY)
        path.lineTo(arrowX1, arrowY1)
        path.moveTo(endX, endY)
        path.lineTo(arrowX2, arrowY2)
    }


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw all visible paths
        for (drawPath in visiblePaths) {
            canvas.drawPath(drawPath.path, drawPath.paint)
        }

        // Draw current path being drawn
        if (isDrawing && currentPath != null && currentPaint != null) {
            when (currentTool) {
                DrawingTool.PEN -> {
                    canvas.drawPath(currentPath!!, currentPaint!!)
                }
                DrawingTool.ARROW -> {
                    val previewPaint = createPaint()
                    previewPaint.alpha = 128 // Semi-transparent preview
                    val previewPath = Path()
                    drawArrow(previewPath, startTouchX, startTouchY, lastTouchX, lastTouchY)
                    canvas.drawPath(previewPath, previewPaint)
                }
            }
        }
    }

    // Methods for saving/loading drawings (to be implemented later)
    fun exportDrawings(): List<DrawPath> {
        return allPaths.toList()
    }

    fun importDrawings(paths: List<DrawPath>) {
        allPaths.clear()
        allPaths.addAll(paths)
        updateVisiblePaths()
    }
}