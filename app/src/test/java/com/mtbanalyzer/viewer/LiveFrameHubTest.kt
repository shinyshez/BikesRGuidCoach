package com.mtbanalyzer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The hub decides whether the detector's camera thread does any live-view work at all, so
 * the "nobody is watching, do nothing" and throttling rules are the important ones here.
 */
class LiveFrameHubTest {

    private var now = 1_000_000L
    private val hub = LiveFrameHub { now }
    private val jpeg = byteArrayOf(1, 2, 3)

    /** A viewer asking without waiting, which is what marks the hub as watched. */
    private fun ask(after: Long = 0L) = hub.await(after, 0L)

    @Test
    fun `no viewer means no frames are claimed`() {
        assertFalse(hub.onCameraFrame())
    }

    @Test
    fun `a viewer request lets the next camera frame through`() {
        ask()
        assertTrue(hub.onCameraFrame())
    }

    @Test
    fun `only one frame is encoded at a time`() {
        ask()
        assertTrue(hub.onCameraFrame())
        now += LiveFrameHub.FRAME_INTERVAL_MS * 3
        assertFalse(hub.onCameraFrame())
        hub.publish(jpeg, 4, 2)
        assertTrue(hub.onCameraFrame())
    }

    @Test
    fun `frames are throttled to the interval`() {
        ask()
        assertTrue(hub.onCameraFrame())
        hub.publish(jpeg, 4, 2)
        now += LiveFrameHub.FRAME_INTERVAL_MS - 1
        assertFalse(hub.onCameraFrame())
        now += 1
        assertTrue(hub.onCameraFrame())
    }

    @Test
    fun `an abandoned frame frees the encoder`() {
        ask()
        assertTrue(hub.onCameraFrame())
        hub.abandon()
        now += LiveFrameHub.FRAME_INTERVAL_MS
        assertTrue(hub.onCameraFrame())
    }

    @Test
    fun `frames stop once the viewer goes quiet`() {
        ask()
        now += LiveFrameHub.WATCH_WINDOW_MS + 1
        assertFalse(hub.onCameraFrame())
    }

    @Test
    fun `a published frame is returned to a viewer that has not seen it`() {
        hub.publish(jpeg, 4, 2)
        val frame = ask()
        assertNotNull(frame)
        assertEquals(1L, frame!!.seq)
        assertEquals(4, frame.width)
        assertNull(ask(after = 1L))
    }

    @Test
    fun `a stale frame is not handed to a viewer`() {
        hub.publish(jpeg, 4, 2)
        now += LiveFrameHub.FRESH_MS + 1
        assertNull(ask())
    }

    @Test
    fun `camera reads as live only while frames keep arriving`() {
        assertFalse(hub.isCameraLive())
        hub.onCameraFrame()
        assertTrue(hub.isCameraLive())
        now += LiveFrameHub.CAMERA_STALE_MS
        assertFalse(hub.isCameraLive())
    }

    @Test
    fun `a waiting viewer wakes when a frame is published`() {
        val realHub = LiveFrameHub()
        val got = AtomicReference<LiveFrameHub.Frame?>()
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        Thread {
            started.countDown()
            got.set(realHub.await(0L, 5_000L))
            done.countDown()
        }.start()

        started.await()
        // The viewer's request is what lets the camera through; wait for it to register.
        val deadline = System.currentTimeMillis() + 2_000
        while (!realHub.onCameraFrame()) {
            assertTrue("viewer never registered", System.currentTimeMillis() < deadline)
            Thread.sleep(5)
        }
        realHub.publish(jpeg, 4, 2)

        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(1L, got.get()?.seq)
    }

    @Test
    fun `a waiting viewer gives up after the timeout`() {
        val realHub = LiveFrameHub()
        val start = System.nanoTime()
        assertNull(realHub.await(0L, 50L))
        assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(50))
    }
}
