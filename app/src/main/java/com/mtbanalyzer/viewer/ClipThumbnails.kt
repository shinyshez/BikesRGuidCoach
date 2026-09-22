package com.mtbanalyzer.viewer

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import android.util.Size
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Semaphore

/**
 * Tile images for the viewer's clip list, cached on disk.
 *
 * Decoding a frame is the only CPU-notable work the server does, and it happens while the
 * recorder may be running pose detection, so generation is capped at two at a time.
 */
class ClipThumbnails(private val context: Context) {

    companion object {
        private const val TAG = "ClipThumbnails"
        private const val MAX_EDGE = 320
        private const val JPEG_QUALITY = 80
    }

    private val cacheDir = File(context.cacheDir, "viewer-thumbs")
    private val gate = Semaphore(2)

    fun jpeg(id: Long): ByteArray? {
        val cached = File(cacheDir, "$id.jpg")
        if (cached.isFile && cached.length() > 0) {
            return try {
                cached.readBytes()
            } catch (e: Exception) {
                Log.w(TAG, "Could not read cached thumbnail for $id", e)
                null
            }
        }

        gate.acquireUninterruptibly()
        try {
            // Another worker may have produced it while we waited on the gate.
            if (cached.isFile && cached.length() > 0) return cached.readBytes()

            val bitmap = decodeFrame(id) ?: return null
            val bytes = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
            bitmap.recycle()

            try {
                cacheDir.mkdirs()
                cached.writeBytes(bytes)
            } catch (e: Exception) {
                Log.w(TAG, "Could not cache thumbnail for $id", e)
            }
            return bytes
        } catch (e: Exception) {
            Log.w(TAG, "Could not build thumbnail for $id", e)
            return null
        } finally {
            gate.release()
        }
    }

    private fun decodeFrame(id: Long): Bitmap? {
        val uri = ContentUrisCompat.videoUri(id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return try {
                context.contentResolver.loadThumbnail(uri, Size(MAX_EDGE, MAX_EDGE), null)
            } catch (e: Exception) {
                Log.w(TAG, "loadThumbnail failed for $id, falling back to a frame grab", e)
                retrieveFirstFrame(id)
            }
        }
        return retrieveFirstFrame(id)
    }

    private fun retrieveFirstFrame(id: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, ContentUrisCompat.videoUri(id))
            retriever.getFrameAtTime(0)?.let(::scaleDown)
        } catch (e: Exception) {
            Log.w(TAG, "Frame grab failed for $id", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Retriever release failed", e)
            }
        }
    }

    private fun scaleDown(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_EDGE) return source
        val scale = MAX_EDGE.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== source) source.recycle()
        return scaled
    }
}

internal object ContentUrisCompat {
    fun videoUri(id: Long) = android.content.ContentUris.withAppendedId(
        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
    )
}
