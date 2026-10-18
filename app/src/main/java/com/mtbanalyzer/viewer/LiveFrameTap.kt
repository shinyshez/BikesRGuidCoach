package com.mtbanalyzer.viewer

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Wraps the detector's analyzer and lends the viewer link a few frames a second.
 *
 * The camera is already at its limit of three use cases (preview, recording, analysis), so
 * the live view rides on the analysis frames instead of opening a stream of its own. Only
 * the plane copy happens on the camera thread, about a millisecond at 640x480, and only
 * when [LiveFrameHub] has a viewer waiting; rotation and JPEG run on a thread of their own.
 * The delegate always sees the frame, and it still owns closing it.
 */
class LiveFrameTap(
    private val delegate: ImageAnalysis.Analyzer,
    private val hub: LiveFrameHub = LiveFrameHub.shared
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "LiveFrameTap"
        private const val JPEG_QUALITY = 60

        private val encoder: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "live-preview-jpeg").apply { isDaemon = true }
        }
    }

    override fun analyze(image: ImageProxy) {
        if (hub.onCameraFrame()) capture(image)
        delegate.analyze(image)
    }

    private fun capture(image: ImageProxy) {
        val frame = try {
            if (image.format != ImageFormat.YUV_420_888) {
                hub.abandon()
                return
            }
            val planes = image.planes
            YuvFrames.toNv21(
                planes[0].buffer, planes[1].buffer, planes[2].buffer,
                image.width, image.height,
                planes[0].rowStride, planes[1].rowStride, planes[1].pixelStride
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy frame for live preview", e)
            hub.abandon()
            return
        }

        val rotation = image.imageInfo.rotationDegrees
        try {
            encoder.execute { encode(frame, rotation) }
        } catch (e: Exception) {
            hub.abandon()
        }
    }

    private fun encode(raw: Nv21Frame, rotation: Int) {
        try {
            val frame = YuvFrames.rotate(raw, rotation)
            val out = ByteArrayOutputStream(48 * 1024)
            YuvImage(frame.data, ImageFormat.NV21, frame.width, frame.height, null)
                .compressToJpeg(Rect(0, 0, frame.width, frame.height), JPEG_QUALITY, out)
            hub.publish(out.toByteArray(), frame.width, frame.height)
        } catch (e: Exception) {
            Log.w(TAG, "Could not encode live preview frame", e)
            hub.abandon()
        }
    }
}
