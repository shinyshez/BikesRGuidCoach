package com.mtbanalyzer

import android.content.Context
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Remembers the lock state and sync offset for each pair of clips compared, so reopening
 * the same comparison restores the sync the user dialled in. A pair that has never been
 * compared opens locked at zero offset.
 */
class CompareSyncStore(context: Context) {

    data class SyncState(val locked: Boolean, val offsetMs: Int)

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(id1: String, id2: String): SyncState {
        val key = pairKey(id1, id2)
        if (!prefs.contains("$key$LOCKED_SUFFIX")) return DEFAULT
        return SyncState(
            locked = prefs.getBoolean("$key$LOCKED_SUFFIX", true),
            offsetMs = prefs.getInt("$key$OFFSET_SUFFIX", 0)
        )
    }

    fun save(id1: String, id2: String, state: SyncState) {
        val key = pairKey(id1, id2)
        prefs.edit()
            .putBoolean("$key$LOCKED_SUFFIX", state.locked)
            .putInt("$key$OFFSET_SUFFIX", state.offsetMs)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "compare_sync"
        private const val LOCKED_SUFFIX = ".locked"
        private const val OFFSET_SUFFIX = ".offsetMs"
        val DEFAULT = SyncState(locked = true, offsetMs = 0)

        /** Order matters: the offset is clip 2 relative to clip 1. */
        fun pairKey(id1: String, id2: String): String = "$id1|$id2"

        /** "Δ +7f" / "Δ −3f" / "Δ 0f" — the offset in whole frames of the first clip. */
        fun formatOffsetFrames(offsetMs: Int, frameDurationMs: Double): String {
            val frames = if (frameDurationMs > 0) (offsetMs / frameDurationMs).roundToInt() else 0
            val sign = when {
                frames > 0 -> "+"
                frames < 0 -> "−"
                else -> ""
            }
            return "Δ $sign${abs(frames)}f"
        }
    }
}
