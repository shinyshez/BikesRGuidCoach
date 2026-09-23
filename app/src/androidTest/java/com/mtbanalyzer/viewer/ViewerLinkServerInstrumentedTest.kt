package com.mtbanalyzer.viewer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtbanalyzer.VideoImporter
import com.mtbanalyzer.clips.LocalClipSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Drives the real server over loopback against a real MediaStore clip.
 *
 * Range handling is the part that decides whether scrubbing on the viewer works, and it is
 * the part a unit test cannot cover end to end, so it is exercised against actual bytes.
 */
@RunWith(AndroidJUnit4::class)
class ViewerLinkServerInstrumentedTest {

    private companion object {
        const val TOKEN = "test-token-123"

        /**
         * Explicitly IPv4, never InetAddress.getLoopbackAddress(): that returns ::1 where
         * IPv6 is preferred, so the server would bind IPv6-only while HttpURLConnection
         * dials 127.0.0.1 and every request is refused on a port the server reports as bound.
         */
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
    }

    private lateinit var context: Context
    private lateinit var server: ViewerLinkServer
    private var port = 0
    private var clipUri: Uri? = null
    private var clipId = -1L

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()

        val source = File(context.cacheDir, "viewer_link_source.mp4")
        InstrumentationRegistry.getInstrumentation().context.resources
            .openRawResource(com.mtbanalyzer.test.R.raw.test_clip)
            .use { input -> source.outputStream().use { input.copyTo(it) } }

        clipUri = VideoImporter(context).import(Uri.fromFile(source))
        assertNotNull("test clip should import", clipUri)
        clipId = ContentUris.parseId(clipUri!!)

        val routes = ViewerLinkRoutes(
            context = context,
            clips = LocalClipSource(context),
            thumbnails = ClipThumbnails(context),
            token = TOKEN,
            allowedHosts = setOf("127.0.0.1", "localhost")
        )
        server = ViewerLinkServer(routes)
        // Port 0: the OS picks a free one, so twelve sequential tests can never collide.
        port = server.start(LOOPBACK, 0)
        awaitReachable()
    }

    /**
     * Fails loudly and specifically if the socket isn't actually reachable, rather than
     * leaving every test to report an indistinguishable ConnectException.
     */
    private fun awaitReachable() {
        try {
            Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), 2_000) }
        } catch (e: Exception) {
            throw AssertionError(
                "Viewer link server reported port $port on ${LOOPBACK.hostAddress} " +
                    "but is not reachable there: ${e.javaClass.simpleName} ${e.message}",
                e
            )
        }
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop()
        clipUri?.let { context.contentResolver.delete(it, null, null) }
    }

    @Test
    fun health_isServedWithoutAToken() {
        val connection = connect("/api/health")
        assertEquals(200, connection.responseCode)
        assertTrue(connection.inputStream.readBytes().toString(Charsets.UTF_8).contains("\"ok\":true"))
    }

    @Test
    fun clips_needATokenAndThenListTheClip() {
        assertEquals(401, connect("/api/clips").responseCode)

        val connection = connect("/api/clips?t=$TOKEN")
        assertEquals(200, connection.responseCode)
        val body = connection.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(body, body.contains("\"id\":$clipId"))
        assertTrue(body, body.contains("MTB_"))
    }

    @Test
    fun clip_isServedWholeWithRangeSupportAdvertised() {
        val connection = connect("/api/clips/$clipId?t=$TOKEN")
        assertEquals(200, connection.responseCode)
        assertEquals("bytes", connection.getHeaderField("Accept-Ranges"))
        assertEquals("video/mp4", connection.getHeaderField("Content-Type"))

        val body = connection.inputStream.readBytes()
        assertEquals(expectedSize(), body.size.toLong())
    }

    @Test
    fun clip_servesTheRequestedRangeOnly() {
        val connection = connect("/api/clips/$clipId?t=$TOKEN", range = "bytes=100-199")
        assertEquals(206, connection.responseCode)
        assertEquals(
            "bytes 100-199/${expectedSize()}",
            connection.getHeaderField("Content-Range")
        )

        val body = connection.inputStream.readBytes()
        assertEquals(100, body.size)

        // The slice must line up with the same offset of the whole file.
        val whole = connect("/api/clips/$clipId?t=$TOKEN").inputStream.readBytes()
        assertTrue(body.contentEquals(whole.copyOfRange(100, 200)))
    }

    @Test
    fun clip_servesAnOpenEndedRangeToTheEnd() {
        val size = expectedSize()
        val start = size - 50
        val connection = connect("/api/clips/$clipId?t=$TOKEN", range = "bytes=$start-")
        assertEquals(206, connection.responseCode)
        assertEquals("bytes $start-${size - 1}/$size", connection.getHeaderField("Content-Range"))
        assertEquals(50, connection.inputStream.readBytes().size)
    }

    @Test
    fun clip_rejectsARangePastTheEnd() {
        val size = expectedSize()
        val connection = connect("/api/clips/$clipId?t=$TOKEN", range = "bytes=$size-")
        assertEquals(416, connection.responseCode)
        assertEquals("bytes */$size", connection.getHeaderField("Content-Range"))
    }

    @Test
    fun head_reportsTheLengthWithoutABody() {
        val connection = connect("/api/clips/$clipId?t=$TOKEN", method = "HEAD")
        assertEquals(200, connection.responseCode)
        assertEquals(expectedSize(), connection.getHeaderField("Content-Length").toLong())
    }

    @Test
    fun unknownClip_is404() {
        assertEquals(404, connect("/api/clips/99999999?t=$TOKEN").responseCode)
    }

    @Test
    fun unknownPath_is404() {
        assertEquals(404, connect("/api/nope?t=$TOKEN").responseCode)
    }

    @Test
    fun page_redirectsTheTokenIntoACookie() {
        val connection = connect("/?t=$TOKEN", followRedirects = false)
        assertEquals(302, connection.responseCode)
        assertEquals("/", connection.getHeaderField("Location"))
        val cookie = connection.getHeaderField("Set-Cookie")
        assertTrue(cookie, cookie.startsWith("${ViewerLinkRoutes.COOKIE_NAME}=$TOKEN"))
        assertTrue(cookie, cookie.contains("HttpOnly"))
        // Lax, not Strict: a scanner app opening the QR link is cross-site as far as the
        // browser is concerned, and a Strict cookie would be withheld on the redirect --
        // leaving the viewer on "/" with a 401. See ViewerLinkRoutes.page().
        assertTrue(cookie, cookie.contains("SameSite=Lax"))
    }

    @Test
    fun page_isRefusedWithoutAToken() {
        assertEquals(401, connect("/").responseCode)
    }

    @Test
    fun page_isServedWithTheCookie() {
        val withCookie = connect("/", cookie = "${ViewerLinkRoutes.COOKIE_NAME}=$TOKEN")
        assertEquals(200, withCookie.responseCode)
        val body = withCookie.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(body, body.contains("MTB Analyzer"))
    }

    /** The descriptor length, which is what the server reports — not the MediaStore column. */
    private fun expectedSize(): Long =
        context.contentResolver.openFileDescriptor(clipUri!!, "r")?.use { it.statSize }
            ?: throw AssertionError("clip $clipId has no readable descriptor")

    private fun connect(
        path: String,
        range: String? = null,
        method: String = "GET",
        cookie: String? = null,
        followRedirects: Boolean = true
    ): HttpURLConnection =
        (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = followRedirects
            connectTimeout = 5_000
            readTimeout = 5_000
            range?.let { setRequestProperty("Range", it) }
            cookie?.let { setRequestProperty("Cookie", it) }
        }
}
