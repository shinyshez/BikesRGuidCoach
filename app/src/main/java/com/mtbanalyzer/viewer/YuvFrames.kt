package com.mtbanalyzer.viewer

import java.nio.ByteBuffer

/** An NV21 image: the full Y plane, then V and U interleaved at half resolution. */
class Nv21Frame(val data: ByteArray, val width: Int, val height: Int)

/**
 * The pixel shuffling behind the live preview, kept free of Android types so it can be unit
 * tested. CameraX hands analysis frames over as YUV_420_888 in the sensor's orientation;
 * the viewer wants an upright JPEG, and `YuvImage` only compresses NV21.
 */
object YuvFrames {

    /**
     * Packs three YUV_420_888 planes into NV21. The strides come straight from the
     * ImageProxy planes: rows can be padded, and chroma is planar or semi-planar depending
     * on the device, so neither can be assumed.
     */
    fun toNv21(
        y: ByteBuffer,
        u: ByteBuffer,
        v: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int
    ): Nv21Frame {
        val out = ByteArray(width * height * 3 / 2)
        var pos = 0

        val luma = y.duplicate()
        for (row in 0 until height) {
            luma.position(row * yRowStride)
            luma.get(out, pos, width)
            pos += width
        }

        for (row in 0 until height / 2) {
            val rowStart = row * uvRowStride
            for (col in 0 until width / 2) {
                val index = rowStart + col * uvPixelStride
                out[pos++] = v.get(index)
                out[pos++] = u.get(index)
            }
        }
        return Nv21Frame(out, width, height)
    }

    /**
     * Rotates clockwise by [degrees] (0, 90, 180 or 270), which is what
     * `ImageInfo.rotationDegrees` asks for. Done on the pixels rather than left to the page:
     * the recorder can turn between portrait and landscape mid-session, and a JPEG that is
     * already upright needs no side channel to say so.
     */
    fun rotate(frame: Nv21Frame, degrees: Int): Nv21Frame {
        val turn = ((degrees % 360) + 360) % 360
        if (turn == 0) return frame
        require(turn % 90 == 0) { "Rotation must be a multiple of 90, was $degrees" }

        val w = frame.width
        val h = frame.height
        val outW = if (turn == 180) w else h
        val outH = if (turn == 180) h else w
        val src = frame.data
        val out = ByteArray(src.size)

        for (y in 0 until h) {
            val rowStart = y * w
            for (x in 0 until w) {
                out[target(x, y, w, h, turn, outW)] = src[rowStart + x]
            }
        }

        val chromaW = w / 2
        val chromaH = h / 2
        val outChromaW = outW / 2
        val srcBase = w * h
        val outBase = outW * outH
        for (y in 0 until chromaH) {
            for (x in 0 until chromaW) {
                val from = srcBase + (y * chromaW + x) * 2
                val to = outBase + target(x, y, chromaW, chromaH, turn, outChromaW) * 2
                out[to] = src[from]
                out[to + 1] = src[from + 1]
            }
        }
        return Nv21Frame(out, outW, outH)
    }

    private fun target(x: Int, y: Int, w: Int, h: Int, turn: Int, outW: Int): Int =
        when (turn) {
            90 -> x * outW + (h - 1 - y)
            180 -> (h - 1 - y) * outW + (w - 1 - x)
            else -> (w - 1 - x) * outW + y // 270
        }
}
