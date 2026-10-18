package com.mtbanalyzer.doppler

import android.content.Context

/**
 * Where in each clip the rider passed the gate, as ms from the start of the clip. Keyed on
 * [com.mtbanalyzer.clips.ClipRef.key], like the compare sync, never on a Uri.
 */
class PassMarkStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(clipKey: String): List<Int> = decode(prefs.getString(clipKey, null))

    fun add(clipKey: String, passMs: Int) {
        val marks = (load(clipKey) + passMs).distinct().sorted()
        prefs.edit().putString(clipKey, encode(marks)).apply()
    }

    companion object {
        private const val PREFS_NAME = "gate_pass_marks"

        fun encode(marks: List<Int>): String = marks.joinToString(",")

        fun decode(value: String?): List<Int> =
            value?.split(',')?.mapNotNull { it.trim().toIntOrNull()?.takeIf { ms -> ms >= 0 } }?.sorted()
                ?: emptyList()
    }
}
