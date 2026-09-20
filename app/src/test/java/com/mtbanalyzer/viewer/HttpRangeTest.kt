package com.mtbanalyzer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Range arithmetic decides whether scrubbing a clip on the viewer is instant or refetches
 * the file, so every form a player might send is pinned down here.
 */
class HttpRangeTest {

    private fun satisfiable(header: String?, total: Long): ByteRange {
        val result = HttpRange.parse(header, total)
        assertTrue("expected a satisfiable range, got $result", result is RangeResult.Satisfiable)
        return (result as RangeResult.Satisfiable).range
    }

    @Test
    fun `no header means the whole entity`() {
        assertEquals(RangeResult.Absent, HttpRange.parse(null, 1000))
    }

    @Test
    fun `closed range is inclusive`() {
        val range = satisfiable("bytes=0-499", 1000)
        assertEquals(0L, range.start)
        assertEquals(499L, range.endInclusive)
        assertEquals(500L, range.length)
    }

    @Test
    fun `single byte range has length one`() {
        val range = satisfiable("bytes=0-0", 10)
        assertEquals(0L, range.start)
        assertEquals(0L, range.endInclusive)
        assertEquals(1L, range.length)
    }

    @Test
    fun `open range runs to the end`() {
        val range = satisfiable("bytes=500-", 1000)
        assertEquals(500L, range.start)
        assertEquals(999L, range.endInclusive)
        assertEquals(500L, range.length)
    }

    @Test
    fun `last byte is reachable`() {
        val range = satisfiable("bytes=999-", 1000)
        assertEquals(999L, range.start)
        assertEquals(999L, range.endInclusive)
        assertEquals(1L, range.length)
    }

    @Test
    fun `suffix range counts back from the end`() {
        val range = satisfiable("bytes=-500", 1000)
        assertEquals(500L, range.start)
        assertEquals(999L, range.endInclusive)
    }

    @Test
    fun `suffix longer than the entity clamps to the whole entity`() {
        val range = satisfiable("bytes=-5000", 1000)
        assertEquals(0L, range.start)
        assertEquals(999L, range.endInclusive)
        assertEquals(1000L, range.length)
    }

    @Test
    fun `end past the entity is clamped`() {
        val range = satisfiable("bytes=0-5000", 1000)
        assertEquals(999L, range.endInclusive)
        assertEquals(1000L, range.length)
    }

    @Test
    fun `whitespace around the spec is tolerated`() {
        val range = satisfiable(" bytes= 10 - 19 ", 1000)
        assertEquals(10L, range.start)
        assertEquals(19L, range.endInclusive)
    }

    @Test
    fun `start past the entity is unsatisfiable`() {
        assertEquals(RangeResult.Unsatisfiable, HttpRange.parse("bytes=1000-", 1000))
        assertEquals(RangeResult.Unsatisfiable, HttpRange.parse("bytes=1000-1500", 1000))
    }

    @Test
    fun `zero length suffix is unsatisfiable`() {
        assertEquals(RangeResult.Unsatisfiable, HttpRange.parse("bytes=-0", 1000))
    }

    @Test
    fun `an empty entity satisfies nothing`() {
        assertEquals(RangeResult.Unsatisfiable, HttpRange.parse("bytes=0-", 0))
    }

    @Test
    fun `reversed range is ignored rather than failed`() {
        assertEquals(RangeResult.Ignore, HttpRange.parse("bytes=500-100", 1000))
    }

    @Test
    fun `multi-range is ignored`() {
        // Serving these needs multipart/byteranges; RFC 7233 lets us serve the full entity.
        assertEquals(RangeResult.Ignore, HttpRange.parse("bytes=0-99,200-299", 1000))
    }

    @Test
    fun `junk is ignored`() {
        assertEquals(RangeResult.Ignore, HttpRange.parse("bytes=abc-def", 1000))
        assertEquals(RangeResult.Ignore, HttpRange.parse("bytes=", 1000))
        assertEquals(RangeResult.Ignore, HttpRange.parse("bytes=12", 1000))
        assertEquals(RangeResult.Ignore, HttpRange.parse("items=0-1", 1000))
    }
}
