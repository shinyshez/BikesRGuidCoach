package com.mtbanalyzer.viewer

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class RemoteClipCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private val clip = ByteArray(200_000) { (it % 253).toByte() }
    private val requests = AtomicInteger()
    /** Bytes of the body to send before hanging up, or null for the whole thing. */
    @Volatile private var truncateAt: Int? = null
    private lateinit var server: ServerSocket

    @Before
    fun setUp() {
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "clip-stub") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: IOException) { break }
                socket.use {
                    val request = HttpParser.readRequest(BufferedInputStream(it.getInputStream())) ?: return@use
                    requests.incrementAndGet()
                    val out = it.getOutputStream()
                    if (request.query["t"] != "tok") {
                        HttpWriter.write(out, HttpResponse.error(401, "Unauthorized"), includeBody = true, keepAlive = false)
                        return@use
                    }
                    val cut = truncateAt
                    if (cut == null) {
                        HttpWriter.write(out, HttpResponse.bytes(200, "video/mp4", clip), includeBody = true, keepAlive = false)
                    } else {
                        // Promise the whole clip, deliver part of it, hang up.
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: ${clip.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(clip, 0, cut)
                        out.flush()
                    }
                }
            }
        }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun recorder(token: String = "tok") = Recorder(
        RecorderAddress("127.0.0.1", server.localPort, token),
        RecorderHealth("Pixel 7", 1, 1, "rec-1")
    )

    private fun cache(maxBytes: Long = RemoteClipCache.DEFAULT_MAX_BYTES) =
        RemoteClipCache(tmp.root, maxBytes, ViewerHttpClient(connectTimeoutMs = 2_000, readTimeoutMs = 2_000))

    @Test
    fun `downloads byte-identical under the final name, then serves from disk`() {
        val cache = cache()
        var last = 0L to 0L
        val file = cache.fetch(recorder(), 7, onProgress = { done, total -> last = done to total })

        assertEquals(File(tmp.root, "rec-1/7.mp4"), file)
        assertArrayEquals(clip, file.readBytes())
        assertEquals(clip.size.toLong() to clip.size.toLong(), last)
        assertFalse(File(tmp.root, "rec-1/7.mp4.part").exists())

        assertEquals(file, cache.fetch(recorder(), 7))
        assertEquals("second fetch is a cache hit", 1, requests.get())
    }

    @Test
    fun `a cut-off download leaves nothing that looks complete`() {
        truncateAt = 50_000
        try {
            cache().fetch(recorder(), 7)
            fail("accepted a truncated body")
        } catch (e: IOException) {
            // expected
        }
        assertNull(cache().cached("rec-1", 7))
        assertEquals(emptyList<String>(), File(tmp.root, "rec-1").list()?.toList() ?: emptyList<String>())
    }

    @Test
    fun `a cancelled download is cleaned up`() {
        try {
            cache().fetch(recorder(), 7, isCancelled = { true })
            fail("not cancelled")
        } catch (e: RemoteClipCache.CancelledException) {
            // expected
        }
        assertEquals(emptyList<String>(), File(tmp.root, "rec-1").list()?.toList() ?: emptyList<String>())
    }

    @Test(expected = RecorderClient.UnauthorizedException::class)
    fun `a refused token surfaces as unauthorized`() {
        cache().fetch(recorder(token = "old"), 7)
    }

    @Test
    fun `prune drops least recently used first and keeps the one being played`() {
        val dir = File(tmp.root, "rec-1").apply { mkdirs() }
        val files = (1..4).map { i ->
            File(dir, "$i.mp4").apply {
                writeBytes(ByteArray(100))
                setLastModified(1_000_000L * i) // 1 oldest, 4 newest
            }
        }
        RemoteClipCache(tmp.root, maxBytes = 250).prune(keep = files[0])

        // 400 bytes over a 250 limit: the two oldest go, except 1 is kept, so 2 and 3 go.
        assertEquals(listOf(true, false, false, true), files.map { it.exists() })
    }

    @Test
    fun `forgetting a recorder clears only its clips`() {
        File(tmp.root, "rec-1").mkdirs()
        File(tmp.root, "rec-1/1.mp4").writeBytes(ByteArray(1))
        File(tmp.root, "rec-2").mkdirs()
        File(tmp.root, "rec-2/1.mp4").writeBytes(ByteArray(1))

        cache().forget("rec-1")

        assertFalse(File(tmp.root, "rec-1").exists())
        assertTrue(File(tmp.root, "rec-2/1.mp4").exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a recorder id can never name a path outside the cache`() {
        cache().fileFor("../../etc", 1)
    }
}
