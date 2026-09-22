package com.mtbanalyzer.viewer

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.nio.charset.Charset

/** A request line + headers. The viewer API is read-only, so bodies are never read. */
class HttpRequest(
    val method: String,
    val target: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>
) {
    fun header(name: String): String? = headers[name.lowercase()]

    /** Value of a single cookie from the Cookie header, or null. */
    fun cookie(name: String): String? {
        val raw = header("cookie") ?: return null
        for (part in raw.split(';')) {
            val pair = part.trim()
            val eq = pair.indexOf('=')
            if (eq > 0 && pair.substring(0, eq).trim() == name) {
                return pair.substring(eq + 1).trim()
            }
        }
        return null
    }

    /** The host without its port, for the rebinding guard. */
    fun hostName(): String? {
        val host = header("host")?.trim() ?: return null
        if (host.startsWith("[")) return host.substringAfter('[').substringBefore(']')
        return host.substringBefore(':')
    }
}

class MalformedRequestException(message: String) : IOException(message)

/**
 * A response the server can write. The body is a lambda so a clip can stream straight off
 * its file descriptor instead of being buffered into memory first.
 */
class HttpResponse(
    val status: Int,
    val headers: LinkedHashMap<String, String> = LinkedHashMap(),
    val contentLength: Long = 0L,
    val body: ((OutputStream) -> Unit)? = null
) {
    companion object {
        private val UTF8: Charset = Charsets.UTF_8

        fun bytes(
            status: Int,
            contentType: String,
            payload: ByteArray,
            extraHeaders: Map<String, String> = emptyMap()
        ): HttpResponse {
            val headers = LinkedHashMap<String, String>()
            headers["Content-Type"] = contentType
            headers.putAll(extraHeaders)
            return HttpResponse(status, headers, payload.size.toLong()) { out -> out.write(payload) }
        }

        fun json(status: Int, payload: String, extraHeaders: Map<String, String> = emptyMap()) =
            bytes(status, "application/json; charset=utf-8", payload.toByteArray(UTF8), extraHeaders)

        fun html(status: Int, payload: String) =
            bytes(status, "text/html; charset=utf-8", payload.toByteArray(UTF8))

        fun error(status: Int, message: String) = json(status, Json.error(message))

        fun empty(status: Int, extraHeaders: Map<String, String> = emptyMap()): HttpResponse {
            val headers = LinkedHashMap<String, String>()
            headers.putAll(extraHeaders)
            return HttpResponse(status, headers, 0L, null)
        }

        fun stream(
            status: Int,
            contentType: String,
            length: Long,
            extraHeaders: Map<String, String> = emptyMap(),
            writer: (OutputStream) -> Unit
        ): HttpResponse {
            val headers = LinkedHashMap<String, String>()
            headers["Content-Type"] = contentType
            headers.putAll(extraHeaders)
            return HttpResponse(status, headers, length, writer)
        }
    }
}

/**
 * Reads requests a byte at a time off the raw stream. Deliberately not a BufferedReader:
 * keep-alive means the same stream serves the next request, and a reader would swallow
 * bytes past the headers into its own buffer.
 */
object HttpParser {

    private const val MAX_LINE_BYTES = 8192
    private const val MAX_HEADERS = 50

    /** Returns null on a clean EOF — the client closed an idle keep-alive connection. */
    fun readRequest(input: InputStream): HttpRequest? {
        var requestLine = readLine(input) ?: return null
        // Tolerate leading blank lines left by a previous request (RFC 7230 §3.5).
        while (requestLine.isEmpty()) {
            requestLine = readLine(input) ?: return null
        }

        val firstSpace = requestLine.indexOf(' ')
        val lastSpace = requestLine.lastIndexOf(' ')
        if (firstSpace <= 0 || lastSpace <= firstSpace) {
            throw MalformedRequestException("bad request line")
        }
        val method = requestLine.substring(0, firstSpace).uppercase()
        val target = requestLine.substring(firstSpace + 1, lastSpace)
        if (target.isEmpty()) throw MalformedRequestException("empty request target")

        val headers = HashMap<String, String>()
        var count = 0
        while (true) {
            val line = readLine(input) ?: throw MalformedRequestException("truncated headers")
            if (line.isEmpty()) break
            if (++count > MAX_HEADERS) throw MalformedRequestException("too many headers")
            val colon = line.indexOf(':')
            if (colon <= 0) throw MalformedRequestException("bad header line")
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        val questionMark = target.indexOf('?')
        val rawPath = if (questionMark < 0) target else target.substring(0, questionMark)
        val rawQuery = if (questionMark < 0) "" else target.substring(questionMark + 1)

        return HttpRequest(
            method = method,
            target = target,
            path = decode(rawPath),
            query = parseQuery(rawQuery),
            headers = headers
        )
    }

    fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) {
                out[decode(pair)] = ""
            } else {
                out[decode(pair.substring(0, eq))] = decode(pair.substring(eq + 1))
            }
        }
        return out
    }

    private fun decode(value: String): String =
        try {
            URLDecoder.decode(value, "UTF-8")
        } catch (e: IllegalArgumentException) {
            value // a stray '%' is not worth failing the request over
        }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            }
            if (b == '\n'.code) {
                val line = buffer.toString("UTF-8")
                return if (line.endsWith("\r")) line.dropLast(1) else line
            }
            if (buffer.size() >= MAX_LINE_BYTES) throw MalformedRequestException("header line too long")
            buffer.write(b)
        }
    }
}

object HttpWriter {

    private val REASONS = mapOf(
        200 to "OK",
        206 to "Partial Content",
        302 to "Found",
        400 to "Bad Request",
        401 to "Unauthorized",
        403 to "Forbidden",
        404 to "Not Found",
        405 to "Method Not Allowed",
        416 to "Range Not Satisfiable",
        500 to "Internal Server Error",
        503 to "Service Unavailable"
    )

    fun write(out: OutputStream, response: HttpResponse, includeBody: Boolean, keepAlive: Boolean) {
        val head = StringBuilder(256)
        head.append("HTTP/1.1 ").append(response.status).append(' ')
            .append(REASONS[response.status] ?: "Status").append("\r\n")
        head.append("Server: MTBAnalyzer\r\n")
        head.append("Content-Length: ").append(response.contentLength).append("\r\n")
        head.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        for ((name, value) in response.headers) {
            head.append(name).append(": ").append(value).append("\r\n")
        }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))

        // HEAD carries the same headers with no body, so callers can size a download.
        if (includeBody) response.body?.invoke(out)
        out.flush()
    }
}
