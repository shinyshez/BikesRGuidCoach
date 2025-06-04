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