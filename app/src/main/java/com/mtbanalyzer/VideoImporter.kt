package com.mtbanalyzer

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Copies a video the user picked (Photo Picker, share sheet, file manager) into the app's
 * MediaStore collection, Movies/MTBAnalyzer, under an `MTB_` display name so the gallery
 * lists it alongside recordings. The copy is owned by this install, so it can be played,
 * compared and deleted without any further permission.
 */
class VideoImporter(private val context: Context) {

    companion object {
        private const val TAG = "VideoImporter"
        private const val RELATIVE_PATH = "Movies/MTBAnalyzer"
        const val PREFIX = "MTB_"

        /**
         * Gallery display name for an imported file: `MTB_` + the original name, without
         * doubling the prefix, with characters MediaStore rejects replaced, and with a
         * `.mp4` extension if the source had none. Falls back to a timestamped name when
         * there is no usable name — the Photo Picker, for one, reports only its numeric id.
         */
        fun importDisplayName(originalName: String?, now: Date = Date()): String {
            val cleaned = originalName
                ?.trim()
                ?.removePrefix(PREFIX)
                ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                ?.takeIf { it.isNotBlank() && !it.substringBeforeLast('.').all(Char::isDigit) }
                ?: "import_" + SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(now) + ".mp4"
            val withExtension = if (cleaned.contains('.')) cleaned else "$cleaned.mp4"
            return PREFIX + withExtension
        }
    }

    /** Copies [source] into Movies/MTBAnalyzer; returns the new MediaStore URI, or null on failure. */
    suspend fun import(source: Uri): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = importDisplayName(queryDisplayName(resolver, source))
        val mimeType = resolver.getType(source)?.takeIf { it.startsWith("video/") } ?: "video/mp4"
        Log.d(TAG, "Importing $source as $displayName ($mimeType)")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                importScoped(resolver, source, displayName, mimeType)
            } else {
                importLegacy(resolver, source, displayName, mimeType)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Import failed for $source", e)
            null
        }
    }

    private fun importScoped(resolver: ContentResolver, source: Uri, displayName: String, mimeType: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            copy(resolver, source, target)
        } catch (e: Exception) {
            resolver.delete(target, null, null)
            throw e
        }
        resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return target
    }

    @Suppress("DEPRECATION")
    private fun importLegacy(resolver: ContentResolver, source: Uri, displayName: String, mimeType: String): Uri? {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "MTBAnalyzer")
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, displayName)
        resolver.openInputStream(source)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.DATA, file.absolutePath)
        }
        return resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    }

    private fun copy(resolver: ContentResolver, source: Uri, target: Uri) {
        val input = resolver.openInputStream(source) ?: throw IllegalStateException("Cannot open $source")
        val output = resolver.openOutputStream(target) ?: throw IllegalStateException("Cannot open $target")
        input.use { i -> output.use { o -> i.copyTo(o) } }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.lastPathSegment
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read display name for $uri", e)
            null
        }
    }
}
