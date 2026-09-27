package com.mtbanalyzer.viewer

import android.content.Context
import java.io.File

/**
 * The recorder this phone is paired with as a viewer. In memory only: the token dies with
 * the recorder's Viewer Link session anyway (Phase 1 §7, kept for Phase 2 — spec Q1), so
 * pairing again after a restart is expected. The download cache is keyed on the stable
 * recorder id and outlives the pairing until the recorder is forgotten.
 */
object RecorderSession {

    @Volatile var recorder: Recorder? = null
        private set

    @Volatile private var cache: RemoteClipCache? = null

    fun cache(context: Context): RemoteClipCache =
        cache ?: synchronized(this) {
            cache ?: RemoteClipCache(File(context.applicationContext.cacheDir, "remote-clips")).also { cache = it }
        }

    fun pair(recorder: Recorder) {
        this.recorder = recorder
    }

    /** Unpairs and drops everything downloaded from that recorder. */
    fun forget(context: Context) {
        val current = recorder ?: return
        recorder = null
        cache(context).forget(current.id)
    }
}
