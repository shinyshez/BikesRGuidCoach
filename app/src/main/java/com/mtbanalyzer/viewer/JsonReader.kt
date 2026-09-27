package com.mtbanalyzer.viewer

import java.io.IOException

/**
 * Just enough JSON to read the viewer API's payloads on the viewer side.
 *
 * Hand-rolled for the same reason as [Json]: org.json is stubbed out in the JVM test
 * runtime, and the client is tested on the JVM against the server's own code. Objects
 * come back as `Map<String, Any?>`, arrays as `List<Any?>`, integers as `Long`, other
 * numbers as `Double`.
 */
internal class JsonReader private constructor(private val text: String) {

    companion object {
        private const val MAX_DEPTH = 32

        @Throws(IOException::class)
        fun parse(text: String): Any? {
            val reader = JsonReader(text)
            val value = reader.value(0)
            reader.skipWhitespace()
            if (reader.pos != text.length) reader.fail("Trailing data")
            return value
        }

        /** [parse], requiring an object at the top. */
        @Throws(IOException::class)
        @Suppress("UNCHECKED_CAST")
        fun parseObject(text: String): Map<String, Any?> =
            parse(text) as? Map<String, Any?> ?: throw IOException("Expected a JSON object")
    }

    private var pos = 0

    private fun value(depth: Int): Any? {
        if (depth > MAX_DEPTH) fail("Nested too deeply")
        skipWhitespace()
        if (pos >= text.length) fail("Unexpected end")
        return when (val ch = text[pos]) {
            '{' -> obj(depth)
            '[' -> array(depth)
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (ch == '-' || ch in '0'..'9') number() else fail("Unexpected '$ch'")
        }
    }

    private fun obj(depth: Int): Map<String, Any?> {
        pos++ // {
        val out = LinkedHashMap<String, Any?>()
        skipWhitespace()
        if (peek() == '}') {
            pos++
            return out
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') fail("Expected a key")
            val key = string()
            skipWhitespace()
            expect(':')
            out[key] = value(depth + 1)
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return out
                }
                else -> fail("Expected ',' or '}'")
            }
        }
    }

    private fun array(depth: Int): List<Any?> {
        pos++ // [
        val out = ArrayList<Any?>()
        skipWhitespace()
        if (peek() == ']') {
            pos++
            return out
        }
        while (true) {
            out.add(value(depth + 1))
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return out
                }
                else -> fail("Expected ',' or ']'")
            }
        }
    }

    private fun string(): String {
        pos++ // opening quote
        val out = StringBuilder()
        while (true) {
            if (pos >= text.length) fail("Unterminated string")
            when (val ch = text[pos++]) {
                '"' -> return out.toString()
                '\\' -> {
                    if (pos >= text.length) fail("Unterminated escape")
                    when (val esc = text[pos++]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) fail("Short \\u escape")
                            val code = text.substring(pos, pos + 4).toIntOrNull(16)
                                ?: fail("Bad \\u escape")
                            out.append(code.toChar())
                            pos += 4
                        }
                        else -> fail("Bad escape '\\$esc'")
                    }
                }
                else -> if (ch < ' ') fail("Control character in string") else out.append(ch)
            }
        }
    }

    private fun number(): Number {
        val start = pos
        if (peek() == '-') pos++
        while (pos < text.length && text[pos] in "0123456789.eE+-") pos++
        val raw = text.substring(start, pos)
        return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: fail("Bad number '$raw'")
    }

    private fun literal(word: String, result: Any?): Any? {
        if (!text.startsWith(word, pos)) fail("Unexpected token")
        pos += word.length
        return result
    }

    private fun expect(ch: Char) {
        if (peek() != ch) fail("Expected '$ch'")
        pos++
    }

    private fun peek(): Char? = if (pos < text.length) text[pos] else null

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun fail(message: String): Nothing = throw IOException("$message at $pos")
}

/** Typed access to a parsed object, failing loudly on the shape the API promises. */
internal fun Map<String, Any?>.long(key: String): Long =
    (this[key] as? Number)?.toLong() ?: throw IOException("Missing number '$key'")

internal fun Map<String, Any?>.string(key: String): String =
    this[key] as? String ?: throw IOException("Missing string '$key'")

internal fun Map<String, Any?>.optString(key: String): String? = this[key] as? String
