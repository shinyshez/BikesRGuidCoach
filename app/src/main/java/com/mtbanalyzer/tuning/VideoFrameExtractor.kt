package com.mtbanalyzer.tuning

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts frames from video files with LRU caching for efficient playback.
 */
class VideoFrameExtractor(private val context: Context) {

    companion object {
        private const val TAG = "VideoFrameExtractor"
        private const val DEFAULT_CACHE_SIZE = 50 // Number of frames to cache
    }

    private var retriever: MediaMetadataRetriever? = null
    private var currentUri: Uri? = null

    // LRU cache for extracted frames
    private val frameCache = object : LruCache<Long, Bitmap>(DEFAULT_CACHE_SIZE) {
        override fun sizeOf(key: Long, bitmap: Bitmap): Int = 1

        override fun entryRemoved(evicted: Boolean, key: Long, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted && oldValue != newValue) {
                oldValue.recycle()
            }
        }
    }

    // Video metadata
    var durationMs: Long = 0
        private set
    var frameRate: Float = 30f
        private set
    var width: Int = 0
        private set
    var height: Int = 0
        private set
    var totalFrames: Int = 0
        private set

    /**
     * Set the video source and extract metadata
     */
    suspend fun setVideoSource(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            // Clear cache if switching videos
            if (currentUri != uri) {
                clearCache()
                currentUri = uri
            }

            retriever?.release()
            retriever = MediaMetadataRetriever().apply {
                setDataSource(context, uri)
            }

            extractMetadata()
            Log.d(TAG, "Video loaded: ${width}x${height}, ${durationMs}ms, ${frameRate}fps, ~$totalFrames frames")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting video source", e)
            throw e
        }
    }

    private fun extractMetadata() {
        retriever?.let { r ->
            durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0

            width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0

            height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0

            // Try to get frame rate (may not be available on all videos)
            val frameRateStr = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            frameRate = frameRateStr?.toFloatOrNull() ?: 30f

            totalFrames = if (durationMs > 0 && frameRate > 0) {
                ((durationMs / 1000.0) * frameRate).toInt()
            } else {
                0
            }
        }
    }

    /**
     * Extract a frame at the specified timestamp
     *
     * @param timestampMs Timestamp in milliseconds
     * @param useCache Whether to use/populate cache (default true)
     * @return Bitmap of the frame, or null if extraction fails
     */
    suspend fun extractFrame(timestampMs: Long, useCache: Boolean = true): Bitmap? = withContext(Dispatchers.IO) {
        // Round to nearest frame boundary for better caching
        val frameMs = roundToFrameBoundary(timestampMs)

        // Check cache first
        if (useCache) {
            frameCache.get(frameMs)?.let { cached ->
                Log.v(TAG, "Cache hit for frame at ${frameMs}ms")
                return@withContext cached
            }
        }

        try {
            val bitmap = retriever?.getFrameAtTime(
                frameMs * 1000, // Convert to microseconds
                MediaMetadataRetriever.OPTION_CLOSEST
            )

            if (bitmap != null && useCache) {
                // Make a copy for the cache since getFrameAtTime may return reused bitmaps
                val cachedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                frameCache.put(frameMs, cachedBitmap)
                Log.v(TAG, "Cached frame at ${frameMs}ms")
                return@withContext cachedBitmap
            }

            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting frame at ${timestampMs}ms", e)
            null
        }
    }

    /**
     * Extract a frame as BitmapImageProxy for use with detectors
     */
    suspend fun extractFrameAsImageProxy(timestampMs: Long): BitmapImageProxy? {
        val bitmap = extractFrame(timestampMs) ?: return null
        return BitmapImageProxy(bitmap, timestampMs * 1_000_000) // Convert to nanoseconds
    }

    /**
     * Pre-load frames around a timestamp for smoother playback
     */
    suspend fun preloadFrames(centerMs: Long, radiusMs: Long = 2000, intervalMs: Long = 100) = withContext(Dispatchers.IO) {
        val startMs = maxOf(0, centerMs - radiusMs)
        val endMs = minOf(durationMs, centerMs + radiusMs)

        var currentMs = startMs
        while (currentMs <= endMs) {
            if (frameCache.get(roundToFrameBoundary(currentMs)) == null) {
                extractFrame(currentMs, useCache = true)
            }
            currentMs += intervalMs
        }
    }

    /**
     * Round timestamp to nearest frame boundary for consistent caching
     */
    private fun roundToFrameBoundary(timestampMs: Long): Long {
        if (frameRate <= 0) return timestampMs
        val frameDurationMs = (1000.0 / frameRate).toLong()
        return (timestampMs / frameDurationMs) * frameDurationMs
    }

    /**
     * Get timestamp for a specific frame index
     */
    fun frameIndexToTimestamp(frameIndex: Int): Long {
        if (frameRate <= 0) return 0
        return ((frameIndex * 1000.0) / frameRate).toLong()
    }

    /**
     * Get frame index for a specific timestamp
     */
    fun timestampToFrameIndex(timestampMs: Long): Int {
        if (frameRate <= 0) return 0
        return ((timestampMs / 1000.0) * frameRate).toInt()
    }

    /**
     * Clear the frame cache
     */
    fun clearCache() {
        frameCache.evictAll()
    }

    /**
     * Release resources
     */
    fun release() {
        clearCache()
        retriever?.release()
        retriever = null
        currentUri = null
    }
}
