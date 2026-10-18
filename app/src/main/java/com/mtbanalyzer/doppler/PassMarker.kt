package com.mtbanalyzer.doppler

/**
 * Matches gate passes to the recordings they fall inside, both on the boot clock.
 *
 * The two arrive in either order. A recording is only known once it finalises, by which time
 * passes during it have long been reported; and a pass is confirmed up to a few hundred ms
 * after its crossing, which can be after a recording that contained it has stopped. So
 * recent passes are kept for clips that finish later, and recent clips for passes that are
 * confirmed later.
 *
 * Pure: no Android types, so it is unit-tested directly. Not thread-safe; call it from one
 * thread (the activity posts both events to the main thread).
 */
class PassMarker(
    private val onMark: (clipKey: String, passMs: Int) -> Unit,
    /** How long to remember passes and finished clips, in ns of the boot clock. */
    private val memoryNanos: Long = 60_000_000_000L,
    /** How long after a clip ends a late-confirmed pass may still be added to it. */
    private val lateNanos: Long = 2_000_000_000L,
) {
    private data class Clip(val key: String, val startNanos: Long, val endNanos: Long)

    private val passes = ArrayDeque<Long>()
    private val clips = ArrayDeque<Clip>()

    /** A pass crossed at [crossingNanos], reported at [nowNanos]. */
    fun onPass(crossingNanos: Long, nowNanos: Long) {
        passes.addLast(crossingNanos)
        for (clip in clips) {
            if (nowNanos <= clip.endNanos + lateNanos) markIfInside(clip, crossingNanos)
        }
        trim(nowNanos)
    }

    /** A recording [key] ran from [startNanos] to [endNanos] and has been saved. */
    fun onClip(key: String, startNanos: Long, endNanos: Long, nowNanos: Long) {
        if (endNanos <= startNanos) return
        val clip = Clip(key, startNanos, endNanos)
        clips.addLast(clip)
        for (crossing in passes) markIfInside(clip, crossing)
        trim(nowNanos)
    }

    private fun markIfInside(clip: Clip, crossingNanos: Long) {
        if (crossingNanos in clip.startNanos..clip.endNanos) {
            onMark(clip.key, ((crossingNanos - clip.startNanos) / 1_000_000L).toInt())
        }
    }

    private fun trim(nowNanos: Long) {
        while (passes.isNotEmpty() && passes.first() < nowNanos - memoryNanos) passes.removeFirst()
        while (clips.isNotEmpty() && clips.first().endNanos < nowNanos - lateNanos) clips.removeFirst()
    }
}
