package com.mtbanalyzer.viewer

/** An inclusive byte range, as HTTP expresses them. */
data class ByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1
}

sealed class RangeResult {
    /** No Range header — serve the whole entity. */
    object Absent : RangeResult()

    /**
     * Malformed or unsupported (multi-range). RFC 7233 says to ignore such a header and
     * serve the full entity rather than fail, which is also what keeps odd clients working.
     */
    object Ignore : RangeResult()

    /** Well-formed but outside the entity — 416. */
    object Unsatisfiable : RangeResult()

    data class Satisfiable(val range: ByteRange) : RangeResult()
}

/**
 * Parses the one Range form that matters for video: a single byte range.
 *
 * Seeking a clip fires a burst of these, so getting the arithmetic right here is what
 * decides whether scrubbing on the viewer is instant or re-downloads the file.
 */
object HttpRange {

    private const val PREFIX = "bytes="

    fun parse(header: String?, totalLength: Long): RangeResult {
        if (header == null) return RangeResult.Absent

        val trimmed = header.trim()
        if (!trimmed.regionMatches(0, PREFIX, 0, PREFIX.length, ignoreCase = true)) {
            return RangeResult.Ignore
        }

        val spec = trimmed.substring(PREFIX.length).trim()
        // Multi-range responses need multipart/byteranges; no client we care about asks.
        if (spec.isEmpty() || spec.contains(',')) return RangeResult.Ignore

        val dash = spec.indexOf('-')
        if (dash < 0) return RangeResult.Ignore
        val firstPart = spec.substring(0, dash).trim()
        val lastPart = spec.substring(dash + 1).trim()

        // An empty entity can satisfy no range at all.
        if (totalLength <= 0L) return RangeResult.Unsatisfiable

        if (firstPart.isEmpty()) {
            // Suffix form: "bytes=-500" means the *last* 500 bytes.
            val suffixLength = lastPart.toLongOrNull() ?: return RangeResult.Ignore
            if (suffixLength <= 0L) return RangeResult.Unsatisfiable
            val start = if (suffixLength >= totalLength) 0L else totalLength - suffixLength
            return RangeResult.Satisfiable(ByteRange(start, totalLength - 1))
        }

        val start = firstPart.toLongOrNull() ?: return RangeResult.Ignore
        if (start < 0L) return RangeResult.Ignore
        if (start >= totalLength) return RangeResult.Unsatisfiable

        if (lastPart.isEmpty()) {
            // Open form: "bytes=500-" runs to the end.
            return RangeResult.Satisfiable(ByteRange(start, totalLength - 1))
        }

        val end = lastPart.toLongOrNull() ?: return RangeResult.Ignore
        if (end < start) return RangeResult.Ignore
        return RangeResult.Satisfiable(ByteRange(start, minOf(end, totalLength - 1)))
    }
}
