package com.mtbanalyzer.viewer

import com.mtbanalyzer.clips.ClipInfo
import com.mtbanalyzer.clips.ClipNaming
import com.mtbanalyzer.clips.ClipRef
import org.junit.After
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

class RecorderClientTest {

    // --- JSON ------------------------------------------------------------------------

    @Test
    fun `json reader handles what the server writes`() {
        val parsed = JsonReader.parseObject(
            """{"a":1,"b":-2.5,"c":"x\"y\\z\né","d":[true,false,null],"e":{}}"""
        )
        assertEquals(1L, parsed["a"])
        assertEquals(-2.5, parsed["b"])
        assertEquals("x\"y\\z\né", parsed["c"])
        assertEquals(listOf(true, false, null), parsed["d"])
        assertEquals(emptyMap<String, Any?>(), parsed["e"])
    }

    @Test
    fun `json reader refuses malformed input instead of guessing`() {
        listOf("", "{", "{\"a\":}", "{\"a\":1,}", "[1 2]", "{\"a\":1} x", "\"open", "{a:1}", "tru")
            .forEach { text ->
                try {
                    JsonReader.parse(text)
                    fail("accepted: $text")
                } catch (e: IOException) {
                    // expected
                }
            }
    }

    @Test
    fun `clip list round-trips through the server's own serialiser`() {
        val clips = listOf(
            ClipInfo(1338, "MTB_2026-09-20-14-31-07-482.mp4", 1758378731, 8004, 12_345_678, ClipNaming.KIND_RECORDING),
            ClipInfo(12, "MTB_odd \"name\".mp4", 1758300000, 5000, 999, ClipNaming.KIND_IMPORT)
        )
        val parsed = parseClips("rec-1", JsonReader.parseObject(clipListJson(clips, mediaPermission = true)))

        assertEquals(clips, parsed.map { it.info })
        assertEquals(ClipRef.Remote("rec-1", 1338), parsed[0].ref)
    }

    // --- pairing URL -----------------------------------------------------------------

    @Test
    fun `the QR code's URL parses`() {
        assertEquals(
            RecorderAddress("192.168.43.1", 8080, "abc-DEF_123"),
            RecorderAddress.parse("http://192.168.43.1:8080/?t=abc-DEF_123")
        )
        // Typed by hand: no trailing slash, surrounding space, default port.
        assertEquals(
            RecorderAddress("172.20.10.2", 80, "tok"),
            RecorderAddress.parse("  http://172.20.10.2?t=tok ")
        )
    }

    @Test
    fun `anything else is not a recorder`() {
        listOf(
            "", "hello", "https://192.168.43.1:8080/?t=tok", "http://192.168.43.1:8080/",
            "http://192.168.43.1:8080/?t=", "WIFI:S:net;T:WPA;P:pw;;", "http://:8080/?t=tok"
        ).forEach { assertNull(it, RecorderAddress.parse(it)) }
    }

    @Test
    fun `api urls carry the token, except health`() {
        val address = RecorderAddress("10.0.0.5", 8080, "a+b/c")
        assertEquals("http://10.0.0.5:8080/api/clips?t=a%2Bb%2Fc&since=5", address.url("/api/clips", mapOf("since" to "5")))
        assertEquals("http://10.0.0.5:8080/api/health", address.url("/api/health", withToken = false))
        assertEquals("http://10.0.0.5:8080/api/clips/7/thumb?t=a%2Bb%2Fc", address.thumbUrl(7))
    }

    // --- client against a socket -----------------------------------------------------

    private lateinit var server: ServerSocket
    private val seen = Collections.synchronizedList(mutableListOf<HttpRequest>())
    @Volatile private var health = """{"ok":true,"device":"Pixel 7","clips":1,"apiVersion":1,"recorderId":"rec-1"}"""
    private val client = RecorderClient(ViewerHttpClient(connectTimeoutMs = 2_000, readTimeoutMs = 2_000))

    @Before
    fun setUp() {
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "recorder-stub") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: IOException) { break }
                socket.use {
                    val request = HttpParser.readRequest(BufferedInputStream(it.getInputStream())) ?: return@use
                    seen.add(request)
                    HttpWriter.write(it.getOutputStream(), respond(request), includeBody = true, keepAlive = false)
                }
            }
        }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun respond(request: HttpRequest): HttpResponse = when {
        request.path == "/api/health" -> HttpResponse.json(200, health)
        request.query["t"] != TOKEN -> HttpResponse.error(401, "Unauthorized")
        request.path == "/api/clips" -> HttpResponse.json(
            200,
            clipListJson(listOf(ClipInfo(7, "MTB_a.mp4", 100, 8000, 10, ClipNaming.KIND_IMPORT)), true)
        )
        else -> HttpResponse.error(404, "Not found")
    }

    private fun address(token: String = TOKEN) = RecorderAddress("127.0.0.1", server.localPort, token)

    @Test
    fun `pairing reads health and proves the token`() {
        val recorder = client.pair(address())

        assertEquals("rec-1", recorder.id)
        assertEquals("Pixel 7", recorder.device)
        assertEquals(listOf("/api/health", "/api/clips"), seen.map { it.path })
        assertNull("health needs no token", seen[0].query["t"])
        assertEquals(listOf(7L), client.clips(recorder).map { it.info.id })
    }

    @Test
    fun `a stale token fails pairing with its own error`() {
        try {
            client.pair(address(token = "old"))
            fail("paired with a refused token")
        } catch (e: RecorderClient.UnauthorizedException) {
            assertTrue(e.message!!.contains("Scan its code again"))
        }
    }

    @Test(expected = IOException::class)
    fun `a recorder without an id cannot be paired`() {
        health = """{"ok":true,"device":"Pixel 7","clips":1,"apiVersion":1}"""
        client.pair(address())
    }

    @Test(expected = IOException::class)
    fun `a future api version is refused rather than misread`() {
        health = """{"ok":true,"device":"Pixel 7","clips":1,"apiVersion":2,"recorderId":"rec-1"}"""
        client.pair(address())
    }

    private companion object {
        const val TOKEN = "tok"
    }
}
