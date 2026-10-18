package com.mtbanalyzer.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The live view is only useful for framing if what the viewer sees is the right way up and
 * the right colour, so the plane packing and the rotations are pinned down on tiny images.
 */
class YuvFramesTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** A 4x2 frame: Y counts 1..8, chroma pairs are (V, U) = (40+i, 20+i). */
    private fun frame4x2() = Nv21Frame(
        bytes(1, 2, 3, 4, 5, 6, 7, 8, 40, 20, 41, 21),
        4, 2
    )

    @Test
    fun `semi-planar planes with padded rows pack into NV21`() {
        // Row stride 6 for a 4-wide image; chroma interleaved with pixel stride 2, the
        // layout most devices hand over (U and V buffers overlap, offset by one).
        val y = ByteBuffer.wrap(bytes(1, 2, 3, 4, 0, 0, 5, 6, 7, 8, 0, 0))
        val uv = bytes(20, 40, 21, 41, 0, 0)
        val u = ByteBuffer.wrap(uv)
        val v = ByteBuffer.wrap(uv, 1, uv.size - 1).slice()

        val frame = YuvFrames.toNv21(y, u, v, 4, 2, 6, 6, 2)

        assertEquals(4, frame.width)
        assertEquals(2, frame.height)
        assertArrayEquals(frame4x2().data, frame.data)
    }

    @Test
    fun `planar chroma packs into NV21`() {
        val y = ByteBuffer.wrap(bytes(1, 2, 3, 4, 5, 6, 7, 8))
        val u = ByteBuffer.wrap(bytes(20, 21))
        val v = ByteBuffer.wrap(bytes(40, 41))

        val frame = YuvFrames.toNv21(y, u, v, 4, 2, 4, 2, 1)

        assertArrayEquals(frame4x2().data, frame.data)
    }

    @Test
    fun `packing leaves the source buffers where they were`() {
        val y = ByteBuffer.wrap(bytes(1, 2, 3, 4, 5, 6, 7, 8))
        YuvFrames.toNv21(y, ByteBuffer.wrap(bytes(20, 21)), ByteBuffer.wrap(bytes(40, 41)), 4, 2, 4, 2, 1)
        assertEquals(0, y.position())
    }

    @Test
    fun `zero rotation returns the frame untouched`() {
        val frame = frame4x2()
        assertEquals(frame, YuvFrames.rotate(frame, 0))
        assertEquals(frame, YuvFrames.rotate(frame, 360))
    }

    @Test
    fun `90 turns clockwise and swaps the dimensions`() {
        // 1 2 3 4        5 1
        // 5 6 7 8   ->   6 2
        //                7 3
        //                8 4
        val rotated = YuvFrames.rotate(frame4x2(), 90)
        assertEquals(2, rotated.width)
        assertEquals(4, rotated.height)
        // Chroma 2x1 [a b] becomes 1x2 [a / b].
        assertArrayEquals(bytes(5, 1, 6, 2, 7, 3, 8, 4, 40, 20, 41, 21), rotated.data)
    }

    @Test
    fun `180 reverses both axes`() {
        val rotated = YuvFrames.rotate(frame4x2(), 180)
        assertEquals(4, rotated.width)
        assertEquals(2, rotated.height)
        assertArrayEquals(bytes(8, 7, 6, 5, 4, 3, 2, 1, 41, 21, 40, 20), rotated.data)
    }

    @Test
    fun `270 turns anticlockwise`() {
        // 1 2 3 4        4 8
        // 5 6 7 8   ->   3 7
        //                2 6
        //                1 5
        val rotated = YuvFrames.rotate(frame4x2(), 270)
        assertEquals(2, rotated.width)
        assertEquals(4, rotated.height)
        assertArrayEquals(bytes(4, 8, 3, 7, 2, 6, 1, 5, 41, 21, 40, 20), rotated.data)
    }

    @Test
    fun `four quarter turns come back to the start`() {
        var frame = frame4x2()
        repeat(4) { frame = YuvFrames.rotate(frame, 90) }
        assertArrayEquals(frame4x2().data, frame.data)
        assertEquals(4, frame.width)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `odd angles are refused`() {
        YuvFrames.rotate(frame4x2(), 45)
    }
}
