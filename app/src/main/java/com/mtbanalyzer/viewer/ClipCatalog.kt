package com.mtbanalyzer.viewer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.mtbanalyzer.MediaPermissions

/** One clip as the viewer API describes it. */
data class ClipDto(
    val id: Long,
    val name: String,
    /** Epoch seconds, matching MediaStore's DATE_ADDED. */
    val dateAdded: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val kind: String
) {
    fun toJson(): String = buildString {
        append("{\"id\":").append(id)
        append(",\"name\":").append(Json.string(name))
        append(",\"dateAdded\":").append(dateAdded)
        append(",\"durationMs\":").append(durationMs)
        append(",\"sizeBytes\":").append(sizeBytes)
        append(",\"kind\":").append(Json.string(kind))
        append('}')
    }
}

/**
 * Recordings are named MTB_yyyy-MM-dd-HH-mm-ss-SSS.mp4; anything else under the MTB_
 * prefix arrived through import. Same rule the gallery uses for its `import` tag.
 */
object ClipNaming {

    private val RECORDING = Regex("^MTB_\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{3}\\.mp4$", RegexOption.IGNORE_CASE)

    const val KIND_RECORDING = "recording"
    const val KIND_IMPORT = "import"

    fun kindOf(displayName: String): String =
        if (RECORDING.matches(displayName)) KIND_RECORDING else KIND_IMPORT
}

/**
 * The clip list the viewer sees.
 *
 * Runs the same query as the gallery (VideoGalleryActivity.loadVideos) rather than sharing
 * its code, so a change made for the viewer cannot regress the gallery. Phase 2 collapses
 * the two once the native viewer mode needs one model for both.
 */
class ClipCatalog(private val context: Context) {

    companion object {
        private const val TAG = "ClipCatalog"

        private val PROJECTION = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE
        )
    }

    fun uriFor(id: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

    /**
     * True when this install can also see clips it does not own (previous installs, other
     * apps). Reported to the viewer so it can explain a short list rather than look broken.
     */
    fun hasFullMediaAccess(): Boolean = MediaPermissions.hasReadVideoPermission(context)

    fun list(sinceEpochSeconds: Long? = null): List<ClipDto> {
        val where = StringBuilder("${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?")
        val args = mutableListOf("MTB_%")

        // A recording still being written is visible but truncated; serving it hands the
        // viewer a broken file.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            where.append(" AND ${MediaStore.Video.Media.IS_PENDING} = 0")
        }
        if (sinceEpochSeconds != null) {
            where.append(" AND ${MediaStore.Video.Media.DATE_ADDED} > ?")
            args.add(sinceEpochSeconds.toString())
        }

        val clips = mutableListOf<ClipDto>()
        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                PROJECTION,
                where.toString(),
                args.toTypedArray(),
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn) ?: continue
                    val durationMs = cursor.getLong(durationColumn)
                    // Pre-Q has no IS_PENDING, so a zero duration is the only tell that a
                    // recording is still in flight.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && durationMs <= 0L) continue

                    clips.add(
                        ClipDto(
                            id = cursor.getLong(idColumn),
                            name = name,
                            dateAdded = cursor.getLong(dateColumn),
                            durationMs = durationMs,
                            sizeBytes = cursor.getLong(sizeColumn),
                            kind = ClipNaming.kindOf(name)
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to list videos", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list videos", e)
        }
        return clips
    }

    /**
     * Looks one clip up by id. Targeted rather than a filtered list(): seeking a clip fires
     * a burst of range requests and each one checks the id, so a full table scan per request
     * would show up as lag on the viewer.
     *
     * Keeping the MTB_ filter here is also what stops an arbitrary MediaStore id being
     * fetched through the API — only the app's own clips are exposed.
     */
    fun find(id: Long): ClipDto? {
        val where = StringBuilder(
            "${MediaStore.Video.Media._ID} = ? AND ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            where.append(" AND ${MediaStore.Video.Media.IS_PENDING} = 0")
        }

        return try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                PROJECTION,
                where.toString(),
                arrayOf(id.toString(), "MTB_%"),
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME))
                    ?: return null
                val durationMs = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION))
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && durationMs <= 0L) return null

                ClipDto(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)),
                    name = name,
                    dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)),
                    durationMs = durationMs,
                    sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)),
                    kind = ClipNaming.kindOf(name)
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to read clip $id", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read clip $id", e)
            null
        }
    }

    fun listJson(sinceEpochSeconds: Long? = null): String {
        val clips = list(sinceEpochSeconds)
        return buildString {
            append("{\"clips\":[")
            clips.forEachIndexed { index, clip ->
                if (index > 0) append(',')
                append(clip.toJson())
            }
            append("],\"mediaPermission\":").append(hasFullMediaAccess()).append('}')
        }
    }
}
