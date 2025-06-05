package com.mtbanalyzer

import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService

class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val cameraExecutor: ExecutorService
) {
    companion object {
        private const val TAG = "CameraManager"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    var videoCapture: VideoCapture<Recorder>? = null
        private set
    private var imageAnalyzer: ImageAnalysis? = null

    interface CameraCallback {
        fun onCameraReady()
        fun onCameraError(error: String)
    }

    fun startCamera(
        previewView: PreviewView,
        graphicOverlay: GraphicOverlay,
        analyzer: ImageAnalysis.Analyzer,
        callback: CameraCallback
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(previewView, graphicOverlay, analyzer, callback)
            } catch (exc: Exception) {
                Log.e(TAG, "Camera initialization failed", exc)
                callback.onCameraError("Camera initialization failed: ${exc.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases(
        previewView: PreviewView,
        graphicOverlay: GraphicOverlay,
        analyzer: ImageAnalysis.Analyzer,
        callback: CameraCallback
    ) {
        val cameraProvider = this.cameraProvider ?: run {
            callback.onCameraError("Camera provider is null")
            return
        }

        // Preview
        preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        // Setup overlay after preview starts
        previewView.post {
            graphicOverlay.setCameraInfo(
                previewView.width,
                previewView.height,
                GraphicOverlay.CameraFacing.BACK
            )
            
            // Debug: Get actual preview resolution
            preview?.resolutionInfo?.let { info ->
                Log.d(TAG, "Preview resolution: ${info.resolution.width}x${info.resolution.height}, rotation: ${info.rotationDegrees}")
                
                // Calculate where the preview actually appears within the view
                val previewWidth = info.resolution.width
                val previewHeight = info.resolution.height
                val viewWidth = previewView.width
                val viewHeight = previewView.height
                
                // Calculate fitCenter scaling
                val previewAspectRatio = previewWidth.toFloat() / previewHeight.toFloat()
                val viewAspectRatio = viewWidth.toFloat() / viewHeight.toFloat()
                
                val scaleFactor = if (viewAspectRatio > previewAspectRatio) {
                    // View is wider than preview, scale by height
                    viewHeight.toFloat() / previewHeight.toFloat()
                } else {
                    // View is taller than preview, scale by width
                    viewWidth.toFloat() / previewWidth.toFloat()
                }
                
                val scaledPreviewWidth = previewWidth * scaleFactor
                val scaledPreviewHeight = previewHeight * scaleFactor
                val previewLeft = (viewWidth - scaledPreviewWidth) / 2
                val previewTop = (viewHeight - scaledPreviewHeight) / 2
                
                Log.d(TAG, "Calculated preview bounds: (${previewLeft}, ${previewTop}) size: ${scaledPreviewWidth}x${scaledPreviewHeight}")
                Log.d(TAG, "Scale factor: $scaleFactor, view: ${viewWidth}x${viewHeight}")
                Log.d(TAG, "Preview aspect ratio: $previewAspectRatio")
                
                // Set the actual preview resolution for correct aspect ratio calculation
                if (info.rotationDegrees == 90 || info.rotationDegrees == 270) {
                    // Rotated - swap dimensions
                    graphicOverlay.setImageSourceInfo(previewHeight, previewWidth)
                } else {
                    graphicOverlay.setImageSourceInfo(previewWidth, previewHeight)
                }
                
                // Set these as the actual preview bounds
                graphicOverlay.setActualPreviewBounds(
                    previewLeft, 
                    previewTop, 
                    previewLeft + scaledPreviewWidth, 
                    previewTop + scaledPreviewHeight
                )
            }
            
            // Debug: Get PreviewView location and dimensions
            val location = IntArray(2)
            previewView.getLocationOnScreen(location)
            Log.d(TAG, "PreviewView location on screen: (${location[0]}, ${location[1]})")
            Log.d(TAG, "PreviewView dimensions: ${previewView.width}x${previewView.height}")
            
        }

        // Video capture with fallback strategy
        val qualitySelector = QualitySelector.fromOrderedList(
            listOf(Quality.HD, Quality.SD, Quality.LOWEST),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.LOWEST)
        )

        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        // Image analysis for pose detection
        imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, analyzer)
            }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            // Check if lifecycle is still valid
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.CREATED)) {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, 
                    cameraSelector, 
                    preview, 
                    videoCapture, 
                    imageAnalyzer
                )
                Log.d(TAG, "Camera bound successfully")
                callback.onCameraReady()
            } else {
                Log.w(TAG, "Lifecycle not ready for camera binding")
                callback.onCameraError("Lifecycle not ready for camera binding")
            }
        } catch (exc: Exception) {
            Log.e(TAG, "Camera binding failed", exc)
            callback.onCameraError("Camera binding failed: ${exc.message}")
        }
    }

    fun release() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        preview = null
        videoCapture = null
        imageAnalyzer = null
        Log.d(TAG, "Camera resources released")
    }
}