package com.mtbanalyzer.viewer

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * The viewer side of the link: a GET-only HTTP/1.1 client on a raw [Socket].
 *
 * Why not HttpURLConnection or media3's DefaultHttpDataSource: Android's network security
 * policy refuses outbound cleartext from the platform HTTP stacks, and a
 * `<domain-config>` can only list hostnames or exact IP literals -- there is no way to say
 * "any private address". Hotspot gateways move (Android 11+ randomises the subnet, an
 * iPhone hotspot hands out 172.20.10.x), so there is no fixed list to write down either.
 * The policy is enforced by those stacks, not by the socket layer, so talking to the
 * recorder over a socket needs no exemption and leaves the strict default in place for
 * everything else the app does. The server on the other end is equally hand-rolled; this
 * only has to understand what [HttpWriter] writes.
 *
 * Pure JVM on purpose (no android.*), so it is unit-tested against a real socket.
 */
class ViewerHttpClient(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 15_000
) {

    companion object {
        private const val MAX_LINE_BYTES = 8192
        private const val MAX_HEADERS = 50
    }

    /**
     * Opens [url] and returns once the response head has been read. [position] and
     * [length] follow media3's DataSpec: a length of -1 means "to the end", and (0, -1)
     * sends no Range header at all.
     *
     * The caller owns the returned response and must close it; one socket per request,
     * which is how a player seeks anyway (it abandons the old read and opens a new one).
     */
    @Throws(IOException::class)
    fun get(
        url: String,
        position: Long = 0L,
        length: Long = -1L,
        headers: Map<String, String> = emptyMap()
    ): RemoteResponse {
        require(position >= 0L) { "position must be >= 0" }
        require(length == -1L || length > 0L) { "length must be -1 or positive" }

        val uri = URI(url)
        if (!"http".equals(uri.scheme, ignoreCase = true)) {
            throw IOException("Only http:// is supported, got ${uri.scheme}")
        }
        val host = uri.host ?: throw IOException("No host in $url")
        val port = if (uri.port == -1) 80 else uri.port
        val target = buildString {
            append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
            uri.rawQuery?.let { append('?').append(it) }
        }

        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true

            val request = requestHead(host, port, target, rangeHeader(position, length), headers)
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.UTF_8))
                flush()
            }

            // Unbuffered read of the head, so no body bytes are swallowed into a reader;
            // the body stream is wrapped afterwards.
            val input = socket.getInputStream()
            val (status, responseHeaders) = readHead(input)
            return RemoteResponse(status, responseHeaders, bodyStream(input, responseHeaders), socket)
        } catch (e: IOException) {
            closeQuietly(socket)
            throw e
        } catch (e: RuntimeException) {
            closeQuietly(socket)
            throw e
        }
    }

    // --- request --------------------------------------------------------------------

    internal fun rangeHeader(position: Long, length: Long): String? = when {
        position == 0L && length == -1L -> null
        length == -1L -> "bytes=$position-"
        else -> "bytes=$position-${position + length - 1}"
    }

    private fun requestHead(
        host: String,
        port: Int,
        target: String,
        range: String?,
        extra: Map<String, String>
    ): String = buildString {
        append("GET ").append(target).append(" HTTP/1.1\r\n")
        // The server's rebinding guard compares this against the address it advertised.
        append("Host: ").append(host)
        if (port != 80) append(':').append(port)
        append("\r\n")
        append("Connection: close\r\n")
        append("Accept-Encoding: identity\r\n")
        range?.let { append("Range: ").append(it).append("\r\n") }
        for ((name, value) in extra) append(name).append(": ").append(value).append("\r\n")
        append("\r\n")
    }

    // --- response -------------------------------------------------------------------

    internal fun readHead(input: InputStream): Pair<Int, Map<String, String>> {
        val statusLine = readLine(input) ?: throw IOException("Connection closed before a response")
        val parts = statusLine.split(' ', limit = 3)
        if (parts.size < 2 || !parts[0].startsWith("HTTP/")) {
            throw IOException("Bad status line: $statusLine")
        }
        val status = parts[1].toIntOrNull() ?: throw IOException("Bad status line: $statusLine")

        val headers = HashMap<String, String>()
        var count = 0
        while (true) {
            val line = readLine(input) ?: throw IOException("Truncated response headers")
            if (line.isEmpty()) break
            if (++count > MAX_HEADERS) throw IOException("Too many response headers")
            val colon = line.indexOf(':')
            if (colon <= 0) throw IOException("Bad header line: $line")
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return status to headers
    }

    private fun bodyStream(input: InputStream, headers: Map<String, String>): InputStream {
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            // HttpWriter always sends Content-Length; chunked would mean a different server.
            throw IOException("Chunked responses are not supported")
        }
        val length = headers["content-length"]?.toLongOrNull()
        // Connection: close was requested, so without a length the body runs to EOF.
        return if (length == null) input else BoundedInputStream(input, length)
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b == -1) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            if (b == '\n'.code) {
                val line = buffer.toString("UTF-8")
                return if (line.endsWith("\r")) line.dropLast(1) else line
            }
            if (buffer.size() >= MAX_LINE_BYTES) throw IOException("Response header line too long")
            buffer.write(b)
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (e: IOException) {
            // Nothing useful to do; the request already failed.
        }
    }
}

/** A response whose body is still on the wire. Closing it closes the socket. */
class RemoteResponse internal constructor(
    val status: Int,
    private val headers: Map<String, String>,
    val body: InputStream,
    private val socket: Socket
) : Closeable {

    fun header(name: String): String? = headers[name.lowercase()]

    /** Bytes in this response's body, or -1 when the server did not say. */
    val contentLength: Long get() = header("content-length")?.toLongOrNull() ?: -1L

    /** The whole entity's size from a 206's Content-Range, or -1. */
    val totalLength: Long get() = ContentRange.total(header("content-range"))

    override fun close() {
        try {
            socket.close()
        } catch (e: IOException) {
            // Closing is best effort; a player abandons reads mid-seek all the time.
        }
    }
}

/** Parses `Content-Range: bytes 100-199/1234` (and `bytes * /1234` from a 416). */
object ContentRange {

    fun total(header: String?): Long {
        val slash = header?.lastIndexOf('/') ?: return -1L
        if (slash < 0) return -1L
        return header.substring(slash + 1).trim().toLongOrNull() ?: -1L
    }

    /** First byte of the range, or -1 for `*` or anything malformed. */
    fun start(header: String?): Long {
        if (header == null) return -1L
        val spec = header.trim()
        if (!spec.startsWith("bytes", ignoreCase = true)) return -1L
        val dash = spec.indexOf('-')
        if (dash < 0) return -1L
        return spec.substring(5, dash).trim().toLongOrNull() ?: -1L
    }
}

/** Stops at [limit] bytes so a keep-alive-style over-read can never leak into the body. */
private class BoundedInputStream(input: InputStream, private var remaining: Long) :
    FilterInputStream(input) {

    override fun read(): Int {
        if (remaining <= 0L) return -1
        val b = super.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0L) return -1
        val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(minOf(n, remaining))
        if (skipped > 0) remaining -= skipped
        return skipped
    }

    override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()

    override fun markSupported(): Boolean = false
}
