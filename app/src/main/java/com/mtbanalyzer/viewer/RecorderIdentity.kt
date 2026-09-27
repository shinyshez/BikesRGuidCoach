package com.mtbanalyzer.viewer

import android.content.Context
import java.util.UUID

/**
 * A random id for this phone as a recorder, created once and kept. The viewer keys its
 * cache and compare state on it, so it has to outlive the per-session token (a new one on
 * every Viewer Link start) — otherwise every restart would look like a different recorder.
 * Random rather than derived from the device, so it identifies nothing outside the link.
 */
object RecorderIdentity {

    private const val PREFS = "viewer_link"
    private const val KEY_RECORDER_ID = "recorder_id"

    @Synchronized
    fun id(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_RECORDER_ID, null)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_RECORDER_ID, fresh).apply()
        return fresh
    }
}
