package com.mtbanalyzer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class HttpParserTest {

    private fun parse(raw: String) =
        HttpParser.readRequest(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))

    @Test
    fun `parses a plain request`() {
        val request = parse(
            "GET /api/clips HTTP/1.1\r\nHost: 192.168.43.1:8080\r\nAccept: */*\r\n\r\n"
        )!!
        assertEquals("GET", request.method)
        assertEquals("/api/clips", request.path)
        assertEquals("192.168.43.1:8080", request.header("host"))
        assertEquals("*/*", request.header("Accept"))
    }

    @Test
    fun `header lookup is case insensitive`() {
        val request = parse("GET / HTTP/1.1\r\nRANGE: bytes=0-10\r\n\r\n")!!
        assertEquals("bytes=0-10", request.header("range"))
    }

    @Test
    fun `splits and decodes the query`() {
        val request = parse("GET /api/clips?since=1700&t=a%20b HTTP/1.1\r\n\r\n")!!
        assertEquals("/api/clips", request.path)
        assertEquals("1700", request.query["since"])
        assertEquals("a b", request.query["t"])
    }

    @Test
    fun `reads a single cookie out of the header`() {
        val request = parse("GET / HTTP/1.1\r\nCookie: other=1; mtbvl=secret-token\r\n\r\n")!!
        assertEquals("secret-token", request.cookie("mtbvl"))
        assertNull(request.cookie("absent"))
    }

    @Test
    fun `host name drops the port`() {
        assertEquals("192.168.43.1", parse("GET / HTTP/1.1\r\nHost: 192.168.43.1:8080\r\n\r\n")!!.hostName())
        assertEquals("localhost", parse("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")!!.hostName())
    }

    @Test
    fun `ipv6 host keeps its address`() {
        assertEquals("::1", parse("GET / HTTP/1.1\r\nHost: [::1]:8080\r\n\r\n")!!.hostName())
    }

    @Test
    fun `clean EOF returns null rather than throwing`() {
        assertNull(parse(""))
    }

    @Test(expected = MalformedRequestException::class)
    fun `a broken request line is rejected`() {
        parse("NONSENSE\r\n\r\n")
    }

    @Test(expected = MalformedRequestException::class)
    fun `a broken header line is rejected`() {
        parse("GET / HTTP/1.1\r\nnot-a-header\r\n\r\n")
    }
}
