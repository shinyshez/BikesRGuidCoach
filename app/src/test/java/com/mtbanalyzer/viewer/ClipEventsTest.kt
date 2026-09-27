package com.mtbanalyzer.viewer

import com.mtbanalyzer.clips.ClipInfo
import com.mtbanalyzer.clips.ClipNaming
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ClipEventsTest {

    private fun clip(id: Long, dateAdded: Long = id) =
        ClipInfo(id, "MTB_$id.mp4", dateAdded, 8000, 10, ClipNaming.KIND_IMPORT)

    // --- the signal -------------------------------------------------------------------

    @Test
    fun `a bump wakes a waiter and a missed bump is not lost`() {
        val signal = ClipChangeSignal()
        val seen = signal.current
        signal.bump() // before anyone waits
        assertEquals(seen + 1, signal.await(seen, 5_000))
    }

    @Test
    fun `a quiet wait times out with the same version`() {
        val signal = ClipChangeSignal()
        val seen = signal.current
        val started = System.currentTimeMillis()
        assertEquals(seen, signal.await(seen, 100))
        assertTrue(System.currentTimeMillis() - started >= 90)
    }

    @Test
    fun `close ends every wait`() {
        val signal = ClipChangeSignal()
        var result: Long? = -1
        val waiter = thread { result = signal.await(signal.current, 10_000) }
        Thread.sleep(50)
        signal.close()
        waiter.join(2_000)
        assertFalse(waiter.isAlive)
        assertNull(result)
    }

    // --- the stream -------------------------------------------------------------------

    /** Collects what the stream writes, safely across threads. */
    private class Sink : OutputStream() {
        private val bytes = ByteArrayOutputStream()
        @Volatile var failWrites = false
        override fun write(b: Int) = synchronized(this) {
            if (failWrites) throw IOException("viewer went away")
            bytes.write(b)
        }
        override fun write(b: ByteArray, off: Int, len: Int) = synchronized(this) {
            if (failWrites) throw IOException("viewer went away")
            bytes.write(b, off, len)
        }
        fun text(): String = synchronized(this) { bytes.toString("UTF-8") }
        fun awaitText(needle: String, timeoutMs: Long = 5_000): String {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (needle in text()) return text()
                Thread.sleep(10)
            }
            throw AssertionError("never saw '$needle' in:\n${text()}")
        }
    }

    @Test
    fun `new and removed clips become events, oldest first, and close ends the stream`() {
        val signal = ClipChangeSignal()
        val current = AtomicReference(listOf(clip(1)))
        val sink = Sink()
        val stream = ClipEventStream(signal, { current.get() }, keepaliveMs = 10_000, settleMs = 0)
        val runner = thread { stream.run(sink) }

        sink.awaitText(": connected")
        current.set(listOf(clip(3, dateAdded = 30), clip(2, dateAdded = 20))) // 1 deleted, 2 and 3 new
        signal.bump()
        val text = sink.awaitText("event: removed")

        val two = text.indexOf("\"id\":2,")
        val three = text.indexOf("\"id\":3,")
        assertTrue(text, two in 0 until three)
        assertTrue(text, text.contains("event: removed\ndata: {\"id\":1}\n\n"))
        assertFalse("clip 1 was known at connect", text.contains("event: clip\ndata: {\"id\":1,"))

        signal.close()
        runner.join(2_000)
        assertFalse("stream should end when the signal closes", runner.isAlive)
    }

    @Test
    fun `noise without a change writes nothing, and a quiet stream keeps alive`() {
        val signal = ClipChangeSignal()
        val sink = Sink()
        val stream = ClipEventStream(signal, { listOf(clip(1)) }, keepaliveMs = 100, settleMs = 0)
        val runner = thread { stream.run(sink) }

        sink.awaitText(": connected")
        signal.bump() // an observer call that changed nothing we list
        sink.awaitText(": keepalive")
        assertFalse(sink.text(), sink.text().contains("event:"))

        signal.close()
        runner.join(2_000)
    }

    @Test
    fun `a viewer that goes away ends the stream at the next write`() {
        val signal = ClipChangeSignal()
        val sink = Sink()
        var failure: Throwable? = null
        val runner = thread {
            try {
                ClipEventStream(signal, { emptyList() }, keepaliveMs = 50, settleMs = 0).run(sink)
            } catch (e: IOException) {
                failure = e
            }
        }
        sink.awaitText(": connected")
        sink.failWrites = true
        runner.join(2_000)
        assertFalse(runner.isAlive)
        assertTrue(failure is IOException)
    }

    // --- framing and parsing ------------------------------------------------------------

    @Test
    fun `an event stream has no length and closes the connection`() {
        val out = ByteArrayOutputStream()
        HttpWriter.write(
            out,
            HttpResponse.eventStream { it.write("event: clip\ndata: {}\n\n".toByteArray()) },
            includeBody = true,
            keepAlive = true
        )
        val text = out.toString("UTF-8")
        assertFalse(text, text.contains("Content-Length"))
        assertTrue(text, text.contains("Connection: close\r\n"))
        assertTrue(text, text.contains("Content-Type: text/event-stream"))
        assertTrue(text, text.endsWith("\r\n\r\nevent: clip\ndata: {}\n\n"))
    }

    @Test
    fun `the parser reads what the stream writes and skips comments`() {
        val parser = SseParser()
        val lines = "retry: 3000\n: connected\n\nevent: clip\ndata: {\"id\":2}\n\n: keepalive\n\nevent: removed\ndata: {\"id\":1}\n\ndata: a\ndata: b\n\n"
            .split("\n")
        val events = lines.mapNotNull { parser.feed(it) }
        assertEquals(
            listOf(
                ServerEvent("clip", "{\"id\":2}"),
                ServerEvent("removed", "{\"id\":1}"),
                ServerEvent("message", "a\nb")
            ),
            events
        )
    }

    // --- client against a socket ------------------------------------------------------

    private lateinit var server: ServerSocket

    @After
    fun tearDown() {
        if (::server.isInitialized) server.close()
    }

    @Test
    fun `the client reads events off a real stream until the recorder ends it`() {
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            server.accept().use { socket ->
                val request = HttpParser.readRequest(BufferedInputStream(socket.getInputStream()))!!
                val response = if (request.query["t"] == "tok") {
                    HttpResponse.eventStream { out ->
                        out.write("retry: 3000\n: connected\n\nevent: clip\ndata: {\"id\":9}\n\n".toByteArray())
                        out.flush()
                        out.write(": keepalive\n\nevent: removed\ndata: {\"id\":8}\n\n".toByteArray())
                    }
                } else {
                    HttpResponse.error(401, "Unauthorized")
                }
                HttpWriter.write(socket.getOutputStream(), response, includeBody = true, keepAlive = true)
            }
        }
        val recorder = Recorder(
            RecorderAddress("127.0.0.1", server.localPort, "tok"),
            RecorderHealth("Pixel 7", 1, 1, "rec-1")
        )

        RecorderClient().openEvents(recorder).use { stream ->
            assertEquals(ServerEvent("clip", "{\"id\":9}"), stream.next())
            assertEquals(ServerEvent("removed", "{\"id\":8}"), stream.next())
            assertNull("the recorder closed the stream", stream.next())
        }
    }
}
