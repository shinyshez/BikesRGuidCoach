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

        /**
         * The name to try on the [attempt]th go (1-based): the name itself, then
         * `MTB_ride (2).mp4`, `MTB_ride (3).mp4`… The number goes before the extension so
         * the file still opens as what it is.
         */
        fun numberedName(displayName: String, attempt: Int): String {
            if (attempt <= 1) return displayName
            val dot = displayName.lastIndexOf('.')
            return if (dot > 0) {
                displayName.substring(0, dot) + " ($attempt)" + displayName.substring(dot)
            } else {
                "$displayName ($attempt)"
            }
        }

        /**
         * Enough for re-importing the same clip a few times; past that something else is
         * wrong and failing is the right answer.
         */
        private const val MAX_NAME_ATTEMPTS = 20
    }

    /**
     * Copies [source] into Movies/MTBAnalyzer; returns the new MediaStore URI, or null on failure.
     * [originalName] overrides the name read from [source], for a copy whose file name means
     * nothing (a recorder clip cached as `<id>.mp4`).
     */
    suspend fun import(source: Uri, originalName: String? = null): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = importDisplayName(originalName ?: queryDisplayName(resolver, source))
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

    /**
     * Android 11+ gives a clashing insert a unique name by itself; Android 10 refuses it
     * outright (UNIQUE constraint on `_data`) and insert() returns null. A clash is ordinary:
     * the same clip imported or saved from a recorder twice, or a file a previous install
     * left behind (uninstalling does not delete it, and without the read permission this
     * install cannot even see it). So on a refusal, try the next numbered name.
     */
    private fun importScoped(resolver: ContentResolver, source: Uri, displayName: String, mimeType: String): Uri? {
        var target: Uri? = null
        for (attempt in 1..MAX_NAME_ATTEMPTS) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, numberedName(displayName, attempt))
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            target = try {
                resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            } catch (e: IllegalStateException) {
                null // some builds throw on the clash instead of returning null
            }
            if (target != null) break
            Log.w(TAG, "Insert refused for ${numberedName(displayName, attempt)}; trying the next name")
        }
        if (target == null) return null
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
        // Writing to a name that exists would overwrite someone else's clip.
        val file = (1..MAX_NAME_ATTEMPTS).asSequence()
            .map { File(dir, numberedName(displayName, it)) }
            .firstOrNull { !it.exists() }
            ?: return null
        resolver.openInputStream(source)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
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
