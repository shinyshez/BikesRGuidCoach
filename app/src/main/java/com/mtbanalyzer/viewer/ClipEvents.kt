package com.mtbanalyzer.viewer

import com.mtbanalyzer.clips.ClipInfo
import java.io.IOException
import java.io.OutputStream

/**
 * "Something in the clip collection may have changed." A version number that goes up on
 * every [bump], so a waiter can never miss a change that happened between two waits.
 *
 * The recorder bumps it from a MediaStore ContentObserver, which fires for a finished
 * recording (IS_PENDING cleared) and an import alike, and also for plenty of noise while a
 * recording is written. The streams diff the finished list, so noise costs a query, not an
 * event. Pure JVM for the unit tests.
 */
class ClipChangeSignal {

    private val lock = Object()
    private var version = 0L
    private var closed = false

    val current: Long get() = synchronized(lock) { version }

    fun bump() = synchronized(lock) {
        version++
        lock.notifyAll()
    }

    /** Ends every wait for good: the server is stopping. */
    fun close() = synchronized(lock) {
        closed = true
        lock.notifyAll()
    }

    /**
     * Waits until the version passes [seen] or [timeoutMs] runs out. Returns the new version
     * (equal to [seen] on a timeout), or null once closed.
     */
    fun await(seen: Long, timeoutMs: Long): Long? = synchronized(lock) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!closed && version == seen) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            lock.wait(left)
        }
        if (closed) null else version
    }
}

/**
 * One viewer's `/api/events` stream (Phase 2 spec §8). Server-Sent Events:
 *
 * ```
 * event: clip
 * data: {"id":1338,"name":"MTB_…","dateAdded":…,"durationMs":…,"sizeBytes":…,"kind":"recording"}
 *
 * event: removed
 * data: {"id":1337}
 * ```
 *
 * plus a `: keepalive` comment every [keepaliveMs], so a viewer that walked out of range
 * shows up as a failed write instead of a thread parked forever. The stream is a nudge, not
 * the source of truth: viewers re-read `/api/clips` on (re)connect.
 */
class ClipEventStream(
    private val signal: ClipChangeSignal,
    /** The finished clips right now, newest first; [ViewerLinkRoutes] passes its query. */
    private val clips: () -> List<ClipInfo>,
    private val keepaliveMs: Long = KEEPALIVE_MS,
    /** A burst of observer calls (a recording being finalised) settles into one query. */
    private val settleMs: Long = SETTLE_MS
) {

    companion object {
        const val KEEPALIVE_MS = 15_000L
        const val SETTLE_MS = 250L
        /** Reconnect delay the browser's EventSource uses after a drop. */
        private const val RETRY_MS = 3_000
    }

    /** Writes until the viewer goes away (IOException) or the signal closes. */
    @Throws(IOException::class)
    fun run(out: OutputStream) {
        var seen = signal.current
        var known = clips().map { it.id }.toSet()
        write(out, "retry: $RETRY_MS\n: connected\n\n")

        while (true) {
            val next = signal.await(seen, keepaliveMs) ?: return
            if (next == seen) {
                write(out, ": keepalive\n\n")
                continue
            }
            if (settleMs > 0) Thread.sleep(settleMs)
            seen = signal.current

            val now = clips()
            val ids = now.map { it.id }.toSet()
            val frames = StringBuilder()
            // Oldest first, so a viewer inserting as it reads keeps its own order.
            now.filter { it.id !in known }.sortedBy { it.dateAdded }.forEach { clip ->
                frames.append("event: clip\ndata: ").append(clip.toJson()).append("\n\n")
            }
            (known - ids).sorted().forEach { id ->
                frames.append("event: removed\ndata: {\"id\":").append(id).append("}\n\n")
            }
            known = ids
            if (frames.isNotEmpty()) write(out, frames.toString())
        }
    }

    private fun write(out: OutputStream, text: String) {
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }
}
