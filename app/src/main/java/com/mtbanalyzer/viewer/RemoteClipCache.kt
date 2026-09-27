package com.mtbanalyzer.viewer

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Whole-file copies of a recorder's clips, so the player, the pose overlay and frame stepping
 * all read a local file exactly as they do for this phone's own clips (Phase 2 spec §7:
 * stream to play, download to analyse; M1 is download, then play).
 *
 * Layout: `<root>/<recorderId>/<clipId>.mp4`. A download goes to `.part` and is renamed only
 * once every byte the server promised has arrived, so a file under its final name is always
 * complete. Clip bytes never change for an id, so a cached file never goes stale.
 *
 * Pure JVM, so the `.part`/rename and LRU rules are unit-tested.
 */
class RemoteClipCache(
    private val root: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val http: ViewerHttpClient = ViewerHttpClient()
) {

    companion object {
        const val DEFAULT_MAX_BYTES = 256L * 1024 * 1024
        private const val SUFFIX = ".mp4"
        private const val PART = ".part"
        private const val BUFFER = 64 * 1024
    }

    /** One lock per clip, so a double tap waits for the first download instead of racing it. */
    private val locks = ConcurrentHashMap<String, Any>()

    fun fileFor(recorderId: String, clipId: Long): File = File(dirFor(recorderId), "$clipId$SUFFIX")

    /** The complete cached copy, or null. A hit counts as a use for LRU. */
    fun cached(recorderId: String, clipId: Long): File? =
        fileFor(recorderId, clipId).takeIf { it.isFile }?.also { it.setLastModified(System.currentTimeMillis()) }

    /**
     * Returns the cached copy, downloading it first if needed. [onProgress] gets (bytes so far,
     * total or -1); [isCancelled] is polled between reads, and a cancelled download throws
     * [CancelledException] and leaves nothing behind. Blocking.
     */
    @Throws(IOException::class)
    fun fetch(
        recorder: Recorder,
        clipId: Long,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): File {
        val lock = locks.getOrPut("${recorder.id}/$clipId") { Any() }
        synchronized(lock) {
            cached(recorder.id, clipId)?.let { return it }
            val target = fileFor(recorder.id, clipId)
            download(recorder.address.clipUrl(clipId), target, onProgress, isCancelled)
            prune(keep = target)
            return target
        }
    }

    private fun download(url: String, target: File, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean) {
        val dir = target.parentFile ?: throw IOException("No cache directory")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val part = File(dir, target.name + PART)

        try {
            http.get(url).use { response ->
                if (response.status == 401) throw RecorderClient.UnauthorizedException()
                if (response.status != 200) throw IOException("Recorder answered HTTP ${response.status}")
                val expected = response.contentLength
                var written = 0L
                part.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        if (isCancelled()) throw CancelledException()
                        val n = response.body.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        onProgress(written, expected)
                    }
                }
                // BoundedInputStream stops at Content-Length, so a short read here means the
                // connection dropped mid-body, not that the server sent too much.
                if (expected >= 0 && written != expected) {
                    throw IOException("Download cut short: $written of $expected bytes")
                }
            }
            if (!part.renameTo(target)) throw IOException("Cannot move download into place")
        } finally {
            part.delete()
        }
    }

    /**
     * Deletes least recently used clips until the cache fits [maxBytes]. [keep] survives even
     * if it alone is over the limit: it is the one about to be played.
     */
    fun prune(keep: File? = null) {
        val files = root.walkTopDown().filter { it.isFile && it.name.endsWith(SUFFIX) }.toMutableList()
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        files.sortBy { it.lastModified() }
        for (file in files) {
            if (total <= maxBytes) break
            if (file == keep) continue
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    /** Everything cached from one recorder, for when the viewer forgets it. */
    fun forget(recorderId: String) {
        dirFor(recorderId).deleteRecursively()
    }

    /** Recorder ids come off the network; never let one name a path outside [root]. */
    private fun dirFor(recorderId: String): File {
        require(isValidRecorderId(recorderId)) { "Bad recorder id" }
        return File(root, recorderId)
    }

    class CancelledException : IOException("Download cancelled")
}
