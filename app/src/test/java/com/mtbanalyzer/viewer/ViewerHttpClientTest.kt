package com.mtbanalyzer.viewer

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * The client against a real socket and the server's own protocol code (HttpParser,
 * HttpRange, HttpWriter), so the two halves of the link are held to the same bytes.
 * ViewerLinkServer itself logs through android.util.Log, so a small stand-in serves here.
 */
class ViewerHttpClientTest {

    private val entity = ByteArray(10_000) { (it % 251).toByte() }
    private val seen = Collections.synchronizedList(mutableListOf<HttpRequest>())
    private lateinit var server: ServerSocket
    @Volatile private var cannedResponse: String? = null

    private val client = ViewerHttpClient(connectTimeoutMs = 2_000, readTimeoutMs = 2_000)

    @Before
    fun setUp() {
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "test-server") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: IOException) { break }
                socket.use {
                    val request = HttpParser.readRequest(BufferedInputStream(it.getInputStream()))
                        ?: return@use
                    seen.add(request)
                    val out = it.getOutputStream()
                    val canned = cannedResponse
                    if (canned != null) {
                        out.write(canned.toByteArray(Charsets.UTF_8))
                        out.flush()
                    } else {
                        HttpWriter.write(out, respond(request), includeBody = true, keepAlive = false)
                    }
                }
            }
        }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun respond(request: HttpRequest): HttpResponse {
        val total = entity.size.toLong()
        return when (val range = HttpRange.parse(request.header("range"), total)) {
            is RangeResult.Satisfiable -> {
                val r = range.range
                HttpResponse.bytes(
                    206, "video/mp4",
                    entity.copyOfRange(r.start.toInt(), r.endInclusive.toInt() + 1),
                    mapOf("Content-Range" to "bytes ${r.start}-${r.endInclusive}/$total")
                )
            }
            is RangeResult.Unsatisfiable ->
                HttpResponse.bytes(416, "text/plain", ByteArray(0), mapOf("Content-Range" to "bytes */$total"))
            else -> HttpResponse.bytes(200, "video/mp4", entity)
        }
    }

    private fun url(path: String = "/api/clips/7?t=tok") = "http://127.0.0.1:${server.localPort}$path"

    @Test
    fun `whole entity with no Range header`() {
        client.get(url()).use { response ->
            assertEquals(200, response.status)
            assertEquals(entity.size.toLong(), response.contentLength)
            assertArrayEquals(entity, response.body.readBytes())
        }
        assertNull(seen.single().header("range"))
    }

    @Test
    fun `bounded range`() {
        client.get(url(), position = 100, length = 50).use { response ->
            assertEquals(206, response.status)
            assertEquals(50L, response.contentLength)
            assertEquals(entity.size.toLong(), response.totalLength)
            assertArrayEquals(entity.copyOfRange(100, 150), response.body.readBytes())
        }
        assertEquals("bytes=100-149", seen.single().header("range"))
    }

    @Test
    fun `open ended range runs to the end`() {
        client.get(url(), position = 9_990).use { response ->
            assertEquals(206, response.status)
            assertArrayEquals(entity.copyOfRange(9_990, 10_000), response.body.readBytes())
        }
        assertEquals("bytes=9990-", seen.single().header("range"))
    }

    @Test
    fun `range past the end is a 416`() {
        client.get(url(), position = 10_000).use { response ->
            assertEquals(416, response.status)
            assertEquals(10_000L, response.totalLength)
        }
    }

    @Test
    fun `sends a Host header the rebinding guard accepts and keeps the query`() {
        client.get(url("/api/clips?since=5&t=a%20b")).close()
        val request = seen.single()
        assertEquals("127.0.0.1", request.hostName())
        assertEquals("/api/clips", request.path)
        assertEquals("a b", request.query["t"])
        assertEquals("5", request.query["since"])
        assertEquals("close", request.header("connection"))
    }

    @Test
    fun `passes extra headers through`() {
        client.get(url(), headers = mapOf("Cookie" to "mtbvl=secret")).close()
        assertEquals("secret", seen.single().cookie("mtbvl"))
    }

    @Test
    fun `body stops at Content-Length even if the server sends more`() {
        cannedResponse = "HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabcdef"
        client.get(url()).use { response ->
            assertEquals("abc", response.body.readBytes().toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `body without Content-Length runs to EOF`() {
        cannedResponse = "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nhello"
        client.get(url()).use { response ->
            assertEquals(-1L, response.contentLength)
            assertEquals("hello", response.body.readBytes().toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `chunked responses are refused rather than misread`() {
        cannedResponse = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n"
        expectIOException { client.get(url()) }
    }

    @Test
    fun `garbage status line is an IOException`() {
        cannedResponse = "SSH-2.0-OpenSSH\r\n\r\n"
        expectIOException { client.get(url()) }
    }

    @Test
    fun `https is refused`() {
        expectIOException { client.get("https://127.0.0.1:${server.localPort}/") }
    }

    @Test
    fun `nothing listening is an IOException`() {
        val port = server.localPort
        server.close()
        expectIOException { client.get("http://127.0.0.1:$port/") }
    }

    @Test
    fun `range header arithmetic matches DataSpec semantics`() {
        assertNull(client.rangeHeader(0, -1))
        assertEquals("bytes=0-0", client.rangeHeader(0, 1))
        assertEquals("bytes=500-", client.rangeHeader(500, -1))
        assertEquals("bytes=500-999", client.rangeHeader(500, 500))
    }

    @Test
    fun `content range parsing`() {
        assertEquals(1234L, ContentRange.total("bytes 100-199/1234"))
        assertEquals(100L, ContentRange.start("bytes 100-199/1234"))
        assertEquals(1234L, ContentRange.total("bytes */1234"))
        assertEquals(-1L, ContentRange.start("bytes */1234"))
        assertEquals(-1L, ContentRange.total(null))
        assertEquals(-1L, ContentRange.total("bytes 0-1/*"))
        assertEquals(-1L, ContentRange.start("items 0-1/2"))
    }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            fail("expected an IOException")
        } catch (e: IOException) {
            assertTrue(true)
        }
    }
}
