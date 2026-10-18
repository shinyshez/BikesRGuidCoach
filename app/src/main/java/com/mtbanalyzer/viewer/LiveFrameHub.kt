package com.mtbanalyzer.viewer

/**
 * The hand-off between the camera and the viewer's live view.
 *
 * The camera side asks [onCameraFrame] on every analysis frame and only does any work when
 * this says yes: someone has asked for a frame in the last [WATCH_WINDOW_MS], the last one
 * was at least [FRAME_INTERVAL_MS] ago, and the previous JPEG has finished encoding. With
 * no viewer watching that is one comparison per frame, so the detector never pays for it.
 *
 * The viewer side long-polls [await]: it names the last frame it has and blocks until a
 * newer one exists. That gives natural back-pressure (a slow phone just gets fewer frames)
 * and no response that stays open for ever, which an MJPEG stream would need.
 */
class LiveFrameHub(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {

    companion object {
        const val FRAME_INTERVAL_MS = 200L
        const val WATCH_WINDOW_MS = 3_000L

        /** Older than this and the recorder's screen is off or the camera is closed. */
        const val CAMERA_STALE_MS = 1_500L

        /** A frame older than this is not handed to a viewer that just arrived. */
        const val FRESH_MS = 1_000L

        val shared = LiveFrameHub()
    }

    class Frame(
        val seq: Long,
        val jpeg: ByteArray,
        val width: Int,
        val height: Int,
        val publishedAt: Long
    )

    private val lock = Object()
    private var latest: Frame? = null
    private var seq = 0L
    private var encoding = false
    private var lastClaimAt = Long.MIN_VALUE / 2
    @Volatile private var lastRequestAt = Long.MIN_VALUE / 2
    @Volatile private var lastCameraFrameAt = Long.MIN_VALUE / 2

    /** Camera thread, every frame. True means "encode this one and [publish] it". */
    fun onCameraFrame(): Boolean {
        val now = clock()
        lastCameraFrameAt = now
        if (now - lastRequestAt > WATCH_WINDOW_MS) return false
        synchronized(lock) {
            if (encoding || now - lastClaimAt < FRAME_INTERVAL_MS) return false
            encoding = true
            lastClaimAt = now
            return true
        }
    }

    fun publish(jpeg: ByteArray, width: Int, height: Int) {
        synchronized(lock) {
            latest = Frame(++seq, jpeg, width, height, clock())
            encoding = false
            lock.notifyAll()
        }
    }

    /** The claimed frame could not be encoded; let the next one through. */
    fun abandon() {
        synchronized(lock) { encoding = false }
    }

    fun isCameraLive(): Boolean = clock() - lastCameraFrameAt < CAMERA_STALE_MS

    /**
     * Blocks until there is a fresh frame newer than [afterSeq], or [timeoutMs] passes.
     * Returns null on timeout; [isCameraLive] then says whether the recorder is just slow
     * or has its screen off. Calling this is also what keeps frames flowing.
     */
    @Throws(InterruptedException::class)
    fun await(afterSeq: Long, timeoutMs: Long): Frame? {
        val start = clock()
        lastRequestAt = start
        synchronized(lock) {
            while (true) {
                val now = clock()
                val frame = latest
                if (frame != null && frame.seq > afterSeq && now - frame.publishedAt <= FRESH_MS) {
                    return frame
                }
                val remaining = timeoutMs - (now - start)
                if (remaining <= 0) return null
                lock.wait(remaining)
            }
        }
    }
}
