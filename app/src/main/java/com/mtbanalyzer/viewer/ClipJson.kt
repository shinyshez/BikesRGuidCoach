package com.mtbanalyzer.viewer

import com.mtbanalyzer.clips.ClipInfo

/** One clip as the viewer API describes it. */
internal fun ClipInfo.toJson(): String = buildString {
    append("{\"id\":").append(id)
    append(",\"name\":").append(Json.string(name))
    append(",\"dateAdded\":").append(dateAdded)
    append(",\"durationMs\":").append(durationMs)
    append(",\"sizeBytes\":").append(sizeBytes)
    append(",\"kind\":").append(Json.string(kind))
    append('}')
}

/** The `/api/clips` payload. */
internal fun clipListJson(clips: List<ClipInfo>, mediaPermission: Boolean): String = buildString {
    append("{\"clips\":[")
    clips.forEachIndexed { index, clip ->
        if (index > 0) append(',')
        append(clip.toJson())
    }
    append("],\"mediaPermission\":").append(mediaPermission).append('}')
}
