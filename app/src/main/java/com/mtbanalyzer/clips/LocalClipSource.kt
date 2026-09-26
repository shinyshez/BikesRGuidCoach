package com.mtbanalyzer.clips

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.mtbanalyzer.MediaPermissions

/** One of this phone's clips, as a MediaStore row describes it. */
data class ClipInfo(
    val id: Long,
    val name: String,
    /** Epoch seconds, matching MediaStore's DATE_ADDED. */
    val dateAdded: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val kind: String
) {
    val ref: ClipRef.Local get() = ClipRef.Local(id)
    val isImport: Boolean get() = kind == ClipNaming.KIND_IMPORT
}

/**
 * Recordings are named MTB_yyyy-MM-dd-HH-mm-ss-SSS.mp4; anything else under the MTB_
 * prefix arrived through import. Drives the gallery's `import` tag and the viewer's `kind`.
 */
object ClipNaming {

    private val RECORDING = Regex("^MTB_\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{3}\\.mp4$", RegexOption.IGNORE_CASE)

    const val KIND_RECORDING = "recording"
    const val KIND_IMPORT = "import"

    fun kindOf(displayName: String): String =
        if (RECORDING.matches(displayName)) KIND_RECORDING else KIND_IMPORT
}

/**
 * This phone's clips: every MediaStore video named MTB_, newest first. The one query behind
 * the gallery, the capture screen's strip and the Viewer Link server.
 *
 * The callers differ in one way, which is [list]'s `finishedOnly`. The server must never hand
 * out a recording that is still being written (the viewer would get a truncated file), while
 * the gallery has always listed every row and must keep doing so: on API 24–28 an import is
 * inserted with no DURATION, so the pre-Q "zero duration means in flight" test would hide it.
 */
class LocalClipSource(private val context: Context) {

    companion object {
        private const val TAG = "LocalClipSource"

        private val PROJECTION = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE
        )

        fun uriFor(id: Long): Uri =
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
    }

    /**
     * True when this install can also see clips it does not own (previous installs, other
     * apps). The viewer reports it so a short list can be explained rather than look broken.
     */
    fun hasFullMediaAccess(): Boolean = MediaPermissions.hasReadVideoPermission(context)

    /**
     * @param sinceEpochSeconds only clips added strictly after this time.
     * @param finishedOnly skip rows still being written; see the class comment.
     * @throws SecurityException when this install may not read the collection at all. The
     *   gallery tells the user; the server treats it as an empty list.
     */
    fun list(sinceEpochSeconds: Long? = null, finishedOnly: Boolean = false): List<ClipInfo> {
        val where = StringBuilder("${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?")
        val args = mutableListOf("MTB_%")

        if (finishedOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            where.append(" AND ${MediaStore.Video.Media.IS_PENDING} = 0")
        }
        if (sinceEpochSeconds != null) {
            where.append(" AND ${MediaStore.Video.Media.DATE_ADDED} > ?")
            args.add(sinceEpochSeconds.toString())
        }

        val clips = mutableListOf<ClipInfo>()
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
                val id = cursor.getLong(idColumn)
                // The LIKE matched, so a null name is a provider oddity. Show it under a
                // made-up name, as the capture strip always has, but never serve it.
                val name = cursor.getString(nameColumn) ?: if (finishedOnly) continue else "MTB_$id.mp4"
                val durationMs = cursor.getLong(durationColumn)
                // Pre-Q has no IS_PENDING, so a zero duration is the only tell that a
                // recording is still in flight.
                if (finishedOnly && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && durationMs <= 0L) continue

                clips.add(
                    ClipInfo(
                        id = id,
                        name = name,
                        dateAdded = cursor.getLong(dateColumn),
                        durationMs = durationMs,
                        sizeBytes = cursor.getLong(sizeColumn),
                        kind = ClipNaming.kindOf(name)
                    )
                )
            }
        }
        return clips
    }

    /**
     * Looks one finished clip up by id. Targeted rather than a filtered list(): seeking a
     * clip fires a burst of range requests and each one checks the id, so a full table scan
     * per request would show up as lag on the viewer.
     *
     * Keeping the MTB_ filter here is also what stops an arbitrary MediaStore id being
     * fetched through the viewer API — only the app's own clips are exposed.
     */
    fun find(id: Long): ClipInfo? {
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

                ClipInfo(
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
}
