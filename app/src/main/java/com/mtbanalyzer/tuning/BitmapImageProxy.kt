package com.mtbanalyzer.tuning

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * An ImageProxy wrapper that provides a Bitmap for video frame detection.
 *
 * Since ImageProxy.getImage() returns android.media.Image which can't be easily mocked,
 * this class provides an alternative path via BitmapProvider interface.
 *
 * Detectors should check: if (imageProxy is BitmapProvider) use getBitmap()
 */
class BitmapImageProxy(
    private val bitmap: Bitmap,
    private val timestampNs: Long = System.nanoTime(),
    private val rotationDegrees: Int = 0
) : ImageProxy, BitmapProvider {

    private var closed = false

    override fun getBitmap(): Bitmap = bitmap

    override fun close() {
        if (!closed) {
            closed = true
            // Don't recycle bitmap here - let the caller manage it
        }
    }

    override fun getCropRect(): Rect = Rect(0, 0, bitmap.width, bitmap.height)

    override fun setCropRect(rect: Rect?) {
        // No-op for video frames
    }

    override fun getFormat(): Int = android.graphics.ImageFormat.FLEX_RGBA_8888

    override fun getHeight(): Int = bitmap.height

    override fun getWidth(): Int = bitmap.width

    override fun getPlanes(): Array<ImageProxy.PlaneProxy> {
        // Create a plane from the bitmap pixel data
        return arrayOf(BitmapPlaneProxy(bitmap))
    }

    @Suppress("DEPRECATION")
    override fun getImage(): android.media.Image? {
        // Return null - callers should check for BitmapProvider first
        return null
    }

    override fun getImageInfo(): ImageInfo = BitmapImageInfo(timestampNs, rotationDegrees)

    /**
     * PlaneProxy implementation that provides bitmap pixel data
     */
    private class BitmapPlaneProxy(private val bitmap: Bitmap) : ImageProxy.PlaneProxy {

        private val pixelBuffer: ByteBuffer by lazy {
            // Extract grayscale (Y plane equivalent) from bitmap
            val width = bitmap.width
            val height = bitmap.height
            val buffer = ByteBuffer.allocate(width * height)

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pixel = bitmap.getPixel(x, y)
                    // Convert to grayscale (Y plane)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                    buffer.put(gray.toByte())
                }
            }
            buffer.rewind()
            buffer
        }

        override fun getRowStride(): Int = bitmap.width

        override fun getPixelStride(): Int = 1

        override fun getBuffer(): ByteBuffer = pixelBuffer.duplicate()
    }

    /**
     * ImageInfo implementation for bitmap-based frames
     */
    private class BitmapImageInfo(
        private val timestampNs: Long,
        private val rotation: Int
    ) : ImageInfo {
        override fun getTimestamp(): Long = timestampNs
        override fun getRotationDegrees(): Int = rotation
        override fun getSensorToBufferTransformMatrix(): android.graphics.Matrix = android.graphics.Matrix()
        override fun getTagBundle(): androidx.camera.core.impl.TagBundle =
            androidx.camera.core.impl.TagBundle.emptyBundle()
        override fun populateExifData(exifBuilder: androidx.camera.core.ExifInfo.Builder) {
            // No EXIF data available for video frames
        }
    }
}

/**
 * Interface for ImageProxy implementations that can provide a Bitmap directly.
 * Detectors should check for this interface to efficiently process video frames.
 */
interface BitmapProvider {
    fun getBitmap(): Bitmap
}
